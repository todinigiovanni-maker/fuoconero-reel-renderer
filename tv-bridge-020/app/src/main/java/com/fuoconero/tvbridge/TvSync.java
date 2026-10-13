package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.*;
import android.media.tv.TvContract;
import android.net.Uri;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;

public final class TvSync {
    public interface Progress { void say(String text); }
    private static final String PREFS="tvbridge";
    private static final String CHANNEL_NAME="Fuoconero · Trakt 0.2.9 TEST";
    private static class Item {
        String id,title,description,image,imdb;
        boolean episode;
        double progress=-1;
        int runtimeMinutes=0;
        String season="",episodeNumber="",episodeTitle="";
        String nextSeason="",nextEpisodeNumber="",nextEpisodeTitle="";
        int episodesRemaining=-1,nextRuntimeMinutes=0;
        int episodesWatched=-1,episodesTotal=-1;
    }
    private TvSync(){}
    public static void perform(Context ctx,Progress notify)throws Exception {
        TraktApi trakt=new TraktApi(ctx);
        // Two API requests maximum: recent history plus playback percentages.
        LinkedHashMap<String,Item> items=new LinkedHashMap<>();
        JSONArray history=trakt.getArray("/sync/history?limit=60&extended=full");
        ingest(trakt,history,items,false,30);
        boolean complete=true;
        int playbackEntries=-1, playbackWithProgress=0;
        try {
            JSONArray playback=trakt.getArray("/sync/playback?extended=full");
            playbackEntries=playback.length();
            for(int i=0;i<playback.length();i++){
                JSONObject event=playback.optJSONObject(i);
                if(event!=null && event.optDouble("progress",-1)>0
                    && event.optDouble("progress",-1)<100)playbackWithProgress++;
            }
            mergePlayback(playback,items,30);
        } catch(TraktApi.ApiError e) {
            if(e.code==403 || e.code==404) {
                notify.say("Trakt non rende disponibile lo stato delle riproduzioni in pausa.");
            } else if(e.code==429 && !items.isEmpty()) {
                complete=false;
                notify.say("Trakt temporaneamente limitato: conservo tutte le schede precedenti.");
            } else throw e;
        }
        if(items.isEmpty())throw new Exception("Trakt non ha restituito film o episodi recenti. La riga esistente non è stata modificata.");
        int watchedShows=-1, showsWithRemaining=0,showsWithNext=0;
        try {
            // July 2026 Trakt change: watched episode breakdown needs extended=progress.
            // Limit the size: one summary page, not 30 individual metadata requests.
            JSONArray watched=trakt.getArray("/sync/watched/shows?page=1&limit=100&extended=progress");
            watchedShows=watched.length();
            addRemaining(watched,items);
        }catch(TraktApi.ApiError e){
            // A secondary enrichment API must never destroy cards already obtained.
            complete=false;
            notify.say("Trakt: riepilogo serie non disponibile ("+e.getMessage()+"). Conservo tutte le schede.");
        }catch(Exception e){
            complete=false;
            android.util.Log.w("FuoconeroTV","Watched summary not available",e);
        }
        // At most three show detail calls per synchronization, the other data is
        // cached. Each reply contains the server-confirmed next_episode field.
        // Avoid high-volume Trakt requests that previously triggered HTTP 429.
        int lookups=0;
        for(Item item:items.values()){
            if(!item.episode)continue;
            if(loadNextCache(ctx,item))continue;
            if(lookups>=3)continue;
            if(item.episodesRemaining==0)continue;
            String showId=item.id.startsWith("show_")?item.id.substring(5):"";
            if(!showId.matches("[a-zA-Z0-9_-]{1,80}"))continue;
            try{
                JSONObject data=trakt.get("/shows/"+showId+"/progress/watched");
                applyNextDetails(item,data);
                storeNextCache(ctx,item,data);
                lookups++;
            }catch(TraktApi.ApiError e){
                complete=false;
                android.util.Log.w("FuoconeroTV","Trakt up-next info limited: "+e.getMessage());
                if(e.code==429)break;
                lookups++;
            }catch(Exception e){
                android.util.Log.w("FuoconeroTV","Trakt up-next lookup failed",e);
                lookups++;
            }
        }
        for(Item item:items.values()){
            if(item.episode && item.episodesRemaining>=0)showsWithRemaining++;
            if(item.episode && !item.nextSeason.isEmpty())showsWithNext++;
        }
        notify.say("Trakt: "+items.size()+" titoli. Scarico le locandine...");
        ArrayList<Item> cards=new ArrayList<>(items.values());
        int realPosters=0,progressCards=0,progressWithRuntime=0,progressImages=0,nextPosters=0;
        int seriesBars=0,seriesWithoutProgress=0;
        for(Item item:cards) {
            if(imageFor(ctx,item))realPosters++;
            if(item.progress>0 && item.progress<100){
                progressCards++;
                if(item.runtimeMinutes>0)progressWithRuntime++;
                if(drawProgressPoster(ctx,item))progressImages++;
            }else if(item.episode && item.episodesRemaining>=0){
                if(drawNextPoster(ctx,item))nextPosters++;
                if(item.episodesTotal>0 && item.episodesWatched>=0)seriesBars++;
                else seriesWithoutProgress++;
            }
        }
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putInt("last_playback_items",playbackEntries)
            .putInt("last_playback_with_progress",playbackWithProgress)
            .putInt("last_cards_with_progress",progressCards)
            .putInt("last_cards_with_runtime",progressWithRuntime)
            .putInt("last_progress_posters",progressImages)
            .putInt("last_watched_shows",watchedShows)
            .putInt("last_shows_with_remaining",showsWithRemaining)
            .putInt("last_shows_with_next",showsWithNext)
            .putInt("last_next_posters",nextPosters)
            .putInt("last_series_bars",seriesBars)
            .putInt("last_series_missing_progress",seriesWithoutProgress).apply();
        long channel=channel(ctx);
        updateCards(ctx,channel,cards,complete);
        int visible=countCards(ctx,channel);
        SyncJob.schedule(ctx);
        notify.say("Trakt: "+cards.size()+" titoli. Schede realmente presenti nel canale Android TV: "+visible+
            ".\nLocandine reali in cache: "+realPosters+"; segnaposto: "+(cards.size()-realPosters)+
            (complete?"":"\nSincronizzazione parziale: non ho eliminato le vecchie schede.")+
            "\nTrakt playback: "+playbackEntries+"; con percentuale: "+playbackWithProgress+
            "; schede con percentuale: "+progressCards+"; con durata: "+progressWithRuntime+
            "; poster con barra: "+progressImages+
            "; riepiloghi serie: "+showsWithRemaining+
            "; prossimi episodi verificati: "+showsWithNext+
            "; barre avanzamento serie: "+seriesBars+
            "\nIl tempo indicato nelle schede delle serie e' la durata dell'episodio, non la posizione di riproduzione.");
    }
    public static String diagnose(Context ctx)throws Exception {
        long id=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("channel_id",-1);
        if(id<0)return "Nessuna riga Fuoconero ancora creata: avvia prima la sincronizzazione.";
        int cards=0,browsable=0,withProgress=0,withPoster=0;
        Set<String> intents=new HashSet<>(),ids=new HashSet<>();
        try(Cursor cur=ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
            new String[]{TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_CONTENT_ID,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_BROWSABLE,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS
            },null,null,null)){
            if(cur!=null)while(cur.moveToNext()){
                if(cur.getLong(0)!=id)continue;
                String internal=cur.getString(1);
                if(internal==null||!internal.startsWith("fuoconero:"))continue;
                cards++;
                if(cur.getString(2)!=null)intents.add(cur.getString(2));
                if(cur.getString(3)!=null)ids.add(cur.getString(3));
                if(cur.getString(4)!=null)withPoster++;
                if(!cur.isNull(5)&&cur.getInt(5)!=0)browsable++;
                if(!cur.isNull(6)&&cur.getLong(6)>0)withProgress++;
            }
        }
        SharedPreferences stats=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return "Schede Android TV: "+cards+
            "\nCollegamenti distinti: "+intents.size()+
            "\nContent ID distinti: "+ids.size()+
            "\nPoster con URI: "+withPoster+
            "\nSchede browsable: "+browsable+
            "\nBarra nativa Android TV: "+withProgress+
            "\nElementi playback da Trakt: "+stats.getInt("last_playback_items",-1)+
            "\nElementi playback con %: "+stats.getInt("last_playback_with_progress",0)+
            "\nSchede con %: "+stats.getInt("last_cards_with_progress",0)+
            "\nSchede con % e durata: "+stats.getInt("last_cards_with_runtime",0)+
            "\nLocandine con barra visibile: "+stats.getInt("last_progress_posters",0)+
            "\nSerie esaminate da Trakt: "+stats.getInt("last_watched_shows",-1)+
            "\nSchede con episodi restanti: "+stats.getInt("last_shows_with_remaining",0)+
            "\nSchede con prossimo episodio certo: "+stats.getInt("last_shows_with_next",0)+
            "\nPoster con info prossima puntata: "+stats.getInt("last_next_posters",0)+
            "\nBarre avanzamento SERIE: "+stats.getInt("last_series_bars",0)+
            "\nSerie senza % affidabile: "+stats.getInt("last_series_missing_progress",0)+
            "\nLa barra sul poster appare solo se l'API Trakt comunica un progresso parziale.";
    }
    public static int countCards(Context c) throws Exception {
        long id=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("channel_id",-1);
        if(id<0)return 0;
        return countCards(c,id);
    }
    private static int countCards(Context c,long channel) throws Exception {
        int count=0;
        try(Cursor cur=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                    TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID},null,null,null)){
            if(cur!=null)while(cur.moveToNext()){
                if(cur.getLong(0)==channel && cur.getString(1)!=null
                    && cur.getString(1).startsWith("fuoconero:")) count++;
            }
        }
        return count;
    }
    private static void ingest(TraktApi api,JSONArray arr,Map<String,Item> items,boolean paused,int limit)throws Exception{
        for(int i=0;i<arr.length() && items.size()<limit;i++){
            JSONObject entry=arr.optJSONObject(i);
            if(entry==null)continue;
            String type=entry.optString("type","");
            boolean ep="episode".equals(type);
            if(!ep&&!"movie".equals(type))continue;
            JSONObject media=entry.optJSONObject(ep?"show":"movie");
            if(media==null)continue;
            JSONObject ids=media.optJSONObject("ids");
            if(ids==null)continue;
            String rawId=ids.optString("trakt",ids.optString("slug",""));
            if(rawId.isEmpty())continue;
            String key=(ep?"show_":"movie_")+rawId;
            if(items.containsKey(key))continue;
            Item t=new Item();t.episode=ep;t.id=key;
            t.title=media.optString("title","Senza titolo");t.imdb=ids.optString("imdb","");
            t.runtimeMinutes=Math.max(0,media.optInt("runtime",0)); // show's average episode runtime is a fallback
            JSONObject episode=entry.optJSONObject("episode");
            if(ep && episode!=null){
                t.season=String.valueOf(episode.optInt("season",0));
                t.episodeNumber=String.valueOf(episode.optInt("number",0));
                t.episodeTitle=episode.optString("title","");
                int episodeRuntime=Math.max(0,episode.optInt("runtime",0));
                if(episodeRuntime>0)t.runtimeMinutes=episodeRuntime;
                String code=String.format(Locale.ITALY,"S%02dE%02d",episode.optInt("season"),episode.optInt("number"));
                t.description=(paused?"In pausa: ":"Ultimo episodio visto: ")+code+" · "+t.episodeTitle;
            }else t.description=paused?"Riproduzione in corso":"Nel tuo storico Trakt";
            t.image=posterUrl(media);
            // Trakt usually omits poster URLs. Prefer the existing IMDb image fallback;
            // never issue dozens of individual Trakt detail requests.
            if(t.image.isEmpty()){ 
                String imdb=ids.optString("imdb","");
                if(imdb.matches("tt[0-9]{5,12}"))
                    t.image="https://images.metahub.space/poster/medium/"+imdb+"/img";
            }
            items.put(key,t);
        }
    }
    private static void mergePlayback(JSONArray arr,LinkedHashMap<String,Item> items,int max)throws Exception {
        for(int i=0;i<arr.length();i++){
            JSONObject entry=arr.optJSONObject(i);
            if(entry==null)continue;
            boolean episode="episode".equals(entry.optString("type",""));
            if(!episode && !"movie".equals(entry.optString("type","")))continue;
            JSONObject media=entry.optJSONObject(episode?"show":"movie");
            JSONObject ids=media==null?null:media.optJSONObject("ids");
            if(ids==null)continue;
            String rawId=ids.optString("trakt",ids.optString("slug",""));
            if(rawId.isEmpty())continue;
            String key=(episode?"show_":"movie_")+rawId;
            Item item=items.get(key);
            if(item==null){
                if(items.size()>=max)continue;
                ingest(null,new JSONArray().put(entry),items,true,max);
                item=items.get(key);
            }
            if(item==null)continue;
            JSONObject ep=entry.optJSONObject("episode");
            if(episode && ep!=null){
                item.season=String.valueOf(ep.optInt("season",0));
                item.episodeNumber=String.valueOf(ep.optInt("number",0));
                item.episodeTitle=ep.optString("title","");
                int episodeRuntime=Math.max(0,ep.optInt("runtime",0));
                if(episodeRuntime>0)item.runtimeMinutes=episodeRuntime;
                else if(item.runtimeMinutes<=0)item.runtimeMinutes=Math.max(0,media.optInt("runtime",0));
                String code=String.format(Locale.ITALY,"S%02dE%02d",ep.optInt("season"),ep.optInt("number"));
                item.description="In pausa: "+code+" · "+item.episodeTitle;
            } else if(!episode) {
                item.runtimeMinutes=Math.max(0,media.optInt("runtime",item.runtimeMinutes));
                item.description="Film in pausa";
            }
            double p=entry.optDouble("progress",-1);
            if(!Double.isNaN(p) && p>=0 && p<=100){
                item.progress=p;
                item.description+=" · "+Math.round(p)+"% riprodotto";
            }
        }
    }
    private static String posterUrl(JSONObject media){
        JSONObject imgs=media.optJSONObject("images");
        if(imgs==null)return "";
        JSONArray poster=imgs.optJSONArray("poster");
        if(poster==null||poster.length()==0)return "";
        String value=poster.optString(0,"");
        if(value.startsWith("//"))value="https:"+value;
        else if(!value.startsWith("https://"))value="https://"+value;
        // Restrict image source; do not proxy arbitrary origins.
        try {
            URL u=new URL(value);
            String host=u.getHost().toLowerCase(Locale.ROOT);
            if(!host.equals("walter-r2.trakt.tv")&&!host.endsWith(".trakt.tv"))return "";
            if(!"https".equalsIgnoreCase(u.getProtocol()))return "";
        }catch(Exception e){return "";}
        return value;
    }
    private static boolean imageFor(Context c,Item item)throws Exception {
        File dir=new File(c.getFilesDir(),"posters");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Impossibile creare cache locandine");
        File destination=new File(dir,item.id+".png");
        File realMarker=new File(dir,item.id+".real");
        File lastAttempt=new File(dir,item.id+".attempt");
        long age=System.currentTimeMillis()-destination.lastModified();
        if(destination.exists() && destination.length()>1000 && realMarker.exists()
                && age<7L*86400000)return true;
        // A cached placeholder is NOT a real poster. Retry occasionally, not on every sync.
        if(destination.exists() && destination.length()>1000 && !realMarker.exists()
                && lastAttempt.exists()
                && System.currentTimeMillis()-lastAttempt.lastModified()<6L*3600000L)return false;
        Bitmap bmp=null;
        if(!item.image.isEmpty()){
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(item.image).openConnection();
                conn.setConnectTimeout(9000);conn.setReadTimeout(9000);
                if(conn.getResponseCode()==200){
                    try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] data=new byte[8192];int n,total=0;
                        while((n=in.read(data))!=-1){
                            total+=n;if(total>3*1024*1024)throw new IOException("Poster too large");
                            out.write(data,0,n);
                        }
                        byte[] all=out.toByteArray();
                        bmp=BitmapFactory.decodeByteArray(all,0,all.length);
                    }
                }
            }catch(Exception ignored){}
            finally{if(conn!=null)conn.disconnect();}
        }
        if(bmp==null && item.imdb!=null && item.imdb.matches("tt[0-9]{5,12}")){
            String fallback="https://images.metahub.space/poster/medium/"+item.imdb+"/img";
            HttpURLConnection con=null;
            try{
                // Avoid downloading the same MetaHub URL twice in one attempt.
                if(fallback.equals(item.image))throw new IOException("Poster URL già provato");
                con=(HttpURLConnection)new URL(fallback).openConnection();
                con.setConnectTimeout(9000);con.setReadTimeout(11000);
                if(con.getResponseCode()==200){
                    String mime=con.getContentType();
                    if(mime==null||mime.startsWith("image/")){
                        try(InputStream in=con.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                            byte[] buf=new byte[8192];int n,total=0;
                            while((n=in.read(buf))!=-1){total+=n;if(total>4*1024*1024)throw new IOException("Poster troppo grande");out.write(buf,0,n);}
                            byte[] bytes=out.toByteArray();
                            bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                        }
                    }
                }
            }catch(Exception ignored){} finally{if(con!=null)con.disconnect();}
        }
        // Never overwrite a previously visible image with a placeholder on a network failure.
        if(bmp==null && destination.isFile() && destination.length()>1000) {
            if(!lastAttempt.exists())lastAttempt.createNewFile();
            lastAttempt.setLastModified(System.currentTimeMillis());
            android.util.Log.w("FuoconeroTV","Download locandina fallito, mantengo cache per "+item.title);
            return realMarker.exists();
        }
        boolean real=bmp!=null;
        if(!real)android.util.Log.w("FuoconeroTV","Poster assente per "+item.title+" (IMDb="+item.imdb+")");
        if(bmp==null){
            bmp=Bitmap.createBitmap(450,675,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bmp);canvas.drawColor(Color.rgb(19,23,38));
            Paint p=new Paint(3);p.setColor(0xffef7844);p.setTextSize(32);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            canvas.drawText("FUOCONERO",30,85,p);
            p.setTextSize(29);p.setColor(Color.WHITE);
            String title=item.title.length()>23?item.title.substring(0,22)+"…":item.title;
            canvas.drawText(title,30,180,p);
        }
        File temp=new File(dir,item.id+".partial");
        try(FileOutputStream o=new FileOutputStream(temp)){
            if(!bmp.compress(Bitmap.CompressFormat.PNG,90,o))throw new IOException("Poster PNG fallito");
        }
        bmp.recycle();
        if(destination.exists()&&!destination.delete())throw new IOException("Impossibile sostituire locandina");
        if(!temp.renameTo(destination))throw new IOException("Salvataggio locandina fallito");
        // Markers allow us to distinguish placeholder PNGs from successfully downloaded images.
        if(real) {
            if(!realMarker.exists())realMarker.createNewFile();
            if(lastAttempt.exists())lastAttempt.delete();
        } else {
            if(realMarker.exists())realMarker.delete();
            if(!lastAttempt.exists())lastAttempt.createNewFile();
            lastAttempt.setLastModified(System.currentTimeMillis());
        }
        return real;
    }
    // Progress data is private per user and no per-show endpoints are fetched in bulk.
    private static void addRemaining(JSONArray watched,Map<String,Item> items){
        for(int i=0;i<watched.length();i++){
            JSONObject obj=watched.optJSONObject(i);
            if(obj==null)continue;
            JSONObject show=obj.optJSONObject("show");
            JSONObject ids=show==null?null:show.optJSONObject("ids");
            if(ids==null)continue;
            String id=ids.optString("trakt",ids.optString("slug",""));
            Item item=items.get("show_"+id);
            if(item==null)continue;
            int aired=show.optInt("aired_episodes",-1);
            JSONArray seasons=obj.optJSONArray("seasons");
            if(aired<0 || seasons==null)continue;
            int watchedCount=0;
            Set<String> seen=new HashSet<>();
            for(int j=0;j<seasons.length();j++){
                JSONObject season=seasons.optJSONObject(j);
                if(season==null)continue;
                int seasonNo=season.optInt("number",-1);
                if(seasonNo<1)continue; // Trakt excludes specials in regular aired counts.
                JSONArray episodes=season.optJSONArray("episodes");
                if(episodes==null)continue;
                for(int k=0;k<episodes.length();k++){
                    JSONObject episode=episodes.optJSONObject(k);
                    if(episode==null)continue;
                    int number=episode.optInt("number",-1);
                    if(number<1)continue;
                    if(episode.optInt("plays",0)>0 || episode.optBoolean("completed",false)){
                        if(seen.add(seasonNo+":"+number))watchedCount++;
                    }
                }
            }
            if(watchedCount<=aired){
                item.episodesWatched=watchedCount;
                item.episodesTotal=aired;
                item.episodesRemaining=aired-watchedCount;
            }
        }
    }
    private static void applyNextDetails(Item item,JSONObject detail){
        if(detail==null)return;
        int aired=detail.optInt("aired",-1),completed=detail.optInt("completed",-1);
        if(aired>0 && completed>=0 && completed<=aired){
            item.episodesWatched=completed;
            item.episodesTotal=aired;
            item.episodesRemaining=aired-completed;
        }
        JSONObject next=detail.optJSONObject("next_episode");
        if(next!=null && next.optInt("season",-1)>0 && next.optInt("number",-1)>0){
            item.nextSeason=String.format(Locale.ITALY,"%02d",next.optInt("season"));
            item.nextEpisodeNumber=String.format(Locale.ITALY,"%02d",next.optInt("number"));
            item.nextEpisodeTitle=next.optString("title","");
            item.nextRuntimeMinutes=Math.max(0,next.optInt("runtime",0));
        }
    }
    private static boolean loadNextCache(Context ctx,Item item){
        try{
            SharedPreferences p=ctx.getSharedPreferences("tvbridge_next",Context.MODE_PRIVATE);
            String value=p.getString(item.id,null);
            if(value==null)return false;
            JSONObject cached=new JSONObject(value);
            long age=System.currentTimeMillis()-cached.optLong("cached_at",0);
            if(age<0||age>=6L*3600L*1000L)return false;
            if(!cached.optString("last_season","").equals(item.season))return false;
            if(!cached.optString("last_episode","").equals(item.episodeNumber))return false;
            applyNextDetails(item,cached.optJSONObject("data"));
            return true;
        }catch(Exception e){return false;}
    }
    private static void storeNextCache(Context ctx,Item item,JSONObject detail){
        try{
            JSONObject cache=new JSONObject();
            cache.put("cached_at",System.currentTimeMillis());
            cache.put("last_season",item.season);
            cache.put("last_episode",item.episodeNumber);
            cache.put("data",detail);
            ctx.getSharedPreferences("tvbridge_next",Context.MODE_PRIVATE)
                .edit().putString(item.id,cache.toString()).apply();
        }catch(Exception ignored){}
    }
    private static boolean drawNextPoster(Context ctx,Item item){
        if(!item.episode || item.episodesRemaining<0)return false;
        File folder=new File(ctx.getFilesDir(),"posters");
        File original=new File(folder,item.id+".png");
        File output=new File(folder,item.id+"_next.png");
        if(!original.isFile())return false;
        Bitmap input=null,bitmap=null;
        try{
            input=BitmapFactory.decodeFile(original.getAbsolutePath());
            if(input==null)return false;
            bitmap=input.copy(Bitmap.Config.ARGB_8888,true);
            input.recycle();input=null;
            if(bitmap==null)return false;
            int width=bitmap.getWidth(),height=bitmap.getHeight();
            Canvas canvas=new Canvas(bitmap);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean hasNext=!item.nextSeason.isEmpty();
            boolean seriesProgress=item.episodesTotal>0 && item.episodesWatched>=0 &&
                item.episodesWatched<=item.episodesTotal;
            float top=height*(seriesProgress?0.68f:(hasNext?0.795f:0.84f));
            p.setColor(0xEC131C2C);
            canvas.drawRect(0,top,width,height,p);
            p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            p.setColor(0xFFFF9350);
            p.setTextSize(Math.max(16f,width*0.074f));
            String first=hasNext?("PROSSIMA  S"+item.nextSeason+"E"+item.nextEpisodeNumber):
                "DA CONTINUARE";
            canvas.drawText(first,width*0.055f,top+height*0.060f,p);
            p.setColor(Color.WHITE);
            p.setTextSize(Math.max(15f,width*0.060f));
            String remaining=item.episodesRemaining+" episodi da vedere";
            canvas.drawText(remaining,width*0.055f,top+height*0.112f,p);
            int runtime=item.nextRuntimeMinutes>0?item.nextRuntimeMinutes:item.runtimeMinutes;
            if(runtime>0){
                p.setTextSize(Math.max(13f,width*0.047f));
                p.setColor(0xFFD1D9E9);
                String label=(item.nextRuntimeMinutes>0?"Durata: ":"Durata media: ")+runtime+" min";
                canvas.drawText(label,width*0.055f,top+height*0.165f,p);
            }
            if(seriesProgress){
                double percent=100.0*item.episodesWatched/item.episodesTotal;
                p.setTextSize(Math.max(13f,width*0.048f));
                p.setColor(Color.WHITE);
                canvas.drawText("SERIE "+Math.round(percent)+"%  ("+
                    item.episodesWatched+"/"+item.episodesTotal+")",
                    width*0.055f,height*0.915f,p);
                float x=width*0.055f, topBar=height*0.944f, bottomBar=height*0.961f;
                float barWidth=width*0.89f;
                p.setColor(0xFF596276);
                canvas.drawRoundRect(x,topBar,x+barWidth,bottomBar,8,8,p);
                if(percent>0){
                    p.setColor(0xFFFF843F);
                    canvas.drawRoundRect(x,topBar,x+barWidth*(float)(percent/100.0),
                        bottomBar,8,8,p);
                }
            }
            File temp=new File(folder,item.id+"_next.tmp");
            boolean saved;
            try(FileOutputStream out=new FileOutputStream(temp)){
                saved=bitmap.compress(Bitmap.CompressFormat.PNG,90,out);
            }
            if(!saved){temp.delete();return false;}
            if(output.exists()&&!output.delete()){temp.delete();return false;}
            if(!temp.renameTo(output)){temp.delete();return false;}
            return true;
        }catch(Exception e){
            android.util.Log.w("FuoconeroTV","Poster up-next not available",e);
            return false;
        }finally{
            if(input!=null)input.recycle();
            if(bitmap!=null)bitmap.recycle();
        }
    }
    private static boolean drawProgressPoster(Context ctx,Item item){
        if(item.progress<=0 || item.progress>=100)return false;
        File folder=new File(ctx.getFilesDir(),"posters");
        File original=new File(folder,item.id+".png");
        File output=new File(folder,item.id+"_progress.png");
        if(!original.isFile())return false;
        Bitmap bmp=null;
        try{
            bmp=BitmapFactory.decodeFile(original.getAbsolutePath());
            if(bmp==null)return false;
            Bitmap editable=bmp.copy(Bitmap.Config.ARGB_8888,true);
            bmp.recycle();bmp=null;
            if(editable==null)return false;
            int w=editable.getWidth(),h=editable.getHeight();
            Canvas canvas=new Canvas(editable);
            Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xDD101828);
            canvas.drawRect(0,h*0.87f,w,h,bg);
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            text.setTextSize(Math.max(16f,w*0.068f));
            long percent=Math.round(item.progress);
            canvas.drawText(percent+"% visto",w*0.065f,h*0.93f,text);
            Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
            track.setColor(0xFF626977);
            float x=w*0.065f,barTop=h*0.95f,barBottom=h*0.97f,barWidth=w*0.87f;
            canvas.drawRoundRect(x,barTop,x+barWidth,barBottom,8,8,track);
            track.setColor(0xFFFF843F);
            canvas.drawRoundRect(x,barTop,x+barWidth*(float)(item.progress/100.0),
                barBottom,8,8,track);
            File temp=new File(folder,item.id+"_progress.tmp");
            boolean okay;
            try(FileOutputStream out=new FileOutputStream(temp)){
                okay=editable.compress(Bitmap.CompressFormat.PNG,90,out);
            }
            editable.recycle();
            if(!okay){temp.delete();return false;}
            if(output.exists()&&!output.delete()){temp.delete();return false;}
            if(!temp.renameTo(output)){temp.delete();return false;}
            return true;
        }catch(Exception ex){
            android.util.Log.w("FuoconeroTV","Progress poster failure",ex);
            return false;
        }finally{if(bmp!=null)bmp.recycle();}
    }
    private static long channel(Context c)throws Exception {
        SharedPreferences prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long old=prefs.getLong("channel_id",-1);
        if(old>0) {
            try(Cursor q=c.getContentResolver().query(TvContract.buildChannelUri(old),
                new String[]{TvContract.Channels._ID},null,null,null)){
                if(q!=null&&q.moveToFirst())return old;
            }catch(Exception ignored){}
        }
        Intent app=new Intent(c,MainActivity.class);
        app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ContentValues v=new ContentValues();
        v.put(TvContract.Channels.COLUMN_TYPE,TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME,CHANNEL_NAME);
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,app.toUri(Intent.URI_INTENT_SCHEME));
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,"fuoconero-trakt-020");
        Uri result=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,v);
        if(result==null)throw new Exception("Android TV non ha creato il canale.");
        long id=ContentUris.parseId(result);
        prefs.edit().putLong("channel_id",id).apply();
        // Raster channel logo.
        Bitmap logo=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);
        Canvas can=new Canvas(logo);can.drawColor(0xff151929);
        Paint paint=new Paint(3);paint.setColor(0xfffb803e);paint.setTextSize(33);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        can.drawText("FUOCONERO",35,85,paint);
        paint.setColor(Color.WHITE);paint.setTextSize(26);
        can.drawText("TV BRIDGE",45,125,paint);
        try(OutputStream os=c.getContentResolver().openOutputStream(TvContract.buildChannelLogoUri(id))){
            if(os!=null)logo.compress(Bitmap.CompressFormat.PNG,90,os);
        }catch(Exception ignored){}
        logo.recycle();
        return id;
    }
    private static void updateCards(Context c,long channel,ArrayList<Item> cards,boolean complete)throws Exception {
        Map<String,Long> existing=new HashMap<>();
        ArrayList<Long> duplicates=new ArrayList<>();
        // Android TV explicitly forbids SQL selections for PreviewPrograms.
        try(Cursor cur=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms._ID,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID},null,null,null)){
            if(cur!=null){
                while(cur.moveToNext()){
                    if(cur.getLong(1)!=channel)continue;
                    String key=cur.getString(2);long id=cur.getLong(0);
                    if(key==null||!key.startsWith("fuoconero:"))continue;
                    if(existing.containsKey(key)) duplicates.add(id);
                    else existing.put(key,id);
                }
            }
        }
        Set<String> retained=new HashSet<>();
        for(int i=0;i<cards.size();i++){
            Item item=cards.get(i);
            String key="fuoconero:"+item.id;
            retained.add(key);
            ContentValues values=new ContentValues();
            values.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,channel);
            values.put(TvContract.PreviewPrograms.COLUMN_TYPE,item.episode?
                TvContract.PreviewPrograms.TYPE_TV_EPISODE:TvContract.PreviewPrograms.TYPE_MOVIE);
            values.put(TvContract.PreviewPrograms.COLUMN_TITLE,item.title);
            if(item.episode){
                StringBuilder d=new StringBuilder(item.description);
                if(!item.nextSeason.isEmpty()){
                    d.setLength(0);
                    d.append("Prossima S").append(item.nextSeason).append("E").append(item.nextEpisodeNumber);
                    if(!item.nextEpisodeTitle.isEmpty())d.append(" · ").append(item.nextEpisodeTitle);
                }
                if(item.episodesRemaining>=0)d.append(" · ").append(item.episodesRemaining).append(" episodi rimanenti");
                if(item.episodesTotal>0 && item.episodesWatched>=0)
                    d.append(" · Serie ").append(Math.round(100.0*item.episodesWatched/item.episodesTotal))
                        .append("% (").append(item.episodesWatched).append("/")
                        .append(item.episodesTotal).append(")");
                if(item.nextRuntimeMinutes>0)d.append(" · ").append(item.nextRuntimeMinutes).append(" min");
                else if(item.runtimeMinutes>0 && item.episodesRemaining>=0)d.append(" · durata media ").append(item.runtimeMinutes).append(" min");
                item.description=d.toString();
            }
            values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,item.description);
            values.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,key);
            values.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID,"trakt:"+item.id);
            if(item.episode && !item.nextSeason.isEmpty()){
                values.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,item.nextSeason);
                values.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,item.nextEpisodeNumber);
                values.put(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,item.nextEpisodeTitle);
            }else if(item.episode && !item.season.isEmpty()){
                values.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,item.season);
                values.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,item.episodeNumber);
                values.put(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,item.episodeTitle);
            }
            if(item.progress>=0 && item.runtimeMinutes>0){
                long total=(long)item.runtimeMinutes*60L*1000L;
                values.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,total);
                values.put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                    Math.min(total,Math.round(total*item.progress/100.0)));
            }
            values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT,999-i);
            File posterDir=new File(c.getFilesDir(),"posters");
            boolean hasProgress=item.progress>0 && item.progress<100 &&
                new File(posterDir,item.id+"_progress.png").isFile();
            boolean hasNext=!hasProgress && item.episode && item.episodesRemaining>=0 &&
                new File(posterDir,item.id+"_next.png").isFile();
            String imageName=item.id+(hasProgress?"_progress":hasNext?"_next":"")+".png";
            File imageFile=new File(posterDir,imageName);
            // Changed URI when a poster is regenerated so launcher image caches refresh.
            Uri poster=new Uri.Builder().scheme("content")
                .authority("com.fuoconero.tvbridge.test029.images")
                .appendPath("posters").appendPath(imageName)
                .appendQueryParameter("v",String.valueOf(imageFile.lastModified())).build();
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,poster.toString());
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,TvContract.PreviewPrograms.ASPECT_RATIO_2_3);
            Intent intent=new Intent(c,OpenContentActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(new Uri.Builder().scheme("fuoconero-tv").authority("item")
                .appendPath(item.id).build());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("title",item.title);
            intent.putExtra("episode",item.episode);
            intent.putExtra("season",!item.nextSeason.isEmpty()?item.nextSeason:item.season);
            intent.putExtra("episode_number",!item.nextEpisodeNumber.isEmpty()?item.nextEpisodeNumber:item.episodeNumber);
            values.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI,intent.toUri(Intent.URI_INTENT_SCHEME));
            Long old=existing.get(key);
            if(old!=null){
                // COLUMN_CHANNEL_ID is immutable, omit for update.
                values.remove(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID);
                c.getContentResolver().update(TvContract.buildPreviewProgramUri(old),values,null,null);
            }else{
                if(c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values)==null)
                    throw new Exception("Inserimento locandina fallito: "+item.title);
            }
        }
        if(complete)for(Map.Entry<String,Long> old:existing.entrySet())if(!retained.contains(old.getKey()))
            c.getContentResolver().delete(TvContract.buildPreviewProgramUri(old.getValue()),null,null);
        for(long id:duplicates)c.getContentResolver().delete(TvContract.buildPreviewProgramUri(id),null,null);
    }
    public static void removeChannel(Context c){
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long id=p.getLong("channel_id",-1);
        if(id>0)c.getContentResolver().delete(TvContract.buildChannelUri(id),null,null);
        p.edit().remove("channel_id").apply();
    }
}

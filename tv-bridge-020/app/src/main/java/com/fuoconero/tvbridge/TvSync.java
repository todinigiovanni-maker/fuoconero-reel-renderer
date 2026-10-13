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
    private static final String CHANNEL_NAME="Fuoconero · Trakt 0.2.5 TEST";
    private static class Item {
        String id,title,description,image,imdb;
        boolean episode;
        double progress=-1;
        int runtimeMinutes=0;
        String season="",episodeNumber="",episodeTitle="";
    }
    private TvSync(){}
    public static void perform(Context ctx,Progress notify)throws Exception {
        TraktApi trakt=new TraktApi(ctx);
        // Two API requests maximum: recent history plus playback percentages.
        LinkedHashMap<String,Item> items=new LinkedHashMap<>();
        JSONArray history=trakt.getArray("/sync/history?limit=60&extended=full");
        ingest(trakt,history,items,false,30);
        boolean complete=true;
        try {
            JSONArray playback=trakt.getArray("/sync/playback?extended=full");
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
        notify.say("Trakt: "+items.size()+" titoli. Scarico le locandine...");
        ArrayList<Item> cards=new ArrayList<>(items.values());
        int realPosters=0;
        for(Item item:cards) {
            if(imageFor(ctx,item))realPosters++;
        }
        long channel=channel(ctx);
        updateCards(ctx,channel,cards,complete);
        int visible=countCards(ctx,channel);
        SyncJob.schedule(ctx);
        notify.say("Trakt: "+cards.size()+" titoli. Schede realmente presenti nel canale Android TV: "+visible+
            ".\nLocandine reali in cache: "+realPosters+"; segnaposto: "+(cards.size()-realPosters)+
            (complete?"":"\nSincronizzazione parziale: non ho eliminato le vecchie schede.")+
            "\nLe schede aprono la HOME di Veezie. Se qui risultano 30 e Projectivy ne mostra una, controlla la configurazione del launcher.");
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
            t.runtimeMinutes=ep?0:Math.max(0,media.optInt("runtime",0));
            JSONObject episode=entry.optJSONObject("episode");
            if(ep && episode!=null){
                t.season=String.valueOf(episode.optInt("season",0));
                t.episodeNumber=String.valueOf(episode.optInt("number",0));
                t.episodeTitle=episode.optString("title","");
                t.runtimeMinutes=Math.max(0,episode.optInt("runtime",0));
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
                item.runtimeMinutes=Math.max(0,ep.optInt("runtime",0));
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
            values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,item.description);
            values.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,key);
            values.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID,"trakt:"+item.id);
            if(item.episode && !item.season.isEmpty()){
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
            Uri poster=Uri.parse("content://com.fuoconero.tvbridge.test025.images/posters/"+item.id+".png");
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,poster.toString());
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,TvContract.PreviewPrograms.ASPECT_RATIO_2_3);
            Intent intent=new Intent(c,OpenContentActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(new Uri.Builder().scheme("fuoconero-tv").authority("item")
                .appendPath(item.id).build());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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

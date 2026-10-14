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
    private static final java.util.concurrent.atomic.AtomicBoolean SYNC_BUSY=new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final String PREFS="tvbridge";
    private static final String CHANNEL_NAME="Fuoconero · Veezie/Trakt 0.4.3 TEST";
    private static class Item {
        String id,title,description,image,imdb;
        boolean episode;
        double progress=-1;
        long positionMs=-1,durationMs=-1;
        String progressSource="Trakt";
        long recentAt=0,watchedAt=0;
        String tmdb="";
        int runtimeMinutes=0;
        String season="",episodeNumber="",episodeTitle="";
        String nextSeason="",nextEpisodeNumber="",nextEpisodeTitle="";
        int episodesRemaining=-1,nextRuntimeMinutes=0;
        int episodesWatched=-1,episodesTotal=-1;
        String importedPosterUri="";
    }
    private TvSync(){}
    /**
     * Import existing Fuoconero channel cards from Android TV, WITHOUT a Trakt token.
     * Read-only towards the source channel, and will not change/delete old app data.
     * Only publicly published previews whose program/provider IDs match Fuoconero
     * are accepted. Poster copy uses the exported read-only image provider.
     */
    // Create an independently usable Veezie preview channel before attempting
    // any cross-app migration. Android TV ordinarily scopes provider reads to the
    // owning package; an import from another app may legitimately be unavailable.
    public static long ensureStarter(Context ctx)throws Exception {
        long id=channel(ctx);
        if(countCards(ctx,id)>0)return id;
        File directory=new File(ctx.getFilesDir(),"posters");
        if(!directory.exists()&&!directory.mkdirs())
            throw new Exception("Cartella locandine non disponibile");
        File image=new File(directory,"movie_openveezie.png");
        Bitmap bmp=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bmp);
        c.drawColor(0xff181e34);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffff8c48);
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextSize(74f);
        c.drawText("VEEZIE",54,290,p);
        p.setColor(Color.WHITE);p.setTextSize(39f);
        c.drawText("APRI CATALOGO",36,362,p);
        p.setTextSize(22f);
        c.drawText("Fuoconero TV Bridge",70,530,p);
        try(FileOutputStream out=new FileOutputStream(image)){
            if(!bmp.compress(Bitmap.CompressFormat.PNG,95,out))
                throw new Exception("Impossibile salvare la locandina Veezie");
        }finally{bmp.recycle();}
        Item item=new Item();
        item.id="movie_openveezie";
        item.title="Apri Veezie";
        item.description="Apri Veezie. Catalogo indipendente da Trakt; recupero altre schede da completare.";
        item.episode=false;
        ArrayList<Item> cards=new ArrayList<>();cards.add(item);
        updateCards(ctx,id,cards,false);
        return id;
    }
    public static void requestVisibility(Context ctx){
        long id=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("channel_id",-1L);
        if(id<=0)return;
        if(android.os.Build.VERSION.SDK_INT>=26){
            try{TvContract.requestChannelBrowsable(ctx,id);}
            catch(Exception e){android.util.Log.w("FuoconeroTV","Richiesta visibilita non supportata",e);}
        }
    }
    /**
     * Restore the 30 posters + media names from a local, user supplied ZIP.
     * This does not contact Trakt or touch any channel owned by another APK.
     */
    public static String importCatalogZip(Context ctx,InputStream source)throws Exception{
        File folder=new File(ctx.getFilesDir(),"posters");
        if(!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cartella locandine non accessibile");
        JSONObject manifest=null;
        HashSet<String> found=new HashSet<>();
        int photos=0;
        long totalBytes=0;
        byte[] buffer=new byte[16384];
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(
            new BufferedInputStream(source,32768))){
            java.util.zip.ZipEntry entry;
            int entries=0;
            while((entry=zip.getNextEntry())!=null){
                if(++entries>45)throw new IOException("Archivio con troppi file");
                String name=entry.getName();
                if("catalog.json".equals(name)){
                    if(manifest!=null)throw new IOException("Manifesto catalogo duplicato");
                    ByteArrayOutputStream content=new ByteArrayOutputStream();
                    int n;
                    while((n=zip.read(buffer))!=-1){
                        if(content.size()+n>64000)throw new IOException("Manifesto troppo grande");
                        content.write(buffer,0,n);
                    }
                    manifest=new JSONObject(content.toString("UTF-8"));
                    if(!"fuoconero-veezie-catalog-v1".equals(manifest.optString("format")))
                        throw new IOException("Formato catalogo non supportato");
                }else if(name.matches("posters/(show|movie)_[0-9]{1,10}\\.png")){
                    String filename=name.substring("posters/".length());
                    File target=new File(folder,filename);
                    File temp=new File(folder,filename+".import");
                    boolean okay=false;
                    long current=0;
                    try(FileOutputStream out=new FileOutputStream(temp)){
                        int n;
                        while((n=zip.read(buffer))!=-1){
                            current+=n;
                            totalBytes+=n;
                            if(current>2000000L||totalBytes>55000000L)
                                throw new IOException("Immagini troppo grandi");
                            out.write(buffer,0,n);
                        }
                    }
                    if(current<128)throw new IOException("Locandina vuota: "+filename);
                    // A PNG signature check avoids accepting a mislabeled or corrupt file.
                    try(FileInputStream input=new FileInputStream(temp)){
                        byte[] magic=new byte[8];
                        if(input.read(magic)!=8 ||
                            magic[0]!=(byte)137 || magic[1]!=80 ||
                            magic[2]!=78 || magic[3]!=71)
                            throw new IOException("Immagine PNG non valida: "+filename);
                    }
                    BitmapFactory.Options check=new BitmapFactory.Options();
                    check.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(temp.getAbsolutePath(),check);
                    if(check.outWidth<100 || check.outWidth>2000 ||
                        check.outHeight<100 || check.outHeight>3000)
                        throw new IOException("Dimensioni immagine non ammesse: "+filename);
                    if(target.exists()&&!target.delete())
                        throw new IOException("Locandina esistente bloccata");
                    okay=temp.renameTo(target);
                    if(!okay)throw new IOException("Non riesco a salvare "+filename);
                    found.add(filename);photos++;
                }else if("LEGGIMI.txt".equals(name)){
                    // Human-readable catalog note; ignore.
                }else{
                    throw new IOException("Archivio contiene un percorso imprevisto: "+name);
                }
                zip.closeEntry();
            }
        }
        if(manifest==null)throw new IOException("Il catalogo non contiene catalog.json");
        JSONArray list=manifest.optJSONArray("items");
        if(list==null || list.length()!=30 || photos!=30)
            throw new IOException("Attese 30 schede e 30 locandine; ricevute "+
                (list==null?0:list.length())+" schede e "+photos+" locandine");
        HashSet<String> ids=new HashSet<>();
        ArrayList<Item> cards=new ArrayList<>();
        for(int i=0;i<list.length();i++){
            JSONObject info=list.getJSONObject(i);
            String id=info.optString("id","");
            String title=info.optString("title","").trim();
            String type=info.optString("type","");
            String poster=info.optString("poster","");
            if(!id.matches("(show|movie)_[0-9]{1,10}") ||
                !poster.equals("posters/"+id+".png") ||
                !found.contains(id+".png") || title.length()<2 || title.length()>120 ||
                !ids.add(id) || !type.equals(id.startsWith("show_")?"show":"movie"))
                throw new IOException("Dati catalogo non validi: "+id);
            Item item=new Item();
            item.id=id;item.title=title;item.episode="show".equals(type);
            item.description=item.episode?
                "Catalogo Veezie: seleziona la puntata dall'elenco Episodi":
                "Catalogo Veezie: apri per cercare il film";
            cards.add(item);
        }
        long channel=channel(ctx);
        // The import is complete and verified: replace just the local preview.
        // Old Fuoconero TV Bridge applications and their channels are untouched.
        updateCards(ctx,channel,cards,true);
        int count=countCards(ctx,channel);
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putInt("import_count",cards.size())
            .putString("import_channel","Backup locale Veezie - 0.3.2")
            .putInt("import_posters_copied",photos)
            .putLong("import_timestamp",System.currentTimeMillis())
            .putBoolean("prefer_veezie",true)
            .apply();
        return "IMPORTAZIONE RIUSCITA: "+count+" schede Android TV, "+
            photos+" locandine locali.\nTrakt NON richiesto. "+
            "Apri Projectivy e verifica Fuoconero · Veezie 0.3.5 TEST.";
    }

    public static String importExisting(Context ctx)throws Exception {
        android.content.ContentResolver resolver=ctx.getContentResolver();
        long own=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getLong("channel_id",-1L);
        Map<Long,String> channels=new HashMap<>();
        try(Cursor cur=resolver.query(TvContract.Channels.CONTENT_URI,
            new String[]{TvContract.Channels._ID,TvContract.Channels.COLUMN_DISPLAY_NAME},
            null,null,null)){
            if(cur!=null)while(cur.moveToNext()){
                long id=cur.getLong(0);
                String display=cur.getString(1);
                if(id!=own && display!=null && display.startsWith("Fuoconero"))
                    channels.put(id,display);
            }
        }
        if(channels.isEmpty())
            throw new Exception("Non trovo righe Fuoconero precedenti nella TV. Le vecchie app devono restare installate.");
        class Previous {
            String id,title,poster;boolean episode;
        }
        Map<Long,LinkedHashMap<String,Previous>> sources=new HashMap<>();
        try(Cursor cur=resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
            new String[]{TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_TYPE},
            null,null,null)){
            if(cur!=null)while(cur.moveToNext()){
                long id=cur.getLong(0);
                if(!channels.containsKey(id))continue;
                String provider=cur.getString(1);
                if(provider==null || !provider.startsWith("fuoconero:"))continue;
                String itemId=provider.substring("fuoconero:".length());
                if(!itemId.matches("(show|movie)_[a-zA-Z0-9_-]{1,80}"))continue;
                String title=cur.getString(2);
                if(title==null || title.trim().isEmpty())continue;
                LinkedHashMap<String,Previous> row=sources.get(id);
                if(row==null){row=new LinkedHashMap<>();sources.put(id,row);}
                if(row.size()>=30 || row.containsKey(itemId))continue;
                Previous p=new Previous();
                p.id=itemId;p.title=title;p.poster=cur.getString(3);
                p.episode=itemId.startsWith("show_");
                row.put(itemId,p);
            }
        }
        long bestId=-1;int bestSize=0;
        for(Map.Entry<Long,LinkedHashMap<String,Previous>> entry:sources.entrySet()){
            int size=entry.getValue().size();
            if(size>bestSize || (size==bestSize && entry.getKey()>bestId)){
                bestSize=size;bestId=entry.getKey();
            }
        }
        if(bestId<0 || bestSize==0)
            throw new Exception("Non riesco a leggere locandine dalle vecchie versioni. Nessuna riga e' stata modificata.");
        ArrayList<Item> cards=new ArrayList<>();
        int copied=0,reused=0,missing=0;
        File posters=new File(ctx.getFilesDir(),"posters");
        if(!posters.exists()&&!posters.mkdirs())
            throw new Exception("Impossibile creare la cartella delle locandine.");
        for(Previous prev:sources.get(bestId).values()){
            Item item=new Item();
            item.id=prev.id;item.title=prev.title;item.episode=prev.episode;
            item.description=prev.episode?
                "Serie su Veezie · scegli la puntata nell'elenco Episodi":
                "Film su Veezie · apri per cercare il contenuto";
            boolean saved=false;
            if(prev.poster!=null && prev.poster.startsWith("content://")){
                try{
                    Uri source=Uri.parse(prev.poster);
                    String authority=source.getAuthority();
                    if(authority!=null && authority.startsWith("com.fuoconero.tvbridge.test")
                        && authority.endsWith(".images")){
                        // First copy the original clean poster, not stale Trakt overlays.
                        Uri base=new Uri.Builder().scheme("content").authority(authority)
                            .appendPath("posters").appendPath(item.id+".png").build();
                        File local=new File(posters,item.id+".png");
                        saved=copyPoster(ctx,base,local);
                        if(!saved)saved=copyPoster(ctx,source,local);
                        if(!saved)item.importedPosterUri=prev.poster;
                    }
                }catch(Exception ignored){ /* some providers may not export the file */ }
            }
            if(saved)copied++;
            else if(!item.importedPosterUri.isEmpty())reused++;
            else missing++;
            cards.add(item);
        }
        long channel=channel(ctx);
        // Partial-update behavior: NEVER delete anything in our own row on an import.
        updateCards(ctx,channel,cards,false);
        int visible=countCards(ctx,channel);
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putInt("import_count",cards.size())
            .putInt("import_posters_copied",copied)
            .putString("import_channel",channels.get(bestId))
            .putLong("import_timestamp",System.currentTimeMillis())
            .apply();
        return "Modalita Veezie pronta. Importate "+cards.size()+" schede da '"+
            channels.get(bestId)+"'.\nSchede presenti nella nuova riga: "+visible+
            "\nPoster indipendenti copiati: "+copied+
            "\nPoster condivisi con vecchia versione: "+reused+
            "\nPoster non disponibili: "+missing+
            "\nNessun account Trakt richiesto. Le vecchie righe non sono state modificate.";
    }

    private static boolean copyPoster(Context ctx,Uri source,File target){
        File temp=new File(target.getAbsolutePath()+".tmp");
        long count=0;boolean okay=false;
        try(InputStream input=ctx.getContentResolver().openInputStream(source)){
            if(input==null)return false;
            try(OutputStream output=new FileOutputStream(temp)){
                byte[] buffer=new byte[16384];
                int n;
                while((n=input.read(buffer))!=-1){
                    count+=n;
                    if(count>4000000L)throw new IOException("Locandina troppo grande");
                    output.write(buffer,0,n);
                }
            }
            if(count<128)return false;
            if(target.exists()&&!target.delete())return false;
            okay=temp.renameTo(target);
            return okay;
        }catch(Exception e){
            android.util.Log.w("FuoconeroTV","Copia poster non disponibile: "+source.getAuthority());
            return false;
        }finally{
            if(!okay)temp.delete();
        }
    }

    public static void perform(Context ctx,Progress notify)throws Exception {
        if(!SYNC_BUSY.compareAndSet(false,true)){
            notify.say("Una sincronizzazione Trakt e' gia' in corso.");
            return;
        }
        try{
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putLong("last_sync_started",System.currentTimeMillis()).apply();
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
            applyVeeziePlayback(ctx,item);
            if(imageFor(ctx,item))realPosters++;
            if(item.progress>=0 && item.progress<=100){
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
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putLong("last_sync_success",System.currentTimeMillis()).apply();
        notify.say("Trakt: "+cards.size()+" titoli. Schede realmente presenti nel canale Android TV: "+visible+
            ".\nLocandine reali in cache: "+realPosters+"; segnaposto: "+(cards.size()-realPosters)+
            (complete?"":"\nSincronizzazione parziale: non ho eliminato le vecchie schede.")+
            "\nTrakt playback: "+playbackEntries+"; con percentuale: "+playbackWithProgress+
            "; schede con percentuale: "+progressCards+"; con durata: "+progressWithRuntime+
            "; poster con barra: "+progressImages+
            "; riepiloghi serie: "+showsWithRemaining+
            "; prossimi episodi verificati: "+showsWithNext+
            "; barre avanzamento serie: "+seriesBars+
            "\nAggiornamento su ritorno da Veezie attivo quando l'Accessibilita' e' abilitata.");
        }finally{
            SYNC_BUSY.set(false);
        }
    }
    public static String diagnose(Context ctx)throws Exception {
        long id=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("channel_id",-1);
        if(id<0)return "Nessuna riga Veezie ancora creata. Premi 1 per importare le locandine senza Trakt.";
        int cards=0,browsable=0,withProgress=0,withPoster=0;
        int channelVisible=-1;
        try(Cursor ch=ctx.getContentResolver().query(TvContract.buildChannelUri(id),
            new String[]{TvContract.Channels.COLUMN_BROWSABLE},null,null,null)){
            if(ch!=null&&ch.moveToFirst())channelVisible=ch.getInt(0);
        }catch(Exception ignored){}
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
        long lastSync=stats.getLong("last_sync_success",0L);
        long queued=stats.getLong("refresh_earliest_at",0L);
        return "Canale Veezie visibile Android TV: "+
            (channelVisible<0?"non determinato":channelVisible==1?"SI":"NO (premi Rendi visibile)")+
            "\nFonte: Veezie (navigazione) / dati importati dalle vecchie righe"+
            "\nFonte locandine: "+stats.getString("import_channel","non ancora importate")+
            "\nLocandine importate: "+stats.getInt("import_count",0)+
            " (copiate: "+stats.getInt("import_posters_copied",0)+")"+
            "\nSchede Android TV: "+cards+
            "\nCollegamenti distinti: "+intents.size()+
            "\nContent ID distinti: "+ids.size()+
            "\nPoster con URI: "+withPoster+
            "\nSchede browsable: "+browsable+
            "\nBarra nativa Android TV: "+withProgress+
            "\nUltima sincronizzazione: "+(lastSync>0?
                new java.text.SimpleDateFormat("dd/MM HH:mm:ss",Locale.ITALY).format(new Date(lastSync)):
                "mai")+
            "\nAggiornamento dopo Veezie richiesto: "+(queued>0?
                new java.text.SimpleDateFormat("dd/MM HH:mm:ss",Locale.ITALY).format(new Date(queued)):
                "non ancora")+
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
            "\nLa barra SERIE importata/Trakt non misura i minuti della puntata."+
            "\nIl player Veezie va verificato con la diagnostica dei tempi.";
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
            t.recentAt=eventTime(entry.optString(paused?"paused_at":"watched_at",""));
            t.watchedAt=paused?0:t.recentAt;
            t.title=media.optString("title","Senza titolo");t.imdb=ids.optString("imdb","");
            t.tmdb=ids.optString("tmdb","");
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
            long pausedAt=eventTime(entry.optString("paused_at",""));
            if(episode && ep!=null && item.watchedAt>pausedAt && pausedAt>0 &&
               item.season.equals(String.valueOf(ep.optInt("season"))) && item.episodeNumber.equals(String.valueOf(ep.optInt("number"))))continue;
            item.recentAt=Math.max(item.recentAt,pausedAt);
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
            if(age<0||age>=6L*3600L*1000L || item.watchedAt>cached.optLong("cached_at",0))return false;
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

    // Real Veezie player fields only. These observations stay local and never
    // submit watched/scrobble events or modify Veezie's private data.
    private static final java.util.concurrent.ExecutorService PLAYBACK_WRITER=
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String playbackTitle(String s){
        return s==null?"":s.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    private static String playbackClock(long ms){
        long seconds=Math.max(0,ms/1000L);
        return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):
            String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    public static void recordVeeziePlayback(Context ctx,String title,String season,String episode,
                                           long position,long duration){
        if(title==null || title.trim().isEmpty() || duration<=0 || position<0 || position>duration)return;
        final Context app=ctx.getApplicationContext();
        PLAYBACK_WRITER.execute(()->{
            try{
                long channel=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("channel_id",-1);
                long veezieChannel=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("veezie_channel_id",-1);
                Set<String> matchedIds=new HashSet<>();
                Item match=null;long row=-1;int matches=0;
                boolean series=!season.isEmpty() && !episode.isEmpty();
                try(Cursor cur=app.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                    new String[]{TvContract.PreviewPrograms._ID,TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                    TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,TvContract.PreviewPrograms.COLUMN_TITLE,
                    TvContract.PreviewPrograms.COLUMN_TYPE},null,null,null)){
                    if(cur!=null)while(cur.moveToNext()){
                        String key=cur.getString(2);
                        if((cur.getLong(1)!=channel&&cur.getLong(1)!=veezieChannel) || key==null || !key.startsWith("fuoconero:") ||
                           !playbackTitle(title).equals(playbackTitle(cur.getString(3))))continue;
                        boolean cardSeries=cur.getInt(4)==TvContract.PreviewPrograms.TYPE_TV_EPISODE;
                        if(series!=cardSeries)continue;
                        String id=key.substring("fuoconero:".length());
                        if(!id.matches("(show|movie)_[a-zA-Z0-9_-]{1,80}"))continue;
                        if(!matchedIds.add(id))continue;
                        matches++;row=cur.getLong(0);
                        match=new Item();match.id=id;match.title=cur.getString(3);match.episode=series;
                    }
                }
                if(matches!=1 || match==null)return; // Ambiguous titles are never guessed.
                SharedPreferences prefs=app.getSharedPreferences("veezie_playback",Context.MODE_PRIVATE);
                JSONObject previous=new JSONObject(prefs.getString(match.id,"{}"));
                long now=System.currentTimeMillis();
                if(previous.optLong("position",-1)==position && previous.optLong("duration",-1)==duration &&
                   previous.optString("season","").equals(season) && previous.optString("episode","").equals(episode)
                   && now-previous.optLong("observed_at",0)<60000L)return;
                JSONObject data=new JSONObject().put("title",match.title).put("season",season)
                    .put("episode",episode).put("position",position).put("duration",duration).put("observed_at",now);
                prefs.edit().putString(match.id,data.toString()).putString("last_title",match.title)
                    .putString("last_episode",series?"S"+season+"E"+episode:"Film")
                    .putLong("last_position",position).putLong("last_duration",duration)
                    .putLong("last_observed",now).apply();
                RecentStore.veezieProgress(app,match.id,match.title,season,episode,position,duration);
                RecentStore.publish(app);
                try(RecentStore history=new RecentStore(app)){
                    for(RecentStore.Entry entry:history.list(200))if(entry.key.equals(match.id)){
                        VeeziePublisher.publish(app,Collections.singletonList(entry),false);break;
                    }
                }
            }catch(Exception e){android.util.Log.w("FuoconeroTV","Veezie playback update failed",e);}
        });
    }
    private static void applyVeeziePlayback(Context ctx,Item item){
        try{
            String raw=ctx.getSharedPreferences("veezie_playback",Context.MODE_PRIVATE).getString(item.id,"");
            if(raw.isEmpty())return;
            JSONObject p=new JSONObject(raw);
            long elapsed=System.currentTimeMillis()-p.optLong("observed_at",0);
            long position=p.optLong("position",-1),duration=p.optLong("duration",-1);
            String season=p.optString("season",""),episode=p.optString("episode","");
            if(item.watchedAt>=p.optLong("observed_at",Long.MAX_VALUE) &&
               item.season.equals(season) && item.episodeNumber.equals(episode))return;
            if(elapsed<0 || elapsed>30L*24L*60L*60L*1000L || duration<=0 || position<0 || position>duration ||
               !playbackTitle(item.title).equals(playbackTitle(p.optString("title",""))))return;
            if(item.episode && (!season.matches("[0-9]{1,3}") || !episode.matches("[0-9]{1,3}")))return;
            if(!item.episode && (!season.isEmpty() || !episode.isEmpty()))return;
            item.positionMs=position;item.durationMs=duration;item.progress=100.0*position/duration;
            item.progressSource="Veezie";item.season=season;item.episodeNumber=episode;
            // A partial episode resumes that exact episode, rather than Trakt's next episode.
            item.nextSeason="";item.nextEpisodeNumber="";item.nextEpisodeTitle="";item.nextRuntimeMinutes=0;
            item.description=(item.episode?String.format(Locale.ITALY,"Riprendi S%02dE%02d · ",
                Integer.parseInt(season),Integer.parseInt(episode)):"Riprendi film · ")+
                "Veezie "+playbackClock(position)+" / "+playbackClock(duration);
        }catch(Exception e){android.util.Log.w("FuoconeroTV","Invalid local playback record",e);}
    }

    private static boolean drawProgressPoster(Context ctx,Item item){
        if(item.progress<0 || item.progress>100)return false;
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
            canvas.drawRect(0,h*(item.durationMs>0?0.81f:0.87f),w,h,bg);
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            text.setTextSize(Math.max(16f,w*0.068f));
            long percent=Math.round(item.progress);
            if(item.durationMs>0){
                text.setTextSize(Math.max(14f,w*0.052f));
                String code=item.episode?String.format(Locale.ITALY,"S%02dE%02d",
                    Integer.parseInt(item.season),Integer.parseInt(item.episodeNumber)):"Film";
                canvas.drawText(code+" · "+String.format(Locale.ITALY,"%.1f%%",item.progress),
                    w*0.065f,h*0.865f,text);
                canvas.drawText(playbackClock(item.positionMs)+" / "+playbackClock(item.durationMs)+" · Veezie",
                    w*0.065f,h*0.925f,text);
            }else canvas.drawText(percent+"% visto · Trakt",w*0.065f,h*0.93f,text);
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
    private static long channel(Context c)throws Exception {return RecentStore.channel(c);}
    private static long eventTime(String value){
        try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception ignored){return 0;}
    }
    private static void updateCards(Context c,long channel,ArrayList<Item> cards,boolean complete)throws Exception {
        ArrayList<RecentStore.Entry> veezieCards=new ArrayList<>();
        try(RecentStore recents=new RecentStore(c)){
            for(Item item:cards){
                if("movie_openveezie".equals(item.id))continue;
                String targetSeason=!item.nextSeason.isEmpty()?item.nextSeason:item.progress>=0?item.season:"";
                String targetEpisode=!item.nextEpisodeNumber.isEmpty()?item.nextEpisodeNumber:item.progress>=0?item.episodeNumber:"";
                Intent intent=new Intent(c,OpenContentActivity.class).setAction(Intent.ACTION_VIEW)
                    .setData(new Uri.Builder().scheme("fuoconero-tv").authority("item").appendPath(item.id).build())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title",item.title).putExtra("episode",item.episode)
                    .putExtra("season",targetSeason)
                    .putExtra("episode_number",targetEpisode);
                RecentStore.Entry e=new RecentStore.Entry();e.key=item.id;e.title=item.title;e.at=item.recentAt;
                if(item.tmdb.matches("[1-9][0-9]*")){
                    if(!item.episode)e.identity="movie:tmdb:"+item.tmdb;
                    else if(targetSeason.matches("[0-9]{1,3}")&&targetEpisode.matches("[0-9]{1,3}"))
                        e.identity="show:tmdb:"+item.tmdb+":s"+Integer.parseInt(targetSeason)+":e"+Integer.parseInt(targetEpisode);
                }
                e.description=!item.nextSeason.isEmpty()?"Prossima S"+item.nextSeason+"E"+item.nextEpisodeNumber+" · "+item.nextEpisodeTitle:item.description;
                e.art=RecentStore.image(c,item.id+".png");e.intent=intent.toUri(Intent.URI_INTENT_SCHEME);
                e.source=item.progressSource;e.percent=item.progress;
                if(item.durationMs>0){e.duration=item.durationMs;e.position=item.positionMs;}
                veezieCards.add(e);
                recents.save(e);
            }
        }
        RecentStore.publish(c);
        VeeziePublisher.publish(c,veezieCards,complete);
    }
    public static void removeChannel(Context c){
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long id=p.getLong("channel_id",-1);
        if(id>0)c.getContentResolver().delete(TvContract.buildChannelUri(id),null,null);
        p.edit().remove("channel_id").apply();
    }
}


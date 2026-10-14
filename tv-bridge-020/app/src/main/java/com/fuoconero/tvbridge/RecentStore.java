package com.fuoconero.tvbridge;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.media.tv.TvContract;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

/** One local, bounded public history. Opening never means watched. */
public final class RecentStore extends SQLiteOpenHelper {
    private static final Object HISTORY_LOCK=new Object();
    public static final String CHANNEL="🔥 FUOCONERO — CONTINUA A GUARDARE";
    private final Context app;
    public static final class Entry {
        public String key,title,description,intent,art,source,identity="";
        public long at,position,duration;
        public double percent=-1;
    }
    public RecentStore(Context c){super(c,"recent043.db",null,2);app=c.getApplicationContext();setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE recent(key TEXT PRIMARY KEY,title TEXT NOT NULL,description TEXT NOT NULL,intent TEXT NOT NULL,art TEXT NOT NULL,source TEXT NOT NULL,at INTEGER NOT NULL,position INTEGER NOT NULL,duration INTEGER NOT NULL,percent REAL NOT NULL,identity TEXT NOT NULL DEFAULT '')");
        d.execSQL("CREATE TABLE hidden(key TEXT PRIMARY KEY,at INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX recent_order ON recent(at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)d.execSQL("ALTER TABLE recent ADD COLUMN identity TEXT NOT NULL DEFAULT ''");}
    public void save(Entry e){
        synchronized(HISTORY_LOCK){saveLocked(e);}
    }
    private void saveLocked(Entry e){
        if(e.key==null || e.title==null || e.intent==null || e.at<=0)return;
        String classified=e.title.toLowerCase(Locale.ROOT);
        if(classified.contains("xxx")||classified.contains("porno")||classified.contains("18+")||classified.contains("adult"))return;
        SQLiteDatabase d=getWritableDatabase();
        try(Cursor c=d.rawQuery("SELECT MAX(at) FROM hidden WHERE key IN (?,?)",new String[]{e.key,"identity:"+e.identity})){
            if(c.moveToFirst() && !c.isNull(0) && e.at<=c.getLong(0))return;
        }
        if(e.identity!=null&&!e.identity.isEmpty()){
            try(Cursor c=d.rawQuery("SELECT key,at FROM recent WHERE identity=? AND key<>?",new String[]{e.identity,e.key})){
                while(c.moveToNext())if(c.getLong(1)>e.at)return;
            }
            d.delete("recent","identity=? AND key<>?",new String[]{e.identity,e.key});
        }
        ContentValues v=new ContentValues();
        v.put("key",e.key);v.put("title",e.title);v.put("description",e.description==null?"":e.description);
        v.put("intent",e.intent);v.put("art",e.art==null?"":e.art);v.put("source",e.source==null?"Aperto":e.source);
        v.put("at",e.at);v.put("position",Math.max(0,e.position));v.put("duration",Math.max(0,e.duration));v.put("percent",e.percent);
        v.put("identity",e.identity==null?"":e.identity);
        // A sync may update the next episode, but may not make old activity look recent.
        try(Cursor c=d.rawQuery("SELECT at FROM recent WHERE key=?",new String[]{e.key})){
            if(c.moveToFirst())v.put("at",Math.max(e.at,c.getLong(0)));
        }
        d.insertWithOnConflict("recent",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        d.execSQL("DELETE FROM recent WHERE key NOT IN (SELECT key FROM recent ORDER BY at DESC LIMIT 200)");
    }
    public ArrayList<Entry> list(int limit){
        ArrayList<Entry> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT key,title,description,intent,art,source,at,position,duration,percent,identity FROM recent ORDER BY at DESC,key LIMIT ?",new String[]{String.valueOf(limit)})){
            while(c.moveToNext()){
                Entry e=new Entry();e.key=c.getString(0);e.title=c.getString(1);e.description=c.getString(2);e.intent=c.getString(3);
                e.art=c.getString(4);e.source=c.getString(5);e.at=c.getLong(6);e.position=c.getLong(7);e.duration=c.getLong(8);e.percent=c.getDouble(9);e.identity=c.getString(10);out.add(e);
            }
        }
        return out;
    }
    public void remove(String key){
        synchronized(HISTORY_LOCK){removeLocked(key);}
    }
    private void removeLocked(String key){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT identity FROM recent WHERE key=?",new String[]{key})){
            if(c.moveToFirst()&&!c.getString(0).isEmpty())getWritableDatabase().execSQL("INSERT OR REPLACE INTO hidden VALUES(?,?)",new Object[]{"identity:"+c.getString(0),System.currentTimeMillis()});
        }
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO hidden VALUES(?,?)",new Object[]{key,System.currentTimeMillis()});
        getWritableDatabase().delete("recent","key=?",new String[]{key});
        // Progress stays available if the viewer deliberately opens the title again.
    }
    public static String image(Context c,String filename){
        File f=new File(c.getFilesDir(),"posters/"+filename);
        return f.isFile()?new Uri.Builder().scheme("content").authority(c.getPackageName()+".images").appendPath("posters")
            .appendPath(filename).appendQueryParameter("v",String.valueOf(f.lastModified())).build().toString():"";
    }
    public static void iptv(Context c,IptvStore.Media m,long at,long position,long duration){
        if(m==null||m.adult)return;
        Entry e=new Entry();e.key="iptv_"+m.id;e.title=m.title;e.at=at;e.position=position;e.duration="TV".equals(m.kind)?0:duration;
        try(IptvLibrary library=new IptvLibrary(c)){e.identity=library.identity(m.id);}
        e.percent=e.duration>0?Math.min(100,100.0*position/e.duration):-1;
        e.source="TV".equals(m.kind)?"Canale IPTV · aperto":e.duration>0?"Riproduzione IPTV":"Aperto IPTV";
        e.description=e.duration>0?"Riprendi · "+WideArtwork.clock(position)+" / "+WideArtwork.clock(duration):
            "TV".equals(m.kind)?"Canale aperto di recente":"Aperto di recente · avanzamento non disponibile";
        e.intent=new Intent(c,IptvPlayActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse("fuoconero-iptv://play/"+m.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("id",m.id).toUri(Intent.URI_INTENT_SCHEME);
        e.art=image(c,"iptv_"+m.id+".png");
        try(RecentStore s=new RecentStore(c)){s.save(e);}
    }
    public static void openedVeezie(Context c,Intent intent){
        if(intent==null || intent.getData()==null)return;
        String id=intent.getData().getLastPathSegment(),title=intent.getStringExtra("title");
        if(id==null || title==null || !id.matches("(show|movie)_[a-zA-Z0-9_-]{1,80}"))return;
        try(RecentStore s=new RecentStore(c)){
            Entry e=null;for(Entry old:s.list(200))if(old.key.equals(id)){e=old;break;}
            if(e==null){e=new Entry();e.key=id;e.title=title;e.art=image(c,id+".png");}
            e.intent=intent.toUri(Intent.URI_INTENT_SCHEME);e.at=System.currentTimeMillis();
            e.source="Aperto Veezie";e.description="Aperto in Veezie · nessuna conferma di completamento";
            s.save(e);
        }
        asyncPublish(c);
    }
    public static void veezieProgress(Context c,String id,String title,String season,String episode,long position,long duration){
        if(duration<=0 || position<0 || position>duration)return;
        try(RecentStore s=new RecentStore(c)){
            Entry e=new Entry();e.key=id;e.title=title;e.at=System.currentTimeMillis();e.position=position;e.duration=duration;
            for(Entry old:s.list(200))if(old.key.equals(id)){
                e.identity=old.identity;
                if(!season.isEmpty()&&!episode.isEmpty()&&e.identity.startsWith("show:tmdb:"))
                    e.identity=e.identity.replaceAll(":s[0-9]+:e[0-9]+$",":s"+Integer.parseInt(season)+":e"+Integer.parseInt(episode));
                break;
            }
            e.percent=100.0*position/duration;e.source="Veezie";
            e.description=(season.isEmpty()?"Film":"S"+season+"E"+episode)+" · "+WideArtwork.clock(position)+" / "+WideArtwork.clock(duration);
            e.art=image(c,id+".png");
            e.intent=new Intent(c,OpenContentActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("fuoconero-tv://item/"+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title",title).putExtra("episode",!season.isEmpty()).putExtra("season",season)
                .putExtra("episode_number",episode).toUri(Intent.URI_INTENT_SCHEME);
            s.save(e);
        }
    }
    private static final java.util.concurrent.ExecutorService WRITER=java.util.concurrent.Executors.newSingleThreadExecutor();
    public static void asyncPublish(Context c){
        Context app=c.getApplicationContext();
        WRITER.execute(()->{try{publish(app);}catch(Exception e){android.util.Log.w("Fuoconero043","Recent refresh deferred");}});
    }
    public static synchronized long channel(Context c)throws Exception{
        SharedPreferences p=c.getSharedPreferences("tvbridge",Context.MODE_PRIVATE);
        long id=p.getLong("channel_id",-1);
        if(id>0)try(Cursor cur=c.getContentResolver().query(TvContract.buildChannelUri(id),new String[]{"_id"},null,null,null)){
            if(cur!=null&&cur.moveToFirst())return id;
        }
        ContentValues v=new ContentValues();v.put(TvContract.Channels.COLUMN_TYPE,TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME,CHANNEL);
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,"fuoconero-043-unified");
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,new Intent(c,BrowseActivity.class).toUri(Intent.URI_INTENT_SCHEME));
        Uri uri=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,v);
        if(uri==null)throw new java.io.IOException("Canale non creato");
        id=ContentUris.parseId(uri);p.edit().putLong("channel_id",id).apply();return id;
    }
    public static synchronized void publish(Context c)throws Exception{
        long channel=channel(c);ArrayList<Entry> rows;
        try(RecentStore s=new RecentStore(c)){rows=s.list(20);}
        Map<String,Long> old=new HashMap<>();
        try(Cursor cur=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(cur!=null)while(cur.moveToNext())if(cur.getLong(1)==channel)old.put(cur.getString(2),cur.getLong(0));
        }
        Set<String> retained=new HashSet<>();retained.add(RefreshActionCard.KEY);int order=0;
        for(Entry e:rows){
            String key="fuoconero:"+e.key;
            Intent intent=Intent.parseUri(e.intent,Intent.URI_INTENT_SCHEME);
            String subtitle=e.description;
            double displayedPercent=e.percent;String displayedSource=e.source;
            if(e.key.startsWith("iptv_"))try(IptvStore store=new IptvStore(c);EpgStore epg=new EpgStore(c)){
                IptvStore.Media m=store.find(Long.parseLong(e.key.substring(5)));
                if(m==null||m.adult)continue;
                if("TV".equals(m.kind)){EpgStore.NowNext guide=epg.nowNext(m);if(guide.now!=null){subtitle=guide.describe();displayedPercent=guide.percent();displayedSource="PROGRAMMA IN ONDA";}}
                e.art=image(c,"iptv_"+m.id+".png");
            }
            String photo=WideArtwork.render(c,e.key,e.title,subtitle,e.art,displayedPercent,displayedSource);
            retained.add(key);
            ContentValues v=new ContentValues();v.put("channel_id",channel);v.put("internal_provider_id",key);
            v.put("title",e.title);v.put("short_description",subtitle);v.put("weight",1000-order++);
            v.put("type",e.key.startsWith("show_")?TvContract.PreviewPrograms.TYPE_TV_EPISODE:
                e.source.contains("Canale")?TvContract.PreviewPrograms.TYPE_CHANNEL:TvContract.PreviewPrograms.TYPE_MOVIE);
            v.put("poster_art_uri",photo);v.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
            v.put("intent_uri",intent.toUri(Intent.URI_INTENT_SCHEME));
            if(e.key.startsWith("show_")){
                String season=intent.getStringExtra("season"),episode=intent.getStringExtra("episode_number");
                if(season!=null&&season.matches("[0-9]{1,3}"))v.put("season_display_number",season);
                if(episode!=null&&episode.matches("[0-9]{1,3}"))v.put("episode_display_number",episode);
            }
            // Projectivy overlays an inconsistent native bar on preview programmes.
            // The artwork already contains the exact measured playback percentage.
            if(e.duration>0){v.put("duration_millis",e.duration);v.putNull("last_playback_position_millis");}
            else{v.putNull("duration_millis");v.putNull("last_playback_position_millis");}
            Long previous=old.get(key);
            if(previous!=null){v.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(previous),v,null,null);}
            else c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,v);
        }
        for(Map.Entry<String,Long> e:old.entrySet())if(!retained.contains(e.getKey()))
            c.getContentResolver().delete(TvContract.buildPreviewProgramUri(e.getValue()),null,null);
        RefreshActionCard.publish(c,channel);
    }
}


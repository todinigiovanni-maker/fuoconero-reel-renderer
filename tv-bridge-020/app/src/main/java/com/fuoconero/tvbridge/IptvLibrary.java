package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separate local media index and playback history. Does not touch IPTV Extreme
 * private storage or pretend its watch progress is available through Android.
 */
public final class IptvLibrary extends SQLiteOpenHelper {
    private static final String TAG="FuoconeroIPTV";
    private static final Pattern EP=Pattern.compile(
        "^(.*?)\\s+[Ss](\\d{1,2})\\s*[.\\- ]?\\s*[Ee](\\d{1,3})(?:\\b|$).*",
        Pattern.CASE_INSENSITIVE);
    private final Context app;
    public static final class Show {
        public String key,title;
        public int count;
        public long newestId;
    }
    public static final class Episode {
        public long id;
        public String title;
        public int season,number;
    }
    public static final class Recent {
        public IptvStore.Media media;
        public long positionMs,durationMs,openedAt;
    }
    public IptvLibrary(Context ctx){
        super(ctx,"fuoconero_library.db",null,2);
        app=ctx.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db){
        super.onConfigure(db);
        // Android SQLiteDatabase.execSQL rejects PRAGMA busy_timeout as a query.
        // Rely on the connection busy handler supplied by Android instead.
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE series_index(media_id INTEGER PRIMARY KEY, series_key TEXT NOT NULL, display_title TEXT NOT NULL, season INTEGER NOT NULL, episode INTEGER NOT NULL, first_seen INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX series_lookup ON series_index(series_key,season,episode)");
        db.execSQL("CREATE INDEX series_recent ON series_index(first_seen,media_id)");
        db.execSQL("CREATE TABLE watch_state(media_id INTEGER PRIMARY KEY, position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, last_opened INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX watch_recent ON watch_state(last_opened)");
        db.execSQL("CREATE TABLE index_state(id INTEGER PRIMARY KEY CHECK(id=1), indexed_at INTEGER NOT NULL)");
        createSourceTables(db);
    }
    private static void createSourceTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS source_movies(media_id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL, artwork TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS source_series(series_key TEXT PRIMARY KEY, updated_at INTEGER NOT NULL, artwork TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){createSourceTables(db);}
    public String artwork(long mediaId){
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT artwork FROM source_movies WHERE media_id=? UNION ALL "+
            "SELECT s.artwork FROM source_series s JOIN series_index i ON i.series_key=s.series_key WHERE i.media_id=? LIMIT 1",
            new String[]{String.valueOf(mediaId),String.valueOf(mediaId)})){
            return c.moveToFirst()?c.getString(0):"";
        }
    }
    private boolean sourceDates(boolean series){
        return app.getSharedPreferences("iptv_metadata",Context.MODE_PRIVATE).getInt(series?"series":"movies",0)>0;
    }
    private static String clean(String s){return s.trim().replaceAll("\\s+"," ");}
    private static String[] parse(String title){
        Matcher m=EP.matcher(title);
        if(m.matches()){
            String display=clean(m.group(1));
            if(!display.isEmpty())return new String[]{
                display.toLowerCase(Locale.ROOT),display,m.group(2),m.group(3)};
        }
        String display=clean(title);
        return new String[]{display.toLowerCase(Locale.ROOT),display,"0","0"};
    }
    // Called on a worker thread; rebuilt only when a successful M3U import changes.
    public synchronized void ensureIndex() {
        SQLiteDatabase db=getWritableDatabase();
        long lastSuccess=app.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE)
            .getLong("last_success",0);
        if(lastSuccess<=0)return;
        try(Cursor state=db.rawQuery("SELECT indexed_at FROM index_state WHERE id=1",null)){
            if(state.moveToFirst() && state.getLong(0)==lastSuccess)return;
        }
        try(IptvStore store=new IptvStore(app)){
            SQLiteDatabase source=store.getReadableDatabase();
            db.beginTransactionNonExclusive();
            try(Cursor rows=source.rawQuery(
                "SELECT id,title,first_seen FROM entries WHERE category='Serie' AND adult=0",null)){
                db.execSQL("DELETE FROM series_index");
                SQLiteStatement put=db.compileStatement(
                    "INSERT OR REPLACE INTO series_index(media_id,series_key,display_title,season,episode,first_seen) VALUES(?,?,?,?,?,?)");
                int n=0;
                while(rows.moveToNext()){
                    String[] parts=parse(rows.getString(1));
                    put.clearBindings();
                    put.bindLong(1,rows.getLong(0));
                    put.bindString(2,parts[0]);
                    put.bindString(3,parts[1]);
                    put.bindLong(4,Integer.parseInt(parts[2]));
                    put.bindLong(5,Integer.parseInt(parts[3]));
                    put.bindLong(6,rows.getLong(2));
                    put.executeInsert();
                    if(++n%10000==0)Log.i(TAG,"Series indicizzate: "+n);
                }
                db.execSQL("INSERT OR REPLACE INTO index_state(id,indexed_at) VALUES(1,?)",
                    new Object[]{lastSuccess});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }catch(Exception e){
            Log.w(TAG,"Serie index not available yet",e);
            throw new IllegalStateException("Indice serie non pronto: "+e.getMessage(),e);
        }
    }
    private long newFrom(){
        return app.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE)
            .getLong("new_from",Long.MAX_VALUE);
    }
    public ArrayList<Show> shows(String filter,int offset,int limit,boolean newOnly){
        ensureIndex();
        ArrayList<Show> result=new ArrayList<>();
        String where=" WHERE 1=1";
        ArrayList<String> args=new ArrayList<>();
        if(filter!=null && !filter.trim().isEmpty()){
            where+=" AND i.series_key LIKE ? ESCAPE '\\'";
            String term=filter.trim().toLowerCase(Locale.ROOT)
                .replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
            args.add("%"+term+"%");
        }
        long threshold=newFrom();
        if(newOnly && sourceDates(true))where+=" AND s.updated_at IS NOT NULL";
        if(newOnly && !sourceDates(true) && threshold<Long.MAX_VALUE){
            where+=" AND i.first_seen>=?";
            args.add(String.valueOf(threshold));
        }
        args.add(String.valueOf(Math.min(50,Math.max(1,limit))));
        args.add(String.valueOf(Math.max(0,offset)));
        String sql="SELECT i.series_key,MAX(i.display_title),COUNT(*),MAX(i.media_id)"+
            " FROM series_index i LEFT JOIN source_series s ON s.series_key=i.series_key"+where+" GROUP BY i.series_key"+
            " ORDER BY MAX(COALESCE(s.updated_at,i.first_seen)) DESC,MAX(i.media_id) DESC LIMIT ? OFFSET ?";
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){
            while(c.moveToNext()){
                Show show=new Show();
                show.key=c.getString(0);show.title=c.getString(1);
                show.count=c.getInt(2);show.newestId=c.getLong(3);
                result.add(show);
            }
        }
        return result;
    }
    public ArrayList<Episode> episodes(String key,int offset,int limit){
        ensureIndex();
        ArrayList<Episode> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT media_id,display_title,season,episode FROM series_index WHERE series_key=?"+
            " ORDER BY season DESC,episode DESC,media_id DESC LIMIT ? OFFSET ?",
            new String[]{key,String.valueOf(Math.min(80,Math.max(1,limit))),
                String.valueOf(Math.max(0,offset))})){
            while(c.moveToNext()){
                Episode e=new Episode();
                e.id=c.getLong(0);e.title=c.getString(1);
                e.season=c.getInt(2);e.number=c.getInt(3);
                out.add(e);
            }
        }
        return out;
    }
    public ArrayList<IptvStore.Media> newestFilms(int offset,int limit){
        ArrayList<IptvStore.Media> rows=new ArrayList<>();
        if(sourceDates(false)){
            int skip=Math.max(0,offset),cap=Math.min(50,Math.max(1,limit));
            try(IptvStore store=new IptvStore(app);Cursor c=getReadableDatabase().rawQuery(
                "SELECT media_id FROM source_movies ORDER BY added_at DESC,media_id DESC",null)){
                while(c.moveToNext()){
                    IptvStore.Media m=store.find(c.getLong(0));
                    if(m==null || m.adult || !"Film".equals(m.category) || !"Film".equals(m.kind))continue;
                    if(skip-->0)continue;
                    rows.add(m);if(rows.size()>=cap)break;
                }
            }
            return rows;
        }
        long threshold=newFrom();
        String where=!sourceDates(false) && threshold<Long.MAX_VALUE ? " AND e.first_seen>=?" : "";
        ArrayList<String> args=new ArrayList<>();
        if(!where.isEmpty())args.add(String.valueOf(threshold));
        args.add(String.valueOf(Math.min(50,Math.max(1,limit))));
        args.add(String.valueOf(Math.max(0,offset)));
        try(IptvStore store=new IptvStore(app);
            Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT e.id,e.title,e.grp,e.url,e.category,e.kind,e.adult,e.fav FROM entries e"+
                " WHERE category='Film' AND kind='Film' AND adult=0"+where+
                " ORDER BY e.first_seen DESC,e.id DESC LIMIT ? OFFSET ?",
                args.toArray(new String[0]))){
            while(c.moveToNext()){
                IptvStore.Media m=new IptvStore.Media();
                m.id=c.getLong(0);m.title=c.getString(1);m.group=c.getString(2);
                m.url=c.getString(3);m.category=c.getString(4);m.kind=c.getString(5);
                m.adult=c.getInt(6)!=0;m.fav=c.getInt(7)!=0;rows.add(m);
            }
        }
        return rows;
    }
    public void markOpened(long id){
        long now=System.currentTimeMillis();
        getWritableDatabase().execSQL(
            "INSERT OR IGNORE INTO watch_state(media_id,position_ms,duration_ms,last_opened) VALUES(?,0,0,?)",
            new Object[]{id,now});
        getWritableDatabase().execSQL(
            "UPDATE watch_state SET last_opened=? WHERE media_id=?",
            new Object[]{now,id});
        try(IptvStore store=new IptvStore(app)){
            long[] prior=playback(id);RecentStore.iptv(app,store.find(id),now,prior[0],prior[1]);
        }
        RecentStore.asyncPublish(app);
    }
    public void saveProgress(long id,long position,long duration){
        if(id<0 || position<0 || duration<=0)return;
        // Live channels and unknown durations are never assigned fake progress.
        long bounded=Math.min(position,duration);
        getWritableDatabase().execSQL(
            "INSERT OR REPLACE INTO watch_state(media_id,position_ms,duration_ms,last_opened) VALUES(?,?,?,?)",
            new Object[]{id,bounded,duration,System.currentTimeMillis()});
        try(IptvStore store=new IptvStore(app)){RecentStore.iptv(app,store.find(id),System.currentTimeMillis(),bounded,duration);}
    }
    public long[] playback(long id){
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT position_ms,duration_ms FROM watch_state WHERE media_id=?",
            new String[]{String.valueOf(id)})){
            return c.moveToFirst()?new long[]{c.getLong(0),c.getLong(1)}:new long[]{0,0};
        }
    }
    public ArrayList<Recent> recentlyOpened(int limit){
        ArrayList<Recent> result=new ArrayList<>();
        ArrayList<long[]> history=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT media_id,position_ms,duration_ms,last_opened FROM watch_state"+
            " ORDER BY last_opened DESC LIMIT ?",new String[]{String.valueOf(limit)})){
            while(c.moveToNext())history.add(
                new long[]{c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3)});
        }
        try(IptvStore store=new IptvStore(app)){
            for(long[] data:history){
                IptvStore.Media media=store.find(data[0]);
                if(media==null||media.adult)continue;
                Recent item=new Recent();
                item.media=media;item.positionMs=data[1];
                item.durationMs=data[2];item.openedAt=data[3];
                result.add(item);
            }
        }
        return result;
    }
}

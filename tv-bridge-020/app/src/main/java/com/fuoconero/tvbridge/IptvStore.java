package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Private, on-device index for large user-provided M3U files. Never uploads a stream URL. */
public final class IptvStore extends SQLiteOpenHelper {
    public interface Progress { void say(String message); }
    public static final String PREFS="iptv_settings";
    public static final String[] PUBLIC_GROUPS={"Continua","Ultimi film","Ultime serie","TV Italia","Sport","DAZN","Documentari","Film","Serie"};
    public static final class Media {
        public long id;
        public String title,category,group,url,kind;
        public boolean adult,fav;
    }
    private static final Pattern GROUP=Pattern.compile("(?:^|\\s)group-title=\"([^\"]*)\"");
    private static final Pattern NAME=Pattern.compile("(?:^|\\s)tvg-name=\"([^\"]*)\"");
    private final Context app;
    public IptvStore(Context c){
        super(c,"fuoconero_iptv.db",null,1);
        app=c.getApplicationContext();
        // Readers must be able to inspect the catalog while a large M3U is indexing.
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db){
        super.onConfigure(db);
        db.execSQL("PRAGMA busy_timeout=30000");
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, ident TEXT NOT NULL UNIQUE, title TEXT NOT NULL, grp TEXT NOT NULL, url TEXT NOT NULL, category TEXT NOT NULL, kind TEXT NOT NULL, adult INTEGER NOT NULL, seen INTEGER NOT NULL, first_seen INTEGER NOT NULL, fav INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_iptv_cat ON entries(category,adult,id)");
        db.execSQL("CREATE INDEX idx_iptv_title ON entries(title)");
        db.execSQL("CREATE INDEX idx_iptv_new ON entries(first_seen)");
        db.execSQL("CREATE INDEX idx_iptv_fav ON entries(fav,adult,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){}
    private static String attr(Pattern p,String info){
        Matcher m=p.matcher(info);
        return m.find()?m.group(1).trim():"";
    }
    private static String clean(String text,int max){
        if(text==null)return "";
        String normalized=text.replaceAll("[\\p{Cntrl}&&[^\\t]]"," ").trim();
        return normalized.substring(0,Math.min(normalized.length(),max));
    }
    private static boolean isAdult(String group,String title){
        String x=(group+" "+title).toLowerCase(Locale.ROOT);
        return x.contains("xxx")||x.contains("x x x")||x.contains("adult")||
            x.contains("porno")||x.contains("porn ")||x.contains("hot vip")||
            x.contains("18+")||x.contains("erotic")||x.contains("sex ")||
            x.contains("onlyfans");
    }
    private static String classify(String group,String title,boolean adult,String url){
        if(adult)return "Adulti";
        String g=group.toLowerCase(Locale.ROOT);
        String u=url.toLowerCase(Locale.ROOT);
        if(g.contains("documentar")||g.contains("history channel")||
            g.contains("discovery+")||g.contains("national geographic")||
            g.contains("bbc earth"))return "Documentari";
        if(g.contains("dazn")||g.contains("sky sport")||
            g.contains("sport")||g.contains("espn")||g.contains("calcio")||
            g.contains("champions")||g.contains("serie a")||g.contains("formula 1"))
            return "Sport";
        if(u.contains("/series/")||g.contains("serie ")||g.contains("series")||
            g.contains("anime")||g.contains("cartoni")||g.contains("disney+")||
            g.contains("netflix series"))return "Serie";
        if(u.contains("/movie/")||g.contains("film")||
            g.contains("cinema")||g.contains("vod -")||g.contains("movie"))
            return "Film";
        if(g.contains("top italia")||g.contains("italia")||
            g.contains("italy")||g.contains("rai")||g.contains("mediaset"))
            return "TV Italia";
        return "Altri canali";
    }
    private static String identity(String title,String grp,String url)throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        byte[] hash=sha.digest((grp+"\n"+title+"\n"+url).getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder(32);
        for(int i=0;i<16;i++)b.append(String.format(Locale.ROOT,"%02x",hash[i]&255));
        return b.toString();
    }
    /**
     * Indexes up to 400,000 items from the stream without loading the entire
     * 91 MB playlist in memory. One transaction makes failed imports reversible.
     */
    public String importPlaylist(Context context,InputStream input,Progress progress)throws Exception{
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis(),count=0;
        SQLiteDatabase db=getWritableDatabase();
        long prev=prefs.getLong("successful_imports",0);
        // Reusing the existing row ID keeps Projectivy Intent IDs and favorites
        // stable across a refresh of the very same playlist.
        String sql="INSERT OR REPLACE INTO entries(id,ident,title,grp,url,category,kind,adult,seen,first_seen,fav) "+
            "VALUES((SELECT id FROM entries WHERE ident=?),?,?,?,?,?,?,?,?,COALESCE((SELECT first_seen FROM entries WHERE ident=?),?),COALESCE((SELECT fav FROM entries WHERE ident=?),0))";
        db.beginTransactionNonExclusive();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8),65536)){
            SQLiteStatement put=db.compileStatement(sql);
            String line,info=null;
            while((line=reader.readLine())!=null){
                if(Thread.currentThread().isInterrupted())throw new IOException("Importazione interrotta");
                if(line.startsWith("#EXTINF:")){
                    info=line;
                }else if(!line.startsWith("#")&&info!=null){
                    String url=line.trim();
                    if(!(url.startsWith("https://")||url.startsWith("http://"))||
                        url.length()>2048){info=null;continue;}
                    String grp=clean(attr(GROUP,info),180);
                    int comma=info.lastIndexOf(',');
                    String title=comma>=0?info.substring(comma+1):attr(NAME,info);
                    title=clean(title,200);
                    if(title.length()<2){info=null;continue;}
                    boolean adult=isAdult(grp,title);
                    String category=classify(grp,title,adult,url);
                    String kind=url.toLowerCase(Locale.ROOT).contains("/movie/")?"Film":
                        url.toLowerCase(Locale.ROOT).contains("/series/")?"Video":
                        "TV";
                    String ident=identity(title,grp,url);
                    put.clearBindings();
                    put.bindString(1,ident);
                    put.bindString(2,ident);
                    put.bindString(3,title);
                    put.bindString(4,grp);
                    put.bindString(5,url);
                    put.bindString(6,category);
                    put.bindString(7,kind);
                    put.bindLong(8,adult?1:0);
                    put.bindLong(9,now);
                    put.bindString(10,ident);
                    put.bindLong(11,now);
                    put.bindString(12,ident);
                    put.executeInsert();
                    count++;
                    info=null;
                    if(count>400000)throw new IOException("La lista supera il limite di 400.000 voci");
                    if(count%50000==0 && progress!=null)progress.say("Indicizzati "+count+" contenuti...");
                }
            }
            if(count<1)throw new IOException("Il file non contiene flussi M3U validi.");
            db.execSQL("DELETE FROM entries WHERE seen<>?",new Object[]{now});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        prefs.edit().putLong("last_success",now)
            .putLong("item_count",count)
            .putLong("successful_imports",prev+1)
            .putLong("new_from",prev==0?Long.MAX_VALUE:now).apply();
        return "Playlist indicizzata: "+count+" contenuti; categorie pronte. "+
            (prev==0?"Primo caricamento: nessuna novita' ancora confrontabile.":"Novita' confrontate con l'importazione precedente.");
    }
    public long count(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries",null)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    public long countCategory(String category){
        String filter="fav".equals(category)?"fav=1 AND adult=0":
            "new".equals(category)?"first_seen>(SELECT COALESCE(MAX(last_success),0) FROM (SELECT 0 AS last_success)) AND adult=0":
            "Adulti".equals(category)?"adult=1":"category=? AND adult=0";
        String[] args=("fav".equals(category)||"new".equals(category)||"Adulti".equals(category))?
            null:new String[]{category};
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE "+filter,args)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    /**
     * Live television: show only an explicitly labelled HD/FHD/UHD variant,
     * one per named channel. SD, catch-up +1/+2/+3 and repeated providers
     * never take a second slot in Projectivy or in the TV catalog.
     *
     * This only changes the presentation, never deletes original M3U rows.
     */
    private static final java.util.regex.Pattern HD_QUALITY=java.util.regex.Pattern.compile(
        "(?i)\\b(?:FULL\\s*HD|FHD|HD|UHD|4K|720P|1080P|2160P|HDR)\\b");
    private static final java.util.regex.Pattern REMOVE_QUALITY=java.util.regex.Pattern.compile(
        "(?i)\\b(?:FULL\\s*HD|FHD|HD|UHD|4K|SD|RAW|HEVC|H\\.?265|H\\.?264|HDR|720P|1080P|2160P)\\b");
    private static boolean liveCategory(String category){
        return "TV Italia".equals(category)||"Sport".equals(category)||"DAZN".equals(category);
    }
    private static String canonicalLiveName(String original){
        String t=original.toUpperCase(Locale.ROOT);
        t=REMOVE_QUALITY.matcher(t).replaceAll(" ");
        t=t.replaceAll("\\+\\s*[123]\\b"," ");
        t=t.replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim();
        // DAZN 1 - SERIE B - ASCOLI is a temporary alternate feed, not a
        // separate permanent channel DAZN 1. Preserve DAZN 10 versus DAZN 1.
        java.util.regex.Matcher special=java.util.regex.Pattern.compile(
            "^DAZN\\s+(\\d{1,2})(?:\\s+.*)?$").matcher(t);
        if(special.matches())return "DAZN "+Integer.parseInt(special.group(1));
        return t;
    }
    private static int qualityScore(String title,String key){
        String u=title.toUpperCase(Locale.ROOT);
        if(!HD_QUALITY.matcher(u).find())return -1; // no SD or unknown resolution
        int score=0;
        if(u.matches(".*\\bHD\\b.*")&&!u.matches(".*\\bFULL\\s*HD\\b.*"))score=600;
        else if(u.matches(".*\\b(?:FHD|FULL\\s*HD|1080P)\\b.*"))score=550;
        else if(u.matches(".*\\b(?:UHD|4K|2160P)\\b.*"))score=500;
        else score=450;
        if(u.matches(".*\\+\\s*[123]\\b.*"))score-=220;
        if(u.matches(".*\\b(?:H265|HEVC|H\\.265)\\b.*"))score-=15;
        String cleaned=REMOVE_QUALITY.matcher(u).replaceAll(" ")
            .replaceAll("\\+\\s*[123]\\b"," ")
            .replaceAll("[^A-Z0-9 ]"," ").replaceAll("\\s+"," ").trim();
        if(cleaned.equals(key))score+=130; // prefer main named channel
        return score;
    }
    private static int priority(String name,String category){
        if("TV Italia".equals(category)){
            if(name.matches("RAI 1"))return 0;
            if(name.matches("RAI 2"))return 1;
            if(name.matches("RAI 3"))return 2;
            if(name.matches("RETE 4"))return 3;
            if(name.matches("CANALE 5"))return 4;
            if(name.matches("ITALIA 1"))return 5;
            if(name.matches("LA 7|LA7"))return 6;
            if(name.matches("NOVE|DISCOVERY NOVE"))return 7;
        }
        if(name.matches("DAZN [0-9]{1,2}"))
            return Integer.parseInt(name.substring(5));
        if(name.equals("SKY SPORT UNO"))return 30;
        if(name.equals("SKY SPORT CALCIO"))return 31;
        return 1000;
    }
    private ArrayList<Media> uniqueHdLive(String category,String search,int offset,int limit){
        java.util.LinkedHashMap<String,Media> selected=new java.util.LinkedHashMap<>();
        java.util.HashMap<String,Integer> bestScores=new java.util.HashMap<>();
        String sql="SELECT id,title,grp,url,category,kind,adult,fav FROM entries "+
            "WHERE category IN ('TV Italia','Sport') AND kind='TV' AND adult=0 "+
            "AND title NOT LIKE '---%' ORDER BY id ASC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)){
            while(c.moveToNext()){
                Media m=new Media();
                m.id=c.getLong(0);m.title=c.getString(1);m.group=c.getString(2);
                m.url=c.getString(3);m.category=c.getString(4);
                m.kind=c.getString(5);m.adult=false;m.fav=c.getInt(7)!=0;
                if(!"DAZN".equals(category)&&!category.equals(m.category))continue;
                if("DAZN".equals(category)&&!m.title.toUpperCase(Locale.ROOT).startsWith("DAZN"))continue;
                String key=canonicalLiveName(m.title);
                if(key.length()<2)continue;
                if("DAZN".equals(category)&&key.contains(" ")){
                    // Keep permanent channels, not matches and team-specific event aliases.
                    if(!key.matches("DAZN (?:[0-9]{1,2}|CHANNEL|SPORT SERIE A [0-9]{1,2})"))
                        continue;
                }
                int score=qualityScore(m.title,key);
                if(score<0)continue;
                Integer old=bestScores.get(key);
                if(old==null||score>old){
                    bestScores.put(key,score);
                    selected.put(key,m);
                }
            }
        }
        java.util.ArrayList<java.util.Map.Entry<String,Media>> sorted=
            new java.util.ArrayList<>(selected.entrySet());
        sorted.sort((a,b)->{
            int x=priority(a.getKey(),category),y=priority(b.getKey(),category);
            if(x!=y)return Integer.compare(x,y);
            return a.getKey().compareTo(b.getKey());
        });
        String filter=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        ArrayList<Media> result=new ArrayList<>();
        int skip=Math.max(0,offset),cap=Math.min(75,Math.max(1,limit));
        for(java.util.Map.Entry<String,Media> e:sorted){
            Media m=e.getValue();
            if(!filter.isEmpty()&&!m.title.toLowerCase(Locale.ROOT).contains(filter)&&
                !e.getKey().toLowerCase(Locale.ROOT).contains(filter)&&
                !m.group.toLowerCase(Locale.ROOT).contains(filter))continue;
            if(skip-->0)continue;
            result.add(m);
            if(result.size()>=cap)break;
        }
        return result;
    }

    public ArrayList<Media> list(String category,String search,int offset,int limit){
        if(liveCategory(category))return uniqueHdLive(category,search,offset,limit);
        ArrayList<Media> result=new ArrayList<>();
        ArrayList<String> args=new ArrayList<>();
        StringBuilder query=new StringBuilder("SELECT id,title,grp,url,category,kind,adult,fav FROM entries WHERE ");
        if("Preferiti".equals(category))query.append("fav=1 AND adult=0");
        else if("Novita".equals(category)){
            query.append("adult=0 AND first_seen>=?");
            args.add(String.valueOf(app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getLong("new_from",Long.MAX_VALUE)));
        }
        else if("DAZN".equals(category)){
            query.append("category='Sport' AND adult=0 AND (title LIKE ? OR grp LIKE ?)");
            args.add("%DAZN%");args.add("%DAZN%");
        }
        else if("Adulti".equals(category))query.append("adult=1");
        else if("Adulti TV".equals(category))query.append("adult=1 AND kind='TV'");
        else if("Adulti Video".equals(category))query.append("adult=1 AND kind<>'TV'");
        else if("Tutti".equals(category))query.append("adult=0");
        else{query.append("category=? AND adult=0");args.add(category);}
        if(search!=null && !search.trim().isEmpty()){
            query.append(" AND (title LIKE ? ESCAPE '\\' OR grp LIKE ? ESCAPE '\\')");
            String safe=search.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
            args.add("%"+safe+"%");args.add("%"+safe+"%");
        }
        // Distinguish real TV channels from VOD; never surface category headers.
        query.append(" AND title NOT LIKE '---%' AND title NOT LIKE '-- %'");
        query.append(" ORDER BY id ASC LIMIT ? OFFSET ?");
        args.add(String.valueOf(Math.min(75,Math.max(1,limit))));
        args.add(String.valueOf(Math.max(0,offset)));
        try(Cursor c=getReadableDatabase().rawQuery(query.toString(),args.toArray(new String[0]))){
            while(c.moveToNext()){
                Media m=new Media();m.id=c.getLong(0);m.title=c.getString(1);
                m.group=c.getString(2);m.url=c.getString(3);
                m.category=c.getString(4);m.kind=c.getString(5);
                m.adult=c.getInt(6)!=0;m.fav=c.getInt(7)!=0;
                result.add(m);
            }
        }
        return result;
    }
    public Media find(long id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,grp,url,category,kind,adult,fav FROM entries WHERE id=?",
            new String[]{String.valueOf(id)})){
            if(!c.moveToFirst())return null;
            Media m=new Media();m.id=c.getLong(0);m.title=c.getString(1);
            m.group=c.getString(2);m.url=c.getString(3);m.category=c.getString(4);
            m.kind=c.getString(5);m.adult=c.getInt(6)!=0;m.fav=c.getInt(7)!=0;
            return m;
        }
    }
    public void toggleFavorite(long id){
        getWritableDatabase().execSQL("UPDATE entries SET fav=1-fav WHERE id=?",new Object[]{id});
    }
}

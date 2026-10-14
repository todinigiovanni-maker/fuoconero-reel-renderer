package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.net.*;
import java.util.*;

/** Read-only metadata for streams already present in the user's imported playlist. */
public final class IptvMetadata {
    private static final long INTERVAL=12L*60L*60L*1000L;
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private IptvMetadata(){}
    public static void refresh(Context ctx){
        SharedPreferences prefs=ctx.getSharedPreferences("iptv_metadata",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        long last=prefs.getLong("success",0),attempt=prefs.getLong("attempt",0);
        if(now-last<INTERVAL || now-attempt<30L*60L*1000L || !BUSY.compareAndSet(false,true))return;
        prefs.edit().putLong("attempt",now).apply();
        try(IptvStore store=new IptvStore(ctx);IptvLibrary library=new IptvLibrary(ctx)){
            library.ensureIndex();
            Uri source=null;
            HashMap<String,Long> movieIds=new HashMap<>();
            try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT id,url FROM entries WHERE kind='Film' AND adult=0",null)){
                while(c.moveToNext()){
                    Uri u=Uri.parse(c.getString(1));
                    List<String> p=u.getPathSegments();
                    if(p.size()!=4 || !"movie".equals(p.get(0)) ||
                       !p.get(3).matches("[0-9]+\\.[a-zA-Z0-9]+"))continue;
                    if(source==null)source=u;
                    List<String> first=source.getPathSegments();
                    if(!Objects.equals(source.getScheme(),u.getScheme()) ||
                       !Objects.equals(source.getEncodedAuthority(),u.getEncodedAuthority()) ||
                       !first.get(1).equals(p.get(1)) || !first.get(2).equals(p.get(2)))continue;
                    String id=p.get(3).substring(0,p.get(3).indexOf('.'));
                    // Duplicate stream IDs do not get assigned to an arbitrary playlist row.
                    if(movieIds.containsKey(id))movieIds.put(id,-1L);
                    else movieIds.put(id,c.getLong(0));
                }
            }
            if(source==null)return;
            HashSet<String> shows=new HashSet<>();
            try(Cursor c=library.getReadableDatabase().rawQuery("SELECT DISTINCT series_key FROM series_index",null)){
                while(c.moveToNext())shows.add(c.getString(0));
            }
            int movies=read(ctx,library,source,"get_vod_streams",movieIds,shows,false);
            int series=read(ctx,library,source,"get_series",movieIds,shows,true);
            prefs.edit().putLong("success",System.currentTimeMillis())
                .putInt("movies",movies).putInt("series",series).apply();
        }catch(Exception e){
            // Never log a URL or provider credentials; a failed request keeps the previous cache.
            android.util.Log.w("FuoconeroIPTV","Provider metadata unavailable: "+e.getClass().getSimpleName());
        }finally{BUSY.set(false);}
    }
    public static void refreshNow(Context ctx){
        if(BUSY.get())return;
        ctx.getSharedPreferences("iptv_metadata",Context.MODE_PRIVATE).edit().remove("success").remove("attempt").apply();
        refresh(ctx);
    }
    private static int read(Context ctx,IptvLibrary library,Uri source,String action,
                            Map<String,Long> movieIds,Set<String> shows,boolean series)throws Exception{
        List<String> path=source.getPathSegments();
        Uri endpoint=new Uri.Builder().scheme(source.getScheme()).encodedAuthority(source.getEncodedAuthority())
            .path("/player_api.php").appendQueryParameter("username",path.get(1))
            .appendQueryParameter("password",path.get(2)).appendQueryParameter("action",action).build();
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint.toString()).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","FuoconeroIPTV/0.5.3");
        SQLiteDatabase db=library.getWritableDatabase();
        try{
            if(connection.getResponseCode()!=200)throw new IOException("Metadata HTTP");
            if(connection.getContentLengthLong()>32L*1024L*1024L)throw new IOException("Metadata too large");
            InputStream limited=new FilterInputStream(connection.getInputStream()){
                long count=0;
                @Override public int read(byte[] b,int off,int len)throws IOException{
                    int n=super.read(b,off,len);
                    if(n>0 && (count+=n)>32L*1024L*1024L)throw new IOException("Metadata too large");
                    return n;
                }
                @Override public int read()throws IOException{
                    int n=super.read();
                    if(n>=0 && ++count>32L*1024L*1024L)throw new IOException("Metadata too large");
                    return n;
                }
            };
            int matched=0;long future=System.currentTimeMillis()+24L*60L*60L*1000L;
            db.beginTransactionNonExclusive();
            try(JsonReader reader=new JsonReader(new InputStreamReader(limited,"UTF-8"));
                SQLiteStatement put=db.compileStatement(series?
                    "INSERT OR REPLACE INTO source_series(series_key,updated_at,artwork) VALUES(?,MAX(?,COALESCE((SELECT updated_at FROM source_series WHERE series_key=?),0)),?)":
                    "INSERT OR REPLACE INTO source_movies(media_id,added_at,artwork) VALUES(?,?,?)")){
                reader.beginArray();
                int rows=0;
                while(reader.hasNext()){
                    if(++rows>200000)throw new IOException("Metadata item limit");
                    String id="",name="",date="",image="";
                    reader.beginObject();
                    while(reader.hasNext()){
                        String key=reader.nextName();
                        boolean wanted=key.equals(series?"last_modified":"added") ||
                            key.equals(series?"cover":"stream_icon") || key.equals("name") || key.equals("stream_id");
                        if(!wanted || reader.peek()==JsonToken.NULL ||
                           !(reader.peek()==JsonToken.STRING || reader.peek()==JsonToken.NUMBER)){
                            reader.skipValue();continue;
                        }
                        String value=reader.nextString();
                        if(key.equals("name"))name=value;
                        else if(key.equals("stream_id"))id=value;
                        else if(key.equals(series?"cover":"stream_icon"))image=value;
                        else date=value;
                    }
                    reader.endObject();
                    if(!date.matches("[0-9]{9,13}"))continue;
                    long time=Long.parseLong(date);if(time<100000000000L)time*=1000L;
                    if(time<946684800000L || time>future)continue;
                    if(image.length()>2048 || !(image.startsWith("http://")||image.startsWith("https://")))image="";
                    put.clearBindings();
                    if(series){
                        String key=name.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
                        if(!shows.contains(key))continue;
                        put.bindString(1,key);
                    }else{
                        Long media=movieIds.get(id);if(media==null || media<0)continue;
                        put.bindLong(1,media);
                    }
                    put.bindLong(2,time);
                    if(series){put.bindString(3,name.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT));put.bindString(4,image);}
                    else put.bindString(3,image);
                    put.executeInsert();matched++;
                }
                reader.endArray();db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            ctx.getSharedPreferences("iptv_metadata",Context.MODE_PRIVATE).edit()
                .putInt(series?"series":"movies",matched).apply();
            return matched;
        }finally{connection.disconnect();}
    }
}

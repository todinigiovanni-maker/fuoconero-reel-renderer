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
    private static final String CHANNEL_NAME="Fuoconero · Storico Trakt";
    private static class Item {
        String id,title,description,image,imdb;
        boolean episode;
    }
    private TvSync(){}
    public static void perform(Context ctx,Progress notify)throws Exception {
        TraktApi trakt=new TraktApi(ctx);
        // A paused/in-progress item is preferable to a fully watched history record.
        LinkedHashMap<String,Item> items=new LinkedHashMap<>();
        try {
            JSONArray paused=trakt.getArray("/sync/playback?extended=full");
            ingest(trakt,paused,items,true,30);
        } catch(TraktApi.ApiError e) {
            // Some API plans/accounts do not expose sync/playback; history still works.
            if(e.code==401)throw e;
        }
        if(items.size()<30){
            JSONArray history=trakt.getArray("/sync/history?limit=60&extended=full");
            ingest(trakt,history,items,false,30);
        }
        if(items.isEmpty())throw new Exception("Trakt non ha restituito film o episodi recenti.");
        notify.say("Recuperate "+items.size()+" schede. Scarico le locandine...");
        ArrayList<Item> cards=new ArrayList<>(items.values());
        int realPosters=0;
        for(Item item:cards){if(imageFor(ctx,item))realPosters++;} // Local cache; never hotlink Trakt images.
        long channel=channel(ctx);
        updateCards(ctx,channel,cards);
        SyncJob.schedule(ctx);
        notify.say("Riga aggiornata: "+cards.size()+" titoli da Trakt.\nLocandine reali: "+realPosters+"; segnaposto: "+(cards.size()-realPosters)+"\nLe schede aprono la home Veezie, non l'episodio.");
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
            JSONObject episode=entry.optJSONObject("episode");
            if(ep && episode!=null){
                String s=String.format(Locale.ITALY,"S%02dE%02d",episode.optInt("season"),episode.optInt("number"));
                t.description=(paused?"In corso: ":"Ultimo episodio visto: ")+s+" · "+episode.optString("title","");
            }else t.description=paused?"Riproduzione in corso":"Nel tuo storico Trakt";
            t.image=posterUrl(media);
            if(t.image.isEmpty()){
                try{
                    String path=(ep?"/shows/":"/movies/")+rawId+"?extended=full";
                    t.image=posterUrl(api.get(path));
                }catch(Exception ignored){}
            }
            if(t.image.isEmpty()){ 
                String imdb=ids.optString("imdb","");
                if(imdb.matches("tt[0-9]{5,12}"))
                    t.image="https://images.metahub.space/poster/medium/"+imdb+"/img";
            }
            items.put(key,t);
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
        if(destination.exists()&&destination.length()>1000 && System.currentTimeMillis()-destination.lastModified()<7L*86400000)return true;
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
        boolean real=bmp!=null;
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
    private static void updateCards(Context c,long channel,ArrayList<Item> cards)throws Exception {
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
            values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT,999-i);
            Uri poster=Uri.parse("content://com.fuoconero.tvbridge.images/posters/"+item.id+".png");
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,poster.toString());
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,TvContract.PreviewPrograms.ASPECT_RATIO_2_3);
            Intent intent=new Intent(c,OpenContentActivity.class);
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
        for(Map.Entry<String,Long> old:existing.entrySet())if(!retained.contains(old.getKey()))
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

package com.fuoconero.tvbridge;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.tv.TvContract;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

final class BridgeStore {
    private static final String APP="com.fuoconero.tvbridge";
    private static final String CHANNEL_NAME="Fuoconero · Continua a guardare";
    private static final String MARKER="fuoconero-trakt";
    interface JsonClient { JSONObject fetch(String method,String path)throws Exception; }
    interface Progress { void status(String text); }
    static class Item {
        String id, title, poster, description, imdb;
        boolean show;
    }
    static class Stats {
        int traktCount,created,updated,errors,readBack,downloaded,fallback;
        String lastError;
    }

    static List<Item> collect(Context ctx,String clientId,String accessToken,JsonClient api)throws Exception {
        LinkedHashMap<String,Item> map=new LinkedHashMap<>();
        try{
            readArray(map,api.fetch("GET","/sync/playback?extended=full").optJSONArray("data"));
        }catch(Exception e){
            // The playback endpoint can be empty or unavailable for some accounts.
            if(!e.toString().contains("404") && !e.toString().contains("403"))throw e;
        }
        try{
            readArray(map,api.fetch("GET","/sync/history?limit=60&extended=full").optJSONArray("data"));
        }catch(Exception e){
            if(map.isEmpty())throw e;
        }
        ArrayList<Item> all=new ArrayList<>(map.values());
        if(all.size()>30)all=new ArrayList<>(all.subList(0,30));
        return all;
    }
    private static void readArray(LinkedHashMap<String,Item> out, JSONArray arr){
        if(arr==null)return;
        for(int i=0;i<arr.length();i++){
            JSONObject row=arr.optJSONObject(i);if(row==null)continue;
            JSONObject show=row.optJSONObject("show");
            JSONObject movie=row.optJSONObject("movie");
            JSONObject media=show!=null?show:movie;
            boolean isShow=show!=null;
            if(media==null)continue;
            JSONObject ids=media.optJSONObject("ids");
            String key="";
            String imdb="";
            if(ids!=null){
                key=ids.optString("trakt", "");
                imdb=ids.optString("imdb","");
                if(key.isEmpty())key=ids.optString("tmdb","");
            }
            if(key.isEmpty())key=media.optString("title","");
            if(key.isEmpty())continue;
            String unique=(isShow?"show:":"movie:")+key;
            if(out.containsKey(unique))continue;
            Item it=new Item();it.id=unique.replace(':','_');
            it.title=media.optString("title","Titolo sconosciuto");
            it.imdb=imdb;it.show=isShow;
            it.description=media.optString("overview",media.optString("tagline",""));
            JSONObject imgs=media.optJSONObject("images");
            if(imgs!=null){
                JSONObject poster=imgs.optJSONObject("poster");
                it.poster=poster==null?imgs.optString("poster",""):poster.optString("full",poster.optString("medium",poster.optString("thumb","")));
            }
            if(it.poster==null)it.poster="";
            if(!it.poster.startsWith("https://"))it.poster="";
            out.put(unique,it);
        }
    }

    static Stats publish(Context ctx,List<Item> items,Progress progress) throws Exception {
        Stats result=new Stats();result.traktCount=items.size();
        ContentResolver resolver=ctx.getContentResolver();
        long channel=getOrCreateChannel(ctx);
        HashMap<String,Long> previous=new HashMap<>();
        ArrayList<Long> obsolete=new ArrayList<>();
        try(Cursor c=resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(c!=null)while(c.moveToNext()){
                if(c.getLong(1)!=channel)continue;
                String marker=c.getString(2);
                if(marker!=null&&marker.startsWith("fuoconero:")){
                    if(!previous.containsKey(marker))previous.put(marker,c.getLong(0));
                    else obsolete.add(c.getLong(0));
                }
            }
        }
        HashSet<String> wanted=new HashSet<>();
        for(int i=0;i<items.size();i++){
            Item item=items.get(i);
            String mark="fuoconero:"+item.id;
            wanted.add(mark);
            try{
                File file=poster(ctx,item);
                if(file.getName().endsWith(".jpg"))result.downloaded++;
                else result.fallback++;
                Uri art=new Uri.Builder().scheme("content").authority("com.fuoconero.tvbridge.images")
                        .appendPath("posters").appendPath(file.getName()).build();
                Intent intent=new Intent(ctx,OpenContentActivity.class);
                intent.setAction("com.fuoconero.tvbridge.OPEN_VEEZIE");
                ContentValues v=new ContentValues();
                v.put("channel_id",channel);
                v.put("internal_provider_id",mark);
                v.put("type",item.show?3:0);
                v.put("title",item.title);
                v.put("short_description",item.description);
                v.put("poster_art_uri",art.toString());
                v.put("poster_art_aspect_ratio",0);
                v.put("weight",1000-i);
                v.put("intent_uri",intent.toUri(Intent.URI_INTENT_SCHEME));
                Long id=previous.get(mark);
                if(id!=null){
                    int count=resolver.update(TvContract.buildPreviewProgramUri(id),v,null,null);
                    if(count>0)result.updated++;
                    else {
                        Uri n=resolver.insert(TvContract.PreviewPrograms.CONTENT_URI,v);
                        if(n==null)throw new Exception("insert returned null");
                        result.created++;
                    }
                }else{
                    Uri n=resolver.insert(TvContract.PreviewPrograms.CONTENT_URI,v);
                    if(n==null)throw new Exception("insert returned null");
                    result.created++;
                }
            }catch(Exception e){
                result.errors++;result.lastError=item.title+": "+e.toString();
            }
            if(i%3==0)progress.status("Locandine "+(i+1)+"/"+items.size()+" | create "+result.created+" | aggiorna "+result.updated+" | errori "+result.errors);
        }
        for(Map.Entry<String,Long> e:previous.entrySet()){
            if(!wanted.contains(e.getKey()))obsolete.add(e.getValue());
        }
        for(Long id:obsolete){
            try{resolver.delete(TvContract.buildPreviewProgramUri(id),null,null);}catch(Exception ignore){}
        }
        // Essential diagnostic: query from the app process, not the adb shell.
        try(Cursor c=resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(c!=null)while(c.moveToNext()){
                if(c.getLong(1)==channel){
                    String marker=c.getString(2);
                    if(marker!=null&&marker.startsWith("fuoconero:")) result.readBack++;
                }
            }
        }catch(Exception e){result.lastError="Lettura diagnostica: "+e.toString();}
        return result;
    }

    private static File poster(Context ctx,Item item)throws Exception {
        File dir=new File(ctx.getFilesDir(),"posters");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("cannot create poster directory");
        String safe=item.id.replaceAll("[^a-zA-Z0-9_]","_");
        File jpeg=new File(dir,safe+".jpg");
        if(jpeg.isFile()&&jpeg.length()>1000)return jpeg;
        File fallback=new File(dir,safe+".png");
        if(fallback.isFile()&&fallback.length()>1000)return fallback;
        String link=item.poster;
        if(link.isEmpty() && item.imdb!=null && item.imdb.startsWith("tt")){
            link="https://images.metahub.space/poster/medium/"+item.imdb;
        }
        if(link.startsWith("https://")){
            try{
                HttpURLConnection h=(HttpURLConnection)new URL(link).openConnection();
                h.setConnectTimeout(5000);h.setReadTimeout(7000);
                h.setRequestProperty("User-Agent","FuoconeroTVBridge/0.2.2");
                try(InputStream in=h.getInputStream()){
                    Bitmap raw=BitmapFactory.decodeStream(in);
                    if(raw!=null){
                        Bitmap scaled=Bitmap.createScaledBitmap(raw,400,600,true);
                        try(OutputStream out=new FileOutputStream(jpeg)){
                            scaled.compress(Bitmap.CompressFormat.JPEG,85,out);
                        }
                        h.disconnect();return jpeg;
                    }
                }
                h.disconnect();
            }catch(Exception ignore){}
        }
        Bitmap bmp=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bmp);
        canvas.drawColor(Color.rgb(20,24,43));
        Paint p=new Paint(3);
        p.setColor(Color.rgb(255,121,54));
        canvas.drawRect(0,0,400,14,p);
        p.setTextSize(30);p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("FUOCONERO",22,85,p);
        p.setColor(Color.WHITE);p.setTextSize(27);
        String title=item.title==null?"":item.title;
        int pos=0,y=178;
        while(pos<title.length() && y<480){
            int end=Math.min(title.length(),pos+21);
            if(end<title.length()){int space=title.lastIndexOf(' ',end);if(space>pos)end=space;}
            canvas.drawText(title.substring(pos,end),22,y,p);
            pos=end;while(pos<title.length()&&title.charAt(pos)==' ')pos++;y+=45;
        }
        p.setColor(Color.rgb(177,188,209));p.setTextSize(22);
        canvas.drawText(item.show?"SERIE TV • Trakt":"FILM • Trakt",22,550,p);
        try(OutputStream out=new FileOutputStream(fallback)){
            bmp.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        return fallback;
    }

    static long getOrCreateChannel(Context ctx)throws Exception {
        ContentResolver r=ctx.getContentResolver();
        try(Cursor c=r.query(TvContract.Channels.CONTENT_URI,
              new String[]{"_id","internal_provider_id","package_name"},null,null,null)){
          if(c!=null)while(c.moveToNext()){
            String tag=c.getString(1);
            String pkg=c.getString(2);
            if(APP.equals(pkg)&&tag!=null&&tag.startsWith(MARKER))return c.getLong(0);
          }
        }
        ContentValues cv=new ContentValues();
        cv.put("type","TYPE_PREVIEW");
        cv.put("display_name",CHANNEL_NAME);
        cv.put("internal_provider_id","fuoconero-trakt-022");
        cv.put("app_link_intent_uri",new Intent(ctx,MainActivity.class).toUri(Intent.URI_INTENT_SCHEME));
        Uri created=r.insert(TvContract.Channels.CONTENT_URI,cv);
        if(created==null)throw new Exception("Android TV refused preview channel");
        long id=Long.parseLong(created.getLastPathSegment());
        try{TvContract.requestChannelBrowsable(ctx,id);}catch(Exception ignore){}
        return id;
    }

    static void remove(Context ctx)throws Exception{
        ContentResolver r=ctx.getContentResolver();long id=-1;
        try(Cursor c=r.query(TvContract.Channels.CONTENT_URI,
           new String[]{"_id","internal_provider_id","package_name"},null,null,null)){
           if(c!=null)while(c.moveToNext()){
              String tag=c.getString(1),pkg=c.getString(2);
              if(APP.equals(pkg)&&tag!=null&&tag.startsWith(MARKER)){id=c.getLong(0);break;}
           }
        }
        if(id>=0)r.delete(TvContract.buildChannelUri(id),null,null);
    }
}

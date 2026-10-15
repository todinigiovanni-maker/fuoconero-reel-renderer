package com.fuoconero.tvbridge;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.tv.TvContract;
import android.net.Uri;
import android.text.Html;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Public catalog metadata only. Playback remains in the user's Veezie installation. */
public final class VeezieNews {
    private static final String[] KEYS={"cb01","euro"};
    private static final String[] URLS={"https://cb01uno.vip/","https://eurostream.pics/"};
    private static final String[] LABELS={"CB01 · Film recenti","Eurostreaming · Serie aggiornate"};
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean();
    static final class Item {String title,url,image;boolean series;}
    static String searchTitle(String title){return title.replaceAll("\\s*\\((?:19|20)\\d{2}\\)\\s*$","").trim();}
    private static String plain(String s){return Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();}
    private static String match(String regex,String s){Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(s);return m.find()?m.group(1):"";}
    static ArrayList<Item> parse(String html,boolean euro){
        ArrayList<Item> result=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        String pattern=euro?"<li\\b[^>]*id=[\"']post-[^\"']+[\"'][^>]*>(.*?)</li>":"<div\\b[^>]*class=[\"']card mp-post horizontal[\"'][^>]*>(.*?</h3>)";
        Matcher blocks=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
        while(blocks.find()&&result.size()<12){
            String block=blocks.group(1),heading=match(euro?"<h2[^>]*>(.*?)</h2>":"<h3[^>]*>(.*?)</h3>",block);
            Item item=new Item();item.url=plain(match("<a[^>]*href=[\"']([^\"']+)[\"']",heading));
            item.title=plain(heading).replaceAll("(?i)\\s*\\[(?:HD|SD|4K|FULL HD|SUB ITA)\\]"," ").replaceAll("\\s+"," ").trim();
            item.image=plain(match("<img[^>]*src=[\"']([^\"']+)[\"']",block));item.series=euro;
            try{
                URI page=new URI(item.url);String host=page.getHost();
                if(!"https".equals(page.getScheme())||!(euro?"eurostream.pics":"cb01uno.vip").equalsIgnoreCase(host))continue;
                if(item.title.length()<2||item.title.length()>200||!seen.add(item.url))continue;
                URI art=new URI(item.image);if(!"https".equals(art.getScheme())||!host.equalsIgnoreCase(art.getHost()))item.image="";
                result.add(item);
            }catch(Exception ignored){}
        }
        return result;
    }
    private static byte[] fetch(String url,int max)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","Mozilla/5.0 FuoconeroTV/0.4.3");
        try{
            if(c.getResponseCode()!=200)throw new IOException("Catalogo non raggiungibile");
            if(c.getContentLengthLong()>max)throw new IOException("Risposta troppo grande");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Risposta troppo grande");out.write(b,0,n);}return out.toByteArray();
            }
        }finally{c.disconnect();}
    }
    public static void refresh(Context c,boolean force){
        if(!BUSY.compareAndSet(false,true))return;
        try{
            SharedPreferences p=c.getSharedPreferences("veezie_news",Context.MODE_PRIVATE);
            for(int i=0;i<KEYS.length;i++){
                String key=KEYS[i];long now=System.currentTimeMillis();
                if(!force&&now-p.getLong(key+"_success",0)<3600000L)continue;
                if(!force&&now-p.getLong(key+"_attempt",0)<900000L)continue;
                p.edit().putLong(key+"_attempt",now).apply();
                try{
                    ArrayList<Item> items=parse(new String(fetch(URLS[i],2*1024*1024),StandardCharsets.UTF_8),i==1);
                    if(items.isEmpty())throw new IOException("Nessuna novita leggibile");
                    publish(c,i,items);
                    p.edit().putLong(key+"_success",System.currentTimeMillis()).putInt(key+"_count",items.size()).remove(key+"_error").apply();
                }catch(Exception e){p.edit().putString(key+"_error",e.getClass().getSimpleName()).apply();}
            }
        }finally{BUSY.set(false);}
    }
    private static String id(String url)throws Exception{
        byte[] h=java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();
        for(int n=0;n<12;n++)s.append(String.format(java.util.Locale.ROOT,"%02x",h[n]&255));return s.toString();
    }
    private static String artwork(Context c,Item item,String id)throws Exception{
        File folder=new File(c.getFilesDir(),"posters");folder.mkdirs();File file=new File(folder,"news_source_"+id+".png");
        if(!file.isFile()&&!item.image.isEmpty())try{
            byte[] data=fetch(item.image,2*1024*1024);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(o.outWidth>0&&o.outHeight>0){o.inSampleSize=1;while(o.outWidth/o.inSampleSize>1000||o.outHeight/o.inSampleSize>1500)o.inSampleSize*=2;o.inJustDecodeBounds=false;
                Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,o);
                if(bitmap!=null)try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,90,out);}finally{bitmap.recycle();}
            }
        }catch(Exception ignored){}
        return RecentStore.image(c,file.getName());
    }
    private static void publish(Context c,int source,ArrayList<Item> items)throws Exception{
        SharedPreferences p=c.getSharedPreferences("veezie_news",Context.MODE_PRIVATE);String key=KEYS[source];long channel=p.getLong(key+"_channel",-1);
        if(channel>0)try(Cursor q=c.getContentResolver().query(TvContract.buildChannelUri(channel),new String[]{"_id"},null,null,null)){if(q==null||!q.moveToFirst())channel=-1;}
        if(channel<0){
            ContentValues row=new ContentValues();row.put("type",TvContract.Channels.TYPE_PREVIEW);row.put("display_name","Fuoconero · Veezie · "+LABELS[source]);row.put("internal_provider_id","fuoconero-news-"+key);
            Intent launch=c.getPackageManager().getLaunchIntentForPackage("st.veezie");if(launch!=null)row.put("app_link_intent_uri",launch.toUri(Intent.URI_INTENT_SCHEME));
            Uri uri=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,row);if(uri==null)throw new IOException("Canale non creato");channel=ContentUris.parseId(uri);p.edit().putLong(key+"_channel",channel).apply();
            try{TvContract.requestChannelBrowsable(c,channel);}catch(Exception ignored){}
        }
        HashMap<String,Long> existing=new HashMap<>();
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&q.getString(2)!=null&&q.getString(2).startsWith("news:"))existing.put(q.getString(2),q.getLong(0));
        }
        int order=0;
        for(Item item:items){
            String hash=id(item.url),internal="news:"+key+":"+hash;
            Intent open=new Intent(c,OpenContentActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse("fuoconero://news/"+key+"/"+hash)).putExtra("title",searchTitle(item.title)).putExtra("episode",item.series).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(item.series)open.putExtra("season","1").putExtra("episode_number","");
            String poster=WideArtwork.render(c,"news_"+hash,item.title,"Ultimi aggiornamenti su "+(source==0?"CB01":"Eurostreaming"),artwork(c,item,hash),-1,"APRI IN VEEZIE");
            ContentValues v=new ContentValues();v.put("channel_id",channel);v.put("internal_provider_id",internal);v.put("title",item.title);v.put("short_description","Dal catalogo "+LABELS[source]+" · Apri in Veezie");v.put("type",item.series?TvContract.PreviewPrograms.TYPE_TV_SERIES:TvContract.PreviewPrograms.TYPE_MOVIE);v.put("weight",1000-order++);v.put("poster_art_uri",poster);v.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);v.put("intent_uri",open.toUri(Intent.URI_INTENT_SCHEME));
            Long old=existing.remove(internal);if(old==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,v);else{v.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(old),v,null,null);}
        }
        for(long old:existing.values())c.getContentResolver().delete(TvContract.buildPreviewProgramUri(old),null,null);
        RefreshActionCard.publish(c,channel);
    }
}


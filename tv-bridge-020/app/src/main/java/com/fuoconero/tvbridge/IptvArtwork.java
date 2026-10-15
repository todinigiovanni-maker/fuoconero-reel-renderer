package com.fuoconero.tvbridge;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/** Read artwork for the small home preview selection, without importing the catalog again. */
public final class IptvArtwork {
    private final HashMap<Long,String> images=new HashMap<>();
    private static final Pattern LOGO=Pattern.compile("(?:^|\\s)tvg-logo=\"([^\"]*)\"");
    public static void backfillEpgIds(Context ctx){
        android.content.SharedPreferences p=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
        long imported=p.getLong("last_success",0);
        if(imported<=0||p.getLong("tvg_backfill_at",0)>=imported)return;
        Pattern id=Pattern.compile("(?:^|\\s)tvg-id=\"([^\"]*)\"");
        try(IptvStore store=new IptvStore(ctx)){
            HashMap<String,ArrayList<Long>> wanted=new HashMap<>();
            try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT id,url FROM entries WHERE kind='TV' AND adult=0 AND tvg_id=''",null)){
                while(c.moveToNext())wanted.computeIfAbsent(c.getString(1),k->new ArrayList<>()).add(c.getLong(0));
            }
            String path=p.getString("source_path",""),uri=p.getString("source_uri","");
            InputStream input=!path.isEmpty()?new FileInputStream(path):!uri.isEmpty()?ctx.getContentResolver().openInputStream(Uri.parse(uri)):null;
            if(input==null)return;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"),65536)){
                String line,tvg="";
                while((line=reader.readLine())!=null&&!wanted.isEmpty()){
                    if(line.startsWith("#EXTINF:")){Matcher m=id.matcher(line);tvg=m.find()?m.group(1).trim():"";}
                    else if(!line.startsWith("#")){
                        ArrayList<Long> keys=wanted.remove(line.trim());
                        if(keys!=null&&!tvg.isEmpty()&&tvg.length()<=200)for(Long key:keys)
                            store.getWritableDatabase().execSQL("UPDATE entries SET tvg_id=? WHERE id=?",new Object[]{tvg,key});
                        tvg="";
                    }
                }
            }
            p.edit().putLong("tvg_backfill_at",System.currentTimeMillis()).apply();
        }catch(Exception e){android.util.Log.w("Fuoconero043","Identificativi EPG non ancora disponibili");}
    }
    public IptvArtwork(Context ctx,IptvStore store){
        HashMap<String,ArrayList<Long>> wanted=new HashMap<>();
        try{
            for(String category:IptvStore.PUBLIC_GROUPS){
                if(category.startsWith("Adulti"))continue;
                ArrayList<IptvStore.Media> items=new ArrayList<>();
                if(category.equals("Serie")||category.equals("Ultime serie")){
                    try(IptvLibrary lib=new IptvLibrary(ctx)){
                        for(IptvLibrary.Show show:lib.shows("",0,12,category.equals("Ultime serie"))){
                            IptvStore.Media media=store.find(show.newestId);
                            if(media!=null)items.add(media);
                        }
                    }
                }else if(category.equals("Ultimi film")){
                    try(IptvLibrary lib=new IptvLibrary(ctx)){items=lib.newestFilms(0,12);}
                }else if(category.equals("Continua")){
                    try(IptvLibrary lib=new IptvLibrary(ctx)){
                        for(IptvLibrary.Recent recent:lib.recentlyOpened(12))items.add(recent.media);
                    }
                }else items=store.list(category,"",0,12);
                try(IptvLibrary lib=new IptvLibrary(ctx)){
                    for(IptvStore.Media media:items){
                        String image=lib.artwork(media.id);
                        if(!media.adult && image!=null && !image.isEmpty())images.put(media.id,image);
                    }
                }
                for(IptvStore.Media media:items){
                    if(media.adult||media.url==null)continue;
                    ArrayList<Long> ids=wanted.get(media.url);
                    if(ids==null){ids=new ArrayList<>();wanted.put(media.url,ids);}
                    ids.add(media.id);
                }
            }
            android.content.SharedPreferences p=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
            String path=p.getString("source_path",""),uri=p.getString("source_uri","");
            InputStream input=!path.isEmpty()?new FileInputStream(path):
                !uri.isEmpty()?ctx.getContentResolver().openInputStream(Uri.parse(uri)):null;
            if(input==null)return;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"),65536)){
                String line,logo="";
                while((line=reader.readLine())!=null&&!wanted.isEmpty()){
                    if(line.startsWith("#EXTINF:")){
                        Matcher m=LOGO.matcher(line);logo=m.find()?m.group(1).trim():"";
                    }else if(!line.startsWith("#")){
                        ArrayList<Long> ids=wanted.remove(line.trim());
                        if(ids!=null&&(logo.startsWith("https://")||logo.startsWith("http://"))&&logo.length()<2048)
                            for(Long id:ids)if(!images.containsKey(id))images.put(id,logo);
                        logo="";
                    }
                }
            }
            Log.i("FuoconeroIPTV","Immagini playlist disponibili per "+images.size()+" anteprime");
        }catch(Exception e){Log.w("FuoconeroIPTV","Lettura immagini: "+e.getClass().getSimpleName());}
    }
    public boolean cover(Context ctx,IptvStore.Media media,File target){
        if(media.adult)return false;
        File marker=new File(target.getParentFile(),"art_"+media.id+".ok");
        if(marker.isFile()&&target.isFile())return true;
        String url=images.get(media.id);
        if(url==null)return false;
        HttpURLConnection connection=null;
        Bitmap decoded=null,card=null;
        try{
            connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent","FuoconeroIPTV/0.4.3");
            if(connection.getResponseCode()!=200)throw new IOException("HTTP image");
            if(connection.getContentLengthLong()>2*1024*1024)throw new IOException("Image too large");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){
                    if(bytes.size()+n>2*1024*1024)throw new IOException("Image too large");
                    bytes.write(buffer,0,n);
                }
            }
            byte[] data=bytes.toByteArray();
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,options);
            if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Invalid image");
            options.inSampleSize=1;
            while(options.outWidth/options.inSampleSize>1200||options.outHeight/options.inSampleSize>1800)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            decoded=BitmapFactory.decodeByteArray(data,0,data.length,options);
            if(decoded==null)throw new IOException("Invalid bitmap");
            card=Bitmap.createBitmap(360,540,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(card);canvas.drawColor(0xff162033);
            boolean tv="TV".equals(media.kind);
            float maxW=tv?320:360,maxH=tv?330:540;
            float scale=Math.min(maxW/decoded.getWidth(),maxH/decoded.getHeight());
            float w=decoded.getWidth()*scale,h=decoded.getHeight()*scale;
            float top=tv?70+(330-h)/2:(540-h)/2;
            canvas.drawBitmap(decoded,null,new RectF((360-w)/2,top,(360+w)/2,top+h),new Paint(3));
            if(tv){
                Paint paint=new Paint(3);paint.setColor(Color.WHITE);paint.setTextSize(22);
                paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
                String title=media.title;
                canvas.drawText(title.length()>24?title.substring(0,24):title,18,470,paint);
                paint.setTextSize(16);paint.setColor(0xffff984c);
                canvas.drawText("FUOCONERO IPTV",18,510,paint);
            }
            File temporary=new File(target.getParentFile(),target.getName()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temporary)){
                if(!card.compress(Bitmap.CompressFormat.PNG,90,out))throw new IOException("Encode image");
            }
            if(!temporary.renameTo(target))throw new IOException("Save image");
            try(FileOutputStream out=new FileOutputStream(marker)){out.write(1);}
            return true;
        }catch(Exception e){Log.w("FuoconeroIPTV","Immagine "+media.id+": "+e.getClass().getSimpleName());return false;}
        finally{if(connection!=null)connection.disconnect();if(decoded!=null)decoded.recycle();if(card!=null)card.recycle();}
    }
}


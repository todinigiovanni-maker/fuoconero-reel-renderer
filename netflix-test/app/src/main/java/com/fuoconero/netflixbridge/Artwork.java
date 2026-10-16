package com.fuoconero.netflixbridge;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.text.*;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
public final class Artwork {
    static boolean allowedHost(String h) {
        if(h==null)return false;
        for(String root:new String[]{"netflix.com","nflximg.net","nflximg.com","nflxso.net","nflxext.com"})
            if(h.equals(root)||h.endsWith("."+root))return true;
        return false;
    }
    static Bitmap load(Context c,String source) {
        if(source==null||source.isEmpty())return null;
        HttpsURLConnection conn=null;
        try {
            Uri u=Uri.parse(source);InputStream in;
            if("content".equals(u.getScheme()))in=c.getContentResolver().openInputStream(u);
            else if("https".equals(u.getScheme())&&allowedHost(u.getHost())) {
                conn=(HttpsURLConnection)new java.net.URL(source).openConnection();
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(3000);conn.setReadTimeout(3000);
                if(conn.getResponseCode()!=200)return null;
                in=conn.getInputStream();
            }else return null;
            if(in==null)return null;
            byte[] data;
            try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buf=new byte[8192];int n,total=0;
                while((n=input.read(buf))!=-1){total+=n;if(total>4*1024*1024)return null;out.write(buf,0,n);}
                data=out.toByteArray();
            }
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(o.outWidth<=0||o.outHeight<=0)return null;
            o.inSampleSize=1;
            while(o.outWidth/o.inSampleSize>1920||o.outHeight/o.inSampleSize>1080)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(data,0,data.length,o);
        }catch(Exception e){return null;}finally{if(conn!=null)conn.disconnect();}
    }
    static void text(Canvas canvas,String value,int y,int size,int lines) {
        TextPaint p=new TextPaint(3);p.setColor(Color.WHITE);p.setTextSize(size);p.setTypeface(Typeface.DEFAULT_BOLD);
        StaticLayout s=StaticLayout.Builder.obtain(value,0,value.length(),p,860).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        canvas.save();canvas.translate(50,y);s.draw(canvas);canvas.restore();
    }
    static String render(Context c,String name,String title,String detail,String source,long position,long duration)throws IOException {
        File folder=new File(c.getFilesDir(),"art");folder.mkdirs();
        Bitmap b=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888),poster=load(c,source);
        try {
            Canvas canvas=new Canvas(b);canvas.drawColor(0xff121218);Paint p=new Paint(3);
            if(poster!=null) {
                // Fit the actual image without stretching; the card itself is always 16:9.
                float scale=Math.min(960f/poster.getWidth(),540f/poster.getHeight());
                float w=poster.getWidth()*scale,h=poster.getHeight()*scale;
                canvas.drawBitmap(poster,null,new RectF((960-w)/2,(540-h)/2,(960+w)/2,(540+h)/2),p);
                p.setShader(new LinearGradient(0,100,0,540,0x22121218,0xf0121218,Shader.TileMode.CLAMP));canvas.drawRect(0,0,960,540,p);p.setShader(null);
            }
            text(canvas,"NETFLIX · FUOCONERO TEST",35,24,1);text(canvas,title,255,46,2);
            text(canvas,detail,390,26,2);
            if(poster==null&&!source.isEmpty())text(canvas,"Immagine non disponibile",190,22,1);
            if(duration>0&&position>0&&position<=duration){p.setColor(0xff555555);canvas.drawRect(50,495,910,507,p);p.setColor(0xffe50914);canvas.drawRect(50,495,50+860f*position/duration,507,p);}
            File f=new File(folder,name+".png"),temp=new File(folder,name+".tmp");
            try(FileOutputStream stream=new FileOutputStream(temp)){if(!b.compress(Bitmap.CompressFormat.PNG,95,stream))throw new IOException();}
            if(!temp.renameTo(f))throw new IOException("Artwork save failed");
            return "content://"+c.getPackageName()+".art/"+f.getName();
        }finally{b.recycle();if(poster!=null)poster.recycle();}
    }
}

package com.fuoconero.tvbridge;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.net.Uri;
import java.io.*;
import java.util.Locale;

/** Landscape derivative; original posters remain untouched. */
public final class WideArtwork {
    private WideArtwork(){}
    public static String clock(long ms){
        long s=Math.max(0,ms/1000L);
        return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):
            String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);
    }
    public static String render(Context c,String id,String title,String description,String source,double percent,String label)throws Exception{
        String safe=id.replaceAll("[^a-zA-Z0-9_-]","_");if(safe.length()>70)safe=safe.substring(0,70);
        File folder=new File(c.getFilesDir(),"posters");folder.mkdirs();
        File out=new File(folder,"wide_"+safe+".png");
        Bitmap b=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888),image=null;
        try{
            Canvas canvas=new Canvas(b);canvas.drawColor(0xff101115);
            if(source!=null&&!source.isEmpty()){
                try(InputStream input=c.getContentResolver().openInputStream(Uri.parse(source))){
                    BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;image=BitmapFactory.decodeStream(input,null,o);
                }catch(Exception ignored){}
            }
            int left=36,width=888;
            if(image!=null){
                Paint paint=new Paint(3);
                if(image.getWidth()>image.getHeight()*1.4){
                    canvas.drawBitmap(image,null,new RectF(0,0,960,540),paint);
                    paint.setColor(0xc9101115);canvas.drawRect(0,0,960,540,paint);
                }else{
                    float scale=Math.min(320f/image.getWidth(),480f/image.getHeight());
                    float w=image.getWidth()*scale,h=image.getHeight()*scale;
                    canvas.drawBitmap(image,null,new RectF((340-w)/2,(540-h)/2,(340+w)/2,(540+h)/2),paint);
                    left=370;width=550;
                }
            }
            text(canvas,title,left,42,width,48,Color.WHITE,3);
            text(canvas,description==null?"":description,left,244,width,29,0xffd0d4df,4);
            Paint paint=new Paint(3);paint.setColor(0xffff8b43);paint.setTextSize(26);
            canvas.drawText(label==null?"FUOCONERO":label,left,480,paint);
            if(percent>=0&&percent<=100){
                paint.setColor(0xff53565f);canvas.drawRoundRect(left,501,924,519,8,8,paint);
                paint.setColor(0xffff8b43);canvas.drawRoundRect(left,501,left+(924-left)*(float)percent/100f,519,8,8,paint);
            }
            File temp=new File(folder,out.getName()+".tmp");
            try(FileOutputStream stream=new FileOutputStream(temp)){if(!b.compress(Bitmap.CompressFormat.PNG,95,stream))throw new IOException("Image encoding");}
            if(!temp.renameTo(out))throw new IOException("Image save");
            return new Uri.Builder().scheme("content").authority(c.getPackageName()+".images")
                .appendPath("posters").appendPath(out.getName()).appendQueryParameter("v",String.valueOf(out.lastModified())).build().toString();
        }finally{b.recycle();if(image!=null)image.recycle();}
    }
    private static void text(Canvas c,String s,int x,int y,int width,int size,int color,int lines){
        TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        StaticLayout layout=StaticLayout.Builder.obtain(s,0,s.length(),p,width).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        c.save();c.translate(x,y);layout.draw(c);c.restore();
    }
}


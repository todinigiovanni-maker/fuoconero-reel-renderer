package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Up to 12 previews per public category. Private/adult entries are never published. */
public final class IptvPublisher {
    private static final String AUTHORITY="com.fuoconero.tvbridge.test042.images";
    private IptvPublisher(){}
    public static String publish(Context ctx)throws Exception{
        IptvStore store=new IptvStore(ctx);
        SharedPreferences prefs=ctx.getSharedPreferences("iptv_channels",Context.MODE_PRIVATE);
        String custom=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE)
            .getString("display_name","").trim();
        if(custom.isEmpty())custom="IPTV";
        if(custom.length()>42)custom=custom.substring(0,42);
        String userPlaylist=custom;
        int all=0,groups=0;
        for(String category:IptvStore.PUBLIC_GROUPS){
            ArrayList<IptvStore.Media> items=store.list(category,"",0,12);
            if(items.isEmpty())continue;
            String key="channel_"+category.replace(' ','_');
            long id=prefs.getLong(key,-1L);
            if(id>0){
                try(Cursor old=ctx.getContentResolver().query(TvContract.buildChannelUri(id),
                    new String[]{TvContract.Channels._ID},null,null,null)){
                    if(old==null||!old.moveToFirst())id=-1L;
                }catch(Exception ignored){id=-1L;}
            }
            // Rename existing Projectivy rows without losing program IDs.
            if(id>0){
                ContentValues rename=new ContentValues();
                rename.put(TvContract.Channels.COLUMN_DISPLAY_NAME,
                    "Fuoconero · "+userPlaylist+" · "+category);
                ctx.getContentResolver().update(TvContract.buildChannelUri(id),
                    rename,null,null);
            }
            if(id<0){
                ContentValues row=new ContentValues();
                row.put(TvContract.Channels.COLUMN_TYPE,TvContract.Channels.TYPE_PREVIEW);
                row.put(TvContract.Channels.COLUMN_DISPLAY_NAME,"Fuoconero · "+userPlaylist+" · "+category);
                row.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,"fuoconero-iptv-042-"+category);
                Intent home=new Intent(ctx,IptvActivity.class);
                home.putExtra("category",category);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                row.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,home.toUri(Intent.URI_INTENT_SCHEME));
                Uri created=ctx.getContentResolver().insert(TvContract.Channels.CONTENT_URI,row);
                if(created==null)throw new Exception("Non riesco a creare il canale "+category);
                id=ContentUris.parseId(created);
                prefs.edit().putLong(key,id).apply();
                try{
                    Bitmap logo=renderPoster("FUOCONERO","IPTV · "+category);
                    try(java.io.OutputStream out=ctx.getContentResolver().openOutputStream(TvContract.buildChannelLogoUri(id))){
                        if(out!=null)logo.compress(Bitmap.CompressFormat.PNG,90,out);
                    }
                    logo.recycle();
                }catch(Exception ignored){}
            }
            HashMap<String,Long> existing=new HashMap<>();
            try(Cursor c=ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms._ID,
                    TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                    TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    if(c.getLong(1)==id && c.getString(2)!=null &&
                        c.getString(2).startsWith("fuoconero:iptv:"))
                        existing.put(c.getString(2),c.getLong(0));
                }
            }
            HashSet<String> retained=new HashSet<>();
            int order=0;
            for(IptvStore.Media media:items){
                if(media.adult)continue;
                String internal="fuoconero:iptv:"+media.id;
                retained.add(internal);
                File poster=posterFile(ctx,media.id);
                if(!poster.isFile()){
                    Bitmap image=renderPoster(category,media.title);
                    try(FileOutputStream out=new FileOutputStream(poster)){
                        image.compress(Bitmap.CompressFormat.PNG,90,out);
                    }finally{image.recycle();}
                }
                Uri photo=new Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath("posters").appendPath(poster.getName())
                    .appendQueryParameter("v",String.valueOf(poster.lastModified())).build();
                Intent open=new Intent(ctx,IptvPlayActivity.class);
                open.setAction(Intent.ACTION_VIEW);
                open.setData(Uri.parse("fuoconero-iptv://play/"+media.id));
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                open.putExtra("id",media.id);
                ContentValues values=new ContentValues();
                values.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,id);
                values.put(TvContract.PreviewPrograms.COLUMN_TYPE,
                    "TV".equals(media.kind)?TvContract.PreviewPrograms.TYPE_CHANNEL:
                    TvContract.PreviewPrograms.TYPE_MOVIE);
                values.put(TvContract.PreviewPrograms.COLUMN_TITLE,media.title);
                values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                    media.group+" · Seleziona un lettore compatibile");
                values.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,internal);
                values.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID,"iptv:"+media.id);
                values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT,1000-(order++));
                values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,photo.toString());
                values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_2_3);
                values.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                    open.toUri(Intent.URI_INTENT_SCHEME));
                Long before=existing.get(internal);
                if(before!=null){
                    values.remove(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID);
                    ctx.getContentResolver().update(
                        TvContract.buildPreviewProgramUri(before),values,null,null);
                }else{
                    ctx.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values);
                }
                all++;
            }
            for(String old:existing.keySet()){
                if(!retained.contains(old))
                    ctx.getContentResolver().delete(
                        TvContract.buildPreviewProgramUri(existing.get(old)),null,null);
            }
            groups++;
        }
        return "Projectivy: "+all+" anteprime pubblicate in "+groups+
            " categorie; nessuna immagine per adulti nella home.";
    }
    private static File posterFile(Context ctx,long id){
        File dir=new File(ctx.getFilesDir(),"posters");
        if(!dir.isDirectory())dir.mkdirs();
        return new File(dir,"iptv_"+id+".png");
    }
    private static Bitmap renderPoster(String cat,String title){
        Bitmap b=Bitmap.createBitmap(360,540,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);
        canvas.drawColor(0xff162033);
        Paint p=new Paint(3);
        p.setColor(0xffff8c48);
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextSize(26);
        canvas.drawText("FUOCONERO IPTV",20,65,p);
        p.setColor(Color.WHITE);
        p.setTextSize(22);
        canvas.drawText(cat.length()>22?cat.substring(0,22):cat,20,125,p);
        p.setTextSize(30);
        String remaining=title.length()>95?title.substring(0,95)+"…":title;
        int y=240;
        while(!remaining.isEmpty()&&y<475){
            int end=Math.min(remaining.length(),23);
            if(end<remaining.length()){
                int ws=remaining.lastIndexOf(' ',end);
                if(ws>=9)end=ws;
            }
            String piece=remaining.substring(0,end).trim();
            canvas.drawText(piece,18,y,p);
            remaining=remaining.substring(end).trim();
            y+=47;
        }
        p.setColor(0xff6d8fae);
        p.setTextSize(17);
        canvas.drawText("SCEGLI PER APRIRE",24,514,p);
        return b;
    }
}

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

/** Up to 12 previews per public category. Adult previews use neutral artwork and route through the PIN screen. */
public final class IptvPublisher {
    private static final String AUTHORITY="com.fuoconero.tvbridge.test043.images";
    private IptvPublisher(){}
    public static synchronized String publish(Context ctx)throws Exception{
        IptvMetadata.refresh(ctx);
        IptvArtwork.backfillEpgIds(ctx);
        try(IptvLibrary lib=new IptvLibrary(ctx)){
            for(IptvLibrary.Recent recent:lib.recentlyOpened(100))
                RecentStore.iptv(ctx,recent.media,recent.openedAt,recent.positionMs,recent.durationMs);
        }
        IptvStore store=new IptvStore(ctx);
        SharedPreferences prefs=ctx.getSharedPreferences("iptv_channels",Context.MODE_PRIVATE);
        String custom=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE)
            .getString("display_name","").trim();
        if(custom.isEmpty())custom="IPTV";
        if(custom.length()>42)custom=custom.substring(0,42);
        String userPlaylist=custom;
        IptvArtwork artwork=new IptvArtwork(ctx,store);
        int all=0,groups=0;
        for(String category:IptvStore.PUBLIC_GROUPS){
            if("Continua".equals(category))continue;
            boolean showCategory="Serie".equals(category)||"Ultime serie".equals(category);
            ArrayList<IptvStore.Media> items;
            ArrayList<IptvLibrary.Show> showItems=new ArrayList<>();
            if(showCategory || "Ultimi film".equals(category)||"Continua".equals(category)){
                try(IptvLibrary library=new IptvLibrary(ctx)){
                    if(showCategory){
                        showItems=library.shows("",0,12,"Ultime serie".equals(category));
                        items=new ArrayList<>();
                        for(IptvLibrary.Show show:showItems){
                            IptvStore.Media media=new IptvStore.Media();
                            media.id=show.newestId;media.title=show.title;
                            media.group=show.count+" episodi";
                            media.category="Serie";media.kind="Serie";
                            media.adult=false;
                            items.add(media);
                        }
                    }else if("Ultimi film".equals(category)){
                        items=library.newestFilms(0,12);
                    }else{
                        items=new ArrayList<>();
                        for(IptvLibrary.Recent recent:library.recentlyOpened(35)){
                            if(!"TV".equals(recent.media.kind) && recent.durationMs>0 && recent.positionMs<recent.durationMs*0.95)
                                items.add(recent.media);
                            if(items.size()>=12)break;
                        }
                    }
                }
            }else items=store.list(category,"",0,12);
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
                    "Fuoconero · "+userPlaylist+" · "+("Ultime serie".equals(category)?"Serie aggiornate":category));
                ctx.getContentResolver().update(TvContract.buildChannelUri(id),
                    rename,null,null);
            }
            if(id<0){
                ContentValues row=new ContentValues();
                row.put(TvContract.Channels.COLUMN_TYPE,TvContract.Channels.TYPE_PREVIEW);
                row.put(TvContract.Channels.COLUMN_DISPLAY_NAME,"Fuoconero · "+userPlaylist+" · "+category);
                row.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,"fuoconero-iptv-051-"+category);
                Intent home=new Intent(ctx,IptvActivity.class);
                home.putExtra("category",category);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
                if(media.adult&&!category.equals("Adulti TV"))continue;
                IptvLibrary.Show show=showCategory?showItems.get(order):null;
                String internal=showCategory?
                    "fuoconero:iptv:show:"+media.id:
                    "fuoconero:iptv:"+media.id;
                retained.add(internal);
                File poster=posterFile(ctx,media.id);
                boolean realArtwork=artwork.cover(ctx,media,poster);
                if(!realArtwork && (!poster.isFile()||media.adult)){
                    Bitmap image=renderPoster(media.adult?"AREA RISERVATA · PIN":category,media.adult?"18+ · Accesso con PIN":media.title);
                    try(FileOutputStream out=new FileOutputStream(poster)){
                        image.compress(Bitmap.CompressFormat.PNG,90,out);
                    }finally{image.recycle();}
                }
                long[] playback={0,0};
                if(!showCategory && !media.adult && !"TV".equals(media.kind)){
                    try(IptvLibrary library=new IptvLibrary(ctx)){playback=library.playback(media.id);}
                }
                boolean hasProgress=playback[1]>0 && playback[0]>=0 && playback[0]<=playback[1];
                if(hasProgress)poster=progressPoster(poster,playback[0],playback[1]);
                EpgStore.NowNext guide=new EpgStore.NowNext();
                if("TV".equals(media.kind) && !media.adult){
                    try(EpgStore epg=new EpgStore(ctx)){guide=epg.nowNext(media);}
                }
                String wide=WideArtwork.render(ctx,"iptv_"+media.id,media.title,
                    guide.now!=null?guide.describe():showCategory?show.count+" episodi":media.adult?"Area riservata · PIN":media.group,
                    RecentStore.image(ctx,poster.getName()),guide.now!=null?guide.percent():hasProgress?100.0*playback[0]/playback[1]:-1,
                    guide.now!=null?"PROGRAMMA IN ONDA":hasProgress?"RIPRENDI":"FUOCONERO IPTV");
                Uri photo=new Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath("posters").appendPath(poster.getName())
                    .appendQueryParameter("v",String.valueOf(poster.lastModified())).build();
                Intent open=(showCategory||media.adult)?
                    new Intent(ctx,IptvActivity.class):
                    new Intent(ctx,IptvPlayActivity.class);
                open.setAction(Intent.ACTION_VIEW);
                open.setData(showCategory?
                    new Uri.Builder().scheme("fuoconero-iptv").authority("series")
                        .appendPath(show.key).build():
                    Uri.parse("fuoconero-iptv://play/"+media.id));
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(showCategory || media.adult)open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if(showCategory){
                    open.putExtra("series_key",show.key);
                    open.putExtra("series_title",show.title);
                }else if(media.adult)open.putExtra("adult_media_id",media.id);
                else open.putExtra("id",media.id);
                ContentValues values=new ContentValues();
                values.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,id);
                values.put(TvContract.PreviewPrograms.COLUMN_TYPE,
                    showCategory?TvContract.PreviewPrograms.TYPE_TV_SERIES:
                    "TV".equals(media.kind)?TvContract.PreviewPrograms.TYPE_CHANNEL:
                    TvContract.PreviewPrograms.TYPE_MOVIE);
                values.put(TvContract.PreviewPrograms.COLUMN_TITLE,media.title);
                values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                    showCategory?show.count+" episodi · Apri per scegliere la puntata":
                    media.adult?"Richiede il PIN · Copertina neutra":media.group+" · Apri in Fuoconero IPTV");
                if(hasProgress){
                    values.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,playback[1]);
                    values.put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,playback[0]);
                    values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                        "Riprendi · "+clock(playback[0])+" / "+clock(playback[1])+" · Fuoconero IPTV");
                }else{
                    values.putNull(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS);
                    values.putNull(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                }
                if(guide.now!=null)values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,guide.describe());
                values.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,internal);
                values.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID,showCategory?"iptv:series:"+show.key:"iptv:"+media.id);
                values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT,1000-(order++));
                values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,wide);
                values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
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
        store.close();
        RecentStore.publish(ctx);
        return "Projectivy: "+all+" anteprime pubblicate in "+groups+
            " categorie; area adulti protetta da PIN con copertine neutre.";
    }

    private static String clock(long ms){
        long s=Math.max(0,ms/1000L);
        return s>=3600?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):
            String.format(java.util.Locale.ROOT,"%02d:%02d",s/60,s%60);
    }
    private static File progressPoster(File original,long position,long duration)throws Exception{
        Bitmap source=android.graphics.BitmapFactory.decodeFile(original.getAbsolutePath());
        if(source==null)return original;
        Bitmap b=source.copy(Bitmap.Config.ARGB_8888,true);source.recycle();
        if(b==null)return original;
        File result=new File(original.getParentFile(),original.getName().replace(".png","_progress.png"));
        try{
            int w=b.getWidth(),h=b.getHeight();Canvas canvas=new Canvas(b);Paint paint=new Paint(3);
            paint.setColor(0xee101828);canvas.drawRect(0,h*0.81f,w,h,paint);
            paint.setColor(Color.WHITE);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            paint.setTextSize(w*0.061f);
            canvas.drawText(String.format(java.util.Locale.ITALY,"%.1f%% visto",100.0*position/duration),w*0.06f,h*0.865f,paint);
            paint.setTextSize(w*0.052f);
            canvas.drawText(clock(position)+" / "+clock(duration),w*0.06f,h*0.925f,paint);
            paint.setColor(0xff626977);float left=w*0.06f,right=w*0.94f,top=h*0.95f,bottom=h*0.97f;
            canvas.drawRoundRect(left,top,right,bottom,6,6,paint);paint.setColor(0xffff843f);
            canvas.drawRoundRect(left,top,left+(right-left)*(float)position/duration,bottom,6,6,paint);
            File temp=new File(result.getParentFile(),result.getName()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){
                if(!b.compress(Bitmap.CompressFormat.PNG,90,out))throw new java.io.IOException("Poster encoding failed");
            }
            if(!temp.renameTo(result))throw new java.io.IOException("Poster save failed");
            return result;
        }finally{b.recycle();}
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


package com.fuoconero.tvbridge;

import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.util.*;

/** Separate Veezie/Trakt shelf; the unified recent history remains independent. */
public final class VeeziePublisher {
    private VeeziePublisher(){}
    public static synchronized void publish(Context c,List<RecentStore.Entry> cards,boolean complete)throws Exception{
        if(cards.isEmpty()&&!complete)return;
        SharedPreferences prefs=c.getSharedPreferences("tvbridge",Context.MODE_PRIVATE);
        long channel=prefs.getLong("veezie_channel_id",-1);
        if(channel>0)try(Cursor cur=c.getContentResolver().query(TvContract.buildChannelUri(channel),new String[]{"_id"},null,null,null)){
            if(cur==null||!cur.moveToFirst())channel=-1;
        }
        if(channel<0){
            ContentValues values=new ContentValues();
            values.put("type",TvContract.Channels.TYPE_PREVIEW);
            values.put("display_name","Fuoconero · Veezie/Trakt · 0.4.3");
            values.put("internal_provider_id","fuoconero-043-veezie");
            values.put("app_link_intent_uri",new Intent(c,BrowseActivity.class).toUri(Intent.URI_INTENT_SCHEME));
            Uri uri=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,values);
            if(uri==null)throw new java.io.IOException("Canale Veezie non creato");
            channel=ContentUris.parseId(uri);prefs.edit().putLong("veezie_channel_id",channel).apply();
            try{TvContract.requestChannelBrowsable(c,channel);}catch(Exception ignored){}
        }
        Map<String,Long> existing=new HashMap<>();
        try(Cursor cur=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(cur!=null)while(cur.moveToNext())if(cur.getLong(1)==channel)existing.put(cur.getString(2),cur.getLong(0));
        }
        Set<String> retained=new HashSet<>();int rank=1000;
        for(RecentStore.Entry item:cards){
            String key="fuoconero:"+item.key;if(!retained.add(key))continue;
            Intent intent=Intent.parseUri(item.intent,Intent.URI_INTENT_SCHEME);
            ContentValues values=new ContentValues();values.put("internal_provider_id",key);
            values.put("title",item.title);values.put("short_description",item.description);values.put("weight",rank--);
            values.put("type",item.key.startsWith("show_")?TvContract.PreviewPrograms.TYPE_TV_EPISODE:TvContract.PreviewPrograms.TYPE_MOVIE);
            values.put("intent_uri",item.intent);
            values.put("poster_art_uri",WideArtwork.render(c,"veezie_"+item.key,item.title,item.description,item.art,item.percent,item.source));
            values.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
            if(item.duration>0)values.put("duration_millis",item.duration);else values.putNull("duration_millis");
            values.putNull("last_playback_position_millis");
            String season=intent.getStringExtra("season"),episode=intent.getStringExtra("episode_number");
            if(season!=null&&season.matches("[0-9]{1,3}"))values.put("season_display_number",season);else values.putNull("season_display_number");
            if(episode!=null&&episode.matches("[0-9]{1,3}"))values.put("episode_display_number",episode);else values.putNull("episode_display_number");
            Long id=existing.get(key);
            if(id!=null)c.getContentResolver().update(TvContract.buildPreviewProgramUri(id),values,null,null);
            else{values.put("channel_id",channel);c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values);}
        }
        if(complete)for(Map.Entry<String,Long> old:existing.entrySet())if(!retained.contains(old.getKey()))
            c.getContentResolver().delete(TvContract.buildPreviewProgramUri(old.getValue()),null,null);
    }
}


package com.fuoconero.tvbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
public final class RefreshActionCard {
    public static final String KEY="action:refresh-all";
    public static synchronized void publish(Context c,long channel)throws Exception{
        Long existing=null;
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&KEY.equals(q.getString(2))){
                if(existing==null)existing=q.getLong(0);else c.getContentResolver().delete(TvContract.buildPreviewProgramUri(q.getLong(0)),null,null);
            }
        }
        Intent i=new Intent().setComponent(new ComponentName("com.fuoconero.tvbridge.test043","com.fuoconero.tvbridge.RefreshAllActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ContentValues values=new ContentValues();values.put("channel_id",channel);values.put("internal_provider_id",KEY);
        values.put("title","Aggiorna tutto");values.put("short_description","Veezie/Trakt · IPTV · Guida TV · Continua a guardare");values.put("weight",2000);
        values.put("type",TvContract.PreviewPrograms.TYPE_CHANNEL);values.put("intent_uri",i.toUri(Intent.URI_INTENT_SCHEME));
        values.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
        values.put("poster_art_uri",WideArtwork.render(c,"refresh_all","↻ Aggiorna tutto","Veezie/Trakt · IPTV · Guida TV","",-1,"UN CLIC PER AGGIORNARE LA HOME"));
        if(existing==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values);
        else{values.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(existing),values,null,null);}
    }
}


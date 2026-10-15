package com.fuoconero.tvbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
public final class SearchActionCard {
    public static final String KEY="action:iptv-search";
    public static synchronized void publish(Context c,long channel,String category)throws Exception{
        Long existing=null;
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&KEY.equals(q.getString(2))){
                if(existing==null)existing=q.getLong(0);else c.getContentResolver().delete(TvContract.buildPreviewProgramUri(q.getLong(0)),null,null);
            }
        }
        Intent i=new Intent(c,IptvActivity.class).putExtra("search_category",category).setData(android.net.Uri.parse("fuoconero-iptv://search/"+android.net.Uri.encode(category))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ContentValues values=new ContentValues();values.put("channel_id",channel);values.put("internal_provider_id",KEY);
        values.put("title","Cerca");values.put("short_description","Cerca in "+category);values.put("weight",3000);
        values.put("type",TvContract.PreviewPrograms.TYPE_CHANNEL);values.put("intent_uri",i.toUri(Intent.URI_INTENT_SCHEME));
        values.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
        values.put("poster_art_uri",WideArtwork.render(c,"search_"+category,"Cerca","Cerca in "+category,"",-1,"FILM · SERIE · CANALI IPTV"));
        if(existing==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values);
        else{values.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(existing),values,null,null);}
    }
}


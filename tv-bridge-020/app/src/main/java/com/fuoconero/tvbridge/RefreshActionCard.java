package com.fuoconero.tvbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
public final class RefreshActionCard {
    public static final String KEY="action:refresh-all";
    static String summary(Context c){
        SharedPreferences p=c.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
        long at=p.getLong("last_checked",p.getLong("last_success",0));
        String date=at==0?"Lista non ancora aggiornata":"Lista controllata "+new java.text.SimpleDateFormat("dd/MM HH:mm",java.util.Locale.ITALY).format(new java.util.Date(at));
        String error=p.getString("refresh_error","");
        if(!error.isEmpty())return date+"\n"+error;
        if(!p.contains("new_films"))return date+"\nAggiornamento automatico ogni ora";
        long films=p.getLong("new_films",0),episodes=p.getLong("new_episodes",0);
        return date+"\n"+(films+episodes==0?"Nessun nuovo film o episodio":films+" film e "+episodes+" episodi nuovi");
    }
    public static void refreshExisting(Context c){
        java.util.HashSet<Long> channels=new java.util.HashSet<>();
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"channel_id","internal_provider_id","package_name"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(KEY.equals(q.getString(1))&&c.getPackageName().equals(q.getString(2)))channels.add(q.getLong(0));
        }catch(Exception ignored){return;}
        for(long channel:channels)try{publish(c,channel);}catch(Exception ignored){}
    }
    public static synchronized void publish(Context c,long channel)throws Exception{
        Long existing=null;
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&KEY.equals(q.getString(2))){
                if(existing==null)existing=q.getLong(0);else c.getContentResolver().delete(TvContract.buildPreviewProgramUri(q.getLong(0)),null,null);
            }
        }
        Intent i=new Intent().setComponent(new ComponentName("com.fuoconero.tvbridge.test043","com.fuoconero.tvbridge.RefreshAllActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ContentValues values=new ContentValues();values.put("channel_id",channel);values.put("internal_provider_id",KEY);
        values.put("title","Aggiorna tutto");values.put("short_description",summary(c));values.put("weight",2000);
        values.put("type",TvContract.PreviewPrograms.TYPE_CHANNEL);values.put("intent_uri",i.toUri(Intent.URI_INTENT_SCHEME));
        values.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
        values.put("poster_art_uri",android.net.Uri.parse(WideArtwork.render(c,"refresh_all","↻ Aggiorna tutto",summary(c),"",-1,"AUTOMATICO OGNI ORA · AGGIORNA ORA")).buildUpon().appendQueryParameter("v",String.valueOf(System.currentTimeMillis())).build().toString());
        if(existing==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,values);
        else{values.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(existing),values,null,null);}
    }
}


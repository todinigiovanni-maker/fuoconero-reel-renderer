package com.fuoconero.tvbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import org.json.*;
import java.util.*;

/** Only Trakt-confirmed next episodes with an exact, unambiguous IPTV identity. */
public final class FollowedEpisodes {
    static final String PREF="followed_episodes";
    static long match(IptvLibrary lib,JSONObject item,long now)throws Exception{
        if(item.optLong("expires",0)<=now||!item.optString("tmdb").matches("[1-9][0-9]*"))return -1;
        int season=item.optInt("season",0),episode=item.optInt("episode",0);if(season<1||episode<1)return -1;
        String sql="SELECT MAX(i.media_id),COUNT(DISTINCT i.series_key) FROM series_index i JOIN source_series s ON s.series_key=i.series_key WHERE s.tmdb=? AND i.season=? AND i.episode=?";
        try(Cursor q=lib.getReadableDatabase().rawQuery(sql,new String[]{item.getString("tmdb"),String.valueOf(season),String.valueOf(episode)})){
            return q.moveToFirst()&&q.getInt(1)==1?q.getLong(0):-1;
        }
    }
    public static synchronized void publish(Context c)throws Exception{
        android.content.SharedPreferences p=c.getSharedPreferences(PREF,0);
        JSONArray candidates=new JSONArray(p.getString("items","[]"));long channel=p.getLong("channel",-1);
        if(channel>0)try(Cursor q=c.getContentResolver().query(TvContract.buildChannelUri(channel),new String[]{"_id"},null,null,null)){if(q==null||!q.moveToFirst())channel=-1;}
        if(channel<0){ContentValues v=new ContentValues();v.put("type",TvContract.Channels.TYPE_PREVIEW);v.put("display_name","Fuoconero · Nuovi episodi delle tue serie");v.put("internal_provider_id","fuoconero-followed-episodes");
            v.put("app_link_intent_uri",new Intent(c,IptvActivity.class).toUri(Intent.URI_INTENT_SCHEME));Uri uri=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,v);if(uri==null)return;channel=ContentUris.parseId(uri);p.edit().putLong("channel",channel).apply();try{TvContract.requestChannelBrowsable(c,channel);}catch(Exception ignored){}
        }
        Map<String,Long> existing=new HashMap<>();try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&q.getString(2)!=null&&q.getString(2).startsWith("followed:"))existing.put(q.getString(2),q.getLong(0));
        }
        int count=0;HashSet<String> seen=new HashSet<>();
        try(IptvLibrary lib=new IptvLibrary(c);IptvStore store=new IptvStore(c)){
            lib.ensureIndex();
            for(int n=0;n<candidates.length()&&count<12;n++){
                JSONObject item=candidates.getJSONObject(n);long id=match(lib,item,System.currentTimeMillis());if(id<0)continue;
                IptvStore.Media media=store.find(id);if(media==null||media.adult||!"Serie".equals(media.category))continue;
                String identity="show:tmdb:"+item.getString("tmdb")+":s"+item.getInt("season")+":e"+item.getInt("episode");if(!seen.add(identity))continue;
                String internal="followed:"+identity,title=item.optString("title",media.title),detail=String.format(Locale.ITALY,"S%02d E%02d · Prossima non vista su Trakt · Disponibile IPTV",item.getInt("season"),item.getInt("episode"));
                Intent open=new Intent(c,IptvPlayActivity.class).putExtra("id",id).setData(Uri.parse("fuoconero-iptv://play/"+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ContentValues v=new ContentValues();v.put("channel_id",channel);v.put("internal_provider_id",internal);v.put("content_id",identity);v.put("title",title);v.put("short_description",detail);v.put("type",TvContract.PreviewPrograms.TYPE_TV_EPISODE);v.put("weight",1000-count++);v.put("intent_uri",open.toUri(Intent.URI_INTENT_SCHEME));
                String art=item.optString("art","");v.put("poster_art_uri",WideArtwork.render(c,"followed_"+id,title,detail,art,-1,"PROSSIMA PUNTATA DISPONIBILE"));v.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
                HiddenHome.write(c,v,existing.remove(internal));
            }
        }
        for(long id:existing.values())c.getContentResolver().delete(TvContract.buildPreviewProgramUri(id),null,null);
        ContentValues status=new ContentValues();status.put("channel_id",channel);status.put("internal_provider_id","action:followed-status");Long statusId=HiddenHome.existing(c,status);
        if(count==0){
            status.put("title","Nessuna prossima puntata verificata");status.put("short_description","Servono progressi Trakt recenti e una corrispondenza esatta nel catalogo IPTV.");status.put("type",TvContract.PreviewPrograms.TYPE_CHANNEL);status.put("weight",1000);
            status.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);status.put("poster_art_uri",WideArtwork.render(c,"followed_status","Le tue prossime puntate","Nessuna corrispondenza verificata tra Trakt e IPTV","",-1,"AGGIORNA PER RIPROVARE"));status.put("intent_uri",new Intent(c,RefreshAllActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).toUri(Intent.URI_INTENT_SCHEME));HiddenHome.write(c,status,statusId);
        }else if(statusId!=null)c.getContentResolver().delete(TvContract.buildPreviewProgramUri(statusId),null,null);
        p.edit().putInt("matches",count).putLong("published",System.currentTimeMillis()).apply();RefreshActionCard.publish(c,channel);
    }
}

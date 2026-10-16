package com.fuoconero.netflixbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.util.*;
public final class Publisher {
    static final String PREF="netflix_test";
    static ContentValues card(Context c,long channel,String key,String title,String detail,String image,int weight) {
        ContentValues v=new ContentValues();v.put("channel_id",channel);v.put("internal_provider_id",key);
        v.put("title",title);v.put("short_description",detail);v.put("poster_art_uri",image);
        v.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
        v.put("type",TvContract.PreviewPrograms.TYPE_MOVIE);v.put("weight",weight);
        Intent open=new Intent(c,OpenActivity.class).putExtra("key",key).setData(Uri.parse("fuoconero-netflix-test://open/"+Uri.encode(key)));
        v.put("intent_uri",open.toUri(Intent.URI_INTENT_SCHEME));return v;
    }
    public static synchronized String refresh(Context c)throws Exception {
        NetflixSource.Result source=NetflixSource.read(c);
        SharedPreferences p=c.getSharedPreferences(PREF,0);long channel=p.getLong("channel",-1);
        if(channel>0)try(Cursor q=c.getContentResolver().query(TvContract.buildChannelUri(channel),new String[]{"_id"},null,null,null)){if(q==null||!q.moveToFirst())channel=-1;}
        if(channel<0) {
            ContentValues v=new ContentValues();v.put("type",TvContract.Channels.TYPE_PREVIEW);
            v.put("display_name","Fuoconero · Netflix TEST");v.put("internal_provider_id","fuoconero-netflix-independent");
            v.put("app_link_intent_uri",new Intent(c,MainActivity.class).toUri(Intent.URI_INTENT_SCHEME));
            Uri u=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,v);if(u==null)throw new IllegalStateException("Channel unavailable");
            channel=ContentUris.parseId(u);p.edit().putLong("channel",channel).apply();
        }
        Map<String,Long> existing=new HashMap<>();
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","package_name","internal_provider_id"},null,null,null)) {
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&c.getPackageName().equals(q.getString(2)))existing.put(q.getString(3),q.getLong(0));
        }
        ArrayList<ContentValues> prepared=new ArrayList<>();HashSet<String> files=new HashSet<>();files.add("app.png");
        prepared.add(card(c,channel,"app","Apri Netflix",source.items.isEmpty()?"Nessun titolo condiviso disponibile":"Apri l'app Netflix",
            Artwork.render(c,"app","Apri Netflix","App Netflix · accesso e abbonamento gestiti da Netflix","",0,0),2000));
        int direct=0,order=0;
        for(NetflixSource.Item i:source.items) {
            String key=i.owner+":"+i.id;boolean link=NetflixLinks.resolves(c,NetflixLinks.safe(i.intent,i.owner),i.owner);if(link)direct++;
            String detail=link?"Da Guarda successivo · apertura da verificare in Netflix":"Collegamento non disponibile · apre Netflix";
            files.add("item_"+i.id+".png");
            prepared.add(card(c,channel,key,i.title,detail,Artwork.render(c,"item_"+i.id,i.title,detail,i.art,i.position,i.duration),1000-order++));
        }
        // Prepare before changing provider rows. Only our independent channel is mutated.
        for(ContentValues v:prepared) {
            Long id=existing.remove(v.getAsString("internal_provider_id"));
            if(id==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,v);
            else c.getContentResolver().update(TvContract.buildPreviewProgramUri(id),v,null,null);
        }
        for(long id:existing.values())c.getContentResolver().delete(TvContract.buildPreviewProgramUri(id),null,null);
        // Revocation/empty source drops mirrored history and cached pictures rather than retaining it.
        java.io.File[] cache=new java.io.File(c.getFilesDir(),"art").listFiles();
        if(cache!=null)for(java.io.File f:cache)if(f.getName().matches("item_[0-9]+\\.png")&&!files.contains(f.getName()))f.delete();
        p.edit().putString("status",source.status).putInt("count",source.items.size()).putInt("resolvable_links",direct)
            .putBoolean("readable",source.readable).putLong("checked",System.currentTimeMillis()).apply();
        return source.status;
    }
}

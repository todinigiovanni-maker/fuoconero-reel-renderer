package com.fuoconero.netflixbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.util.*;
import java.text.SimpleDateFormat;

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
    static long channel(Context c,String preference,String key,String name) {
        SharedPreferences p=c.getSharedPreferences(PREF,0);long id=p.getLong(preference,-1);
        if(id>0)try(Cursor q=c.getContentResolver().query(TvContract.buildChannelUri(id),new String[]{"_id","package_name","internal_provider_id"},null,null,null)) {
            if(q==null||!q.moveToFirst()||!c.getPackageName().equals(q.getString(1))||!key.equals(q.getString(2)))id=-1;
        }
        ContentValues v=new ContentValues();v.put("type",TvContract.Channels.TYPE_PREVIEW);
        v.put("display_name",name);v.put("internal_provider_id",key);
        v.put("app_link_intent_uri",new Intent(c,MainActivity.class).toUri(Intent.URI_INTENT_SCHEME));
        if(id<0) {
            Uri u=c.getContentResolver().insert(TvContract.Channels.CONTENT_URI,v);
            if(u==null)throw new IllegalStateException("Channel unavailable");id=ContentUris.parseId(u);
            p.edit().putLong(preference,id).apply();
        }else c.getContentResolver().update(TvContract.buildChannelUri(id),v,null,null);
        return id;
    }
    static void publish(Context c,long channel,List<ContentValues> prepared) {
        Map<String,Long> existing=new HashMap<>();
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","package_name","internal_provider_id"},null,null,null)) {
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==channel&&c.getPackageName().equals(q.getString(2)))existing.put(q.getString(3),q.getLong(0));
        }
        for(ContentValues v:prepared) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Refresh cancelled");
            Long id=existing.remove(v.getAsString("internal_provider_id"));
            if(id==null){if(c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,v)==null)throw new IllegalStateException("Insert failed");}
            else c.getContentResolver().update(TvContract.buildPreviewProgramUri(id),v,null,null);
        }
        for(long id:existing.values())c.getContentResolver().delete(TvContract.buildPreviewProgramUri(id),null,null);
    }
    public static String refresh(Context c)throws Exception{return refresh(c,false);}
    public static synchronized String refresh(Context c,boolean forceCatalog)throws Exception {
        NetflixSource.Result source=NetflixSource.read(c);
        SharedPreferences p=c.getSharedPreferences(PREF,0);
        long channel=channel(c,"channel","fuoconero-netflix-independent","Netflix · Continua a guardare");
        ArrayList<ContentValues> prepared=new ArrayList<>();HashSet<String> files=new HashSet<>();files.add("app.png");files.add("news.png");
        prepared.add(card(c,channel,"app","Apri Netflix",source.items.isEmpty()?"Nessun titolo condiviso disponibile":"Apri l'app Netflix",
            Artwork.render(c,"app","Apri Netflix","App Netflix · accesso e abbonamento gestiti da Netflix","",0,0),2000));
        int direct=0,order=0;
        for(NetflixSource.Item i:source.items) {
            String key=i.owner+":"+i.id;boolean link=NetflixLinks.resolves(c,NetflixLinks.safe(i.intent,i.owner),i.owner);if(link)direct++;
            String detail=link?"Da Continua a guardare · collegamento Netflix":"Collegamento non disponibile · apre Netflix";
            files.add("item_"+i.id+".png");
            prepared.add(card(c,channel,key,i.title,detail,Artwork.render(c,"item_"+i.id,i.title,detail,i.art,i.position,i.duration),1000-order++));
        }
        publish(c,channel,prepared);
        CatalogSource.Result catalog=CatalogSource.read(c,forceCatalog);
        long news=channel(c,"news_channel","fuoconero-netflix-new-it","Novità Netflix Italia");
        ArrayList<ContentValues> titles=new ArrayList<>();order=0;
        SimpleDateFormat date=new SimpleDateFormat("dd/MM/yyyy",Locale.ITALY);date.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
        for(CatalogData.Item i:catalog.items) {
            String detail=(i.collection==CatalogData.SERIES?"Serie":"Film")+" · dal "+date.format(new Date(i.available))+" · Fonte Netflix"+(catalog.stale?" · dati salvati":"");
            String name="new_"+i.id;files.add(name+".png");
            ContentValues v=card(c,news,i.key(),i.title,detail,Artwork.render(c,name,i.title,detail,i.art,0,0),1000-order++);
            v.put("type",i.collection==CatalogData.SERIES?TvContract.PreviewPrograms.TYPE_TV_SERIES:TvContract.PreviewPrograms.TYPE_MOVIE);titles.add(v);
        }
        if(titles.isEmpty())titles.add(card(c,news,"app","Novità Netflix Italia",catalog.status,
            Artwork.render(c,"news","Novità Netflix Italia",catalog.status,"",0,0),1));
        publish(c,news,titles);
        java.io.File[] cache=new java.io.File(c.getFilesDir(),"art").listFiles();
        if(cache!=null)for(java.io.File f:cache)if(f.getName().matches("(item|new)_[0-9]+\\.png")&&!files.contains(f.getName()))f.delete();
        String status=source.status+"\n\n"+catalog.status;
        p.edit().putString("status",status).putInt("count",source.items.size()).putInt("resolvable_links",direct)
            .putInt("catalog_count",catalog.items.size()).putBoolean("catalog_stale",catalog.stale)
            .putBoolean("readable",source.readable).putLong("checked",System.currentTimeMillis()).apply();
        return status;
    }
}

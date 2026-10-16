package com.fuoconero.netflixbridge;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import java.util.*;

/** Read-only use of the shared Android TV contract, never Netflix's private provider. */
public final class NetflixSource {
    public static final String READ = "android.permission.READ_TV_LISTINGS";
    public static boolean isNetflix(String owner) {
        return "com.netflix.ninja".equals(owner) || "com.netflix.mediaclient".equals(owner);
    }
    public static final class Item {
        long id, engagement, position, duration;
        String owner, title, description, intent, art;
    }
    public static final class Result {
        public final ArrayList<Item> items = new ArrayList<>();
        public String status;
        public boolean readable;
    }
    static String str(Cursor c,String key) { int n=c.getColumnIndex(key); return n<0||c.isNull(n)?"":c.getString(n); }
    static long number(Cursor c,String key) { int n=c.getColumnIndex(key); return n<0||c.isNull(n)?0:c.getLong(n); }
    static void collect(Cursor c,Result out) {
        HashSet<Long> ids=new HashSet<>();
        while(c.moveToNext()) {
            String owner=str(c,"package_name");
            if(!isNetflix(owner) || number(c,"browsable")!=1)continue;
            String title=str(c,"title").trim(); long id=number(c,"_id");
            if(title.isEmpty()||!ids.add(id))continue;
            Item i=new Item();i.id=id;i.owner=owner;i.title=title;i.description=str(c,"short_description");
            i.intent=str(c,"intent_uri");i.art=str(c,"poster_art_uri");
            i.engagement=number(c,"last_engagement_time_utc_millis");
            i.position=number(c,"last_playback_position_millis");i.duration=number(c,"duration_millis");
            out.items.add(i);
        }
        out.items.sort((a,b)->Long.compare(b.engagement,a.engagement));
        if(out.items.size()>20)out.items.subList(20,out.items.size()).clear();
    }
    public static Result read(Context c) {
        Result r=new Result();
        if(c.checkSelfPermission(READ)!=PackageManager.PERMISSION_GRANTED) {
            r.status="Consenti la lettura dei programmi TV per cercare le voci condivise da Netflix.";return r;
        }
        // No SQL selection: the provider applies its own visibility and ownership constraints.
        try(Cursor q=c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
            new String[]{"_id","package_name","title","short_description","intent_uri","poster_art_uri","browsable",
                "last_engagement_time_utc_millis","last_playback_position_millis","duration_millis"},null,null,null)) {
            if(q==null){r.status="Il provider TV non ha restituito dati. Puoi comunque aprire Netflix.";return r;}
            collect(q,r);r.readable=true;
            r.status=r.items.isEmpty()?"Nessuna voce Netflix condivisa e leggibile in Guarda successivo. Puoi comunque aprire Netflix.":
                r.items.size()+" contenuti Netflix condivisi da Android TV. L'apertura del titolo dipende da Netflix.";
        }catch(SecurityException e){r.status="Android TV non consente questa lettura. Puoi comunque aprire Netflix.";}
        catch(RuntimeException e){r.status="Lettura di Guarda successivo non disponibile su questo dispositivo.";}
        return r;
    }
}

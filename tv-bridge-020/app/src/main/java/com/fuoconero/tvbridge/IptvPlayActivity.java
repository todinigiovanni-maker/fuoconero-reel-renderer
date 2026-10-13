package com.fuoconero.tvbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

/** Open only a user-provided media URL. No DRM bypass or embedded video scrapers. */
public class IptvPlayActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        long id=getIntent()==null?-1:getIntent().getLongExtra("id",-1);
        if(id<0&&getIntent()!=null&&getIntent().getData()!=null){
            try{id=Long.parseLong(getIntent().getData().getLastPathSegment());}
            catch(Exception ignored){}
        }
        IptvStore store=new IptvStore(this);
        IptvStore.Media media=store.find(id);
        if(media==null){Toast.makeText(this,"Canale non disponibile: aggiorna la M3U",Toast.LENGTH_LONG).show();finish();return;}
        if(media.adult){
            long active=getSharedPreferences("iptv_settings",MODE_PRIVATE).getLong("pin_unlocked_until",0);
            if(System.currentTimeMillis()>active){
                Toast.makeText(this,"Apri prima l'area riservata dal modulo IPTV e inserisci il PIN",Toast.LENGTH_LONG).show();
                Intent home=new Intent(this,IptvActivity.class);
                home.putExtra("category","Adulti");
                startActivity(home);
                finish();return;
            }
        }
        Intent generic=new Intent(Intent.ACTION_VIEW);
        generic.setDataAndType(Uri.parse(media.url),"video/*");
        generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{
            // IPTV Extreme is preferred if it explicitly accepts ACTION_VIEW
            // for the stream. Otherwise show the Android video-app chooser.
            List<ResolveInfo> players=getPackageManager().queryIntentActivities(generic,0);
            String iptvPackage=null;
            for(ResolveInfo r:players){
                if(r.activityInfo!=null && r.activityInfo.packageName!=null
                    && r.activityInfo.packageName.toLowerCase(Locale.ROOT).contains("iptvextreme")){
                    iptvPackage=r.activityInfo.packageName;
                    break;
                }
            }
            if(iptvPackage!=null){
                generic.setPackage(iptvPackage);
                startActivity(generic);
            }else{
                generic.setPackage(null);
                startActivity(Intent.createChooser(generic,"Apri con un lettore compatibile"));
            }
        }catch(Exception e){
            Toast.makeText(this,"Nessun lettore accetta il collegamento diretto. Apri il canale da IPTV Extreme.",Toast.LENGTH_LONG).show();
        }
        finish();
    }
}

package com.fuoconero.netflixbridge;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.media.tv.TvContract;
import android.widget.*;
public final class MainActivity extends Activity {
    TextView status;Button sync;
    public void onCreate(Bundle b) {
        super.onCreate(b);LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(64,32,64,32);
        ScrollView sc=new ScrollView(this);sc.addView(l);setContentView(sc);
        TextView title=new TextView(this);title.setText("Fuoconero · Netflix TEST");title.setTextSize(32);l.addView(title);
        status=new TextView(this);status.setTextSize(21);status.setPadding(0,20,0,20);l.addView(status);
        button(l,"Consenti lettura di Guarda successivo",()->requestPermissions(new String[]{NetflixSource.READ},10));
        sync=button(l,"Aggiorna riga Netflix",this::refresh);
        button(l,"Mostra riga nella home",()->{long id=getSharedPreferences(Publisher.PREF,0).getLong("channel",-1);if(id>0)try{TvContract.requestChannelBrowsable(this,id);}catch(RuntimeException e){status.setText("Abilita Fuoconero Netflix TEST in Projectivy > Modifica canali.");}});
        button(l,"Apri Netflix",()->startActivity(new Intent(this,OpenActivity.class).putExtra("key","app")));
        TextView info=new TextView(this);info.setTextSize(18);info.setPadding(0,20,0,0);
        info.setText("Legge solo le voci che Android TV rende disponibili. Nessun accesso all'account o alla cronologia privata Netflix.\n\nUna voce può aprire la scheda, la riproduzione o la home di Netflix: dipende dal collegamento pubblicato e dall'app. Apri Netflix rimane sempre disponibile.\n\nMetadati aggiuntivi: non attivati in questa prova. Nessuna associazione inventata tra ID TMDB e ID Netflix.\n\nApplicazione indipendente e non affiliata a Netflix. Le altre versioni Fuoconero restano separate.");l.addView(info);
        RefreshJob.schedule(this);refresh();
    }
    Button button(LinearLayout l,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());l.addView(b);return b;}
    void refresh(){sync.setEnabled(false);status.setText("Controllo dei contenuti condivisi…");new Thread(()->{String msg;try{msg=Publisher.refresh(this);}catch(Exception e){msg="Aggiornamento non riuscito. Riprova; le altre app non sono state modificate.";}String result=msg;runOnUiThread(()->{status.setText(result);sync.setEnabled(true);});},"NetflixRefresh").start();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==10)refresh();}
}

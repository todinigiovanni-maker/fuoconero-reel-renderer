package com.fuoconero.tvbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Veezie first: Veezie opens media, Trakt enriches and automatically refreshes
 * the catalogue whenever its one-time authorization is available.
 * No repeated device code flow and no PC import.
 */
public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private EditText clientId,clientSecret;
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private final ExecutorService authPool=Executors.newSingleThreadExecutor();
    private Future<?> authRequest;
    private static final long STARTUP_SYNC_MIN_AGE_MS=4L*60L*1000L;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences trakt=getSharedPreferences("trakt",MODE_PRIVATE);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40,26,40,26);
        root.setBackgroundColor(Color.rgb(17,20,32));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        label("FUOCONERO · IPTV EDITION",29,0xffff8c48,true);
        label("0.4.0 TEST · Modulo IPTV che affianca la 0.3.6 funzionante.",17,Color.WHITE,false);
        label("Apri subito IPTV qui sotto. La 0.3.6 continua a gestire Veezie e Trakt.",15,0xffcbd1df,false);
        button("▶ ENTRA IN FUOCONERO IPTV",()->{
            startActivity(new Intent(this,IptvActivity.class));
        });
        label("Client ID Trakt (si inserisce una sola volta):",15,Color.WHITE,false);
        clientId=input("Client ID Trakt",trakt.getString("client_id",""),false);
        label("Client Secret (opzionale):",14,0xffcbd1df,false);
        clientSecret=input("Opzionale",trakt.getString("client_secret",""),true);

        button("1 · Collega Trakt una sola volta",()->{
            saveTraktSettings();
            if(clientId.getText().toString().trim().isEmpty()){
                status("Prima inserisci il Client ID di Trakt; poi premi Collega.");
                return;
            }
            if(authRequest!=null && !authRequest.isDone()){
                status("Autorizzazione ancora in corso. Puoi annullarla con il pulsante 2.");
                return;
            }
            status("Contatto Trakt per il codice di attivazione. Se la rete non risponde comparira' un errore, non un blocco.");
            authRequest=authPool.submit(()->{
                try{
                    TraktApi api=new TraktApi(this);
                    TraktApi.DeviceCode device=api.startDevice();
                    if(Thread.currentThread().isInterrupted())return;
                    status("Apri "+device.url+" sul telefono e inserisci: "+device.userCode+
                        "\nAspetto l'autorizzazione; e' richiesta solo la prima volta.");
                    api.waitForApproval(device,this::status);
                    if(Thread.currentThread().isInterrupted())return;
                    status("Trakt collegato! Recupero automaticamente locandine e serie...");
                    syncNow();
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }catch(Exception e){
                    if(!Thread.currentThread().isInterrupted())
                        status("Trakt non raggiungibile: "+e.getMessage()+
                            "\nPuoi sempre aprire Veezie. Premi 1 per riprovare, senza resettare l'app.");
                }
            });
        });
        button("2 · Annulla richiesta Trakt",()->{
            if(authRequest!=null)authRequest.cancel(true);
            status("Richiesta Trakt interrotta. Veezie resta utilizzabile.");
        });
        button("3 · Aggiorna locandine ora",()->{
            if(!hasTrakt()){
                status("Per popolare automaticamente le locandine collega Trakt una sola volta con il pulsante 1.");
                return;
            }
            status("Aggiorno catalogo da Trakt...");
            syncNow();
        });
        button("★ IPTV · Catalogo M3U e preferiti",()->{
            startActivity(new Intent(this,IptvActivity.class));
        });
        button("4 · Apri Veezie",()->{
            if(!OpenContentActivity.openVeezie(this))
                status("Veezie non e' installato o non puo essere aperto.");
        });
        button("5 · Diagnostica canale Android TV",()->{
            pool.execute(()->{
                try{status(TvSync.diagnose(this));}
                catch(Exception e){status("Diagnostica: "+e.getMessage());}
            });
        });
        button("6 · Diagnostica Veezie e tempo di riproduzione",()->
            status(OpenContentActivity.diagnoseVeezie(this)));
        button("7 · Abilita navigazione Veezie (Accessibilita)",()->{
            try{
                startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                status("Abilita soltanto Fuoconero TV Bridge 0.4.0 TEST; disabilita i servizi delle vecchie versioni per evitare doppie azioni.");
            }catch(Exception e){status("Apri Impostazioni TV > Accessibilita. "+e.getMessage());}
        });
        button("8 · Test lettura tempi Veezie (2 minuti)",()->{
            if(!VeezieNavService.isEnabled(this)){
                status("Abilita prima il servizio di Accessibilita con il pulsante 7.");
                return;
            }
            VeezieNavService.startPlaybackProbe(this);
            status("Apri Veezie, riproduci una puntata e mostra i controlli. Poi torna e premi 6 per leggere i tempi.");
        });
        button("9 · Rendi visibile il canale in Projectivy",()->{
            pool.execute(()->{
                try{
                    TvSync.ensureStarter(this);
                    runOnUiThread(()->{
                        TvSync.requestVisibility(this);
                        status("Richiesta di visibilita' inviata. Se necessario abilita la riga Fuoconero · Veezie/Trakt 0.4.0 TEST in Projectivy.");
                    });
                }catch(Exception e){status("Canale: "+e.getMessage());}
            });
        });
        button("10 · Scollega Trakt (mantieni le locandine)",()->{
            if(authRequest!=null)authRequest.cancel(true);
            trakt.edit().remove("access_token").remove("refresh_token")
                .remove("expires_at").apply();
            status("Trakt scollegato in questa app. Le locandine gia' scaricate restano visibili.");
        });

        status=new TextView(this);
        status.setTextColor(0xfff1f0f8);
        status.setTextSize(17);
        status.setPadding(0,24,0,12);
        root.addView(status);
        status(hasTrakt()?
            "Trakt gia' collegato: controllo automaticamente il catalogo...":
            "IPTV pronta da configurare. Veezie/Trakt rimangono operativi nella versione 0.3.6.");

        pool.execute(()->{
            try{
                // Keep the stable 0.3.6 Veezie row untouched. 0.4.0 remains
                // an IPTV-only companion unless Trakt is linked in THIS app.
                if(hasTrakt()){
                    TvSync.ensureStarter(this);
                    runOnUiThread(()->TvSync.requestVisibility(this));
                    SyncJob.schedule(this);
                    long last=getSharedPreferences("tvbridge",MODE_PRIVATE)
                        .getLong("last_sync_started",0);
                    if(System.currentTimeMillis()-last>STARTUP_SYNC_MIN_AGE_MS){
                        status("Trakt gia' autorizzato. Aggiorno automaticamente le locandine...");
                        try{
                            TvSync.perform(this,this::status);
                        }catch(Exception e){
                            status("Catalogo temporaneamente non aggiornato: "+e.getMessage()+
                                "\nLe schede salvate rimangono.");
                        }
                    }else status("Catalogo gia' sincronizzato recentemente; aggiornamento automatico programmato.");
                }
            }catch(Exception e){status("Avvio canale Veezie: "+e.getMessage());}
        });
    }

    private boolean hasTrakt(){
        SharedPreferences trakt=getSharedPreferences("trakt",MODE_PRIVATE);
        return !trakt.getString("access_token","").isEmpty()
            && !trakt.getString("client_id","").isEmpty();
    }
    private void syncNow(){
        pool.execute(()->{
            try{
                TvSync.perform(this,this::status);
                SyncJob.schedule(this);
                runOnUiThread(()->TvSync.requestVisibility(this));
            }catch(Exception e){
                status("Sincronizzazione non riuscita: "+e.getMessage()+
                    "\nLe locandine gia' scaricate non sono state cancellate.");
            }
        });
    }
    private void saveTraktSettings(){
        getSharedPreferences("trakt",MODE_PRIVATE).edit()
            .putString("client_id",clientId.getText().toString().trim())
            .putString("client_secret",clientSecret.getText().toString().trim()).apply();
    }
    private EditText input(String hint,String value,boolean secret){
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xffa9b1c4);
        if(secret)field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(field,new LinearLayout.LayoutParams(-1,-2));
        return field;
    }
    private void label(String text,int size,int color,boolean bold){
        TextView label=new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if(bold)label.setTypeface(null,Typeface.BOLD);
        label.setPadding(0,7,0,7);
        root.addView(label);
    }
    private void button(String text,Runnable action){
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=8;
        root.addView(button,p);
        button.setOnClickListener(v->action.run());
    }
    private void status(String text){
        runOnUiThread(()->{if(status!=null)status.setText(text);});
    }
    @Override protected void onDestroy(){
        if(authRequest!=null)authRequest.cancel(true);
        authPool.shutdownNow();
        pool.shutdownNow();
        super.onDestroy();
    }
}

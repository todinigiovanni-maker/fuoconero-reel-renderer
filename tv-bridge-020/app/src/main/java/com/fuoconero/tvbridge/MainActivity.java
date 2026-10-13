package com.fuoconero.tvbridge;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.SharedPreferences;
import android.content.Context;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private EditText clientId, clientSecret;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences("trakt",MODE_PRIVATE);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48,28,48,28); root.setBackgroundColor(Color.rgb(17,20,32));
        ScrollView sv=new ScrollView(this); sv.setFillViewport(true); sv.addView(root);
        setContentView(sv);
        label("FUOCONERO · TV BRIDGE TEST",30,Color.rgb(255,128,58),true);
        label("Versione 0.2.9 TEST · Ricerca guidata Veezie con permesso accessibilita facoltativo.",17,Color.WHITE,false);
        label("Per cercare automaticamente il titolo in Veezie, abilita il servizio Fuoconero nelle impostazioni Accessibilita della TV. Puoi revocarlo quando vuoi.",15,0xffcccfde,false);
        label("Client ID Trakt (dalla propria app developer.trakt.tv/apps):",17,Color.WHITE,false);
        clientId=input("Client ID Trakt",p.getString("client_id",""),false);
        label("Client Secret (lascia vuoto se non richiesto da Trakt):",15,0xffcccfde,false);
        clientSecret=input("Opzionale – non condividere",p.getString("client_secret",""),true);
        button("1 · Collega account Trakt (codice sul telefono)", () -> {
            saveId();
            status("Richiesta di un codice Trakt...");
            pool.execute(() -> {
                try {
                    TraktApi a = new TraktApi(this);
                    TraktApi.DeviceCode code=a.startDevice();
                    status("Apri "+code.url+" sul telefono e inserisci il codice: "+code.userCode+"\nAttendo l'autorizzazione...");
                    a.waitForApproval(code, this::status);
                    status("Account Trakt collegato! Adesso aggiorno la riga...");
                    TvSync.perform(this, this::status);
                } catch(Exception e) {status("Autorizzazione fallita: "+e.getMessage());}
            });
        });
        button("2 · Sincronizza adesso la riga", () -> {
            saveId(); status("Sincronizzazione in corso...");
            pool.execute(()->{try{TvSync.perform(this,this::status);} catch(Exception e){status("Errore sincronizzazione: "+e.getMessage());}});
        });
        button("3 · Prova apertura Veezie", () -> {
            boolean ok=OpenContentActivity.openVeezie(this);
            if (!ok) status("Veezie non risulta installato.");
        });
        button("4 · Elimina SOLO la riga Fuoconero", () -> {
            pool.execute(()->{try{TvSync.removeChannel(this);status("Riga Fuoconero eliminata.");}
            catch(Exception e){status("Errore eliminazione: "+e.getMessage());}});
        });
        button("5 · Scollega Trakt su questa TV", () -> {
            getSharedPreferences("trakt",MODE_PRIVATE).edit()
                .remove("access_token").remove("refresh_token").remove("expires_at").apply();
            status("Account scollegato localmente; la riga rimane finché non la elimini.");
        });
        button("6 · Controlla quante schede sono pubblicate", () -> {
            pool.execute(() -> {
                try {status(TvSync.diagnose(this));}
                catch(Exception e){status("Errore controllo schede: "+e.getMessage());}
            });
        });
        button("7 · Diagnostica apertura Veezie", () -> {
            status(OpenContentActivity.diagnoseVeezie(this));
        });
                button("8 · Abilita navigazione Veezie (Accessibilita)", () -> {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                status("Nelle impostazioni Accessibilita, cerca Fuoconero TV Bridge 0.2.9 TEST e abilita Veezie Navigator. Questa funzione e' facoltativa e leggendo l'interfaccia di Veezie puo' cliccare sui suoi comandi.");
            }catch(Exception ex){status("Apri manualmente Impostazioni TV > Accessibilita. "+ex.getMessage());}
        });
        button("9 · Annulla navigazione automatica", () -> {
            VeezieNavService.cancel(this);
            status("Navigazione automatica annullata.");
        });
                status=new TextView(this);status.setTextSize(18);status.setTextColor(0xffeeecf5);
        status.setPadding(0,24,0,0); root.addView(status);
        status(p.getString("access_token","").isEmpty()?"Pronto: collega Trakt per mostrare le locandine.":"Trakt già autorizzato. Premi Sincronizza.");
        SyncJob.schedule(this);
    }
    private void label(String t,int size,int color,boolean bold){
        TextView tv=new TextView(this);tv.setText(t);tv.setTextColor(color);tv.setTextSize(size);
        if(bold)tv.setTypeface(null,Typeface.BOLD);tv.setPadding(0,8,0,8);root.addView(tv);
    }
    private EditText input(String hint,String value,boolean secret){
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setHint(hint);
        e.setTextColor(Color.WHITE);e.setHintTextColor(0xff9296ab);
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private void button(String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(17);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=9;
        root.addView(b,lp);b.setOnClickListener(v->action.run());
    }
    private void saveId(){
        getSharedPreferences("trakt",MODE_PRIVATE).edit()
            .putString("client_id",clientId.getText().toString().trim())
            .putString("client_secret",clientSecret.getText().toString().trim()).apply();
    }
    private void status(String s){runOnUiThread(()->status.setText(s));}
    @Override protected void onDestroy(){pool.shutdownNow();super.onDestroy();}
}

package com.fuoconero.tvbridge;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private LinearLayout root,traktPanel;
    private TextView status;
    private EditText clientId,clientSecret;
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private final ExecutorService authPool=Executors.newSingleThreadExecutor();
    private Future<?> pendingTrakt;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences p=getSharedPreferences("trakt",MODE_PRIVATE);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(42,28,42,28);
        root.setBackgroundColor(Color.rgb(17,20,32));
        ScrollView sv=new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        label(root,"FUOCONERO · VEEZIE EDITION",29,Color.rgb(255,128,58),true);
        label(root,"0.3.5 TEST · Importazione offline delle 30 locandine e titoli, senza codice Trakt.",17,Color.WHITE,false);
        label(root,"Veezie in Projectivy. Importa il catalogo ZIP dal PC via ADB: nessun accesso al database TV.",15,0xffcdd1dd,false);

        button(root,"1 · Crea e rendi visibile la riga Veezie",()->{
            status("Creo la riga Veezie indipendente...");
            pool.execute(()->{
                try{
                    TvSync.ensureStarter(this);
                    runOnUiThread(()->{
                        TvSync.requestVisibility(this);
                        status("Canale Veezie creato. Se non compare, apri Projectivy > Gestisci canali e abilita Fuoconero · Veezie 0.3.5 TEST.");
                    });
                }catch(Exception e){status("Creazione canale: "+e.getMessage());}
            });
        });
        button(root,"2 · Ricevi catalogo ZIP dal PC via ADB (porta 8787)",()->{
            status("In attesa per 3 minuti del catalogo ZIP da ADB sulla porta 8787. Non chiudere l'app.");
            pool.execute(()->{
                String answer=CatalogReceiver.receiveOnce(this);
                status(answer);
                if(answer.startsWith("IMPORTAZIONE RIUSCITA:"))
                    runOnUiThread(()->TvSync.requestVisibility(this));
            });
        });
        button(root,"3 · Prova a importare le vecchie schede",()->{
            status("Leggo le righe Fuoconero gia' presenti in Android TV...");
            pool.execute(()->{
                try{status(TvSync.importExisting(this));}
                catch(Exception e){status("Importazione: "+e.getMessage());}
            });
        });
        button(root,"2 · Apri Veezie",()->{
            if(!OpenContentActivity.openVeezie(this))
                status("Veezie non risulta installato.");
        });
        button(root,"3 · Diagnostica schede Android TV",()->{
            pool.execute(()->{
                try{status(TvSync.diagnose(this));}
                catch(Exception e){status("Diagnostica: "+e.getMessage());}
            });
        });
        button(root,"4 · Diagnostica navigazione e tempi Veezie",()->
            status(OpenContentActivity.diagnoseVeezie(this)));
        button(root,"5 · Abilita navigazione Veezie (Accessibilita)",()->{
            try{
                startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                status("Abilita SOLO il servizio Fuoconero TV Bridge 0.3.5 TEST. Se una vecchia versione e' ancora abilitata, disabilitala per evitare interferenze.");
            }catch(Exception e){status("Apri Impostazioni TV > Accessibilita. "+e.getMessage());}
        });
        button(root,"6 · Test tempi lettore Veezie (2 min)",()->{
            if(!VeezieNavService.isEnabled(this)){
                status("Abilita prima il servizio Accessibilita con il pulsante 5.");
                return;
            }
            VeezieNavService.startPlaybackProbe(this);
            status("Test di due minuti avviato: apri una puntata Veezie, mostra i controlli video e torna a leggere la diagnostica 4.");
        });
        button(root,"7 · Annulla navigazione automatica",()->{
            VeezieNavService.cancel(this);
            status("Navigazione automatica annullata.");
        });

        button(root,"8 · Trakt FACOLTATIVO: mostra/nascondi opzioni",()->{
            traktPanel.setVisibility(traktPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
        });
        traktPanel=new LinearLayout(this);
        traktPanel.setOrientation(LinearLayout.VERTICAL);
        traktPanel.setVisibility(View.GONE);
        root.addView(traktPanel);
        label(traktPanel,"Solo per aggiornare cronologia e dati delle serie. Per Veezie non serve Trakt.",16,Color.WHITE,false);
        clientId=input(traktPanel,"Client ID Trakt (opzionale)",
            p.getString("client_id",""),false);
        clientSecret=input(traktPanel,"Client Secret (opzionale)",
            p.getString("client_secret",""),true);
        button(traktPanel,"Collega Trakt (opzionale)",()->{
            saveId();
            if(clientId.getText().toString().trim().isEmpty()){
                status("Inserisci un Client ID solo se desideri usare Trakt.");
                return;
            }
            if(pendingTrakt!=null && !pendingTrakt.isDone()){
                status("Richiesta Trakt gia' in corso. Premi Annulla o attendi.");
                return;
            }
            status("Richiedo il codice Trakt (facoltativo)... Puoi annullare in qualunque momento.");
            pendingTrakt=authPool.submit(()->{
                try{
                    TraktApi a=new TraktApi(this);
                    TraktApi.DeviceCode code=a.startDevice();
                    status("Per Trakt apri "+code.url+" e inserisci "+code.userCode+
                        "\nPuoi annullare senza perdere le schede Veezie.");
                    a.waitForApproval(code,this::status);
                    if(Thread.currentThread().isInterrupted())return;
                    status("Trakt autorizzato. Premi Aggiorna da Trakt, solo se vuoi.");
                    SyncJob.schedule(this);
                }catch(InterruptedException ignored){
                    Thread.currentThread().interrupt();
                }catch(Exception e){status("Trakt non collegato: "+e.getMessage()+
                    "\nVeezie e le schede importate restano disponibili.");}
            });
        });
        button(traktPanel,"Annulla richiesta codice Trakt",()->{
            if(pendingTrakt!=null)pendingTrakt.cancel(true);
            status("Richiesta Trakt annullata. Continua con Veezie, senza codice.");
        });
        button(traktPanel,"Aggiorna informazioni da Trakt (opzionale)",()->{
            saveId();
            if(p.getString("access_token","").isEmpty()){
                status("Trakt non autorizzato. Le schede Veezie rimangono utilizzabili.");
                return;
            }
            status("Aggiorno da Trakt...");
            pool.execute(()->{
                try{TvSync.perform(this,this::status);}
                catch(Exception e){status("Trakt non disponibile: "+e.getMessage()+
                    "\nConservo le locandine presenti.");}
            });
        });
        button(traktPanel,"Scollega Trakt solo da questa app",()->{
            if(pendingTrakt!=null)pendingTrakt.cancel(true);
            p.edit().remove("access_token").remove("refresh_token")
                .remove("expires_at").apply();
            status("Trakt scollegato. Le schede importate rimangono.");
        });

        status=new TextView(this);
        status.setTextSize(17);
        status.setTextColor(0xffeeecf5);
        status.setPadding(0,22,0,12);
        root.addView(status);
        status("Modalita' Veezie attiva. Premi 2 per ricevere il catalogo offline via ADB. Trakt e' facoltativo.");
        // Nessuna sincronizzazione Trakt automatica in modalita Veezie.
        if(getSharedPreferences("tvbridge",MODE_PRIVATE).getLong("channel_id",-1L)<0L){
            pool.execute(()->{
                try{
                    TvSync.ensureStarter(this);
                    runOnUiThread(()->{
                        TvSync.requestVisibility(this);
                        status("Canale Veezie creato. Se non compare in Projectivy, abilitalo in Gestisci canali. Nessun Trakt richiesto.");
                    });
                }catch(Exception e){status("Avvio: "+e.getMessage()+" - premi 1 per riprovare.");}
            });
        }
    }
    private void saveId(){
        getSharedPreferences("trakt",MODE_PRIVATE).edit()
            .putString("client_id",clientId.getText().toString().trim())
            .putString("client_secret",clientSecret.getText().toString().trim())
            .apply();
    }
    private EditText input(LinearLayout target,String hint,String value,boolean secret){
        EditText e=new EditText(this);
        e.setSingleLine(true);e.setText(value);e.setHint(hint);
        e.setTextColor(Color.WHITE);e.setHintTextColor(0xff9296ab);
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_PASSWORD);
        target.addView(e,new LinearLayout.LayoutParams(-1,-2));
        return e;
    }
    private void label(LinearLayout target,String text,int size,int color,boolean bold){
        TextView tv=new TextView(this);
        tv.setText(text);tv.setTextColor(color);tv.setTextSize(size);
        if(bold)tv.setTypeface(null,Typeface.BOLD);
        tv.setPadding(0,8,0,8);
        target.addView(tv);
    }
    private void button(LinearLayout target,String name,Runnable action){
        Button b=new Button(this);
        b.setText(name);b.setAllCaps(false);b.setTextSize(16);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=8;
        target.addView(b,p);
        b.setOnClickListener(v->action.run());
    }
    private void status(String value){
        runOnUiThread(()->{if(status!=null)status.setText(value);});
    }
    @Override protected void onDestroy(){
        if(pendingTrakt!=null)pendingTrakt.cancel(true);
        pool.shutdownNow();authPool.shutdownNow();
        super.onDestroy();
    }
}

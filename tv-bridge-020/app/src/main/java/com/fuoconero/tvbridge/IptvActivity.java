package com.fuoconero.tvbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IptvActivity extends Activity {
    private static final int PICK_M3U=440;
    private static final long PIN_WINDOW=5L*60L*1000L;
    private LinearLayout root,contentArea;
    private TextView status;
    private EditText remoteUrl,searchInput;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String currentCategory="";private int page=0;
    private SharedPreferences prefs;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences(IptvStore.PREFS,MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,26,36,26);
        root.setBackgroundColor(0xff141d2d);
        scroll.addView(root);
        setContentView(scroll);

        label(root,"FUOCONERO · IPTV",30,0xffff984c,true);
        label(root,"M3U locale o URL privato · catalogo e preferiti",17,Color.WHITE,false);
        label(root,"La lista rimane sulla TV. Le categorie per adulti richiedono un PIN e non compaiono nelle anteprime Projectivy.",14,0xffc3cbde,false);
        status=new TextView(this);
        status.setTextColor(0xffffddaa);
        status.setTextSize(16);
        status.setPadding(0,12,0,15);
        root.addView(status);

        button(root,"1 · Scegli file M3U dalla memoria TV",()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{startActivityForResult(pick,PICK_M3U);}
            catch(Exception e){showStatus("Selezione file non disponibile: "+e.getMessage());}
        });
        label(root,"Oppure configura una sola volta l'URL della playlist per aggiornarla in automatico:",14,Color.WHITE,false);
        remoteUrl=new EditText(this);
        remoteUrl.setText(prefs.getString("source_url",""));
        remoteUrl.setHint("https://.../playlist.m3u (facoltativo)");
        remoteUrl.setTextSize(15);
        remoteUrl.setTextColor(Color.WHITE);
        remoteUrl.setHintTextColor(0xff97a6bd);
        remoteUrl.setSingleLine(true);
        remoteUrl.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(remoteUrl);
        button(root,"2 · Salva URL e aggiorna lista",()->{
            String url=remoteUrl.getText().toString().trim();
            if(!(url.startsWith("https://")||url.startsWith("http://"))){
                showStatus("Inserisci un URL M3U HTTP(S) completo. Non e' l'indirizzo di un singolo canale.");
                return;
            }
            prefs.edit().putString("source_url",url).remove("source_uri").apply();
            refresh();
        });
        button(root,"3 · Aggiorna ora",this::refresh);
        button(root,"4 · Pubblica le anteprime in Projectivy",()->{
            worker.execute(()->{
                try{showStatus(IptvPublisher.publish(this));}
                catch(Exception e){showStatus("Projectivy: "+e.getMessage());}
            });
        });
        label(root,"LE TUE CATEGORIE",20,Color.WHITE,true);
        for(String c:new String[]{"Preferiti","Novita","TV Italia","Sport","Documentari","Film","Serie","Altri canali","Tutti"}){
            button(root,"▶ "+("Novita".equals(c)?"Novità della playlist":c),()->showCategory(c,0,""));
        }
        button(root,"🔒 Area riservata adulti (PIN)",()->unlockAdult(()->showAdultHome()));
        contentArea=new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(0,12,0,0);
        root.addView(contentArea);
        IptvRefreshJob.schedule(this);
        long count=new IptvStore(this).count();
        showStatus(count==0?
            "Playlist non importata. Seleziona la M3U una volta o usa l'URL della playlist.":
            "Catalogo pronto: "+count+" contenuti. Aggiornamento automatico programmato se la fonte resta accessibile.");
        String category=getIntent().getStringExtra("category");
        if(category!=null && !category.isEmpty()){
            if(category.startsWith("Adulti"))unlockAdult(()->showAdultHome());
            else showCategory(category,0,"");
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_M3U || result!=RESULT_OK || data==null||data.getData()==null)return;
        Uri selected=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(Exception ignored){ /* still usable for initial import */ }
        prefs.edit().putString("source_uri",selected.toString()).remove("source_url").apply();
        refresh();
    }
    private void refresh(){
        showStatus("Importazione in corso. La lista e' molto grande: lascia aperta questa schermata.");
        worker.execute(()->{
            try{
                String result=IptvRefreshJob.update(this,this::showStatus);
                showStatus(result);
            }catch(Exception e){
                showStatus("Aggiornamento M3U non riuscito: "+e.getMessage()+
                    ". I dati gia' importati restano disponibili.");
            }
        });
    }
    private void showCategory(String category,int nextPage,String term){
        if(category.startsWith("Adulti")&&!isAdultUnlocked()){
            unlockAdult(()->showCategory(category,0,""));return;
        }
        currentCategory=category;page=nextPage;
        contentArea.removeAllViews();
        label(contentArea,category+" · pagina "+(page+1),21,Color.WHITE,true);
        if(searchInput==null || searchInput.getParent()!=contentArea){
            searchInput=new EditText(this);
            searchInput.setSingleLine(true);
            searchInput.setTextColor(Color.WHITE);
            searchInput.setHintTextColor(0xff98a5b9);
            searchInput.setHint("Cerca titolo o gruppo IPTV");
            contentArea.addView(searchInput);
        }
        searchInput.setText(term);
        button(contentArea,"🔎 Cerca in "+category,()->showCategory(category,0,searchInput.getText().toString()));
        button(contentArea,"← Categorie",()->{contentArea.removeAllViews();currentCategory="";});
        final int offset=page*50;
        worker.execute(()->{
            try{
                ArrayList<IptvStore.Media> rows=new IptvStore(this).list(category,term,offset,50);
                runOnUiThread(()->{
                    if(!category.equals(currentCategory)||nextPage!=page)return;
                    if(rows.isEmpty())label(contentArea,"Nessun contenuto trovato.",17,0xffc7d1df,false);
                    for(IptvStore.Media entry:rows){
                        LinearLayout line=new LinearLayout(this);
                        line.setOrientation(LinearLayout.HORIZONTAL);
                        Button open=new Button(this);
                        open.setText(entry.title+"\n"+entry.group);
                        open.setTextSize(13);
                        open.setAllCaps(false);
                        open.setMaxLines(3);
                        LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(0,-2,1f);
                        line.addView(open,wide);
                        open.setOnClickListener(v->{
                            Intent intent=new Intent(this,IptvPlayActivity.class);
                            intent.putExtra("id",entry.id);
                            startActivity(intent);
                        });
                        if(!entry.adult){
                            Button star=new Button(this);
                            star.setText(entry.fav?"★":"☆");
                            star.setTextSize(17);
                            line.addView(star,new LinearLayout.LayoutParams(75,-2));
                            star.setOnClickListener(v->{
                                new IptvStore(this).toggleFavorite(entry.id);
                                star.setText(entry.fav?"☆":"★");
                                entry.fav=!entry.fav;
                            });
                        }
                        contentArea.addView(line);
                    }
                    if(rows.size()==50)
                        button(contentArea,"Pagina successiva →",()->
                            showCategory(category,nextPage+1,searchInput.getText().toString()));
                    if(nextPage>0)
                        button(contentArea,"← Pagina precedente",()->
                            showCategory(category,nextPage-1,searchInput.getText().toString()));
                });
            }catch(Exception e){showStatus("Ricerca IPTV: "+e.getMessage());}
        });
    }
    private void showAdultHome(){
        contentArea.removeAllViews();
        currentCategory="";
        label(contentArea,"AREA RISERVATA (PIN)",21,0xffffba92,true);
        label(contentArea,"Non viene mostrata nella home Projectivy.",15,0xffc8d1df,false);
        button(contentArea,"Canali adulti TV",()->showCategory("Adulti TV",0,""));
        button(contentArea,"Video adulti on demand",()->showCategory("Adulti Video",0,""));
        button(contentArea,"Blocca ora",()->{
            prefs.edit().remove("pin_unlocked_until").apply();
            contentArea.removeAllViews();
            showStatus("Area privata bloccata.");
        });
    }
    private boolean isAdultUnlocked(){
        return prefs.contains("adult_pin_hash")&&
            System.currentTimeMillis()<prefs.getLong("pin_unlocked_until",0);
    }
    private void unlockAdult(Runnable success){
        if(isAdultUnlocked()){success.run();return;}
        boolean setup=!prefs.contains("adult_pin_hash");
        EditText pin=new EditText(this);
        pin.setSingleLine(true);
        pin.setHint("PIN numerico (4-8 cifre)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
            .setTitle(setup?"Crea PIN area adulti":"Sblocca area privata")
            .setMessage(setup?"Il PIN protegge canali e video adulti sul dispositivo.":"Inserisci il PIN.")
            .setView(pin)
            .setPositiveButton(setup?"Continua":"Sblocca",(d,w)->{
                String value=pin.getText().toString();
                if(!value.matches("[0-9]{4,8}")){
                    showStatus("Il PIN deve contenere da 4 a 8 cifre.");return;
                }
                if(setup)confirmPin(value,success);
                else if(verifyPin(value)){
                    prefs.edit().putLong("pin_unlocked_until",
                        System.currentTimeMillis()+PIN_WINDOW).apply();
                    success.run();
                }else showStatus("PIN non corretto.");
            })
            .setNegativeButton("Annulla",null).show();
    }
    private void confirmPin(String first,Runnable success){
        EditText confirm=new EditText(this);
        confirm.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Conferma PIN")
            .setView(confirm)
            .setPositiveButton("Salva",(dialog,which)->{
                if(!first.equals(confirm.getText().toString())){
                    showStatus("I due PIN non coincidono. Riprova.");return;
                }
                try{
                    byte[] random=new byte[16];
                    new SecureRandom().nextBytes(random);
                    StringBuilder hex=new StringBuilder();
                    for(byte b:random)hex.append(String.format(Locale.ROOT,"%02x",b&255));
                    String salt=hex.toString();
                    prefs.edit().putString("adult_pin_salt",salt)
                        .putString("adult_pin_hash",sha(salt+first))
                        .putLong("pin_unlocked_until",System.currentTimeMillis()+PIN_WINDOW)
                        .apply();
                    success.run();
                }catch(Exception e){showStatus("Impossibile salvare il PIN.");}
            }).setNegativeButton("Annulla",null).show();
    }
    private boolean verifyPin(String candidate){
        try{
            String salt=prefs.getString("adult_pin_salt","");
            String correct=prefs.getString("adult_pin_hash","");
            return MessageDigest.isEqual(correct.getBytes(StandardCharsets.UTF_8),
                sha(salt+candidate).getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){return false;}
    }
    private static String sha(String v)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private void label(LinearLayout container,String value,int size,int color,boolean bold){
        TextView view=new TextView(this);
        view.setText(value);view.setTextSize(size);view.setTextColor(color);
        if(bold)view.setTypeface(null,Typeface.BOLD);
        view.setPadding(0,8,0,8);
        container.addView(view);
    }
    private void button(LinearLayout container,String name,Runnable action){
        Button b=new Button(this);
        b.setText(name);b.setAllCaps(false);b.setTextSize(16);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=8;
        container.addView(b,p);
        b.setOnClickListener(v->action.run());
    }
    private void showStatus(String value){
        runOnUiThread(()->{
            if(status!=null)status.setText(value);
        });
    }
    @Override protected void onDestroy(){
        worker.shutdownNow();
        super.onDestroy();
    }
}

package com.fuoconero.tvbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.content.Context;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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
    private static final int READ_M3U_PERMISSION=441;
    private static final long PIN_WINDOW=5L*60L*1000L;
    private LinearLayout root,contentArea;
    private TextView status,playlistLabel,sourceLabel;
    private EditText searchInput;
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
        label(root,"0.4.2 TEST · Configurazione ottimizzata per telecomando Sony",15,0xffc3cbde,false);
        playlistLabel=new TextView(this);
        playlistLabel.setTextColor(Color.WHITE);
        playlistLabel.setTextSize(22);
        playlistLabel.setTypeface(null,Typeface.BOLD);
        playlistLabel.setPadding(0,12,0,5);
        root.addView(playlistLabel);
        sourceLabel=new TextView(this);
        sourceLabel.setTextColor(0xffc3cbde);
        sourceLabel.setTextSize(14);
        sourceLabel.setPadding(0,4,0,10);
        root.addView(sourceLabel);
        updatePlaylistLabels();

        status=new TextView(this);
        status.setTextColor(0xffffddaa);
        status.setTextSize(16);
        status.setPadding(0,12,0,15);
        root.addView(status);

        button(root,"1 · DAI UN NOME ALLA PLAYLIST",this::promptPlaylistName);
        button(root,"2 · IMPOSTA LINK M3U",this::promptPlaylistUrl);
        button(root,"3 · AGGIORNA LISTA ORA",this::refresh);
        button(root,"4 · IMPORTA FILE M3U DA DOWNLOAD",this::importFromDownloads);
        button(root,"5 · RECUPERA CATALOGO 0.4.1 (backup ADB)",()->{
            showStatus("Controllo il backup della vecchia versione. Non cancello nulla.");
            worker.execute(()->{
                try{
                    String restored=IptvMigration.restore(this);
                    showStatus(restored);
                    try{showStatus(restored+"\n"+IptvPublisher.publish(this));}
                    catch(Exception e){showStatus(restored+"\nProjectivy: "+e.getMessage());}
                }catch(Exception e){showStatus("Recupero non riuscito: "+e.getMessage()+
                    ". La 0.4.1 rimane intatta.")}
            });
        });
        button(root,"6 · MOSTRA LE CATEGORIE IN PROJECTIVY",()->{
            worker.execute(()->{
                try{showStatus(IptvPublisher.publish(this));}
                catch(Exception e){showStatus("Projectivy: "+e.getMessage());}
            });
        });
        label(root,"Il nome scelto e' solo un'etichetta: il file M3U conserva sempre il nome originale.",14,0xffc3cbde,false);
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
        prefs.edit().putString("source_uri",selected.toString())
            .putString("source_name",selected.getLastPathSegment())
            .remove("source_path").remove("source_url").apply();
        updatePlaylistLabels();
        refresh();
    }

    /** Display name is separate from the original M3U filename or URL. */
    private String displayName(){
        String custom=prefs.getString("display_name","").trim();
        if(!custom.isEmpty())return custom;
        String source=prefs.getString("source_name","");
        if(source.isEmpty())return "La mia playlist IPTV";
        return source.replaceFirst("(?i)\\.m3u8?$","").trim();
    }
    private void updatePlaylistLabels(){
        if(playlistLabel!=null)playlistLabel.setText("Playlist: "+displayName());
        if(sourceLabel!=null){
            String filename=prefs.getString("source_name","");
            String url=prefs.getString("source_url","");
            String source;
            if(!url.isEmpty())source="Sorgente: URL M3U salvato (indirizzo nascosto)";
            else if(!filename.isEmpty())source="File originale: "+filename;
            else source="Sorgente non configurata";
            sourceLabel.setText(source);
        }
    }
    /** A button opens the editor; the URL field is no longer in the DPAD scroll flow. */
    private void promptPlaylistName(){
        EditText field=dialogEdit(prefs.getString("display_name",displayName()),false);
        newTvDialog("Nome da mostrare in Fuoconero IPTV",field,()->{
            String name=field.getText().toString().trim();
            if(name.isEmpty()||name.length()>42||name.contains("\\n")){
                showStatus("Scegli un nome da 1 a 42 caratteri.");return;
            }
            prefs.edit().putString("display_name",name).apply();
            updatePlaylistLabels();
            showStatus("Playlist rinominata: "+name+
                ". Il file originale e il catalogo non vengono modificati.");
            worker.execute(()->{
                try{
                    if(new IptvStore(this).count()>0)IptvPublisher.publish(this);
                }catch(Exception e){
                    showStatus("Nome salvato, ma aggiornamento Projectivy non riuscito: "+
                        e.getMessage());
                }
            });
        });
    }
    private void promptPlaylistUrl(){
        EditText field=dialogEdit(prefs.getString("source_url",""),true);
        newTvDialog("Indirizzo della playlist M3U",field,()->{
            String url=field.getText().toString().trim();
            if(!(url.startsWith("https://")||url.startsWith("http://"))||
                url.contains(" ")||url.length()>2500){
                showStatus("Inserisci l'indirizzo completo HTTP(S) della playlist, "+
                    "non il link di un singolo film.");return;
            }
            prefs.edit().putString("source_url",url)
                .remove("source_path").remove("source_uri").apply();
            IptvRefreshJob.schedule(this);
            updatePlaylistLabels();
            showStatus("Link M3U salvato. Il catalogo attuale resta disponibile. "+
                "Premi 3 per aggiornare ora oppure attendi l'aggiornamento programmato.");
        });
    }
    private EditText dialogEdit(String initial,boolean uri){
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(18);
        field.setTextColor(Color.WHITE);
        field.setSelectAllOnFocus(true);
        field.setPadding(24,14,24,14);
        field.setInputType(uri?
            (InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI):
            (InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        field.setText(initial);
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        return field;
    }
    private void newTvDialog(String title,EditText field,Runnable save){
        // Native dialog buttons get DPAD focus and Enter works on Android TV.
        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(field)
            .setPositiveButton("Salva",(d,which)->save.run())
            .setNegativeButton("Annulla",null).create();
        dlg.getWindow(); // window is available after show
        dlg.setOnShowListener(d->{
            field.requestFocus();
            field.selectAll();
            if(dlg.getWindow()!=null)dlg.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            InputMethodManager keyboard=(InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);
        });
        dlg.show();
    }

    private void importFromDownloads(){
        // Some Android TV versions restrict public Downloads; when this
        // happens app-private storage is a supported ADB-copy fallback.
        if(Build.VERSION.SDK_INT>=23 && Build.VERSION.SDK_INT<=32 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                !=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                READ_M3U_PERMISSION);
            return;
        }
        scanLocalM3u();
    }

    @Override public void onRequestPermissionsResult(int requestCode,
        String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode!=READ_M3U_PERMISSION)return;
        if(grantResults.length==0 ||
            grantResults[0]!=PackageManager.PERMISSION_GRANTED){
            showStatus("Android non consente l'accesso a Download. "+
                "Puoi copiare la M3U nello spazio dell'app tramite ADB.");
            return;
        }
        scanLocalM3u();
    }

    private void scanLocalM3u(){
        ArrayList<File> matches=new ArrayList<>();
        File privateDir=getFilesDir();
        addM3uFiles(privateDir,matches);
        File externalPrivate=getExternalFilesDir(null);
        addM3uFiles(externalPrivate,matches);
        File publicDownloads=Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
        addM3uFiles(publicDownloads,matches);
        if(matches.isEmpty()){
            showStatus("Non trovo M3U leggibili nella cartella Download. "+
                "Se Android blocca l'accesso, puoi copiarla con ADB "+
                "nella cartella privata del Bridge.");
            return;
        }
        Collections.sort(matches,Comparator.comparingLong(File::lastModified).reversed());
        if(matches.size()==1){
            selectM3u(matches.get(0));return;
        }
        String[] names=new String[matches.size()];
        for(int i=0;i<matches.size();i++){
            File file=matches.get(i);
            names[i]=file.getName()+
                (file.getParentFile().equals(publicDownloads)?" · Download":" · IPTV");
        }
        new AlertDialog.Builder(this)
            .setTitle("Scegli la M3U, mantenendo il nome originale")
            .setItems(names,(d,which)->selectM3u(matches.get(which)))
            .setNegativeButton("Annulla",null).show();
    }
    private static void addM3uFiles(File directory,ArrayList<File> output){
        if(directory==null||!directory.isDirectory())return;
        File[] files=directory.listFiles();
        if(files==null)return;
        for(File file:files){
            String name=file.getName().toLowerCase(Locale.ROOT);
            if(file.isFile()&&file.canRead()&&file.length()>0&&
                (name.endsWith(".m3u")||name.endsWith(".m3u8")))
                output.add(file);
        }
    }
    private void selectM3u(File selected){
        prefs.edit().putString("source_path",selected.getAbsolutePath())
            .putString("source_name",selected.getName())
            .remove("source_uri").remove("source_url").apply();
        updatePlaylistLabels();
        showStatus("Avvio l'importazione di "+selected.getName()+
            " ("+Math.max(1,selected.length()/(1024*1024))+" MB).");
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

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
    private LinearLayout root,contentArea,menuArea;
    private ScrollView rootScroll;
    private EditText globalSearch;
    private int viewGeneration=0;
    private TextView status,playlistLabel,sourceLabel;
    private EditText searchInput;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean refreshQueued=false;
    private final java.util.concurrent.atomic.AtomicBoolean metadataRefreshQueued=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private String currentCategory="";private int page=0;
    private SharedPreferences prefs;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences(IptvStore.PREFS,MODE_PRIVATE);
        immersiveMode();
        ScrollView scroll=new ScrollView(this);
        rootScroll=scroll;
        scroll.setFillViewport(true);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,12,24,16);
        root.setBackgroundColor(0xff141d2d);
        scroll.addView(root);
        setContentView(scroll);

        label(root,"FUOCONERO · IPTV",30,0xffff984c,true);
        label(root,"0.4.3 HD · Schermo intero, canali TV unici e Sport senza doppioni",15,0xffc3cbde,false);
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

        globalSearch=new EditText(this);
        globalSearch.setSingleLine(true);
        globalSearch.setTextSize(18);
        globalSearch.setTextColor(Color.WHITE);
        globalSearch.setHintTextColor(0xffa6b4c8);
        globalSearch.setHint("Cerca serie, film o canali");
        globalSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        globalSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        root.addView(globalSearch);
        button(root,"🔎 Cerca nel catalogo",()->searchCatalog(globalSearch.getText().toString(),0));
        globalSearch.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
               (event!=null && event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER &&
                event.getAction()==android.view.KeyEvent.ACTION_DOWN)){
                searchCatalog(globalSearch.getText().toString(),0);return true;
            }
            return false;
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        menuArea=new LinearLayout(this);
        menuArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(menuArea);
        status=new TextView(this);
        status.setTextColor(0xffffddaa);
        status.setTextSize(16);
        status.setPadding(0,12,0,15);
        menuArea.addView(status);

        button(menuArea,"1 · DAI UN NOME ALLA PLAYLIST",this::promptPlaylistName);
        button(menuArea,"2 · IMPOSTA LINK M3U",this::promptPlaylistUrl);
        button(menuArea,"3 · AGGIORNA LISTA ORA",this::refresh);
        button(menuArea,"4 · IMPORTA PLAYLIST ITALIANA DA DOWNLOAD",this::importFromDownloads);
        button(menuArea,"5 · DIAGNOSTICA CATALOGO",()->{
            if(IptvRefreshJob.isUpdating()){
                showStatus("Importazione M3U ancora in corso. Attendi il messaggio di completamento prima della diagnostica.");
                return;
            }
            worker.execute(()->{
                try(IptvStore store=new IptvStore(this)){
                    long total=store.count();
                    StringBuilder diagnostic=new StringBuilder("Catalogo locale: "+total+" contenuti");
                    for(String cat:IptvStore.PUBLIC_GROUPS){
                        diagnostic.append("\\n").append(cat).append(": ")
                            .append(store.countCategory(cat));
                    }
                    diagnostic.append("\\nSe il catalogo e' vuoto, importa prima il file M3U dalla cartella Download.");
                    showStatus(diagnostic.toString());
                }catch(Exception e){showStatus("Diagnostica: "+e.getMessage()+
                    ". Se stai importando la lista, attendi che finisca e riprova.");}
            });
        });
        button(menuArea,"6 · MOSTRA LE CATEGORIE IN PROJECTIVY",()->{
            if(IptvRefreshJob.isUpdating()){
                showStatus("Importazione in corso. Le schede saranno pubblicate automaticamente alla fine.");
                return;
            }
            worker.execute(()->{
                try{
                    if(new IptvStore(this).count()==0){
                        showStatus("Nessuna scheda: importa prima il file M3U con il pulsante 4.");
                        return;
                    }
                    showStatus(IptvPublisher.publish(this));
                }catch(Exception e){showStatus("Projectivy: "+e.getMessage());}
            });
        });
        button(menuArea,"7 · AGGIORNA NOVITÀ IPTV",()->{
            if(!metadataRefreshQueued.compareAndSet(false,true)){
                showStatus("Aggiornamento novità già in corso.");return;
            }
            if(IptvRefreshJob.isUpdating()){
                metadataRefreshQueued.set(false);
                showStatus("Importazione in corso: attendi il completamento.");return;
            }
            showStatus("Leggo le novità dalla sorgente IPTV...");
            worker.execute(()->{
                try{
                    IptvMetadata.refreshNow(this);
                    showStatus(IptvPublisher.publish(this));
                }catch(Exception e){showStatus("Novità temporaneamente non disponibili.");}
                finally{metadataRefreshQueued.set(false);}
            });
        });
        label(menuArea,"Questa app non importa automaticamente i canali stranieri: usa la playlist italiana selezionata. La lista originale resta intatta.",14,0xffc3cbde,false);
        label(menuArea,"LE TUE CATEGORIE",20,Color.WHITE,true);
        for(String c:new String[]{"Continua","Ultimi film","Ultime serie","Preferiti","TV Italia","Sport","DAZN","Documentari","Film","Serie","Altri canali","Tutti"}){
            button(menuArea,"▶ "+("Ultime serie".equals(c)?"Serie aggiornate":"Novita".equals(c)?"Novità della playlist":c),()->showCategory(c,0,""));
        }
        button(menuArea,"🔒 Area riservata adulti (PIN)",()->unlockAdult(()->showAdultHome()));
        contentArea=new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(0,12,0,0);
        root.addView(contentArea);
        IptvRefreshJob.schedule(this);
        showStatus("Verifico il catalogo locale...");
        worker.execute(()->{
            if(IptvRefreshJob.isUpdating()){
                showStatus("Importazione in corso: attendi il completamento prima di usare la diagnostica.");
                return;
            }
            try(IptvStore store=new IptvStore(this)){
                long count=store.count();
                showStatus(count==0?
                    "Catalogo vuoto. Copia Fuoconero_IPTV_Italia_Selezione.m3u in Download, poi premi 4.":
                    "Catalogo pronto: "+count+" contenuti. Premi 5 per la diagnostica.");
            }catch(Exception e){showStatus("Catalogo temporaneamente occupato: attendi la fine dell'importazione e riprova.");}
        });
        routeIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        routeIntent(intent);
    }
    private void routeIntent(Intent intent){
        if(intent==null)return;
        String searchCategory=intent.getStringExtra("search_category");
        if(searchCategory!=null){
            if(!java.util.Arrays.asList(IptvStore.PUBLIC_GROUPS).contains(searchCategory))return;
            if(searchCategory.startsWith("Adulti"))unlockAdult(()->promptCategorySearch(searchCategory));
            else promptCategorySearch(searchCategory);
            return;
        }
        long adult=intent.getLongExtra("adult_media_id",-1L);
        if(adult>=0){unlockAdult(()->openMedia(adult));return;}
        String key=intent.getStringExtra("series_key");
        Uri data=intent.getData();
        if((key==null || key.isEmpty()) && data!=null &&
           "fuoconero-iptv".equals(data.getScheme()) && "series".equals(data.getHost()))
            key=data.getLastPathSegment();
        if(key!=null && !key.isEmpty()){
            String title=intent.getStringExtra("series_title");
            showSeriesEpisodes(key,title==null?key:title,0);return;
        }
        String query=intent.getStringExtra("search_query");
        if(query==null && Intent.ACTION_SEARCH.equals(intent.getAction()))
            query=intent.getStringExtra(android.app.SearchManager.QUERY);
        if(query!=null){globalSearch.setText(query);searchCatalog(query,0);return;}
        String category=intent.getStringExtra("category");
        if(category!=null && !category.isEmpty()){
            if(category.startsWith("Adulti"))unlockAdult(this::showAdultHome);
            else showCategory(category,0,"");
            return;
        }
        showMainMenu();
    }
    private void promptCategorySearch(String requested){
        final String category="Ultimi film".equals(requested)?"Film":requested;
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Scrivi il titolo o il canale");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Cerca in "+category).setView(input)
            .setPositiveButton("Cerca",(d,w)->showCategory(category,0,input.getText().toString().trim()))
            .setNegativeButton("Annulla",(d,w)->showCategory(category,0,"")).create();
        dialog.setOnShowListener(d->{input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});
        input.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){dialog.dismiss();showCategory(category,0,input.getText().toString().trim());return true;}return false;
        });
        dialog.show();
    }
    private int beginContent(String category,int at){
        viewGeneration++;
        currentCategory=category;page=at;
        menuArea.setVisibility(View.GONE);
        playlistLabel.setVisibility(View.GONE);
        sourceLabel.setVisibility(View.GONE);
        contentArea.removeAllViews();
        revealContent();
        return viewGeneration;
    }
    private void revealContent(){
        contentArea.post(()->rootScroll.smoothScrollTo(0,0));
    }
    private void showMainMenu(){
        viewGeneration++;currentCategory="";page=0;
        contentArea.removeAllViews();
        menuArea.setVisibility(View.VISIBLE);
        playlistLabel.setVisibility(View.VISIBLE);
        sourceLabel.setVisibility(View.VISIBLE);
        rootScroll.post(()->rootScroll.smoothScrollTo(0,0));
    }
    private void searchCatalog(String query,int at){
        final String term=query==null?"":query.trim();
        if(term.isEmpty()){android.widget.Toast.makeText(this,"Scrivi il titolo da cercare.",android.widget.Toast.LENGTH_SHORT).show();globalSearch.requestFocus();return;}
        if(term.length()>100){showStatus("Usa un titolo più breve.");return;}
        InputMethodManager keyboard=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(globalSearch.getWindowToken(),0);
        final int generation=beginContent("Ricerca:"+term,at);
        label(contentArea,"Risultati per «"+term+"»",22,Color.WHITE,true);
        button(contentArea,"← Categorie",this::showMainMenu);
        worker.execute(()->{
            try(IptvLibrary library=new IptvLibrary(this);IptvStore store=new IptvStore(this)){
                ArrayList<IptvLibrary.Show> shows=library.shows(term,at*20,20,false);
                ArrayList<IptvStore.Media> media=store.searchMedia(term,at*30,30);
                runOnUiThread(()->{
                    if(generation!=viewGeneration)return;
                    if(shows.isEmpty() && media.isEmpty())
                        label(contentArea,"Nessun risultato nel catalogo.",17,0xffc7d1df,false);
                    if(!shows.isEmpty())label(contentArea,"SERIE",18,0xffff984c,true);
                    for(IptvLibrary.Show show:shows)
                        button(contentArea,"📺 "+show.title+" · "+show.count+" episodi",()->
                            showSeriesEpisodes(show.key,show.title,0));
                    if(!media.isEmpty())label(contentArea,"FILM E CANALI",18,0xffff984c,true);
                    for(IptvStore.Media item:media)
                        button(contentArea,("TV".equals(item.kind)?"📡 ":"🎬 ")+item.title+
                            " · "+item.group,()->openMedia(item.id));
                    if(shows.size()==20 || media.size()==30)
                        button(contentArea,"Altri risultati →",()->searchCatalog(term,at+1));
                    if(at>0)button(contentArea,"← Risultati precedenti",()->searchCatalog(term,at-1));
                    revealContent();
                });
            }catch(Exception e){showStatus("Ricerca temporaneamente non disponibile.");}
        });
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
        if(refreshQueued || IptvRefreshJob.isUpdating()){
            showStatus("Importazione gia' in corso. Attendi il completamento.");
            return;
        }
        refreshQueued=true;
        IptvRefreshJob.schedule(this);
        showStatus("Importazione in corso. La lista e' molto grande: lascia aperta questa schermata.");
        worker.execute(()->{
            try{
                String result=IptvRefreshJob.update(this,this::showStatus);
                showStatus(result);
            }catch(Exception e){
                showStatus("Aggiornamento M3U non riuscito: "+e.getMessage()+
                    ". I dati gia' importati restano disponibili.");
            }finally{
                runOnUiThread(()->refreshQueued=false);
            }
        });
    }
    private void showCategory(String category,int nextPage,String term){
        if(category.startsWith("Adulti")&&!isAdultUnlocked()){
            unlockAdult(()->showCategory(category,0,""));return;
        }
        if(category.equals("Serie")||category.equals("Ultime serie")){
            showSeriesList(category,nextPage,term);return;
        }
        if(category.equals("Ultimi film")||category.equals("Continua")){
            showSpecialList(category,nextPage);return;
        }
        final int generation=beginContent(category,nextPage);
        if(category.equals("Sport")){
            button(contentArea,"⚽ DAZN · Vedi tutti i canali",()->showCategory("DAZN",0,""));
        }
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
        button(contentArea,"← Categorie",this::showMainMenu);
        final int offset=page*50;
        worker.execute(()->{
            try{
                ArrayList<IptvStore.Media> rows=new IptvStore(this).list(category,term,offset,50);
                runOnUiThread(()->{
                    if(generation!=viewGeneration)return;
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

    private void openMedia(long id){
        Intent intent=new Intent(this,IptvPlayActivity.class);
        intent.putExtra("id",id);
        startActivity(intent);
    }
    private void showSeriesList(String category,int at,String term){
        final int generation=beginContent(category,at);
        label(contentArea,category+" · una scheda per serie",21,Color.WHITE,true);
        EditText filter=new EditText(this);
        filter.setTextColor(Color.WHITE);
        filter.setHintTextColor(0xffa6b4c8);
        filter.setSingleLine(true);
        filter.setHint("Cerca una serie");
        filter.setText(term);
        contentArea.addView(filter);
        button(contentArea,"🔎 Cerca",()->showSeriesList(category,0,filter.getText().toString()));
        button(contentArea,"← Categorie",this::showMainMenu);
        worker.execute(()->{
            try(IptvLibrary library=new IptvLibrary(this)){
                ArrayList<IptvLibrary.Show> rows=library.shows(term,at*35,35,category.equals("Ultime serie"));
                runOnUiThread(()->{
                    if(generation!=viewGeneration)return;
                    if(rows.isEmpty())label(contentArea,"Nessuna serie trovata.",17,0xffc7d1df,false);
                    for(IptvLibrary.Show show:rows){
                        button(contentArea,"📺 "+show.title+"  ·  "+show.count+" episodi",()->
                            showSeriesEpisodes(show.key,show.title,0));
                    }
                    if(rows.size()==35)button(contentArea,"Pagina successiva →",()->
                        showSeriesList(category,at+1,filter.getText().toString()));
                    if(at>0)button(contentArea,"← Pagina precedente",()->
                        showSeriesList(category,at-1,filter.getText().toString()));
                });
            }catch(Exception e){showStatus("Serie: "+e.getMessage());}
        });
    }
    private void showSeriesEpisodes(String key,String title,int at){
        String marker="Serie:"+key;
        final int generation=beginContent(marker,at);
        label(contentArea,title+" · episodi",22,Color.WHITE,true);
        button(contentArea,"← Tutte le serie",()->showSeriesList("Serie",0,""));
        worker.execute(()->{
            try(IptvLibrary library=new IptvLibrary(this);
                IptvStore store=new IptvStore(this)){
                ArrayList<IptvLibrary.Episode> rows=library.episodes(key,at*45,45);
                ArrayList<String> labels=new ArrayList<>();
                for(IptvLibrary.Episode ep:rows){
                    IptvStore.Media item=store.find(ep.id);
                    if(item!=null)labels.add((ep.season>0?
                        String.format(Locale.ITALY,"S%02d E%02d · ",ep.season,ep.number):
                        "")+item.title);
                    else labels.add(ep.title);
                }
                runOnUiThread(()->{
                    if(generation!=viewGeneration)return;
                    if(rows.isEmpty())label(contentArea,"Nessuna puntata disponibile.",16,0xffc7d1df,false);
                    for(int i=0;i<rows.size();i++){
                        long id=rows.get(i).id;
                        String labelText=labels.get(i);
                        button(contentArea,"▶ "+labelText,()->openMedia(id));
                    }
                    if(rows.size()==45)button(contentArea,"Altri episodi →",()->
                        showSeriesEpisodes(key,title,at+1));
                    if(at>0)button(contentArea,"← Episodi precedenti",()->
                        showSeriesEpisodes(key,title,at-1));
                });
            }catch(Exception e){showStatus("Episodi: "+e.getMessage());}
        });
    }
    private void showSpecialList(String category,int at){
        final int generation=beginContent(category,at);
        label(contentArea,category,21,Color.WHITE,true);
        if(category.equals("Ultimi film"))
            label(contentArea,"Ordine per data di aggiunta del fornitore, quando disponibile.",14,0xffc7d1df,false);
        if(category.equals("Continua"))
            label(contentArea,"Avanzamento letto dal player Fuoconero IPTV. I lettori esterni non comunicano qui i tempi.",14,0xffc7d1df,false);
        button(contentArea,"← Categorie",this::showMainMenu);
        worker.execute(()->{
            try(IptvLibrary library=new IptvLibrary(this)){
                ArrayList<IptvStore.Media> films=category.equals("Ultimi film")?
                    library.newestFilms(at*35,35):new ArrayList<>();
                ArrayList<IptvLibrary.Recent> history=category.equals("Continua")?
                    library.recentlyOpened(35):new ArrayList<>();
                runOnUiThread(()->{
                    if(generation!=viewGeneration)return;
                    if(category.equals("Ultimi film")){
                        if(films.isEmpty())label(contentArea,"Nessuna nuova aggiunta.",16,0xffc7d1df,false);
                        for(IptvStore.Media m:films)button(contentArea,"🎬 "+m.title,()->openMedia(m.id));
                        if(films.size()==35)button(contentArea,"Altri film →",()->
                            showSpecialList(category,at+1));
                        if(at>0)button(contentArea,"← Film precedenti",()->
                            showSpecialList(category,at-1));
                    }else{
                        if(history.isEmpty())label(contentArea,"Ancora nessuna riproduzione registrata in Fuoconero IPTV.",16,0xffc7d1df,false);
                        for(IptvLibrary.Recent h:history){
                            String percent=h.durationMs>0?
                                " · riprendi da "+Math.round(h.positionMs*100.0/h.durationMs)+"%":
                                " · aperto di recente";
                            button(contentArea,"▶ "+h.media.title+percent,()->
                                openMedia(h.media.id));
                        }
                    }
                });
            }catch(Exception e){showStatus(category+": "+e.getMessage());}
        });
    }

    private void showAdultHome(){
        beginContent("Adulti",0);
        label(contentArea,"AREA RISERVATA (PIN)",21,0xffffba92,true);
        label(contentArea,"Sulla home Projectivy compaiono solo copertine neutre; il PIN protegge la riproduzione.",15,0xffc8d1df,false);
        button(contentArea,"Canali adulti TV",()->showCategory("Adulti TV",0,""));
        button(contentArea,"Video adulti on demand",()->showCategory("Adulti Video",0,""));
        button(contentArea,"Blocca ora",()->{
            prefs.edit().remove("pin_unlocked_until").apply();
            showMainMenu();
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
            if(menuArea!=null && menuArea.getVisibility()!=View.VISIBLE &&
               (value.startsWith("Ricerca temporaneamente") || value.startsWith("Episodi:") || value.startsWith("Serie:")))
                label(contentArea,value,16,0xffffddaa,false);
        });
    }
    private void immersiveMode(){
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus)immersiveMode();
    }
    @Override protected void onDestroy(){
        worker.shutdownNow();
        super.onDestroy();
    }
}


package com.fuoconero.tvbridge;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
import java.util.concurrent.*;

public class EpgActivity extends Activity{
    private static final int PICK=4301;
    private LinearLayout root,list;
    private TextView status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b){
        super.onCreate(b);ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);
        root.setOrientation(1);root.setPadding(24,20,24,20);root.setBackgroundColor(0xff101115);scroll.addView(root);setContentView(scroll);
        text(root,"GUIDA TV · XMLTV",26);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(18);root.addView(status);
        button(root,"Configura URL guida",()->{
            EditText field=new EditText(this);field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            field.setText(getSharedPreferences("epg",MODE_PRIVATE).getString("url",""));
            new AlertDialog.Builder(this).setTitle("URL XMLTV (.xml o .gz)").setView(field)
                .setPositiveButton("Salva e aggiorna",(d,w)->{
                    String url=field.getText().toString().trim();
                    if(!(url.startsWith("https://")||url.startsWith("http://"))){status.setText("Usa un indirizzo HTTP(S)");return;}
                    getSharedPreferences("epg",MODE_PRIVATE).edit().putString("url",url).remove("uri").apply();
                    EpgRefreshJob.reschedule(this);refresh();
                }).setNegativeButton("Annulla",null).show();
        });
        button(root,"Importa file XMLTV",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{startActivityForResult(i,PICK);}catch(Exception e){status.setText("Selettore file non disponibile; usa l'URL XMLTV");}
        });
        button(root,"Aggiorna guida ora",this::refresh);
        button(root,"Mostra programmi salvati",this::showGuide);
        button(root,"← Home Fuoconero",this::finish);
        list=new LinearLayout(this);list.setOrientation(1);root.addView(list);
        EpgRefreshJob.configureFromPlaylist(this);showGuide();EpgRefreshJob.schedule(this);
        if(!getSharedPreferences("epg",MODE_PRIVATE).getString("url","").isEmpty() &&
           System.currentTimeMillis()-getSharedPreferences("epg",MODE_PRIVATE).getLong("last_success",0)>6L*3600L*1000L)refresh();
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==PICK&&res==RESULT_OK&&data!=null&&data.getData()!=null){
            try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            getSharedPreferences("epg",MODE_PRIVATE).edit().putString("uri",data.getData().toString()).remove("url").apply();
            EpgRefreshJob.reschedule(this);refresh();
        }
    }
    private void refresh(){
        status.setText("Importazione guida in corso...");
        worker.execute(()->{
            String message;try{message=EpgRefreshJob.refresh(this);}catch(Exception e){message="Guida non aggiornata: dati precedenti conservati";}
            final String m=message;runOnUiThread(()->{status.setText(m);showGuide();});
        });
    }
    private void showGuide(){
        worker.execute(()->{
            java.util.ArrayList<String> labels=new java.util.ArrayList<>();
            java.util.ArrayList<Long> ids=new java.util.ArrayList<>();
            try(IptvStore store=new IptvStore(this);EpgStore epg=new EpgStore(this)){
                for(IptvStore.Media media:store.list("TV Italia","",0,50)){
                    labels.add(media.title+"\n"+epg.nowNext(media).describe());ids.add(media.id);
                }
            }catch(Exception ignored){}
            runOnUiThread(()->{list.removeAllViews();if(labels.isEmpty())text(list,"Importa prima la playlist IPTV",18);
                for(int n=0;n<labels.size();n++){long id=ids.get(n);button(list,labels.get(n),()->startActivity(new Intent(this,IptvPlayActivity.class).putExtra("id",id)));}});
        });
    }
    private void text(LinearLayout r,String t,int size){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(Color.WHITE);r.addView(v);}
    private void button(LinearLayout r,String t,Runnable action){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(18);r.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}


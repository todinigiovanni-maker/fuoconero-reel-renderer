package com.fuoconero.tvbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Full-screen playback page: the Projectivy card opens THIS activity.
 * Play user-provided ordinary media formats locally or launch an installed player.
 * No DRM circumvention, unofficial streaming URL discovery or embedded web scrapers.
 */
public class IptvPlayActivity extends Activity {
    private IptvStore.Media media;
    private TextView status;
    private VideoView video;
    private boolean active=false;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){
        @Override public void run(){
            savePlayback();
            handler.postDelayed(this,10000L);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        long id=getIntent()==null?-1:getIntent().getLongExtra("id",-1);
        if(id<0 && getIntent()!=null && getIntent().getData()!=null) {
            try{id=Long.parseLong(getIntent().getData().getLastPathSegment());}
            catch(Exception ignored){}
        }
        media=new IptvStore(this).find(id);
        if(media==null){showUnavailable("Scheda non trovata nel catalogo. Importa la playlist prima di aprirla.");return;}
        if(media.adult) {
            long until=getSharedPreferences("iptv_settings",MODE_PRIVATE)
                .getLong("pin_unlocked_until",0);
            if(System.currentTimeMillis()>until){
                showUnavailable("Contenuto riservato: apri prima l'area protetta da PIN.");
                return;
            }
        }
        try(IptvLibrary library=new IptvLibrary(this)){
            library.markOpened(media.id);
        }catch(Exception e){android.util.Log.w("FuoconeroIPTV","Recent open unavailable",e);}
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(22,18,22,18);
        screen.setBackgroundColor(0xff101928);
        setContentView(screen);
        TextView label=new TextView(this);
        label.setText(media.title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(22);
        label.setMaxLines(2);
        screen.addView(label);

        status=new TextView(this);
        status.setTextColor(0xffe1e7ef);
        status.setTextSize(15);
        status.setPadding(2,6,0,8);
        screen.addView(status);
        video=new VideoView(this);
        video.setKeepScreenOn(true);
        LinearLayout.LayoutParams videoParams=
            new LinearLayout.LayoutParams(-1,0,1f);
        screen.addView(video,videoParams);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        screen.addView(actions);
        addButton(actions,"▶ Riproduci",()->play());
        addButton(actions,"Apri con un altro lettore",this::openExternal);
        addButton(actions,"← Indietro",this::finish);
        video.setOnPreparedListener(mp->{
            mp.setOnInfoListener((player,what,extra)->false);
            long[] previous={0,0};
            try(IptvLibrary library=new IptvLibrary(this)){
                previous=library.playback(media.id);
            }catch(Exception ignored){}
            int duration=video.getDuration();
            if(previous[0]>3000 && duration>0 &&
                previous[0]<(long)duration*95/100 &&
                !("TV".equals(media.kind))){
                video.seekTo((int)Math.min(Integer.MAX_VALUE,previous[0]));
                status.setText("Riprendo da "+Math.round(previous[0]/60000.0)+" minuti");
            }else status.setText("Riproduzione avviata · "+media.group);
            video.start();
        });
        video.setOnCompletionListener(mp->{
            savePlayback();
            status.setText("Riproduzione completata");
        });
        handler.postDelayed(heartbeat,10000L);
        video.setOnErrorListener((mp,what,extra)->{
            active=false;
            status.setText("Il lettore integrato non supporta questo flusso, oppure la fonte "+
                "non risponde. Prova 'Apri con un altro lettore'.");
            return true;
        });
        VideoView v=video;
        MediaController controller=new MediaController(this);
        controller.setAnchorView(v);
        v.setMediaController(controller);
        play();
    }
    private void addButton(LinearLayout row,String text,Runnable action){
        Button button=new Button(this);
        button.setText(text);button.setAllCaps(false);button.setTextSize(14);
        row.addView(button,new LinearLayout.LayoutParams(0,-2,1f));
        button.setOnClickListener(v->action.run());
    }
    private void showUnavailable(String message){
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(35,45,35,25);
        screen.setBackgroundColor(0xff101928);
        TextView explanation=new TextView(this);
        explanation.setText(message);explanation.setTextColor(Color.WHITE);
        explanation.setTextSize(22);
        screen.addView(explanation);
        Button back=new Button(this);back.setText("Indietro");
        screen.addView(back);
        back.setOnClickListener(v->finish());
        setContentView(screen);
    }
    private void play(){
        if(media==null || video==null)return;
        if(active){video.start();return;}
        if(!(media.url.startsWith("http://")||media.url.startsWith("https://"))) {
            status.setText("Indirizzo di riproduzione non valido.");return;
        }
        try{
            status.setText("Collegamento alla sorgente...");
            video.setVideoURI(Uri.parse(media.url));
            video.requestFocus();
            active=true;
            video.start();
        }catch(Exception e){
            active=false;
            status.setText("Riproduzione non disponibile: prova un lettore esterno.");
        }
    }
    private void openExternal(){
        if(media==null)return;
        try{
            Intent view=new Intent(Intent.ACTION_VIEW);
            Uri stream=Uri.parse(media.url);
            // MIME hint, not a replacement for the source metadata.
            view.setDataAndType(stream,"video/*");
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(view,"Scegli un lettore per "+media.title));
        }catch(Exception e){
            if(status!=null)status.setText("Nessun lettore esterno compatibile installato.");
        }
    }
    private void savePlayback(){
        if(video==null || media==null)return;
        try{
            int duration=video.getDuration();
            int position=video.getCurrentPosition();
            if(duration>0 && duration<12*3600*1000 && position>=0 &&
                !"TV".equals(media.kind)){
                try(IptvLibrary library=new IptvLibrary(this)){
                    library.saveProgress(media.id,position,duration);
                }
            }
        }catch(Exception e){android.util.Log.w("FuoconeroIPTV","Unable to save position",e);}
    }
    @Override protected void onPause(){
        savePlayback();
        if(video!=null && video.isPlaying())video.pause();
        handler.removeCallbacks(heartbeat);
        new Thread(()->{
            try{IptvPublisher.publish(getApplicationContext());}
            catch(Exception e){android.util.Log.w("FuoconeroIPTV","Projectivy watchlist refresh skipped",e);}
        },"FuoconeroWatchlistUpdate").start();
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();
        if(video!=null)handler.postDelayed(heartbeat,10000L);
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(heartbeat);
        if(video!=null)video.stopPlayback();
        super.onDestroy();
    }
}

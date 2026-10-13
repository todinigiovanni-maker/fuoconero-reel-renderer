package com.fuoconero.tvbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Immersive fullscreen playback with a remote-controlled transient control bar.
 * Only opens streams present in a playlist imported by the user.
 */
public class IptvPlayActivity extends Activity {
    private IptvStore.Media media;
    private TextView status;
    private VideoView video;
    private LinearLayout controls;
    private Button replay;
    private boolean active=false;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable hideControls=new Runnable(){
        @Override public void run(){
            if(controls!=null&&video!=null&&video.isPlaying())
                controls.setVisibility(View.GONE);
        }
    };
    private final Runnable heartbeat=new Runnable(){
        @Override public void run(){
            savePlayback();
            handler.postDelayed(this,10000L);
        }
    };
    private void immersiveMode(){
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        immersiveMode();
        long id=getIntent()==null?-1:getIntent().getLongExtra("id",-1);
        if(id<0&&getIntent()!=null&&getIntent().getData()!=null){
            try{id=Long.parseLong(getIntent().getData().getLastPathSegment());}
            catch(Exception ignored){}
        }
        try(IptvStore store=new IptvStore(this)){media=store.find(id);}
        if(media==null){
            showUnavailable("Contenuto non trovato nel catalogo. Controlla la playlist.");
            return;
        }
        if(media.adult){
            long until=getSharedPreferences("iptv_settings",MODE_PRIVATE)
                .getLong("pin_unlocked_until",0);
            if(System.currentTimeMillis()>until){
                showUnavailable("Contenuto riservato: inserisci prima il PIN nell'area privata.");
                return;
            }
        }
        try(IptvLibrary library=new IptvLibrary(this)){library.markOpened(media.id);}
        catch(Exception e){android.util.Log.w("FuoconeroIPTV","History not available",e);}

        FrameLayout full=new FrameLayout(this);
        full.setBackgroundColor(Color.BLACK);
        setContentView(full);
        video=new VideoView(this);
        video.setKeepScreenOn(true);
        full.addView(video,new FrameLayout.LayoutParams(-1,-1));

        controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(24,16,24,20);
        controls.setBackgroundColor(0xd0101727);
        FrameLayout.LayoutParams dock=new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM);
        full.addView(controls,dock);
        TextView name=new TextView(this);
        name.setText(media.title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(20);
        name.setMaxLines(2);
        controls.addView(name);
        status=new TextView(this);
        status.setTextColor(0xffd5e2f5);
        status.setTextSize(15);
        status.setPadding(0,6,0,9);
        controls.addView(status);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(buttons);
        replay=addButton(buttons,"▶ Riproduci",this::play);
        addButton(buttons,"Apri con altro lettore",this::openExternal);
        addButton(buttons,"← Indietro",this::finish);
        replay.requestFocus();
        full.setOnClickListener(v->showControls());

        video.setOnPreparedListener(mp->{
            long[] prior={0,0};
            try(IptvLibrary library=new IptvLibrary(this)){
                prior=library.playback(media.id);
            }catch(Exception ignored){}
            int length=video.getDuration();
            if(prior[0]>3000&&length>0&&prior[0]<(long)length*95/100&&
                !"TV".equals(media.kind)){
                video.seekTo((int)Math.min(Integer.MAX_VALUE,prior[0]));
                status.setText("Riprendo da "+Math.round(prior[0]/60000.0)+" minuti");
            }else status.setText("In riproduzione · Premi OK per i comandi");
            video.start();
            scheduleHide();
        });
        video.setOnCompletionListener(mp->{
            savePlayback();status.setText("Riproduzione terminata");
            showControls();
        });
        video.setOnErrorListener((mp,what,extra)->{
            active=false;
            status.setText("Flusso non disponibile o formato non supportato. "+
                "Prova 'Apri con altro lettore'.");
            showControls();
            return true;
        });
        MediaController controller=new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        handler.postDelayed(heartbeat,10000L);
        play();
    }
    private Button addButton(LinearLayout row,String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);
        b.setTextSize(15);
        row.addView(b,new LinearLayout.LayoutParams(0,-2,1f));
        b.setOnClickListener(v->{handler.removeCallbacks(hideControls);action.run();});
        return b;
    }
    private void showControls(){
        if(controls!=null){
            controls.setVisibility(View.VISIBLE);
            if(replay!=null)replay.requestFocus();
            scheduleHide();
        }
    }
    private void scheduleHide(){
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls,9000L);
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        // Let the viewer recall the bottom controls with one remote click.
        if(controls!=null&&controls.getVisibility()!=View.VISIBLE&&
            (keyCode==KeyEvent.KEYCODE_DPAD_CENTER ||
             keyCode==KeyEvent.KEYCODE_ENTER ||
             keyCode==KeyEvent.KEYCODE_DPAD_DOWN ||
             keyCode==KeyEvent.KEYCODE_DPAD_UP)){
            showControls();return true;
        }
        if(keyCode==KeyEvent.KEYCODE_MENU){showControls();return true;}
        return super.onKeyDown(keyCode,event);
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(focused)immersiveMode();
    }
    private void showUnavailable(String reason){
        LinearLayout root=new LinearLayout(this);
        root.setBackgroundColor(0xff111b2b);
        root.setPadding(28,45,28,28);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView text=new TextView(this);text.setText(reason);
        text.setTextSize(22);text.setTextColor(Color.WHITE);
        root.addView(text);
        Button back=new Button(this);back.setText("Indietro");
        root.addView(back);back.setOnClickListener(v->finish());
        setContentView(root);
    }
    private void play(){
        if(media==null||video==null)return;
        if(active){video.start();scheduleHide();return;}
        if(!(media.url.startsWith("http://")||media.url.startsWith("https://"))){
            status.setText("URL di riproduzione non valido.");return;
        }
        try{
            status.setText("Connessione alla sorgente...");
            video.setVideoURI(Uri.parse(media.url));
            video.requestFocus();active=true;video.start();
        }catch(Exception e){
            active=false;status.setText("Impossibile avviare il lettore interno.");
            showControls();
        }
    }
    private void openExternal(){
        if(media==null)return;
        try{
            Intent view=new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(Uri.parse(media.url),"video/*");
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(view,"Lettore per "+media.title));
        }catch(Exception e){
            if(status!=null)status.setText("Nessun lettore esterno compatibile.");
            showControls();
        }
    }
    private void savePlayback(){
        if(media==null||video==null||"TV".equals(media.kind))return;
        try{
            int duration=video.getDuration(),position=video.getCurrentPosition();
            if(duration>0&&duration<12*3600*1000&&position>=0){
                try(IptvLibrary library=new IptvLibrary(this)){
                    library.saveProgress(media.id,position,duration);
                }
            }
        }catch(Exception e){android.util.Log.w("FuoconeroIPTV","Saving playback failed",e);}
    }
    @Override protected void onPause(){
        savePlayback();
        if(video!=null&&video.isPlaying())video.pause();
        handler.removeCallbacks(heartbeat);handler.removeCallbacks(hideControls);
        new Thread(()->{
            try{IptvPublisher.publish(getApplicationContext());}
            catch(Exception e){android.util.Log.w("FuoconeroIPTV","Projectivy refresh deferred",e);}
        },"FuoconeroWatchlistUpdate").start();
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();immersiveMode();
        if(video!=null)handler.postDelayed(heartbeat,10000L);
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(heartbeat);handler.removeCallbacks(hideControls);
        if(video!=null)video.stopPlayback();
        super.onDestroy();
    }
}
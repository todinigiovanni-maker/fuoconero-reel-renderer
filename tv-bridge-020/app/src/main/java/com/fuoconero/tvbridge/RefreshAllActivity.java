package com.fuoconero.tvbridge;
import android.app.Activity;
import android.os.*;
import android.widget.*;
public class RefreshAllActivity extends Activity {
    final Handler handler=new Handler(Looper.getMainLooper());TextView status;
    final Runnable tick=new Runnable(){public void run(){status.setText(getSharedPreferences("manual_refresh",MODE_PRIVATE).getString("status","In attesa della rete…"));handler.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle b){super.onCreate(b);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(64,48,64,48);
        TextView title=new TextView(this);title.setText("Aggiorna tutto");title.setTextSize(32);box.addView(title);
        status=new TextView(this);status.setTextSize(22);box.addView(status);
        TextView note=new TextView(this);note.setText("Puoi tornare alla home: l’aggiornamento continua in background.");note.setTextSize(18);box.addView(note);
        Button back=new Button(this);back.setText("Torna alla home");back.setOnClickListener(w->{
            try{startActivity(new android.content.Intent().setComponent(new android.content.ComponentName("com.spocky.projengmenu","com.spocky.projengmenu.ui.home.MainActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(android.content.ActivityNotFoundException ignored){}
            finish();
        });box.addView(back);setContentView(box);back.requestFocus();
        ManualRefreshJob.request(this);
    }
    @Override protected void onResume(){super.onResume();handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
}


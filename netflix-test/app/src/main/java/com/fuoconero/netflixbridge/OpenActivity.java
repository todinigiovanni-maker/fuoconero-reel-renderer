package com.fuoconero.netflixbridge;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.widget.*;
public final class OpenActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        String key=getIntent().getStringExtra("key");
        if("app".equals(key)){openApp();return;}
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(64,40,64,40);setContentView(l);
        TextView t=new TextView(this);t.setTextSize(24);t.setText("Apertura del contenuto Netflix…");l.addView(t);
        Button fallback=new Button(this);fallback.setText("Apri Netflix");fallback.setOnClickListener(v->openApp());l.addView(fallback);
        // Reload the shared source by ID; never accept an arbitrary launch intent from the caller.
        new Thread(()->{
            NetflixSource.Result r=NetflixSource.read(this);NetflixSource.Item found=null;
            for(NetflixSource.Item i:r.items)if((i.owner+":"+i.id).equals(key)){found=i;break;}
            NetflixSource.Item item=found;
            runOnUiThread(()->{
                Intent target=item==null?null:NetflixLinks.safe(item.intent,item.owner);
                if(item!=null&&NetflixLinks.resolves(this,target,item.owner)) {
                    try{startActivity(target);t.setText("Se Netflix non ha aperto il titolo, usa Apri Netflix.");return;}catch(RuntimeException ignored){}
                }
                openApp();
            });
        },"NetflixOpen").start();
    }
    private void openApp() {
        Intent i=NetflixLinks.launcher(this);
        if(i!=null)try{startActivity(i);finish();return;}catch(RuntimeException ignored){}
        TextView t=new TextView(this);t.setPadding(64,64,64,64);t.setTextSize(26);t.setText("Netflix non è disponibile. Installa o abilita l'app Netflix per continuare.");setContentView(t);
    }
}

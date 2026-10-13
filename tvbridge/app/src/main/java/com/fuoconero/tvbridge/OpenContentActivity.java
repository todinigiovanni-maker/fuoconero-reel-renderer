package com.fuoconero.tvbridge;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.widget.Toast;

public class OpenContentActivity extends Activity {
    @Override public void onCreate(Bundle state) {
       super.onCreate(state);
       openVeezie(this);
       finish();
    }
    static void openVeezie(Context context){
        try{
           Intent intent=context.getPackageManager().getLaunchIntentForPackage("st.veezie");
           if(intent==null)throw new IllegalStateException("Veezie non installato o non visibile");
           intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
           context.startActivity(intent);
        }catch(Exception e){
           Toast.makeText(context,"Impossibile aprire Veezie: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
}

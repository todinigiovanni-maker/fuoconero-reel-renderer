package com.fuoconero.tvbridge;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;

public class OpenContentActivity extends Activity {
    @Override public void onCreate(Bundle b){super.onCreate(b);openVeezie(this);finish();}
    public static boolean openVeezie(Context ctx) {
        try {
            Intent target=ctx.getPackageManager().getLaunchIntentForPackage("st.veezie");
            if(target==null)return false;
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(target);
            return true;
        } catch(Exception e){return false;}
    }
}

package com.fuoconero.tvbridge;
import android.content.*;
/** System boot/package events schedule bounded work, never run imports here. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?"":intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        HomeRefreshJob.schedule(context);HomeRefreshJob.request(context);
    }
}


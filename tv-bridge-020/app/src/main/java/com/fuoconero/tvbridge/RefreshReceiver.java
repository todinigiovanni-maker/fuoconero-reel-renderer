package com.fuoconero.tvbridge;
import android.content.*;
import android.app.job.*;
public class RefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(!"com.fuoconero.tvbridge.REFRESH_ALL".equals(i.getAction()))return;
        SharedPreferences p=c.getSharedPreferences("tvbridge",Context.MODE_PRIVATE);long now=System.currentTimeMillis();
        if(now-p.getLong("manual_refresh_requested",0)<60000)return;
        JobScheduler s=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(s!=null&&s.getPendingJob(2022)==null&&s.schedule(new JobInfo.Builder(2022,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())==JobScheduler.RESULT_SUCCESS)
            p.edit().putLong("manual_refresh_requested",now).apply();
    }
}


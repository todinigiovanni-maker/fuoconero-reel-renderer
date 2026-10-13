package com.fuoconero.tvbridge;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ComponentName;

public class SyncJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(()->{
            try{
                String token=getSharedPreferences("trakt",MODE_PRIVATE).getString("access_token","");
                if(!token.isEmpty())TvSync.perform(this,msg->{});
            } catch(Exception e){android.util.Log.w("FuoconeroTV","Sincronizzazione automatica sospesa: "+e.getMessage());}
            finally{jobFinished(params,false);}
        }).start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}
    public static void schedule(Context c){
        JobScheduler s=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(s==null)return;
        JobInfo j=new JobInfo.Builder(2020,new ComponentName(c,SyncJob.class))
            .setPeriodic(12L*60*60*1000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build();
        s.schedule(j);
    }
}

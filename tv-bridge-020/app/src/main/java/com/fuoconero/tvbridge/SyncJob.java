package com.fuoconero.tvbridge;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Network-conscious Trakt updates: a deferred refresh when returning from
 * Veezie to Projectivy, plus a periodic safety refresh.
 *
 * JobScheduler controls actual launch time. No continuous polling.
 */
public class SyncJob extends JobService {
    static final int PERIODIC_JOB=2020;
    static final int RETURN_JOB=2021;
    static final long MIN_INTERVAL_MS=4L*60L*1000L;

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(()->{
            try{
                SharedPreferences state=getSharedPreferences("tvbridge",MODE_PRIVATE);
                long now=System.currentTimeMillis();
                long previous=state.getLong("last_sync_started",0L);
                if(previous>0 && now-previous<MIN_INTERVAL_MS){
                    // Another manual/automatic refresh ran recently.
                    if(params.getJobId()==RETURN_JOB)
                        requestRefresh(this);
                    return;
                }
                String token=getSharedPreferences("trakt",MODE_PRIVATE)
                    .getString("access_token","");
                if(token==null || token.isEmpty())return;
                TvSync.perform(this,msg->Log.i("FuoconeroTV",msg));
            }catch(Exception e){
                Log.w("FuoconeroTV","Aggiornamento automatico sospeso: "+e.getMessage());
            }finally{
                jobFinished(params,false);
            }
        },"FuoconeroTraktSync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}

    public static void requestRefresh(Context context){
        String access=context.getSharedPreferences("trakt",Context.MODE_PRIVATE)
            .getString("access_token","");
        if(access==null || access.isEmpty())return; // No Trakt account means no polling.
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        SharedPreferences state=context.getSharedPreferences("tvbridge",Context.MODE_PRIVATE);
        long last=state.getLong("last_sync_started",0L);
        long elapsed=Math.max(0L,System.currentTimeMillis()-last);
        long wait=last<=0?8000L:Math.max(8000L,MIN_INTERVAL_MS-elapsed+15000L);
        JobInfo request=new JobInfo.Builder(RETURN_JOB,new ComponentName(context,SyncJob.class))
            .setMinimumLatency(wait)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(false).build();
        if(scheduler.schedule(request)==JobScheduler.RESULT_SUCCESS){
            state.edit().putLong("refresh_requested_at",System.currentTimeMillis())
                .putLong("refresh_earliest_at",System.currentTimeMillis()+wait).apply();
        }
    }

    public static void schedule(Context context){
        String access=context.getSharedPreferences("trakt",Context.MODE_PRIVATE)
            .getString("access_token","");
        if(access==null || access.isEmpty())return;
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        JobInfo info=new JobInfo.Builder(PERIODIC_JOB,new ComponentName(context,SyncJob.class))
            .setPeriodic(30L*60L*1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(false).build();
        scheduler.schedule(info);
    }
}

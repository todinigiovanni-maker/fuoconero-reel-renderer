package com.fuoconero.tvbridge;
import android.app.job.*;
import android.content.*;
public class HomeRefreshJob extends JobService {
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean(false);
    public static void schedule(Context c){
        JobScheduler s=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);
        if(s!=null&&s.getPendingJob(4304)==null)s.schedule(new JobInfo.Builder(4304,new ComponentName(c,HomeRefreshJob.class))
            .setPeriodic(15L*60L*1000L).build());
    }
    public static void request(Context c){
        long now=System.currentTimeMillis();
        SharedPreferences p=c.getSharedPreferences("auto043",Context.MODE_PRIVATE);
        if(now-p.getLong("request_at",0)<4L*60L*1000L)return;
        JobScheduler s=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);
        if(s==null)return;
        if(s.getPendingJob(4305)!=null)return;
        int scheduled=s.schedule(new JobInfo.Builder(4305,new ComponentName(c,HomeRefreshJob.class))
            .setMinimumLatency(8000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        if(scheduled==JobScheduler.RESULT_SUCCESS)p.edit().putLong("request_at",now).apply();
    }
    @Override public boolean onStartJob(JobParameters p){
        if(!BUSY.compareAndSet(false,true))return false;
        new Thread(()->{try{
            long now=System.currentTimeMillis();
            SharedPreferences trakt=getSharedPreferences("trakt",MODE_PRIVATE);
            if(!trakt.getString("access_token","").isEmpty() &&
               now-getSharedPreferences("tvbridge",MODE_PRIVATE).getLong("last_sync_started",0)>=240000)
                try{TvSync.perform(this,m->{});}catch(Exception ignored){}
            SharedPreferences iptv=getSharedPreferences(IptvStore.PREFS,MODE_PRIVATE);
            SharedPreferences attempts=getSharedPreferences("auto043",MODE_PRIVATE);
            EpgRefreshJob.configureFromPlaylist(this);EpgRefreshJob.schedule(this);
            boolean configured=!iptv.getString("source_path","").isEmpty()||!iptv.getString("source_uri","").isEmpty()||!iptv.getString("source_url","").isEmpty();
            if(configured && now-iptv.getLong("last_success",0)>=12L*3600L*1000L &&
               now-attempts.getLong("iptv_attempt",0)>=30L*60L*1000L){
                attempts.edit().putLong("iptv_attempt",now).apply();
                try{IptvRefreshJob.update(this,m->{});}catch(Exception ignored){}
            }
            if(now-getSharedPreferences("epg",MODE_PRIVATE).getLong("last_success",0)>=6L*3600L*1000L &&
               now-attempts.getLong("epg_attempt",0)>=30L*60L*1000L){
                attempts.edit().putLong("epg_attempt",now).apply();
                try{EpgRefreshJob.refresh(this);}catch(Exception ignored){}
            }
            IptvPublisher.publish(this);
            getSharedPreferences("auto043",MODE_PRIVATE).edit().putLong("last_completed",System.currentTimeMillis()).apply();
        }catch(Exception ignored){}finally{BUSY.set(false);jobFinished(p,false);}},"Fuoconero043Home").start();return true;
    }
    @Override public boolean onStopJob(JobParameters p){return true;}
}


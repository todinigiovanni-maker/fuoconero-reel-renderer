package com.fuoconero.netflixbridge;
import android.app.job.*;
import android.content.*;
public final class RefreshJob extends JobService {
    static final int JOB_ID=6101;
    static final class Run { Thread worker; volatile boolean stopped; }
    Run current;
    static void schedule(Context c){
        JobScheduler s=c.getSystemService(JobScheduler.class);JobInfo old=s.getPendingJob(JOB_ID);
        if(old==null||old.getIntervalMillis()!=CatalogData.DAY||!old.isPersisted()||old.getNetworkType()!=JobInfo.NETWORK_TYPE_ANY)
            s.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,RefreshJob.class))
                .setPeriodic(CatalogData.DAY).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    public boolean onStartJob(JobParameters p){
        Run run=new Run();current=run;
        run.worker=new Thread(()->{boolean retry=false;try{Publisher.refresh(this);retry=getSharedPreferences(Publisher.PREF,0).getBoolean("catalog_stale",false);}
            catch(Exception e){retry=true;}finally{if(!run.stopped)jobFinished(p,retry);}},"NetflixJob");run.worker.start();return true;
    }
    public boolean onStopJob(JobParameters p){Run run=current;if(run!=null){run.stopped=true;if(run.worker!=null)run.worker.interrupt();}return true;}
}

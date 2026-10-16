package com.fuoconero.netflixbridge;
import android.app.job.*;
import android.content.*;
public final class RefreshJob extends JobService {
    static void schedule(Context c){JobScheduler s=c.getSystemService(JobScheduler.class);if(s.getPendingJob(6101)==null)s.schedule(new JobInfo.Builder(6101,new ComponentName(c,RefreshJob.class)).setPeriodic(3600000).build());}
    public boolean onStartJob(JobParameters p){new Thread(()->{try{Publisher.refresh(this);}catch(Exception ignored){}finally{jobFinished(p,false);}},"NetflixJob").start();return true;}
    public boolean onStopJob(JobParameters p){return true;}
}

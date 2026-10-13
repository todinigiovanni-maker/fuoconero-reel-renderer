package com.fuoconero.tvbridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Refresh only from a source deliberately configured by the user. */
public class IptvRefreshJob extends JobService {
    static final int JOB_ID=4080;
    static final long PERIOD=12L*60*60*1000;
    @Override public boolean onStartJob(JobParameters params){
        new Thread(()->{
            try{
                update(this,msg->Log.i("FuoconeroIPTV",msg));
            }catch(Exception e){
                Log.w("FuoconeroIPTV","Aggiornamento periodico IPTV: "+e.getClass().getSimpleName());
            }finally{jobFinished(params,false);}
        },"FuoconeroIptvRefresh").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}

    public static String update(Context ctx,IptvStore.Progress progress)throws Exception{
        SharedPreferences prefs=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
        String remote=prefs.getString("source_url","").trim();
        String savedUri=prefs.getString("source_uri","").trim();
        if(remote.isEmpty() && savedUri.isEmpty())
            throw new IOException("Importa un file M3U o inserisci l'URL della lista.");
        try(InputStream in=!remote.isEmpty()?openRemote(remote):
            ctx.getContentResolver().openInputStream(Uri.parse(savedUri))){
            if(in==null)throw new IOException("La fonte M3U non e' accessibile.");
            String result=new IptvStore(ctx).importPlaylist(ctx,in,progress);
            String projectivy=IptvPublisher.publish(ctx);
            schedule(ctx);
            return result+"\n"+projectivy;
        }
    }
    private static InputStream openRemote(String url)throws Exception{
        if(!url.startsWith("https://")&&!url.startsWith("http://"))
            throw new IOException("URL M3U non valido. Usa HTTP o HTTPS.");
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent","FuoconeroIPTV/0.4.0");
        int result=connection.getResponseCode();
        if(result<200 || result>=300){
            connection.disconnect();
            throw new IOException("Server playlist HTTP "+result);
        }
        if(connection.getContentLengthLong()>190L*1024*1024){
            connection.disconnect();
            throw new IOException("Playlist troppo grande");
        }
        return new java.io.FilterInputStream(connection.getInputStream()){
            long bytes=0;
            @Override public int read()throws IOException {
                int i=super.read();
                if(i>=0 && ++bytes>190L*1024*1024)throw new IOException("Playlist troppo grande");
                return i;
            }
            @Override public int read(byte[] b,int off,int len)throws IOException{
                int n=super.read(b,off,len);
                if(n>0 && (bytes+=n)>190L*1024*1024)throw new IOException("Playlist troppo grande");
                return n;
            }
            @Override public void close()throws IOException{
                try{super.close();}finally{connection.disconnect();}
            }
        };
    }
    public static void schedule(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
        boolean online=!p.getString("source_url","").isEmpty();
        boolean local=!p.getString("source_uri","").isEmpty();
        if(!online&&!local)return;
        JobScheduler scheduler=(JobScheduler)ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(ctx,IptvRefreshJob.class))
            .setPeriodic(PERIOD)
            .setRequiredNetworkType(online?JobInfo.NETWORK_TYPE_ANY:JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(false).build();
        scheduler.schedule(info);
    }
}

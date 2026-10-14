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
import java.io.File;
import java.io.FileInputStream;
import android.os.Environment;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Refresh only from a source deliberately configured by the user. */
public class IptvRefreshJob extends JobService {
    static final int JOB_ID=4080;
    static final long PERIOD=12L*60*60*1000;
    static final long FLEX=Math.max(JobInfo.getMinFlexMillis(),Math.max(30L*60*1000,PERIOD/20));
    private static final java.util.concurrent.atomic.AtomicBoolean UPDATING=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    public static boolean isUpdating(){return UPDATING.get();}
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
        if(!UPDATING.compareAndSet(false,true))
            throw new IOException("Importazione gia' in corso. Attendi la fine prima di aggiornare di nuovo.");
        try{
            SharedPreferences prefs=ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE);
            String remote=prefs.getString("source_url","").trim();
            String savedUri=prefs.getString("source_uri","").trim();
            String savedPath=prefs.getString("source_path","").trim();
            if(remote.isEmpty() && savedUri.isEmpty() && savedPath.isEmpty())
                throw new IOException("Importa un file M3U da Download o inserisci l'URL della lista.");
            try(InputStream in=!remote.isEmpty()?openRemote(remote):
                !savedPath.isEmpty()?openLocalFile(ctx,savedPath):
                ctx.getContentResolver().openInputStream(Uri.parse(savedUri))){
                if(in==null)throw new IOException("La fonte M3U non e' accessibile.");
                String result;
                try(IptvStore store=new IptvStore(ctx)){
                    result=store.importPlaylist(ctx,in,progress);
                }
                String projectivy=IptvPublisher.publish(ctx);
                return result+"\n"+projectivy;
            }
        }catch(Exception e){
            ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE).edit().putString("refresh_error","Lista non aggiornata: controlla rete o sorgente").apply();
            RefreshActionCard.refreshExisting(ctx);throw e;
        }finally{
            UPDATING.set(false);
        }
    }
    private static InputStream openLocalFile(Context ctx,String path)throws Exception{
        File file=new File(path).getCanonicalFile();
        File publicDownloads=Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS).getCanonicalFile();
        File appDir=ctx.getFilesDir().getCanonicalFile();
        File appExternal=ctx.getExternalFilesDir(null);
        boolean allowed=isDirectChild(file,publicDownloads)||
            isDirectChild(file,appDir)||
            (appExternal!=null && isDirectChild(file,appExternal.getCanonicalFile()));
        if(!allowed)throw new IOException("Percorso M3U non consentito");
        if(!file.isFile()||!file.canRead())
            throw new IOException("M3U non accessibile: "+file.getName());
        return new FileInputStream(file);
    }
    private static boolean isDirectChild(File file,File directory){
        return directory.equals(file.getParentFile());
    }
    private static InputStream openRemote(String url)throws Exception{
        if(!url.startsWith("https://")&&!url.startsWith("http://"))
            throw new IOException("URL M3U non valido. Usa HTTP o HTTPS.");
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent","FuoconeroIPTV/0.4.2");
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
        HomeRefreshJob.schedule(ctx);
    }
}


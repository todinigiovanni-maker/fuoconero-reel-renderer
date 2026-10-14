package com.fuoconero.tvbridge;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import java.io.*;
import java.net.*;

public class EpgRefreshJob extends JobService {
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean(false);
    public static boolean configureFromPlaylist(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("epg",MODE_PRIVATE);
        if(!p.getString("url","").isEmpty()||!p.getString("uri","").isEmpty())return false;
        try(IptvStore store=new IptvStore(c);android.database.Cursor rows=store.getReadableDatabase().rawQuery(
            "SELECT url FROM entries WHERE kind='Film' AND adult=0 LIMIT 1",null)){
            if(!rows.moveToFirst())return false;
            Uri u=Uri.parse(rows.getString(0));java.util.List<String> parts=u.getPathSegments();
            if(parts.size()!=4||!"movie".equals(parts.get(0)))return false;
            String url=new Uri.Builder().scheme(u.getScheme()).encodedAuthority(u.getEncodedAuthority()).path("/xmltv.php")
                .appendQueryParameter("username",parts.get(1)).appendQueryParameter("password",parts.get(2)).build().toString();
            p.edit().putString("url",url).putBoolean("auto_source",true).apply();return true;
        }catch(Exception ignored){return false;}
    }
    public static String refresh(Context c)throws Exception{
        if(!BUSY.compareAndSet(false,true))return "Aggiornamento guida già in corso";
        HttpURLConnection connection=null;
        try{
            android.content.SharedPreferences p=c.getSharedPreferences("epg",MODE_PRIVATE);
            String url=p.getString("url",""),file=p.getString("uri","");
            InputStream in;
            if(!url.isEmpty()){
                if(!(url.startsWith("http://")||url.startsWith("https://")))throw new IOException("Usa un URL HTTP(S)");
                connection=(HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(10000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent","Fuoconero043");
                if(connection.getResponseCode()!=200)throw new IOException("Guida non raggiungibile");
                in=connection.getInputStream();
            }else if(!file.isEmpty())in=c.getContentResolver().openInputStream(Uri.parse(file));
            else return "Configura una guida XMLTV";
            if(in==null)throw new IOException("File guida non accessibile");
            int count;try(InputStream source=in;EpgStore epg=new EpgStore(c)){count=epg.importGuide(source);}
            IptvPublisher.publish(c);RecentStore.publish(c);
            return "Guida aggiornata: "+count+" programmi";
        }finally{if(connection!=null)connection.disconnect();BUSY.set(false);}
    }
    public static void schedule(Context c){
        HomeRefreshJob.schedule(c);
    }
    public static void reschedule(Context c){
        JobScheduler s=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);if(s!=null)s.cancel(4303);schedule(c);
    }
    @Override public boolean onStartJob(JobParameters p){
        new Thread(()->{try{refresh(this);}catch(Exception e){android.util.Log.w("Fuoconero043","EPG non disponibile; cache conservata");}
            finally{jobFinished(p,false);}},"Fuoconero043EPG").start();return true;
    }
    @Override public boolean onStopJob(JobParameters p){return true;}
}


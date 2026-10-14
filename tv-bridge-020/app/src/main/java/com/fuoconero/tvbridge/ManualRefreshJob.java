package com.fuoconero.tvbridge;
import android.app.job.*;
import android.content.*;
import java.util.ArrayList;
public class ManualRefreshJob extends JobService {
    static final int ID=4306;
    static void status(Context c,String text){c.getSharedPreferences("manual_refresh",MODE_PRIVATE).edit().putString("status",text).apply();}
    public static synchronized void request(Context c){
        JobScheduler s=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);if(s==null)return;
        SharedPreferences p=c.getSharedPreferences("manual_refresh",MODE_PRIVATE);long now=System.currentTimeMillis();
        if(s.getPendingJob(ID)!=null||now-p.getLong("requested",0)<60000)return;
        if(s.schedule(new JobInfo.Builder(ID,new ComponentName(c,ManualRefreshJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_LINEAR).build())==JobScheduler.RESULT_SUCCESS){
            p.edit().putLong("requested",now).putString("status","Aggiornamento richiesto, in attesa della rete…").apply();
        }else status(c,"Impossibile avviare l’aggiornamento. Riprova tra un minuto.");
    }
    @Override public boolean onStartJob(JobParameters p){
        if(!HomeRefreshJob.BUSY.compareAndSet(false,true)){status(this,"Attendo la fine dell’aggiornamento già in corso…");new android.os.Handler(getMainLooper()).post(()->jobFinished(p,true));return true;}
        new Thread(()->{
            ArrayList<String> errors=new ArrayList<>();
            try{
                status(this,"Aggiorno Veezie/Trakt e le prossime puntate…");
                sendBroadcast(new Intent("com.fuoconero.tvbridge.REFRESH_ALL").setComponent(new ComponentName("com.fuoconero.tvbridge.test037","com.fuoconero.tvbridge.RefreshReceiver")));
                if(!getSharedPreferences("trakt",MODE_PRIVATE).getString("access_token","").isEmpty())try{TvSync.perform(this,m->{});}catch(Exception e){errors.add("Veezie/Trakt");}
                status(this,"Aggiorno il catalogo IPTV e le locandine…");
                SharedPreferences iptv=getSharedPreferences(IptvStore.PREFS,MODE_PRIVATE);
                boolean configured=!iptv.getString("source_path","").isEmpty()||!iptv.getString("source_uri","").isEmpty()||!iptv.getString("source_url","").isEmpty();
                if(configured)try{IptvRefreshJob.update(this,m->status(this,m));IptvMetadata.refreshNow(this);}catch(Exception e){errors.add("catalogo IPTV");}
                status(this,"Aggiorno la guida TV…");
                try{EpgRefreshJob.configureFromPlaylist(this);EpgRefreshJob.refresh(this);}catch(Exception e){errors.add("guida TV");}
                status(this,"Aggiorno le card della home…");
                IptvPublisher.publish(this);RecentStore.publish(this);
                status(this,errors.isEmpty()?"Aggiornamento completato. Richiesta inviata anche alla riga Veezie/Trakt 0.3.7.":"Aggiornamento parziale. Da riprovare: "+android.text.TextUtils.join(", ",errors)+". I dati precedenti restano disponibili.");
                getSharedPreferences("manual_refresh",MODE_PRIVATE).edit().putLong("completed",System.currentTimeMillis()).apply();
            }catch(Exception e){status(this,"Aggiornamento interrotto. Riprova tra un minuto.");}
            finally{RefreshActionCard.refreshExisting(this);HomeRefreshJob.BUSY.set(false);jobFinished(p,false);}
        },"FuoconeroManualRefresh").start();return true;
    }
    @Override public boolean onStopJob(JobParameters p){return true;}
}


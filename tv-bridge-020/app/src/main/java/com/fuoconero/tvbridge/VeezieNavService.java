package com.fuoconero.tvbridge;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayDeque;
import java.util.Locale;
import android.os.SystemClock;

/**
 * User-initiated, time-limited accessibility navigation of Veezie.
 *
 * Reads and acts only on st.veezie foreground nodes, and only after the user
 * clicks a Fuoconero TV card. Does not inspect other apps, auto-play media,
 * or perform gestures at guessed coordinates.
 */
public class VeezieNavService extends AccessibilityService {
    static final String PREFS="veezie_nav";
    static final long TIMEOUT_MS=25000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long requestId=-1;
    private int stage=0;
    private String title="";
    private long lastAction=0;
    private int attempts=0;
    private boolean running=false;

    private final Runnable worker=new Runnable(){
        @Override public void run(){
            tick();
            if(running)handler.postDelayed(this,650L);
        }
    };
    @Override public void onServiceConnected(){
        super.onServiceConnected();
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("service_seen",true).apply();
        queue();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event==null||event.getPackageName()==null)return;
        String pkg=event.getPackageName().toString();
        long now=System.currentTimeMillis();
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        if("st.veezie".equals(pkg)){
            // Only record activity in Veezie. Never inspect unrelated applications.
            if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                ||event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                p.edit().putLong("last_veezie_event",now).apply();
            if(now<p.getLong("probe_until",0L)) samplePlayback(p,now);
            queue();
            return;
        }
        if("com.spocky.projengmenu".equals(pkg) &&
            event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            long watched=p.getLong("last_veezie_event",0L);
            long requested=p.getLong("last_projectivy_refresh",0L);
            // Once per Veezie visit and only on returning to Projectivy.
            // The job runs no sooner than the cooldown and only with network.
            if(watched>requested && now-watched>=0 && now-watched<6L*60L*60L*1000L){
                p.edit().putLong("last_projectivy_refresh",now).apply();
                SyncJob.requestRefresh(getApplicationContext());
            }
        }
    }
    /**
     * Explicit short-lived probe: reads only exposed playback slider metadata
     * while Veezie is in the foreground. No screen text or auth data stored.
     * Never marks episodes watched or guesses a position for a Trakt item.
     */
    public static void startPlaybackProbe(Context ctx){
        long now=System.currentTimeMillis();
        getPrefs(ctx).edit()
            .putLong("probe_until",now+120000L)
            .putLong("probe_last_scan",0L)
            .putLong("probe_last_candidate",0L)
            .putInt("probe_last_percent",-1)
            .putInt("probe_slider_hits",0)
            .putInt("probe_time_label_count",0)
            .putString("probe_last_class","")
            .putString("probe_result","In attesa: apri una puntata in Veezie, visualizza i comandi video per alcuni secondi.")
            .apply();
    }
    private void samplePlayback(SharedPreferences prefs,long now){
        if(now-prefs.getLong("probe_last_scan",0)<1500L)return;
        prefs.edit().putLong("probe_last_scan",now).apply();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || root.getPackageName()==null ||
            !"st.veezie".contentEquals(root.getPackageName()))return;
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.add(root);
        int checked=0,timeCount=0;
        while(!stack.isEmpty()&&checked++<350){
            AccessibilityNodeInfo node=stack.removeFirst();
            if(node.isVisibleToUser() && node.getPackageName()!=null &&
                "st.veezie".contentEquals(node.getPackageName())){
                String cls=node.getClassName()==null?"":node.getClassName().toString().toLowerCase(Locale.ROOT);
                String id=node.getViewIdResourceName()==null?"":node.getViewIdResourceName().toLowerCase(Locale.ROOT);
                String shown=node.getText()==null?"":node.getText().toString().trim();
                if(shown.matches("[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?"))timeCount++;
                boolean slider=cls.contains("seekbar")||cls.contains("slider")||
                    id.contains("seekbar")||id.endsWith("/seek");
                AccessibilityNodeInfo.RangeInfo range=slider?node.getRangeInfo():null;
                if(range!=null && range.getMax()>range.getMin()){
                    float frac=(range.getCurrent()-range.getMin())/(range.getMax()-range.getMin());
                    if(Float.isFinite(frac) && frac>=0 && frac<=1){
                        int percent=Math.round(frac*100f);
                        prefs.edit().putLong("probe_last_candidate",now)
                            .putInt("probe_last_percent",percent)
                            .putInt("probe_slider_hits",prefs.getInt("probe_slider_hits",0)+1)
                            .putString("probe_last_class",cls.length()>75?cls.substring(0,75):cls)
                            .putString("probe_result","Barra visibile rilevata dentro Veezie. NON ancora associata a una puntata.")
                            .apply();
                    }
                }
            }
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo c=node.getChild(i);
                if(c!=null)stack.addLast(c);
            }
        }
        if(timeCount>0)prefs.edit().putInt("probe_time_label_count",timeCount).apply();
    }
    @Override public void onInterrupt(){halt("Interrotto");}
    @Override public void onDestroy(){
        halt("Servizio interrotto");
        super.onDestroy();
    }
    private void queue(){
        if(!running){
            running=true;
            handler.removeCallbacks(worker);
            handler.postDelayed(worker,450L);
        }
    }
    private void halt(String message){
        running=false;
        handler.removeCallbacks(worker);
        if(requestId>0 && !message.isEmpty())finish(message);
    }
    private void finish(String status){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean("pending",false)
            .putString("result",status)
            .putLong("finished_at",System.currentTimeMillis()).apply();
        running=false;
        handler.removeCallbacks(worker);
        requestId=-1;
    }
    private void tick(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        if(!p.getBoolean("pending",false)){
            running=false;
            handler.removeCallbacks(worker);
            requestId=-1;
            return;
        }
        long id=p.getLong("request_id",-1);
        if(id!=requestId){
            requestId=id;
            stage=0;attempts=0;lastAction=0;
            title=p.getString("title","").trim();
        }
        long now=System.currentTimeMillis();
        if(now>p.getLong("expires",0)){finish("Tempo scaduto: navigazione manuale");return;}
        if(title.isEmpty()){finish("Titolo non disponibile");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || root.getPackageName()==null ||
            !"st.veezie".contentEquals(root.getPackageName()))return;
        // Throttle each step, so events caused by our own actions cannot loop.
        if(now-lastAction<850)return;
        try{
            // 0: locate search icon, 1: text field, 2: exact match, 3: Episodes button
            if(stage==0){
                AccessibilityNodeInfo edit=findEdit(root);
                if(edit!=null){stage=1;return;}
                AccessibilityNodeInfo search=findLabel(root,new String[]{"cerca","search"},
                    true,false);
                if(search!=null && click(search)){
                    stage=1;lastAction=now;setStatus("Ricerca aperta");
                }else setStatus("Cerco il pulsante Cerca nella home Veezie");
            }else if(stage==1){
                AccessibilityNodeInfo edit=findEdit(root);
                if(edit!=null){
                    Bundle args=new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title);
                    if(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){
                        stage=2;lastAction=now;
                        setStatus("Titolo scritto. Attendo i risultati: "+title);
                        if(Build.VERSION.SDK_INT>=30)edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                    }
                }else{
                    attempts++;
                    if(attempts>12)finish("Campo ricerca non accessibile; cerca manualmente");
                }
            }else if(stage==2){
                AccessibilityNodeInfo result=findExactTitle(root,title);
                if(result!=null && click(result)){
                    stage=3;lastAction=now;attempts=0;
                    setStatus("Titolo selezionato; cerco Episodi");
                }else if(++attempts>18){
                    finish("Titolo non individuato tra i risultati; completa manualmente");
                }
            }else if(stage==3){
                AccessibilityNodeInfo episodes=findLabel(root,
                    new String[]{"episodi","episodes"},true,false);
                if(episodes!=null && click(episodes)){
                    lastAction=now;
                    finish("Elenco episodi aperto: "+title);
                }else if(++attempts>15){
                    finish("Scheda selezionata, ma pulsante Episodi non accessibile");
                }
            }
        }catch(Exception e){
            android.util.Log.w("FuoconeroTV","Veezie navigation issue",e);
            finish("Navigazione interrotta: interfaccia Veezie non riconosciuta");
        }
    }
    private void setStatus(String status){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("result",status).apply();
    }
    private static AccessibilityNodeInfo findEdit(AccessibilityNodeInfo root){
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.add(root);int checked=0;
        while(!stack.isEmpty() && checked++<450){
            AccessibilityNodeInfo n=stack.removeFirst();
            if(n.isVisibleToUser() && n.isEditable() &&
                "st.veezie".contentEquals(n.getPackageName()))return n;
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)stack.addLast(c);
            }
        }
        return null;
    }
    private static AccessibilityNodeInfo findExactTitle(AccessibilityNodeInfo root,String title){
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.add(root);int checked=0;
        String expected=title.trim().toLowerCase(Locale.ROOT);
        while(!stack.isEmpty() && checked++<450){
            AccessibilityNodeInfo n=stack.removeFirst();
            String text=n.getText()==null?"":n.getText().toString().trim().toLowerCase(Locale.ROOT);
            // Never click the search field itself as if it were a result.
            if(n.isVisibleToUser() && !n.isEditable() && text.equals(expected)
                && "st.veezie".contentEquals(n.getPackageName()))return n;
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)stack.addLast(c);
            }
        }
        return null;
    }
    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo root,
        String[] labels,boolean exact,boolean editable){
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.add(root);int checked=0;
        while(!stack.isEmpty()&&checked++<450){
            AccessibilityNodeInfo n=stack.removeFirst();
            if(n.isVisibleToUser() && (!editable || n.isEditable()) &&
                "st.veezie".contentEquals(n.getPackageName())){
                String text=n.getText()==null?"":n.getText().toString().trim().toLowerCase(Locale.ROOT);
                String desc=n.getContentDescription()==null?"":n.getContentDescription().toString().trim().toLowerCase(Locale.ROOT);
                String viewId=n.getViewIdResourceName()==null?"":n.getViewIdResourceName().toLowerCase(Locale.ROOT);
                for(String label:labels){
                    if((exact?(text.equals(label)||desc.equals(label)):
                        (text.contains(label)||desc.contains(label))) ||
                        (label.equals("search") && viewId.endsWith("/search")) ||
                        (label.equals("cerca") && viewId.endsWith("/btn_search"))){
                        return n;
                    }
                }
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)stack.addLast(c);
            }
        }
        return null;
    }
    private static boolean click(AccessibilityNodeInfo node){
        AccessibilityNodeInfo n=node;
        for(int i=0;i<6 && n!=null;i++){
            if(n.isVisibleToUser() && n.isClickable())
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n=n.getParent();
        }
        return false;
    }
    public static void request(Context ctx,String title,String season,String ep){
        long now=System.currentTimeMillis();
        getPrefs(ctx).edit()
            .putBoolean("pending",true)
            .putLong("request_id",now)
            .putLong("expires",now+TIMEOUT_MS)
            .putString("title",title)
            .putString("season",season)
            .putString("episode",ep)
            .putString("result","Avvio ricerca Veezie: "+title)
            .apply();
    }
    public static void cancel(Context ctx){
        getPrefs(ctx).edit().putBoolean("pending",false)
            .putString("result","Annullato dall'utente").apply();
    }
    private static SharedPreferences getPrefs(Context ctx){
        return ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    public static String diagnose(Context ctx){
        SharedPreferences p=getPrefs(ctx);
        boolean enabled=isEnabled(ctx);
        return "Navigazione Veezie accessibilita: "+(enabled?"ABILITATA":"NON ATTIVA")+
            "\nAmbito: solo interfaccia di Veezie"+
            "\nAutomazione avviata: "+(p.getBoolean("pending",false)?"SI":"NO")+
            "\nUltimo stato: "+p.getString("result","non ancora provata")+
            "\nLimite ricerca: 25 secondi; nessun clic se il titolo non e' riconosciuto"+
            "\nSonda riproduzione: "+(System.currentTimeMillis()<p.getLong("probe_until",0L)?"ATTIVA (2 min)":"NON ATTIVA")+
            "\nBarre Veezie osservate: "+p.getInt("probe_slider_hits",0)+
            "\nUltima barra osservata: "+(p.getInt("probe_last_percent",-1)<0?"non rilevata":
                p.getInt("probe_last_percent",-1)+"% (NON verificata come avanzamento puntata)")+
            "\nIndicatori temporali visibili: "+p.getInt("probe_time_label_count",0)+
            "\nEsito sonda: "+p.getString("probe_result","non ancora eseguita")+
            "\nAggiornamento automatico Trakt: richiesto al ritorno in Projectivy, soggetto a cooldown"+
            "\nRevochi il permesso in Impostazioni > Accessibilita.";
    }
    public static boolean isEnabled(Context ctx){
        String enabled=android.provider.Settings.Secure.getString(
            ctx.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(enabled==null)return false;
        String component=ctx.getPackageName()+"/com.fuoconero.tvbridge.VeezieNavService";
        String shortComponent=ctx.getPackageName()+"/.VeezieNavService";
        for(String id:enabled.split(":")){
            if(id.equalsIgnoreCase(component)||id.equalsIgnoreCase(shortComponent))return true;
        }
        return false;
    }
}

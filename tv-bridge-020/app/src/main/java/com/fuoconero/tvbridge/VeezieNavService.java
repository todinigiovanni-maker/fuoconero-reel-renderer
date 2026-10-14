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
import java.util.LinkedHashMap;
import java.util.Map;
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
    static final long TIMEOUT_MS=60000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long requestId=-1;
    private int stage=0;
    private String title="",season="",episode="";
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
        handler.post(playbackObserver);
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
            handler.removeCallbacks(playbackObserver);
            handler.post(playbackObserver);
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

    private long playbackLastScan=0;
    private final Runnable playbackObserver=new Runnable(){
        @Override public void run(){
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null || root.getPackageName()==null || !"st.veezie".contentEquals(root.getPackageName()))return;
            long now=SystemClock.elapsedRealtime();
            if(now-playbackLastScan>=2000L){
                playbackLastScan=now;
                String title=playerText(root,"exo_title");
                String position=playerText(root,"exo_position"),duration=playerText(root,"exo_duration");
                String subtitle=playerText(root,"exo_subtitle");
                // IDs observed on the actual Veezie 3.4.7 player: no clock ordering guesses.
                if(!title.isEmpty() && isClock(position) && isClock(duration)){
                    String season="",episode="";
                    java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([0-9]{1,3})x([0-9]{1,3})\\b.*")
                        .matcher(subtitle);
                    boolean valid=subtitle.isEmpty();
                    if(m.matches()){
                        season=String.valueOf(Integer.parseInt(m.group(1)));
                        episode=String.valueOf(Integer.parseInt(m.group(2)));valid=true;
                    }
                    if(valid)TvSync.recordVeeziePlayback(getApplicationContext(),title,season,episode,
                        clockSeconds(position)*1000L,clockSeconds(duration)*1000L);
                }
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this,2000L);
        }
    };
    private String playerText(AccessibilityNodeInfo root,String id){
        java.util.List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId("st.veezie:id/"+id);
        if(nodes==null || nodes.size()!=1)return "";
        AccessibilityNodeInfo node=nodes.get(0);
        return node.isVisibleToUser() && node.getText()!=null?node.getText().toString().trim():"";
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
            .putString("probe_clock_1","")
            .putString("probe_clock_2","")
            .putString("probe_clock_ids","")
            .putString("probe_clock_interpretation","In attesa di due tempi Veezie")
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
        LinkedHashMap<String,String> clocks=new LinkedHashMap<>();
        while(!stack.isEmpty()&&checked++<350){
            AccessibilityNodeInfo node=stack.removeFirst();
            if(node.isVisibleToUser() && node.getPackageName()!=null &&
                "st.veezie".contentEquals(node.getPackageName())){
                String cls=node.getClassName()==null?"":node.getClassName().toString().toLowerCase(Locale.ROOT);
                String id=node.getViewIdResourceName()==null?"":node.getViewIdResourceName().toLowerCase(Locale.ROOT);
                String shown=node.getText()==null?"":node.getText().toString().trim();
                // Veezie often exposes two clock labels even when a native
                // seek bar is NOT exported to Android Accessibility.
                if(isClock(shown)){
                    timeCount++;
                    if(clocks.size()<4 && !clocks.containsKey(shown)){
                        String safeId=id.length()>65?id.substring(id.length()-65):id;
                        clocks.put(shown,safeId);
                    }
                }
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
        if(timeCount>0){
            java.util.ArrayList<String> found=new java.util.ArrayList<>(clocks.keySet());
            String first=found.size()>0?found.get(0):"";
            String second=found.size()>1?found.get(1):"";
            String hint="Servono due indicatori temporali";
            if(!first.isEmpty() && !second.isEmpty()){
                long a=clockSeconds(first),b=clockSeconds(second);
                if(a>=0 && b>0 && a<=b && b>=120 && b<=21600){
                    hint="Candidato: "+Math.round(100.0*a/b)+"% (ordine tempi NON verificato, nessuna scheda modificata)";
                }else{
                    hint="I due tempi non identificano ancora con certezza elapsed / duration";
                }
            }
            java.util.ArrayList<String> ids=new java.util.ArrayList<>(clocks.values());
            prefs.edit()
                .putInt("probe_time_label_count",timeCount)
                .putString("probe_clock_1",first)
                .putString("probe_clock_2",second)
                .putString("probe_clock_ids",String.join(" ; ",ids))
                .putString("probe_clock_interpretation",hint)
                .putString("probe_result","Due tempi letti da Veezie; significato da verificare prima di aggiornare Projectivy.")
                .apply();
        }
    }
    private static boolean isClock(String value){
        return value!=null && value.matches("-?(?:[0-9]{1,3}:)?[0-9]{1,2}:[0-9]{2}");
    }
    private static long clockSeconds(String value){
        if(!isClock(value)||value.startsWith("-"))return -1;
        try{
            String[] parts=value.split(":");
            long seconds=0;
            for(String part:parts)seconds=seconds*60+Long.parseLong(part);
            return seconds;
        }catch(NumberFormatException e){return -1;}
    }
    @Override public void onInterrupt(){halt("Interrotto");}
    @Override public void onDestroy(){
        handler.removeCallbacks(playbackObserver);
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
            season=p.getString("season","").trim();
            episode=p.getString("episode","").trim();
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
            if(stage<=1){
                AccessibilityNodeInfo close=findLabel(root,new String[]{"chiudi","close"},true,false);
                if(close!=null&&click(close)){lastAction=now;attempts=0;return;}
                String[] typeLabels=season.matches("[0-9]{1,3}")?
                    new String[]{"serie tv","tv series"}:new String[]{"film","movies"};
                AccessibilityNodeInfo typePrompt=findLabel(root,new String[]{"cosa vuoi cercare?"},true,false);
                AccessibilityNodeInfo type=typePrompt==null?null:findPromptControl(typePrompt,typeLabels);
                if(type!=null&&click(type)){
                    stage=1;lastAction=now;attempts=0;setStatus("Tipo contenuto selezionato");return;
                }
                AccessibilityNodeInfo globalPrompt=findLabel(root,new String[]{"dove vuoi cercare?"},true,false);
                AccessibilityNodeInfo global=globalPrompt==null?null:findPromptControl(globalPrompt,new String[]{"ricerca globale","global search"});
                if(global!=null&&click(global)){
                    stage=1;lastAction=now;attempts=0;setStatus("Ricerca globale selezionata");return;
                }
            }
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
                    if(attempts>24)finish("Campo ricerca non accessibile; cerca manualmente");
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
                if(!season.matches("[0-9]{1,3}")||!episode.matches("[0-9]{1,3}")){
                    if(findLabel(root,new String[]{"riproduci","play"},true,false)!=null){
                        finish("Scheda contenuto aperta: "+title);return;
                    }
                    if(++attempts>20)finish("Titolo selezionato; scheda da completare manualmente");
                    return;
                }
                AccessibilityNodeInfo episodes=findLabel(root,
                    new String[]{"episodi","episodes"},true,false);
                if(episodes!=null && click(episodes)){
                    lastAction=now;
                    stage=4;attempts=0;setStatus("Scelgo la stagione "+season);
                }else if(++attempts>15){
                    finish("Scheda selezionata, ma pulsante Episodi non accessibile");
                }
            }else if(stage==4){
                AccessibilityNodeInfo selected=findNumbered(root,season,true);
                if(selected!=null&&click(selected)){
                    stage=5;attempts=0;lastAction=now;
                    setStatus("Stagione "+season+" aperta; cerco episodio "+episode);
                }else if(++attempts>15){
                    finish("Elenco stagioni aperto; stagione richiesta non disponibile");
                }else if(attempts%3==0){scroll(root,true);lastAction=now;}
            }else if(stage==5){
                AccessibilityNodeInfo selected=findNumbered(root,episode,false);
                if(selected!=null&&click(selected)){
                    stage=6;attempts=0;lastAction=now;
                    setStatus("Episodio selezionato; attendo il menu Riproduci");
                }else if(++attempts>20){
                    finish("Stagione aperta; episodio richiesto non individuato");
                }else{
                    scroll(root,attempts>5);lastAction=now;
                }
            }else if(stage==6){
                AccessibilityNodeInfo prompt=findLabel(root,new String[]{"cosa vuoi fare?","what do you want to do?"},true,false);
                AccessibilityNodeInfo play=prompt==null?null:findPromptControl(prompt,new String[]{"riproduci","play"});
                if(play!=null){
                    finish("Menu Riproduci verificato: "+title+" S"+season+"E"+episode+
                        ". Scegli Riproduci o l'host in Veezie.");return;
                }
                if(++attempts>12){finish("Episodio selezionato, ma menu Riproduci non comparso; premi OK sulla puntata");return;}
                // A WebView may accept ACTION_CLICK by focusing the card only.
                // Retry only the exact episode, and never a playback control.
                if(attempts%3==0 && prompt==null){
                    AccessibilityNodeInfo selected=findNumbered(root,episode,false);
                    if(selected!=null&&click(selected))lastAction=now;
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

    private static AccessibilityNodeInfo findPromptControl(AccessibilityNodeInfo prompt,String[] labels){
        AccessibilityNodeInfo parent=prompt.getParent();
        for(int i=0;i<3&&parent!=null;i++){
            if("android.webkit.WebView".contentEquals(parent.getClassName()))break;
            AccessibilityNodeInfo control=findLabel(parent,labels,true,false);
            if(control!=null)return control;
            parent=parent.getParent();
        }
        return null;
    }
    private static String label(CharSequence value){
        if(value==null)return "";
        return value.toString().replaceAll("[\\p{Co}\\p{So}\\p{Cntrl}]"," ")
            .replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
    }
    private static AccessibilityNodeInfo findNumbered(AccessibilityNodeInfo root,String number,boolean season){
        int expected;
        try{expected=Integer.parseInt(number);}catch(Exception e){return null;}
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile(
            season?"^(?:stagione|season)\\s*0*([0-9]{1,3})\\b.*":
                "^(?:episodio\\s*)?0*([0-9]{1,3})\\s*[-–:].*");
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();stack.add(root);
        AccessibilityNodeInfo fallback=null;int checked=0;
        while(!stack.isEmpty()&&checked++<850){
            AccessibilityNodeInfo node=stack.removeFirst();
            if(node.isVisibleToUser()&&!node.isEditable()&&"st.veezie".contentEquals(node.getPackageName())){
                String text=label(node.getText());
                java.util.regex.Matcher match=pattern.matcher(text);
                if(match.matches()&&Integer.parseInt(match.group(1))==expected){
                    if(!season||text.matches(".*\\bita\\b.*"))return node;
                    if(fallback==null)fallback=node;
                }
            }
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo child=node.getChild(i);if(child!=null)stack.addLast(child);
            }
        }
        return fallback;
    }
    private static boolean scroll(AccessibilityNodeInfo root,boolean forward){
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();stack.add(root);int checked=0;
        java.util.ArrayList<AccessibilityNodeInfo> scrollables=new java.util.ArrayList<>();
        while(!stack.isEmpty()&&checked++<850){
            AccessibilityNodeInfo node=stack.removeFirst();
            if(node.isVisibleToUser()&&node.isScrollable()&&"st.veezie".contentEquals(node.getPackageName())
                 )scrollables.add(node);
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo child=node.getChild(i);if(child!=null)stack.addLast(child);
            }
        }
        // Veezie retains its page behind modal lists: prefer the deepest active list.
        for(int i=scrollables.size()-1;i>=0;i--){
            if(scrollables.get(i).performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;
        }
        return false;
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
        // Only choose results in the active search pane, never a home card behind it.
        AccessibilityNodeInfo edit=findEdit(root);
        if(edit==null)return null;
        AccessibilityNodeInfo pane=edit.getParent();
        for(int depth=0;depth<5 && pane!=null;depth++){
            if(pane.isScrollable()){root=pane;break;}
            AccessibilityNodeInfo parent=pane.getParent();
            if(parent==null||"android.webkit.WebView".contentEquals(parent.getClassName())){root=pane;break;}
            pane=parent;
        }
        ArrayDeque<AccessibilityNodeInfo> stack=new ArrayDeque<>();
        stack.add(root);int checked=0;
        String expected=label(title);
        while(!stack.isEmpty() && checked++<450){
            AccessibilityNodeInfo n=stack.removeFirst();
            String text=label(n.getText());
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
                String text=label(n.getText());
                String desc=label(n.getContentDescription());
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
            "\nLimite ricerca: 60 secondi; nessun clic se il titolo non e' riconosciuto"+
            "\nSonda riproduzione: "+(System.currentTimeMillis()<p.getLong("probe_until",0L)?"ATTIVA (2 min)":"NON ATTIVA")+
            "\nBarre Veezie osservate: "+p.getInt("probe_slider_hits",0)+
            "\nUltima barra osservata: "+(p.getInt("probe_last_percent",-1)<0?"non rilevata":
                p.getInt("probe_last_percent",-1)+"% (NON verificata come avanzamento puntata)")+
            "\nIndicatori temporali visibili: "+p.getInt("probe_time_label_count",0)+
            "\nTempo 1 letto da Veezie: "+p.getString("probe_clock_1","nessuno")+
            "\nTempo 2 letto da Veezie: "+p.getString("probe_clock_2","nessuno")+
            "\nCampi UI temporali: "+p.getString("probe_clock_ids","nessuno")+
            "\nInterpretazione: "+p.getString("probe_clock_interpretation","non ancora provata")+
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


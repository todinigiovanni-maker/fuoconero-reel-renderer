package com.fuoconero.tvbridge;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;
import org.json.*;

public class MainActivity extends Activity {
    private EditText clientId, secret;
    private TextView output;
    private SharedPreferences prefs;
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean stopped=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("trakt",MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30,20,30,20);
        root.setBackgroundColor(Color.rgb(17,21,34));
        ScrollView scroll=new ScrollView(this); scroll.addView(root);
        setContentView(scroll);

        TextView title=text("FUOCONERO • TV BRIDGE",27,0xffff8345);root.addView(title);
        root.addView(text("Versione 0.2.2 diagnostica | Trakt → Android TV / Projectivy",16,0xffd0d7ec));
        root.addView(text("I nuovi collegamenti aprono la HOME di Veezie, non il singolo episodio.",14,0xffa7b4cb));

        clientId=new EditText(this);
        clientId.setSingleLine(true);clientId.setTextColor(Color.WHITE);clientId.setHintTextColor(0xffadb2c8);
        clientId.setHint("Client ID Trakt");clientId.setText(prefs.getString("client_id",""));
        root.addView(clientId);
        secret=new EditText(this);
        secret.setSingleLine(true);secret.setTextColor(Color.WHITE);secret.setHintTextColor(0xffadb2c8);
        secret.setHint("Client Secret (opzionale)");secret.setInputType(129);
        secret.setText(prefs.getString("client_secret",""));
        root.addView(secret);

        addButton(root,"1 • Salva Client ID",v->saveCredentials());
        addButton(root,"2 • Autorizza Trakt (codice sul telefono)",v->{saveCredentials();run(this::authorize);});
        addButton(root,"3 • Sincronizza e conta le schede",v->{saveCredentials();run(this::sync);});
        addButton(root,"4 • Prova apertura Veezie",v->OpenContentActivity.openVeezie(this));
        addButton(root,"5 • Rimuovi la riga Fuoconero",v->run(()->{
            try { BridgeStore.remove(this);say("Riga Fuoconero rimossa."); }
            catch(Exception e){say("Errore rimozione: "+e);}
        }));
        output=text("Pronto. Trakt: "+(prefs.getString("access_token","").isEmpty()?"non collegato":"collegato")+".\nPremi Sincronizza per leggere il numero reale di schede pubblicate.",18,Color.WHITE);
        root.addView(output);
    }

    private TextView text(String s,int sz,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sz);
        t.setPadding(4,10,4,12);return t;
    }
    private void addButton(LinearLayout root,String label,View.OnClickListener click){
        Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);
        root.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(click);
    }
    private void saveCredentials(){
        prefs.edit().putString("client_id",clientId.getText().toString().trim())
                .putString("client_secret",secret.getText().toString().trim()).apply();
        say("Client ID salvato. "+(prefs.getString("access_token","").isEmpty()?"Trakt da autorizzare.":"Trakt già autorizzato."));
    }
    private void say(String s){runOnUiThread(()->output.setText(s));}
    private void run(Runnable action){ worker.submit(()->{try{action.run();}catch(Exception e){say("ERRORE: "+e.toString());}}); }

    private static JSONObject request(String method,String path,JSONObject body,String client,String token)throws Exception{
        URL url=new URL("https://api.trakt.tv"+path);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);
        c.setRequestMethod(method);c.setRequestProperty("trakt-api-version","2");
        c.setRequestProperty("trakt-api-key",client);c.setRequestProperty("Accept","application/json");
        if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
        if(body!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}
        }
        int status=c.getResponseCode();
        InputStream in=status>=400?c.getErrorStream():c.getInputStream();
        StringBuilder result=new StringBuilder();
        if(in!=null)try(BufferedReader rd=new BufferedReader(new InputStreamReader(in,"UTF-8"))){
          String line; while((line=rd.readLine())!=null)result.append(line);
        }
        c.disconnect();
        if(status>=400)throw new HttpError(status,result.toString());
        String s=result.toString();
        return s.startsWith("[")?new JSONObject().put("data",new JSONArray(s)):new JSONObject(s);
    }
    private static class HttpError extends Exception{
        final int code;
        HttpError(int code,String desc){super("HTTP "+code+" "+desc);this.code=code;}
    }
    private String client(){return prefs.getString("client_id","");}
    private String token(){return prefs.getString("access_token","");}

    private void authorize() {
       if(client().isEmpty()){say("Inserisci e salva prima il Client ID.");return;}
       try{
         JSONObject response=request("POST","/oauth/device/code",
             new JSONObject().put("client_id",client()),client(),null);
         String device=response.getString("device_code");
         String user=response.getString("user_code");
         String url=response.optString("verification_url","https://trakt.tv/activate");
         int interval=Math.max(5,response.optInt("interval",5));
         long expires=System.currentTimeMillis()+1000L*response.optInt("expires_in",600);
         say("Apri "+url+" dal telefono e inserisci il codice:\n\n"+user+"\n\nIn attesa dell'autorizzazione...");
         while(System.currentTimeMillis()<expires && !stopped){
            try {
              Thread.sleep(interval*1000L);
              JSONObject payload=new JSONObject().put("code",device).put("client_id",client());
              String cs=prefs.getString("client_secret","");
              if(!cs.isEmpty()) payload.put("client_secret",cs);
              JSONObject auth=request("POST","/oauth/device/token",payload,client(),null);
              prefs.edit().putString("access_token",auth.getString("access_token"))
                  .putString("refresh_token",auth.optString("refresh_token","")).apply();
              say("Trakt collegato! Premi «3 • Sincronizza e conta le schede».");return;
            } catch(HttpError e) {
              if(e.code==400||e.code==404||e.code==429)continue;
              if(e.code==410){say("Codice scaduto: richiedine un altro.");return;}
              throw e;
            }
         }
         say("Autorizzazione non completata entro il tempo disponibile.");
       }catch(Exception e){say("Errore autorizzazione Trakt: "+e);}
    }
    private void sync(){
        if(client().isEmpty()||token().isEmpty()){say("Collega prima l'account Trakt.");return;}
        try{
            say("1/3 • Recupero film e serie da Trakt...");
            java.util.List<BridgeStore.Item> items=BridgeStore.collect(this,client(),token(),(method,path)->request(method,path,null,client(),token()));
            say("2/3 • "+items.size()+" titoli Trakt. Preparo le locandine e le schede...");
            BridgeStore.Stats stats=BridgeStore.publish(this,items,
                txt->say("2/3 • "+txt));
            say("3/3 • CONTROLLO COMPLETATO\n"+
                "Titoli ricevuti da Trakt: "+stats.traktCount+"\n"+
                "Schede create: "+stats.created+"\n"+
                "Schede aggiornate: "+stats.updated+"\n"+
                "Errori di scrittura: "+stats.errors+"\n"+
                "Schede leggibili nel canale Android TV: "+stats.readBack+"\n"+
                "Poster scaricati: "+stats.downloaded+"\n"+
                "Poster segnaposto: "+stats.fallback+"\n"+
                (stats.lastError==null?"":"Ultimo errore: "+stats.lastError+"\n")+
                "\nControlla la riga Fuoconero in Projectivy. Il clic apre Veezie.");
        }catch(HttpError e){say("Errore Trakt: "+e.getMessage()+
           (e.code==401?"\nToken scaduto: autorizza nuovamente.":""));}
         catch(Exception e){say("Errore sincronizzazione: "+e);}
    }
    @Override protected void onDestroy(){ stopped=true;worker.shutdownNow();super.onDestroy();}
}

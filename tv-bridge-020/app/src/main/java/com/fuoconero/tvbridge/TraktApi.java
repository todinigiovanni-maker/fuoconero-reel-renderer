package com.fuoconero.tvbridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class TraktApi {
    private static final String BASE="https://api.trakt.tv";
    private final SharedPreferences p;
    private static final String COOLDOWN_KEY = "trakt_cooldown_until";
    private static final long DEFAULT_COOLDOWN_MS = 30L * 60L * 1000L;
    private void assertAllowed() throws ApiError {
        long remaining = p.getLong(COOLDOWN_KEY,0L) - System.currentTimeMillis();
        if(remaining > 0) {
            long minutes = (remaining + 59999L) / 60000L;
            throw new ApiError(429,"Trakt temporaneamente limitato: riprova tra circa "+minutes+" min. I dati già presenti restano visibili.");
        }
    }
    private ApiError httpFailure(int status, HttpURLConnection c, String response) {
        if(status == 429) {
            long wait = DEFAULT_COOLDOWN_MS;
            String retryAfter = c.getHeaderField("Retry-After");
            if(retryAfter != null) {
                try { wait = Math.max(60000L, Long.parseLong(retryAfter.trim()) * 1000L); }
                catch(NumberFormatException ignored) { /* conservatively use default */ }
            }
            wait = Math.min(wait,24L*60L*60L*1000L);
            p.edit().putLong(COOLDOWN_KEY,System.currentTimeMillis()+wait).apply();
            return new ApiError(429, "Trakt HTTP 429 (limite richieste / Cloudflare 1015). Attendi circa "+
                ((wait+59999L)/60000L)+" min prima di riprovare. Nessuna scheda esistente è stata cancellata.");
        }
        return new ApiError(status,"Trakt HTTP "+status+" "+response.substring(0,Math.min(response.length(),200)));
    }
    private final String clientId,clientSecret;
    public interface Progress {void say(String text);}
    public static final class DeviceCode {
        public final String deviceCode,userCode,url;
        public final int interval,expires;
        DeviceCode(JSONObject json){
            deviceCode=json.optString("device_code");
            userCode=json.optString("user_code");
            url=json.optString("verification_url","https://trakt.tv/activate");
            interval=Math.max(5,json.optInt("interval",5));
            expires=Math.min(900,Math.max(60,json.optInt("expires_in",600)));
        }
    }
    public TraktApi(Context c) {
        p=c.getSharedPreferences("trakt",Context.MODE_PRIVATE);
        clientId=p.getString("client_id","").trim();
        clientSecret=p.getString("client_secret","").trim();
        if(clientId.isEmpty())throw new IllegalStateException("Inserisci il Client ID Trakt nelle impostazioni.");
    }
    public DeviceCode startDevice()throws Exception {
        JSONObject data=new JSONObject();data.put("client_id",clientId);
        return new DeviceCode(request("POST","/oauth/device/code",data,false));
    }
    public void waitForApproval(DeviceCode code,Progress progress)throws Exception {
        if(code.deviceCode.isEmpty())throw new Exception("Trakt non ha inviato il codice del dispositivo");
        final long limit=System.currentTimeMillis()+code.expires*1000L;
        int interval=code.interval;
        while(System.currentTimeMillis()<limit&&!Thread.currentThread().isInterrupted()) {
            Thread.sleep(interval*1000L);
            JSONObject data=new JSONObject();data.put("code",code.deviceCode);
            data.put("client_id",clientId);
            if(!clientSecret.isEmpty())data.put("client_secret",clientSecret);
            try {
                JSONObject token=request("POST","/oauth/device/token",data,false);
                storeToken(token);
                if(p.getString("access_token","").isEmpty())throw new Exception("Nessun access_token");
                progress.say("Trakt autorizzato!");
                return;
            }catch(ApiError e){
                // Device Code grant: awaiting user, too many polls, expired or denied.
                if(e.code==400 || e.code==409) continue;
                if(e.code==429)throw e; // Avoid appearing frozen on a Trakt rate limit.
                throw e;
            }
        }
        throw new Exception("Codice scaduto: premi di nuovo Collega Trakt");
    }
    public JSONObject get(String path)throws Exception {
        return request("GET",path,null,true);
    }
    public String accessToken()throws Exception {
        String token=p.getString("access_token","");
        if(token.isEmpty())throw new Exception("Trakt non collegato: usa la voce 1.");
        long expire=p.getLong("expires_at",0);
        if(expire>0 && System.currentTimeMillis()+60000>expire) {
            String refresh=p.getString("refresh_token","");
            if(refresh.isEmpty())throw new Exception("Token scaduto: ricollega Trakt.");
            JSONObject data=new JSONObject(); data.put("grant_type","refresh_token");
            data.put("refresh_token",refresh);data.put("client_id",clientId);
            if(!clientSecret.isEmpty())data.put("client_secret",clientSecret);
            try{storeToken(request("POST","/oauth/token",data,false));}
            catch(Exception e){throw new Exception("Rinnovo Trakt fallito: ricollega l'account. "+e.getMessage());}
            token=p.getString("access_token","");
        }
        return token;
    }
    private void storeToken(JSONObject token)throws Exception {
        String access=token.optString("access_token","");
        if(access.isEmpty())throw new Exception("Risposta Trakt priva di access_token");
        long seconds=token.optLong("expires_in",604800);
        p.edit().putString("access_token",access)
            .putString("refresh_token",token.optString("refresh_token",p.getString("refresh_token","")))
            .putLong("expires_at",System.currentTimeMillis()+seconds*1000L).apply();
    }
    private JSONObject request(String method,String path,JSONObject body,boolean bearer)throws Exception {
        assertAllowed();
        URL url=new URL(BASE+path);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(18000);
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("trakt-api-version","2");
        c.setRequestProperty("trakt-api-key",clientId);
        c.setRequestProperty("User-Agent","FuoconeroTVBridge/0.2.0");
        if(bearer)c.setRequestProperty("Authorization","Bearer "+accessToken());
        try {
            if(body!=null){
                byte[] json=body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                try(OutputStream o=c.getOutputStream()){o.write(json);}
            }
            int status=c.getResponseCode();
            InputStream stream=(status>=400)?c.getErrorStream():c.getInputStream();
            String response=read(stream);
            if(status>=400)throw httpFailure(status,c,response);
            return new JSONObject(response);
        }finally{c.disconnect();}
    }
    public org.json.JSONArray getArray(String path)throws Exception {
        assertAllowed();
        URL url=new URL(BASE+path);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(18000);
        c.setRequestProperty("trakt-api-key",clientId);
        c.setRequestProperty("trakt-api-version","2");
        c.setRequestProperty("Authorization","Bearer "+accessToken());
        c.setRequestProperty("User-Agent","FuoconeroTVBridge/0.2.0");
        try {
            int status=c.getResponseCode();
            InputStream stream=(status>=400)?c.getErrorStream():c.getInputStream();
            String response=read(stream);
            if(status>=400)throw httpFailure(status,c,response);
            return new org.json.JSONArray(response);
        }finally{c.disconnect();}
    }
    private static String read(InputStream in)throws Exception{
        if(in==null)return "";
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;int total=0;
            while((n=stream.read(buf))!=-1){
                total+=n;
                if(total>3*1024*1024)throw new Exception("Risposta Trakt troppo grande");
                out.write(buf,0,n);
            }
            return out.toString("UTF-8");
        }
    }
    public static final class ApiError extends Exception{
        public final int code;
        ApiError(int code,String msg){super(msg);this.code=code;}
    }
}

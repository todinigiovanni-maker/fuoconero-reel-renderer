package com.fuoconero.netflixbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

public final class CatalogSource {
    static final String BASE="https://about.netflix.com/api/data/releases?language=it&country=IT";
    public static final class Result {
        public List<CatalogData.Item> items = new ArrayList<>();
        public String status;
        public long checked;
        public boolean stale;
    }
    static String get(String url)throws IOException {
        HttpsURLConnection c=(HttpsURLConnection)new java.net.URL(url).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","FuoconeroNetflixBridgeTest/0.2 (public Netflix release schedule)");
        try {
            int code=c.getResponseCode(); if(code!=200)throw new IOException("Schedule HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192];int n,total=0;
                while((n=in.read(b))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("Oversized response");out.write(b,0,n);}
                return out.toString("UTF-8");
            }
        } finally {c.disconnect();}
    }
    static List<CatalogData.Item> fetch(long now)throws Exception {
        ArrayList<CatalogData.Item> all=new ArrayList<>();
        for(int type:new int[]{CatalogData.FILM,CatalogData.SERIES}) {
            int pages=1;
            for(int page=1;page<=pages;page++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                JSONObject data=new JSONObject(get(BASE+"&collection="+type+"&page="+page));
                pages=data.getInt("totalPages");
                if(pages<0||pages>8||data.getInt("current")!=page)throw new JSONException("Invalid pagination");
                all.addAll(CatalogData.parse(data,type,now));
            }
        }
        return CatalogData.recent(all,now);
    }
    static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"catalog-it.json"));}
    public static List<CatalogData.Item> cached(Context c,long now) {
        ArrayList<CatalogData.Item> all=new ArrayList<>();
        try {
            JSONObject root=new JSONObject(new String(file(c).readFully(),StandardCharsets.UTF_8));
            JSONArray data=root.getJSONArray("items");
            if(data.length()>30)return all;
            for(int n=0;n<data.length();n++){
                JSONObject row=data.getJSONObject(n);
                JSONObject page=new JSONObject().put("data",new JSONArray().put(row));
                all.addAll(CatalogData.parse(page,row.getInt("collection"),now));
            }
        }catch(Exception ignored){return new ArrayList<>();}
        return CatalogData.recent(all,now);
    }
    static void save(Context c,List<CatalogData.Item> items)throws Exception {
        JSONArray a=new JSONArray();for(CatalogData.Item i:items)a.put(CatalogData.encode(i));
        byte[] bytes=new JSONObject().put("items",a).toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile target=file(c);FileOutputStream stream=null;
        try {stream=target.startWrite();stream.write(bytes);target.finishWrite(stream);}
        catch(Exception e){if(stream!=null)target.failWrite(stream);throw e;}
    }
    public static synchronized Result read(Context c,boolean force) {
        long now=System.currentTimeMillis();SharedPreferences p=c.getSharedPreferences(Publisher.PREF,0);
        Result r=new Result();r.checked=p.getLong("catalog_checked",0);r.items=cached(c,now);
        long attempt=p.getLong("catalog_attempt",0);
        if(!force&&r.checked>0&&now>=r.checked&&now-r.checked<CatalogData.DAY){r.status="Novità Italia · fonte Netflix · aggiornamento giornaliero";return r;}
        if(!force&&attempt>0&&now>=attempt&&now-attempt<15*60_000L){r.stale=true;r.status="Novità Italia · ultimo catalogo salvato; nuovo tentativo più tardi";return r;}
        p.edit().putLong("catalog_attempt",now).apply();
        try {
            List<CatalogData.Item> fresh=fetch(now);save(c,fresh);r.items=fresh;r.checked=now;
            p.edit().putLong("catalog_checked",now).apply();
            r.status=fresh.size()+" novità Italia · fonte About Netflix · ultimi 30 giorni";
        }catch(Exception e){r.stale=true;r.status=r.items.isEmpty()?"Novità Italia temporaneamente non disponibili · riprova più tardi":
            "Novità Italia non aggiornate · mostro l'ultimo catalogo salvato";}
        return r;
    }
}

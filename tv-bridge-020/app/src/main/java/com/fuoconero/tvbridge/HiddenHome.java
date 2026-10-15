package com.fuoconero.tvbridge;
import android.content.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import org.json.*;
import java.util.*;

/** Home-only suppression. Catalog, favorites and playback history are never changed. */
public final class HiddenHome {
    static final String ACTION="action:manage-home";
    static final String[] COLUMNS={"channel_id","internal_provider_id","content_id","title","short_description","type","weight","poster_art_uri","poster_art_aspect_ratio","intent_uri","season_display_number","episode_display_number","duration_millis","last_playback_position_millis"};
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("hidden_home",Context.MODE_PRIVATE);}
    static String key(ContentValues v){
        String internal=v.getAsString("internal_provider_id");
        if(internal==null||internal.startsWith("action:"))return "";
        if(internal.startsWith("news:")){String[] parts=internal.split(":",3);if(parts.length==3&&v.getAsString("title")!=null)return "news:"+parts[1]+":"+v.getAsString("title").toLowerCase(Locale.ROOT);}
        String content=v.getAsString("content_id");if(content!=null&&!content.isEmpty())return content;
        if(internal.matches("fuoconero:iptv_[0-9]+"))return "iptv:"+internal.substring("fuoconero:iptv_".length());
        return internal;
    }
    static JSONObject json(ContentValues v)throws Exception{
        JSONObject out=new JSONObject();for(String col:COLUMNS)if(v.containsKey(col))out.put(col,v.get(col)==null?JSONObject.NULL:v.get(col));return out;
    }
    static ContentValues values(JSONObject o)throws Exception{
        ContentValues v=new ContentValues();for(String col:COLUMNS)if(o.has(col)){
            Object x=o.get(col);if(x==JSONObject.NULL)v.putNull(col);else if(x instanceof Number)v.put(col,((Number)x).longValue());else v.put(col,x.toString());
        }return v;
    }
    static ArrayList<ContentValues> current(Context c){
        ArrayList<ContentValues> rows=new ArrayList<>();
        String[] projection=Arrays.copyOf(COLUMNS,COLUMNS.length+1);projection[COLUMNS.length]="package_name";
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,projection,null,null,null)){
            if(q!=null)while(q.moveToNext()){
                if(!c.getPackageName().equals(q.getString(COLUMNS.length)))continue;
                ContentValues v=new ContentValues();for(int i=0;i<COLUMNS.length;i++)if(q.isNull(i))v.putNull(COLUMNS[i]);else if(q.getType(i)==Cursor.FIELD_TYPE_INTEGER)v.put(COLUMNS[i],q.getLong(i));else v.put(COLUMNS[i],q.getString(i));
                rows.add(v);
            }
        }return rows;
    }
    static Long existing(Context c,ContentValues v){
        try(Cursor q=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,new String[]{"_id","channel_id","internal_provider_id"},null,null,null)){
            if(q!=null)while(q.moveToNext())if(q.getLong(1)==v.getAsLong("channel_id")&&Objects.equals(q.getString(2),v.getAsString("internal_provider_id")))return q.getLong(0);
        }return null;
    }
    static void remember(Context c,String key,ContentValues v)throws Exception{
        JSONObject saved=new JSONObject(prefs(c).getString(key,"{}"));saved.put(String.valueOf(v.getAsLong("channel_id")),json(v));
        if(!prefs(c).edit().putString(key,saved.toString()).commit())throw new java.io.IOException("Salvataggio non riuscito");
    }
    public static synchronized void write(Context c,ContentValues v,Long previous)throws Exception{
        String key=key(v);
        if(!key.isEmpty()&&prefs(c).contains(key)){
            remember(c,key,v);if(previous!=null)c.getContentResolver().delete(TvContract.buildPreviewProgramUri(previous),null,null);return;
        }
        put(c,v,previous);
    }
    private static void put(Context c,ContentValues v,Long previous){
        if(previous==null)c.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI,v);
        else{ContentValues update=new ContentValues(v);update.remove("channel_id");c.getContentResolver().update(TvContract.buildPreviewProgramUri(previous),update,null,null);}
    }
    public static synchronized void hide(Context c,String key)throws Exception{
        if(key.isEmpty()||key.startsWith("action:"))return;
        for(ContentValues v:current(c))if(key.equals(key(v))){remember(c,key,v);Long id=existing(c,v);if(id!=null)c.getContentResolver().delete(TvContract.buildPreviewProgramUri(id),null,null);}
    }
    public static synchronized void restore(Context c,String key)throws Exception{
        if(!prefs(c).contains(key))return;
        JSONObject saved=new JSONObject(prefs(c).getString(key,"{}"));
        for(Iterator<String> it=saved.keys();it.hasNext();){ContentValues v=values(saved.getJSONObject(it.next()));
            try(Cursor ch=c.getContentResolver().query(TvContract.buildChannelUri(v.getAsLong("channel_id")),new String[]{"_id"},null,null,null)){
                if(ch!=null&&ch.moveToFirst())put(c,v,existing(c,v));
            }
        }
        if(!prefs(c).edit().remove(key).commit())throw new java.io.IOException("Ripristino non salvato");
    }
    static boolean privateRow(Context c,ContentValues v){return Objects.equals(v.getAsLong("channel_id"),c.getSharedPreferences("iptv_channels",0).getLong("channel_Adulti_TV",-1));}
    public static synchronized LinkedHashMap<String,String> titles(Context c,boolean hidden)throws Exception{
        TreeMap<String,String> sorted=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);LinkedHashMap<String,String> out=new LinkedHashMap<>();
        if(hidden){for(String key:prefs(c).getAll().keySet()){
            JSONObject o=new JSONObject(prefs(c).getString(key,"{}"));Iterator<String> it=o.keys();if(it.hasNext()){ContentValues v=values(o.getJSONObject(it.next()));if(!privateRow(c,v))sorted.put(v.getAsString("title")+"\u0000"+key,key);}
        }}else for(ContentValues v:current(c)){String key=key(v);if(!key.isEmpty()&&!privateRow(c,v))sorted.put(v.getAsString("title")+"\u0000"+key,key);}
        for(Map.Entry<String,String> e:sorted.entrySet())out.put(e.getValue(),e.getKey().split("\u0000",2)[0]);return out;
    }
    static void publishAction(Context c,long channel)throws Exception{
        ContentValues v=new ContentValues();v.put("channel_id",channel);v.put("internal_provider_id",ACTION);v.put("title","Gestisci home");
        v.put("short_description","Nascondi o ripristina titoli della home Fuoconero 0.4.3");v.put("type",TvContract.PreviewPrograms.TYPE_CHANNEL);v.put("weight",1500);
        v.put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9);v.put("poster_art_uri",WideArtwork.render(c,"manage_home","Gestisci home","Nascondi e ripristina titoli","",-1,"IL CATALOGO RESTA DISPONIBILE"));
        v.put("intent_uri",new Intent(c,HiddenHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).toUri(Intent.URI_INTENT_SCHEME));put(c,v,existing(c,v));
    }
}

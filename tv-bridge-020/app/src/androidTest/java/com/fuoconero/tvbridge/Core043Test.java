package com.fuoconero.tvbridge;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

/** Real Android SQLite/XML parser tests, isolated from the user's databases. */
public class Core043Test {
    private Context ctx;
    @Before public void setup(){
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getDatabasePath(String name){File d=new File(getFilesDir(),"test043-db");d.mkdirs();return new File(d,name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String n,int mode,SQLiteDatabase.CursorFactory f,DatabaseErrorHandler h){
                return SQLiteDatabase.openDatabase(getDatabasePath(n).getAbsolutePath(),f,SQLiteDatabase.CREATE_IF_NECESSARY,h);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String n,int mode,SQLiteDatabase.CursorFactory f){return openOrCreateDatabase(n,mode,f,null);}
            @Override public SharedPreferences getSharedPreferences(String n,int mode){return super.getSharedPreferences("test043_"+n,mode);}
        };
        for(String n:new String[]{"recent043.db","epg043.db","fuoconero_iptv.db","fuoconero_library.db"})SQLiteDatabase.deleteDatabase(ctx.getDatabasePath(n));
        for(String n:new String[]{"tvbridge","epg","veezie_playback","tvbridge_next"})ctx.getSharedPreferences(n,0).edit().clear().commit();
    }
    @Test public void xmltvTimezoneAndInvalidDate()throws Exception{
        assertEquals(EpgStore.parseTime("20261014120000 +0200"),EpgStore.parseTime("20261014100000 +0000"));
        assertEquals(EpgStore.parseTime("20261014100000"),EpgStore.parseTime("20261014100000 +0000"));
        try{EpgStore.parseTime("20260230100000 +0000");fail();}catch(java.text.ParseException expected){}
    }
    private String time(long millis){return DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis));}
    @Test public void epgAssociationNowNextRollbackAndAmbiguity()throws Exception{
        long now=System.currentTimeMillis();
        String xml="<tv><channel id='rai1'><display-name>RAI 1</display-name></channel>"+
            "<programme channel='rai1' start='"+time(now-600000)+"' stop='"+time(now+600000)+"'><title>EPG TEST — NON PALINSESTO REALE</title></programme>"+
            "<programme channel='rai1' start='"+time(now+600000)+"' stop='"+time(now+1200000)+"'><title>TEST SUCCESSIVO</title></programme></tv>";
        try(IptvStore store=new IptvStore(ctx);EpgStore epg=new EpgStore(ctx)){
            android.content.ContentValues v=new android.content.ContentValues();
            v.put("ident","test");v.put("title","RAI 1 HD");v.put("grp","Test");v.put("url","https://example.invalid/live");v.put("category","TV Italia");
            v.put("kind","TV");v.put("adult",0);v.put("seen",now);v.put("first_seen",now);
            long id=store.getWritableDatabase().insertOrThrow("entries",null,v);IptvStore.Media m=store.find(id);
            assertEquals(2,epg.importGuide(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            EpgStore.NowNext guide=epg.nowNext(m);assertNotNull(guide.now);assertEquals("TEST SUCCESSIVO",guide.next.title);
            assertTrue(guide.percent()>45&&guide.percent()<55);
            try{epg.importGuide(new ByteArrayInputStream("<tv><programme".getBytes()));fail();}catch(Exception expected){}
            assertNotNull(epg.nowNext(m).now);
            epg.getWritableDatabase().execSQL("INSERT INTO channels VALUES('ambiguous','rai 1')");
            assertNull(epg.nowNext(m).now);
            store.getWritableDatabase().execSQL("UPDATE entries SET tvg_id='rai1' WHERE id=?",new Object[]{id});
            assertNotNull(epg.nowNext(m).now);
            m.adult=true;assertNull(epg.nowNext(m).now);
        }
    }
    @Test public void historyDedupeOrderingRemovalAndAdults()throws Exception{
        try(RecentStore store=new RecentStore(ctx)){
            for(int n=0;n<25;n++){RecentStore.Entry e=new RecentStore.Entry();e.key="test_"+n;e.title="TEST "+n;e.intent="intent:#Intent;end";e.at=1000+n;store.save(e);}
            assertEquals(20,store.list(20).size());assertEquals("test_24",store.list(20).get(0).key);
            RecentStore.Entry e=store.list(20).get(0);store.save(e);assertEquals(25,store.list(100).size());
            store.remove(e.key);store.save(e);assertEquals(24,store.list(100).size());
            e.at=System.currentTimeMillis()+1000;store.save(e);assertEquals(25,store.list(100).size());
            IptvStore.Media adult=new IptvStore.Media();adult.id=900;adult.adult=true;adult.title="Private";
            RecentStore.iptv(ctx,adult,System.currentTimeMillis(),100,1000);assertEquals(25,store.list(100).size());
        }
    }
    @Test public void traktCompletionOverridesOlderObservedProgress()throws Exception{
        Class<?> type=Class.forName("com.fuoconero.tvbridge.TvSync$Item");
        Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object item=ctor.newInstance();
        for(String key:new String[]{"id","title","season","episodeNumber"}){
            Field f=type.getDeclaredField(key);f.setAccessible(true);f.set(item,key.equals("id")?"show_test":key.equals("title")?"TEST":"1");
        }
        Field episode=type.getDeclaredField("episode");episode.setAccessible(true);episode.setBoolean(item,true);
        Field watched=type.getDeclaredField("watchedAt");watched.setAccessible(true);watched.setLong(item,System.currentTimeMillis());
        JSONObject observation=new JSONObject().put("title","TEST").put("season","1").put("episode","1")
            .put("position",5000).put("duration",600000).put("observed_at",System.currentTimeMillis()-1000);
        ctx.getSharedPreferences("veezie_playback",0).edit().putString("show_test",observation.toString()).commit();
        Method apply=TvSync.class.getDeclaredMethod("applyVeeziePlayback",Context.class,type);apply.setAccessible(true);apply.invoke(null,ctx,item);
        Field progress=type.getDeclaredField("progress");progress.setAccessible(true);assertEquals(-1,progress.getDouble(item),0);
        watched.setLong(item,0);apply.invoke(null,ctx,item);assertTrue(progress.getDouble(item)>0);
    }
    @Test public void verifiedIdentityMergesSourcesWithoutMergingRemakes(){
        try(RecentStore store=new RecentStore(ctx)){
            RecentStore.Entry a=new RecentStore.Entry();a.key="movie_trakt";a.title="TEST FILM";a.intent="intent:#Intent;end";a.at=1000;a.identity="movie:tmdb:1";store.save(a);
            RecentStore.Entry b=new RecentStore.Entry();b.key="iptv_1";b.title="TEST FILM";b.intent="intent:#Intent;end";b.at=2000;b.identity="movie:tmdb:1";store.save(b);
            assertEquals(1,store.list(20).size());assertEquals("iptv_1",store.list(20).get(0).key);
            a.identity="movie:tmdb:2";a.at=3000;store.save(a);assertEquals(2,store.list(20).size());
            store.remove("iptv_1");b.key="another_source";store.save(b);assertEquals(1,store.list(20).size());
        }
    }
    @Test public void m3uIdentifiersAndFavouriteSurviveRefresh()throws Exception{
        String m3u="#EXTM3U\n#EXTINF:-1 tvg-id=\"rai1\" group-title=\"Italia\",RAI 1 HD\nhttps://example.invalid/live/u/p/1.ts\n";
        try(IptvStore store=new IptvStore(ctx)){
            store.importPlaylist(ctx,new ByteArrayInputStream(m3u.getBytes(StandardCharsets.UTF_8)),null);
            long id=store.list("TV Italia","",0,1).get(0).id;assertEquals("rai1",store.epgId(id));store.toggleFavorite(id);
            store.importPlaylist(ctx,new ByteArrayInputStream(m3u.getBytes(StandardCharsets.UTF_8)),null);
            assertEquals(id,store.list("TV Italia","",0,1).get(0).id);assertTrue(store.find(id).fav);
        }
    }
}


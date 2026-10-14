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
    @Test public void hourlyRefreshReplacesLegacySchedules(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.job.JobScheduler s=(android.app.job.JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        s.schedule(new android.app.job.JobInfo.Builder(4304,new ComponentName(c,HomeRefreshJob.class)).setPeriodic(15L*60*1000).build());
        s.schedule(new android.app.job.JobInfo.Builder(4080,new ComponentName(c,IptvRefreshJob.class)).setPeriodic(12L*3600*1000).build());
        HomeRefreshJob.schedule(c);
        android.app.job.JobInfo job=s.getPendingJob(4304);
        assertEquals(3600000L,job.getIntervalMillis());
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY,job.getNetworkType());
        assertNull(s.getPendingJob(4080));assertNull(s.getPendingJob(4303));
        IptvRefreshJob.schedule(c);EpgRefreshJob.schedule(c);
        assertEquals(3600000L,s.getPendingJob(4304).getIntervalMillis());
        assertNull(s.getPendingJob(4080));assertNull(s.getPendingJob(4303));
    }
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
        for(String n:new String[]{"tvbridge","epg","veezie_playback","tvbridge_next","iptv_settings"})ctx.getSharedPreferences(n,0).edit().clear().commit();
    }
    @Test public void liveVariantsAcrossCategoriesKeepHdAndVod()throws Exception{
        try(IptvStore store=new IptvStore(ctx)){
            String[] titles={"Cinema Uno SD","Cinema Uno H264","Cinema Uno HD","Cinema Uno FHD","Cinema Uno HD +1","Local SD","A Film SD","A Film HD","Cinema Uno HD H265","Only HEVC HD"};
            for(int n=0;n<titles.length;n++){
                ContentValues v=new ContentValues();v.put("ident","variant"+n);v.put("title",titles[n]);v.put("grp","Film");v.put("url","https://example.invalid/"+n);v.put("category","Film");v.put("kind",n<6||n>=8?"TV":"Video");v.put("adult",0);v.put("seen",1);v.put("first_seen",1);
                store.getWritableDatabase().insertOrThrow("entries",null,v);
            }
            java.util.ArrayList<IptvStore.Media> rows=store.list("Film","",0,75);
            assertEquals(5,rows.size());
            assertEquals("Cinema Uno HD",rows.get(0).title);
            assertEquals("Cinema Uno HD +1",rows.get(1).title);
            assertEquals("Local SD",rows.get(2).title);
            assertEquals(2,store.searchMedia("Cinema Uno",0,50).size());
            assertEquals(10,store.count());
        }
    }
    @Test public void unifiedVeezieUsesWorking037Navigator(){
        Intent original=new Intent().setData(android.net.Uri.parse("fuoconero-tv://item/show_123"))
            .putExtra("title","Tracker").putExtra("episode",true).putExtra("season","04").putExtra("episode_number","02");
        Intent delegated=OpenContentActivity.bridge037Intent(original);
        assertEquals("com.fuoconero.tvbridge.test037",delegated.getComponent().getPackageName());
        assertEquals("com.fuoconero.tvbridge.OpenContentActivity",delegated.getComponent().getClassName());
        assertEquals(original.getData(),delegated.getData());assertEquals("Tracker",delegated.getStringExtra("title"));
        assertTrue(delegated.getBooleanExtra("episode",false));
        assertEquals("04",delegated.getStringExtra("season"));assertEquals("02",delegated.getStringExtra("episode_number"));
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
    @Test public void onlineGroupsPreserveExistingIdsAndExcludeUnselected()throws Exception{
        String header="#EXTM3U\n",item="#EXTINF:-1 group-title=\"TV Italia\",RAI 1 HD\nhttps://example.invalid/live/u/p/1.ts\n";
        try(IptvStore store=new IptvStore(ctx)){
            store.importPlaylist(ctx,new ByteArrayInputStream((header+item).getBytes(StandardCharsets.UTF_8)),null);
            long id=store.list("TV Italia","",0,1).get(0).id;store.toggleFavorite(id);
            ctx.getSharedPreferences(IptvStore.PREFS,0).edit().putString("source_group_categories","{\"Top Italia\":\"TV Italia\"}").commit();
            String online=header+item.replace("TV Italia","Top Italia")+
                "#EXTINF:-1 group-title=\"Foreign\",OTHER HD\nhttps://example.invalid/live/u/p/2.ts\n"+
                "#EXTINF:-1 group-title=\"Top Italia\",RAI 2 HD\nhttps://example.invalid/live/u/p/3.ts\n";
            store.importPlaylist(ctx,new ByteArrayInputStream(online.getBytes(StandardCharsets.UTF_8)),null);
            assertEquals(2,store.count());assertEquals(id,store.list("TV Italia","",0,1).get(0).id);assertTrue(store.find(id).fav);
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


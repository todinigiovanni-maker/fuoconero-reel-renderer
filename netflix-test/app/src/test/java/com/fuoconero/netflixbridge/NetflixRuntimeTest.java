package com.fuoconero.netflixbridge;
import android.content.*;
import android.database.MatrixCursor;
import android.net.Uri;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class NetflixRuntimeTest {
    @Test public void ownershipIsExact(){assertTrue(NetflixSource.isNetflix("com.netflix.ninja"));assertFalse(NetflixSource.isNetflix("com.netflix.ninja.fake"));assertFalse(NetflixSource.isNetflix("com.netflix.partner.ncm"));assertFalse(NetflixSource.isNetflix(null));}
    @Test public void filtersOwnerHiddenAndDuplicateRows(){
        MatrixCursor c=new MatrixCursor(new String[]{"_id","package_name","title","browsable","last_engagement_time_utc_millis"});
        c.addRow(new Object[]{1,"other.app","Netflix title",1,500});c.addRow(new Object[]{2,"com.netflix.ninja","Hidden",0,400});
        c.addRow(new Object[]{3,"com.netflix.ninja","One",1,10});c.addRow(new Object[]{3,"com.netflix.ninja","One",1,10});c.addRow(new Object[]{4,"com.netflix.ninja","Two",1,20});
        NetflixSource.Result r=new NetflixSource.Result();NetflixSource.collect(c,r);assertEquals(2,r.items.size());assertEquals("Two",r.items.get(0).title);
    }
    @Test public void validPublishedLinkStaysNetflix(){Intent i=NetflixLinks.safe("https://www.netflix.com/watch/123","com.netflix.ninja");assertNotNull(i);assertEquals("com.netflix.ninja",i.getPackage());}
    @Test public void rejectsUnsafeDestinations(){
        for(String u:new String[]{"https://netflix.com.evil.test/watch/1","file:///data/a","javascript:alert(1)","https://other.test/watch/1","http://www.netflix.com/watch/1"})assertNull(NetflixLinks.safe(u,"com.netflix.ninja"));
        assertNull(NetflixLinks.safe("https://www.netflix.com/watch/1","other.app"));
        Intent bad=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.netflix.com/watch/1")).setComponent(new ComponentName("other.app","other.app.Main"));assertNull(NetflixLinks.safe(bad.toUri(Intent.URI_INTENT_SCHEME),"com.netflix.ninja"));
    }
    @Test public void stripsPermissionGrants(){Intent original=new Intent(Intent.ACTION_VIEW,Uri.parse("nflx://www.netflix.com/watch/1")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);Intent safe=NetflixLinks.safe(original.toUri(Intent.URI_INTENT_SCHEME),"com.netflix.ninja");assertNotNull(safe);assertEquals(0,safe.getFlags()&3);}
    @Test public void artworkProviderCannotEscapeDirectory()throws Exception{
        java.io.File d=org.robolectric.RuntimeEnvironment.getApplication().getCacheDir();
        for(String path:new String[]{"../secret","%2e%2e%2fsecret","item_1.png/secret","other.xml"})try{ArtworkProvider.resolve(d,Uri.parse("content://test/"+path));fail();}catch(java.io.IOException expected){}
    }
    @Test public void imageHostsAreRestricted(){assertTrue(Artwork.allowedHost("occ-0.nflxso.net"));assertFalse(Artwork.allowedHost("nflxso.net.evil.test"));assertFalse(Artwork.allowedHost("127.0.0.1"));}
    @Test public void rendersActual16by9()throws Exception{
        Context c=org.robolectric.RuntimeEnvironment.getApplication();String u=Artwork.render(c,"app","Netflix","Test","",0,0);
        try(java.io.InputStream in=c.getContentResolver().openInputStream(Uri.parse(u))){android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeStream(in);assertEquals(960,b.getWidth());assertEquals(540,b.getHeight());b.recycle();}
    }
    @Test public void dailyJobReplacesOldIntervalAndSurvivesReboot(){
        Context c=org.robolectric.RuntimeEnvironment.getApplication();
        android.app.job.JobScheduler scheduler=c.getSystemService(android.app.job.JobScheduler.class);
        scheduler.cancel(RefreshJob.JOB_ID);
        scheduler.schedule(new android.app.job.JobInfo.Builder(RefreshJob.JOB_ID,new ComponentName(c,RefreshJob.class)).setPeriodic(3600000).build());
        RefreshJob.schedule(c);
        android.app.job.JobInfo job=scheduler.getPendingJob(RefreshJob.JOB_ID);
        assertNotNull(job);assertEquals(CatalogData.DAY,job.getIntervalMillis());assertTrue(job.isPersisted());
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY,job.getNetworkType());
        RefreshJob.schedule(c);assertNotNull(scheduler.getPendingJob(RefreshJob.JOB_ID));
    }
    @Test public void catalogArtworkNamesStayWithinProvider()throws Exception{
        java.io.File dir=org.robolectric.RuntimeEnvironment.getApplication().getCacheDir();
        assertEquals("new_123.png",ArtworkProvider.resolve(dir,Uri.parse("content://test/new_123.png")).getName());
        assertEquals("news.png",ArtworkProvider.resolve(dir,Uri.parse("content://test/news.png")).getName());
    }
    @Test public void rebootAndUpgradeRescheduleOnlyIndependentJob(){
        Context c=org.robolectric.RuntimeEnvironment.getApplication();
        android.app.job.JobScheduler scheduler=c.getSystemService(android.app.job.JobScheduler.class);
        for(String action:new String[]{Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED}){
            scheduler.cancel(RefreshJob.JOB_ID);new BootReceiver().onReceive(c,new Intent(action));
            assertNotNull(scheduler.getPendingJob(RefreshJob.JOB_ID));
        }
    }
    @Test public void catalogSurvivesProcessCacheAndOfflineExpiry()throws Exception {
        Context c=org.robolectric.RuntimeEnvironment.getApplication();long now=System.currentTimeMillis();
        CatalogData.Item item=new CatalogData.Item();item.id=81234567;item.available=now;
        item.title="Titolo";item.subtitle="Film";item.art="";item.collection=CatalogData.FILM;
        CatalogSource.save(c,java.util.Collections.singletonList(item));
        assertEquals(1,CatalogSource.cached(c,now).size());
        assertTrue(CatalogSource.cached(c,now+31*CatalogData.DAY).isEmpty());
    }
    @Test public void freshCatalogUsesCacheWithoutNetwork()throws Exception {
        Context c=org.robolectric.RuntimeEnvironment.getApplication();long now=System.currentTimeMillis();
        CatalogSource.save(c,java.util.Collections.emptyList());
        c.getSharedPreferences(Publisher.PREF,0).edit().putLong("catalog_checked",now).commit();
        CatalogSource.Result r=CatalogSource.read(c,false);assertEquals(now,r.checked);assertFalse(r.stale);assertTrue(r.items.isEmpty());
    }
    @Test public void failedCatalogRetryKeepsSavedEntries()throws Exception {
        Context c=org.robolectric.RuntimeEnvironment.getApplication();long now=System.currentTimeMillis();
        CatalogData.Item i=new CatalogData.Item();i.id=81234567;i.available=now;i.title="Titolo";i.subtitle="";i.art="";i.collection=CatalogData.SERIES;
        CatalogSource.save(c,java.util.Collections.singletonList(i));
        c.getSharedPreferences(Publisher.PREF,0).edit().putLong("catalog_checked",now-2*CatalogData.DAY).putLong("catalog_attempt",now).commit();
        CatalogSource.Result r=CatalogSource.read(c,false);assertTrue(r.stale);assertEquals(1,r.items.size());
    }
    @Test public void corruptCacheDoesNotInventTitles()throws Exception {
        Context c=org.robolectric.RuntimeEnvironment.getApplication();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(CatalogSource.file(c).getBaseFile())){out.write("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertTrue(CatalogSource.cached(c,System.currentTimeMillis()).isEmpty());
    }
}

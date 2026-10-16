package com.fuoconero.netflixbridge;
import android.content.*;
import android.database.MatrixCursor;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class NetflixTest {
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
        java.io.File d=InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        for(String path:new String[]{"../secret","%2e%2e%2fsecret","item_1.png/secret","other.xml"})try{ArtworkProvider.resolve(d,Uri.parse("content://test/"+path));fail();}catch(java.io.IOException expected){}
    }
    @Test public void imageHostsAreRestricted(){assertTrue(Artwork.allowedHost("occ-0.nflxso.net"));assertFalse(Artwork.allowedHost("nflxso.net.evil.test"));assertFalse(Artwork.allowedHost("127.0.0.1"));}
    @Test public void rendersActual16by9()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();String u=Artwork.render(c,"app","Netflix","Test","",0,0);
        try(java.io.InputStream in=c.getContentResolver().openInputStream(Uri.parse(u))){android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeStream(in);assertEquals(960,b.getWidth());assertEquals(540,b.getHeight());b.recycle();}
    }
    @Test public void dailyJobReplacesOldIntervalAndSurvivesReboot(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
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
        java.io.File dir=InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        assertEquals("new_123.png",ArtworkProvider.resolve(dir,Uri.parse("content://test/new_123.png")).getName());
        assertEquals("news.png",ArtworkProvider.resolve(dir,Uri.parse("content://test/news.png")).getName());
    }
    @Test public void rebootAndUpgradeRescheduleOnlyIndependentJob(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.job.JobScheduler scheduler=c.getSystemService(android.app.job.JobScheduler.class);
        for(String action:new String[]{Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED}){
            scheduler.cancel(RefreshJob.JOB_ID);new BootReceiver().onReceive(c,new Intent(action));
            assertNotNull(scheduler.getPendingJob(RefreshJob.JOB_ID));
        }
    }
}

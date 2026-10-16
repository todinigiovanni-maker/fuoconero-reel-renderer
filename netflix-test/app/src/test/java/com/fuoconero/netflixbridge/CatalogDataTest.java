package com.fuoconero.netflixbridge;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class CatalogDataTest {
    final long now=1792188000000L;
    JSONObject row(long id,long when)throws Exception{return new JSONObject().put("country","IT").put("collection",CatalogData.FILM)
        .put("videoID",id).put("startTime",when).put("title1","Titolo "+id).put("title2","Film")
        .put("image","https://dnm.nflximg.net/image.jpg");}
    JSONObject page(JSONObject... rows)throws Exception{JSONArray a=new JSONArray();for(JSONObject row:rows)a.put(row);return new JSONObject().put("data",a);}
    @Test public void keepsItalianFilmWithOriginalNetflixId()throws Exception{
        CatalogData.Item i=CatalogData.parse(page(row(81234567,now)),CatalogData.FILM,now).get(0);
        assertEquals("new:81234567",i.key());assertEquals("https://www.netflix.com/watch/81234567",i.link());assertEquals("Titolo 81234567",i.title);
    }
    @Test public void includesSeriesSeparately()throws Exception{
        JSONObject r=row(1,now).put("collection",CatalogData.SERIES);
        assertEquals(CatalogData.SERIES,CatalogData.parse(page(r),CatalogData.SERIES,now).get(0).collection);
    }
    @Test(expected=JSONException.class) public void rejectsSilentUsTerritoryFallback()throws Exception{CatalogData.parse(page(row(1,now).put("country","US")),CatalogData.FILM,now);}
    @Test(expected=JSONException.class) public void rejectsGamesAndIncorrectCollection()throws Exception{CatalogData.parse(page(row(1,now).put("collection",81500273)),CatalogData.FILM,now);}
    @Test public void excludesFutureAndExpiredReleaseDates()throws Exception{
        assertEquals(1,CatalogData.parse(page(row(1,now+1),row(2,now-31*CatalogData.DAY),row(3,now-30*CatalogData.DAY)),CatalogData.FILM,now).size());
    }
    @Test public void rejectsInvalidIdsAndEmptyTitles()throws Exception{
        assertTrue(CatalogData.parse(page(row(-1,now),row(0,now),row(10000000000L,now),row(1,now).put("title1","  ")),CatalogData.FILM,now).isEmpty());
    }
    @Test public void rejectsArtworkFromForeignHostsAndPlainHttp(){
        for(String s:new String[]{"http://dnm.nflximg.net/a","https://nflximg.net.evil.test/a","https://127.0.0.1/a","https://nflximg.net@evil.test/a","https://dnm.nflximg.net:1234/a","file:///a"})assertFalse(s,CatalogData.allowedArt(s));
        assertTrue(CatalogData.allowedArt("https://dnm.nflximg.net/a"));
    }
    @Test public void unsafeImageStillKeepsValidTitleWithTextFallback()throws Exception{
        assertEquals("",CatalogData.parse(page(row(1,now).put("image","https://evil.test/a")),CatalogData.FILM,now).get(0).art);
    }
    @Test public void deduplicatesSortsAndLimitsToThirty()throws Exception{
        ArrayList<CatalogData.Item> all=new ArrayList<>();
        for(int i=1;i<=40;i++)all.addAll(CatalogData.parse(page(row(i,now-i*1000)),CatalogData.FILM,now));
        all.addAll(CatalogData.parse(page(row(40,now)),CatalogData.FILM,now));
        List<CatalogData.Item> recent=CatalogData.recent(all,now);assertEquals(30,recent.size());assertEquals(40,recent.get(0).id);
        assertEquals(30,new HashSet<Long>(){{for(CatalogData.Item i:recent)add(i.id);}}.size());
    }
    @Test public void cachedEntriesExpireWithoutNetwork()throws Exception{
        List<CatalogData.Item> items=CatalogData.parse(page(row(1,now)),CatalogData.FILM,now);
        assertTrue(CatalogData.recent(items,now+31*CatalogData.DAY).isEmpty());
    }
    @Test public void cacheRoundTripPreservesSourceIdentity()throws Exception{
        CatalogData.Item i=CatalogData.parse(page(row(42,now)),CatalogData.FILM,now).get(0);
        CatalogData.Item decoded=CatalogData.parse(page(CatalogData.encode(i)),CatalogData.FILM,now).get(0);
        assertEquals(i.key(),decoded.key());assertEquals(i.art,decoded.art);assertEquals(i.available,decoded.available);
    }
    @Test(expected=JSONException.class) public void malformedSourceCannotReplaceKnownGoodCache()throws Exception{CatalogData.parse(new JSONObject().put("items",new JSONArray()),CatalogData.FILM,now);}
}

package com.fuoconero.netflixbridge;

import org.json.*;
import java.net.URI;
import java.util.*;

/** Public Netflix release schedule, not private account data or a complete licensed catalog. */
public final class CatalogData {
    public static final long DAY = 86_400_000L;
    public static final int FILM = 2694103, SERIES = 2170013;
    public static final class Item {
        public long id, available;
        public int collection;
        public String title, subtitle, art;
        public String key() { return "new:" + id; }
        // Same Netflix video ID and URL format used by the public release schedule itself.
        public String link() { return "https://www.netflix.com/watch/" + id; }
    }
    public static boolean allowedArt(String value) {
        try {
            URI u = new URI(value); String h = u.getHost();
            if (!"https".equals(u.getScheme()) || h == null || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return false;
            for (String root : new String[]{"nflximg.net", "nflximg.com", "nflxso.net", "nflxext.com"})
                if (h.equals(root) || h.endsWith("." + root)) return true;
        } catch (Exception ignored) { }
        return false;
    }
    public static List<Item> parse(JSONObject page, int expectedCollection, long now) throws JSONException {
        if (expectedCollection != FILM && expectedCollection != SERIES) throw new JSONException("Unknown collection");
        JSONArray rows = page.getJSONArray("data");
        if (rows.length() > 100) throw new JSONException("Oversized page");
        ArrayList<Item> out = new ArrayList<>();
        for (int n = 0; n < rows.length(); n++) {
            JSONObject row = rows.getJSONObject(n);
            // Fail the entire response if the server silently changes territory or type.
            if (!"IT".equals(row.getString("country")) || row.getInt("collection") != expectedCollection)
                throw new JSONException("Unexpected region or collection");
            long id = row.getLong("videoID"), date = row.getLong("startTime");
            String title = row.getString("title1").trim();
            if (id <= 0 || id > 9999999999L || title.isEmpty() || title.length() > 300 || date <= 0) continue;
            if (date > now || date < now - 30 * DAY) continue;
            Item i = new Item(); i.id=id; i.available=date; i.collection=expectedCollection; i.title=title;
            i.subtitle=row.optString("title2", ""); if(i.subtitle.length()>300)i.subtitle="";
            String art=row.optString("image", ""); i.art=allowedArt(art)?art:""; out.add(i);
        }
        return out;
    }
    public static List<Item> recent(Collection<Item> items, long now) {
        HashMap<Long,Item> unique=new HashMap<>();
        for(Item i:items) if(i.available<=now && i.available>=now-30*DAY) {
            Item old=unique.get(i.id); if(old==null||old.available<i.available)unique.put(i.id,i);
        }
        ArrayList<Item> out=new ArrayList<>(unique.values());
        out.sort((a,b)->{int order=Long.compare(b.available,a.available);return order!=0?order:Long.compare(a.id,b.id);});
        if(out.size()>30)out.subList(30,out.size()).clear(); return out;
    }
    public static JSONObject encode(Item i)throws JSONException {
        return new JSONObject().put("country","IT").put("collection",i.collection).put("videoID",i.id)
            .put("startTime",i.available).put("title1",i.title).put("title2",i.subtitle).put("image",i.art);
    }
}

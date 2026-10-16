package com.fuoconero.netflixbridge;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
public final class NetflixLinks {
    static Intent safe(String raw,String owner) {
        if(!NetflixSource.isNetflix(owner)||raw==null||raw.isEmpty())return null;
        try {
            Intent i=Intent.parseUri(raw,Intent.URI_INTENT_SCHEME);
            if(i.getSelector()!=null)return null;
            if(i.getPackage()!=null&&!owner.equals(i.getPackage()))return null;
            if(i.getComponent()!=null&&!owner.equals(i.getComponent().getPackageName()))return null;
            if(i.getAction()!=null&&!Intent.ACTION_VIEW.equals(i.getAction()))return null;
            Uri u=i.getData();
            if(u==null && i.getComponent()==null)return null;
            if(u!=null) {
                String s=u.getScheme(),h=u.getHost();
                boolean netflixHost=h!=null&&(h.equals("netflix.com")||h.endsWith(".netflix.com"));
                if(!netflixHost||!("https".equals(s)||"nflx".equals(s)))return null;
            }
            i.setPackage(owner);i.setAction(Intent.ACTION_VIEW);i.setClipData(null);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return i;
        }catch(Exception e){return null;}
    }
    static boolean resolves(Context c,Intent i,String owner) {
        if(i==null)return false;
        ResolveInfo r=c.getPackageManager().resolveActivity(i,PackageManager.MATCH_DEFAULT_ONLY);
        return r!=null&&r.activityInfo!=null&&r.activityInfo.exported&&r.activityInfo.enabled&&owner.equals(r.activityInfo.packageName);
    }
    static Intent launcher(Context c) {
        for(String p:new String[]{"com.netflix.ninja","com.netflix.mediaclient"}) {
            Intent i=c.getPackageManager().getLeanbackLaunchIntentForPackage(p);
            if(i==null)i=c.getPackageManager().getLaunchIntentForPackage(p);
            if(i!=null)return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }
}

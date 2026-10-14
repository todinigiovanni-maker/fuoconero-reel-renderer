package com.fuoconero.tvbridge;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Couch-first view of the same public programmes published to Projectivy. */
public class BrowseActivity extends Activity {
    private LinearLayout root,rows;
    private ScrollView scroll;
    private TextView status;
    private final ExecutorService data=Executors.newSingleThreadExecutor();
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final ExecutorService images=Executors.newFixedThreadPool(3);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean resumed=false,busy=false;
    private int generation=0;
    private final android.util.LruCache<String,Bitmap> cache=new android.util.LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private static class Card {String key,title,description,intent,image;long channel;}
    private static class Section {String title;long id;ArrayList<Card> cards=new ArrayList<>();}
    private final Runnable ticker=new Runnable(){public void run(){if(resumed){load();handler.postDelayed(this,60000);}}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(18),dp(20),dp(30));
        root.setBackgroundColor(0xff101115);scroll.addView(root);setContentView(scroll);
        heading(root,"FUOCONERO TV · 0.4.3 SAFE TEST",28,0xffff8b43);
        LinearLayout toolbar=new LinearLayout(this);root.addView(toolbar);
        action(toolbar,"Cerca / IPTV",()->startActivity(new Intent(this,IptvActivity.class)));
        action(toolbar,"Guida TV",()->startActivity(new Intent(this,EpgActivity.class)));
        action(toolbar,"Impostazioni",()->startActivity(new Intent(this,MainActivity.class)));
        action(toolbar,"Aggiorna",this::refresh);
        status=heading(root,"Carico le schede...",17,0xffc9cbd3);
        rows=new LinearLayout(this);rows.setOrientation(1);root.addView(rows);
        refresh();HomeRefreshJob.schedule(this);HomeRefreshJob.request(this);
    }
    @Override public void onResume(){super.onResume();resumed=true;load();handler.postDelayed(ticker,60000);}
    @Override public void onPause(){resumed=false;handler.removeCallbacks(ticker);super.onPause();}
    private void refresh(){
        if(busy)return;busy=true;status.setText("Aggiornamento in corso; il catalogo salvato resta utilizzabile.");
        data.execute(()->{
            try{
                try(IptvLibrary lib=new IptvLibrary(this)){
                    for(IptvLibrary.Recent e:lib.recentlyOpened(100))
                        RecentStore.iptv(this,e.media,e.openedAt,e.positionMs,e.durationMs);
                }
                boolean hasTrakt=!getSharedPreferences("trakt",MODE_PRIVATE).getString("access_token","").isEmpty();
                if(hasTrakt && System.currentTimeMillis()-getSharedPreferences("tvbridge",MODE_PRIVATE).getLong("last_sync_started",0)>240000)
                    try{TvSync.perform(this,m->{});}catch(Exception ignored){}
                EpgRefreshJob.configureFromPlaylist(this);
                IptvPublisher.publish(this);RecentStore.publish(this);
                SyncJob.schedule(this);IptvRefreshJob.schedule(this);EpgRefreshJob.schedule(this);
            }catch(Exception e){android.util.Log.w("Fuoconero043","Aggiornamento differito; dati locali conservati");}
            runOnUiThread(()->{busy=false;status.setText("OK apre · tieni premuto OK per rimuovere dai recenti");load();
                SharedPreferences p=getSharedPreferences("auto043",MODE_PRIVATE);
                if(!p.getBoolean("visibility_requested",false)){TvSync.requestVisibility(this);p.edit().putBoolean("visibility_requested",true).apply();}
            });
        });
    }
    private void load(){
        final int request=++generation;
        reader.execute(()->{
            ArrayList<Section> sections=new ArrayList<>();HashMap<Long,Section> channels=new HashMap<>();
            try(Cursor c=getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{"_id","display_name","package_name"},null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    if(!getPackageName().equals(c.getString(2)))continue;
                    String title=c.getString(1);if(title==null||title.contains("Adulti"))continue;
                    Section s=new Section();s.id=c.getLong(0);s.title=title;channels.put(s.id,s);
                }
            }catch(Exception ignored){}
            try(Cursor c=getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{"channel_id","internal_provider_id","title","short_description","intent_uri","poster_art_uri"},null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    Section s=channels.get(c.getLong(0));if(s==null)continue;
                    Card card=new Card();card.channel=s.id;card.key=c.getString(1);card.title=c.getString(2);
                    card.description=c.getString(3);card.intent=c.getString(4);card.image=c.getString(5);s.cards.add(card);
                }
            }catch(Exception ignored){}
            // Provider reads are scoped and cannot use SQL sorting/selections.
            for(Section s:channels.values())if(!s.cards.isEmpty())sections.add(s);
            long recent=getSharedPreferences("tvbridge",MODE_PRIVATE).getLong("channel_id",-1);
            sections.sort((a,b)->a.id==b.id?0:a.id==recent?-1:b.id==recent?1:a.title.compareTo(b.title));
            // Explicit recent order comes from the local history, not provider row IDs.
            HashMap<String,Integer> rank=new HashMap<>();rank.put(RefreshActionCard.KEY,-1);
            try(RecentStore rs=new RecentStore(this)){int n=0;for(RecentStore.Entry e:rs.list(20))rank.put("fuoconero:"+e.key,n++);}
            Section recentSection=channels.get(recent);
            if(recentSection!=null)recentSection.cards.sort(Comparator.comparingInt(c->rank.getOrDefault(c.key,999)));
            runOnUiThread(()->{
                if(request!=generation||isFinishing())return;
                Object focus=getCurrentFocus()==null?null:getCurrentFocus().getTag();
                int y=scroll.getScrollY();rows.removeAllViews();
                if(sections.isEmpty())heading(rows,"Nessun contenuto ancora. Apri Cerca / IPTV o collega Trakt nelle impostazioni.",22,Color.WHITE);
                for(Section section:sections)render(section,focus,recent);
                scroll.post(()->scroll.scrollTo(0,y));
            });
        });
    }
    private void render(Section section,Object focus,long recent){
        heading(rows,section.title.replace("Fuoconero · ",""),22,Color.WHITE);
        HorizontalScrollView slider=new HorizontalScrollView(this);slider.setHorizontalScrollBarEnabled(false);
        slider.setClipChildren(false);LinearLayout line=new LinearLayout(this);line.setPadding(dp(10),dp(12),dp(10),dp(18));line.setClipChildren(false);
        slider.addView(line);rows.addView(slider);
        for(Card card:section.cards){
            LinearLayout tile=new LinearLayout(this);tile.setOrientation(1);tile.setPadding(dp(3),dp(3),dp(3),dp(8));
            tile.setFocusable(true);tile.setClickable(true);tile.setTag(card.key);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(310),-2);lp.rightMargin=dp(16);line.addView(tile,lp);
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);tile.addView(image,new LinearLayout.LayoutParams(-1,dp(171)));
            heading(tile,card.title,21,Color.WHITE);
            TextView detail=heading(tile,card.description==null?"":card.description,15,0xffc9cbd3);detail.setMaxLines(3);
            tile.setContentDescription(card.title+" · "+card.description);
            tile.setBackground(border(false));
            tile.setOnFocusChangeListener((v,selected)->{v.setBackground(border(selected));v.animate().scaleX(selected?1.04f:1).scaleY(selected?1.04f:1).setDuration(100).start();});
            tile.setOnClickListener(v->{try{startActivity(Intent.parseUri(card.intent,Intent.URI_INTENT_SCHEME));}catch(Exception e){status.setText("Contenuto non disponibile");}});
            if(card.channel==recent && !RefreshActionCard.KEY.equals(card.key))tile.setOnLongClickListener(v->{
                new AlertDialog.Builder(this).setTitle(card.title).setMessage("Rimuovere dalla cronologia di questa versione?")
                    .setPositiveButton("Rimuovi",(d,w)->data.execute(()->{
                        try(RecentStore s=new RecentStore(this)){s.remove(card.key.substring("fuoconero:".length()));}
                        try{RecentStore.publish(this);}catch(Exception ignored){}runOnUiThread(this::load);
                    })).setNegativeButton("Annulla",null).show();return true;
            });
            if(card.key!=null&&card.key.equals(focus))tile.requestFocus();
            String uri=card.image;
            if(uri!=null){
                Bitmap cached=cache.get(uri);
                if(cached!=null)image.setImageBitmap(cached);
                else images.execute(()->{
                    Bitmap bitmap=null;
                    try(java.io.InputStream in=getContentResolver().openInputStream(Uri.parse(uri))){
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;
                        bitmap=BitmapFactory.decodeStream(in,null,options);
                    }catch(Exception ignored){}
                    if(bitmap!=null){cache.put(uri,bitmap);final Bitmap result=bitmap;runOnUiThread(()->{if(!isFinishing())image.setImageBitmap(result);});}
                });
            }
        }
    }
    private GradientDrawable border(boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(0xff191b20);d.setCornerRadius(dp(10));d.setStroke(dp(focus?3:1),focus?0xffff8b43:0xff30333a);return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView heading(LinearLayout r,String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(dp(4),dp(6),dp(4),dp(6));r.addView(v);return v;}
    private void action(LinearLayout r,String t,Runnable action){Button b=new Button(this);b.setText(t);b.setAllCaps(false);r.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);data.shutdownNow();images.shutdownNow();super.onDestroy();}
}


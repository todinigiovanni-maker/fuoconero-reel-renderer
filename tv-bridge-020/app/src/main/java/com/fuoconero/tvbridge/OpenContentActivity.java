package com.fuoconero.tvbridge;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.net.Uri;
import android.widget.Toast;

/**
 * Handles one unique Android TV preview intent per media item.
 * Veezie exposes a veezie: scheme but there is no confirmed contract for
 * opening a specific Trakt title/episode. Never construct speculative URLs.
 */
public class OpenContentActivity extends Activity {
    private static final String VEEZIE="st.veezie";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Intent clicked=getIntent();
        String title=clicked==null?"":clicked.getStringExtra("title");
        if(title==null)title="";
        String season=clicked==null?"":clicked.getStringExtra("season");
        String episode=clicked==null?"":clicked.getStringExtra("episode_number");
        boolean tvEpisode=clicked!=null && clicked.getBooleanExtra("episode",false);
        String display=title;
        if(tvEpisode && season!=null && episode!=null
            && season.matches("[0-9]{1,3}") && episode.matches("[0-9]{1,3}")) {
            display+=" S"+season+"E"+episode;
        }
        String mode="ERRORE_APERTURA";
        if(!title.trim().isEmpty()){
            // Clipboard is a usable fallback when Veezie has no search intent.
            // Keep only a title, never tokens or IDs.
            try{
                ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("Fuoconero ricerca",title));
            }catch(Exception ignored){}
            if(searchVeezie(this,title)) {
                mode="RICERCA_DIRETTA_ANDROID";
            }else if(openVeezie(this)) {
                mode="HOME_VEEZIE_TITOLO_COPIATO";
                Toast.makeText(this,"Veezie aperto. Titolo copiato: "+display,Toast.LENGTH_LONG).show();
            }
        }else if(openVeezie(this)) {
            mode="HOME_VEEZIE";
        }
        SharedPreferences p=getSharedPreferences("veezie_launch",MODE_PRIVATE);
        p.edit().putString("last_title",display)
            .putString("last_result",mode)
            .putLong("last_attempt",System.currentTimeMillis())
            .putInt("search_attempts",p.getInt("search_attempts",0)+1)
            .apply();
        finish();
    }

    private static boolean searchVeezie(Context ctx,String title) {
        try {
            Intent query=new Intent(Intent.ACTION_SEARCH);
            query.setPackage(VEEZIE);
            query.putExtra(SearchManager.QUERY,title);
            query.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Only try a supported Android search intent; no undocumented links.
            if(query.resolveActivity(ctx.getPackageManager())==null)return false;
            ctx.startActivity(query);
            return true;
        }catch(Exception ignored){return false;}
    }

    public static String diagnoseVeezie(Context ctx) {
        boolean home=ctx.getPackageManager().getLaunchIntentForPackage(VEEZIE)!=null;
        Intent search=new Intent(Intent.ACTION_SEARCH).setPackage(VEEZIE);
        boolean searchAvailable=search.resolveActivity(ctx.getPackageManager())!=null;
        Intent deep=new Intent(Intent.ACTION_VIEW,Uri.parse("veezie://"));
        deep.setPackage(VEEZIE);
        boolean schemeAvailable=deep.resolveActivity(ctx.getPackageManager())!=null;
        SharedPreferences p=ctx.getSharedPreferences("veezie_launch",MODE_PRIVATE);
        return "Veezie installata: "+(home?"SI":"NO")+
            "\nRicerca esterna Android disponibile: "+(searchAvailable?"SI":"NO")+
            "\nSchema veezie:// dichiarato: "+(schemeAvailable?"SI":"NO")+
            "\nLink preciso al singolo titolo: NON documentato"+
            "\nUltimo clic: "+p.getString("last_title","non ancora testato")+
            "\nRisultato: "+p.getString("last_result","nessun test")+
            "\nSe non esiste ricerca esterna, apro la home e copio il titolo.";
    }

    public static boolean openVeezie(Context ctx) {
        try {
            Intent target=ctx.getPackageManager().getLaunchIntentForPackage(VEEZIE);
            if(target==null)return false;
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(target);
            return true;
        } catch(Exception e){return false;}
    }
}

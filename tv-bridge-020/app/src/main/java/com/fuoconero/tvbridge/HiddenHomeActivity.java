package com.fuoconero.tvbridge;
import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
import java.util.*;
public class HiddenHomeActivity extends Activity {
    private LinearLayout body;private boolean hidden=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);show();}
    private void button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);body.addView(b);b.setOnClickListener(v->action.run());}
    private void show(){
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(48,24,48,24);scroll.addView(body);setContentView(scroll);
        TextView title=new TextView(this);title.setText("Gestisci home · Fuoconero 0.4.3\n"+(hidden?"Titoli nascosti: seleziona per ripristinare.":"Seleziona un titolo da nascondere. Catalogo, ricerca e preferiti restano invariati.\nLa riga Veezie/Trakt 0.3.7 e l’area PIN non sono incluse."));title.setTextSize(21);title.setTextColor(Color.WHITE);body.addView(title);
        button(hidden?"← Titoli visibili":"Mostra titoli nascosti",()->{hidden=!hidden;show();});button("← Torna alla home",this::finish);
        try{
            LinkedHashMap<String,String> titles=HiddenHome.titles(this,hidden);
            if(titles.isEmpty()){TextView empty=new TextView(this);empty.setText("Nessun titolo in questo elenco.");body.addView(empty);}
            for(Map.Entry<String,String> e:titles.entrySet())button((hidden?"Ripristina · ":"Nascondi · ")+e.getValue(),()->{
                new AlertDialog.Builder(this).setTitle(e.getValue()).setMessage(hidden?"Ripristinare questo titolo nella home?":"Nascondere questo titolo dalla home? Resterà disponibile nel catalogo e nella ricerca.")
                    .setNegativeButton("Annulla",null).setPositiveButton(hidden?"Ripristina":"Nascondi",(d,w)->{
                        try{if(hidden)HiddenHome.restore(this,e.getKey());else HiddenHome.hide(this,e.getKey());Toast.makeText(this,hidden?"Titolo ripristinato":"Titolo nascosto",Toast.LENGTH_SHORT).show();show();}
                        catch(Exception error){Toast.makeText(this,"Operazione non completata. Riprova.",Toast.LENGTH_LONG).show();}
                    }).show();
            });
        }catch(Exception e){Toast.makeText(this,"Elenco non disponibile. Riprova.",Toast.LENGTH_LONG).show();}
    }
}

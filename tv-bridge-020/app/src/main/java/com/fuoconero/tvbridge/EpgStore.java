package com.fuoconero.tvbridge;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Atomic, bounded XMLTV cache; external entities are never resolved. */
public final class EpgStore extends SQLiteOpenHelper {
    private final Context app;
    public static final class Programme {public String title;public long start,end;}
    public static final class NowNext {
        public Programme now,next;
        public String describe(){
            if(now==null)return "Guida non disponibile";
            DateFormat f=new SimpleDateFormat("HH:mm",Locale.ITALY);
            String text=now.title+" · "+f.format(new Date(now.start))+"–"+f.format(new Date(now.end));
            if(next!=null)text+=" · Poi: "+next.title+" ("+f.format(new Date(next.start))+")";
            return text;
        }
        public double percent(){return now==null?-1:Math.max(0,Math.min(100,100.0*(System.currentTimeMillis()-now.start)/(now.end-now.start)));}
    }
    public EpgStore(Context c){super(c,"epg043.db",null,1);app=c.getApplicationContext();setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE channels(id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(id,name))");
        d.execSQL("CREATE TABLE programme(channel TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,title TEXT NOT NULL,PRIMARY KEY(channel,start,end))");
        d.execSQL("CREATE INDEX epg_lookup ON programme(channel,start,end)");
        d.execSQL("CREATE INDEX epg_names ON channels(name)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int a,int b){}
    public static long parseTime(String value)throws ParseException{
        if(value==null||!value.matches("[0-9]{14}(?: [+-][0-9]{4})?"))throw new ParseException("Unsupported XMLTV time",0);
        String s=value.length()==14?value+" +0000":value;
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.ROOT);format.setLenient(false);
        ParsePosition pos=new ParsePosition(0);Date date=format.parse(s,pos);
        if(date==null||pos.getIndex()!=s.length())throw new ParseException("Invalid XMLTV time",0);
        return date.getTime();
    }
    public static String normalized(String name){
        return name==null?"":name.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\b(?:hd|fhd|uhd|4k|hevc|h265|1080p|720p)\\b"," ")
            .replaceAll("\\s+"," ").trim();
    }
    public int importGuide(InputStream raw)throws Exception{
        BufferedInputStream buffered=new BufferedInputStream(raw);buffered.mark(2);
        int a=buffered.read(),b=buffered.read();buffered.reset();
        InputStream input=a==31&&b==139?new GZIPInputStream(buffered):buffered;
        InputStream bounded=new FilterInputStream(input){
            long bytes=0;
            @Override public int read(byte[] b,int o,int n)throws IOException{
                int read=super.read(b,o,n);if(read>0&&(bytes+=read)>250L*1024*1024)throw new IOException("Guida troppo grande");return read;
            }
            @Override public int read()throws IOException{int n=super.read();if(n>=0&&++bytes>250L*1024*1024)throw new IOException("Guida troppo grande");return n;}
        };
        XmlPullParser x=Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
        x.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
        x.setInput(bounded,null);
        SQLiteDatabase d=getWritableDatabase();d.beginTransactionNonExclusive();
        int count=0,total=0;boolean tv=false;String channel="",current="",title="",start="",end="";
        long earliest=System.currentTimeMillis()-48L*3600*1000,latest=System.currentTimeMillis()+21L*86400*1000;
        try{
            d.delete("channels",null,null);d.delete("programme",null,null);
            int event;
            while((event=x.next())!=XmlPullParser.END_DOCUMENT){
                if(Thread.currentThread().isInterrupted())throw new IOException("Importazione interrotta");
                if(event==XmlPullParser.DOCDECL)throw new IOException("DTD non consentito");
                if(event==XmlPullParser.START_TAG){
                    String name=x.getName();
                    if("tv".equals(name))tv=true;
                    else if("channel".equals(name)){channel=x.getAttributeValue(null,"id");}
                    else if("display-name".equals(name)&&channel!=null&&!channel.isEmpty()){
                        String display=x.nextText();
                        if(display.length()>300)throw new IOException("Nome canale troppo lungo");
                        ContentValues v=new ContentValues();v.put("id",channel);v.put("name",normalized(display));
                        d.insertWithOnConflict("channels",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                    }else if("programme".equals(name)){
                        if(++total>2000000)throw new IOException("Troppi programmi");
                        current=x.getAttributeValue(null,"channel");start=x.getAttributeValue(null,"start");end=x.getAttributeValue(null,"stop");title="";
                    }else if("title".equals(name)&&current!=null&&!current.isEmpty()){
                        String value=x.nextText();if(title.isEmpty())title=value.substring(0,Math.min(value.length(),500));
                    }
                }else if(event==XmlPullParser.END_TAG){
                    if("channel".equals(x.getName()))channel="";
                    else if("programme".equals(x.getName())){
                        try{
                            long from=parseTime(start),to=parseTime(end);
                            if(current!=null&&!current.isEmpty()&&!title.isEmpty()&&to>from&&to>=earliest&&from<=latest){
                                ContentValues v=new ContentValues();v.put("channel",current);v.put("start",from);v.put("end",to);v.put("title",title);
                                d.insertWithOnConflict("programme",null,v,SQLiteDatabase.CONFLICT_REPLACE);count++;
                            }
                        }catch(ParseException ignored){}
                        current="";title="";
                    }
                }
            }
            if(!tv||count==0)throw new IOException("XMLTV senza programmi validi nel periodo corrente");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();bounded.close();}
        app.getSharedPreferences("epg",Context.MODE_PRIVATE).edit().putLong("last_success",System.currentTimeMillis()).putInt("programmes",count).apply();
        return count;
    }
    public NowNext nowNext(IptvStore.Media media){
        NowNext result=new NowNext();if(media==null||media.adult||!"TV".equals(media.kind))return result;
        String id="";
        try(IptvStore store=new IptvStore(app)){id=store.epgId(media.id);}
        SQLiteDatabase d=getReadableDatabase();
        if(id.isEmpty()){
            try(Cursor c=d.rawQuery("SELECT DISTINCT id FROM channels WHERE name=? LIMIT 2",new String[]{normalized(media.title)})){
                if(!c.moveToFirst())return result;id=c.getString(0);if(c.moveToNext())return result;
            }
        }
        long now=System.currentTimeMillis();
        // Overlapping programmes are ambiguous: do not pick one arbitrarily.
        try(Cursor c=d.rawQuery("SELECT title,start,end FROM programme WHERE channel=? AND start<=? AND end>? ORDER BY start DESC LIMIT 2",new String[]{id,String.valueOf(now),String.valueOf(now)})){
            if(c.moveToFirst()){Programme p=new Programme();p.title=c.getString(0);p.start=c.getLong(1);p.end=c.getLong(2);if(!c.moveToNext())result.now=p;}
        }
        try(Cursor c=d.rawQuery("SELECT title,start,end FROM programme WHERE channel=? AND start>=? ORDER BY start LIMIT 1",
            new String[]{id,String.valueOf(result.now==null?now:result.now.end)})){
            if(c.moveToFirst()){Programme p=new Programme();p.title=c.getString(0);p.start=c.getLong(1);p.end=c.getLong(2);result.next=p;}
        }
        return result;
    }
}


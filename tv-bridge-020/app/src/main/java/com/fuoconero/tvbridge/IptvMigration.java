package com.fuoconero.tvbridge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Optional offline catalog migration from the previously installed test041.
 *
 * The user explicitly exports their own catalog with adb/run-as and copies
 * the TAR to this app's own external files directory. Neither the playlist,
 * provider URL nor any private stream URL is uploaded to Github or elsewhere.
 */
public final class IptvMigration {
    public static final String BACKUP="Fuoconero-IPTV-041-backup.tar";
    private IptvMigration(){}
    public static String restore(Context ctx) throws Exception{
        File ext=ctx.getExternalFilesDir(null);
        if(ext==null)throw new IOException("Memoria locale non disponibile");
        File backup=new File(ext,BACKUP);
        if(!backup.isFile())throw new IOException("Backup non presente: "+BACKUP);
        if(backup.length()<4096 || backup.length()>750L*1024*1024)
            throw new IOException("Dimensioni archivio non valide");
        File staging=new File(ctx.getCacheDir(),"fuoconero_restore_041");
        if(!staging.isDirectory()&&!staging.mkdirs())
            throw new IOException("Memoria temporanea non accessibile");
        File dbfile=new File(staging,"fuoconero_iptv.db");
        File walfile=new File(staging,"fuoconero_iptv.db-wal");
        File shmfile=new File(staging,"fuoconero_iptv.db-shm");
        dbfile.delete();walfile.delete();shmfile.delete();
        try{
            extractTar(backup,dbfile,walfile,shmfile);
            if(!dbfile.isFile())throw new IOException("Database IPTV non presente nell'archivio");
            try(FileInputStream input=new FileInputStream(dbfile)){
                byte[] header=new byte[16];
                if(input.read(header)!=16 ||
                    !"SQLite format 3\0".equals(new String(header,StandardCharsets.ISO_8859_1)))
                    throw new IOException("Backup SQLite non valido");
            }
            IptvStore store=new IptvStore(ctx);
            SQLiteDatabase dest=store.getWritableDatabase();
            long existing=store.count();
            if(existing>0){
                store.close();
                throw new IOException("Catalogo gia' presente ("+existing+
                    " titoli). Il ripristino e' disponibile solo prima dell'importazione.");
            }
            // Open a snapshot of the restored SQLite files; WAL is picked up
            // by SQLite automatically if the old app used journal_mode=WAL.
            SQLiteDatabase check=SQLiteDatabase.openDatabase(dbfile.getAbsolutePath(),
                null,SQLiteDatabase.OPEN_READWRITE);
            try(Cursor c=check.rawQuery("SELECT COUNT(*) FROM entries",null)){
                if(!c.moveToFirst()||c.getLong(0)<1)
                    throw new IOException("Nessun contenuto nell'archivio 0.4.1");
            }finally{check.close();}
            String safe=dbfile.getAbsolutePath().replace("'","''");
            dest.execSQL("ATTACH DATABASE '"+safe+"' AS legacy");
            long copied=0;
            try{
                dest.beginTransaction();
                try{
                    dest.execSQL("INSERT INTO entries(id,ident,title,grp,url,category,kind,adult,seen,first_seen,fav) "+
                        "SELECT id,ident,title,grp,url,category,kind,adult,seen,first_seen,fav "+
                        "FROM legacy.entries");
                    try(Cursor count=dest.rawQuery("SELECT COUNT(*) FROM entries",null)){
                        if(count.moveToFirst())copied=count.getLong(0);
                    }
                    if(copied<1)throw new IOException("Nessun record importato");
                    dest.setTransactionSuccessful();
                }finally{dest.endTransaction();}
            }finally{
                try{dest.execSQL("DETACH DATABASE legacy");}catch(Exception ignored){}
                store.close();
            }
            ctx.getSharedPreferences(IptvStore.PREFS,Context.MODE_PRIVATE).edit()
                .putLong("item_count",copied)
                .putLong("last_success",System.currentTimeMillis())
                .putLong("successful_imports",1L)
                .putLong("new_from",Long.MAX_VALUE)
                .apply();
            return "Recuperati "+copied+" titoli dalla 0.4.1 senza riaprire "+
                "il file M3U. La vecchia applicazione resta intatta. "+
                "Puoi dare un nome alla playlist e configurare il link.";
        }finally{
            dbfile.delete();walfile.delete();shmfile.delete();
            staging.delete();
        }
    }
    private static void extractTar(File archive,File database,File wal,File shm)
        throws Exception{
        byte[] header=new byte[512],buffer=new byte[32768];
        long extracted=0;
        try(InputStream in=new BufferedInputStream(new FileInputStream(archive),65536)){
            for(int entries=0;entries<40;entries++){
                if(!readFull(in,header,512))break;
                boolean empty=true;
                for(byte b:header)if(b!=0){empty=false;break;}
                if(empty)break;
                String name=ascii(header,0,100);
                long len=octal(header,124,12);
                if(len<0||len>700L*1024*1024)
                    throw new IOException("Dimensione voce TAR non valida");
                File output=null;
                if(name.equals("databases/fuoconero_iptv.db"))output=database;
                else if(name.equals("databases/fuoconero_iptv.db-wal"))output=wal;
                else if(name.equals("databases/fuoconero_iptv.db-shm"))output=shm;
                try(OutputStream sink=output==null?null:
                    new BufferedOutputStream(new FileOutputStream(output),32768)){
                    long remaining=len;
                    while(remaining>0){
                        int n=in.read(buffer,0,(int)Math.min(remaining,buffer.length));
                        if(n<0)throw new EOFException("Backup TAR incompleto");
                        if(sink!=null){
                            sink.write(buffer,0,n);
                            extracted+=n;
                            if(extracted>720L*1024*1024)
                                throw new IOException("Backup troppo grande");
                        }
                        remaining-=n;
                    }
                }
                long pad=(512-(len%512))%512;
                while(pad-->0){
                    if(in.read()==-1)throw new EOFException("Fine TAR prematura");
                }
            }
        }
    }
    private static boolean readFull(InputStream in,byte[] arr,int size)throws IOException {
        int offset=0;
        while(offset<size){
            int n=in.read(arr,offset,size-offset);
            if(n<0){
                if(offset==0)return false;
                throw new EOFException("Intestazione TAR incompleta");
            }
            offset+=n;
        }
        return true;
    }
    private static String ascii(byte[] bytes,int from,int len){
        int end=from;
        while(end<from+len && bytes[end]!=0)end++;
        return new String(bytes,from,end-from,StandardCharsets.US_ASCII);
    }
    private static long octal(byte[] bytes,int from,int len)throws IOException {
        String raw=ascii(bytes,from,len).trim();
        if(raw.isEmpty())return 0;
        try{return Long.parseLong(raw,8);}
        catch(NumberFormatException ex){throw new IOException("Header TAR non valido");}
    }
}

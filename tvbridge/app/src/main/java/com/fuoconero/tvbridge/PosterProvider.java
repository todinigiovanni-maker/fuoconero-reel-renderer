package com.fuoconero.tvbridge;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public class PosterProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){
      return uri.getLastPathSegment()!=null && uri.getLastPathSegment().endsWith(".png")?"image/png":"image/jpeg";
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        if(uri.getPathSegments().size()!=2||!"posters".equals(uri.getPathSegments().get(0)))
            throw new FileNotFoundException("Invalid artwork path");
        String name=uri.getPathSegments().get(1);
        if(!name.matches("[A-Za-z0-9_]+\\.(jpg|png)"))
            throw new FileNotFoundException("Invalid poster filename");
        File f=new File(new File(getContext().getFilesDir(),"posters"),name);
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u,String[]p,String s,String[] a,String o){return null;}
    @Override public Uri insert(Uri u,ContentValues c){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues c,String s,String[] a){return 0;}
}

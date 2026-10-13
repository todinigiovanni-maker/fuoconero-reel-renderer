package com.fuoconero.tvbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.net.Uri;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public class PosterProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri u){return "image/png";}
    @Override public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{
        if(!"r".equals(mode)) throw new FileNotFoundException("Read only");
        String name=u.getLastPathSegment();
        if(name==null||!name.matches("[a-zA-Z0-9_-]{1,90}\\.png"))throw new FileNotFoundException("Bad image name");
        File f=new File(getContext().getFilesDir(),"posters/"+name);
        if(!f.isFile())throw new FileNotFoundException("No poster");
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){return null;}
    @Override public Uri insert(Uri u,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues values,String selection,String[] args){return 0;}
    @Override public int delete(Uri u,String selection,String[] args){return 0;}
}

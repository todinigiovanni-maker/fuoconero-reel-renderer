package com.fuoconero.netflixbridge;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
public final class ArtworkProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    static File resolve(File dir,Uri u)throws IOException {
        if(u.getPathSegments().size()!=1||!u.getLastPathSegment().matches("(app|item_[0-9]+)\\.png"))throw new FileNotFoundException();
        File f=new File(dir,u.getLastPathSegment());
        if(!f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))throw new FileNotFoundException();
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        try{return ParcelFileDescriptor.open(resolve(new File(getContext().getFilesDir(),"art"),u),ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IOException e){throw new FileNotFoundException();}
    }
    public String getType(Uri u){return "image/png";}
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}

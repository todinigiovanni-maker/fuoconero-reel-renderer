package com.fuoconero.tvbridge;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One-shot, user-initiated import endpoint. Listens only on the TV's loopback,
 * and only for a few minutes while the Bridge import button was pressed.
 * ADB forward tcp:8787 tcp:8787 exposes this socket to the owner's PC.
 * No third party or public LAN listener is opened.
 */
public final class CatalogReceiver {
    private static final int PORT=8787;
    private static final long MAX_UPLOAD=32L*1024L*1024L;
    private CatalogReceiver(){}

    public static String receiveOnce(Context ctx) {
        try(ServerSocket listener=new ServerSocket()){
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT));
            listener.setSoTimeout(180000);
            try(Socket client=listener.accept()){
                client.setSoTimeout(90000);
                BufferedInputStream in=new BufferedInputStream(client.getInputStream(),32768);
                OutputStream out=client.getOutputStream();
                try{
                    String req=readLine(in);
                    if(!"POST /import HTTP/1.1".equals(req)
                        && !"POST /import HTTP/1.0".equals(req)){
                        respond(out,400,"Invia un file ZIP con POST /import.");
                        return "Richiesta ADB non valida";
                    }
                    long contentLength=-1;
                    for(int i=0;i<40;i++){
                        String header=readLine(in);
                        if(header.isEmpty())break;
                        if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                            contentLength=Long.parseLong(header.substring(header.indexOf(':')+1).trim());
                    }
                    if(contentLength<=0 || contentLength>MAX_UPLOAD){
                        respond(out,413,"Dimensione archivio non ammessa.");
                        return "Archivio assente o troppo grande";
                    }
                    String result=TvSync.importCatalogZip(ctx,new LimitedInputStream(in,contentLength));
                    respond(out,200,result);
                    return result;
                }catch(Exception e){
                    String err="Importazione non completata: "+e.getMessage();
                    try{respond(out,400,err);}catch(Exception ignored){}
                    Log.w("FuoconeroTV","Importazione catalogo ZIP non riuscita",e);
                    return err;
                }
            }
        }catch(java.net.SocketTimeoutException e){
            return "Nessun archivio ricevuto per 3 minuti. Riprova premendo il pulsante importazione.";
        }catch(java.net.BindException e){
            return "Porta 8787 gia' occupata; annulla eventuale importazione precedente.";
        }catch(Exception e){
            return "Ricezione ADB non disponibile: "+e.getMessage();
        }
    }

    private static String readLine(InputStream in)throws IOException {
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        for(int i=0;i<8192;i++){
            int c=in.read();
            if(c<0)throw new IOException("Connessione chiusa prematuramente");
            if(c==10){
                byte[] bytes=buf.toByteArray();
                int n=bytes.length;
                if(n>0&&bytes[n-1]==13)n--;
                return new String(bytes,0,n,StandardCharsets.US_ASCII);
            }
            buf.write(c);
        }
        throw new IOException("Riga HTTP troppo lunga");
    }
    private static void respond(OutputStream out,int code,String text)throws IOException{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        String header="HTTP/1.1 "+code+" "+(code==200?"OK":"Bad Request")+"\r\n"+
            "Content-Type: text/plain; charset=utf-8\r\n"+
            "Content-Length: "+data.length+"\r\n"+
            "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
    private static class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining;
        LimitedInputStream(InputStream input,long limit){source=input;remaining=limit;}
        @Override public int read()throws IOException{
            if(remaining<=0)return -1;
            int b=source.read();
            if(b>=0)remaining--;
            return b;
        }
        @Override public int read(byte[] arr,int offset,int count)throws IOException{
            if(remaining<=0)return -1;
            int n=source.read(arr,offset,(int)Math.min(count,remaining));
            if(n>0)remaining-=n;
            return n;
        }
    }
}

import express from 'express';
import multer from 'multer';
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const app = express();
const upload = multer({ dest: '/tmp/uploads', limits: { fileSize: 40 * 1024 * 1024 } });
const PORT = process.env.PORT || 8080;
const SECRET = process.env.RENDERER_SECRET || '';
const PUBLIC_DIR = '/tmp/public-media';
const PUBLIC_TTL_MS = 60 * 60 * 1000;
const PUBLIC_BASE_URL = 'https://fuoconero-reel-renderer-app-production.up.railway.app';

app.get('/health', (_req,res)=>res.json({ok:true, service:'fuoconero-reel-renderer'}));

function auth(req,res,next){
  if (!SECRET || req.get('authorization') !== `Bearer ${SECRET}`) return res.status(401).json({ok:false,error:'Non autorizzato'});
  next();
}
function run(cmd,args){
  return new Promise((resolve,reject)=>{
    const p=spawn(cmd,args); let err='';
    p.stderr.on('data',d=>err+=d.toString());
    p.on('error',reject);
    p.on('close',code=>code===0?resolve():reject(new Error(err.slice(-4000)||`${cmd} exit ${code}`)));
  });
}
function esc(s=''){return s.replace(/\\/g,'\\\\').replace(/:/g,'\\:').replace(/'/g,"\\'").replace(/%/g,'\\%');}
function wrapText(value='',max=32){
  const words=String(value).replace(/\s+/g,' ').trim().split(' ').filter(Boolean);
  const lines=[];
  let line='';
  for(const word of words){
    const next=line?line+' '+word:word;
    if(next.length>max&&line){
      lines.push(line);
      line=word;
    }else{
      line=next;
    }
  }
  if(line) lines.push(line);
  return lines.slice(0,4).join('\n');
}
function escMultiline(value='',max=32){
  return esc(wrapText(value,max)).replace(/\n/g,'\\n');
}

// Lightweight FFmpeg smoke test.
app.get('/selftest', async (_req,res)=>{
  const id=crypto.randomUUID(); const out='/tmp/selftest-'+id+'.mp4';
  try{
    await run('ffmpeg',['-y','-f','lavfi','-i','color=c=black:s=360x640:r=15','-t','1','-c:v','libx264','-preset','ultrafast','-threads','2','-pix_fmt','yuv420p','-movflags','+faststart',out]);
    const st=await fs.stat(out);
    await fs.unlink(out);
    res.json({ok:true,rendered:true,width:360,height:640,duration:1,bytes:st.size,codec:'h264',stage:'minimal'});
  }catch(e){
    await Promise.allSettled([fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore self-test'});
  }
});

async function exposeVideo(out, req){
  await fs.mkdir(PUBLIC_DIR,{recursive:true});
  const name=path.basename(out);
  const target=path.join(PUBLIC_DIR,name);
  if(out!==target) await fs.rename(out,target);
  const timer=setTimeout(()=>{ fs.unlink(target).catch(()=>{}); },PUBLIC_TTL_MS);
  if(typeof timer.unref==='function') timer.unref();
  return `${PUBLIC_BASE_URL}/media/${encodeURIComponent(name)}`;
}

app.get('/media/:name', async (req,res)=>{
  const name=path.basename(req.params.name||'');
  if(!/^[a-f0-9-]+\.mp4$/i.test(name)) return res.status(400).json({ok:false,error:'Nome file non valido'});
  const file=path.join(PUBLIC_DIR,name);
  try{
    await fs.access(file);
    res.type('video/mp4');
    res.setHeader('Cache-Control','public, max-age=600');
    res.sendFile(file);
  }catch{
    res.status(404).json({ok:false,error:'Video non disponibile o scaduto'});
  }
});

// Full-size public URL validation, without publishing to social platforms.
app.get('/pipeline-selftest', async (req,res)=>{
  const id=crypto.randomUUID(); const out=path.join(PUBLIC_DIR,id+'.mp4');
  try{
    await fs.mkdir(PUBLIC_DIR,{recursive:true});
    await run('ffmpeg',[
      '-y','-f','lavfi','-i','color=c=0x101014:s=1080x1920:r=30',
      '-vf',"drawbox=x=0:y=1450:w=1080:h=470:color=black@0.62:t=fill,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='FUOCONERO':fontcolor=white:fontsize=62:x=(w-text_w)/2:y=1540,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='fuoconero.com':fontcolor=white:fontsize=38:x=(w-text_w)/2:y=1640",
      '-t','3','-r','30','-c:v','libx264','-preset','veryfast','-threads','2','-crf','23',
      '-pix_fmt','yuv420p','-movflags','+faststart',out
    ]);
    const st=await fs.stat(out);
    const videoUrl=await exposeVideo(out,req);
    res.json({ok:true,rendered:true,video_url:videoUrl,expires_in_seconds:3600,width:1080,height:1920,duration:3,bytes:st.size,codec:'h264',stage:'pipeline'});
  }catch(e){
    await Promise.allSettled([fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore pipeline self-test'});
  }
});

// First renderer: image + optional audio -> 1080x1920 H.264/AAC MP4.
app.post('/render', auth, upload.fields([{name:'image',maxCount:1},{name:'audio',maxCount:1}]), async (req,res)=>{
  const image=req.files?.image?.[0]; const audio=req.files?.audio?.[0];
  if(!image) return res.status(400).json({ok:false,error:'Immagine mancante'});
  const title=(req.body.title||'FUOCONERO').slice(0,120);
  const subtitle=(req.body.subtitle||'fuoconero.com').slice(0,160);
  const duration=Math.min(Math.max(Number(req.body.duration)||12,3),60);
  const id=crypto.randomUUID(); const out=`/tmp/${id}.mp4`;
  try{
    const vf=`scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,drawbox=x=0:y=1450:w=1080:h=470:color=black@0.62:t=fill,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='${esc(title)}':fontcolor=white:fontsize=62:x=(w-text_w)/2:y=1540,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='${esc(subtitle)}':fontcolor=white:fontsize=38:x=(w-text_w)/2:y=1640`;
    const args=['-y','-loop','1','-i',image.path];
    if(audio) args.push('-i',audio.path);
    args.push('-vf',vf,'-t',String(duration),'-r','30','-c:v','libx264','-preset','veryfast','-threads','2','-crf','23','-pix_fmt','yuv420p');
    if(audio) args.push('-c:a','aac','-b:a','192k','-shortest'); else args.push('-an');
    args.push('-movflags','+faststart',out);
    await run('ffmpeg',args);
    res.setHeader('Content-Type','video/mp4');
    res.setHeader('Content-Disposition','attachment; filename="fuoconero-reel.mp4"');
    res.sendFile(out, async()=>{ await Promise.allSettled([fs.unlink(out),fs.unlink(image.path),audio?fs.unlink(audio.path):Promise.resolve()]); });
  }catch(e){
    await Promise.allSettled([fs.unlink(image.path),audio?fs.unlink(audio.path):Promise.resolve(),fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore renderer'});
  }
});

// Render an image + optional audio and return a temporary public HTTPS MP4 URL.
app.post('/render-url', auth, upload.fields([{name:'image',maxCount:1},{name:'audio',maxCount:1}]), async (req,res)=>{
  const image=req.files?.image?.[0]; const audio=req.files?.audio?.[0];
  if(!image) return res.status(400).json({ok:false,error:'Immagine mancante'});
  const title=(req.body.title||'FUOCONERO').slice(0,120);
  const subtitle=(req.body.subtitle||'fuoconero.com').slice(0,160);
  const duration=Math.min(Math.max(Number(req.body.duration)||12,3),60);
  const id=crypto.randomUUID(); const out=path.join(PUBLIC_DIR,id+'.mp4');
  try{
    await fs.mkdir(PUBLIC_DIR,{recursive:true});
    const vf=`scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,drawbox=x=0:y=1450:w=1080:h=470:color=black@0.62:t=fill,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='${esc(title)}':fontcolor=white:fontsize=62:x=(w-text_w)/2:y=1540,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='${esc(subtitle)}':fontcolor=white:fontsize=38:x=(w-text_w)/2:y=1640`;
    const args=['-y','-loop','1','-i',image.path];
    if(audio) args.push('-i',audio.path);
    args.push('-vf',vf,'-t',String(duration),'-r','30','-c:v','libx264','-preset','veryfast','-threads','2','-crf','23','-pix_fmt','yuv420p');
    if(audio) args.push('-c:a','aac','-b:a','192k','-shortest'); else args.push('-an');
    args.push('-movflags','+faststart',out);
    await run('ffmpeg',args);
    const st=await fs.stat(out);
    const videoUrl=await exposeVideo(out,req);
    await Promise.allSettled([fs.unlink(image.path),audio?fs.unlink(audio.path):Promise.resolve()]);
    res.json({ok:true,rendered:true,video_url:videoUrl,expires_in_seconds:3600,bytes:st.size,width:1080,height:1920,codec:'h264'});
  }catch(e){
    await Promise.allSettled([fs.unlink(image.path),audio?fs.unlink(audio.path):Promise.resolve(),fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore renderer'});
  }
});


app.post('/render-blog-url', auth, upload.fields([
  {name:'image',maxCount:1},{name:'voice',maxCount:1},{name:'music',maxCount:1}
]), async (req,res)=>{
  const image=req.files?.image?.[0], voice=req.files?.voice?.[0], music=req.files?.music?.[0];
  if(!image) return res.status(400).json({ok:false,error:'Immagine mancante'});
  const duration=Math.min(Math.max(Number(req.body.duration)||17,10),30);
  const id=crypto.randomUUID(), out=path.join(PUBLIC_DIR,id+'.mp4');
  const category=escMultiline(String(req.body.category||'FUOCONERO').slice(0,60),28);
  const title=escMultiline(String(req.body.title||'FUOCONERO').slice(0,120),24);
  const subtitle=escMultiline(String(req.body.subtitle||'').slice(0,160),32);
  const hook=escMultiline(String(req.body.hook||'').slice(0,220),34);
  const keyPoint=escMultiline(String(req.body.keyPoint||'').slice(0,220),34);
  const highlight=escMultiline(String(req.body.highlight||'').slice(0,260),32);
  const close=escMultiline(String(req.body.close||'').slice(0,240),34);
  const cta=escMultiline(String(req.body.cta||'Leggi tutto su fuoconero.com').slice(0,160),32);
  try{
    await fs.mkdir(PUBLIC_DIR,{recursive:true});
    let fc="[0:v]split=2[bg0][fg0];"+
      "[bg0]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=24:2,eq=brightness=-0.24:saturation=0.75[bg];"+
      "[fg0]scale=1000:1150:force_original_aspect_ratio=decrease[fg];"+
      "[bg][fg]overlay=(W-w)/2:250:format=auto,drawbox=x=0:y=0:w=1080:h=1920:color=black@0.16:t=fill[v0];"+
      "[v0]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+category+"':fontcolor=white@0.82:fontsize=34:x=60:y=70:enable='between(t,0,"+duration+")'[v1];"+
      "[v1]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+title+"':fontcolor=white:fontsize=72:line_spacing=10:x=(w-text_w)/2:y=1320:box=1:boxcolor=black@0.62:boxborderw=34:enable='between(t,0,2.8)'[v2];"+
      "[v2]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='"+subtitle+"':fontcolor=white@0.92:fontsize=35:line_spacing=8:x=(w-text_w)/2:y=1535:box=1:boxcolor=black@0.54:boxborderw=24:enable='between(t,0,2.8)'[v3];"+
      "[v3]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+hook+"':fontcolor=white:fontsize=56:line_spacing=12:x=(w-text_w)/2:y=1370:box=1:boxcolor=black@0.68:boxborderw=34:enable='between(t,2.8,5.6)'[v4];"+
      "[v4]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+keyPoint+"':fontcolor=white:fontsize=58:line_spacing=12:x=(w-text_w)/2:y=1370:box=1:boxcolor=black@0.68:boxborderw=34:enable='between(t,5.6,8.1)'[v5];"+
      "[v5]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+highlight+"':fontcolor=white:fontsize=52:line_spacing=12:x=(w-text_w)/2:y=1345:box=1:boxcolor=black@0.72:boxborderw=34:enable='between(t,8.1,12.2)'[v6];"+
      "[v6]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+close+"':fontcolor=white:fontsize=54:line_spacing=12:x=(w-text_w)/2:y=1360:box=1:boxcolor=black@0.70:boxborderw=34:enable='between(t,12.2,15.5)'[v7];"+
      "[v7]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='"+cta+"':fontcolor=white:fontsize=50:line_spacing=12:x=(w-text_w)/2:y=1410:box=1:boxcolor=black@0.76:boxborderw=38:enable='between(t,15.5,"+duration+")'[vout]";
    const args=['-y','-loop','1','-i',image.path];
    let idx=1,vi=null,mi=null;
    if(voice){vi=idx++;args.push('-i',voice.path);}
    if(music){mi=idx++;args.push('-stream_loop','-1','-i',music.path);}
    let audioMap=null;
    if(vi!==null&&mi!==null){
      fc+=';['+vi+':a]volume=1.0[va];['+mi+':a]volume=0.12,afade=t=in:st=0:d=0.7,afade=t=out:st='+(duration-1.5)+':d=1.5[ma];[va][ma]amix=inputs=2:duration=longest:dropout_transition=2[aout]';
      audioMap='[aout]';
    }else if(vi!==null){fc+=';['+vi+':a]volume=1.0[aout]';audioMap='[aout]';}
    else if(mi!==null){fc+=';['+mi+':a]volume=0.18,afade=t=in:st=0:d=0.7,afade=t=out:st='+(duration-1.5)+':d=1.5[aout]';audioMap='[aout]';}
    args.push('-filter_complex',fc,'-map','[vout]');
    if(audioMap) args.push('-map',audioMap);
    args.push('-t',String(duration),'-r','30','-c:v','libx264','-preset','veryfast','-threads','2','-crf','23','-pix_fmt','yuv420p');
    if(audioMap) args.push('-c:a','aac','-b:a','192k'); else args.push('-an');
    args.push('-movflags','+faststart',out);
    await run('ffmpeg',args);
    const st=await fs.stat(out), videoUrl=await exposeVideo(out,req);
    await Promise.allSettled([fs.unlink(image.path),voice?fs.unlink(voice.path):Promise.resolve(),music?fs.unlink(music.path):Promise.resolve()]);
    res.json({ok:true,rendered:true,template:'FUOCONERO_BLOG_REEL_V1',video_url:videoUrl,expires_in_seconds:3600,bytes:st.size,width:1080,height:1920,duration,codec:'h264',audio:{voice:Boolean(voice),music:Boolean(music)}});
  }catch(e){
    await Promise.allSettled([fs.unlink(image.path),voice?fs.unlink(voice.path):Promise.resolve(),music?fs.unlink(music.path):Promise.resolve(),fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore blog renderer'});
  }
});

app.listen(PORT,()=>console.log(`renderer listening on ${PORT}`));

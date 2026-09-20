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
const PUBLIC_TTL_MS = 15 * 60 * 1000;
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
    res.json({ok:true,rendered:true,video_url:videoUrl,expires_in_seconds:900,width:1080,height:1920,duration:3,bytes:st.size,codec:'h264',stage:'pipeline'});
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
    res.json({ok:true,rendered:true,video_url:videoUrl,expires_in_seconds:900,bytes:st.size,width:1080,height:1920,codec:'h264'});
  }catch(e){
    await Promise.allSettled([fs.unlink(image.path),audio?fs.unlink(audio.path):Promise.resolve(),fs.unlink(out)]);
    res.status(500).json({ok:false,error:e instanceof Error?e.message:'Errore renderer'});
  }
});

app.listen(PORT,()=>console.log(`renderer listening on ${PORT}`));

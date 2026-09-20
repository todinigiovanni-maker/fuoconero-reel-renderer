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
    args.push('-vf',vf,'-t',String(duration),'-r','30','-c:v','libx264','-pix_fmt','yuv420p');
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

app.listen(PORT,()=>console.log(`renderer listening on ${PORT}`));

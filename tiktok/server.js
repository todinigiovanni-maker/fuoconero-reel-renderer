import express from 'express';
import crypto from 'node:crypto';
import { promises as fs } from 'node:fs';

const app=express();
app.use(express.json({limit:'2mb'}));

const PORT=Number(process.env.PORT||8080);
const CLIENT_KEY=process.env.TIKTOK_CLIENT_KEY||'';
const CLIENT_SECRET=process.env.TIKTOK_CLIENT_SECRET||'';
const REDIRECT_URI=process.env.TIKTOK_REDIRECT_URI||'';
const BRIDGE_SECRET=process.env.BRIDGE_SECRET||'';
const PUBLISH_PIN=process.env.PUBLISH_PIN||'';
const TOKEN_FILE=process.env.TIKTOK_TOKEN_FILE||'/data/tiktok-oauth.json';

function b64url(buf){
  return Buffer.from(buf).toString('base64url');
}
function unb64url(s){
  return Buffer.from(s,'base64url');
}
function key(){
  return crypto.createHash('sha256').update(BRIDGE_SECRET||'fuoconero-tiktok').digest();
}
function makeState(){
  const payload=Buffer.from(JSON.stringify({ts:Date.now(),nonce:b64url(crypto.randomBytes(18))}));
  const p=b64url(payload);
  const sig=b64url(crypto.createHmac('sha256',key()).update(p).digest());
  return p+'.'+sig;
}
function verifyState(state=''){
  try{
    const [p,s]=state.split('.');
    if(!p||!s) return false;
    const expected=crypto.createHmac('sha256',key()).update(p).digest();
    const actual=unb64url(s);
    if(expected.length!==actual.length||!crypto.timingSafeEqual(expected,actual)) return false;
    const body=JSON.parse(unb64url(p).toString('utf8'));
    return Number.isFinite(body.ts)&&Date.now()-body.ts>=0&&Date.now()-body.ts<=10*60*1000;
  }catch{return false;}
}
async function saveTokens(tokens){
  await fs.mkdir('/data',{recursive:true});
  const iv=crypto.randomBytes(12);
  const cipher=crypto.createCipheriv('aes-256-gcm',key(),iv);
  const plain=Buffer.from(JSON.stringify(tokens));
  const enc=Buffer.concat([cipher.update(plain),cipher.final()]);
  const tag=cipher.getAuthTag();
  await fs.writeFile(TOKEN_FILE,JSON.stringify({
    v:1,iv:b64url(iv),tag:b64url(tag),data:b64url(enc),updated_at:new Date().toISOString()
  }));
}
async function loadTokens(){
  const raw=JSON.parse(await fs.readFile(TOKEN_FILE,'utf8'));
  const decipher=crypto.createDecipheriv('aes-256-gcm',key(),unb64url(raw.iv));
  decipher.setAuthTag(unb64url(raw.tag));
  return JSON.parse(Buffer.concat([decipher.update(unb64url(raw.data)),decipher.final()]).toString('utf8'));
}
async function tokenRequest(params){
  const body=new URLSearchParams(params);
  const r=await fetch('https://open.tiktokapis.com/v2/oauth/token/',{
    method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body,
    signal:AbortSignal.timeout(30000)
  });
  const data=await r.json();
  if(!r.ok||!data?.access_token) throw new Error('TikTok OAuth HTTP '+r.status+': '+JSON.stringify(data).slice(-1200));
  return data;
}
async function getAccessToken(){
  let t=await loadTokens();
  if(Date.now()<Number(t.expires_at||0)-120000) return {access_token:t.access_token,open_id:t.open_id||''};
  const fresh=await tokenRequest({
    client_key:CLIENT_KEY,
    client_secret:CLIENT_SECRET,
    grant_type:'refresh_token',
    refresh_token:t.refresh_token
  });
  t={...fresh,
    expires_at:Date.now()+Number(fresh.expires_in||86400)*1000,
    refresh_expires_at:Date.now()+Number(fresh.refresh_expires_in||31536000)*1000
  };
  await saveTokens(t);
  return {access_token:t.access_token,open_id:t.open_id||''};
}
async function tiktokJson(path,accessToken,body){
  const r=await fetch('https://open.tiktokapis.com'+path,{
    method:'POST',
    headers:{
      Authorization:'Bearer '+accessToken,
      'Content-Type':'application/json; charset=UTF-8'
    },
    body:JSON.stringify(body||{}),
    signal:AbortSignal.timeout(60000)
  });
  const data=await r.json();
  if(!r.ok||data?.error?.code!=='ok'){
    throw new Error('TikTok API '+path+' HTTP '+r.status+': '+JSON.stringify(data).slice(-1800));
  }
  return data;
}
function bridgeAuth(req,res,next){
  const auth=req.get('authorization')||'';
  if(!BRIDGE_SECRET||auth!=='Bearer '+BRIDGE_SECRET) return res.status(401).json({ok:false,error:'Non autorizzato'});
  next();
}

app.get('/health',(_,res)=>res.json({
  ok:true,
  service:'fuoconero-tiktok-bridge',
  configured:Boolean(CLIENT_KEY&&CLIENT_SECRET&&REDIRECT_URI&&BRIDGE_SECRET&&PUBLISH_PIN),
  version:'1.0.0'
}));

app.get('/connect',(req,res)=>{
  if(!CLIENT_KEY||!REDIRECT_URI||!BRIDGE_SECRET) return res.status(500).send('Configurazione TikTok incompleta');
  const u=new URL('https://www.tiktok.com/v2/auth/authorize/');
  u.searchParams.set('client_key',CLIENT_KEY);
  u.searchParams.set('response_type','code');
  u.searchParams.set('scope','user.info.basic,video.publish');
  u.searchParams.set('redirect_uri',REDIRECT_URI);
  u.searchParams.set('state',makeState());
  res.redirect(u.toString());
});

app.get('/oauth/callback',async(req,res)=>{
  try{
    const {code,state,error,error_description}=req.query;
    if(error) return res.status(400).send('TikTok ha rifiutato l’autorizzazione: '+String(error_description||error));
    if(!code||!verifyState(String(state||''))) return res.status(400).send('Richiesta OAuth non valida o scaduta');
    const t=await tokenRequest({
      client_key:CLIENT_KEY,
      client_secret:CLIENT_SECRET,
      code:String(code),
      grant_type:'authorization_code',
      redirect_uri:REDIRECT_URI
    });
    await saveTokens({
      ...t,
      expires_at:Date.now()+Number(t.expires_in||86400)*1000,
      refresh_expires_at:Date.now()+Number(t.refresh_expires_in||31536000)*1000
    });
    res.type('html').send("<!doctype html><html lang='it'><body style='font-family:sans-serif;background:#111;color:#fff;padding:40px'><h1>TikTok collegato ✅</h1><p>Fuoconero può ora usare il Content Posting API. Puoi chiudere questa pagina.</p></body></html>");
  }catch(e){
    res.status(500).send('Errore collegamento TikTok: '+(e instanceof Error?e.message:String(e)));
  }
});

app.get('/status',bridgeAuth,async(_,res)=>{
  try{
    const t=await loadTokens();
    res.json({
      ok:true,
      connected:Boolean(t.refresh_token),
      scopes:String(t.scope||'').split(',').filter(Boolean),
      expires_at:t.expires_at||null,
      refresh_expires_at:t.refresh_expires_at||null,
      open_id:t.open_id||null
    });
  }catch{
    res.json({ok:true,connected:false});
  }
});

app.post('/creator-info',bridgeAuth,async(_,res)=>{
  try{
    const {access_token}=await getAccessToken();
    const data=await tiktokJson('/v2/post/publish/creator_info/query/',access_token,{});
    res.json({ok:true,creator:data.data});
  }catch(e){
    res.status(502).json({ok:false,error:e instanceof Error?e.message:String(e)});
  }
});

app.post('/post',async(req,res)=>{
  try{
    if(!PUBLISH_PIN||req.get('x-publish-pin')!==PUBLISH_PIN) return res.status(401).json({ok:false,error:'Accesso non autorizzato'});
    const body=req.body||{};
    if(body.confirmed!==true) return res.status(400).json({ok:false,error:'Pubblicazione non confermata'});
    const videoUrl=String(body.video_url||'');
    if(!videoUrl.startsWith('https://')) return res.status(400).json({ok:false,error:'video_url HTTPS mancante'});
    const title=String(body.title||'').slice(0,2200);
    const requestedPrivacy=String(body.privacy_level||'SELF_ONLY');

    const {access_token}=await getAccessToken();
    const creator=(await tiktokJson('/v2/post/publish/creator_info/query/',access_token,{})).data||{};
    const options=Array.isArray(creator.privacy_level_options)?creator.privacy_level_options:[];
    const privacy=options.includes(requestedPrivacy)?requestedPrivacy:(options.includes('SELF_ONLY')?'SELF_ONLY':options[0]);
    if(!privacy) throw new Error('Nessuna privacy_level disponibile per il creator');

    const v=await fetch(videoUrl,{signal:AbortSignal.timeout(120000)});
    if(!v.ok) throw new Error('Download video HTTP '+v.status);
    const bytes=Buffer.from(await v.arrayBuffer());
    if(!bytes.length) throw new Error('Video vuoto');

    const init=await tiktokJson('/v2/post/publish/video/init/',access_token,{
      post_info:{
        title,
        privacy_level:privacy,
        disable_duet:Boolean(body.disable_duet),
        disable_comment:Boolean(body.disable_comment),
        disable_stitch:Boolean(body.disable_stitch),
        video_cover_timestamp_ms:Number(body.video_cover_timestamp_ms||1000),
        ...(body.is_aigc===true?{is_aigc:true}:{})
      },
      source_info:{
        source:'FILE_UPLOAD',
        video_size:bytes.length,
        chunk_size:bytes.length,
        total_chunk_count:1
      }
    });

    const publishId=init.data?.publish_id;
    const uploadUrl=init.data?.upload_url;
    if(!publishId||!uploadUrl) throw new Error('TikTok non ha restituito publish_id/upload_url');

    const up=await fetch(uploadUrl,{
      method:'PUT',
      headers:{
        'Content-Type':'video/mp4',
        'Content-Length':String(bytes.length),
        'Content-Range':'bytes 0-'+(bytes.length-1)+'/'+bytes.length
      },
      body:bytes,
      signal:AbortSignal.timeout(180000)
    });
    if(!up.ok) throw new Error('TikTok upload HTTP '+up.status+': '+(await up.text()).slice(-1000));

    res.json({
      ok:true,
      publish_id:publishId,
      privacy_level:privacy,
      bytes:bytes.length,
      uploaded:true,
      published:false,
      note:'TikTok elabora la pubblicazione in modo asincrono'
    });
  }catch(e){
    res.status(502).json({ok:false,error:e instanceof Error?e.message:String(e)});
  }
});

app.post('/publish-status',bridgeAuth,async(req,res)=>{
  try{
    const publishId=String(req.body?.publish_id||'');
    if(!publishId) return res.status(400).json({ok:false,error:'publish_id mancante'});
    const {access_token}=await getAccessToken();
    const data=await tiktokJson('/v2/post/publish/status/fetch/',access_token,{publish_id:publishId});
    res.json({ok:true,data:data.data});
  }catch(e){
    res.status(502).json({ok:false,error:e instanceof Error?e.message:String(e)});
  }
});

app.listen(PORT,()=>console.log('fuoconero tiktok bridge listening on '+PORT));

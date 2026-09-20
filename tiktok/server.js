import express from 'express';
import crypto from 'node:crypto';
import { promises as fs } from 'node:fs';

const app=express();
app.use(express.json({limit:'2mb'}));
app.use(express.urlencoded({extended:false,limit:'1mb'}));

const PORT=Number(process.env.PORT||8080);
const CLIENT_KEY=process.env.TIKTOK_CLIENT_KEY||'';
const CLIENT_SECRET=process.env.TIKTOK_CLIENT_SECRET||'';
const REDIRECT_URI=process.env.TIKTOK_REDIRECT_URI||'';
const BRIDGE_SECRET=process.env.BRIDGE_SECRET||'';
const PUBLISH_PIN=process.env.PUBLISH_PIN||'';
const TOKEN_FILE=process.env.TIKTOK_TOKEN_FILE||'/data/tiktok-oauth.json';
const DEMO_VIDEO_URL=process.env.TIKTOK_DEMO_VIDEO_URL||'https://fuoconero-reel-renderer-app-production.up.railway.app/media/63a0e242-f8cb-4d55-8c5e-ac2da4a3e62b.mp4';

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

function escapeHtml(value=''){
  return String(value)
    .replaceAll('&','&amp;')
    .replaceAll('<','&lt;')
    .replaceAll('>','&gt;')
    .replaceAll('"','&quot;')
    .replaceAll("'","&#039;");
}
function cookieValue(req,name){
  const raw=req.get('cookie')||'';
  for(const part of raw.split(';')){
    const i=part.indexOf('=');
    if(i<0) continue;
    if(part.slice(0,i).trim()===name) return decodeURIComponent(part.slice(i+1).trim());
  }
  return '';
}
function makeDemoSession(openId=''){
  const payload=b64url(Buffer.from(JSON.stringify({
    ts:Date.now(),
    open_id:String(openId||'')
  })));
  const sig=b64url(crypto.createHmac('sha256',key()).update('demo:'+payload).digest());
  return payload+'.'+sig;
}
function verifyDemoSession(value=''){
  try{
    const [payload,sig]=String(value).split('.');
    if(!payload||!sig) return false;
    const expected=crypto.createHmac('sha256',key()).update('demo:'+payload).digest();
    const actual=unb64url(sig);
    if(expected.length!==actual.length||!crypto.timingSafeEqual(expected,actual)) return false;
    const body=JSON.parse(unb64url(payload).toString('utf8'));
    return Number.isFinite(body.ts)&&Date.now()-body.ts>=0&&Date.now()-body.ts<=2*60*60*1000;
  }catch{return false;}
}
function demoAuthorized(req){
  return verifyDemoSession(cookieValue(req,'fuoconero_demo'));
}
function demoAuth(req,res,next){
  if(!demoAuthorized(req)) return res.status(401).json({ok:false,error:'Sessione demo non autorizzata'});
  next();
}
function pageShell(body,{title='Fuoconero Social'}={}){
  return `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
:root{color-scheme:dark;--bg:#090909;--panel:#131313;--soft:#1d1d1d;--line:#303030;--text:#f6f6f6;--muted:#aaa;--fire:#ff6a00;--ok:#39d98a}
*{box-sizing:border-box} body{margin:0;background:radial-gradient(circle at top,#201008 0,#090909 42%);font-family:Inter,system-ui,Segoe UI,Arial,sans-serif;color:var(--text)}
.wrap{max-width:880px;margin:0 auto;padding:34px 20px 60px}.brand{display:flex;gap:14px;align-items:center;margin-bottom:24px}.mark{width:58px;height:58px;border-radius:16px;background:#050505;border:1px solid #3c2718;display:grid;place-items:center;font-size:32px;box-shadow:0 0 30px #ff6a0022}.brand h1{font-size:28px;margin:0}.brand p{margin:4px 0 0;color:var(--muted)}
.card{background:#121212dd;border:1px solid var(--line);border-radius:18px;padding:20px;margin:14px 0;box-shadow:0 14px 40px #0008}.step{font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--fire);font-weight:800}.ok{color:var(--ok);font-weight:700}.muted{color:var(--muted)}
.btn{display:inline-flex;align-items:center;justify-content:center;border:0;border-radius:12px;padding:12px 17px;font-weight:800;cursor:pointer;text-decoration:none;background:var(--fire);color:#111}.btn.secondary{background:#242424;color:#fff;border:1px solid #3a3a3a}.btn:disabled{opacity:.45;cursor:not-allowed}
textarea{width:100%;min-height:96px;background:#090909;border:1px solid #363636;color:#fff;border-radius:12px;padding:12px;font:inherit;resize:vertical}video{width:100%;max-height:520px;background:#000;border-radius:14px;border:1px solid #2b2b2b;margin-top:12px}.row{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.confirm{display:flex;gap:10px;align-items:flex-start;margin:15px 0;color:#ddd}.confirm input{margin-top:4px}.status{white-space:pre-wrap;background:#080808;border:1px solid #282828;border-radius:12px;padding:12px;color:#d7d7d7;min-height:48px}.pill{display:inline-block;padding:5px 9px;border-radius:999px;background:#202020;border:1px solid #343434;font-size:12px;color:#ccc}.foot{margin-top:24px;color:#777;font-size:13px}
</style>
</head>
<body><main class="wrap">${body}</main></body></html>`;
}
async function demoCreator(){
  try{
    const {access_token}=await getAccessToken();
    const data=await tiktokJson('/v2/post/publish/creator_info/query/',access_token,{});
    return data.data||{};
  }catch{return {};}
}


function bridgeAuth(req,res,next){
  const auth=req.get('authorization')||'';
  if(!BRIDGE_SECRET||auth!=='Bearer '+BRIDGE_SECRET) return res.status(401).json({ok:false,error:'Non autorizzato'});
  next();
}


app.get('/',(_req,res)=>res.redirect('/demo'));

app.get('/demo',async(req,res)=>{
  const connected=demoAuthorized(req);
  let creator={};
  if(connected) creator=await demoCreator();
  const privacy=Array.isArray(creator.privacy_level_options)?creator.privacy_level_options:[];
  const body=`
    <div class="brand"><div class="mark">★</div><div><h1>Fuoconero Social</h1><p>Crea, rivedi e pubblica i reel Fuoconero con conferma esplicita.</p></div></div>

    <section class="card">
      <div class="step">1 · Collega TikTok</div>
      <h2>${connected?'<span class="ok">Account collegato ✓</span>':'Autorizza il tuo account'}</h2>
      <p class="muted">Login Kit viene usato per autorizzare in modo sicuro l'account TikTok.</p>
      ${connected
        ? '<span class="pill">user.info.basic</span> <span class="pill">video.publish</span>'
        : '<a class="btn" href="/connect">Collega TikTok</a>'}
    </section>

    <section class="card" style="${connected?'':'opacity:.45;pointer-events:none'}">
      <div class="step">2 · Rivedi il contenuto</div>
      <h2>Anteprima reel</h2>
      <p class="muted">Il contenuto viene mostrato prima dell'invio. Nessuna pubblicazione parte senza conferma.</p>
      <video controls playsinline preload="metadata" src="${escapeHtml(DEMO_VIDEO_URL)}"></video>
      <label for="caption"><p><strong>Caption TikTok</strong></p></label>
      <textarea id="caption">FUOCONERO — test integrazione privata #fuoconero</textarea>
    </section>

    <section class="card" style="${connected?'':'opacity:.45;pointer-events:none'}">
      <div class="step">3 · Conferma e pubblica</div>
      <h2>Pubblicazione TikTok</h2>
      <p>Privacy disponibile nel Sandbox: <strong>${privacy.includes('SELF_ONLY')?'Solo io (SELF_ONLY)':'SELF_ONLY'}</strong></p>
      <label class="confirm"><input id="confirm" type="checkbox"><span>Confermo di aver rivisto video e caption e voglio inviare questo contenuto a TikTok.</span></label>
      <div class="row">
        <button id="publish" class="btn" disabled>Pubblica su TikTok · Solo io</button>
        <button id="check" class="btn secondary" disabled>Controlla stato</button>
      </div>
      <div id="status" class="status" style="margin-top:14px">In attesa di conferma.</div>
    </section>

    <p class="foot">Fuoconero Social · fuoconero.com · La pubblicazione richiede sempre un'azione esplicita dell'utente.</p>

    <script>
    const confirmBox=document.getElementById('confirm');
    const publishBtn=document.getElementById('publish');
    const checkBtn=document.getElementById('check');
    const statusBox=document.getElementById('status');
    let publishId='';
    if(confirmBox){
      confirmBox.addEventListener('change',()=>{publishBtn.disabled=!confirmBox.checked;});
    }
    async function checkStatus(){
      if(!publishId)return;
      checkBtn.disabled=true;
      try{
        const r=await fetch('/demo/status',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({publish_id:publishId})});
        const data=await r.json();
        statusBox.textContent=data.ok?'Stato TikTok: '+JSON.stringify(data.data):'Errore: '+(data.error||'sconosciuto');
      }catch(e){statusBox.textContent='Errore controllo stato: '+e.message;}
      finally{checkBtn.disabled=false;}
    }
    if(checkBtn)checkBtn.addEventListener('click',checkStatus);
    if(publishBtn)publishBtn.addEventListener('click',async()=>{
      if(!confirmBox.checked)return;
      publishBtn.disabled=true;
      statusBox.textContent='Invio a TikTok in corso…';
      try{
        const r=await fetch('/demo/post',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirmed:true,title:document.getElementById('caption').value})});
        const data=await r.json();
        if(!r.ok||!data.ok){statusBox.textContent='Errore: '+(data.error||'pubblicazione non riuscita');publishBtn.disabled=false;return;}
        publishId=data.publish_id||'';
        statusBox.textContent='Upload completato ✓\nPrivacy: '+data.privacy_level+'\nTikTok sta elaborando il video.';
        checkBtn.disabled=!publishId;
        setTimeout(()=>{if(publishId)checkStatus();},2500);
      }catch(e){statusBox.textContent='Errore: '+e.message;publishBtn.disabled=false;}
    });
    </script>
  `;
  res.type('html').send(pageShell(body));
});

app.post('/demo/post',demoAuth,async(req,res)=>{
  try{
    if(req.body?.confirmed!==true) return res.status(400).json({ok:false,error:'Pubblicazione non confermata'});
    const title=String(req.body?.title||'FUOCONERO').slice(0,2200);
    const videoUrl=DEMO_VIDEO_URL;
    const {access_token}=await getAccessToken();
    const creator=(await tiktokJson('/v2/post/publish/creator_info/query/',access_token,{})).data||{};
    const options=Array.isArray(creator.privacy_level_options)?creator.privacy_level_options:[];
    if(!options.includes('SELF_ONLY')) throw new Error('SELF_ONLY non disponibile per questo account');

    const v=await fetch(videoUrl,{signal:AbortSignal.timeout(120000)});
    if(!v.ok) throw new Error('Download video HTTP '+v.status);
    const bytes=Buffer.from(await v.arrayBuffer());
    if(!bytes.length) throw new Error('Video vuoto');

    const init=await tiktokJson('/v2/post/publish/video/init/',access_token,{
      post_info:{
        title,
        privacy_level:'SELF_ONLY',
        disable_duet:true,
        disable_comment:true,
        disable_stitch:true,
        video_cover_timestamp_ms:1000
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

    res.json({ok:true,publish_id:publishId,privacy_level:'SELF_ONLY',bytes:bytes.length,uploaded:true});
  }catch(e){
    res.status(502).json({ok:false,error:e instanceof Error?e.message:String(e)});
  }
});

app.post('/demo/status',demoAuth,async(req,res)=>{
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
    const session=makeDemoSession(t.open_id||'');
    res.setHeader('Set-Cookie','fuoconero_demo='+encodeURIComponent(session)+'; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=7200');
    res.type('html').send(pageShell(`
      <div class="brand"><div class="mark">★</div><div><h1>Fuoconero Social</h1><p>Integrazione TikTok Content Posting API</p></div></div>
      <section class="card"><div class="step">Connessione</div><h2>TikTok collegato ✅</h2><p>Autorizzazione completata. Ora puoi rivedere il contenuto e decidere se pubblicarlo.</p><a class="btn" href="/demo">Continua alla demo</a></section>
    `));
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

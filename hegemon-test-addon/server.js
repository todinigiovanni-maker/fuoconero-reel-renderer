'use strict';
const http = require('node:http');
const id = 'tt1254207';
const movie = {id, type:'movie', name:'Big Buck Bunny (film completo MP4)', description:'Film pubblico diagnostico di circa 10 minuti, MP4 H.264 senza torrent.', releaseInfo:'2008'};
const seriesId = 'tt13293588';
const pageUrl = 'https://cb01uno.vip/serietv/mushoku-tensei-jobless-reincarnation/';
const show = {
  id:seriesId, type:'series', name:'Mushoku Tensei - test apertura pagina',
  description:'Test tecnico: apre una pagina web esterna, non un flusso video né Veezie.',
  releaseInfo:'2021',
  videos:[{id:seriesId+':1:1', title:'Prova collegamento esterno', season:1, episode:1, released:'2021-01-10T00:00:00.000Z'}]
};
const manifest = {
  id:'com.fuoconero.hegemon.mp4test', version:'1.0.3', name:'Fuoconero - Test MP4',
  logo:'https://www.stremio.com/website/stremio-logo-small.png',
  description:'Test MP4 funzionante e verifica apertura pagina esterna; nessun video prelevato da terzi.',
  resources:['catalog','stream'], types:['movie','series'], idPrefixes:['tt'],
  catalogs:[{type:'movie',id:'fuoconero_test',name:'Fuoconero Test MP4'}, {type:'series',id:'fuoconero_external_test',name:'Fuoconero Test collegamento web'}]
};
function send(res,code,data){
  res.writeHead(code,{'Content-Type':'application/json; charset=utf-8','Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'*','Access-Control-Allow-Methods':'GET,OPTIONS','Cache-Control':'public, max-age=30'});
  res.end(JSON.stringify(data));
}
const server = http.createServer((req,res)=>{
  const p = new URL(req.url || '/', 'http://localhost').pathname;
  console.log('[HTTP]',req.method,p,'origin='+String(req.headers.origin||''),'ua='+String(req.headers['user-agent']||'').slice(0,120));
  if(req.method==='OPTIONS') {res.writeHead(204,{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'*','Access-Control-Allow-Methods':'GET,OPTIONS'}); return res.end();}
  if(req.method!=='GET' && req.method!=='HEAD')return send(res,405,{error:'GET or HEAD only'});
  if(p==='/' || p==='/health')return send(res,200,{status:'ok',addon:manifest.name});
  if(p==='/manifest.json')return send(res,200,manifest);
  if(p==='/catalog/movie/fuoconero_test.json')return send(res,200,{metas:[movie]});
  if(p==='/catalog/series/fuoconero_external_test.json')return send(res,200,{metas:[show]});
  if(p===`/meta/series/${seriesId}.json`)return send(res,200,{meta:show});
  if(p===`/meta/movie/${id}.json`)return send(res,200,{meta:movie});
  if(p.startsWith('/stream/series/') && p.slice('/stream/series/'.length).startsWith(seriesId))return send(res,200,{streams:[{
    name:'Fuoconero - Apri pagina esterna',
    title:'Test: apre pagina della serie nel browser (non un file video)',
    externalUrl:pageUrl
  }]});
  if(p===`/stream/movie/${id}.json`)return send(res,200,{streams:[{
    name:'Fuoconero - MP4 pubblico',title:'Big Buck Bunny - film completo circa 10 minuti',
    url:'https://uploads.video-commander.com/sample/BigBuckBunny.mp4'
  }]});
  if(p.startsWith('/stream/'))return send(res,200,{streams:[]});
  if(p.startsWith('/catalog/'))return send(res,200,{metas:[]});
  return send(res,404,{error:'Not found'});
});
server.listen(Number(process.env.PORT)||10000,'0.0.0.0',()=>console.log('Fuoconero Hegemon MP4 test ready'));

setTimeout(async () => {
  try {
    const response = await fetch('https://fuoconero-hegemon-mp4-test.onrender.com/manifest.json', {signal: AbortSignal.timeout(12000)});
    console.log('[PUBLIC CHECK]',response.status,response.headers.get('content-type'),(await response.text()).slice(0,100));
  } catch(e) {console.log('[PUBLIC CHECK FAILED]',String(e));}
},8000);

setTimeout(async () => {
  try {
    const r = await fetch('https://uploads.video-commander.com/sample/BigBuckBunny.mp4', {
      method:'HEAD', signal:AbortSignal.timeout(12000)
    });
    console.log('[VIDEO CHECK]', r.status, r.headers.get('content-type'), r.headers.get('content-length'), r.headers.get('accept-ranges'));
  } catch(e) { console.log('[VIDEO CHECK FAILED]', String(e)); }
}, 9000);

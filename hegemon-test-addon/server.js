'use strict';
const http = require('node:http');
const id = 'tt1254207';
const movie = {id, type:'movie', name:'Big Buck Bunny (test MP4 di 10 secondi)', description:'Clip diagnostica MP4/H.264, senza torrent.', releaseInfo:'2008'};
const manifest = {
  id:'com.fuoconero.hegemon.mp4test', version:'1.0.1', name:'Fuoconero - Test MP4',
  logo:'https://www.stremio.com/website/stremio-logo-small.png',
  description:'Un solo MP4 pubblico di 10 secondi per testare il lettore Hegemon.',
  resources:['catalog','stream'], types:['movie'], idPrefixes:['tt'],
  catalogs:[{type:'movie',id:'fuoconero_test',name:'Fuoconero Test MP4'}]
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
  if(p===`/meta/movie/${id}.json`)return send(res,200,{meta:movie});
  if(p===`/stream/movie/${id}.json`)return send(res,200,{streams:[{
    name:'Fuoconero - MP4 pubblico',title:'Big Buck Bunny - clip 10 secondi',
    url:'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4'
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

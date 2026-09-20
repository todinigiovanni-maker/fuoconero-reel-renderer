import { parseArticle, buildReelPlan, fetchBuffer } from './blog.js';

const RENDERER_URL=process.env.RENDERER_URL||'https://fuoconero-reel-renderer-app-production.up.railway.app';
const SOCIAL_BRIDGE_URL=process.env.SOCIAL_BRIDGE_URL||'https://fuoconero-social-bridge-production.up.railway.app';
const RENDERER_SECRET=process.env.RENDERER_SECRET||'';
const PUBLISH_PIN=process.env.PUBLISH_PIN||'';
const MUSIC_URL=process.env.FUOCONERO_MUSIC_URL||'';

async function renderArticle(url){
 const article=await parseArticle(url);
 const plan=buildReelPlan(article);
 const image=await fetchBuffer(article.image);
 const music=MUSIC_URL?await fetchBuffer(MUSIC_URL):null;
 const form=new FormData();
 form.append('image',new File([image.buffer],image.name,{type:image.type}));
 if(music) form.append('music',new File([music.buffer],music.name,{type:music.type}));
 for(const [k,v] of Object.entries({category:plan.category,title:plan.title,subtitle:plan.subtitle,hook:plan.hook,keyPoint:plan.keyPoint,highlight:plan.highlight,close:plan.close,cta:plan.cta,duration:'17'})) form.append(k,String(v||''));
 const r=await fetch(RENDERER_URL+'/render-blog-url',{method:'POST',headers:{Authorization:'Bearer '+RENDERER_SECRET},body:form,signal:AbortSignal.timeout(220000)});
 const data=await r.json(); if(!r.ok||!data?.video_url) throw new Error('renderer '+r.status+' '+JSON.stringify(data).slice(-1000));
 return {article,plan,videoUrl:data.video_url};
}
async function main(){
 if(process.env.RUN_SOCIAL_ONCE!=='1'){console.log('FUOCONERO_TRIGGER_SKIPPED');return;}
 const url=String(process.env.PUBLISH_ARTICLE_URL||'');
 if(!url){console.log('FUOCONERO_TRIGGER_ERROR missing PUBLISH_ARTICLE_URL');return;}
 try{
  const {article,plan,videoUrl}=await renderArticle(url);
  const caption=process.env.PUBLISH_CAPTION||plan.caption;
  const meta=await fetch(SOCIAL_BRIDGE_URL+'/publish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({videoUrl,caption,shareToFeed:true,platform:'both',confirmed:true,pin:PUBLISH_PIN}),signal:AbortSignal.timeout(240000)});
  let mb;try{mb=await meta.json()}catch{mb={raw:'non-json'}}
  const yt=await fetch(SOCIAL_BRIDGE_URL+'/youtube/short',{method:'POST',headers:{'Content-Type':'application/json','X-Publish-Pin':PUBLISH_PIN},body:JSON.stringify({video_url:videoUrl,title:String(plan.title).slice(0,100),description:caption,tags:plan.hashtags.join(','),privacy_status:'public'}),signal:AbortSignal.timeout(300000)});
  let yb;try{yb=await yt.json()}catch{yb={raw:'non-json'}}
  console.log('FUOCONERO_TRIGGER_RESULT '+JSON.stringify({article:article.title,video_url:videoUrl,meta:{status:meta.status,body:mb},youtube:{status:yt.status,body:yb}}));
 }catch(e){console.error('FUOCONERO_TRIGGER_ERROR '+(e instanceof Error?e.message:String(e)));}
}
await main();

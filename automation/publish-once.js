import { parseArticle, buildReelPlan, fetchBuffer } from './blog.js';

const ARTICLE_URL='https://fuoconero.com/2026/09/18/fisicamente-hanno-teletrasportato-unimmagine-ma-la-vera-notizia-non-e-quella/';
const RENDERER_URL=process.env.RENDERER_URL||'https://fuoconero-reel-renderer-app-production.up.railway.app';
const RENDERER_SECRET=process.env.RENDERER_SECRET||'';
const SOCIAL_BRIDGE_URL=process.env.SOCIAL_BRIDGE_URL||'https://fuoconero-social-bridge-production.up.railway.app';
const PUBLISH_PIN=process.env.PUBLISH_PIN||'';
const MUSIC_URL=process.env.FUOCONERO_MUSIC_URL||'';

async function renderBlog(article,plan){
  const image=await fetchBuffer(article.image);
  const music=MUSIC_URL ? await fetchBuffer(MUSIC_URL) : null;
  const form=new FormData();
  form.append('image',new File([image.buffer],image.name,{type:image.type}));
  if(music) form.append('music',new File([music.buffer],music.name,{type:music.type}));
  const fields={
    category:plan.category,title:plan.title,subtitle:plan.subtitle,hook:plan.hook,
    keyPoint:plan.keyPoint,highlight:plan.highlight,close:plan.close,cta:plan.cta,duration:'17'
  };
  for(const [k,v] of Object.entries(fields)) form.append(k,String(v||''));
  const r=await fetch(RENDERER_URL+'/render-blog-url',{
    method:'POST',
    headers:{Authorization:'Bearer '+RENDERER_SECRET},
    body:form,
    signal:AbortSignal.timeout(220000)
  });
  const data=await r.json();
  if(!r.ok||!data?.ok||!data?.video_url) throw new Error('renderer '+r.status+' '+JSON.stringify(data).slice(-1500));
  return data;
}

async function main(){
  if(process.env.RUN_SOCIAL_ONCE!=='1'){
    console.log('FUOCONERO_PUBLISH_SKIPPED');
    return;
  }
  const out={article:null,video_url:null,meta:null,youtube:null};
  try{
    const article=await parseArticle(ARTICLE_URL);
    const plan=buildReelPlan(article,{});
    out.article=article.title;
    const rendered=await renderBlog(article,plan);
    out.video_url=rendered.video_url;

    const meta=await fetch(SOCIAL_BRIDGE_URL+'/publish',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({
        videoUrl:rendered.video_url,
        caption:plan.caption,
        shareToFeed:true,
        platform:'both',
        confirmed:true,
        pin:PUBLISH_PIN
      }),
      signal:AbortSignal.timeout(240000)
    });
    let metaBody; try{metaBody=await meta.json();}catch{metaBody={raw:'non-json'};}
    out.meta={status:meta.status,body:metaBody};

    const yt=await fetch(SOCIAL_BRIDGE_URL+'/youtube/short',{
      method:'POST',
      headers:{'Content-Type':'application/json','X-Publish-Pin':PUBLISH_PIN},
      body:JSON.stringify({
        video_url:rendered.video_url,
        title:String(plan.title).slice(0,100),
        description:plan.caption,
        tags:plan.hashtags.join(','),
        privacy_status:'public'
      }),
      signal:AbortSignal.timeout(300000)
    });
    let ytBody; try{ytBody=await yt.json();}catch{ytBody={raw:'non-json'};}
    out.youtube={status:yt.status,body:ytBody};
  }catch(e){
    out.error=e instanceof Error?e.message:String(e);
  }
  console.log('FUOCONERO_PUBLISH_RESULT '+JSON.stringify(out));
}

await main();

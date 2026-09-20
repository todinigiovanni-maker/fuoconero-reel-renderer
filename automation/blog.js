function htmlText(value='') {
  return String(value)
    .replace(/<script[\s\S]*?<\/script>/gi,' ')
    .replace(/<style[\s\S]*?<\/style>/gi,' ')
    .replace(/<br\s*\/?\s*>/gi,'\n')
    .replace(/<\/p>/gi,'\n')
    .replace(/<\/h[1-6]>/gi,'\n')
    .replace(/<[^>]+>/g,' ')
    .replace(/&nbsp;/gi,' ')
    .replace(/&amp;/gi,'&')
    .replace(/&quot;/gi,'"')
    .replace(/&#039;|&apos;/gi,"'")
    .replace(/&lt;/gi,'<')
    .replace(/&gt;/gi,'>')
    .replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n)))
    .replace(/[ \t]+/g,' ')
    .replace(/\n\s*\n+/g,'\n')
    .trim();
}

function cleanTitle(s='') {
  return htmlText(s).replace(/\s*[|–-]\s*Momenti che diventano parole\s*$/i,'').trim();
}

function splitSentences(text='') {
  return String(text)
    .replace(/\n+/g,' ')
    .split(/(?<=[.!?])\s+/)
    .map(s=>s.trim())
    .filter(s=>s.length>=25 && s.length<=260);
}

function short(s,max=150) {
  s=String(s||'').replace(/\s+/g,' ').trim();
  if(s.length<=max) return s;
  return s.slice(0,max-1).replace(/\s+\S*$/,'')+'…';
}

function urlSlug(articleUrl) {
  const u=new URL(articleUrl);
  if(!/(^|\.)fuoconero\.com$/i.test(u.hostname)) throw new Error('URL non appartenente a fuoconero.com');
  const parts=u.pathname.split('/').filter(Boolean);
  const slug=parts.at(-1);
  if(!slug) throw new Error('Slug articolo mancante');
  return {url:u.toString(),slug};
}

async function fetchJson(url,timeout=25000) {
  const r=await fetch(url,{headers:{'User-Agent':'FuoconeroAutomation/1.1'},signal:AbortSignal.timeout(timeout)});
  if(!r.ok) throw new Error('HTTP '+r.status+' su '+url);
  return r.json();
}

export async function fetchBuffer(url,timeout=45000) {
  const r=await fetch(url,{headers:{'User-Agent':'FuoconeroAutomation/1.1'},signal:AbortSignal.timeout(timeout)});
  if(!r.ok) throw new Error('HTTP '+r.status+' scaricando asset');
  const type=r.headers.get('content-type')||'application/octet-stream';
  return {
    buffer:Buffer.from(await r.arrayBuffer()),
    type,
    name:new URL(url).pathname.split('/').filter(Boolean).at(-1)||'asset'
  };
}

export async function parseArticle(articleUrl) {
  const {url,slug}=urlSlug(articleUrl);
  const api='https://fuoconero.com/wp-json/wp/v2/posts?slug='+encodeURIComponent(slug)+'&_embed=1';
  const posts=await fetchJson(api);
  if(!Array.isArray(posts)||!posts[0]) throw new Error('Articolo non trovato via WordPress REST API');
  const p=posts[0];
  const terms=p?._embedded?.['wp:term']?.flat?.()||[];
  const categories=terms.filter(t=>t?.taxonomy==='category').map(t=>htmlText(t.name)).filter(Boolean);
  const featured=p?._embedded?.['wp:featuredmedia']?.[0];
  const image=featured?.source_url||
    featured?.media_details?.sizes?.full?.source_url||
    featured?.media_details?.sizes?.large?.source_url||'';
  return {
    id:p.id,
    url:p.link||url,
    slug,
    date:p.date||null,
    title:cleanTitle(p?.title?.rendered||slug.replace(/-/g,' ')),
    categories,
    category:categories[0]||'FUOCONERO',
    image,
    image_alt:featured?.alt_text||'',
    excerpt:htmlText(p?.excerpt?.rendered||''),
    content:htmlText(p?.content?.rendered||'')
  };
}

export function buildReelPlan(article,override={}) {
  const source=(article.excerpt&&article.excerpt.length>70?article.excerpt:article.content).trim();
  const sentences=splitSentences(source);
  const title=override.title||article.title;
  const subtitle=override.subtitle||(title.includes('—')?title.split('—').slice(1).join('—').trim():'Momenti che diventano parole');
  const hook=override.hook||short(sentences[0]||article.excerpt||article.content,130);
  const keyPoint=override.keyPoint||short(sentences[1]||sentences[0]||'',135);
  const highlight=override.highlight||short(
    sentences.find(s=>/ricord|cervell|archiv|cambia|vita|mondo|anim|natura|fisic/i.test(s))||
    sentences[2]||keyPoint,155
  );
  const close=override.close||short(sentences.at(-1)||highlight,145);
  const cta=override.cta||'Leggi l’articolo completo su fuoconero.com';
  const voiceover=override.voiceover||[hook,keyPoint,highlight,close].filter(Boolean).join(' ');
  const caption=override.caption||(hook+'\n\nLeggi l’articolo completo su fuoconero.com');
  const hashtags=override.hashtags||[
    'Fuoconero',
    article.category.replace(/[^\p{L}\p{N}]/gu,''),
    'Blog',
    'Reel'
  ].filter(Boolean);
  return {
    category:override.category||article.category,
    title,subtitle,hook,keyPoint,highlight,close,cta,
    voiceover:short(voiceover,430),
    caption,
    hashtags
  };
}

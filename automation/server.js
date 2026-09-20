import express from 'express';
import multer from 'multer';
import { promises as fs } from 'node:fs';
import { parseArticle, buildReelPlan, fetchBuffer } from './blog.js';

const app = express();
app.use(express.json({ limit: '2mb' }));
const upload = multer({ dest: '/tmp/uploads', limits: { fileSize: 50 * 1024 * 1024 } });

const PORT = process.env.PORT || 8080;
const RENDERER_URL =
  process.env.RENDERER_URL ||
  'https://fuoconero-reel-renderer-app-production.up.railway.app';
const RENDERER_SECRET = process.env.RENDERER_SECRET || '';
const SOCIAL_BRIDGE_URL =
  process.env.SOCIAL_BRIDGE_URL ||
  'https://fuoconero-social-bridge-production.up.railway.app';
const PUBLISH_PIN = process.env.PUBLISH_PIN || '';
const AUTOMATION_SECRET = process.env.AUTOMATION_SECRET || '';
const FUOCONERO_MUSIC_URL = process.env.FUOCONERO_MUSIC_URL || '';

function detail(error) {
  return error instanceof Error
    ? (error.name + ': ' + error.message).slice(0, 500)
    : String(error).slice(0, 500);
}

function auth(req, res, next) {
  if (!AUTOMATION_SECRET || req.get('authorization') !== 'Bearer ' + AUTOMATION_SECRET) {
    return res.status(401).json({ ok: false, error: 'Non autorizzato' });
  }
  next();
}

async function render({ imageBuffer, imageName, imageType, audioBuffer, audioName, audioType, title, subtitle, duration }) {
  if (!RENDERER_SECRET) throw new Error('RENDERER_SECRET mancante');

  const form = new FormData();
  form.append('image', new File([imageBuffer], imageName || 'image', {
    type: imageType || 'application/octet-stream'
  }));

  if (audioBuffer) {
    form.append('audio', new File([audioBuffer], audioName || 'audio', {
      type: audioType || 'application/octet-stream'
    }));
  }

  form.append('title', title || 'FUOCONERO');
  form.append('subtitle', subtitle || 'fuoconero.com');
  form.append('duration', String(duration || 12));

  const response = await fetch(RENDERER_URL + '/render-url', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + RENDERER_SECRET },
    body: form,
    signal: AbortSignal.timeout(180000)
  });

  const payload = await response.json();
  if (!response.ok || !payload?.ok || !payload?.video_url) {
    const rendererErrorRaw =
      typeof payload?.error === 'string'
        ? payload.error.slice(-2500)
        : JSON.stringify(payload).slice(-2500);
    const rendererError = rendererErrorRaw
      .replace(/\r/g, ' ')
      .replace(/\n/g, ' | ')
      .replace(/\s+/g, ' ')
      .slice(-1800);
    throw new Error('Renderer HTTP ' + response.status + ': ' + rendererError);
  }
  return payload;
}


async function renderBlog({ article, plan, voiceUrl, musicUrl, duration = 17 }) {
  if (!RENDERER_SECRET) throw new Error('RENDERER_SECRET mancante');
  if (!article.image) throw new Error('Immagine in evidenza mancante');

  const image = await fetchBuffer(article.image);
  const voice = voiceUrl ? await fetchBuffer(voiceUrl) : null;
  const isBuiltinMusic = musicUrl === 'builtin:fuoconero';
  const isYoutubeMusic = Boolean(musicUrl && !isBuiltinMusic && /^https:\/\/(?:www\.|m\.)?(?:youtube\.com|youtu\.be)\//i.test(musicUrl));
  const music = musicUrl && !isYoutubeMusic && !isBuiltinMusic ? await fetchBuffer(musicUrl) : null;

  const form = new FormData();
  form.append('image', new File([image.buffer], image.name, { type: image.type }));
  if (voice) form.append('voice', new File([voice.buffer], voice.name, { type: voice.type }));
  if (music) form.append('music', new File([music.buffer], music.name, { type: music.type }));
  if (isYoutubeMusic) form.append('musicUrl', musicUrl);
  if (isBuiltinMusic) form.append('useDefaultMusic', '1');

  const fields = {
    category: plan.category,
    title: plan.title,
    subtitle: plan.subtitle,
    hook: plan.hook,
    keyPoint: plan.keyPoint,
    highlight: plan.highlight,
    close: plan.close,
    cta: plan.cta,
    duration: String(duration)
  };
  for (const [key, value] of Object.entries(fields)) form.append(key, String(value || ''));

  const response = await fetch(RENDERER_URL + '/render-blog-url', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + RENDERER_SECRET },
    body: form,
    signal: AbortSignal.timeout(220000)
  });
  const payload = await response.json();
  if (!response.ok || !payload?.ok || !payload?.video_url) {
    throw new Error('Blog renderer HTTP ' + response.status + ': ' + JSON.stringify(payload).slice(-2200));
  }
  return payload;
}

async function publishRendered({ videoUrl, caption, platform = 'none', youtube = false, youtubeTitle = 'FUOCONERO', youtubeDescription = '', youtubeTags = '' }) {
  if (!PUBLISH_PIN) throw new Error('PUBLISH_PIN non configurato');
  const results = {};

  if (['instagram', 'facebook', 'both'].includes(platform)) {
    const meta = await fetch(SOCIAL_BRIDGE_URL + '/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        videoUrl,
        caption,
        shareToFeed: true,
        platform,
        confirmed: true,
        pin: PUBLISH_PIN
      }),
      signal: AbortSignal.timeout(180000)
    });
    results.meta = { status: meta.status, body: await meta.json() };
  }

  if (youtube) {
    const yt = await fetch(SOCIAL_BRIDGE_URL + '/youtube/short', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Publish-Pin': PUBLISH_PIN },
      body: JSON.stringify({
        video_url: videoUrl,
        title: String(youtubeTitle).slice(0, 100),
        description: youtubeDescription || caption,
        tags: youtubeTags,
        privacy_status: 'public'
      }),
      signal: AbortSignal.timeout(300000)
    });
    results.youtube = { status: yt.status, body: await yt.json() };
  }

  return results;
}


app.get('/blog/preview', async (req, res) => {
  try {
    const url = String(req.query.url || '');
    if (!url) return res.status(400).json({ ok: false, error: 'Parametro url mancante' });

    const article = await parseArticle(url);
    const reel = buildReelPlan(article);

    return res.json({
      ok: true,
      article: {
        id: article.id,
        url: article.url,
        date: article.date,
        title: article.title,
        category: article.category,
        categories: article.categories,
        image: article.image,
        image_alt: article.image_alt,
        excerpt: article.excerpt
      },
      reel,
      published: false,
      version: '1.1.0'
    });
  } catch (error) {
    return res.status(502).json({
      ok: false,
      stage: 'blog-preview',
      error: 'Impossibile leggere l’articolo',
      detail: detail(error)
    });
  }
});

app.post('/blog-reel', auth, async (req, res) => {
  try {
    const url = String(req.body?.url || '');
    if (!url) return res.status(400).json({ ok: false, error: 'url mancante' });

    const article = await parseArticle(url);
    const plan = buildReelPlan(article, req.body?.reel || {});
    const rendered = await renderBlog({
      article,
      plan,
      voiceUrl: String(req.body?.voice_url || ''),
      musicUrl: String(req.body?.music_url || FUOCONERO_MUSIC_URL || ''),
      duration: Math.min(Math.max(Number(req.body?.duration) || 17, 10), 30)
    });

    const publish = req.body?.publish === true;
    if (!publish) {
      return res.json({
        ok: true,
        source_url: article.url,
        article: { title: article.title, category: article.category, image: article.image },
        reel: plan,
        video_url: rendered.video_url,
        expires_in_seconds: rendered.expires_in_seconds || 3600,
        audio: rendered.audio || {},
        published: false
      });
    }

    if (req.body?.confirmed !== true) {
      return res.status(400).json({
        ok: false,
        rendered: true,
        video_url: rendered.video_url,
        error: 'Pubblicazione non confermata'
      });
    }

    const platform = String(req.body?.platform || 'none').toLowerCase();
    const youtube = req.body?.youtube === true;
    const results = await publishRendered({
      videoUrl: rendered.video_url,
      caption: plan.caption,
      platform,
      youtube,
      youtubeTitle: plan.title,
      youtubeDescription: plan.caption,
      youtubeTags: plan.hashtags.join(',')
    });

    return res.json({
      ok: true,
      source_url: article.url,
      reel: plan,
      video_url: rendered.video_url,
      published: true,
      results
    });
  } catch (error) {
    return res.status(502).json({
      ok: false,
      stage: 'blog-reel',
      error: 'Blog reel automation failed',
      detail: detail(error)
    });
  }
});

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'fuoconero-automation', version: '1.1.0' });
});

app.get('/selftest', async (_req, res) => {
  try {
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAFElEQVR4nGMUEBBhgAEmBiSAmwMACygAPDOMYd8AAAAASUVORK5CYII=',
      'base64'
    );

    const rendered = await render({
      imageBuffer: png,
      imageName: 'selftest.png',
      imageType: 'image/png',
      audioBuffer: null,
      title: 'FUOCONERO',
      subtitle: 'fuoconero.com',
      duration: 3
    });

    const videoResponse = await fetch(rendered.video_url, {
      signal: AbortSignal.timeout(120000)
    });
    if (!videoResponse.ok) {
      return res.status(502).json({ ok: false, stage: 'public-video', status: videoResponse.status });
    }

    const bytes = await videoResponse.arrayBuffer();
    return res.json({
      ok: true,
      stage: 'automation',
      renderer: true,
      authenticated_renderer: true,
      public_video: true,
      video_url: rendered.video_url,
      renderer_bytes: rendered.bytes || null,
      fetched_bytes: bytes.byteLength,
      width: rendered.width || 1080,
      height: rendered.height || 1920,
      codec: rendered.codec || 'h264',
      published: false,
      version: '1.1.0'
    });
  } catch (error) {
    return res.status(502).json({
      ok: false,
      stage: 'automation',
      error: 'Self-test failed',
      detail: detail(error)
    });
  }
});

app.post('/reel', auth, upload.fields([
  { name: 'image', maxCount: 1 },
  { name: 'audio', maxCount: 1 }
]), async (req, res) => {
  const image = req.files?.image?.[0];
  const audio = req.files?.audio?.[0];
  if (!image) return res.status(400).json({ ok: false, error: 'Immagine mancante' });

  try {
    const imageBuffer = await fs.readFile(image.path);
    const audioBuffer = audio ? await fs.readFile(audio.path) : null;

    const rendered = await render({
      imageBuffer,
      imageName: image.originalname,
      imageType: image.mimetype,
      audioBuffer,
      audioName: audio?.originalname,
      audioType: audio?.mimetype,
      title: String(req.body.title || 'FUOCONERO').slice(0, 120),
      subtitle: String(req.body.subtitle || 'fuoconero.com').slice(0, 160),
      duration: Math.min(Math.max(Number(req.body.duration) || 12, 3), 60)
    });

    const publish = String(req.body.publish || 'false').toLowerCase() === 'true';
    if (!publish) {
      return res.json({
        ok: true,
        rendered: true,
        video_url: rendered.video_url,
        expires_in_seconds: rendered.expires_in_seconds || 3600,
        bytes: rendered.bytes || null,
        width: rendered.width || 1080,
        height: rendered.height || 1920,
        codec: rendered.codec || 'h264',
        published: false
      });
    }

    const confirmed = String(req.body.confirmed || 'false').toLowerCase() === 'true';
    if (!confirmed) {
      return res.status(400).json({
        ok: false,
        rendered: true,
        video_url: rendered.video_url,
        error: 'Pubblicazione non confermata'
      });
    }

    if (!PUBLISH_PIN) {
      return res.status(500).json({ ok: false, error: 'PUBLISH_PIN non configurato' });
    }

    const results = {};
    const platform = String(req.body.platform || 'none').toLowerCase();
    const caption = String(req.body.caption || '');
    const youtube = String(req.body.youtube || 'false').toLowerCase() === 'true';

    if (['instagram', 'facebook', 'both'].includes(platform)) {
      const meta = await fetch(SOCIAL_BRIDGE_URL + '/publish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          videoUrl: rendered.video_url,
          caption,
          shareToFeed: true,
          platform,
          confirmed: true,
          pin: PUBLISH_PIN
        }),
        signal: AbortSignal.timeout(180000)
      });
      results.meta = { status: meta.status, body: await meta.json() };
    }

    if (youtube) {
      const yt = await fetch(SOCIAL_BRIDGE_URL + '/youtube/short', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Publish-Pin': PUBLISH_PIN
        },
        body: JSON.stringify({
          video_url: rendered.video_url,
          title: String(req.body.youtube_title || req.body.title || 'FUOCONERO').slice(0, 100),
          description: String(req.body.youtube_description || caption),
          tags: String(req.body.youtube_tags || '')
        }),
        signal: AbortSignal.timeout(300000)
      });
      results.youtube = { status: yt.status, body: await yt.json() };
    }

    return res.json({
      ok: true,
      rendered: true,
      video_url: rendered.video_url,
      published: true,
      results
    });
  } catch (error) {
    return res.status(502).json({
      ok: false,
      stage: 'automation',
      error: 'Automation failed',
      detail: detail(error)
    });
  } finally {
    await Promise.allSettled([
      image ? fs.unlink(image.path) : Promise.resolve(),
      audio ? fs.unlink(audio.path) : Promise.resolve()
    ]);
  }
});

async function startupSelftest() {
  try {
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAFElEQVR4nGMUEBBhgAEmBiSAmwMACygAPDOMYd8AAAAASUVORK5CYII=',
      'base64'
    );

    const rendered = await render({
      imageBuffer: png,
      imageName: 'startup-selftest.png',
      imageType: 'image/png',
      audioBuffer: null,
      title: 'FUOCONERO',
      subtitle: 'fuoconero.com',
      duration: 3
    });

    const videoResponse = await fetch(rendered.video_url, {
      signal: AbortSignal.timeout(120000)
    });

    if (!videoResponse.ok) {
      throw new Error('Public video HTTP ' + videoResponse.status);
    }

    const bytes = await videoResponse.arrayBuffer();
    console.log(
      'AUTOMATION_SELFTEST_OK ' +
      JSON.stringify({
        bytes: bytes.byteLength,
        rendererBytes: rendered.bytes || null,
        width: rendered.width || 1080,
        height: rendered.height || 1920,
        codec: rendered.codec || 'h264'
      })
    );

    try {
      const article = await parseArticle(
        'https://fuoconero.com/2026/09/14/pruriti-quando-imparare-significa-modificare-la-carne/'
      );
      const plan = buildReelPlan(article, {
        category: 'POESIE',
        title: 'PRURITI',
        subtitle: 'Quando imparare significa modificare la carne',
        hook: 'Imparare non significa soltanto sapere qualcosa in più.',
        keyPoint: 'Significa cambiare fisicamente il cervello.',
        highlight: 'I ricordi non stanno dentro un archivio. In parte, sono l’archivio.',
        close: 'E certi pruriti spariscono solo quando li scriviamo.',
        cta: 'Leggi l’articolo completo su fuoconero.com'
      });
      const blogRendered = await renderBlog({
        article,
        plan,
        voiceUrl: process.env.BLOG_TEST_VOICE_URL || '',
        musicUrl: process.env.BLOG_TEST_MUSIC_URL || '',
        duration: 17.6
      });
      const blogVideo = await fetch(blogRendered.video_url, {
        signal: AbortSignal.timeout(120000)
      });
      if (!blogVideo.ok) throw new Error('Blog public video HTTP ' + blogVideo.status);
      const blogBytes = await blogVideo.arrayBuffer();
      console.log(
        'BLOG_SELFTEST_OK ' +
        JSON.stringify({
          title: article.title,
          category: plan.category,
          hasImage: Boolean(article.image),
          videoUrl: blogRendered.video_url,
          bytes: blogBytes.byteLength,
          width: blogRendered.width || 1080,
          height: blogRendered.height || 1920,
          audio: blogRendered.audio || {},
          published: false
        })
      );
    } catch (blogError) {
      console.error('BLOG_SELFTEST_FAILED ' + detail(blogError));
    }
  } catch (error) {
    console.error('AUTOMATION_SELFTEST_FAILED ' + detail(error));
  }
}


app.listen(PORT, () => {
  console.log('fuoconero automation listening on ' + PORT);
  startupSelftest();
  if (PUBLISH_PIN) {
    (async () => {
      const videoUrl = 'https://d2jqrm6oza8nb6.cloudfront.net/datasets/11d9d99a-6556-4986-95a7-b2f5309b44f8.mp4?_jwt=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJrZXlIYXNoIjoiNmYxODQyODE3M2MxYTAzMSIsImJ1Y2tldCI6InJ1bndheS1kYXRhc2V0cyIsInN0YWdlIjoicHJvZCIsImV4cCI6MTc5MDA3NTI2NX0.bxgGb3lho96aeK8eb5odbXbBWv0DQ8yHK2_g_c_rfXo';
      const caption = 'PRURITI — Quando imparare significa modificare la carne.\\n\\nImparare non significa soltanto sapere qualcosa in più. Significa cambiare fisicamente il cervello. I ricordi non stanno dentro un archivio. In parte, sono l\\'archivio.\\n\\nLeggi l\\'articolo completo su fuoconero.com\\n\\n#Fuoconero #Pruriti #Poesie #Neuroscienze #Scrittura';
      try {
        const results = await publishRendered({
          videoUrl,
          caption,
          platform: 'both',
          youtube: true,
          youtubeTitle: 'PRURITI | Fuoconero',
          youtubeDescription: caption,
          youtubeTags: 'Fuoconero,Pruriti,Poesie,Neuroscienze,Scrittura'
        });
        console.log('PRURITI_SOCIAL_TEST_DIRECT ' + JSON.stringify(results).slice(0,5000));
      } catch (e) {
        console.error('PRURITI_SOCIAL_TEST_DIRECT_FAILED ' + detail(e));
      }
    })();
  }



});

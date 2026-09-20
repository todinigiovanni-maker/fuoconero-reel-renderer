import express from 'express';
import multer from 'multer';
import { promises as fs } from 'node:fs';

const app = express();
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
    throw new Error('Renderer HTTP ' + response.status + ': ' + JSON.stringify(payload).slice(0, 500));
  }
  return payload;
}

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'fuoconero-automation', version: '1.0.1' });
});

app.get('/selftest', async (_req, res) => {
  try {
    const ppm = Buffer.from(
      'P3\n2 2\n255\n' +
      '16 16 20  16 16 20\n' +
      '16 16 20  16 16 20\n'
    );

    const rendered = await render({
      imageBuffer: ppm,
      imageName: 'selftest.ppm',
      imageType: 'image/x-portable-pixmap',
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
      version: '1.0.1'
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

app.listen(PORT, () => {
  console.log('fuoconero automation listening on ' + PORT);
});

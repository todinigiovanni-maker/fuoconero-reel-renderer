/** @jsx jsx */
/** @jsxImportSource hono/jsx */
import { Hono } from "hono";

const app = new Hono();

const IG_BASE = "https://graph.instagram.com/v26.0";
const FB_BASE = "https://graph.facebook.com/v26.0";
const YOUTUBE_TOKEN_FILE = "/data/youtube-oauth.json";
const FUOCONERO_MUSIC_FILE = "/data/fuoconero-music-carrier.mp4";
const YOUTUBE_STATE_MAX_AGE_MS = 10 * 60 * 1000;
const PUBLIC_BASE = "https://fuoconero-social-bridge-production.up.railway.app";

function safeError(e: unknown): string {
  return e instanceof Error ? e.message.slice(0, 900) : String(e).slice(0, 900);
}

function checkBearer(c: any): boolean {
  const secret = Bun.env.BRIDGE_SECRET || "";
  return Boolean(secret && c.req.header("Authorization") === "Bearer " + secret);
}

function checkPin(value: string): boolean {
  const pin = Bun.env.PUBLISH_PIN || "";
  return Boolean(pin && value && pin.length === value.length && [...value].every((ch, i) => ch === pin[i]));
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlToBytes(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function getHmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"]
  );
}

async function createYoutubeState(secret: string): Promise<string> {
  const nonce = new Uint8Array(24);
  crypto.getRandomValues(nonce);
  const payload = JSON.stringify({ ts: Date.now(), nonce: bytesToBase64Url(nonce) });
  const payloadPart = bytesToBase64Url(new TextEncoder().encode(payload));
  const key = await getHmacKey(secret);
  const signature = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payloadPart))
  );
  return payloadPart + "." + bytesToBase64Url(signature);
}

async function validateYoutubeState(state: string, secret: string): Promise<boolean> {
  try {
    const parts = state.split(".");
    if (parts.length !== 2) return false;
    const [payloadPart, signaturePart] = parts;
    const key = await getHmacKey(secret);
    const valid = await crypto.subtle.verify(
      "HMAC",
      key,
      base64UrlToBytes(signaturePart),
      new TextEncoder().encode(payloadPart)
    );
    if (!valid) return false;
    const payload = JSON.parse(new TextDecoder().decode(base64UrlToBytes(payloadPart))) as { ts?: number };
    if (typeof payload.ts !== "number") return false;
    const age = Date.now() - payload.ts;
    return age >= 0 && age <= YOUTUBE_STATE_MAX_AGE_MS;
  } catch {
    return false;
  }
}

async function getYoutubeEncryptionKey(secret: string): Promise<CryptoKey> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  return crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function storeYoutubeRefreshToken(refreshToken: string, secret: string): Promise<void> {
  const key = await getYoutubeEncryptionKey(secret);
  const iv = new Uint8Array(12);
  crypto.getRandomValues(iv);
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      key,
      new TextEncoder().encode(refreshToken)
    )
  );
  await Bun.write(
    YOUTUBE_TOKEN_FILE,
    JSON.stringify({
      version: 1,
      algorithm: "AES-GCM",
      iv: bytesToBase64Url(iv),
      ciphertext: bytesToBase64Url(ciphertext),
      updatedAt: new Date().toISOString()
    })
  );
}

async function loadYoutubeRefreshToken(secret: string): Promise<string> {
  const file = Bun.file(YOUTUBE_TOKEN_FILE);
  if (!(await file.exists())) throw new Error("YouTube non collegato");
  const stored = (await file.json()) as { version?: number; algorithm?: string; iv?: string; ciphertext?: string };
  if (stored.version !== 1 || stored.algorithm !== "AES-GCM" || !stored.iv || !stored.ciphertext) {
    throw new Error("Credenziale YouTube non valida");
  }
  const key = await getYoutubeEncryptionKey(secret);
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: base64UrlToBytes(stored.iv) },
    key,
    base64UrlToBytes(stored.ciphertext)
  );
  return new TextDecoder().decode(plaintext);
}

async function getYoutubeAccessToken(): Promise<string> {
  const clientId = Bun.env.YOUTUBE_CLIENT_ID;
  const clientSecret = Bun.env.YOUTUBE_CLIENT_SECRET;
  const bridgeSecret = Bun.env.BRIDGE_SECRET;
  if (!clientId || !clientSecret || !bridgeSecret) throw new Error("Configurazione YouTube incompleta");

  const refreshToken = await loadYoutubeRefreshToken(bridgeSecret);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      refresh_token: refreshToken,
      grant_type: "refresh_token"
    }),
    signal: AbortSignal.timeout(30000)
  });
  if (!response.ok) throw new Error("Impossibile aggiornare l'autorizzazione YouTube");
  const data = (await response.json()) as { access_token?: string };
  if (!data.access_token) throw new Error("Access token YouTube mancante");
  return data.access_token;
}

async function fetchVideo(videoUrl: string): Promise<{ bytes: Uint8Array; contentType: string }> {
  const response = await fetch(videoUrl, {
    redirect: "follow",
    signal: AbortSignal.timeout(120000)
  });
  if (!response.ok) throw new Error("Download video HTTP " + response.status);
  const contentType = (response.headers.get("content-type") || "video/mp4").split(";")[0].trim();
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (!bytes.length) throw new Error("Video vuoto");
  if (bytes.length > 250 * 1024 * 1024) throw new Error("Video troppo grande per il bridge");
  return { bytes, contentType: contentType.startsWith("video/") ? contentType : "video/mp4" };
}

async function uploadYoutubeBytes(
  bytes: Uint8Array,
  contentType: string,
  title: string,
  description: string,
  tags: string[],
  privacyStatus: "public" | "private" | "unlisted" = "private"
): Promise<{ id: string }> {
  const accessToken = await getYoutubeAccessToken();
  const initUrl = new URL("https://www.googleapis.com/upload/youtube/v3/videos");
  initUrl.searchParams.set("uploadType", "resumable");
  initUrl.searchParams.set("part", "snippet,status");

  const init = await fetch(initUrl.toString(), {
    method: "POST",
    headers: {
      Authorization: "Bearer " + accessToken,
      "Content-Type": "application/json; charset=UTF-8",
      "X-Upload-Content-Length": String(bytes.byteLength),
      "X-Upload-Content-Type": contentType
    },
    body: JSON.stringify({
      snippet: {
        title: title.slice(0, 100),
        description,
        tags: tags.slice(0, 30),
        categoryId: "22"
      },
      status: {
        privacyStatus,
        selfDeclaredMadeForKids: false
      }
    }),
    signal: AbortSignal.timeout(60000)
  });

  if (!init.ok) {
    throw new Error("YouTube init HTTP " + init.status + ": " + (await init.text()).slice(-700));
  }
  const location = init.headers.get("location");
  if (!location) throw new Error("YouTube upload URL mancante");

  const uploaded = await fetch(location, {
    method: "PUT",
    headers: {
      "Content-Type": contentType,
      "Content-Length": String(bytes.byteLength)
    },
    body: bytes,
    signal: AbortSignal.timeout(300000)
  });
  const data = (await uploaded.json()) as { id?: string; error?: { message?: string } };
  if (!uploaded.ok || !data.id) {
    throw new Error("YouTube upload HTTP " + uploaded.status + ": " + (data.error?.message || "ID mancante"));
  }
  return { id: data.id };
}

async function publishToInstagram(videoUrl: string, caption: string, shareToFeed: boolean) {
  const accessToken = Bun.env.INSTAGRAM_ACCESS_TOKEN;
  const userId = Bun.env.INSTAGRAM_USER_ID;
  if (!accessToken || !userId) return { success: false, error: "Configurazione Instagram non valida" };

  try {
    const createUrl = new URL(IG_BASE + "/" + userId + "/media");
    createUrl.searchParams.set("video_url", videoUrl);
    createUrl.searchParams.set("media_type", "REELS");
    createUrl.searchParams.set("caption", caption);
    createUrl.searchParams.set("share_to_feed", String(shareToFeed));
    createUrl.searchParams.set("access_token", accessToken);

    const create = await fetch(createUrl.toString(), { method: "POST", signal: AbortSignal.timeout(30000) });
    const created = (await create.json()) as any;
    if (!create.ok || !created.id) {
      return { success: false, error: "Instagram create: " + (created.error?.message || "HTTP " + create.status) };
    }

    const creationId = String(created.id);
    let status = "IN_PROGRESS";
    for (let i = 0; i < 60; i++) {
      const statusUrl = new URL(IG_BASE + "/" + creationId);
      statusUrl.searchParams.set("fields", "status_code,status");
      statusUrl.searchParams.set("access_token", accessToken);
      const check = await fetch(statusUrl.toString(), { signal: AbortSignal.timeout(30000) });
      const state = (await check.json()) as any;
      status = String(state.status_code || state.status || "UNKNOWN");
      if (status === "FINISHED") break;
      if (["ERROR", "EXPIRED"].includes(status)) {
        return { success: false, error: "Instagram processing: " + status };
      }
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    if (status !== "FINISHED") return { success: false, error: "Timeout elaborazione Instagram" };

    const publishUrl = new URL(IG_BASE + "/" + userId + "/media_publish");
    publishUrl.searchParams.set("creation_id", creationId);
    publishUrl.searchParams.set("access_token", accessToken);
    const publish = await fetch(publishUrl.toString(), { method: "POST", signal: AbortSignal.timeout(30000) });
    const result = (await publish.json()) as any;
    if (!publish.ok || !result.id) {
      return { success: false, error: "Instagram publish: " + (result.error?.message || "HTTP " + publish.status) };
    }
    return { success: true, mediaId: String(result.id) };
  } catch (e) {
    return { success: false, error: safeError(e) };
  }
}

async function publishToFacebook(videoUrl: string, description: string) {
  const pageAccessToken = Bun.env.FACEBOOK_PAGE_ACCESS_TOKEN;
  if (!pageAccessToken) return { success: false, error: "Configurazione Facebook non valida" };

  try {
    const startUrl = new URL(FB_BASE + "/me/video_reels");
    startUrl.searchParams.set("upload_phase", "start");
    startUrl.searchParams.set("access_token", pageAccessToken);
    const start = await fetch(startUrl.toString(), { method: "POST", signal: AbortSignal.timeout(30000) });
    const started = (await start.json()) as any;
    if (!start.ok || !started.upload_url || !started.video_id) {
      return { success: false, error: "Facebook start: " + (started.error?.message || "HTTP " + start.status) };
    }

    const upload = await fetch(String(started.upload_url), {
      method: "POST",
      headers: {
        Authorization: "OAuth " + pageAccessToken,
        file_url: videoUrl
      },
      signal: AbortSignal.timeout(60000)
    });
    if (!upload.ok) {
      let detail = "HTTP " + upload.status;
      try { detail += ": " + JSON.stringify(await upload.json()).slice(-700); } catch {}
      return { success: false, error: "Facebook upload: " + detail };
    }

    const finishUrl = new URL(FB_BASE + "/me/video_reels");
    finishUrl.searchParams.set("upload_phase", "finish");
    finishUrl.searchParams.set("video_state", "PUBLISHED");
    finishUrl.searchParams.set("video_id", String(started.video_id));
    finishUrl.searchParams.set("description", description);
    finishUrl.searchParams.set("access_token", pageAccessToken);
    const finish = await fetch(finishUrl.toString(), { method: "POST", signal: AbortSignal.timeout(30000) });
    const done = (await finish.json()) as any;
    if (!finish.ok) {
      return { success: false, error: "Facebook finish: " + (done.error?.message || "HTTP " + finish.status) };
    }
    return { success: true, mediaId: String(done.video_id || done.id || started.video_id) };
  } catch (e) {
    return { success: false, error: safeError(e) };
  }
}

app.get("/health", (c) => c.json({ ok: true, service: "fuoconero-social-bridge", version: "3.0.0" }));

async function ensureFuoconeroMusicAsset(): Promise<void> {
  const file = Bun.file(FUOCONERO_MUSIC_FILE);
  if (await file.exists() && file.size > 35000) return;
  const seed = Bun.env.FUOCONERO_MUSIC_SEED_URL || "";
  if (!seed) throw new Error("Seed musica Fuoconero mancante");
  const response = await fetch(seed, { redirect: "follow", signal: AbortSignal.timeout(120000) });
  if (!response.ok) throw new Error("Download seed musica HTTP " + response.status);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.byteLength < 35000) throw new Error("Seed musica Fuoconero incompleto");
  await Bun.write(FUOCONERO_MUSIC_FILE, bytes);
}

app.get("/assets/fuoconero-music.mp4", async (c) => {
  try {
    await ensureFuoconeroMusicAsset();
    const file = Bun.file(FUOCONERO_MUSIC_FILE);
    return new Response(file, {
      headers: {
        "Content-Type": "video/mp4",
        "Content-Length": String(file.size),
        "Cache-Control": "public, max-age=3600"
      }
    });
  } catch (e) {
    return c.json({ ok: false, error: safeError(e) }, 503);
  }
});

app.get("/status", async (c) => {
  const statusPin = String(c.req.header("X-Publish-Pin") || "");
  if (!checkBearer(c) && !checkPin(statusPin)) return c.json({ ok: false, error: "Non autorizzato" }, 401);
  const results: any = {};

  try {
    const token = Bun.env.FACEBOOK_PAGE_ACCESS_TOKEN;
    const pageId = Bun.env.FACEBOOK_PAGE_ID;
    if (!token || !pageId) throw new Error("config_missing");
    const u = new URL(FB_BASE + "/" + pageId);
    u.searchParams.set("fields", "id,name");
    u.searchParams.set("access_token", token);
    const r = await fetch(u.toString(), { signal: AbortSignal.timeout(20000) });
    const d = (await r.json()) as any;
    results.facebook = { ok: r.ok && !!d.id, status: r.status, name: d.name || null, id: d.id || null, error: d.error?.message || null };
  } catch (e) {
    results.facebook = { ok: false, status: null, error: safeError(e) };
  }

  try {
    const token = Bun.env.INSTAGRAM_ACCESS_TOKEN;
    const userId = Bun.env.INSTAGRAM_USER_ID;
    if (!token || !userId) throw new Error("config_missing");
    const u = new URL(IG_BASE + "/" + userId);
    u.searchParams.set("fields", "id,username");
    u.searchParams.set("access_token", token);
    const r = await fetch(u.toString(), { signal: AbortSignal.timeout(20000) });
    const d = (await r.json()) as any;
    results.instagram = { ok: r.ok && !!d.id, status: r.status, username: d.username || null, id: d.id || null, error: d.error?.message || null };
  } catch (e) {
    results.instagram = { ok: false, status: null, error: safeError(e) };
  }

  try {
    const access = await getYoutubeAccessToken();
    const r = await fetch("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true", {
      headers: { Authorization: "Bearer " + access },
      signal: AbortSignal.timeout(20000)
    });
    const d = (await r.json()) as any;
    const item = d.items?.[0] || null;
    results.youtube = { ok: r.ok && !!item?.id, status: r.status, title: item?.snippet?.title || null, id: item?.id || null, error: d.error?.message || null };
  } catch (e) {
    results.youtube = { ok: false, status: null, error: safeError(e) };
  }

  return c.json({
    ok: Boolean(results.facebook?.ok && results.instagram?.ok && results.youtube?.ok),
    ...results
  });
});

app.post("/publish", async (c) => {
  let body: any;
  try { body = await c.req.json(); } catch { return c.json({ error: "JSON non valido" }, 400); }

  const videoUrl = String(body.videoUrl || "");
  const caption = String(body.caption || "");
  const platform = String(body.platform || "instagram").toLowerCase();
  const confirmed = body.confirmed === true;
  const pin = String(body.pin || "");
  const shareToFeed = body.shareToFeed !== false;

  if (!confirmed) return c.json({ error: "Pubblicazione non confermata" }, 400);
  if (!checkPin(pin)) return c.json({ error: "Accesso non autorizzato" }, 401);
  if (!videoUrl.startsWith("https://")) return c.json({ error: "URL video non valido" }, 400);
  if (!["instagram", "facebook", "both"].includes(platform)) return c.json({ error: "Piattaforma non valida" }, 400);

  const mediaIds: any = {};
  const platforms: string[] = [];

  if (platform === "instagram" || platform === "both") {
    const ig = await publishToInstagram(videoUrl, caption, shareToFeed);
    if (!ig.success) return c.json({ error: ig.error }, 502);
    mediaIds.instagram = ig.mediaId;
    platforms.push("Instagram");
  }
  if (platform === "facebook" || platform === "both") {
    const fb = await publishToFacebook(videoUrl, caption);
    if (!fb.success) return c.json({ error: fb.error }, 502);
    mediaIds.facebook = fb.mediaId;
    platforms.push("Facebook");
  }

  return c.json({ success: true, mediaIds, platforms });
});

app.post("/facebook/reel", async (c) => {
  if (!checkBearer(c)) return c.json({ error: "Non autorizzato" }, 401);
  let body: any;
  try { body = await c.req.json(); } catch { return c.json({ error: "JSON non valido" }, 400); }
  const result = await publishToFacebook(String(body.video_url || ""), String(body.description || ""));
  return result.success
    ? c.json({ success: true, video_id: result.mediaId })
    : c.json({ error: result.error }, 502);
});

app.post("/instagram/reel", async (c) => {
  if (!checkBearer(c)) return c.json({ error: "Non autorizzato" }, 401);
  let body: any;
  try { body = await c.req.json(); } catch { return c.json({ error: "JSON non valido" }, 400); }
  const result = await publishToInstagram(
    String(body.video_url || ""),
    String(body.caption || ""),
    body.share_to_feed !== false
  );
  return result.success
    ? c.json({ success: true, media_id: result.mediaId })
    : c.json({ error: result.error }, 502);
});

app.get("/youtube/connect", async (c) => {
  const clientId = Bun.env.YOUTUBE_CLIENT_ID;
  const bridgeSecret = Bun.env.BRIDGE_SECRET;
  if (!clientId || !bridgeSecret) return c.html("<h1>Configurazione YouTube incompleta</h1>", 500);

  const redirectUri = PUBLIC_BASE + "/youtube/oauth/callback";
  const state = await createYoutubeState(bridgeSecret);
  const u = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  u.searchParams.set("client_id", clientId);
  u.searchParams.set("redirect_uri", redirectUri);
  u.searchParams.set("response_type", "code");
  u.searchParams.set("scope", "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly");
  u.searchParams.set("access_type", "offline");
  u.searchParams.set("prompt", "consent");
  u.searchParams.set("state", state);
  return c.redirect(u.toString());
});

app.get("/youtube/oauth/callback", async (c) => {
  const code = c.req.query("code");
  const state = c.req.query("state") || "";
  const oauthError = c.req.query("error");
  const clientId = Bun.env.YOUTUBE_CLIENT_ID;
  const clientSecret = Bun.env.YOUTUBE_CLIENT_SECRET;
  const bridgeSecret = Bun.env.BRIDGE_SECRET;
  if (!clientId || !clientSecret || !bridgeSecret) return c.html("<h1>Configurazione YouTube incompleta</h1>", 500);
  if (oauthError) return c.html("<h1>Autorizzazione YouTube annullata</h1>", 400);
  if (!code || !(await validateYoutubeState(state, bridgeSecret))) return c.html("<h1>Richiesta OAuth non valida o scaduta</h1>", 400);

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      code,
      grant_type: "authorization_code",
      redirect_uri: PUBLIC_BASE + "/youtube/oauth/callback"
    }),
    signal: AbortSignal.timeout(30000)
  });
  const data = (await response.json()) as any;
  if (!response.ok || !data.refresh_token) return c.html("<h1>Collegamento YouTube non riuscito</h1>", 502);
  await storeYoutubeRefreshToken(String(data.refresh_token), bridgeSecret);
  return c.html("<!doctype html><html lang='it'><body style='font-family:sans-serif;background:#111;color:#fff;padding:40px'><h1>YouTube collegato ✅</h1><p>Puoi chiudere questa pagina.</p></body></html>");
});

app.post("/youtube/short", async (c) => {
  if (!checkPin(String(c.req.header("X-Publish-Pin") || ""))) return c.json({ ok: false, error: "Non autorizzato" }, 401);
  let body: any;
  try { body = await c.req.json(); } catch { return c.json({ ok: false, error: "JSON non valido" }, 400); }

  const videoUrl = String(body.video_url || "").trim();
  const title = String(body.title || "").trim();
  const description = String(body.description || "");
  const tags = Array.isArray(body.tags)
    ? body.tags.map((x: any) => String(x).trim()).filter(Boolean)
    : String(body.tags || "").split(",").map((x) => x.trim()).filter(Boolean);

  if (!videoUrl.startsWith("https://") || !title) return c.json({ ok: false, error: "video_url e title sono obbligatori" }, 400);

  try {
    const media = await fetchVideo(videoUrl);
    const requestedPrivacy = String(body.privacy_status || "private").toLowerCase();
    const privacyStatus = (["public","private","unlisted"].includes(requestedPrivacy) ? requestedPrivacy : "private") as "public" | "private" | "unlisted";
    const uploaded = await uploadYoutubeBytes(media.bytes, media.contentType, title, description, tags, privacyStatus);
    return c.json({
      ok: true,
      platform: "youtube",
      video_id: uploaded.id,
      privacy_status: privacyStatus,
      url: "https://www.youtube.com/watch?v=" + uploaded.id
    });
  } catch (e) {
    return c.json({ ok: false, error: safeError(e) }, 502);
  }
});

app.post("/youtube/upload", async (c) => {
  try {
    const form = await c.req.formData();
    if (!checkPin(String(form.get("pin") || ""))) return c.json({ ok: false, error: "Non autorizzato" }, 401);
    const video = form.get("video");
    const title = String(form.get("title") || "").trim();
    const description = String(form.get("description") || "");
    const tags = String(form.get("tags") || "").split(",").map((x) => x.trim()).filter(Boolean);
    if (!(video instanceof File) || !title) return c.json({ ok: false, error: "File e titolo obbligatori" }, 400);
    const bytes = new Uint8Array(await video.arrayBuffer());
    const uploaded = await uploadYoutubeBytes(bytes, video.type || "video/mp4", title, description, tags);
    return c.json({
      ok: true,
      platform: "youtube",
      video_id: uploaded.id,
      privacy_status: "private",
      url: "https://www.youtube.com/watch?v=" + uploaded.id
    });
  } catch (e) {
    return c.json({ ok: false, error: safeError(e) }, 502);
  }
});

app.post("/youtube/auto-upload", async (c) => {
  if (!checkPin(String(c.req.header("X-Publish-Pin") || ""))) return c.json({ ok: false, error: "Non autorizzato" }, 401);
  let body: any;
  try { body = await c.req.json(); } catch { return c.json({ ok: false, error: "JSON non valido" }, 400); }
  try {
    const media = await fetchVideo(String(body.video_url || ""));
    const tags = Array.isArray(body.tags) ? body.tags.map(String) : String(body.tags || "").split(",").filter(Boolean);
    const uploaded = await uploadYoutubeBytes(
      media.bytes,
      media.contentType,
      String(body.title || "FUOCONERO").slice(0, 100),
      String(body.description || ""),
      tags
    );
    return c.json({ ok: true, video_id: uploaded.id, privacy_status: "private", url: "https://www.youtube.com/watch?v=" + uploaded.id });
  } catch (e) {
    return c.json({ ok: false, error: safeError(e) }, 502);
  }
});


app.get("/youtube/test", (c) => c.html(`<!doctype html>
<html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fuoconero · YouTube test</title><style>body{font-family:system-ui;background:#111;color:#eee;padding:24px}main{max-width:620px;margin:auto}input,textarea,button{width:100%;box-sizing:border-box;margin:8px 0;padding:12px}</style></head>
<body><main><h1>Upload diretto YouTube Fuoconero</h1><p>Il video viene caricato come <strong>PRIVATO</strong>.</p>
<form id="f"><input name="video" type="file" accept="video/mp4" required><input name="title" placeholder="Titolo" required><textarea name="description" placeholder="Descrizione"></textarea><input name="tags" placeholder="tag1,tag2"><input name="pin" type="password" placeholder="PIN" required><button>Carica privato</button></form><pre id="out"></pre>
<script>document.getElementById("f").onsubmit=async(e)=>{e.preventDefault();const r=await fetch("/youtube/upload",{method:"POST",body:new FormData(e.target)});document.getElementById("out").textContent=await r.text();}</script>
</main></body></html>`));

export default app;

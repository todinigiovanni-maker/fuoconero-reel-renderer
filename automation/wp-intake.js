// WordPress -> AutoReel intake helper.
// Stateless by design: the WordPress/Fuoconero Social side owns the durable queue.
// Idempotency key is derived from the WordPress post id, so the same article cannot
// accidentally create two different jobs downstream.

export function normalizeWordPressEvent(body = {}) {
  const postId = String(body.post_id ?? body.id ?? '').trim();
  const url = String(body.url ?? body.post_url ?? body.permalink ?? '').trim();
  const status = String(body.status ?? 'publish').toLowerCase();

  if (!postId) throw new Error('post_id mancante');
  if (!/^https:\/\/fuoconero\.com\//i.test(url)) throw new Error('url Fuoconero non valido');
  if (!['publish','published'].includes(status)) throw new Error('articolo non pubblicato');

  return {
    postId,
    url,
    status: 'publish',
    idempotencyKey: 'wp-post:' + postId
  };
}

// India Pulse — zero-dependency Node server (Node 18+). Run: node server.js → http://localhost:3000
// Serves web/index.html and a tiny RSS relay (/api/rss?url=...) so the page can read Google News without CORS proxies.
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const ALLOWED_HOST = 'news.google.com';
const TTL = 10 * 60 * 1000;            // cache each feed for 10 minutes
const cache = new Map();                // url -> { ts, body }

async function relay(target) {
  const hit = cache.get(target);
  if (hit && Date.now() - hit.ts < TTL) return hit.body;
  const res = await fetch(target, { headers: { 'User-Agent': 'Mozilla/5.0 IndiaPulse' }, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error('upstream HTTP ' + res.status);
  const body = await res.text();
  cache.set(target, { ts: Date.now(), body });
  if (cache.size > 100) cache.delete(cache.keys().next().value);
  return body;
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };
const WEB = path.join(__dirname, 'web');

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/rss') {
    try {
      const target = new URL(url.searchParams.get('url') || '');
      if (target.protocol !== 'https:' || target.hostname !== ALLOWED_HOST) throw new Error('host not allowed');
      const body = await relay(target.toString());
      res.writeHead(200, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(body);
    } catch (e) {
      res.writeHead(502, { 'Content-Type': 'text/plain' });
      return res.end(String(e.message));
    }
  }
  const rel = path.normalize(url.pathname === '/' ? '/index.html' : url.pathname).replace(/^(\.\.[/\\])+/, '');
  fs.readFile(path.join(WEB, rel), (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(rel)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(PORT, () => console.log(`India Pulse on http://localhost:${PORT}`));

// India Pulse — zero-dependency Node server (Node 18+).
// Fetches Google News RSS per topic, dedupes, caches, and refreshes every hour.
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const REFRESH_MS = 60 * 60 * 1000;

const TOPICS = [
  { id: 'markets',  label: 'Markets & Rates',   q: 'Sensex OR Nifty OR RBI OR rupee OR "bond yield" India' },
  { id: 'economy',  label: 'Economy & Data',    q: 'India GDP OR inflation OR "fiscal deficit" OR GST collections OR PMI' },
  { id: 'policy',   label: 'Policy & Regulation', q: 'India government policy OR SEBI OR "Union Cabinet" OR "Finance Ministry"' },
  { id: 'trade',    label: 'Trade & Geopolitics', q: 'India trade deal OR tariff OR exports OR "India US" OR "India China" OR border' },
  { id: 'energy',   label: 'Energy & Commodities', q: 'India crude oil OR gold OR "fuel prices" OR coal OR power demand' },
  { id: 'global',   label: 'Global Spillovers', q: 'Fed OR "global markets" OR "FII" OR "foreign investors" India impact' },
  { id: 'corporate', label: 'Corporate & Deals', q: 'India earnings OR IPO OR "RBI penalty" OR "bank results" OR FDI' },
];

let cache = { items: [], updatedAt: null, nextRefreshAt: null, errors: [] };

const decode = (s = '') => s
  .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
  .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
  .replace(/<[^>]+>/g, '').trim();

const tag = (block, name) => {
  const m = block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)</${name}>`));
  return m ? decode(m[1]) : '';
};

function parseRss(xml, topic) {
  const out = [];
  const blocks = xml.match(/<item>[\s\S]*?<\/item>/g) || [];
  for (const b of blocks) {
    let title = tag(b, 'title');
    const link = tag(b, 'link');
    const pub = tag(b, 'pubDate');
    let source = tag(b, 'source');
    if (source && title.endsWith(' - ' + source)) title = title.slice(0, -(source.length + 3));
    else if (!source) {
      const i = title.lastIndexOf(' - ');
      if (i > 0) { source = title.slice(i + 3); title = title.slice(0, i); }
    }
    const ts = Date.parse(pub);
    if (!title || !link || isNaN(ts)) continue;
    out.push({ title, link, source: source || 'Unknown', ts, topic: topic.id });
  }
  return out;
}

async function fetchTopic(topic) {
  const url = 'https://news.google.com/rss/search?q=' +
    encodeURIComponent(topic.q + ' when:1d') + '&hl=en-IN&gl=IN&ceid=IN:en';
  const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0 IndiaPulse' }, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error(`${topic.id}: HTTP ${res.status}`);
  return parseRss(await res.text(), topic);
}

async function refresh() {
  const results = await Promise.allSettled(TOPICS.map(fetchTopic));
  const errors = results.filter(r => r.status === 'rejected').map(r => String(r.reason.message || r.reason));
  const seen = new Map();
  for (const r of results) {
    if (r.status !== 'fulfilled') continue;
    for (const it of r.value) {
      const key = it.title.toLowerCase().replace(/[^a-z0-9]+/g, ' ').slice(0, 80);
      if (!seen.has(key)) seen.set(key, it);
    }
  }
  const items = [...seen.values()].sort((a, b) => b.ts - a.ts).slice(0, 300);
  if (items.length || !cache.items.length) {
    cache = { items, updatedAt: Date.now(), nextRefreshAt: Date.now() + REFRESH_MS, errors };
  } else {
    cache.errors = errors; // keep stale items if everything failed
    cache.nextRefreshAt = Date.now() + REFRESH_MS;
  }
  console.log(`[${new Date().toISOString()}] refreshed: ${items.length} items, ${errors.length} errors`);
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/news') {
    const stale = !cache.updatedAt || Date.now() - cache.updatedAt > REFRESH_MS;
    const forced = url.searchParams.get('force') === '1' && Date.now() - (cache.updatedAt || 0) > 60 * 1000;
    const pre = (stale || forced) ? refresh().catch(console.error) : Promise.resolve();
    return pre.then(() => {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ...cache, topics: TOPICS.map(({ id, label }) => ({ id, label })), now: Date.now() }));
    });
  }
  const file = url.pathname === '/' ? '/index.html' : url.pathname;
  const full = path.join(__dirname, 'public', path.normalize(file).replace(/^(\.\.[/\\])+/, ''));
  fs.readFile(full, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(PORT, () => console.log(`India Pulse on http://localhost:${PORT}`));

refresh().catch(console.error);
setInterval(() => refresh().catch(console.error), REFRESH_MS);

module.exports = { parseRss };

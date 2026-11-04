// REAL API test: sequential exhaustion + cooldown against https://kiosapi.com/v1 (uses ~5 upstream requests)
import { KeySlot, CFG } from './relay.mjs';
import { startRelay } from './server.mjs';
import http from 'node:http';

CFG.upstreamBase = 'https://kiosapi.com/v1';
CFG.cooldownSec = 60; CFG.maxWaitSec = 120; CFG.errorCooldownSec = 15;

const kA = new KeySlot('a', 'Key-1', process.env.KIOSA_KEY1);
const kB = new KeySlot('b', 'Key-2', process.env.KIOSA_KEY2);
const relay = startRelay([kA, kB]);
await new Promise(r => relay.server.listen(8849, r));

const t0 = Date.now();
const ts = () => `[+${((Date.now() - t0) / 1000).toFixed(1)}s]`;
function post(i) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify({ model: 'agnes-2.5-flash', messages: [{ role: 'user', content: 'hi' }], max_tokens: 5 }));
    const rq = http.request({ host: '127.0.0.1', port: 8849, path: '/v1/chat/completions', method: 'POST', headers: { 'content-type': 'application/json', 'content-length': data.length } }, res => {
      let s = ''; res.on('data', c => s += c); res.on('end', () => resolve({ code: res.statusCode, via: res.headers['x-kiosa-upstream'] || '-', body: s }));
    });
    rq.setTimeout(180000); rq.on('error', reject); rq.write(data); rq.end();
  });
}

console.log(`${ts()} Firing 4 sequential requests through the relay (limit burn on Key-1 expected)...`);
for (let i = 1; i <= 4; i++) {
  const r = await post(i);
  console.log(`${ts()} request ${i}: HTTP ${r.code} via ${r.via} :: ${r.code === 200 ? 'OK' : r.body.slice(0, 110)}`);
}

console.log(`\n${ts()} Final engine state:`);
for (const k of relay.engine.keys) console.log(`  ${k.label}: status=${k.status} success=${k.success} rateLimited=${k.rateLimited} fail=${k.fail} cooldownLeft=${Math.ceil(k.remaining() / 1000)}s`);
console.log(`\n${ts()} Engine event log:`);
for (const l of [...relay.engine.log].reverse()) console.log(`  ${new Date(l.t).toISOString().slice(11, 19)} [${l.kind}] ${l.key}: ${l.msg}`);
relay.server.close(); process.exit(0);

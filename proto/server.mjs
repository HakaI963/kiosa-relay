// Kiosa Relay prototype — HTTP server entry
import http from 'node:http';
import { RelayEngine, KeySlot, relayRequest, translateAnthropicRequest, openAiToAnthropicResponse, streamOpenAiToAnthropic, upstreamCall, readBody, CFG } from './relay.mjs';
import { URL } from 'node:url';

export function startRelay(keys) {
  const engine = new RelayEngine(keys);
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const path = url.pathname;
    const body = await readBody(req, 32 * 1024 * 1024);
    try {
      if (CFG.accessKey) {
        const auth = req.headers['authorization'] || '';
        const xkey = req.headers['x-api-key'] || '';
        const ok = auth === `Bearer ${CFG.accessKey}` || xkey === CFG.accessKey;
        if (!ok && path !== '/') {
          res.writeHead(401, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'Invalid relay access key', type: 'kiosa_relay_auth' } }));
          return;
        }
      }
      if (path === '/' || path === '/relay/status') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({
          app: 'Kiosa Relay', version: 'proto-1.0', strategy: 'sequential-exhaustion + per-key cooldown',
          upstream: CFG.upstreamBase, stats: engine.stats,
          keys: engine.keys.map(k => ({ label: k.label, status: k.status, cooldownRemainingSec: Math.ceil(k.remaining() / 1000), success: k.success, rateLimited: k.rateLimited, fail: k.fail, lastError: k.lastError })),
          log: engine.log.slice(0, 30),
        }));
        return;
      }
      if (path === '/v1/models') {
        const key = engine.keys.find(k => k.key)?.key;
        if (!key) { res.writeHead(503); res.end(); return; }
        try {
          const r = await upstreamCall(CFG.upstreamBase, '/models', '', key, 'GET');
          const b = await readBody(r);
          res.writeHead(r.statusCode || 502, { 'content-type': r.headers['content-type'] || 'application/json' });
          res.end(b);
        } catch (e) { res.writeHead(502, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: { message: e.message } })); }
        return;
      }
      if ((path === '/v1/chat/completions' || path === '/v1/completions' || path === '/v1/embeddings') && req.method === 'POST') {
        await relayRequest(engine, res, path.replace(/^\/v1/, ''), body, path, null); // base already ends with /v1
        return;
      }
      if (path === '/v1/messages' && req.method === 'POST') {
        let a; try { a = JSON.parse(body); } catch { res.writeHead(400); res.end('bad json'); return; }
        const openaiBody = JSON.stringify(translateAnthropicRequest(a));
        const transform = a.stream
          ? (up, cr) => streamOpenAiToAnthropic(up, cr, a.model)
          : async (up, cr) => {
              const b = await readBody(up);
              cr.writeHead(up.statusCode, { 'content-type': 'application/json' });
              cr.end(openAiToAnthropicResponse(b, a.model));
            };
        await relayRequest(engine, res, '/chat/completions', openaiBody, '/v1/messages', transform);
        return;
      }
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: `Unknown path ${path}. Relay exposes: POST /v1/chat/completions, POST /v1/messages, GET /v1/models, GET /relay/status`, type: 'kiosa_relay' } }));
    } catch (e) {
      try { res.writeHead(500, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: { message: e.message, type: 'kiosa_relay_internal' } })); } catch {}
    }
  });
  return { server, engine };
}

// Kiosa Relay — strategy prototype (zero-dep Node). Mirrors the Kotlin implementation 1:1.
// Env: RELAY_KEY (optional local access key), KIOSA_KEY1, KIOSA_KEY2, UPSTREAM (default https://kiosapi.com/v1)
import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';
import readline from 'node:readline';
import crypto from 'node:crypto';

export const CFG = {
  port: Number(process.env.RELAY_PORT || 8849),
  upstreamBase: process.env.UPSTREAM || 'https://kiosapi.com/v1',
  cooldownSec: 60,
  errorCooldownSec: 15,
  authCooldownSec: 300,
  maxWaitSec: 90,
  accessKey: process.env.RELAY_KEY || '',
  requestTimeoutMs: 300_000,
};

export class KeySlot {
  constructor(id, label, key) {
    this.id = id; this.label = label; this.key = key;
    this.cooldownUntil = 0; this.lastError = null;
    this.success = 0; this.fail = 0; this.rateLimited = 0; this.lastUsedAt = 0;
    this.status = 'idle'; // idle | active | cooldown | error
  }
  available(now) { return this.cooldownUntil <= now; }
  remaining(now) { return Math.max(0, this.cooldownUntil - now); }
}

export class RelayEngine {
  constructor(keys) { this.keys = keys; this.stats = { relayed: 0, rateLimited: 0, errors: 0, waits: 0 }; this.log = []; }
  pick(now = Date.now()) { return this.keys.find(k => k.available(now)) || null; }
  soonest(now = Date.now()) {
    const ts = this.keys.map(k => k.cooldownUntil).filter(t => t > now);
    return ts.length ? Math.min(...ts) : null;
  }
  markRateLimited(k) {
    k.cooldownUntil = Date.now() + CFG.cooldownSec * 1000; k.rateLimited++; k.status = 'cooldown';
    k.lastError = '429 rate limited'; this.stats.rateLimited++; this.pushLog('cooldown', k, `429 → cooldown ${CFG.cooldownSec}s`);
  }
  markError(k, msg, sec) {
    k.cooldownUntil = Date.now() + sec * 1000; k.fail++; k.status = 'error'; k.lastError = msg;
    this.stats.errors++; this.pushLog('error', k, `${msg} → cooldown ${sec}s`);
  }
  markSuccess(k, extra = '') {
    k.success++; k.status = 'active'; k.lastError = null; k.lastUsedAt = Date.now();
    this.stats.relayed++; this.pushLog('ok', k, extra);
  }
  pushLog(kind, k, msg) {
    this.log.unshift({ t: Date.now(), kind, key: k.label, msg });
    if (this.log.length > 200) this.log.pop();
  }
}

export function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

export function upstreamCall(base, path, body, key, method = 'POST') {
  return new Promise((resolve, reject) => {
    const u = new URL(base + path);
    const isHttps = u.protocol === 'https:';
    const mod = isHttps ? https : http;
    const data = Buffer.from(body, 'utf8');
    const req = mod.request(u, {
      method,
      headers: { 'Authorization': `Bearer ${key}`, 'Content-Type': 'application/json', 'Content-Length': data.length },
      timeout: CFG.requestTimeoutMs,
    }, res => resolve(res));
    req.on('timeout', () => req.destroy(new Error('upstream timeout')));
    req.on('error', reject);
    req.write(data); req.end();
  });
}

export function readBody(res, limit = 512 * 1024) {
  return new Promise(resolve => {
    const chunks = []; let n = 0;
    res.on('data', c => { n += c.length; if (n <= limit) chunks.push(c); });
    res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

// ---- The core sequential-exhaustion attempt loop (this is the heart of the app) ----
export async function relayRequest(engine, clientRes, path, body, label, transform) {
  const startedAt = Date.now();
  let attempts = 0; let lastErr = null;
  while (true) {
    if (++attempts > 12) {
      clientRes.writeHead(502, { 'content-type': 'application/json' });
      clientRes.end(JSON.stringify({ error: { message: 'All keys failed: ' + (lastErr || 'unknown'), type: 'kiosa_relay_exhausted' } }));
      return;
    }
    const now = Date.now();
    const slot = engine.pick(now);
    if (!slot) {
      const soon = engine.soonest(now);
      const waitMs = soon ? soon - now : 0;
      engine.stats.waits++;
      if (!soon || waitMs > CFG.maxWaitSec * 1000) {
        const retryAfter = Math.max(1, Math.ceil((soon ? waitMs : CFG.cooldownSec * 1000) / 1000));
        engine.pushLog('client-429', { label: '(none)' }, `all keys cooling → 429 to client, retry-after ${retryAfter}s`);
        clientRes.writeHead(429, { 'content-type': 'application/json', 'retry-after': String(retryAfter) });
        clientRes.end(JSON.stringify({ error: { message: `All API keys are rate-limited. Retry after ${retryAfter}s.`, type: 'kiosa_relay_all_limited' } }));
        return;
      }
      engine.pushLog('wait', { label: '(pool)' }, `all keys cooling → waiting ${Math.ceil(waitMs / 1000)}s for revival`);
      await sleep(waitMs + 75);
      continue;
    }
    let res;
    try { res = await upstreamCall(CFG.upstreamBase, path, body, slot.key); }
    catch (e) { engine.markError(slot, e.message, CFG.errorCooldownSec); lastErr = e.message; continue; }

    if (res.statusCode === 429) { const b = await readBody(res); engine.markRateLimited(slot); lastErr = '429: ' + b.slice(0, 120); continue; }
    if (res.statusCode >= 400) {
      const b = await readBody(res);
      const auth = res.statusCode === 401 || res.statusCode === 403;
      engine.markError(slot, `HTTP ${res.statusCode}: ${b.slice(0, 140)}`, auth ? CFG.authCooldownSec : CFG.errorCooldownSec);
      lastErr = `HTTP ${res.statusCode}: ${b.slice(0, 200)}`;
      continue;
    }
    // success
    engine.markSuccess(slot, `${label} 200 via ${slot.label} (+${((Date.now() - startedAt) / 1000).toFixed(1)}s)`);
    if (transform) { await transform(res, clientRes); return; }
    const ct = res.headers['content-type'] || 'application/json';
    clientRes.writeHead(res.statusCode, { 'content-type': ct, 'x-kiosa-upstream': slot.label });
    res.pipe(clientRes);
    return;
  }
}

// ---------------- Anthropic ↔ OpenAI bridge ----------------
export function translateAnthropicRequest(a) {
  const o = { model: a.model, messages: [] };
  if (a.max_tokens) o.max_tokens = a.max_tokens;
  if (a.temperature != null) o.temperature = a.temperature;
  if (a.top_p != null) o.top_p = a.top_p;
  if (a.stop_sequences) o.stop = a.stop_sequences;
  if (a.stream != null) o.stream = a.stream;
  if (Array.isArray(a.tools) && a.tools.length) {
    o.tools = a.tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description || '', parameters: t.input_schema || { type: 'object', properties: {} } } }));
  }
  if (a.tool_choice) {
    const tc = a.tool_choice;
    if (tc.type === 'auto') o.tool_choice = 'auto';
    else if (tc.type === 'any') o.tool_choice = 'required';
    else if (tc.type === 'none') o.tool_choice = 'none';
    else if (tc.type === 'tool') o.tool_choice = { type: 'function', function: { name: tc.name } };
  }
  if (a.system) {
    const s = Array.isArray(a.system) ? a.system.map(b => b.text || '').join('\n') : a.system;
    if (s) o.messages.push({ role: 'system', content: s });
  }
  for (const m of a.messages || []) {
    if (typeof m.content === 'string') { o.messages.push({ role: m.role, content: m.content }); continue; }
    const textParts = [], toolCalls = [], toolResults = [], images = [];
    for (const b of m.content) {
      if (b.type === 'text') textParts.push(b.text);
      else if (b.type === 'thinking' || b.type === 'redacted_thinking') { /* skip */ }
      else if (b.type === 'image') {
        const src = b.source || {};
        const url = src.type === 'base64' ? `data:${src.media_type};base64,${src.data}` : src.url;
        images.push({ type: 'image_url', image_url: { url } });
      }
      else if (b.type === 'tool_use') toolCalls.push({ id: b.id, type: 'function', function: { name: b.name, arguments: JSON.stringify(b.input || {}) } });
      else if (b.type === 'tool_result') {
        const c = typeof b.content === 'string' ? b.content : Array.isArray(b.content) ? b.content.map(x => x.text || '').join('\n') : '';
        toolResults.push({ role: 'tool', tool_call_id: b.tool_use_id, content: c });
      }
    }
    if (toolResults.length) o.messages.push(...toolResults);
    if (images.length || textParts.length || toolCalls.length) {
      const msg = { role: m.role };
      if (images.length) {
        msg.content = [];
        if (textParts.length) msg.content.push({ type: 'text', text: textParts.join('') });
        msg.content.push(...images);
      } else {
        msg.content = textParts.join('') || null;
        if (toolCalls.length) msg.tool_calls = toolCalls;
        if (msg.content === null && !toolCalls.length) msg.content = '';
      }
      o.messages.push(msg);
    }
  }
  return o;
}

const STOP_MAP = { tool_calls: 'tool_use', length: 'max_tokens', stop: 'end_turn', content_filter: 'end_turn' };

export function openAiToAnthropicResponse(openaiText, model) {
  let j; try { j = JSON.parse(openaiText); } catch { return JSON.stringify({ type: 'error', error: { type: 'api_error', message: 'bad upstream json' } }); }
  if (j.error) return JSON.stringify({ type: 'error', error: { type: 'api_error', message: j.error.message || 'upstream error' } });
  const msg = j.choices?.[0]?.message || {};
  const content = [];
  if (msg.reasoning_content) content.push({ type: 'thinking', thinking: String(msg.reasoning_content), signature: '' });
  if (typeof msg.content === 'string' && msg.content.length) content.push({ type: 'text', text: msg.content });
  else if (Array.isArray(msg.content)) for (const p of msg.content) if (p.type === 'text' && p.text) content.push({ type: 'text', text: p.text });
  for (const tc of msg.tool_calls || []) {
    let input = {}; try { input = JSON.parse(tc.function?.arguments || '{}'); } catch {}
    content.push({ type: 'tool_use', id: tc.id || 'call_' + crypto.randomUUID(), name: tc.function?.name || '', input });
  }
  const out = {
    id: 'msg_' + (j.id || crypto.randomUUID()),
    type: 'message', role: 'assistant', model: model || j.model || '',
    content, stop_reason: STOP_MAP[j.choices?.[0]?.finish_reason] || 'end_turn', stop_sequence: null,
    usage: { input_tokens: j.usage?.prompt_tokens ?? 0, output_tokens: j.usage?.completion_tokens ?? 0 },
  };
  return JSON.stringify(out);
}

// Streams upstream OpenAI SSE → Anthropic SSE
export async function streamOpenAiToAnthropic(upstreamRes, clientRes, model) {
  clientRes.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
  const sse = (obj) => clientRes.write(`event: ${obj.type}\ndata: ${JSON.stringify(obj)}\n\n`);
  let nextIdx = 0, textOpen = -1, thinkOpen = -1;
  const toolBlocks = new Map(); // openai tool index → { idx }
  const usage = { input_tokens: 0, output_tokens: 0 };
  let stopReason = null, outChars = 0, sawEvent = false;

  sse({ type: 'message_start', message: { id: 'msg_' + crypto.randomUUID(), type: 'message', role: 'assistant', model: model || '', content: [], stop_sequence: null, stop_reason: null, usage } });

  const openBlock = (type) => {
    const idx = nextIdx++;
    sse({ type: 'content_block_start', index: idx, content_block: type === 'text' ? { type: 'text', text: '' } : { type: 'thinking', thinking: '' } });
    return idx;
  };
  const closeBlock = (idx, blockType) => {
    if (idx < 0) return;
    sse({ type: 'content_block_stop', index: idx });
    if (blockType === 'text') textOpen = -1; else if (blockType === 'thinking') thinkOpen = -1;
  };

  const rl = readline.createInterface({ input: upstreamRes });
  try {
    for await (const line of rl) {
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (payload === '[DONE]') break;
      let j; try { j = JSON.parse(payload); } catch { continue; }
      sawEvent = true;
      if (j.usage) { usage.input_tokens = j.usage.prompt_tokens ?? usage.input_tokens; usage.output_tokens = j.usage.completion_tokens ?? usage.output_tokens; }
      const d = j.choices?.[0]?.delta || {};
      if (d.reasoning_content) {
        if (thinkOpen < 0) thinkOpen = openBlock('thinking');
        outChars += d.reasoning_content.length;
        sse({ type: 'content_block_delta', index: thinkOpen, delta: { type: 'thinking_delta', thinking: d.reasoning_content } });
      }
      if (d.content) {
        if (thinkOpen >= 0) closeBlock(thinkOpen, 'thinking');
        if (textOpen < 0) textOpen = openBlock('text');
        outChars += d.content.length;
        sse({ type: 'content_block_delta', index: textOpen, delta: { type: 'text_delta', text: d.content } });
      }
      for (const tc of d.tool_calls || []) {
        const i = tc.index ?? 0;
        if (!toolBlocks.has(i)) {
          closeBlock(textOpen, 'text'); closeBlock(thinkOpen, 'thinking');
          const idx = nextIdx++;
          toolBlocks.set(i, { idx });
          sse({ type: 'content_block_start', index: idx, content_block: { type: 'tool_use', id: tc.id || 'call_' + crypto.randomUUID(), name: tc.function?.name || '', input: {} } });
        }
        const args = tc.function?.arguments || '';
        if (args) {
          outChars += args.length;
          sse({ type: 'content_block_delta', index: toolBlocks.get(i).idx, delta: { type: 'input_json_delta', partial_json: args } });
        }
      }
      const fr = j.choices?.[0]?.finish_reason;
      if (fr) stopReason = STOP_MAP[fr] || 'end_turn';
    }
  } catch (e) {
    sse({ type: 'error', error: { type: 'api_error', message: 'upstream stream error: ' + e.message } });
  }
  closeBlock(textOpen, 'text'); closeBlock(thinkOpen, 'thinking');
  for (const [, b] of toolBlocks) sse({ type: 'content_block_stop', index: b.idx });
  if (!sawEvent) sse({ type: 'error', error: { type: 'api_error', message: 'empty upstream stream' } });
  if (usage.output_tokens === 0) usage.output_tokens = Math.ceil(outChars / 4);
  sse({ type: 'message_delta', delta: { stop_reason: stopReason || 'end_turn', stop_sequence: null }, usage: { output_tokens: usage.output_tokens } });
  sse({ type: 'message_stop' });
  clientRes.end();
}

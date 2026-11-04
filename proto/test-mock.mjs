// Mock-upstream tests: translation correctness + sequential exhaustion + cooldown (no real quota used)
import http from 'node:http';
import assert from 'node:assert';
import { RelayEngine, KeySlot, relayRequest, translateAnthropicRequest, CFG } from './relay.mjs';
import { startRelay } from './server.mjs';

let mock429Hits = {}; // per key: rolling-window counter (mimics real 3-per-window limit)
const MOCK_WINDOW_MS = 6000;
function mockAllow(key) {
  const now = Date.now();
  const arr = (mock429Hits[key] = (mock429Hits[key] || []).filter(t => now - t < MOCK_WINDOW_MS));
  if (arr.length >= 3) return false;
  arr.push(now); return true;
}

const mock = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    const key = req.headers['authorization'] || '';
    const path = req.url;
    if (path === '/v1/chat/completions') {
      const j = JSON.parse(body);
      if (!mockAllow(key)) {
        res.writeHead(429, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'You have reached the request limit: maximum 3 requests in 1 minutes', type: 'new_api_error' } }));
        return;
      }
      if (j.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const chunks = [
          { choices: [{ delta: { reasoning_content: 'thinking hard' }, index: 0 }] },
          { choices: [{ delta: { content: 'Hello' }, index: 0 }] },
          { choices: [{ delta: { content: ' world' }, index: 0 }] },
          { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_abc', function: { name: 'get_weather', arguments: '{"city":' } }] }, index: 0 }] },
          { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"Paris"}' } }] }, index: 0 }] },
          { choices: [{ delta: {}, finish_reason: 'tool_calls', index: 0 }], usage: { prompt_tokens: 12, completion_tokens: 9 } },
        ];
        let i = 0;
        const t = setInterval(() => {
          if (i < chunks.length) res.write(`data: ${JSON.stringify(chunks[i++])}\n\n`);
          else { res.write('data: [DONE]\n\n'); res.end(); clearInterval(t); }
        }, 10);
        return;
      }
      // non-stream: echo back the received OpenAI body for verification + a tool call
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        id: 'cmpl_mock', model: j.model, object: 'chat.completion',
        choices: [{ index: 0, finish_reason: 'tool_calls', message: { role: 'assistant', content: 'Using tool.', tool_calls: [{ id: 'call_xyz', type: 'function', function: { name: 'get_weather', arguments: '{"city":"Paris"}' } }] } }],
        usage: { prompt_tokens: 20, completion_tokens: 5 },
        _received: j,
      }));
    } else { res.writeHead(404); res.end(); }
  });
});
await new Promise(r => mock.listen(9911, r));

// point relay at mock (base must include /v1 like production)
process.env.UPSTREAM = 'http://127.0.0.1:9911/v1';
CFG.upstreamBase = 'http://127.0.0.1:9911/v1';
CFG.cooldownSec = 2; // fast cooldown for mock test
CFG.errorCooldownSec = 1;

const kA = new KeySlot('a', 'MockKey-A', 'sk-mock-A');
const kB = new KeySlot('b', 'MockKey-B', 'sk-mock-B');
const engine = new RelayEngine([kA, kB]);

const relay = startRelay([kA, kB]);
await new Promise(r => relay.server.listen(8849, r));

function post(port, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const rq = http.request({ host: '127.0.0.1', port, path, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': data.length, ...headers } }, resolve);
    rq.on('error', reject); rq.write(data); rq.end();
  });
}
async function readAll(res) { let s = ''; for await (const c of res) s += c; return s; }

// ---- TEST 1: Anthropic non-stream request → OpenAI translation → Anthropic response ----
{
  const anthropicBody = {
    model: 'agnes-2.5-flash', max_tokens: 100, stream: false,
    system: [{ type: 'text', text: 'You are helpful.' }],
    messages: [{ role: 'user', content: [{ type: 'text', text: 'Weather in Paris?' }] }],
    tools: [{ name: 'get_weather', description: 'Get weather', input_schema: { type: 'object', properties: { city: { type: 'string' } } } }],
    tool_choice: { type: 'auto' }, temperature: 0.5,
  };
  const res = await post(8849, '/v1/messages', anthropicBody);
  const text = await readAll(res);
  assert.equal(res.statusCode, 200, 'test1 status');
  const out = JSON.parse(text);
  assert.equal(out.type, 'message');
  assert.equal(out.stop_reason, 'tool_use');
  assert.equal(out.content[0].type, 'text');
  assert.equal(out.content[1].type, 'tool_use');
  assert.equal(out.content[1].name, 'get_weather');
  assert.deepEqual(out.content[1].input, { city: 'Paris' });
  // verify what upstream RECEIVED (mock echoes it in _received)
  // grab from engine log path — instead re-issue via direct fetch of last mock body: simpler: assert on translation directly
  const translated = translateAnthropicRequest(anthropicBody);
  assert.equal(translated.messages[0].role, 'system');
  assert.equal(translated.messages[0].content, 'You are helpful.');
  assert.equal(translated.messages[1].content, 'Weather in Paris?');
  assert.equal(translated.tools[0].type, 'function');
  assert.equal(translated.tools[0].function.name, 'get_weather');
  assert.equal(translated.tool_choice, 'auto');
  assert.equal(translated.max_tokens, 100);
  console.log('✅ TEST 1  anthropic⇄openai non-stream translation + tool_use mapping');
}

// ---- TEST 2: Anthropic SSE streaming (text + thinking + tool_use + usage) ----
{
  const res = await post(8849, '/v1/messages', { model: 'm', max_tokens: 50, stream: true, messages: [{ role: 'user', content: 'hi' }] });
  const raw = await readAll(res);
  const events = raw.split('\n\n').filter(Boolean).map(e => {
    const dataLine = e.split('\n').find(l => l.startsWith('data: '));
    return JSON.parse(dataLine.slice(6));
  });
  const types = events.map(e => e.type);
  assert.ok(types[0] === 'message_start', 'first event message_start');
  assert.ok(types.includes('content_block_start'), 'has block start');
  assert.ok(types.filter(t => t === 'content_block_delta').length >= 5, 'has deltas');
  assert.ok(events.some(e => e.delta?.type === 'thinking_delta'), 'thinking deltas pass through');
  assert.ok(events.some(e => e.delta?.type === 'text_delta' && e.delta.text === 'Hello'), 'text deltas');
  const toolStart = events.find(e => e.type === 'content_block_start' && e.content_block?.type === 'tool_use');
  assert.ok(toolStart, 'tool_use block started');
  assert.equal(toolStart.content_block.name, 'get_weather');
  const jsonDelta = events.filter(e => e.delta?.type === 'input_json_delta').map(e => e.delta.partial_json).join('');
  assert.equal(JSON.parse(jsonDelta).city, 'Paris', 'tool args stream reassembles');
  assert.ok(types.includes('message_delta') && types[types.length - 1] === 'message_stop', 'proper close');
  const md = events.find(e => e.type === 'message_delta');
  assert.equal(md.delta.stop_reason, 'tool_use');
  assert.equal(md.usage.output_tokens, 9, 'usage output tokens from final chunk');
  console.log('✅ TEST 2  anthropic SSE streaming bridge (thinking + text + tool_use + usage)');
}

// ---- TEST 3: sequential exhaustion + per-key cooldown + strict order revival ----
{
  mock429Hits = {}; // fresh rolling windows for both keys (tests 1-2 pre-burned A)
  kA.cooldownUntil = 0; kB.cooldownUntil = 0; kA.status = 'idle'; kB.status = 'idle';
  relay.engine.stats = { relayed: 0, rateLimited: 0, errors: 0, waits: 0 };
  CFG.maxWaitSec = 10; CFG.cooldownSec = 2;
  const t0 = Date.now();
  const seq = [];
  for (let i = 0; i < 8; i++) {
    const res = await post(8849, '/v1/chat/completions', { model: 'm', messages: [{ role: 'user', content: 'hi' }], max_tokens: 5 });
    const via = res.headers['x-kiosa-upstream'] || 'none';
    seq.push(`${res.statusCode}:${via}${i < 7 ? ' →' : ''}`);
    await readAll(res);
  }
  const ms = Date.now() - t0;
  console.log(`   sequence: ${seq.join(' ')}  (total ${ms}ms)`);
  // expect: 200:A ×3 → A 429→cooldown → B 200 ×3 → B 429 → both cooling → wait ~2s → A 200 (revived, first in order)
  assert.deepEqual(seq.slice(0, 3).map(s => s.startsWith('200:MockKey-A')), [true, true, true], 'first 3 all on key A');
  assert.ok(seq.slice(3, 6).every(s => s.startsWith('200:MockKey-B')), 'next 3 all on key B');
  assert.ok(seq[6].startsWith('200:MockKey-A'), 'after cooldowns, back to first key A');
  assert.ok(ms > 1800 && ms < 9000, 'relay waited out the 2s cooldown instead of failing');
  assert.ok(relay.engine.stats.rateLimited >= 1, '429s detected and handled (mock tight window may cause several)');
  assert.equal(relay.engine.stats.relayed, 8);
  console.log('✅ TEST 3  sequential exhaustion A×3 → B×3 → cooldown wait → A revival (strict order, no round-robin)');
}

// ---- TEST 4: all-limited → bounded wait → clean 429 to client ----
{
  CFG.cooldownSec = 30; CFG.maxWaitSec = 1; // make wait exceed max → immediate 429
  mock429Hits = {}; mock429Hits['Bearer sk-mock-A'] = [Date.now(), Date.now(), Date.now()]; // A exhausted now
  mock429Hits['Bearer sk-mock-B'] = [Date.now(), Date.now(), Date.now()];
  kA.cooldownUntil = 0; kB.cooldownUntil = 0;
  const t0 = Date.now();
  const res = await post(8849, '/v1/chat/completions', { model: 'm', messages: [{ role: 'user', content: 'hi' }] });
  const body = JSON.parse(await readAll(res));
  const ms = Date.now() - t0;
  assert.equal(res.statusCode, 429, 'client gets 429 when maxWait exceeded');
  assert.equal(res.headers['retry-after'], '30');
  assert.equal(body.error.type, 'kiosa_relay_all_limited');
  assert.ok(ms < 1500, 'fails fast when maxWait small');
  console.log('✅ TEST 4  all-limited → Retry-After 429 to client (fail fast respected)');
}

mock.close();
relay.server.close();
console.log('\nALL MOCK TESTS PASSED');
process.exit(0);

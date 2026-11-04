# Kiosa Relay — Test Results

The relay strategy and the Anthropic↔OpenAI translation layer were **tested locally before building the APK**, first against a scripted mock upstream, then against the **real KiosAPI production endpoint**.

Reproduce (needs Node 18+, keys as env vars — never hardcode):

```bash
cd proto
node test-mock.mjs                      # no quota used
KIOSA_KEY1=sk-... KIOSA_KEY2=sk-... node test-real.mjs   # uses ~5 tiny upstream requests
```

## 1. Mock tests (`test-mock.mjs`)

| # | Test | Result |
|---|------|--------|
| 1 | Anthropic `/v1/messages` request → OpenAI translation (system, tools, tool_choice, max_tokens) + response → Anthropic (tool_use blocks, stop_reason, usage) | ✅ PASS |
| 2 | Streaming SSE bridge: OpenAI chunks (thinking + text + tool_call deltas) → valid Anthropic event sequence (`message_start` → `content_block_*` → `message_delta` → `message_stop`), tool args reassemble to valid JSON | ✅ PASS |
| 3 | Sequential exhaustion: `A×3 → B×3 → cooldown wait → A revival`, strict order, **no round-robin** | ✅ PASS |
| 4 | All keys cooling + max-wait exceeded → clean `429` + `Retry-After` to client, fail fast | ✅ PASS |

Observed sequence in test 3:

```
200:Key-A → 200:Key-A → 200:Key-A → 200:Key-B → 200:Key-B → 200:Key-B → 200:Key-A → 200:Key-A
```

## 2. Real API test (`test-real.mjs`, production kiosapi.com)

Setup: two API keys from the **same account**, relay defaults (60 s cooldown, max wait 120 s). Four tiny requests (`max_tokens: 5`) fired sequentially through the relay:

```
[+6.7s]  request 1: HTTP 200 via Key-1
[+7.6s]  request 2: HTTP 200 via Key-1
[+8.5s]  request 3: HTTP 200 via Key-1      <- 3 RPM burned
[+8.5s]  Key-1: 429 -> cooldown 60s
[+8.6s]  Key-2: 429 -> cooldown 60s         <- SAME ACCOUNT: shared limit!
[+8.6s]  pool: all keys cooling -> waiting 60s for revival
[+74.7s] request 4: HTTP 200 via Key-1      <- auto-revived; client never saw an error
```

Engine log from the run:

```
01:34:23 [ok]       Key-1: /v1/chat/completions 200 via Key-1 (+6.7s)
01:34:24 [ok]       Key-1: /v1/chat/completions 200 via Key-1 (+0.9s)
01:34:25 [ok]       Key-1: /v1/chat/completions 200 via Key-1 (+0.8s)
01:34:25 [cooldown] Key-1: 429 -> cooldown 60s
01:34:26 [cooldown] Key-2: 429 -> cooldown 60s
01:34:26 [wait]     (pool): all keys cooling -> waiting 60s for revival
01:35:31 [ok]       Key-1: /v1/chat/completions 200 via Key-1 (+66.2s)
```

## 3. Findings about KiosAPI rate limits

| Question | Answer (measured) |
|----------|-------------------|
| Is the 3 RPM limit per key or per account? | **Per account.** A second key on the same account got an instant `429` right after the first key burned 3 requests. |
| Does the API send rate-limit headers? | No `x-ratelimit-*` / `Retry-After` headers. The only signal is the `429` JSON body: *"You have reached the request limit: maximum 3 requests in 1 minutes"*. |
| When does the window reset? | ~60 s after the burn; a request after ~65 s succeeded. |
| Practical takeaway | Multiple keys only add throughput if they come from **different accounts**. The relay still manages each key independently, so both setups work. |

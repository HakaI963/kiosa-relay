# Kiosa Relay

**Android app that pools multiple [KiosAPI](https://kiosapi.com) keys behind ONE local endpoint your coding agents can use.**

Not a round-robin load balancer. Keys are used **strictly in order — key 1 until its 3 RPM is exhausted, then key 2, then key 3…** — with automatic 60 s cooldowns on rate limits and a live status dashboard.

```
Claude Code ─┐                                   ┌─ key 1 (account A)  used until 429
Codex CLI  ──┼─► http://phone:8849  [Kiosa Relay]├─ key 2 (account B)  used until 429
Cline etc  ──┘        sequential + cooldown      └─ key 3 (account C)  …
```

- **OpenAI-compatible**: `POST /v1/chat/completions` (streaming), `/v1/models`
- **Anthropic-compatible**: `POST /v1/messages` + streaming SSE — Claude Code connects natively
- **One local URL + access key** for all your agents
- Live dashboard: per-key status, cooldown countdowns, counters, request log
- 100% local: keys live encrypted on-device, nothing is committed or synced

## Install

1. Go to **[Actions](https://github.com/HakaI963/kiosa-relay/actions)** → open the latest **Build APK** run → download the **kiosa-relay-apks** artifact
2. Unzip, copy `KiosaRelay-debug.apk` to your phone and install it (allow "install unknown apps")
3. (Tagged versions also appear under [Releases](https://github.com/HakaI963/kiosa-relay/releases))

> APKs are built **only** by GitHub Actions — there is no local Gradle/NDK requirement.

## Setup

1. **Keys tab** → *+ Add key* → paste one or more `sk-…` keys (order matters — that's the usage order)
2. **Relay tab** → **Start**
3. Copy the shown URL + access key into your agent:

**Claude Code** (native Anthropic endpoint):
```bash
export ANTHROPIC_BASE_URL=http://<phone-ip>:8849
export ANTHROPIC_AUTH_TOKEN=<relay access key>
```

**OpenAI-style agents** (Codex CLI, Cline, Continue, Aider, OpenCode…):
```bash
base_url = http://<phone-ip>:8849/v1
api_key  = <relay access key>
```

Phone and computer must share the same Wi-Fi. Test it: `curl http://<phone-ip>:8849/relay/status -H "Authorization: Bearer <access key>"`

## How the relay decides

| Situation | Behaviour |
|-----------|-----------|
| Key has quota | Requests go to the **first key in list order** that is enabled & not cooling (strict sequence, never round-robin) |
| `429` rate limit | Key gets a **60 s cooldown** (configurable), next key takes over instantly |
| Other upstream error | Short cooldown (15 s default) and try next key |
| Auth error (401/403) | Long cooldown (5 min) so bad keys don't burn quota |
| **All** keys cooling | Relay **waits** for the soonest revival (up to 90 s) so agents don't crash; beyond that a clean `429` + `Retry-After` is returned |
| `/v1/models` | Served directly, does not count against the chat quota |

> **Measured fact** (see [proto/TEST-RESULTS.md](proto/TEST-RESULTS.md)): the 3 RPM limit is enforced **per account, not per key** — two keys of one account share a single counter. Extra keys only add throughput when they come from different accounts. The app treats every key independently either way.

## Options

| Setting | Default | Where |
|---------|---------|-------|
| Port | 8849 | Settings |
| Listen on LAN vs localhost | LAN | Settings |
| Relay access key (generate/regen) | required | Settings + Relay tab |
| 429 cooldown | 60 s | Settings |
| Error / auth cooldowns | 15 s / 300 s | Settings |
| Max wait when all keys cooling | 90 s (0 = fail fast) | Settings |
| Upstream request timeout | 300 s | Settings |
| Upstream base URL | https://kiosapi.com/v1 | Settings |
| Pause relay (503 new requests) | off | Relay tab |
| Theme | Light-only (white / black ink / light cyan / cyan-grey palette) | — |
| Keys: add, edit, delete, reorder, enable/disable, live test | — | Keys tab |

## Build

No local Android toolchain needed — push to `main` (or run the workflow manually) and GitHub Actions builds debug + release APKs. Tag `v*` to publish a GitHub Release with APKs attached.

## Local test suite

`proto/` contains the zero-dependency Node prototype that mirrors the app's engine 1:1:

```bash
cd proto && node test-mock.mjs          # strategy + translation, no quota used
KIOSA_KEY1=sk-… KIOSA_KEY2=sk-… node test-real.mjs   # live validation (~5 tiny requests)
```

## License

MIT

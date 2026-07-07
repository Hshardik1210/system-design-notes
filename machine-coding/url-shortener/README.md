# 🔗 URL Shortener (TinyURL)

Turn a long URL into a short link and expand it back.

## Approach: base62 of an auto-increment ID
1. Assign each new URL a unique numeric **id** (counter).
2. **base62-encode** the id using `[0-9a-z A-Z]` → compact short code (`1`, `2`, … `Z`, `10`, …).
3. Store `code → url` (expand) and `url → code` (idempotent re-shortening).

### Why encode an ID instead of hashing the URL?
- **No collisions** — IDs are unique by construction (hashing needs collision handling).
- **Short & scalable** — `62^7 ≈ 3.5 trillion` codes fit in just 7 characters.

## Features
- **Idempotent** `shorten` — same long URL returns the same code.
- **Custom aliases** — `sho.rt/linux`, rejected if already taken.
- **Expand** — short link → original URL.

## Scaling note
In production the counter becomes a **distributed ID generator** (Snowflake / range-based / KGS) and the maps become a KV store + cache. See `system-design/url-shortener-system-design.md` and `system-design/distributed-id-generator-system-design.md`.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Singleton (service) | `UrlShortener` | Single shared mapping store |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```

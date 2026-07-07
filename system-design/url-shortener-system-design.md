# URL Shortener — System Design

> **Core challenge:** turn a long URL into a short, unique code, then **redirect at very low latency** under a **read-heavy** load (reads ≫ writes, ~100:1). The hard parts are **generating unique short codes without a bottleneck**, **serving redirects from cache/edge**, and **surviving hot links + abuse**. Examples: bit.ly, TinyURL, t.co (Twitter), lnkd.in (LinkedIn).

> **How to read this doc:** each section has the dense interview summary first, then a **Plain-English** deep dive (bit.ly/TinyURL analogies, annotated example code, and the exact confusions that trip up beginners). Skim the summaries for revision; read the plain-English parts to actually understand.

---

## Contents

- [1. What Is a URL Shortener?](#1-what-is-a-url-shortener)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. API Design](#4-api-design)
- [5. The Short Code — Encoding](#5-the-short-code--encoding)
- [6. Distributed ID Generation](#6-distributed-id-generation)
- [7. Data Model & Schema](#7-data-model--schema)
- [8. High-Level Architecture](#8-high-level-architecture)
- [9. Core Flows](#9-core-flows)
- [10. Caching Deep Dive](#10-caching-deep-dive)
- [11. Redirect: 301 vs 302](#11-redirect-301-vs-302)
- [12. Analytics Pipeline](#12-analytics-pipeline)
- [13. Consistency & Cache Invalidation](#13-consistency--cache-invalidation)
- [14. Scaling the System](#14-scaling-the-system)
- [15. Security & Abuse Prevention](#15-security--abuse-prevention)
- [16. Failure Scenarios & Mitigations](#16-failure-scenarios--mitigations)
- [17. Observability](#17-observability)
- [18. Final Architecture](#18-final-architecture)
- [19. How to Drive the Interview](#19-how-to-drive-the-interview)
- [20. Interview Cheat Sheet](#20-interview-cheat-sheet)
- [21. Design Patterns (that can be used)](#21-design-patterns-that-can-be-used)
- [22. Final Takeaways](#22-final-takeaways)

---

## 1. What Is a URL Shortener?

A service that maps a **short code** to a **long URL** and redirects on lookup.

```
create:   long URL ──► [ generate unique code ] ──► short URL (sho.rt/aB3xY7)
redirect: sho.rt/aB3xY7 ──► [ code → long URL lookup ] ──► HTTP 301/302 → long URL
```

### Mental model

| Layer | Role |
| --- | --- |
| **Code generator** | Produce a unique, short, URL-safe code — the only "hard" write-path problem |
| **KV store** | `code → long_url` — essentially one giant hash map |
| **Cache / CDN** | Absorb the read-heavy redirect traffic; DB is rarely touched |
| **Analytics pipeline** | Async click tracking — never blocks the redirect |

> The whole system is **two endpoints** (`create`, `redirect`) over a **key-value map**. The interview is about doing that at **billions of rows, tens of thousands of reads/sec, low latency, and no duplicate codes** — plus abuse handling.

### Plain-English: what problem are we even solving?

Think about **bit.ly** or **TinyURL**. You have an ugly, gigantic link:

```
https://www.airbnb.com/rooms/12345678?check_in=2026-08-01&check_out=2026-08-07&adults=2&source=newsletter&utm_campaign=summer
```

…and you want to paste it into a tweet, a text message, or say it out loud. So you feed it to bit.ly and get back something tiny:

```
https://bit.ly/aB3xY7
```

Now `aB3xY7` is the **short code**. When anyone opens `bit.ly/aB3xY7`, bit.ly looks up "what long URL does `aB3xY7` point to?" and **bounces the browser** to the real Airbnb page. That "bounce" is an HTTP **redirect** (§11).

So the entire product is really just a **two-column notebook**:

```
short code   →   long URL
aB3xY7       →   https://www.airbnb.com/rooms/12345678?...
tk9Qm2       →   https://docs.google.com/document/d/...
```

- **Writing** a new row (create a short link) happens rarely.
- **Reading** a row (someone clicks a short link) happens constantly — a popular link can be clicked millions of times.

That imbalance is the single most important fact about this system.

### Plain-English: why is this "hard" if it's just a notebook?

A `HashMap<String, String>` on your laptop does exactly this. It gets hard only because of **scale + a few rules**:

| Beginner question | Short answer |
| --- | --- |
| Why not just a `HashMap`? | It's on one machine, in RAM. It dies when the process restarts (you'd lose every link forever) and can't hold billions of entries or serve the whole world. |
| What makes it read-heavy? | People **create** a link once but **click** it thousands of times. Reads ≫ writes (~100:1), so we optimize the *read* (redirect) path above all. |
| What's the actual hard part? | (1) Generating a **short, unique** code with no two links colliding, and (2) serving the redirect **fast** (from cache/CDN) even when a link goes viral. |

> Analogy: it's like a **coat check** at a theater. You hand over your coat (long URL) and get a small numbered ticket (short code). Later you show the ticket and get your coat back (redirect). Making a ticket is quick and rare; thousands of people redeeming tickets at once is the busy part.

---

## 2. Requirements

> 💡 **Start here.** Clarify scope before designing.

### Functional

| # | Requirement |
| --- | --- |
| 1 | Create a short URL from a long URL (`POST /shorten`) |
| 2 | Redirect a short URL → original long URL (`GET /{code}`) |
| 3 | **Custom alias** (vanity codes: `sho.rt/my-brand`) |
| 4 | **Expiry** — links can have a TTL, then stop resolving |
| 5 | **Analytics** — click counts, geo, referrer, device (optional but common) |
| 6 | Link management — user accounts, list/update/delete their links |

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Read-heavy** | Redirects ≫ creations (~100:1) — cache + replicas dominate |
| **Low latency** | Redirect p99 < ~50 ms (it's on the user's critical path) |
| **High availability** | A redirect outage breaks **every** link ever created — target 99.99% |
| **Scalable** | Billions of URLs; tens of thousands of redirects/sec at peak |
| **Durable** | Never lose a mapping — a lost row = a permanently dead link |
| **Unique codes** | No two long URLs share a code; no collisions at write time |
| **Non-enumerable** (optional) | Codes shouldn't be trivially guessable/scrapeable |

### Out of scope (state assumptions)

- Full analytics product (dashboards, funnels) — mention, defer.
- Link preview / unfurling.
- Multi-region active-active — mention as a follow-up for global low latency + DR.

> **Consistency stance:** the `code → long_url` mapping is effectively **immutable once created** (write-once, read-many). That is the single most important property — it makes caching trivially safe and lets us relax to **eventual consistency** on read replicas (except right after create — see [§13](#13-consistency--cache-invalidation)).

---

## 3. Capacity Estimation

> Numbers are illustrative — show the **method**.

```
Assume:
  New URLs / month            ~ 100M
  Read:write ratio            ~ 100:1
  Retention                   ~ 5 years

Write QPS:
  100M / (30 * 86,400)        ~ 40 writes/sec (avg)
  Peak (5x)                   ~ 200 writes/sec

Read QPS (redirects):
  40 * 100                    ~ 4,000 reads/sec (avg)
  Peak (5x)                   ~ 20,000 reads/sec

Storage:
  100M/mo * 12 * 5yr          ~ 6B rows
  ~500 bytes/row (url + meta) ~ 3 TB  (partition + archive expired)

Read bandwidth (from store):
  4,000 rps * ~500 B          ~ 2 MB/s  (tiny — redirect response is just a header)

Cache sizing (the important one):
  80/20 rule → ~20% of links drive ~80% of clicks
  Cache the hot working set, say 100M entries
  ~100 bytes/entry (code + url)   ~ 10–20 GB → fits in a Redis cluster easily

Keyspace (base62):
  62^7 ~ 3.5 trillion  ← length 7 covers 6B rows with enormous headroom
```

**Takeaways that drive design:**

- **Cache + CDN dominate** — at 100:1 the redirect path must almost never touch the DB.
- **Writes are tiny** — the only write-path challenge is **collision-free unique code generation**, not throughput.
- **Store is a KV map** — 6B single-key lookups → **NoSQL KV (DynamoDB/Cassandra)** or sharded SQL.
- **Durability matters more than write speed** — a lost mapping is a dead link forever.

---

## 4. API Design

### Create short URL

```
POST /v1/shorten
Authorization: Bearer <token>       # or anonymous with stricter rate limits

{
  "longUrl": "https://example.com/very/long/path?x=1",
  "customAlias": "my-brand",        # optional
  "expiresAt": "2027-01-01T00:00:00Z",  # optional
  "userId": 123                     # optional
}
```

```
201 Created
{
  "code": "aB3xY7",
  "shortUrl": "https://sho.rt/aB3xY7",
  "longUrl": "https://example.com/very/long/path?x=1",
  "expiresAt": "2027-01-01T00:00:00Z"
}

409 Conflict        # custom alias already taken
400 Bad Request     # malformed / disallowed URL
429 Too Many Requests   # rate limited
```

### Redirect (the hot path)

```
GET /{code}

301 Moved Permanently   (or 302 Found)
Location: https://example.com/very/long/path?x=1

404 Not Found       # unknown code
410 Gone            # expired or disabled link
```

### Link management & analytics

```
GET    /v1/urls/{code}                 # metadata (owner only)
DELETE /v1/urls/{code}                  # disable a link (owner only)
GET    /v1/urls/{code}/stats?from=&to=  # click analytics
GET    /v1/users/{userId}/urls?cursor=&limit=20
```

> **Design note:** `POST /shorten` and `GET /{code}` are served by **separate services** (write vs read) so the read path can scale independently and stay dead-simple. The redirect endpoint does the **least work possible** — lookup + 301/302, everything else (analytics) is async.

---

## 5. The Short Code — Encoding

Use **base62** (`[a-z A-Z 0-9]`, 62 symbols): compact and fully URL-safe.

```
62^6  ~ 56.8 billion
62^7  ~ 3.52 trillion    ← length 7 is plenty for 6B rows
```

### Why base62 (not base64 or hex)?

| Encoding | Alphabet | Problem |
| --- | --- | --- |
| **hex (base16)** | `0-9a-f` | codes too long (16^n grows slowly → need ~10 chars) |
| **base64** | `A-Za-z0-9+/=` | `+`, `/`, `=` are **not URL-safe** (need escaping); `=` padding is ugly |
| **base62** ✅ | `A-Za-z0-9` | all URL-safe, no escaping, compact |

> base64url (`-` and `_` instead of `+/`) is also URL-safe, but base62 avoids any special chars entirely — cleaner for humans typing/sharing.

### Approaches to generate the code

| Approach | How | Pros | Cons |
| --- | --- | --- | --- |
| **Counter → base62** | encode a globally-unique increasing integer | simple, guaranteed unique, shortest codes | sequential = **enumerable/guessable**; naive single counter is a bottleneck |
| **Random + check** | random base62, check collision in DB | non-sequential (not guessable) | collision probability + a **DB read per create** as the space fills |
| **Hash + truncate** | `base62(hash(longUrl))[:7]` | **dedups** identical URLs for free | truncation **collisions** must be resolved (rehash/salt) |
| **KGS (Key Generation Service)** | pre-generate unique keys offline, hand them out | **no collision check at write time**, fast, non-sequential | needs a key store + concurrency control + is a dependency |

> **Common pick:** a **counter with range allocation** (shortest codes, no per-write coordination) or a **KGS** (non-enumerable, no write-time collision check). If you must dedup identical long URLs, add a **hash index on `long_url`** rather than hashing into the code.

### Enumeration / security

Sequential counter → `base62(1), base62(2), …` are adjacent codes → an attacker can **walk the whole namespace** and scrape every link. Mitigate by:

- Using a **KGS with random keys**, or
- **Salting/permuting** the counter (e.g., multiply by a large coprime mod keyspace, or Feistel/`XOR` scramble) so consecutive IDs map to scattered codes.

### Plain-English: what "base62" actually means

You already know **base10** (normal numbers): 10 digits `0-9`. When you run out (9 → 10) you add a new place. **Base62** is the same idea with **62 symbols** instead of 10:

```
digits:      0 1 2 3 4 5 6 7 8 9         (10 symbols)  → base10
base62:      0-9  a-z  A-Z               (10+26+26 = 62 symbols) → base62
```

Because each character can be one of **62** values (not 10), you pack **way more information into fewer characters**. That's the whole point — short codes.

```
base10:  "1000000"     needs 7 characters to reach one million
base62:  "4c92"        reaches ~one million in just 4 characters
```

Here's the mental model: **base62 is just "counting in a 62-symbol number system."** We take a normal integer (an ID like `125`) and rewrite it using those 62 symbols.

#### Annotated example: turning a number into a short code

```java
// The 62 symbols, in order. Index 0='0', index 10='a', index 36='A', etc.
static final String ALPHABET =
    "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
static final int BASE = 62;   // there are 62 symbols

// Turn an integer id into a base62 short code (e.g. 125 -> "21")
static String encode(long id) {
    StringBuilder sb = new StringBuilder();
    while (id > 0) {
        int remainder = (int) (id % BASE);      // which symbol for this "place"
        sb.append(ALPHABET.charAt(remainder));  // look it up in the alphabet
        id = id / BASE;                          // move to the next place
    }
    return sb.reverse().toString();              // we built it backwards
}

// Turn a short code back into the integer (e.g. "21" -> 125)
static long decode(String code) {
    long id = 0;
    for (char c : code.toCharArray()) {
        id = id * BASE + ALPHABET.indexOf(c);    // reverse of encode
    }
    return id;
}
```

Walking through `encode(125)`:

```
125 % 62 = 1   → symbol '1' ,  125 / 62 = 2
  2 % 62 = 2   → symbol '2' ,    2 / 62 = 0  → stop
built backwards: "12" → reverse → "21"
so id 125  →  code "21"
```

> Analogy: it's like an **odometer with 62 digits on each wheel** instead of 10. The wheels just roll over less often, so you reach big numbers with fewer wheels (shorter codes).

### Plain-English: base62 Q&A

#### Q: Why 62? Why not use all 64 base64 symbols to be even shorter?

base64 includes `+`, `/`, and `=`. Those have **special meaning inside a URL** (`/` is a path separator, `+` can mean space, `=` is padding), so they'd need escaping and look ugly. base62 sticks to only letters and digits — everything is **safe to put in a URL and easy to type or read aloud**. You give up almost nothing in length.

#### Q: If I encode 1, 2, 3… won't the codes be guessable?

Yes — that's the trap. Sequential IDs make **adjacent codes**:

```
id 1 → "1",  id 2 → "2",  id 61 → "z",  id 62 → "10", ...
```

An attacker can just walk `1, 2, 3, ...` and scrape **every** link in your system. Fixes: use **random** pre-generated codes (KGS, §6), or **scramble** the counter before encoding so `126` doesn't sit next to `125`. Encoding itself doesn't hide anything; the *number you feed it* is what must be non-sequential.

#### Q: Counter vs random vs hash — which do I pick? (the classic confusion)

These are three different ways to get the **number/code**, and they trade off differently:

| Approach | One-line idea | Collisions? | Guessable? | Best when |
| --- | --- | --- | --- | --- |
| **Counter → base62** | just keep incrementing a global number | **Never** (each number used once) | **Yes** (sequential) unless scrambled | You want the **shortest** codes |
| **Random + check** | pick a random code, ask DB "taken?" | **Possible** → must retry on clash | No (random) | You want non-guessable, simple |
| **Hash(longURL) → truncate** | code = first 7 chars of a hash | **Possible** (truncation) → rehash | No | You want to **dedup identical URLs** for free |

```java
// Counter: guaranteed-unique number, then encode → shortest, but sequential
String code = encode(nextId());            // nextId() = 1,2,3,...

// Random: might already exist, so loop until INSERT succeeds
String code;
do { code = randomBase62(7); } while (db.exists(code));   // extra DB read

// Hash: same long URL always yields the same code (natural dedup)
String code = base62(sha256(longUrl)).substring(0, 7);    // may truncate-collide
```

#### Q: What exactly is a "collision" here, and why do only some approaches have it?

A **collision** = two different links accidentally get the **same short code**, which would send people to the wrong site. A pure **counter never collides** because every number is handed out exactly once. **Random** and **hash-truncate** *can* collide (two rolls/two URLs land on the same code), so those need a "check and retry" step. This is why interviewers like counter/KGS: **collision-free by construction**, no retry loop.

---

## 6. Distributed ID Generation

A single DB `AUTO_INCREMENT` is a **SPOF + write bottleneck** and blocks sharding. Options:

### Option A — Range allocation (ticket/counter server) ✅ simplest scalable

```
A central store holds a global counter.
Each app node claims a BLOCK (e.g. 1,000 ids) in one atomic op:
    range = [start, start+1000)
Node serves ids locally from its block; refills when exhausted.
```

| Property | Value |
| --- | --- |
| Coordination | Once per 1,000 creates (not per create) |
| Codes | **Shortest** (small integers → 6–7 chars) |
| Failure | A node crash "loses" its unused block — fine (keyspace is huge) |
| Store | Redis `INCRBY 1000`, ZooKeeper, or a single-row DB counter |

### Option B — Snowflake-style IDs (no coordination)

```
64-bit: | 1 sign | 41 bits timestamp(ms) | 10 bits machineId | 12 bits sequence |
        ~69 years             1024 machines        4096 ids/ms/machine
```

- **No central counter** — each machine generates locally, roughly time-ordered.
- ⚠️ **Trade-off:** a full 64-bit Snowflake ID base62-encodes to **~11 characters**, not 7 — longer codes. Good when you don't need the shortest possible code; range allocation wins when you do.

### Option C — KGS (Key Generation Service)

```
Offline job pre-generates random unique 7-char keys → `keys` table (state = AVAILABLE)
App servers fetch a batch (e.g. 1,000) into memory, marking them USED atomically
On create: pop a key from the in-memory batch — no collision check needed
```

- **Concurrency:** moving a key AVAILABLE→USED must be atomic so no two servers get the same key (row lock / conditional update / move between tables).
- **HA:** KGS is a dependency → replicate it; app servers buffering a batch in memory survive brief KGS outages.
- **Non-enumerable** by construction (keys are random).

> **Interview one-liner:** "I'd use range allocation off a central counter — nodes grab blocks of 1,000 IDs, so coordination is rare and codes stay short. If enumeration is a concern I'd use a KGS with random pre-generated keys, or scramble the counter before base62."

### Plain-English: why can't one machine just do `count++`?

A single counter (`AUTO_INCREMENT` or one variable) is the obvious answer, and it's *correct* — the problem is it doesn't **scale** and it's a **single point of failure**:

- Every create anywhere in the world must talk to that **one** counter → it becomes a bottleneck and a queue.
- If that one machine dies, **nobody can create links** until it's back.

But we run **many** app servers for scale. If each one keeps its own counter, they'd all hand out `1, 2, 3…` and **collide**. So we need a way for many servers to mint unique numbers **without constantly coordinating**.

> Analogy: a **deli counter with "take a number" tickets**. If there were one shared ticket printer and everyone had to walk to it for a single ticket, there'd be a huge line. Instead, each clerk tears off a **strip of 100 tickets** at once and hands them out from their own strip. They only walk back to the printer when their strip runs out.

### Plain-English: range allocation with code

That "grab a strip of 100" is exactly **range allocation** — the most common answer.

```java
@Component
public class RangeIdGenerator {

    // the numbers THIS server currently owns and can hand out with no coordination
    private long nextId;      // next id to give out
    private long blockEnd;    // one past the last id in our current block
    private static final int BLOCK_SIZE = 1000;

    // Hand out the next id. Almost always a pure in-memory increment (fast!).
    public synchronized long next() {
        if (nextId >= blockEnd) {   // our block is used up → go claim a new one
            claimNewBlock();
        }
        return nextId++;            // local, no network call
    }

    // The ONLY step that coordinates — happens once per 1,000 creates.
    private void claimNewBlock() {
        // Atomically bump the global counter by 1000 and get the new value back.
        // Redis: INCRBY global_counter 1000  (atomic → two servers can't get the same range)
        long newEnd = redis.incrBy("global_counter", BLOCK_SIZE);
        this.blockEnd = newEnd;
        this.nextId   = newEnd - BLOCK_SIZE;   // our block = [newEnd-1000, newEnd)
    }
}
```

What this buys us:

```
Server A claims [1000, 2000)   →  hands out 1000,1001,... locally, no network
Server B claims [2000, 3000)   →  hands out 2000,2001,... locally, no network
```

- **Coordination is rare** — one atomic op per **1,000** creates, not per create.
- **No collisions** — Redis's atomic `INCRBY` guarantees no two servers get the same block.
- **Crash is cheap** — if Server A dies with unused ids in `[1000,2000)`, those numbers are just **skipped forever**. The keyspace is trillions, so we don't care about gaps.

### Plain-English: ID generation Q&A

#### Q: Range allocator vs Snowflake vs KGS — when do I reach for each?

| You want… | Use | Why / catch |
| --- | --- | --- |
| The **shortest** codes | **Range allocation** | Small integers → 6–7 chars. Needs one central counter (but touched rarely). |
| **Zero** coordination, even with the counter | **Snowflake** (time + machineId + sequence) | Each machine mints locally. Catch: 64-bit ID → **~11-char** code (longer). |
| **Non-guessable** codes with no write-time check | **KGS** (pre-made random keys) | A batch of keys is pre-generated; servers just pop one. Needs its own key store. |

#### Q: What is a KGS and how is it different from a counter?

**KGS = Key Generation Service.** Instead of computing a code at create-time, an **offline job** pre-generates a big pile of **random, unique** 7-char codes and stores them as `AVAILABLE`. App servers grab a batch into memory and hand them out.

```java
// Startup / when running low: claim a batch, marking them USED atomically
List<String> myKeys = kgs.fetchBatch(1000);   // e.g. ["9fKq2a", "Xt7wLp", ...]

// On create: just pop one. No collision check, no math — it's pre-guaranteed unique.
String code = myKeys.remove(myKeys.size() - 1);
```

Difference from a counter: a counter produces **sequential** numbers (guessable); a KGS produces **random** codes (non-enumerable) and never needs a write-time collision check because uniqueness was guaranteed when they were generated. Cost: you maintain a key store and its concurrency control.

---

## 7. Data Model & Schema

### URL mapping (the core table)

```sql
CREATE TABLE url (
    code        VARCHAR(10) PRIMARY KEY,   -- base62 short code (also the shard key)
    long_url    TEXT NOT NULL,
    user_id     BIGINT,                    -- optional owner
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,                 -- NULL = never expires
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,  -- soft delete / disable
    long_url_hash CHAR(64)                 -- optional: for dedup of identical URLs
);

-- Optional dedup: return existing code for an identical long URL
CREATE INDEX idx_url_hash ON url (long_url_hash);
```

- The store is essentially a **giant key-value map** (`code → long_url`) → a **KV store (DynamoDB, Cassandra)** fits naturally; or **sharded SQL**.
- **Shard by `code`** (hash / consistent hashing): every read and write is a **single-key lookup** — no cross-shard queries, scales linearly.
- Mapping rows are **write-once/immutable** (except `is_active`/`expires_at`) → caching is safe with long TTLs.

### SQL vs NoSQL

| Need | Choice | Why |
| --- | --- | --- |
| 6B single-key lookups, low latency, horizontal scale | **NoSQL KV (DynamoDB/Cassandra)** | built for exactly this access pattern; auto-sharding, replication |
| Strong secondary-index/transaction needs (small scale) | **Sharded SQL** | familiar, ACID; but you shard manually |

### Analytics (separate store — never on the hot path)

```sql
-- Raw click events (append-only, often in a warehouse / column store)
CREATE TABLE click_events (
    code        VARCHAR(10),
    clicked_at  TIMESTAMP,
    ip          INET,
    country     VARCHAR(2),
    referrer    TEXT,
    user_agent  TEXT,
    device      VARCHAR(20)
);

-- Pre-aggregated counters for fast stats reads
CREATE TABLE click_stats_daily (
    code        VARCHAR(10),
    day         DATE,
    clicks      BIGINT,
    PRIMARY KEY (code, day)
);
```

> **Keep analytics off the mapping store.** Click writes are high-volume and would compete with redirect reads. Emit events to Kafka → stream processor → analytics DB/warehouse ([§12](#12-analytics-pipeline)).

### Custom alias

Custom aliases live in the **same `url` table** — `code` is just user-supplied instead of generated. Uniqueness is enforced by the `PRIMARY KEY`; reject with `409` if taken. Reserve system words (`api`, `admin`, `login`) via a blocklist.

### Plain-English: the data model is basically one big dictionary

Forget "database" for a second. The core storage is a **dictionary / hash map**:

```
{
  "aB3xY7": "https://www.airbnb.com/rooms/12345678?...",
  "tk9Qm2": "https://docs.google.com/document/d/...",
  "my-brand": "https://mycompany.com/landing"
}
```

- The **key** is the short code (what's in the URL).
- The **value** is the long URL (plus a little metadata: owner, expiry, active flag).

Every operation is a **single-key lookup** — "give me the value for key `aB3xY7`." There are **no** joins, no "find all URLs where…", no range scans on the hot path. That's why a **key-value store** (DynamoDB, Cassandra) fits so perfectly, and why it scales: single-key lookups shard cleanly across machines.

> Analogy: it's a **phone book** where you always know the exact name you're looking up. You never "scan the whole book" — you jump straight to the entry. That's the fastest thing a database can do.

#### Reading the schema line by line

```sql
CREATE TABLE url (
    code        VARCHAR(10) PRIMARY KEY,   -- the short code; PRIMARY KEY = must be UNIQUE
    long_url    TEXT NOT NULL,             -- where we send the user
    user_id     BIGINT,                    -- who made it (optional)
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,                 -- NULL = lives forever; else stop resolving after this
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,  -- flip to FALSE to "delete" without erasing the row
    long_url_hash CHAR(64)                 -- fingerprint of long_url, only if we want dedup
);
```

Two beginner-facing ideas hide in there:

- **`PRIMARY KEY` on `code`** is what makes collisions *impossible to store*. If two creates ever tried the same code, the second `INSERT` fails — the database itself is our safety net.
- **`is_active` = soft delete.** Instead of truly deleting a row (which loses history and can break analytics), we just flip a flag. The redirect checks it and returns `410 Gone`.

### Plain-English: data model Q&A

#### Q: Why store `long_url_hash` — isn't the long URL already there?

It's a **fingerprint** (e.g. SHA-256) of the long URL, used only for **deduplication**: "has someone already shortened this exact URL?" Comparing/indexing a fixed 64-char hash is far cheaper than indexing a giant `TEXT` column. On create you look up the hash; if found, hand back the existing code instead of minting a new one.

```sql
-- "Did we already shorten this exact long URL?" — one indexed lookup
SELECT code FROM url WHERE long_url_hash = :hashOfIncomingUrl;
```

Trade-off (noted in §19): dedup saves space but means two users share one code — a mild privacy leak (one can see the other's click stats), so it's optional.

#### Q: Why keep analytics (`click_events`) in a *separate* store?

Because they have **opposite** access patterns. The `url` table is read constantly and must stay lean and fast. `click_events` is **append-only and huge** (a row per click). If you wrote clicks into the same store, those heavy writes would fight with your all-important redirect reads. So clicks go to a **column store / warehouse** via Kafka (§12), completely off the hot path.

#### Q: What does "shard by `code`" mean and why that column?

**Sharding** = splitting the table across many machines so no single machine holds all 6B rows. We split by `code` because **every** query already knows the code (`GET /aB3xY7`), so we can compute exactly which machine holds it — `shard = hash(code) % N` — and go straight there. One hop, no fan-out.

---

## 8. High-Level Architecture

```
                        ┌─────────┐
   Client ─────────────►│   CDN   │  edge-cache redirects (esp. 301s)
                        └────┬────┘
                             │ miss
                             ▼
                      ┌───────────────┐
                      │  API Gateway  │  TLS, auth, rate limiting
                      └───────┬───────┘
             ┌───────────────┴────────────────┐
             ▼ (writes)                        ▼ (reads ≫ writes)
      ┌──────────────┐                  ┌──────────────┐
      │ Write Service│                  │ Read Service │  (redirects only)
      │  /shorten    │                  │   /{code}    │
      └──────┬───────┘                  └──────┬───────┘
             │                                 │
             ▼                                 ▼
     ┌───────────────┐                 ┌───────────────┐
     │ ID gen / KGS  │                 │  Redis cache  │◄─ hot codes (cache-aside)
     └──────┬────────┘                 └──────┬────────┘
            │                                 │ miss
            └───────────────┬─────────────────┘
                            ▼
                 ┌────────────────────────┐
                 │  KV store (sharded)     │  + read replicas
                 │  code → long_url         │
                 └────────────────────────┘
                            │ async click events
                            ▼
                     Kafka → Stream proc → Analytics DB / warehouse
```

| Component | Responsibility |
| --- | --- |
| **CDN** | Cache redirects at the edge — offloads the read path globally |
| **API Gateway** | TLS termination, auth, **rate limiting** (abuse control) |
| **Write Service** | Validate URL, get unique code, persist mapping |
| **Read Service** | The hot path: `code → long_url`, return 301/302, emit click event |
| **ID gen / KGS** | Collision-free code generation (range allocator / KGS) |
| **Redis** | Cache-aside for hot codes; **negative cache** for unknown codes |
| **KV store** | Durable `code → long_url`, sharded by code, replicated |
| **Kafka + analytics** | Async click tracking, decoupled from redirects |

---

## 9. Core Flows

### Create (write path)

```
POST /shorten { longUrl, customAlias?, expiresAt? }

1. Validate URL (scheme, length, malware/blocklist check — see §15)
2. If customAlias:
       reserved word?  → 400
       already taken?  → 409
       else code = customAlias
   Else:
       (optional) dedup: hash(longUrl) seen? → return existing code
       id = idGenerator.next()        # range allocator / KGS
       code = base62(id)              # (scramble id first if non-enumerable)
3. INSERT url (code, longUrl, expiresAt)   # PK on code guarantees uniqueness
4. cache.set(code, longUrl, ttl)           # warm the cache on write (read-your-writes)
5. return 201 { shortUrl }
```

### Redirect (read path — the hot path)

```
GET /{code}

1. longUrl = cache.get(code)               # Redis, cache-aside
2. on HIT (not a tombstone) → go to 5
3. on MISS:
       row = db.get(code)                  # replica; primary if just-created
       if not found      → cache.setNegative(code, short ttl); return 404
       if expired/disabled → return 410
       cache.set(code, row.longUrl, ttl)
       longUrl = row.longUrl
4. (negative hit / tombstone) → return 404
5. emit click event → Kafka (fire-and-forget; NEVER block on this)
6. return 301/302  Location: longUrl
```

> The redirect does the **minimum work**: one cache lookup, one header. Analytics is fired async. Target: served from cache/CDN >99% of the time.

### Custom alias collision

```
INSERT ... (code = 'my-brand')
→ PK violation → 409 Conflict "alias taken"
```

### Collision on generated code (only for random/hash approaches)

```
INSERT → PK violation → regenerate (new random / rehash with salt) → retry
```
> Range allocation / KGS **never collide**, so no retry loop is needed on the write path.

### Plain-English: the redirect handler, line by line

The redirect is the busiest, most important piece of code in the whole system, yet it does **almost nothing** — that's the point. Here it is as a small Spring controller:

```java
@RestController
public class RedirectController {

    // GET https://sho.rt/{code}  — e.g. /aB3xY7
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {

        // 1. Try the cache FIRST (Redis). >99% of clicks should be answered right here.
        String longUrl = cache.get(code);

        if (longUrl == null) {                      // 2. cache MISS → ask the database
            UrlRow row = db.findByCode(code);       //    (a read replica, usually)

            if (row == null) {                      // 3. no such code
                cache.setNegative(code, 60);        //    remember "not found" briefly (see §10)
                return ResponseEntity.notFound().build();          // 404
            }
            if (row.isExpiredOrDisabled()) {        // 4. existed but dead
                return ResponseEntity.status(410).build();         // 410 Gone
            }

            longUrl = row.longUrl();
            cache.set(code, longUrl, Duration.ofHours(24));   // 5. fill cache for next time
        } else if (longUrl.equals(TOMBSTONE)) {     // cached "not found"
            return ResponseEntity.notFound().build();              // 404, no DB hit
        }

        // 6. Fire the click event and DON'T WAIT for it — analytics must never slow a redirect.
        kafka.sendAsync("click-events", new ClickEvent(code, Instant.now()));

        // 7. The actual redirect: a 302 with a Location header pointing at the long URL.
        return ResponseEntity.status(HttpStatus.FOUND)   // 302
                .location(URI.create(longUrl))
                .build();
    }
}
```

The important beginner takeaways:

- **Cache before DB** — step 1 is the whole ballgame. If it's a hit, we skip steps 2–5 entirely.
- **Analytics is "fire-and-forget"** (step 6) — we hand the click event to Kafka and immediately move on. We never block the user's redirect waiting for logging.
- **The response is just a header** (step 7) — `302` + `Location:`. The browser then goes to the long URL itself. We do *not* fetch or proxy the target page.

> Analogy: the redirect is a **receptionist who only points**. You say a ticket number, they glance at a sticky note (cache), point you down the correct hallway (Location header), and jot a tally mark *after* you've already started walking (async analytics). They never walk you there themselves.

### Plain-English: create vs redirect — who does the hard work?

| | **Create** (`POST /shorten`) | **Redirect** (`GET /{code}`) |
| --- | --- | --- |
| How often | Rare (~40/sec) | Constant (~thousands/sec) |
| Hard part | Get a unique code, persist durably | Be **fast** — answer from cache |
| Touches DB? | Yes, one write | Ideally **no** (cache/CDN hit) |
| Analogy | Printing a new coat-check ticket | Redeeming an existing ticket |

#### Q: Why warm the cache on create (step 4 of the write flow)?

So the creator's **own first click works instantly**. Right after you create `sho.rt/aB3xY7`, you'll probably click it to test. If we only wrote to the DB (and the read path uses replicas), that fresh row might not have **replicated** yet → your click 404s. Doing `cache.set(code, longUrl)` at create time guarantees **read-your-writes** (§13).

---

## 10. Caching Deep Dive

The redirect path lives or dies on the cache. At 100:1 read:write, a good cache hit rate is the difference between 200 DB reads/sec and 20,000.

### Cache-aside (lazy loading)

```
read:  cache miss → load from DB → populate cache (TTL)
write: populate cache on create (so the creator's first redirect is a hit)
```

### TTL & why it's easy here

Mappings are **immutable** → no stale-data problem for the common case. Use a **long TTL** (hours–days). Invalidate explicitly only on **delete/disable/expiry** ([§13](#13-consistency--cache-invalidation)).

### Negative caching (critical for abuse)

Bots scan random codes (`/aaaaa`, `/aaaab`, …). Every miss would hit the DB. **Cache the 404** for a short TTL (a *tombstone*) so repeated scans of the same bad code don't reach the DB.

```
cache.get(code) = TOMBSTONE → return 404 without touching DB
```

### Bloom filter (optional, avoids DB on unknown codes)

Keep a **Bloom filter of existing codes** in front of the DB:

```
if bloom.mightContain(code) == false → definitely doesn't exist → 404 (no DB hit)
else → check cache/DB (might be a false positive)
```

No false negatives → safe to short-circuit non-existent codes. Also useful to pre-check custom-alias availability cheaply.

### Sizing & eviction

- Working set ≈ **10–20 GB** (100M hot entries × ~100 B) → fits a Redis cluster.
- **Eviction: LRU** — hot/viral links stay, cold links fall out and reload on demand.
- **CDN** sits in front of Redis for geographic reads (especially 301s, which the browser also caches).

### Thundering herd on miss

A viral link expiring from cache → thousands of simultaneous misses hammer one DB shard. Mitigate with **request coalescing** (single-flight: one loader fetches, others wait) and/or slightly randomized TTLs.

### Plain-English: why caching is the entire game here

Recall the system is **read-heavy** (~100 reads per write). A single viral link — say a bit.ly link in a celebrity tweet — might be clicked **millions of times**. If every click hit the database, the DB would melt. But every one of those clicks wants the **exact same answer** (`aB3xY7 → the same long URL`), and that answer **never changes**.

That's the perfect situation for a **cache**: compute the answer once, keep it in fast memory (Redis), and serve millions of clicks from there without ever touching the database.

> Analogy: a **coat-check clerk who memorizes the regulars**. The first time you show ticket #42 they walk to the back to find your coat (DB read). After that, they *remember* where #42 lives and hand it over instantly (cache hit) — no trip to the back.

#### Cache-aside in code (the pattern used on every miss)

```java
String getLongUrl(String code) {
    String longUrl = cache.get(code);        // 1. look in the cache first
    if (longUrl != null) {
        return longUrl;                       //    HIT → done, DB untouched
    }
    // 2. MISS → this is the rare path
    UrlRow row = db.findByCode(code);         //    go to the (slow) database
    if (row == null) return null;
    cache.set(code, row.longUrl(),
              Duration.ofHours(24));          // 3. store it so NEXT time is a hit
    return row.longUrl();
}
```

"Cache-aside" = the app checks the cache, and only on a miss loads from the DB and *puts it aside* in the cache for next time. Because our data is **immutable**, we can use a **long TTL** (hours/days) with no fear of serving something stale.

### Plain-English: caching Q&A

#### Q: What's a "negative cache" / tombstone, and why do I need one?

Bots scan random codes (`/aaaaa`, `/aaaab`, …) hoping to find valid links. Each of those is a **miss** → a DB read for something that *doesn't exist*. If we only cache things that **do** exist, the bot walks right past the cache and hammers the DB.

Fix: **also cache the "not found" answer** for a short time (a *tombstone*). Then repeated scans of the same bad code are answered by the cache, not the DB.

```java
// on a miss that turns out to be "no such code":
cache.setNegative(code, Duration.ofSeconds(60));   // remember the 404 briefly
// next scan of the same bad code:
if (cache.get(code) == TOMBSTONE) return notFound();   // answered without a DB hit
```

Short TTL (seconds) because a code that's absent now might get created soon.

#### Q: What is the "thundering herd" and how does a tiny cache miss cause an outage?

Imagine a viral link is served from cache, then its cache entry **expires at 3:00:00**. At that exact instant, 50,000 people are mid-click. All 50,000 get a cache **miss simultaneously** and all 50,000 stampede the **same DB shard** at once — the "thundering herd" — potentially knocking it over.

Fixes:
- **Request coalescing (single-flight):** only the **first** miss actually queries the DB; the other 49,999 **wait** for that one result and share it.
- **Randomized TTLs (jitter):** don't expire everything on a round number; spread expirations out so misses don't align.

```java
// single-flight: concurrent callers for the same code share ONE DB load
String getLongUrlCoalesced(String code) {
    return singleFlight.execute(code, () -> {   // only 1 loader runs per code
        UrlRow row = db.findByCode(code);        // the other callers just wait for this
        cache.set(code, row.longUrl(), jitteredTtl());
        return row.longUrl();
    });
}
```

#### Q: Cache vs CDN — aren't they the same thing?

Both store answers to avoid recomputing, but at different **layers**:

| | **Redis cache** | **CDN** |
| --- | --- | --- |
| Where | Inside your data center, near the DB | At edge locations **near the user** (worldwide) |
| Stores | `code → long_url` | The whole HTTP redirect response |
| Saves | A **DB read** | A **round-trip to your servers at all** |

A `301` redirect is even cacheable by the **user's own browser** — after the first click, repeat clicks never leave their machine. (That's also why 301 loses analytics — see §11.)

---

## 11. Redirect: 301 vs 302

| | **301 Moved Permanently** | **302 Found (Temporary)** |
| --- | --- | --- |
| Browser caches redirect | **Yes**, aggressively | No (re-hits your server each time) |
| Load on your service | **Lower** (repeat clicks skip you) | Higher (every click flows through) |
| Analytics on repeat visits | **Lost** (browser doesn't re-hit) | **Full** (you see every click) |
| Can you disable/expire later? | Hard (browser cached it) | **Yes** (you control every hit) |
| SEO link equity | Passes to target | Weaker |

> **Rule of thumb:** use **302** (or `307`) if you need **click analytics** or the ability to **change/expire** a link — most shorteners do. Use **301** if you want minimum load and don't need per-visit tracking.

### Plain-English: what a "redirect" even is

When your browser asks for `sho.rt/aB3xY7`, our server doesn't send back a web page. It sends back a tiny reply that basically says: *"That's not here — go to this other address instead,"* along with a status code (**301** or **302**) and a `Location:` header holding the long URL. The browser then automatically goes to that long URL. The user just sees the destination page load; the hop is invisible.

```
Browser  ──GET /aB3xY7──►  sho.rt
Browser  ◄──302, Location: https://airbnb.com/rooms/123──  sho.rt
Browser  ──GET https://airbnb.com/rooms/123──►  airbnb.com   (goes on its own)
```

The **only** difference between 301 and 302 is one word: **"permanently"** vs **"temporarily."** That one word changes whether the browser bothers to ask us again next time.

> Analogy: a **"we've moved!" sign on a shop door**.
> - **301 (permanent):** "We've moved to Main St — **forever**." You memorize it and next time drive **straight to Main St**, never passing the old shop. (Browser caches it → future clicks skip our server.)
> - **302 (temporary):** "We're **temporarily** operating from Main St today." You still come to the **old door each time** to check, because tomorrow it might change. (Browser asks us every time → we see every click.)

### Plain-English: 301 vs 302 Q&A

#### Q: If 301 is faster and lighter, why doesn't everyone use it?

Because "the browser skips us" is a **double-edged sword**. When the browser caches a 301 and stops asking us, we **lose two things**:

1. **Analytics on repeat clicks** — we never see them, so our click counts undercount badly.
2. **Control** — we can no longer expire, disable, or change where that link points, because the browser already "knows" the answer and won't check back.

Most real shorteners (bit.ly, etc.) care about analytics and link control, so they choose **302** and accept the higher server load (which the cache/CDN absorbs anyway).

#### Q: So which do I say in an interview?

> "**302** for a real product — I want per-click analytics and the ability to expire/disable links, so every click must flow through me. I'd use **301** only if analytics don't matter and I want to shed maximum load, since browsers cache it and repeat clicks never reach my servers."

#### Q: Quick recap table

| Situation | Choose | Because |
| --- | --- | --- |
| I want to count every click | **302** | Browser re-hits us each time |
| I might expire/disable/repoint the link | **302** | We stay in control of every hit |
| I want the least possible server load | **301** | Browser caches it and skips us |
| I care about SEO link equity to the target | **301** | Passes more ranking signal |

---

## 12. Analytics Pipeline

Click tracking must be **async and decoupled** — it can never slow or break a redirect.

```
Read Service ──(fire-and-forget)──► Kafka(click-events)
                                        │
                                        ▼
                              Stream processor (Flink/Spark/consumer)
                                        │  aggregate by code/day/geo
                                        ▼
                        Analytics DB / warehouse  +  click_stats_daily
```

| Concern | Approach |
| --- | --- |
| What to capture | `code`, timestamp, IP→geo, referrer, user-agent→device |
| Don't block redirect | Emit to Kafka fire-and-forget; drop on backpressure if needed |
| High write volume | Stream-aggregate; store rollups, not every raw row long-term |
| Bot/dedup | Filter known bots; optionally dedup rapid repeat clicks |
| Fast stats reads | Serve from pre-aggregated `click_stats_daily`, not raw scans |

> If 301s are used, repeat clicks are invisible (browser-cached) — a reason many shorteners choose 302.

---

## 13. Consistency & Cache Invalidation

The mapping is **write-once/immutable**, which makes consistency mostly trivial — with two exceptions.

### Read-your-writes after create

A just-created code may not have replicated to a **read replica** yet → immediate redirect could 404.

**Mitigations:**
- **Warm the cache on write** (`cache.set` in the create flow) — the first redirect is a cache hit.
- Route reads for very fresh codes to the **primary**, or read-after-write from primary for a short window.

### Invalidation on delete / disable / expiry

These are the only mutations. On delete/disable:

```
1. UPDATE url SET is_active = FALSE WHERE code = ?
2. cache.delete(code)   (or set a tombstone so the 410/404 is cached)
```

Expiry can be **lazy** (check `expires_at` on read, return 410) plus a background sweeper that purges/archives expired rows.

> Because everything else is immutable, we can safely serve stale-but-correct data from cache/replicas — **eventual consistency is fine** for the redirect path.

---

## 14. Scaling the System

### Read path (dominant)

- **CDN + Redis cache-aside** — >99% of redirects should never reach the DB.
- **Read replicas** for cache-miss reads; keep the primary for writes + fresh reads.
- Read Service is **stateless** → scale horizontally behind a load balancer.

### Write path (tiny, but must not collide)

- **Range-allocated IDs / KGS** — no per-write coordination, no collisions.
- Writes are ~40–200/sec → a single primary handles it comfortably.

### Database

- **Shard by `code`** (consistent hashing) — single-key reads/writes, linear scale.
- **Replicate** each shard (multi-AZ) — a lost shard = dead links, so durability + failover are non-negotiable.
- **Partition/archive** by `created_at` or `expires_at` — move expired/cold rows to cold storage to keep hot data small.

### Hot keys (viral links)

- A single viral code = one hot shard/key. **CDN + cache absorb it**; because the value is immutable, a long TTL means near-zero DB load even for a link getting millions of clicks.

### Multi-region (follow-up)

- Cache/CDN close to users for low-latency redirects globally.
- Replicate the KV store across regions (async) — safe because mappings are immutable; ID generation needs region-aware ranges (or Snowflake with region bits) to avoid collisions.

---

## 15. Security & Abuse Prevention

URL shorteners are a favorite tool for **phishing and malware** (they hide the destination) — this section is a common interview differentiator.

| Threat | Mitigation |
| --- | --- |
| **Malicious destination** (phishing/malware) | Scan `longUrl` on create against **Google Safe Browsing / blocklists**; re-scan async; allow user reports + takedown |
| **Spam link creation** | **Rate limit** per user/IP at the gateway; CAPTCHA for anonymous creates |
| **Enumeration/scraping** | Non-sequential codes (KGS random keys or scrambled counter); **negative caching** + rate limits blunt scanners |
| **Open-redirect abuse** | You *are* a redirector by design — mitigate with destination scanning + interstitial warning pages for flagged links |
| **Data in transit** | HTTPS everywhere |
| **Reserved paths** | Blocklist system words for custom aliases (`api`, `admin`, `login`) |

> **One-liner:** "Scan destinations against Safe Browsing on create + async, rate-limit creation per user/IP, use non-enumerable codes, cache 404s to blunt scanners, and support reporting/takedown for malicious links."

---

## 16. Failure Scenarios & Mitigations

| Failure | Mitigation |
| --- | --- |
| **Cache (Redis) down** | Fall back to DB/replicas; **request coalescing** to avoid thundering herd; circuit breaker; the DB must be provisioned to survive a cache-cold period |
| **DB shard down** | Replica failover (multi-AZ); reads served from replicas; brief write unavailability for that shard |
| **ID generator / KGS down** | App nodes hold a **buffered block/batch** of IDs in memory → survive short outages; standby KGS |
| **Replication lag** | Read-your-writes via cache-on-write / primary reads for fresh codes ([§13](#13-consistency--cache-invalidation)) |
| **Hot/viral link** | CDN + long-TTL cache absorb; immutable value → near-zero DB load |
| **Analytics pipeline down** | Redirects unaffected (fire-and-forget); Kafka buffers; backfill later |
| **Duplicate create (retry)** | PK on `code`; custom alias → 409; generated → regenerate; dedup via `long_url_hash` if enabled |
| **Bot scanning namespace** | Negative caching + rate limiting + Bloom filter short-circuit |

---

## 17. Observability

| Signal | What to track |
| --- | --- |
| **Metrics** | Redirect QPS, **cache hit ratio** (the key SLO), redirect p50/p99 latency, create QPS, 404/410 rate, ID-allocator refill rate |
| **Logs** | Structured: `code`, `status`, `cache_hit`, latency, `user_id` (never log full destination if sensitive) |
| **Traces** | Gateway → read service → cache → DB (OpenTelemetry) |
| **Alerts** | Cache hit ratio drop, redirect p99 spike, 5xx rate, DB replica lag, shard availability, Safe-Browsing scan backlog |

> **Cache hit ratio is the headline metric** — a drop directly translates to DB load and latency.

---

## 18. Final Architecture

```
                          ┌──────────┐
   Client ───────────────►│   CDN    │  edge cache (redirects)
                          └────┬─────┘
                               │ miss
                               ▼
                        ┌──────────────┐
                        │ API Gateway  │  TLS · auth · rate limit
                        └──────┬───────┘
                 ┌─────────────┴──────────────┐
                 ▼ writes                       ▼ reads (≫)
          ┌──────────────┐               ┌──────────────┐
          │ Write Service│               │ Read Service │
          └──────┬───────┘               └──────┬───────┘
                 │                               │
        ┌────────┴────────┐                      ▼
        ▼                 ▼               ┌──────────────┐
 ┌────────────┐   ┌──────────────┐        │ Redis (LRU)  │
 │ ID gen/KGS │   │ URL scanner  │        │ + neg cache  │
 └────────────┘   │ (SafeBrowse) │        │ + bloom filt │
                  └──────────────┘        └──────┬───────┘
                 │                               │ miss
                 └───────────────┬───────────────┘
                                 ▼
                    ┌────────────────────────────┐
                    │  KV store (sharded by code) │  + replicas, multi-AZ
                    └──────────────┬──────────────┘
                                   │ async click events
                                   ▼
                        Kafka → Stream proc → Analytics DB / warehouse
```

---

## 19. How to Drive the Interview

| Phase | Time | What to say |
| --- | --- | --- |
| **1. Clarify** | 5 min | Custom alias? Expiry? Analytics? Read:write ratio? Non-guessable codes? |
| **2. Estimation** | 5 min | Writes/sec, reads/sec, storage, **cache size**, keyspace |
| **3. API + code gen** | 5 min | `POST /shorten`, `GET /{code}`, base62, range allocator vs KGS |
| **4. Data + architecture** | 10 min | KV store sharded by code, cache-aside, CDN, read replicas |
| **5. Deep dives** | 10 min | Interviewer picks: caching, ID gen, analytics, security, 301/302 |
| **6. Wrap-up** | 5 min | Failure modes, observability, multi-region, trade-offs |

**Strong opening sentence:**

> "This is a **read-heavy** system — redirects dominate creates ~100:1 — so the design centers on **cache + CDN + replicas** on the read path and **collision-free unique code generation** on the write path. The store is a giant key-value map sharded by code, and analytics is fully async so it never slows a redirect."

**Trade-offs to mention proactively:**

| Choice | Trade-off |
| --- | --- |
| Range allocation vs Snowflake | Shortest codes vs zero coordination (Snowflake codes are longer) |
| Counter vs KGS/random | Simplicity vs non-enumerability |
| 301 vs 302 | Lower load vs analytics + link control |
| NoSQL KV vs sharded SQL | Built-in scale vs familiar ACID |
| Dedup identical URLs | Saves space vs extra hash lookup per create + shared-link privacy concerns |

---

## 20. Interview Cheat Sheet

> **"How do you generate the short code?"**
>
> "Base62-encode a globally-unique integer. Get the integer from a **range allocator** — each node grabs a block of 1,000 IDs so there's no per-write coordination and codes stay short. If codes must be non-guessable, use a **KGS** of random pre-generated keys, or scramble the counter before encoding."

> **"Why base62?"**
>
> "All URL-safe with no escaping, and more compact than hex. base64 has `+/=` which aren't URL-safe. Length 7 gives 62^7 ≈ 3.5 trillion codes."

> **"How do you make redirects fast?"**
>
> "It's read-heavy, so **cache-aside with Redis plus a CDN**, read replicas, and shard by code. >99% of redirects should hit cache/edge and never touch the DB. The redirect does one lookup and returns a 301/302 — analytics is fired async."

> **"How do you avoid collisions at scale?"**
>
> "Range-allocated counters and KGS are **collision-free by construction** — no write-time check. For random/hash codes, the PK on `code` catches collisions and I regenerate on conflict."

> **"301 or 302?"**
>
> "302 if I want click analytics or the ability to expire/disable links — every click flows through me. 301 if I want minimum load and don't need per-visit tracking, since browsers cache it."

> **"Someone is scanning random codes — how do you protect the DB?"**
>
> "**Negative caching** (cache the 404 as a tombstone), a **Bloom filter** of existing codes to short-circuit unknowns without a DB hit, and rate limiting at the gateway."

> **"A link just created returns 404 on redirect — why?"**
>
> "Replication lag — it hasn't reached the read replica. I warm the cache on write and read fresh codes from the primary, so read-your-writes holds."

> **"How does analytics not slow the redirect?"**
>
> "Fire-and-forget click events to Kafka, aggregated by a stream processor into a separate analytics store. If it's down, redirects are unaffected and Kafka buffers."

> **"How do you handle malicious/phishing links?"**
>
> "Scan destinations against Safe Browsing/blocklists on create and re-scan async, rate-limit creation, show interstitial warnings for flagged links, and support reporting + takedown."

> **"A link goes viral — hot key problem?"**
>
> "CDN and a long-TTL cache absorb it. The `code → url` value is immutable, so even millions of clicks generate near-zero DB load. Request coalescing prevents a thundering herd if it expires from cache."

---

## 21. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Consistent Hashing** | Sharding the KV store by `code` | Minimal reshuffle when adding nodes |
| **Strategy** | Code generation (range allocator / KGS / hash), redirect policy (301/302) | Swap approaches |
| **Cache-Aside** | Redis in front of the KV store | Read-heavy redirect path |
| **Bloom Filter** | Short-circuit unknown codes / custom-alias existence | Avoid DB hits from scanners |
| **Circuit Breaker** | Calls to Safe Browsing / payment / external APIs | Fail fast on provider issues |
| **Producer-Consumer** | Async click-analytics via Kafka | Keep analytics off the redirect path |
| **CQRS** | Write path (create) vs read model (analytics/discovery) | Optimized reads |
| **Proxy / CDN** | Edge caching of redirects | Global low latency |
| **Repository** | KV/metadata access | Testable |
| **Facade** | Read service exposing a simple redirect API | Simplicity |
| **Range/Ticket Allocation** | Distributed ID blocks for short codes | Rare coordination |

---

## 22. Final Takeaways

- **Read-heavy** (~100:1) → **cache + CDN + read replicas** dominate; writes are tiny.
- **Short code = base62 of a uniquely-generated ID** — range allocator (shortest), KGS (non-enumerable), or Snowflake (no coordination, longer codes).
- **Collision-free by construction** (range/KGS) beats generate-and-check; PK on `code` is the backstop.
- **KV store sharded by `code`** — every op is a single-key lookup; replicate for durability (a lost row = a dead link forever).
- **Mapping is immutable** → caching is trivially safe with long TTLs; invalidate only on delete/expire.
- **Negative caching + Bloom filter** protect the DB from namespace scanners.
- **301 vs 302** is an analytics/load trade-off — 302 for tracking + control.
- **Analytics is fully async** via Kafka — never on the redirect path.
- **Read-your-writes** — warm cache on create / read fresh codes from primary to beat replication lag.
- **Security matters** — destination scanning, rate limiting, non-enumerable codes, takedown.
- **Cache hit ratio is the headline SLO** — watch it above all else.

### Related notes

- [Notification System — System Design](notification-system-design.md) — async pipelines, Kafka, idempotency, DB bottlenecks
- [Scaling Architecture](../concepts/scaling-architecture.md)
- [Rate Limiting](../concepts/rate-limiting.md)

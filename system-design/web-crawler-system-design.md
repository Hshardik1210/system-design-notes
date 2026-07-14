# Web Crawler — System Design

> **Core challenge:** download billions of web pages **at scale**, following links, **without re-crawling duplicates**, **respecting politeness** (don't hammer a site / obey robots.txt), staying **fresh**, and being **fault-tolerant**. The heart is the **URL frontier** (what to crawl next) + **dedup** + **politeness**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture & Crawl Loop](#4-architecture--crawl-loop)
- [5. URL Frontier (Mercator design)](#5-url-frontier-mercator-design)
- [6. DNS Resolution](#6-dns-resolution)
- [7. Deduplication (URL & content)](#7-deduplication-url--content)
- [8. Politeness & robots.txt](#8-politeness--robotstxt)
- [9. Freshness / Recrawl & Traps](#9-freshness--recrawl--traps)
- [10. Data Model / Stores](#10-data-model--stores)
- [11. Sequences](#11-sequences)
- [12. Scaling & Failure](#12-scaling--failure)
- [13. Interview Cheat Sheet](#13-interview-cheat-sheet)
- [14. JavaScript Rendering & Edge Cases](#14-javascript-rendering--edge-cases)
- [15. How to Drive the Interview (framework)](#15-how-to-drive-the-interview-framework)
- [16. Design Patterns (that can be used)](#16-design-patterns-that-can-be-used)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model

```
Seed URLs → fetch page → parse links → filter/dedup new URLs → add to frontier → repeat
                       └→ store page content (for indexing/search)
```

A giant BFS/priority traversal of the web graph, distributed across many crawler workers, coordinated by a **URL frontier** that balances **priority** and **politeness**.

### What problem are we even solving?

A web crawler starts from a few **seed URLs** and repeats a simple loop:

1. **Fetch a page.**
2. **Store the content** so search can index it later.
3. **Extract links** so it can visit those pages too.
4. Move to the next URL on its to-do list and repeat — **forever**, because new pages keep appearing.

Two rules keep it scalable and well-behaved:

- **Never fetch the same page twice** — the web has *billions* of pages, many are duplicates, and re-fetching wastes enormous effort. (This is **dedup**.)
- **Be polite** — don't send hundreds of requests per second to one site and overload it; space requests out. (This is **politeness** / rate-limiting per site.)

That's the whole system. Everything else is just *"how does one crawler become 10,000 workers that never trip over each other, never re-fetch a page, and never overload a site?"*

### Why not just a simple recursive function?

First instinct: write `crawl(url)` that downloads a page, then calls itself on every link it finds.

```java
void crawl(String url) {
    String html = download(url);
    save(html);
    for (String link : extractLinks(html)) {
        crawl(link);   // recurse into every link
    }
}
```

It "works" on a 5-page toy site and **explodes** on the real web:

| Wall | Problem |
| --- | --- |
| **Runs forever, one at a time** | Recursion is sequential — one page at a time. At billions of pages you'd finish in ~centuries. You need thousands of workers crawling **in parallel**. |
| **Infinite loop** | Page A links to B, B links back to A → recurse forever. You must **remember what you've already seen** (dedup). |
| **Stack overflow** | The web is billions deep; the call stack can't hold the "to-do list." The to-do list must live **outside** the program, in a durable queue (the **frontier**). |
| **Gets you banned** | Hammering one site's pages back-to-back = you look like an attack. You must **pace yourself per site** (politeness). |
| **One crash loses everything** | A power blip wipes the in-memory to-do list → restart from zero. The to-do list must be **durable**. |

**Key insight that drives the entire design:**

> Don't use the call stack and don't recurse. Keep an explicit, durable **to-do list of URLs** (the *frontier*), a **memory of what you've already seen** (dedup), and a crowd of **stateless workers** that loop: *pull a URL → fetch → find links → add the new ones back to the list*.

That "explicit queue instead of recursion" turns a toy function into a distributed **breadth-first search (BFS)** over the web — which is exactly the architecture in §4.

> 💡 **tip:** two words recur everywhere below — the **frontier** is the durable to-do list of URLs waiting to be crawled (*what's next*), and **politeness** is the rule that you never fetch from one host too fast (*how fast, per site*). If you keep those two straight, the whole design falls into place.

---

## 2. Requirements

> 💡 **tip:** start the interview here. Pin down *scale* (how many pages, how fresh) and the two non-negotiables — **dedup** and **politeness** — before drawing boxes. It frames every later decision.

**Functional**
- Crawl from seed URLs; extract + follow links; store page content.
- Avoid duplicate URLs and duplicate content.
- Respect **robots.txt** + rate limits per domain (**politeness**).
- Recrawl for **freshness**; handle many content types.

**Non-functional**
- **Scalable** (billions of pages), **fault-tolerant**, **polite**, **extensible** (new parsers), robust to **traps** (infinite/spider traps).

### Non-functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Scale** | Billions of pages; ~hundreds–thousands of fetches/sec sustained; 10B+ URLs in the seen-set. |
| **Politeness** | Hard constraint — never overload a host; obey robots.txt. Correctness of politeness > raw throughput. |
| **Fault tolerance** | A worker/DNS/host crash must not lose the frontier or re-crawl the whole web. Durable frontier + retries/DLQ. |
| **Freshness** | Important/fast-changing pages recrawled sooner; stable pages age. Eventual, not real-time. |
| **Availability** | The crawl is a best-effort background pipeline — no strict SLA per page; keep making forward progress. |
| **Extensibility** | New content parsers (PDF, JS-rendered) plug in without touching the core loop. |

### Out of scope (state assumptions)

- Building the **search index / ranking** itself (we produce the corpus + link graph; indexing is a downstream system).
- **Authenticated / paywalled** crawling, form submission, and login flows.
- **Real-time** crawling (sub-second freshness) and full **JS execution** by default (see §14 for the optional headless path).
- Deep **content understanding** (entity extraction, dedup of *meaning* vs bytes) beyond near-duplicate fingerprints.

---

## 3. Capacity Estimation

```
Target: 1B pages/month → ~400 pages/sec sustained; recrawls multiply this
Page size: ~100 KB avg HTML → 1B × 100 KB = ~100 TB/month raw content (+ compression) → blob store
Seen-URL set: 10B+ URLs → a Bloom filter (~a few GB) fronts a KV store
Bandwidth: 400 pages/sec × 100 KB ≈ 40 MB/s download (distribute across workers/regions)
DNS: one lookup per new host → cache aggressively (millions of hosts)
```

> Two coordination hotspots: the **frontier** (what to fetch next, politely) and the **seen-URL set** (dedup at 10B scale → Bloom filter). Fetch/parse workers are stateless + horizontal.

---

## 4. Architecture & Crawl Loop

```
        ┌──────────────── URL Frontier (priority + per-host queues) ────────────────┐
        │                                                                            │
   Seeds ─►[Frontier]─► Fetcher workers ─► DNS resolve ─► Download ─► Content store (S3)
                             │                                             │
                             ▼                                             ▼
                        Parser/Extractor ─► extract links ─► normalize ─► URL filter/dedup ─┘
                             │                                             ▲
                             ▼                                    (Bloom filter + seen-set)
                       Index pipeline (text → search index) · Link graph (ranking)
```

- **Stateless fetcher/parser workers** scale horizontally; the **frontier** + **seen-URL store** hold the coordination state.
- Stages decoupled by queues (Producer-Consumer) so fetch, parse, and index scale independently.

### The crawl loop as a worker

All workers share one **frontier** (the to-do list of URLs). Each **worker** runs the same small loop over and over: pull the next URL from the frontier, fetch it, extract any new links, add those back to the frontier, repeat. Because every worker is identical and holds **no state of its own** (all shared state lives in the frontier + the "already-seen" set), you can add 10 or 10,000 workers and they all contribute. That's what **"stateless workers scale horizontally"** means.

```java
// Every fetcher worker runs this exact same loop, forever.
// You can run 10,000 copies of it in parallel — they share the frontier + seen-set.
void workerLoop() {
    while (true) {
        // 1. Pull the next URL the frontier says is allowed RIGHT NOW
        //    (allowed = high enough priority AND its host isn't in a politeness cool-down)
        String url = frontier.next();          // blocks until one is ready

        try {
            String ip   = dns.resolve(host(url));   // §6 — cached, or DNS melts
            String html = download(url, ip);         // the actual fetch
            contentStore.put(hash(url), html);       // save raw HTML to S3 (§10)

            // 2. Find every link on the page and try to schedule it
            for (String raw : extractLinks(html)) {
                String norm = normalize(raw);        // §7 — canonical form
                if (seen.isNew(norm)) {              // §7 — Bloom filter + KV check
                    frontier.add(norm);              // brand-new URL → add to the frontier
                }
                // else: already seen → drop it (this is what prevents infinite loops)
            }

            markCrawled(url);                        // status + schedule recrawl (§9)
        } catch (Exception e) {
            frontier.retryLater(url);                // transient fail → try again; give up after N (DLQ)
        }
    }
}
```

Notice the four big subsystems the loop leans on — each is its own section:

- `frontier.next()` / `frontier.add()` → the **URL frontier** (§5): *what to crawl next, politely.*
- `dns.resolve(...)` → **DNS** (§6): *turn a hostname into an IP, cached.*
- `seen.isNew(...)` → **dedup** (§7): *have we already got this?*
- `frontier` respecting host cool-downs → **politeness** (§8).

#### Q: Why split fetch, parse, and index into separate stages/queues?

Because they have **wildly different speeds and failure modes**, and you want to scale them independently:

- **Fetching** is slow and network-bound (waiting on far-away servers). You need *many* fetchers.
- **Parsing** is CPU-bound (chewing through HTML). Needs different tuning.
- **Indexing** is a whole separate pipeline (build the search index, compute the link graph for ranking).

If they were one giant function, a slow index step would stall your fetchers. Instead, each stage reads from a queue and writes to the next queue (**Producer-Consumer**): `fetch → [queue] → parse → [queue] → index`. Any stage can be scaled up, restarted, or fall behind without freezing the others.

---

## 5. URL Frontier (Mercator design)

The frontier decides **what to crawl next** and enforces **politeness**. Classic **Mercator** design = **two levels of queues**.

> 💡 **tip:** **Mercator** is just the name of the classic crawler design that split the frontier into *priority* front queues and *politeness* back queues. Say "Mercator-style two-level frontier" in an interview and you signal you know the canonical answer.

```
FRONT queues (priority):  F1..Fn by importance (PageRank-ish, freshness, depth)
                          a prioritizer assigns each URL to a front queue
BACK queues (politeness): B1..Bm, each mapped to ONE host at a time
                          a HEAP of (nextFetchTime, backQueue) picks which host is due
```

> `enqueue` (prioritize → route to a host's back queue) and `next` (pop the due host, fetch, re-arm its timer) are shown as annotated code in the deep dive below.

| Concern | Mechanism |
| --- | --- |
| **Priority** | Front queues by importance; sample front queues weighted by priority |
| **Politeness** | One host per back queue + a min-heap of next-allowed-fetch times → never hammer a host |
| **Distribution** | Frontier **sharded by host hash** across nodes (a host lives on one node → politeness is local) |
| **Persistence** | Durable (Kafka/DB/disk) so a crash doesn't lose the frontier |

> The two-level split cleanly separates **"what's important" (front)** from **"who can I politely fetch now" (back)**.

### The frontier is a smart to-do list

The crawler has a to-do list of URLs. A naive version is a single FIFO queue: *fetch them in the order I found them.* Two things go wrong:

1. **Priority is ignored.** The CNN homepage and some random abandoned blog get equal treatment. You'd rather crawl important/fresh pages first.
2. **Politeness is impossible.** If 5,000 URLs from `wikipedia.org` happen to sit next to each other in the queue, your workers fetch them all back-to-back and hammer Wikipedia.

Mercator's fix is **two levels of queues**:

- **Front queues = "how important?"** Incoming URLs get sorted into priority lanes (F1..Fn). Important sites (high PageRank, changes often, shallow depth) go in the fast lane.
- **Back queues = "who can I fetch right now without overloading a host?"** Each back queue holds URLs for **exactly one host**, and there's a timer (a min-heap) saying *"host X is next allowed at 12:00:03."*

So the flow is: a URL comes in → front queues decide *importance* → it's routed to its host's back queue → a worker always pulls **the host whose timer is up soonest**. Priority and politeness, cleanly separated.

```java
// THE FRONTIER = priority-in, politeness-out.

// --- IN: prioritize, then route to the URL's host queue ---
void enqueue(String url) {
    int priority = prioritizer.score(url);       // PageRank-ish, freshness, depth
    frontQueues.get(priority).add(url);          // drop into a priority lane
}

// A background mover shifts URLs from front lanes into per-host back queues,
// sampling higher-priority lanes more often (so important URLs flow through faster).
void routeToBackQueue(String url) {
    String host = host(url);
    backQueues.get(host).add(url);               // ONE host per back queue = politeness unit
}

// --- OUT: a worker asks "what's the next polite URL?" ---
String next() {
    // The heap orders hosts by their nextFetchTime. Peek the soonest.
    HostEntry due = heap.peek();
    if (due.nextFetchTime > now()) {
        sleepUntil(due.nextFetchTime);           // nobody is due yet — wait, don't hammer
    }
    HostEntry host = heap.poll();

    String url = backQueues.get(host.name).poll();
    // Re-arm this host's timer: it can't be fetched again until now + its crawl delay
    host.nextFetchTime = now() + crawlDelay(host.name);   // §8 politeness
    heap.add(host);                              // put the host back with its new timer
    return url;
}
```

The min-heap is the key trick: instead of scanning every host asking *"are you ready?"*, the heap always hands you **the one host that's ready soonest** in O(log n).

#### Priority scoring — how a URL picks its front queue

`prioritizer.score(url)` above is a black box; here's a concrete sketch. Blend a few signals into one number, then bucket it into a front lane:

```
score = α·PageRank(host/page)     # importance in the link graph (authority)
      + β·freshness(url)          # how often this page/host changes (news > static)
      + γ·(1 / (1 + depth))       # shallower = closer to a seed = usually more important
```

```java
int frontQueueFor(Url u) {
    double s = ALPHA * pageRank(u)        // e.g. precomputed from the link graph
             + BETA  * changeRate(u.host) // observed change frequency (§9)
             + GAMMA * (1.0 / (1 + u.depth));
    // Map the continuous score into N discrete priority lanes (F1..Fn).
    return clamp((int) (s * NUM_FRONT_QUEUES), 0, NUM_FRONT_QUEUES - 1);
}
```

How it feeds the frontier: the score only decides **which front lane** a URL lands in. A background mover then samples higher-priority lanes **more often** when pushing URLs down into the per-host back queues — so important URLs *flow through faster*, but the back-queue heap still gates the actual fetch on politeness. **Priority influences order; politeness always has the final say on timing.**

> ⚠️ **pitfall:** don't let priority override politeness. A high score just means "route this sooner," never "fetch this host now" — the back-queue timer still rules, or you'll hammer popular hosts (which are exactly the high-PageRank ones).

The to-do list must be *durable* (on disk/Kafka), not just in memory, because the frontier can hold **billions** of pending URLs and represents **weeks of discovered work**. If it lived only in RAM and a worker crashed (or you deployed new code), you'd lose the entire to-do list and restart the crawl from the seed URLs. So the frontier is backed by durable storage (Kafka / DB / disk) — a crash loses at most a few in-flight URLs, not the whole plan.

#### Q: How does "one host per back queue" actually enforce politeness?

Politeness means *"don't fetch from the same site too fast."* By funneling **all** of a host's URLs into a single back queue with a single timer, there's exactly **one** place tracking "when is this host next allowed?" No matter how many workers exist, the heap only releases a URL for `wikipedia.org` once its timer expires — so you physically cannot have two workers hammering Wikipedia at the same instant. (Contrast: if the same host's URLs were scattered across many queues, each worker would fetch independently and overload the site.)

---

## 6. DNS Resolution

- Every new **host** needs a DNS lookup → at billions of URLs across millions of hosts, DNS is a **bottleneck**.
- **Cache DNS results** aggressively (respect TTL); run a local caching resolver; pre-resolve popular hosts.
- DNS can be slow/blocking → use async resolution + a resolver pool.

### DNS resolution and why it's a bottleneck

A URL says `www.wikipedia.org`, but the network can only connect to an **IP address** (like `198.35.26.96`). DNS converts hostname → IP. The catch: each lookup is a **network round-trip to a DNS server** — often 20–100 ms. That's negligible once, but the crawler makes **hundreds of fetches per second**, and doing a fresh lookup every time would make DNS the slowest part of the whole crawler.

The fix: **cache** the answer and reuse it.

```java
class CachingResolver {
    // host -> (ip, expiry). Cached so repeat lookups skip the network.
    private final Map<String, DnsEntry> cache = new ConcurrentHashMap<>();

    String resolve(String host) {
        DnsEntry e = cache.get(host);
        if (e != null && e.expiry > now()) {
            return e.ip;                      // CACHE HIT — no network call, instant
        }
        // Cache miss (or expired) → actually ask a DNS server (the slow part)
        DnsResult r = dns.lookup(host);
        // Respect the TTL the DNS server gives us — don't cache stale forever
        cache.put(host, new DnsEntry(r.ip, now() + r.ttlMs));
        return r.ip;
    }
}
```

Why this works so well: the web has billions of *URLs* but only millions of *hosts*, and most links point back into the same site (`wikipedia.org/A` links to `wikipedia.org/B`). So after the first lookup of a host, thousands of that host's pages hit the cache.

Why not just cache the DNS answer forever, then, and skip the TTL entirely? An IP can change (site moves servers). DNS answers come with a **TTL** ("this is valid for 300 seconds"). Honoring it keeps you from crawling a dead IP. In practice crawlers cache generously (and sometimes past the TTL for hosts that rarely move) to save lookups.

It's also worth resolving DNS **asynchronously with a resolver pool** rather than inline: a DNS lookup **blocks** — the thread just sits waiting for the reply. If a fetcher thread blocks on DNS, it's not fetching. So crawlers fire the lookup, do other work, and handle the answer when it arrives, keeping a **pool** of resolver threads/connections so many lookups happen at once instead of one-at-a-time.

---

## 7. Deduplication (URL & content)

Avoid re-crawling the same URL and storing near-duplicate content.

| Dedup | How |
| --- | --- |
| **URL dedup** | **Normalize** the URL, then check a **seen-set**; a **Bloom filter** gives O(1) membership at 10B scale (no false negatives → "definitely new" is trustworthy; a "maybe seen" is verified in the KV store) |
| **Content dedup** | Hash the page: exact = SHA; **near-duplicate = SimHash/MinHash** (mirror sites, boilerplate) → skip |

- The bloom-check-then-verify-in-KV logic is shown as annotated code (`isNew`) in the deep dive below.
- **URL normalization:** lowercase host, strip fragments (`#...`), sort query params, resolve relative → absolute, drop tracking params, canonicalize (`http`↔`https`, trailing slash).

### Two kinds of "we already have this"

The crawler must avoid duplicate work at **two** different moments, and they're easy to mix up:

| Kind | Question | When | Tool |
| --- | --- | --- | --- |
| **URL dedup** | "Have I already **scheduled/visited this address**?" | *Before* fetching, when we discover a link | Normalize + **seen-set** (Bloom filter + KV) |
| **Content dedup** | "Is this **page's text** the same as one I already stored?" | *After* fetching, before indexing | **Content hash** (SHA exact, SimHash near-dup) |

You need both: two different URLs can serve the **same** page (mirror sites, `?utm_source=...` tracking junk), so URL dedup alone won't catch duplicate *content*.

#### Step 0 — Normalize first (or dedup is worthless)

`HTTP://Wikipedia.org/Foo#intro` and `https://wikipedia.org/Foo` are the **same page** but different strings. If you dedup on the raw string you'll crawl both. So **canonicalize** every URL into one standard form first:

```java
String normalize(String raw, String pageUrl) {
    URI u = URI.create(pageUrl).resolve(raw);   // relative "/foo" -> absolute
    String host = u.getHost().toLowerCase();     // Wikipedia.org -> wikipedia.org
    String scheme = "https";                     // treat http/https as one
    String path = stripTrailingSlash(u.getPath());
    String query = sortAndDropTracking(u.getQuery()); // drop utm_*, sort params
    // fragment (#intro) always dropped — it's the same page
    return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + query);
}
```

**Worked example** — one messy link, discovered on the page `https://blog.example.com/posts/`:

| Step | URL |
| --- | --- |
| raw (as found in `<a href>`) | `../Foo/?utm_source=twitter&id=42&ref=home#section-2` |
| resolve relative → absolute | `HTTP://Blog.Example.com/Foo/?utm_source=twitter&id=42&ref=home#section-2` |
| lowercase host + force https | `https://blog.example.com/Foo/?utm_source=twitter&id=42&ref=home#section-2` |
| strip fragment (`#...`) | `https://blog.example.com/Foo/?utm_source=twitter&id=42&ref=home` |
| drop tracking params (`utm_*`, `ref`) | `https://blog.example.com/Foo/?id=42` |
| sort remaining params + strip trailing slash | `https://blog.example.com/Foo?id=42` |
| **canonical form** (what we dedup + store) | **`https://blog.example.com/Foo?id=42`** |

Every variant of that link — with a different `utm_source`, a `#anchor`, `HTTP://`, or a trailing slash — now collapses to the **same** string, so the seen-set catches it as one URL.

#### Redirect chains — follow, but cap the hops

A fetch can return `301/302 → another URL → another…`. You **normalize and follow** each hop, but cap the chain (**~5 hops max**) so a misconfigured site (or a redirect loop `A→B→A`) can't trap a worker. Dedup on the **final** landing URL, and record redirect sources as aliases so you don't re-crawl them.

```java
String followRedirects(String url) {
    Set<String> seenHops = new HashSet<>();
    for (int hop = 0; hop < MAX_REDIRECTS; hop++) {     // MAX_REDIRECTS = 5
        HttpResponse r = fetch(url);
        if (!r.isRedirect()) return url;                // landed — this is the canonical target
        String next = normalize(r.location(), url);     // normalize each hop too
        if (!seenHops.add(next)) break;                 // loop detected (A→B→A) → bail
        url = next;
    }
    return null;   // too many hops / loop → drop this URL
}
```

> ⚠️ **pitfall:** dedup on the URL you *requested* and you'll re-crawl the same page under every redirecting alias. Always key the seen-set on the **final normalized target**.

#### The Bloom filter (URL dedup at 10B scale)

We have **10 billion+** URLs. Keeping every full URL string in memory to check "seen it?" would need terabytes of RAM. A **Bloom filter** solves this: a small bit-array (a few GB) that answers "have I seen this?" using a handful of hash functions — no strings stored.

Its one quirk: it can say **"maybe seen"** when it actually hasn't (a **false positive**), but it will **never** say "new" for something it has seen (**no false negatives**). We use that asymmetry:

```java
boolean isNew(String normUrl) {
    if (!bloom.mightContain(normUrl)) {
        // "definitely NEW" — Bloom never lies in this direction.
        bloom.add(normUrl);
        kv.put(normUrl, DISCOVERED);   // record the truth in the durable KV store
        return true;                   // schedule it
    }
    // Bloom says "MAYBE seen" — could be a false positive, so VERIFY in the KV store
    if (kv.contains(normUrl)) {
        return false;                  // truly seen → skip
    }
    // false positive → it's actually new after all
    bloom.add(normUrl);
    kv.put(normUrl, DISCOVERED);
    return true;
}
```

- **"Definitely new"** from the Bloom filter is trustworthy → schedule immediately, no DB hit. This is the common case, and it's why the filter is so valuable: **billions of new URLs skip the database entirely.**
- **"Maybe seen"** is double-checked against the KV store (the source of truth), so a false positive never makes us *wrongly* skip a genuinely new page.

> **Why not just the KV store?** You could — but that's a database lookup for *every* discovered link (hundreds of thousands/sec). The Bloom filter absorbs the overwhelming "yep, new" majority in memory, so the KV is only touched on the rare "maybe."

#### Content hashing (near-duplicate pages)

Even with perfect URL dedup, two *different* URLs can hold **identical or nearly-identical** content (mirror sites, "printer-friendly" versions, pages that differ only by an ad or a timestamp). Indexing all of them wastes storage and pollutes search results.

- **Exact duplicate → a normal hash (SHA-256).** Hash the page bytes; identical bytes → identical hash → skip. But one changed character → totally different hash, so it only catches *exact* copies.
- **Near-duplicate → SimHash / MinHash.** These are "**fuzzy** fingerprints": pages with *similar* text get *similar* fingerprints, so you can detect "95% the same" and skip it.

> 💡 **tip:** **SimHash** maps similar text to *similar* bit-strings, so "almost the same" pages sit a few bits apart (small **Hamming distance**). A plain hash (SHA) does the opposite — one changed byte → a totally different value — which is why SHA catches only *exact* dupes and SimHash catches *near* dupes.

```java
void maybeIndex(String url, String html) {
    long simhash = simHash(extractText(html));   // fuzzy fingerprint of the text

    for (long known : recentFingerprints) {
        // hamming distance = how many bits differ. Few bits differ ⇒ near-identical text.
        if (hammingDistance(simhash, known) <= 3) {
            return;   // near-duplicate of something we already have → don't index
        }
    }
    recentFingerprints.add(simhash);
    index(url, html);   // genuinely new content → send to the search index
}
```

#### Q: If the Bloom filter can be wrong, why trust it at all?

Because it's wrong in only the *safe* direction. A false positive costs one extra KV lookup (we verify). A false *negative* would be dangerous (we'd re-crawl or loop forever) — and Bloom filters **guarantee** those never happen. So "definitely new" is always safe to act on, and "maybe seen" is always verified. You get near-database accuracy at a fraction of the memory.

#### Q: What does a "1% false-positive rate" actually mean here?

It means that out of the URLs the Bloom filter flags as **"maybe seen,"** about 1% are actually **brand new** — the filter guessed "seen" because their hash bits happened to collide with other URLs' bits. Concretely, at 10B URLs with a 1% FP rate: for every ~100 genuinely-new URLs the filter *thinks* it has seen, ~1 gets an unnecessary KV lookup that comes back empty, and we crawl it anyway. So the cost of 1% FP is a **1% sliver of extra KV verifications**, never a missed page — false positives only ever add work, they never cause us to skip a real page (that would require a false *negative*, which can't happen). The knobs: more bits per element and more hash functions drive the FP rate down (1% → 0.1%) at the cost of more RAM. 1% is a common sweet spot — cheap memory, negligible wasted lookups.

---

## 8. Politeness & robots.txt

- Fetch + **cache `/robots.txt` per host**; obey `Disallow` and **`Crawl-delay`**.
- **Rate-limit per host** (the back-queue heap enforces min interval) — never overload one server.
- Identify with a proper **User-Agent**; **back off** on 429/5xx (exponential); honor `Retry-After`.
- Respect `nofollow`, meta-robots, and canonical tags.

### Politeness

A crawler must be polite or sites will **block it** (and hammering a small server can even take it down). Politeness has three parts: (1) obey the site's published rules, (2) don't send many requests at once, and (3) identify yourself so site owners know who you are.

#### Part A — Read the rules: `robots.txt`

Every site can publish a file at `example.com/robots.txt` that says *which paths you may crawl* and *how slowly*. You fetch it **once per host**, cache it, and obey it.

```
# example.com/robots.txt
User-agent: *
Disallow: /private/       # don't crawl anything under /private/
Disallow: /cgi-bin/
Crawl-delay: 5            # wait at least 5 seconds between requests to me
```

```java
boolean mayFetch(String url) {
    String host = host(url);

    // Fetch + cache robots.txt per host (don't re-download it every time)
    RobotsRules rules = robotsCache.computeIfAbsent(host,
        h -> parseRobots(download("https://" + h + "/robots.txt")));

    return rules.allows(path(url));   // honor Disallow rules
}
```

#### Part B — Don't sprint: rate-limit per host

Even where crawling is *allowed*, you must **pace** yourself so you don't flood one server. This is exactly what the frontier's per-host back-queue + min-heap timer from §5 enforces: after fetching a page from a host, that host can't be fetched again until `now + crawlDelay`.

```java
long crawlDelay(String host) {
    RobotsRules rules = robotsCache.get(host);
    // Use the site's requested delay if it set one, else a sane default.
    return rules.crawlDelay > 0 ? rules.crawlDelay * 1000L : DEFAULT_DELAY_MS;  // e.g. 1s
}
```

#### Part C — Say who you are, and back off when pushed back

- **User-Agent:** identify honestly (e.g. `MyCrawler/1.0 (+https://mysite.com/bot)`) so site owners can contact you.
- **Back off on errors:** if a server returns `429 Too Many Requests` or `503`, that's it saying *"you're overwhelming me."* Slow down **exponentially** (wait 1s, then 2s, 4s, 8s...) and honor a `Retry-After` header if present.

```java
void handleResponse(String host, HttpResponse resp) {
    if (resp.status == 429 || resp.status >= 500) {
        long wait = resp.hasHeader("Retry-After")
            ? resp.retryAfterMs()
            : backoff.nextDelay(host);     // exponential: 1s, 2s, 4s, 8s...
        frontier.delayHost(host, wait);    // push this host's timer out
    } else {
        backoff.reset(host);               // healthy response → reset the backoff
    }
}
```

robots.txt and rate-limiting can look like the same "politeness," but they answer different questions: **robots.txt = *"am I allowed here at all?"*** (a yes/no per path, plus the site's requested delay) — it's about **permission**. **Rate-limiting = *"how fast may I go where I'm allowed?"*** — it's about **pace**. You can be allowed everywhere but still be rude by fetching too fast; you can pace perfectly but still be rude by crawling `/private/`. A polite crawler does **both**.

#### Part D — Honor `<link rel="canonical">`

A page can declare its own canonical address in the HTML head: `<link rel="canonical" href="https://site.com/article">`. This is the *site telling you* "these variants (`?ref=`, AMP, mobile, print) are all really this one URL." Treat it as an authoritative normalization hint: index the canonical, and fold the variant into the seen-set as an alias so you don't store the same article ten times. (It complements the syntactic normalization in §7, which the site's canonical can override.)

#### Q: You have to crawl `robots.txt` to obey it — but is fetching it itself "allowed"?

Yes, and it's a special case: `/robots.txt` is **always fetchable** — a crawler is *expected* to request it, and robots rules never apply to the robots file itself. Practical rules: fetch it **once per host** and cache it (with a TTL, e.g. re-check daily) so you're not re-downloading it constantly; the fetch **doesn't count against** the site's normal `Crawl-delay` budget for content. Handle the edge cases explicitly — **`404`/missing** → assume *everything is allowed*; **`5xx`/timeout** → be conservative and treat the host as *disallowed* until you can fetch it (don't crawl a site whose rules you can't read); an **oversized/garbage** file → cap the parse size and fall back to "allow all" or a safe default.

---

## 9. Freshness / Recrawl & Traps

- Pages change → schedule **recrawls** by observed **change frequency** (news hourly, static pages monthly) — adaptive interval from `last_crawled` + change history.
- **Spider traps** (infinite calendars, session-id URLs, faceted-filter explosions) → **depth limits, URL-pattern filters, per-host URL caps**, and detecting parameter explosions.
- **Politeness vs freshness** trade-off: important, fast-changing sites get higher priority + shorter recrawl.

### Keeping the archive fresh (recrawl)

A crawl isn't "done" — the web keeps changing. A news homepage rewrites itself hourly; a 2009 blog post never changes. So the crawler must **revisit** pages, but revisiting *everything* on the same schedule is wasteful. The trick: **learn each page's change rate** and recrawl accordingly.

```java
// After crawling, decide when to come back based on how often this page changes.
long scheduleNextRecrawl(Url u, String newHash) {
    if (newHash.equals(u.contentHash)) {
        // Unchanged since last time → it's stable → back off, check less often.
        u.recrawlInterval = min(u.recrawlInterval * 2, MAX_INTERVAL);  // e.g. up to 30 days
    } else {
        // It changed → it's active → check more often.
        u.recrawlInterval = max(u.recrawlInterval / 2, MIN_INTERVAL);  // e.g. down to 1 hour
    }
    u.contentHash = newHash;
    return now() + u.recrawlInterval;   // stored as next_recrawl; a scheduler re-enqueues when due
}
```

A separate **scheduler** just wakes up periodically and re-adds due pages to the frontier: `SELECT url WHERE next_recrawl <= now()`.

### Spider traps (infinite loops)

Some sites (accidentally or maliciously) generate **infinite** unique URLs, and a naive crawler will follow them forever, never finishing and never reaching real content:

- **Infinite calendar:** `/calendar?date=2026-07-08` → "next day" → `2026-07-09` → ... forever into the year 3000.
- **Session-id URLs:** every visit appends a new `?sid=abc123`, so the *same* page looks like infinitely many new URLs.
- **Faceted filters:** `/shirts?color=red&size=M&sort=price&page=2...` — every combination is a "new" URL, exploding combinatorially.

Each of these is *technically* a new URL, so URL dedup (§7) doesn't save you. Guards:

```java
boolean shouldSkipAsTrap(Url u) {
    // 1. Depth limit — how many links deep from a seed. Real content is shallow.
    if (u.depth > MAX_DEPTH) return true;                 // e.g. 20

    // 2. Per-host URL cap — one host shouldn't yield millions of URLs.
    if (crawledCountForHost(u.host) > MAX_URLS_PER_HOST) return true;

    // 3. Pattern filters — kill known trap shapes (endless params, calendars).
    if (u.url.matches(".*([?&]sid=|/calendar\\?date=).*")) return true;

    // 4. Parameter explosion — too many query params ⇒ likely a faceted-filter trap.
    if (countQueryParams(u.url) > MAX_PARAMS) return true;

    return false;
}
```

#### Q: How is a spider trap different from a normal duplicate?

A **duplicate** is the *same* URL/content seen again → caught cheaply by the seen-set / content hash. A **trap** produces endless *distinct* URLs (each genuinely new to the Bloom filter), so dedup never triggers. Traps are stopped by **limits and pattern rules** (depth, per-host caps, param counts), not by dedup.

There's a real tension between freshness and politeness: you'd *like* to recrawl a busy news site every few minutes to stay fresh — but politeness (§8) caps how fast you may hit that host. So you spend your limited per-host budget on the pages that **change most and matter most** (high priority + short recrawl), and let stable/unimportant pages age. It's a budgeting problem, not a "crawl everything constantly" problem.

#### Q: If we discover URLs by following links, why bother with `sitemap.xml`?

They're **complementary discovery paths**, and a good crawler uses both. **Link discovery** finds pages by following `<a href>`s from pages you've already fetched — but it only reaches pages that are *linked* from somewhere you've been, and it discovers them *lazily*, one hop at a time. A **`sitemap.xml`** (often advertised in `robots.txt`) is the site handing you an explicit, complete list of its URLs — including **orphan pages** nobody links to and **deep pages** that are many hops from the homepage. Better still, sitemap entries carry `<lastmod>` / `<changefreq>` hints, which feed the recrawl scheduler directly (freshness for free, without re-fetching to detect a change). Rule of thumb: **seed from the sitemap for coverage, then let link discovery fill the gaps** — but still verify (a sitemap can lie or be stale), and dedup both streams through the same seen-set.

---

## 10. Data Model / Stores

```sql
-- URL registry / seen-set (huge → KV store + Bloom filter in front)
CREATE TABLE urls (
    url_hash    CHAR(64) PRIMARY KEY,      -- hash of normalized URL
    url         TEXT, host VARCHAR(255),
    status      VARCHAR(20),               -- DISCOVERED, CRAWLED, FAILED
    priority    INT, depth INT,
    last_crawled TIMESTAMP, content_hash CHAR(64), next_recrawl TIMESTAMP
);
CREATE INDEX idx_urls_host ON urls(host);
CREATE INDEX idx_urls_recrawl ON urls(next_recrawl) WHERE status='CRAWLED';

CREATE TABLE robots_cache ( host VARCHAR(255) PRIMARY KEY, rules TEXT, crawl_delay INT, fetched_at TIMESTAMP );

-- Content → blob store (S3): raw HTML keyed by url_hash (compressed)
-- Frontier → durable queue (Kafka/DB/disk) partitioned by host
-- Seen-URL membership → Bloom filter (in memory) + KV backing (truth)
-- Link graph → adjacency (for PageRank/priority)
```

> **Stores to consider:** URL registry/seen-set (KV + Bloom filter), content blob store (S3, compressed), robots cache, durable frontier queue, link graph, extracted-text/search index, DNS cache.

### Database & storage choices (which DB, and why at scale)

Crawler storage splits along one line: **coordination state** (small, hot, needs correctness) vs **bulk archive** (huge, cold, needs cheapness). Deciding question: *"do I need this to be durable-and-transactional, or just a fast 'have I seen this?' check?"*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| URL frontier (work queue + politeness state) | **Kafka/queue** (durable work) + **RDBMS** (frontier row state, robots cache) | The frontier represents weeks of discovered work — must survive a worker crash; per-host politeness timers need one authoritative place to check "is this host due?" (§5) | An in-memory queue loses billions of pending URLs on a crash; recomputing the crawl plan from seeds isn't an option at this scale |
| Seen-URL dedup (10B+ URLs) | **Bloom filter** (in-memory/Redis) fronting a **KV store** | A few GB of bits answers "definitely new" for the overwhelming majority of checks in O(1), with no false negatives, so it's safe to trust (§7) | Checking a full KV/RDBMS on every discovered link (hundreds of thousands/sec) makes the durable store the bottleneck for a check that's almost always "yes, it's new" |
| Raw fetched pages | **Blob/object store (S3)**, compressed | ~100KB per page × billions of pages is squarely "cheap bulk immutable storage," never queried by content | A database row per page wastes space on data nobody `SELECT`s by field — you only ever fetch it by `url_hash` |
| Parsed/searchable content | **Inverted index (Elasticsearch)** | Full-text search over billions of pages needs an index built for exactly that access pattern | The URL registry answers "what's this URL's status," not "which pages mention X" — a different query shape entirely |
| Crawl metadata/scheduling (status, priority, `next_recrawl`) | **RDBMS/KV with indexes** | Structured, small rows, looked up by `url_hash` and range-queried by `next_recrawl` for the recrawl scheduler | Doesn't fit the Bloom filter (no range queries) or S3 (needs random small-row access, not blob fetches) |

**Why a Bloom filter + queue beats a database lookup per URL at billions scale:** at 10B+ URLs, a straight "has this URL been seen?" query against a database — for *every single link* extracted from *every single page* — would mean hundreds of thousands of DB reads per second just to decide whether to do any work at all, making the correctness store the bottleneck for a check that's almost always "yes, it's new." A Bloom filter flips that: a small in-memory bit array answers "definitely new" instantly for the common case, and only the rare "maybe seen" falls through to the KV store for verification — so the durable store is touched for a tiny fraction of checks instead of all of them (§7). The **frontier** gets the same treatment on the write side: instead of every worker writing directly into a shared table (lock contention, hot rows), discovered URLs flow through a **durable queue** partitioned by host, so politeness (§8) and durability are enforced by the queue/heap structure, not by transactions on a database row. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### Why so many different stores?

Each piece of crawler data has a different *shape* and *access pattern*, so each gets a store built for it:

| What | Store | Why this store |
| --- | --- | --- |
| **Seen-URL membership** ("got it?") | Bloom filter (RAM) + KV store | Bloom = instant "new?" check; KV = durable truth to verify "maybe" |
| **URL registry** (status, priority, recrawl time) | KV / DB with indexes | Small structured rows, looked up by `url_hash`, queried by `next_recrawl` |
| **Raw page HTML** (big, ~100 KB each) | Blob store (S3), compressed | Cheap bulk storage for huge, write-once files — a DB would be wrong/expensive |
| **The to-do list** (pending URLs) | Durable queue (Kafka/DB/disk) | Append-heavy, must survive crashes, partitioned by host |
| **robots.txt rules** | Robots cache (KV) | One small entry per host, read constantly |
| **Link graph** (who links to whom) | Adjacency store | Feeds PageRank/priority scoring |
| **Extracted text** | Search index | Optimized for full-text queries, not crawling |

> The one-liner: **the frontier and the seen-set are the coordination brain; S3 is the bulk archive; everything else is a specialized helper.** Notice the two hotspots from §3 show up here as their own stores — that's where all the hard scaling work concentrates.

---

## 11. Sequences

### Crawl one URL

```
Frontier → Fetcher: pop next politely-allowed URL (host due in heap)
  DNS resolve (cached) → download → store raw HTML in S3
  Parser: extract links → normalize each → Bloom/seen check → enqueue NEW ones to frontier
  mark url CRAWLED, set content_hash, schedule next_recrawl
  (content SimHash → skip near-duplicates from indexing)
```

### Recrawl

```
Scheduler: select urls WHERE next_recrawl <= now() → re-enqueue to frontier (priority by change rate)
```

---

## 12. Scaling & Failure

- **Shard frontier + workers by host hash** → each host handled in one place (politeness + locality); scale by adding shards.
- **Bloom filter** for the seen-set keeps dedup cheap; KV store is the durable truth (verify on "maybe").
- **Content → S3** (compressed); text → indexing pipeline (Kafka).
- **Durable frontier** (Kafka/DB) so crashes don't lose work; **retries + DLQ** for failed fetches.
- **DNS caching** to avoid resolver bottlenecks; distribute crawlers across regions.
- **Traps** handled via depth/pattern/host caps.

### One crawler becomes thousands (distributed crawling)

One machine can't crawl billions of pages. So you run thousands of workers — but if they pick URLs randomly, two workers might fetch the same page (wasted work) *or* several might hit one fragile site at once (overload). The fix: **give each worker ownership of specific *hosts*.**

We **shard by host hash**: `owner = hash(host) % numberOfShards`. Every URL for `wikipedia.org` always routes to the same shard/node. This single rule quietly solves several problems at once:

```java
int shardFor(String url) {
    return Math.floorMod(host(url).hashCode(), numShards);   // same host -> same shard, always
}

void routeDiscoveredUrl(String url) {
    int shard = shardFor(url);
    if (shard == myShardId) {
        frontier.enqueue(url);              // mine — handle locally
    } else {
        sendToShard(shard, url);            // someone else's host — hand it off
    }
}
```

Why "shard by **host**" (not by URL) is the magic choice:

- **Politeness stays local & correct.** All of a host's URLs live on one node → that node alone owns the host's rate-limit timer (§5/§8). No cross-node coordination needed to avoid hammering a site.
- **DNS cache hits soar.** One node handles one host repeatedly → it resolves that host's DNS once and reuses it (§6).
- **robots.txt fetched once.** The owning node caches the host's rules and every worker there reuses them.
- **Scaling = add shards.** More hosts than you can handle? Add nodes and rebalance the hash ring (**consistent hashing** keeps reshuffling minimal).

Putting the crash-survival story together: the **durable frontier** means the to-do list lives in Kafka/DB, not RAM, so a dead worker loses at most a few in-flight URLs, not the plan (§5); **retries + DLQ** mean a failed fetch is retried with backoff, and after N failures it goes to a **dead-letter queue** for later inspection instead of blocking forever; and **idempotent dedup** means re-processing a URL after a crash is harmless — the seen-set/content-hash checks make "crawl it again" a no-op for anything already stored.

#### Q: Do the thousands of workers need to talk to each other?

Almost never — and that's the point. Workers are **stateless** and coordinate *only* through shared infrastructure (the frontier, the seen-set, the content store). A worker's whole world is "pull a URL, fetch, push new links back." Because they don't hold private state or message each other, you scale by simply launching more of them.

---

## 13. Interview Cheat Sheet

> **"What decides what to crawl next?"**
> "The **URL frontier** (Mercator two-level): **front queues** by priority (importance/freshness) and **back queues** per host for politeness, with a **min-heap** of next-allowed-fetch times so no host is hammered. Sharded by host hash and durable so a crash doesn't lose it."

> **"How do you avoid crawling the same thing twice?"**
> "Normalize the URL and check a **seen-set** via a **Bloom filter** (O(1), no false negatives) backed by a KV store; verify in the KV on a 'maybe' to avoid false-positive skips. For duplicate content, **SimHash/MinHash** to skip near-duplicates."

> **"How do you stay polite?"**
> "Cache + obey robots.txt (Disallow + Crawl-delay), rate-limit per host via the back-queue heap, proper User-Agent, and exponential back-off on 429/5xx."

> **"What breaks a crawler and how do you handle it?"**
> "DNS bottleneck → cache aggressively. Spider traps → depth/pattern/host caps. Crash → durable frontier + retries/DLQ. Duplicate content → SimHash."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Bloom filter says "maybe seen"** | It could be a **false positive** — verify against the KV store (source of truth). Only skip if the KV confirms it; a "definitely new" from Bloom is always safe to crawl (§7). |
| **Host-shard rebalance (add/remove nodes)** | Use **consistent hashing** so only a small slice of hosts move; drain in-flight fetches, hand off each host's frontier + robots cache + DNS to the new owner, so politeness state isn't lost (§12). |
| **Worker crashes mid-fetch** | The URL was leased, not deleted — the durable frontier **re-leases** it after a visibility timeout; **idempotent dedup** makes a re-fetch a harmless no-op. At most a few in-flight URLs are retried, never the whole plan (§12). |
| **Infinite redirect loop (A→B→A)** | Cap the chain at **~5 hops** and track seen hops; bail and drop the URL if it loops or exceeds the cap. Dedup on the final landing URL (§7). |
| **Spider trap (infinite distinct URLs)** | Not caught by dedup (each URL is genuinely new) — stop with **depth limits, per-host caps, and pattern filters** (§9). |
| **`robots.txt` unreachable (5xx/timeout)** | Be conservative: treat the host as **disallowed** until you can read its rules; a `404` means allow-all (§8). |

---

## 14. JavaScript Rendering & Edge Cases

> A static HTTP fetch gets you 90% of the web cheaply. This section covers the awkward remainder — JS-heavy pages, canonical/cert edge cases, per-domain budgets, and the ops knobs you need to actually run a crawler.

### JavaScript rendering — the optional headless queue

Many modern pages ship a nearly-empty HTML shell and build their real content with JavaScript in the browser. A plain fetch sees the shell and **misses the content**. Executing JS requires a **headless browser** (Chromium), which is **10–100× more expensive** (CPU, memory, seconds per page) than a raw fetch — so you never do it by default.

The pattern: fetch statically first; if a cheap heuristic says "this looks JS-rendered and we got nothing," **route it to a separate, small, rate-limited headless-render queue.**

```
Fetcher → static GET ─► looks empty? (tiny body, <div id="root"></div>, no links)
                          │ no  → parse normally
                          │ yes → enqueue to HEADLESS RENDER queue (small, expensive pool)
                                     └► Chromium renders → extract links + text → back into the pipeline
```

```java
boolean needsJsRender(String html) {
    String text = extractText(html);
    // Heuristic: almost no visible text but a known SPA mount point ⇒ content is JS-built.
    return text.length() < MIN_TEXT
        && html.matches(".*(id=\"root\"|id=\"app\"|__NEXT_DATA__).*");
}
```

> ⚠️ **pitfall:** rendering every page in a headless browser will bankrupt the crawl (and still needs politeness). Keep the render pool **small and prioritized** — render only pages that (a) failed static extraction *and* (b) are worth it (high priority). Everything else stays on the cheap path.

### Other real-world edge cases

| Case | Handling |
| --- | --- |
| **`<link rel="canonical">`** | The site's own "this is the real URL" hint — index the canonical, fold variants (AMP, `?ref=`, print) into the seen-set as aliases so one article isn't stored ten times (§8). |
| **Crawl budget per domain** | Cap how many pages/day you'll pull from any one host (not just rate) so a giant site doesn't monopolize workers; allocate more budget to high-value domains. Prevents slow-motion trap explosions too. |
| **HTTPS cert errors** | Expired / self-signed / hostname-mismatch certs: log and **skip by default** (don't silently ignore — that's how you crawl spoofed content). Optionally retry over `http` only if policy allows; never send credentials. |
| **Non-HTML content types** | Route by MIME: PDFs/docs → a text-extraction parser; images/video/binaries → record metadata, don't try to parse as HTML (Factory-per-MIME, §16). |
| **Huge pages / slow servers** | Cap response size and set fetch timeouts; a 500 MB page or a server that trickles bytes must not tie up a worker forever. |

### Admin / Ops API

An operator needs live control over a running crawl — expose a small internal API:

```
POST /admin/seeds            { urls: [...] }        → inject new seed URLs into the frontier
POST /admin/hosts/{host}/pause                       → stop fetching a host (complaint / outage)
POST /admin/hosts/{host}/resume
POST /admin/urls/recrawl     { url | pattern }       → force an immediate recrawl (bypass schedule)
GET  /admin/stats                                    → frontier size, fetch rate, DLQ depth, per-host budgets
PUT  /admin/hosts/{host}/crawl-delay { ms }          → override politeness delay for a host
```

> 💡 **tip:** the "**pause a host**" button is the one you'll actually use in an incident — when a site owner emails "stop crawling us," you need to halt one host in seconds without redeploying. Mentioning an ops API signals production maturity.

---

## 15. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–7.

1. **Clarify requirements** (functional + NFRs, scale) — §2
2. **Estimate capacity** (pages/sec, storage, seen-set size) — §3
3. **Sketch the architecture + crawl loop** — §4
4. **Name the two hotspots** up front: the **URL frontier** and the **seen-set** — §5, §7
5. **Deep dive: the hard part** → **frontier (priority + politeness)** and **dedup at 10B scale** — §5–§8
6. **Deep dive: freshness, traps, and failure/scaling** — §9, §12
7. **Address edge cases** — JS rendering, redirects, sitemaps, ops — §14
8. **Summarize tradeoffs** — freshness vs politeness, Bloom vs KV, cheap fetch vs headless — §13

> 🎤 **Lead with the core challenge:** state up front that "the crux is *what to crawl next, politely, without re-crawling duplicates, at billions scale*" — the frontier + dedup + politeness triangle. Then spend most of your time there.

---

## 16. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Frontier → fetcher/parser workers | Decouple + parallelize |
| **Strategy** | URL prioritization, recrawl policy, parser per content-type | Swap algorithms |
| **Bloom Filter** | Seen-URL membership | Cheap dedup at 10B scale |
| **Pipeline / Chain of Responsibility** | fetch → parse → extract → normalize → filter → store | Composable stages |
| **Factory** | Parser per MIME type (HTML/PDF/image) | Extensible content handling |
| **Rate Limiter / Token Bucket** | Per-host politeness (back-queue heap) | Don't overload sites |
| **Priority Queue / Heap** | Front queues + per-host next-fetch heap | Priority + politeness scheduling |
| **Repository** | URL/content stores | Abstraction |
| **Circuit Breaker** | Failing hosts | Back off gracefully |
| **Observer/Pub-Sub** | New links → dedup → frontier | Decouple |

---

## 17. Final Takeaways

- **URL frontier (Mercator)** = front queues (priority) + per-host back queues + a next-fetch heap (politeness); durable + sharded by host.
- **Dedup:** URL normalization + **Bloom filter** (+ KV truth); content near-dup via **SimHash**.
- **Politeness:** robots.txt + per-host rate limiting + back-off.
- **DNS caching** is essential; **freshness** = adaptive recrawl; guard **spider traps**.
- Content → **blob store**; stages decoupled by queues (fetch → parse → extract → filter → store → index).
- Patterns: Producer-Consumer, Strategy, Bloom Filter, Pipeline/Chain, Factory (parsers), Token Bucket, Priority Queue.

### Related notes

- [Bloom Filters](../concepts/bloom-filters.md) — the seen-URL dedup structure
- [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Rate Limiting](../concepts/rate-limiting.md)

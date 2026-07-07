# Web Crawler — System Design

> **Core challenge:** download billions of web pages **at scale**, following links, **without re-crawling duplicates**, **respecting politeness** (don't hammer a site / obey robots.txt), staying **fresh**, and being **fault-tolerant**. The heart is the **URL frontier** (what to crawl next) + **dedup** + **politeness**.

> **How to read this doc:** each section has the dense interview summary first, then a **Plain-English** deep dive (analogies, annotated code, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

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
- [12. Design Patterns (that can be used)](#12-design-patterns-that-can-be-used)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
Seed URLs → fetch page → parse links → filter/dedup new URLs → add to frontier → repeat
                       └→ store page content (for indexing/search)
```

A giant BFS/priority traversal of the web graph, distributed across many crawler workers, coordinated by a **URL frontier** that balances **priority** and **politeness**.

### Plain-English: what problem are we even solving?

Imagine a **robot librarian** let loose in the biggest library on Earth (the web). You hand it a few starting books (**seed URLs**). Its job:

1. Open a book (**fetch a page**).
2. Copy it into the archive (**store the content** so search can index it later).
3. Write down every reference it mentions (**extract links**) so it can visit those too.
4. Go to the next book on its to-do list and repeat — **forever**, because new books keep appearing.

Two rules keep the robot sane and welcome:

- **Never read the same book twice** — the library has *billions* of books, many are copies, and re-reading wastes months of effort. (This is **dedup**.)
- **Be a polite guest** — don't yank 500 books off one shelf per second and collapse it; take a few, wait, take a few more. (This is **politeness** / rate-limiting per site.)

That's the whole system. Everything else is just *"how does one robot become 10,000 robots that never trip over each other, never re-read a book, and never anger a librarian?"*

### Plain-English: why not just a simple recursive function?

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

---

## 2. Requirements

**Functional**
- Crawl from seed URLs; extract + follow links; store page content.
- Avoid duplicate URLs and duplicate content.
- Respect **robots.txt** + rate limits per domain (**politeness**).
- Recrawl for **freshness**; handle many content types.

**Non-functional**
- **Scalable** (billions of pages), **fault-tolerant**, **polite**, **extensible** (new parsers), robust to **traps** (infinite/spider traps).

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

### Plain-English: the crawl loop as a worker

**Analogy: a room full of identical robot librarians sharing one to-do clipboard.**

The clipboard is the **frontier** (the to-do list of URLs). Each robot (a **worker**) does the same tiny loop over and over: grab the next URL off the clipboard, go fetch it, jot down any new links it finds, drop those back on the clipboard, repeat. Because every robot is identical and carries **no memory of its own** (all shared state lives on the clipboard + the "already-seen" ledger), you can add 10 or 10,000 robots and they just... help. That's what **"stateless workers scale horizontally"** means.

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
                    frontier.add(norm);              // brand-new URL → onto the clipboard
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

```
FRONT queues (priority):  F1..Fn by importance (PageRank-ish, freshness, depth)
                          a prioritizer assigns each URL to a front queue
BACK queues (politeness): B1..Bm, each mapped to ONE host at a time
                          a HEAP of (nextFetchTime, backQueue) picks which host is due
```

```
enqueue(url): prioritizer → pick front queue by priority
route to back: a router maps url's host → a back queue (one host per back queue)
fetch loop: pop the host with the earliest nextFetchTime from the heap
            fetch one URL from its back queue → set nextFetchTime = now + crawlDelay(host)
```

| Concern | Mechanism |
| --- | --- |
| **Priority** | Front queues by importance; sample front queues weighted by priority |
| **Politeness** | One host per back queue + a min-heap of next-allowed-fetch times → never hammer a host |
| **Distribution** | Frontier **sharded by host hash** across nodes (a host lives on one node → politeness is local) |
| **Persistence** | Durable (Kafka/DB/disk) so a crash doesn't lose the frontier |

> The two-level split cleanly separates **"what's important" (front)** from **"who can I politely fetch now" (back)**.

### Plain-English: the frontier is just a smart to-do list

**Analogy: a delivery dispatcher with two whiteboards.**

Our robot librarian has a to-do list of URLs. A dumb version is a single FIFO queue: *fetch them in the order I found them.* Two things go wrong:

1. **Priority is ignored.** The CNN homepage and some random abandoned blog get equal treatment. You'd rather crawl important/fresh pages first.
2. **Politeness is impossible.** If 5,000 URLs from `wikipedia.org` happen to sit next to each other in the queue, your workers fetch them all back-to-back and hammer Wikipedia.

Mercator's fix is **two whiteboards**:

- **Front whiteboard = "how important?"** Incoming URLs get sorted into priority lanes (F1..Fn). Important sites (high PageRank, changes often, shallow depth) go in the fast lane.
- **Back whiteboard = "who can I fetch right now without being rude?"** Each back queue holds URLs for **exactly one host**, and there's a timer (a min-heap) saying *"host X is next allowed at 12:00:03."*

So the flow is: a URL comes in → front board decides *importance* → gets routed to its host's back queue → a worker always pulls **the host whose timer is up soonest**. Priority and politeness, cleanly separated.

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

#### Q: Why must the to-do list be *durable* (on disk/Kafka), not just in memory?

Because the frontier can hold **billions** of pending URLs and represents **weeks of discovered work**. If it lived only in RAM and a worker crashed (or you deployed new code), you'd lose the entire to-do list and restart the crawl from the seed URLs. So the frontier is backed by durable storage (Kafka / DB / disk) — a crash loses at most a few in-flight URLs, not the whole plan.

#### Q: How does "one host per back queue" actually enforce politeness?

Politeness means *"don't fetch from the same site too fast."* By funneling **all** of a host's URLs into a single back queue with a single timer, there's exactly **one** place tracking "when is this host next allowed?" No matter how many workers exist, the heap only releases a URL for `wikipedia.org` once its timer expires — so you physically cannot have two workers hammering Wikipedia at the same instant. (Contrast: if the same host's URLs were scattered across many queues, each worker would fetch independently and overload the site.)

---

## 6. DNS Resolution

- Every new **host** needs a DNS lookup → at billions of URLs across millions of hosts, DNS is a **bottleneck**.
- **Cache DNS results** aggressively (respect TTL); run a local caching resolver; pre-resolve popular hosts.
- DNS can be slow/blocking → use async resolution + a resolver pool.

### Plain-English: DNS is the phone book (and it's slow)

**Analogy: looking up a phone number before every call.** A URL says `www.wikipedia.org`, but the network can only connect to a **number** (an IP like `198.35.26.96`). DNS is the phone book that converts name → number. The catch: each lookup is a **network round-trip to a DNS server** — often 20–100 ms. That's tiny for a human, but our robot makes **hundreds of fetches per second**, and doing a fresh lookup every time would make DNS the slowest part of the whole crawler.

The fix is the same as memorizing your friends' numbers: **cache** the answer and reuse it.

```java
class CachingResolver {
    // host -> (ip, expiry). A friend's number you've memorized.
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

#### Q: Why not just cache forever? What's the TTL for?

An IP can change (site moves servers). DNS answers come with a **TTL** ("this is valid for 300 seconds"). Honoring it keeps you from crawling a dead IP. In practice crawlers cache generously (and sometimes past the TTL for hosts that rarely move) to save lookups.

#### Q: Why "async resolution + a resolver pool"?

A DNS lookup **blocks** — the thread just sits waiting for the reply. If a fetcher thread blocks on DNS, it's not fetching. So crawlers resolve DNS **asynchronously** (fire the lookup, do other work, handle the answer when it arrives) and keep a **pool** of resolver threads/connections so many lookups happen at once instead of one-at-a-time.

---

## 7. Deduplication (URL & content)

Avoid re-crawling the same URL and storing near-duplicate content.

| Dedup | How |
| --- | --- |
| **URL dedup** | **Normalize** the URL, then check a **seen-set**; a **Bloom filter** gives O(1) membership at 10B scale (no false negatives → "definitely new" is trustworthy; a "maybe seen" is verified in the KV store) |
| **Content dedup** | Hash the page: exact = SHA; **near-duplicate = SimHash/MinHash** (mirror sites, boilerplate) → skip |

```
if not bloom.mightContain(normUrl):        # definitely new
    add to frontier + set bloom + persist to seen-set
else:                                       # maybe seen → verify in KV (avoid false-positive skip)
    if kv.contains(normUrl): skip
```

- **URL normalization:** lowercase host, strip fragments (`#...`), sort query params, resolve relative → absolute, drop tracking params, canonicalize (`http`↔`https`, trailing slash).

### Plain-English: two kinds of "we already have this"

The robot librarian must avoid duplicate work at **two** different moments, and beginners mix them up:

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

#### Plain-English: the Bloom filter (URL dedup at 10B scale)

**Analogy: a hand-stamp at a club entrance.** You want to know *"has this person already been let in?"* without keeping a giant guest list of every name. So you stamp each entrant's hand. To check someone, you look for the stamp — fast, tiny.

We have **10 billion+** URLs. Keeping every full URL string in memory to check "seen it?" would need terabytes of RAM. A **Bloom filter** is a magical stamp: a small bit-array (a few GB) that answers "have I seen this?" using a handful of hash functions — no strings stored.

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

#### Plain-English: content hashing (near-duplicate pages)

Even with perfect URL dedup, two *different* URLs can hold **identical or nearly-identical** content (mirror sites, "printer-friendly" versions, pages that differ only by an ad or a timestamp). Indexing all of them wastes storage and pollutes search results.

- **Exact duplicate → a normal hash (SHA-256).** Hash the page bytes; identical bytes → identical hash → skip. But one changed character → totally different hash, so it only catches *exact* copies.
- **Near-duplicate → SimHash / MinHash.** These are "**fuzzy** fingerprints": pages with *similar* text get *similar* fingerprints, so you can detect "95% the same" and skip it.

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

---

## 8. Politeness & robots.txt

- Fetch + **cache `/robots.txt` per host**; obey `Disallow` and **`Crawl-delay`**.
- **Rate-limit per host** (the back-queue heap enforces min interval) — never overload one server.
- Identify with a proper **User-Agent**; **back off** on 429/5xx (exponential); honor `Retry-After`.
- Respect `nofollow`, meta-robots, and canonical tags.

### Plain-English: being a polite guest

**Analogy: a considerate house guest.** A rude guest barges into every room, opens every drawer, and never reads the "Staff Only" signs. A polite guest (1) reads the posted rules at the door, (2) doesn't sprint through the house grabbing everything at once, and (3) leaves a note saying who they are. Web crawlers must be polite guests or sites will **block them** (and hammering a small server can even take it down).

#### Part A — Read the rules: `robots.txt`

Every site can publish a file at `example.com/robots.txt` that says *which paths you may crawl* and *how slowly*. It's the "house rules" posted at the door. You fetch it **once per host**, cache it, and obey it.

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

#### Q: robots.txt vs rate-limiting — aren't they the same "politeness"?

They answer different questions:

- **robots.txt = *"am I allowed here at all?"*** (a yes/no per path, plus the site's requested delay). It's about **permission**.
- **Rate-limiting = *"how fast may I go where I'm allowed?"*** It's about **pace**.

You can be allowed everywhere but still be rude by fetching too fast; you can pace perfectly but still be rude by crawling `/private/`. A polite crawler does **both**.

---

## 9. Freshness / Recrawl & Traps

- Pages change → schedule **recrawls** by observed **change frequency** (news hourly, static pages monthly) — adaptive interval from `last_crawled` + change history.
- **Spider traps** (infinite calendars, session-id URLs, faceted-filter explosions) → **depth limits, URL-pattern filters, per-host URL caps**, and detecting parameter explosions.
- **Politeness vs freshness** trade-off: important, fast-changing sites get higher priority + shorter recrawl.

### Plain-English: keeping the archive fresh (recrawl)

**Analogy: re-reading books that keep getting new editions.** A crawl isn't "done" — the web keeps changing. A news homepage rewrites itself hourly; a 2009 blog post never changes. So the robot must **revisit** pages, but revisiting *everything* on the same schedule is wasteful. The trick: **learn each page's change rate** and recrawl accordingly.

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

### Plain-English: spider traps (infinite loops)

**Analogy: a hall of mirrors.** Some sites (accidentally or maliciously) generate **infinite** unique URLs, and a naive crawler will happily follow them forever, never finishing and never getting stuck on real content:

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

#### Q: What's the freshness-vs-politeness tension?

You'd *like* to recrawl a busy news site every few minutes to stay fresh — but politeness (§8) caps how fast you may hit that host. So you spend your limited per-host budget on the pages that **change most and matter most** (high priority + short recrawl), and let stable/unimportant pages age. It's a budgeting problem, not a "crawl everything constantly" problem.

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

### Plain-English: why so many different stores?

**Analogy: a warehouse with the right shelf for each thing.** You don't keep frozen food, paperwork, and shipping crates in the same drawer. Each piece of crawler data has a different *shape* and *access pattern*, so each gets a store built for it:

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

## 12. Design Patterns (that can be used)

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

## 13. Scaling & Failure

- **Shard frontier + workers by host hash** → each host handled in one place (politeness + locality); scale by adding shards.
- **Bloom filter** for the seen-set keeps dedup cheap; KV store is the durable truth (verify on "maybe").
- **Content → S3** (compressed); text → indexing pipeline (Kafka).
- **Durable frontier** (Kafka/DB) so crashes don't lose work; **retries + DLQ** for failed fetches.
- **DNS caching** to avoid resolver bottlenecks; distribute crawlers across regions.
- **Traps** handled via depth/pattern/host caps.

### Plain-English: one robot becomes thousands (distributed crawling)

**Analogy: dividing the library by neighborhood.** One robot can't read billions of books. So you hire thousands — but if they wander randomly, two robots might read the same shelf (wasted work) *or* three might swarm one fragile shelf at once (rudeness). The fix: **give each robot ownership of specific *hosts*.**

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

#### Q: How does the crawler survive crashes without losing or double-doing work?

- **Durable frontier:** the to-do list lives in Kafka/DB, not RAM, so a dead worker loses at most a few in-flight URLs, not the plan (§5).
- **Retries + DLQ:** a failed fetch is retried with backoff; after N failures it goes to a **dead-letter queue** for later inspection instead of blocking forever.
- **Idempotent dedup:** re-processing a URL after a crash is harmless — the seen-set/content-hash checks make "crawl it again" a no-op for anything already stored.

#### Q: Do the thousands of workers need to talk to each other?

Almost never — and that's the point. Workers are **stateless** and coordinate *only* through shared infrastructure (the frontier, the seen-set, the content store). A worker's whole world is "pull a URL, fetch, push new links back." Because they don't hold private state or message each other, you scale by simply launching more of them.

---

## 14. Interview Cheat Sheet

> **"What decides what to crawl next?"**
> "The **URL frontier** (Mercator two-level): **front queues** by priority (importance/freshness) and **back queues** per host for politeness, with a **min-heap** of next-allowed-fetch times so no host is hammered. Sharded by host hash and durable so a crash doesn't lose it."

> **"How do you avoid crawling the same thing twice?"**
> "Normalize the URL and check a **seen-set** via a **Bloom filter** (O(1), no false negatives) backed by a KV store; verify in the KV on a 'maybe' to avoid false-positive skips. For duplicate content, **SimHash/MinHash** to skip near-duplicates."

> **"How do you stay polite?"**
> "Cache + obey robots.txt (Disallow + Crawl-delay), rate-limit per host via the back-queue heap, proper User-Agent, and exponential back-off on 429/5xx."

> **"What breaks a crawler and how do you handle it?"**
> "DNS bottleneck → cache aggressively. Spider traps → depth/pattern/host caps. Crash → durable frontier + retries/DLQ. Duplicate content → SimHash."

---

## 15. Final Takeaways

- **URL frontier (Mercator)** = front queues (priority) + per-host back queues + a next-fetch heap (politeness); durable + sharded by host.
- **Dedup:** URL normalization + **Bloom filter** (+ KV truth); content near-dup via **SimHash**.
- **Politeness:** robots.txt + per-host rate limiting + back-off.
- **DNS caching** is essential; **freshness** = adaptive recrawl; guard **spider traps**.
- Content → **blob store**; stages decoupled by queues (fetch → parse → extract → filter → store → index).
- Patterns: Producer-Consumer, Strategy, Bloom Filter, Pipeline/Chain, Factory (parsers), Token Bucket, Priority Queue.

### Related notes

- [Bloom Filters](../concepts/bloom-filters.md) — the seen-URL dedup structure
- [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Rate Limiting](../concepts/rate-limiting.md)

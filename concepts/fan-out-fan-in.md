# Fan-Out / Fan-In & the Celebrity Problem

> **Fan-out** = deliver one thing to **many** places (1 → N). **Fan-in** = combine **many** things into one (N → 1). These two patterns underlie **news feeds, notifications, messaging, and sharded queries**. The famous gotcha is the **celebrity problem**: fanning out one post to 50M followers explodes into 50M writes.

---

## Contents

- [1. Definitions](#1-definitions)
- [2. Fan-Out on Write vs Read (Push vs Pull)](#2-fan-out-on-write-vs-read-push-vs-pull)
- [3. The Celebrity Problem (in detail)](#3-the-celebrity-problem-in-detail)
- [4. The Hybrid Solution](#4-the-hybrid-solution)
- [5. Fan-In (Scatter-Gather / Aggregation)](#5-fan-in-scatter-gather--aggregation)
- [6. Where These Appear](#6-where-these-appear)
- [7. Trade-offs & Numbers](#7-trade-offs--numbers)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Definitions

```
FAN-OUT (1 → N):  one input distributed to many outputs
   one tweet        → written into N followers' feeds
   one order event  → N notification jobs (email + SMS + push)
   one search query → sent to N shards

FAN-IN (N → 1):   many inputs combined into one
   N shard results  → merged into one response (scatter-gather)
   N producers      → one queue / one aggregated counter
   N followees' posts → merged into one feed (pull model)
```

> **Mental hook:** MapReduce = **map is fan-out**, **reduce is fan-in**. Scatter-gather = **fan-out the request, fan-in the responses**.

---

## 2. Fan-Out on Write vs Read (Push vs Pull)

The central design decision for feeds/timelines: **when** do you do the fan-out — at **write** time or at **read** time?

### Fan-out on write (push) — precompute feeds

```
User posts → immediately WRITE the post id into EVERY follower's feed (a per-user list in Redis)
Read feed  → just read your precomputed list (fast)
```

| Pros | Cons |
| --- | --- |
| **Reads are cheap + fast** (feed already built) | **Writes amplify**: 1 post → N feed writes |
| Great when avg followers is small | **Celebrity problem** (N huge) |
| Read-heavy systems love it | Wastes work for inactive followers |

### Fan-out on read (pull) — assemble at read time

```
User posts → just store the post once
Read feed  → fetch recent posts from EVERYONE you follow, then merge + rank (fan-in)
```

| Pros | Cons |
| --- | --- |
| **Writes are cheap** (store once) | **Reads are expensive** (gather from many sources every time) |
| No wasted work; no celebrity blow-up | Slower feed load; repeated work |
| Good for users who follow many / rarely read | Hard to rank across many sources live |

> **Read-heavy → push** (do work once on write, reads are cheap). **Write-heavy / celebrity → pull** (don't amplify writes). Most large systems use a **hybrid** (§4).

---

## 3. The Celebrity Problem (in detail)

Pure **fan-out-on-write** breaks for accounts with millions of followers.

```
Normal user posts (200 followers)    → 200 feed writes      → fine
Celebrity posts (50,000,000 followers) → 50,000,000 feed writes for ONE tweet
```

**What goes wrong:**
| Symptom | Why |
| --- | --- |
| **Write amplification** | One action triggers tens of millions of writes |
| **Latency spike / backlog** | The fan-out job takes minutes; some followers see it late |
| **Hot partition / thundering herd** | A burst of writes hammers the feed store |
| **Wasted work** | Most followers won't open the app before the next post |
| **Multiple celebrities at once** | A big event (e.g. finale) = many celebrities posting → compounding load |

> It's really the **hot-key / write-amplification** problem wearing a costume. Same family as a hot DB shard or a viral URL — a single entity with disproportionate fan-out.

---

## 4. The Hybrid Solution

**Push for the masses, pull for celebrities.** This is the standard answer.

```
On post:
   if author is a NORMAL user  → fan-out on write (push post id into followers' feeds)
   if author is a CELEBRITY    → DON'T fan out; just store the post in their own timeline

On read (build home feed):
   feed = precomputed pushed posts               (from normal accounts you follow)
        ⊕ pull recent posts of the FEW celebrities you follow   (fan-in / merge)
        → rank → return
```

- **Threshold**: flag accounts above N followers (e.g. 100k) as "celebrity" → skip push for them.
- You follow only a handful of celebrities, so **pulling their latest posts at read time is cheap** — and you avoid the 50M-write explosion.
- **Best of both:** normal accounts get fast precomputed reads; celebrities avoid write amplification.
- Same idea for **notifications**: don't synchronously fan out to millions — use a **batch pipeline** (segment → chunk → queue → workers) and throttle producers.

```
Hybrid = cheap reads for the 99% (push) + cheap writes for the 1% celebrities (pull) + merge at read
```

---

## 5. Fan-In (Scatter-Gather / Aggregation)

Fan-in = combine many inputs into one result. Two common shapes:

### Scatter-gather (query many shards, merge)

```
Query → FAN-OUT to all N shards in parallel → each returns partial (e.g. top-k)
      → FAN-IN: coordinator MERGES + re-ranks → one response
```

- Used by **Elasticsearch** (search all shards → merge top-k), **sharded DBs** (query without a shard key), federated search.
- Cost: as slow as the **slowest shard** (tail latency); more shards = more tail risk. Mitigate with timeouts + partial results.

### Aggregation fan-in (many producers → one)

```
Many click events → one Kafka topic → stream aggregator → one counter per window
Many services → one log pipeline → one dashboard
```

- Used by **analytics** (ad-click aggregation), **counters** (likes/views), **log aggregation**, **MapReduce reduce**.

> **Pull-model feeds are fan-in too:** gather posts from all followees and merge into one timeline.

---

## 6. Where These Appear

| System | Fan-out | Fan-in |
| --- | --- | --- |
| **Twitter/Facebook/Instagram feed** | Push post → followers' feeds (write) | Pull + merge followees' posts (read) |
| **Notifications** | One event → many users → many channel jobs | — |
| **Group chat (WhatsApp)** | One message → all group members | — |
| **Search (Elasticsearch)** | Query → all shards | Merge top-k results |
| **Sharded DB query (no shard key)** | Query → all shards | Merge rows |
| **Analytics (ad clicks, counters)** | — | Many events → aggregated rollups |
| **MapReduce** | Map (split work) | Reduce (combine) |

---

## 7. Trade-offs & Numbers

```
Assume: 200M users, avg 200 followers, celebrity = 50M followers

Pure push:  a normal post = 200 writes (fine)
            a celebrity post = 50,000,000 writes (disaster)
Pure pull:  every feed load = gather from ~hundreds of followees + merge (slow, repeated)
Hybrid:     normal posts pushed (fast reads) + pull ~handful of celebrities at read (cheap)
            → celebrity writes drop from 50M to ~0; reads stay fast
```

| Approach | Write cost | Read cost | Celebrity-safe? |
| --- | --- | --- | --- |
| Push (fan-out on write) | High (× followers) | Low | ❌ |
| Pull (fan-out on read) | Low | High | ✅ |
| **Hybrid** ✅ | Low–medium | Low–medium | ✅ |

---

## 8. Interview Cheat Sheet

> **"What's fan-out vs fan-in?"**
> "Fan-out = deliver one input to many outputs (a post to N feeds, an event to N notifications, a query to N shards). Fan-in = combine many inputs into one (merge N shard results, aggregate N events). MapReduce is fan-out (map) then fan-in (reduce)."

> **"Fan-out on write vs read?"**
> "On write (push) precomputes each user's feed → fast reads but write amplification. On read (pull) stores once and gathers at read time → cheap writes but slow reads. Read-heavy → push; write-heavy → pull."

> **"What's the celebrity problem and how do you fix it?"**
> "Pure fan-out-on-write means one celebrity post (50M followers) triggers 50M feed writes — write amplification, latency spikes, hot partitions. Fix with a **hybrid**: push posts for normal accounts, but **don't fan out celebrity posts** — pull their recent posts at read time and merge into the feed. You follow few celebrities, so the pull is cheap, and you avoid the write explosion."

> **"What's scatter-gather?"**
> "Fan-out a query to all shards in parallel, then fan-in by merging their partial results (e.g. top-k) into one response — used by Elasticsearch and sharded DBs. Watch tail latency: you're as slow as the slowest shard."

---

## 9. Final Takeaways

- **Fan-out (1→N)** distribute; **fan-in (N→1)** combine. Map = fan-out, reduce = fan-in; scatter-gather = both.
- **Feeds:** push (fan-out on write) = fast reads / heavy writes; pull (fan-out on read) = cheap writes / heavy reads.
- **Celebrity problem** = fan-out-on-write write amplification (a hot-key problem). **Fix = hybrid**: push for normal accounts, pull celebrities at read, merge.
- **Notifications/group chat** fan out too — use a **batch pipeline + throttling**, not a synchronous blast.
- **Fan-in / scatter-gather** merges shard results (Elasticsearch, sharded queries) — mind **tail latency**; and aggregates event streams (analytics/counters).

### Related notes

- [Twitter / News Feed](../system-design/twitter-news-feed-system-design.md) · [Facebook](../system-design/facebook-system-design.md) · [Instagram](../system-design/instagram-system-design.md) — hybrid feed fan-out
- [Notification System](../system-design/notification-system-design.md) — event fan-out + batch pipeline
- [Ad Click Aggregation](../system-design/ad-click-aggregation-system-design.md) — fan-in aggregation · [Apache Kafka](kafka.md) · [Caching Strategies](caching-strategies.md)

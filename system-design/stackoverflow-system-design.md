# Stack Overflow — System Design (Q&A, Reputation & Tags)

> **Core challenge:** users ask **questions**, others post **answers**, the asker marks one **accepted**, and the community **votes** — which drives a durable **reputation** score and **badges**. Organized by **tags** (not communities), extremely **read-heavy** (most traffic is anonymous visitors arriving from Google), with three distinctive twists: **reputation/badges (gamification)**, **accepted answers**, and **close-as-duplicate** dedup.

> **How to read this doc:** each section leads with the dense interview summary, then a deep dive (annotated SQL/flows, plus the exact confusions that trip up learners — look for the `Q:` callouts). Skim the summaries for revision; read the deep dives to actually understand. `💡 tip`, `⚠️ pitfall`, and `❓ question` markers flag the things interviewers love to probe.

---

## Contents

- [1. Mental Model & vs Quora/Reddit](#1-mental-model--vs-quorareddit)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Reputation & Badges](#5-reputation--badges)
- [6. Answer Ranking & Accepted Answer](#6-answer-ranking--accepted-answer)
- [7. Tags & Search](#7-tags--search)
- [8. Data Model](#8-data-model)
- [9. API Design](#9-api-design)
- [10. Sequences](#10-sequences)
- [11. Consistency & Edge Cases](#11-consistency--edge-cases)
- [12. Scaling & Failure](#12-scaling--failure)
- [13. Consistency & CAP Tradeoffs](#13-consistency--cap-tradeoffs)
- [14. How to Drive the Interview (framework)](#14-how-to-drive-the-interview-framework)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Design Patterns](#16-design-patterns)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model & vs Quora/Reddit

```
Ask question (tagged) → many answers → community votes → asker accepts one
   → votes adjust AUTHOR reputation → reputation unlocks privileges + earns badges
   → questions found mostly via Google/tag search (anonymous read traffic dominates)
```

Someone hits a bug, Googles the error, and lands on a Stack Overflow **question page**. It shows the question, a pinned **accepted answer**, then other answers **sorted by votes**, plus comments. They might upvote, and if they're logged in with enough **reputation**, comment or edit. The person who wrote the accepted/upvoted answer earns **reputation** and maybe a **badge**. That's the whole product in one paragraph: **ask → answer → vote → accept → earn reputation**, with the overwhelming majority of traffic just *reading* one of those question pages anonymously, having arrived from a search engine.

The everyday actions: **ask** a question tagged with 1–5 tags (and don't create a duplicate of an existing one); **answer** a question (best answers float up; the asker can **accept** one); **vote** on questions and answers (this is what drives reputation); **read** a question page (by far the most common action, overwhelmingly by anonymous users from search engines); and **earn reputation & badges**, which unlock **privileges** (comment, vote, edit, moderate).

| | **Reddit** | **Quora** | **Stack Overflow** |
| --- | --- | --- | --- |
| Unit | Post in a subreddit | Question + many answers | **Question + many answers** |
| Organization | Communities (subreddits) | Topics + people you follow | **Tags** (`python`, `kafka`) |
| Ranking | Post hot/top | Answer quality (expertise, ML) | **Accepted answer pinned, then by votes** (score-based) |
| Signature twist | Nested comments, feeds | Semantic dedup, expertise ranking | **Reputation + badges**, **accepted answer**, **close-as-duplicate** |
| Traffic shape | Logged-in browsing feed | Personalized feed | **Anonymous, search-engine-driven** (SEO) |

~80% of the plumbing (questions, answers, votes, tags, async vote aggregation, caching) is the same read-heavy Q&A family you'd design for Quora or Reddit — the data model rhymes, and the vote-aggregation machinery is identical (see those docs for the deep dive on batching votes so a viral post doesn't create a hot-row lock). What actually makes SO *Stack Overflow*, and where your interview time is best spent, is three things:

1. **Reputation & badges** — votes don't just rank content, they permanently credit the *author* and gate what users are allowed to do. This has to be an **event-sourced, must-be-durable** score, unlike a fuzzy vote count that's allowed to be approximately right.
2. **Accepted answer** — the asker designates one canonical "this solved it" answer, pinned above all others regardless of votes. Quora instead ranks purely by an ML quality score; SO deliberately keeps ranking simple and deterministic (accepted-first, then votes) because a shared, cacheable page and "why is this on top?" transparency matter more here than personalized ML ranking.
3. **Read shape** — anonymous, Google-driven, cache-everything-at-the-edge. There's no per-user feed fan-out problem like Reddit/Quora have; it's a CDN + SEO problem instead.

Quora's hard parts are semantic dedup and ML answer-ranking by expertise; SO's hard parts are a correct, durable reputation system (people genuinely care about their rep and privileges, so it can't be "approximately right" the way a vote count can) and serving a firehose of anonymous read traffic cheaply. Different emphasis, same skeleton.

---

## 2. Requirements

**Functional**
- Ask questions with **tags**; post/edit answers; comment on questions/answers.
- **Upvote/downvote** questions and answers; **accept** an answer (asker only).
- **Reputation**: votes/accepts adjust the author's reputation; **badges** awarded by rules.
- **Privileges** gated by reputation (comment, vote, downvote, edit, close, moderate).
- **Tags**: browse/search questions by tag; full-text search; **close-as-duplicate**.
- **Bounties** (optional): spend rep to boost a question.

**Non-functional**
- **Extremely read-heavy** (reads ≫ writes; huge anonymous/SEO traffic); reads must be cheap and CDN-cacheable.
- **Reputation must be correct and durable** (auditable), even if it updates with a slight delay.
- Vote *counts* can be eventually consistent; scale to billions of Q&A + votes; fast search.

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Strong + durable** for the reputation ledger (auditable, recomputable); **eventual** for vote counts, cached pages, and search. |
| **Availability** | Very high for reads (anonymous pages served from CDN even during origin trouble); writes can favor correctness. |
| **Latency** | Anonymous page read ~edge-fast (tens of ms); vote/accept should feel instant (heavy work is async). |
| **Scale** | Reads ≫ writes by a huge factor; reads mostly **anonymous/SEO** → optimize **CDN bandwidth**, not per-user feeds. |
| **Durability** | A reputation event, once recorded, must never be lost (append-only log). |

> 💡 **The one NFR that sets SO apart:** *reputation must be correct and auditable.* A vote count off by one for a second is invisible; a wrong reputation can strip a privilege the user earned — a real, user-visible defect. That single requirement is why reputation is **event-sourced** (see §5), not just a mutable counter.

Functional requirements are what the product does (ask, answer, vote, accept, tag, earn rep/badges); non-functional is how well (read-cheap, rep-correct, scalable). The one non-functional requirement that sets SO apart from Quora/Reddit is that **reputation must be correct and auditable.** A vote count showing 1,240 vs 1,241 for a second is fine — nobody's access to anything depends on the exact number. But if a user's reputation is wrong, they might lose access to a privilege they earned (e.g. the 3,000-rep close-vote), which is a real, user-visible defect. So reputation is **event-sourced** (derived from an append-only log of reputation events), can be **recomputed/audited**, and is allowed to lag by seconds — but not to be *wrong*.

"Read-heavy" is also more extreme here than on Quora or Reddit, for a structural reason: most SO readers are **anonymous and arrive from Google**, not logged-in users scrolling a feed. One good answer to "TypeError: cannot read property of undefined" gets read by millions over years, mostly by people who never log in. That means (a) no per-user personalization is needed for the bulk of traffic, and (b) the rendered question page should be **cacheable at the CDN/edge** and served without touching app servers at all. The design leans hard into edge caching rather than feed fan-out — the opposite lever from Quora/Reddit, where the bottleneck is building *per-user* feeds.

---

## 3. Capacity Estimation

```
Users ~ tens of millions (registered) · questions ~ 20M+ · answers ~ 30M+ · tags ~ 60k
Traffic: overwhelmingly READS, mostly ANONYMOUS via search engines → billions of page views/month
Reads ≫ writes → CDN/edge cache rendered pages; app+DB handle the small write path
Reputation events: every vote/accept → append-only log; aggregate to a rep number
Storage: Q&A grow forever → partition + archive; search index (ES) separate
```

**Back-of-envelope (illustrative — show the method, not exact numbers):**

```
Anonymous read QPS → CDN bandwidth (the load-bearing path):
  Page views          ~ 5B / month
  Per second          5e9 / 2.6e6 s   ~ 1,900 avg → ~5,000–10,000 QPS at peak
  ~95%+ anonymous     → the overwhelming majority are CDN-cache HITS
  Rendered page       ~ 100 KB (HTML+assets)
  Peak egress         10,000 QPS * 100 KB ~ 1 GB/s served from the EDGE
  → origin sees only misses + logged-in reads + writes = a tiny fraction

Write path (tiny by comparison):
  Votes               ~ 5M / day → ~60 QPS avg (peaks higher)
  New Q&A             ~ 100–200k / day → a few QPS
  → Postgres primary + replicas handle this comfortably

Reputation-event storage (must be durable, kept forever):
  1 rep_event per vote/accept ~ 5M / day
  Row ~ 50 bytes → 5M * 50B    ~ 250 MB/day ~ 90 GB/year
  → cheap to retain for audit/recompute; partition by time, archive cold
```

> 💡 **The number that drives the design:** billions of views/month collapses to **CDN egress (~1 GB/s at the edge)**, *not* database QPS — because anonymous visitors all see the identical rendered page. Getting this framing right (reads = a caching/bandwidth problem, writes = a correctness problem) is half the interview.

Reads dominate by an enormous factor, and are mostly anonymous — so the single biggest lever is caching *rendered question pages* at a CDN so the origin is barely touched. Ideally an anonymous view doesn't hit your servers at all: it's served from a CDN edge cache (the fully rendered HTML) with a short TTL and cache invalidation when the question changes. Only *logged-in* views (which show vote buttons, edit links, personalized bits) or cache misses fall through to the app + DB. So a "billions of views/month" number mostly translates to **CDN bandwidth**, not database load. This is a different lever from Quora/Reddit, where the bottleneck is building *per-user* feeds rather than rendering one shared page.

Writes (ask/answer/vote) are comparatively tiny, so the write path (Postgres + Kafka) doesn't need exotic scaling — it needs *correctness* (reputation). Reputation events are frequent (one per vote), so treat them as an event stream, aggregate to a per-user number, and keep the raw events for audit/recompute. Q&A grows forever, so partition and archive cold content, and keep the search index (Elasticsearch) on its own stack.

---

## 4. Architecture

```
Client / Google crawler → CDN (edge-cached rendered question pages)
                              │ (miss or logged-in)
                           API Gateway
  ├── Question Service (ask, edit, close/dedup)   → RDBMS + ES
  ├── Answer Service (post/edit, accept)          → RDBMS + cache
  ├── Vote Service                                → votes + Kafka (VOTE_CAST)
  ├── Reputation Service (event-sourced)          → rep_events (append-only) + users.reputation
  ├── Badge Service (rules engine)                → badges + Kafka consumer
  ├── Tag Service                                 → tags + question_tags (inverted index)
  └── Search Service                              → Elasticsearch (full-text + tag)
             │
          Kafka (QUESTION_CREATED, ANSWER_CREATED, VOTE_CAST, ANSWER_ACCEPTED
                 → reputation, badges, search index, cache invalidation)
```

| Service | Its one job |
| --- | --- |
| **Question Service** | Create/edit questions, close & link duplicates |
| **Answer Service** | Post/edit answers, mark accepted |
| **Vote Service** | Record votes (one per user/target), emit events |
| **Reputation Service** | Turn vote/accept events into a durable, auditable rep number |
| **Badge Service** | Award badges when rule conditions are met |
| **Tag Service** | Manage tags; power tag browse/search (inverted index) |
| **Search Service** | Full-text + tag search over questions (Elasticsearch) |

The write path is the same Pub/Sub decoupling you'd use for Quora/Reddit: a write (vote, accept) is saved fast and an **event** is published; subscribers react on their own time, so the voter never waits for any of it.

```
Upvote answer ─► Vote Service ─► save vote ─► publish VOTE_CAST ─► (return fast)
                                                    │
                     Kafka fans out to independent subscribers:
                        ├─► Reputation Service (author +10 → append rep_event, update rep)
                        ├─► Badge Service      (did this cross a badge threshold?)
                        ├─► Search indexer     (score changed → update ES ranking)
                        └─► Cache invalidator  (purge the question's CDN/edge entry)
```

What's new here — and doesn't exist on Quora/Reddit — is the **Reputation** and **Badge** subscribers: a vote isn't just a counter, it's an economic event that credits a *person*. That's the core idea of **event-sourced reputation**: instead of only storing a mutable `reputation = 4210` number, you store the **append-only list of events that produced it** (`+10 upvote on answer #7`, `+15 accepted`, `−2 downvote`, …). The number `users.reputation` is a cached **aggregate** of that log. If it's ever wrong or disputed, you **replay the log** to recompute the exact value — which is why reputation can be trusted and audited, unlike a fuzzy vote count.

---

## 5. Reputation & Badges

The signature twist: votes credit the **author**, building a durable score that **gates privileges** and earns **badges**.

```
Reputation events (illustrative; real SO values):
  answer upvoted        → +10        answer downvoted   → −2 (and −1 to the downvoter)
  question upvoted      → +5         question downvoted → −2
  answer accepted       → +15 (answerer)   accepting    → +2 (asker)
  bounty awarded        → +bounty
Reputation UNLOCKS privileges (comment @50, upvote @15, downvote @125, edit @2000, close @3000, ...)
Badges: bronze/silver/gold, awarded by a RULES ENGINE on events (e.g. "answer score ≥ 25 = Nice Answer")
```

**Privileges are just `reputation >= threshold` checks** — earning rep unlocks more of the site. A representative ladder (illustrative values):

| Privilege | Rep threshold | What it lets you do |
| --- | --- | --- |
| Comment anywhere | **50** | Leave comments on any post |
| Upvote | 15 | Cast upvotes |
| Downvote | 125 | Cast downvotes (costs the target −2) |
| **Edit** any post | **2000** | Edit others' questions/answers without review |
| **Close / reopen** | **3000** | Vote to close (e.g. as duplicate) or reopen questions |
| Moderator tools | 10000+ | Access moderation queues, delete, etc. |

> 💡 Because privileges only **grant** abilities, a few seconds of reputation lag before a user crosses a threshold is harmless — they simply get the button a moment later. This is why reputation can be updated **async** (§13) without breaking the product.

**Badges** are earned by rules, not thresholds alone. Three concrete examples and their trigger conditions:

| Badge | Tier | Trigger condition |
| --- | --- | --- |
| **Nice Answer** | Bronze | An answer you posted reaches **score ≥ 10** |
| **Scholar** | Bronze | You **accept an answer** on one of your own questions (first time) |
| **Civic Duty** | Silver | You cast **300+ votes** (participation, not content quality) |

> Each badge is a **rule** evaluated by a rules engine subscribed to the event stream (see below), awarded once per user (dedup on `(user_id, badge_id[, context])`). Adding a badge = adding a rule, no core code change.

When a vote or accept happens, the Reputation Service appends an event and adjusts the author's total. Doing it as an **append + increment** (not "recount all votes") keeps it cheap and gives a perfect audit trail:

```java
// Reputation Service consumes VOTE_CAST / ANSWER_ACCEPTED events
@KafkaListener(topics = {"VOTE_CAST", "ANSWER_ACCEPTED"})
void onEvent(RepAffectingEvent e) {
    int delta = switch (e.type()) {
        case ANSWER_UPVOTE    -> +10;
        case ANSWER_DOWNVOTE  -> -2;
        case QUESTION_UPVOTE  -> +5;
        case QUESTION_DOWNVOTE-> -2;
        case ANSWER_ACCEPTED  -> +15;   // to the answerer
        default -> 0;
    };
    if (delta == 0) return;

    long authorId = e.contentAuthorId();

    // 1) append the immutable event (audit / recompute source of truth)
    repEvents.append(new RepEvent(authorId, e.contentId(), e.type(), delta, now()));

    // 2) update the cached aggregate the app reads (idempotent by eventId)
    users.addReputation(authorId, delta);
}
```

Reputation is **correct and durable, but may lag a few seconds** (eventual). It's fine if a +10 shows up a moment late; it's *not* fine if it's silently lost — hence the append-only log. Privileges are just `reputation >= threshold` checks; since they only *grant* abilities, a few seconds of lag before a user crosses 3,000 rep and can close-vote is harmless.

#### Q: Why not just do `UPDATE users SET reputation = reputation + 10` when a vote comes in? Why an event log?

Because a bare counter can't be **audited or fixed**. Picture reputation as a bank balance: banks don't store *only* your balance — they store every **transaction**, and the balance is their sum. Same here: `rep_events` is the append-only ledger (`+10 upvote on answer #7`, `−2 downvote`, `+15 accepted`), and `users.reputation` is a cached running total so reads don't re-sum millions of rows. If the number is ever disputed, wrong after a bug, or you find a fraud ring, you don't guess — you **replay the log** (`SELECT SUM(delta) FROM rep_events WHERE user_id = ?`) to get the exact truth, and you correct fraud by *appending reversal events*, never by editing history. A plain `+10` update throws away the "how did we get here?" that makes reputation trustworthy. (Vote *counts* don't get this treatment because nobody audits whether a post has 1,240 or 1,241 — only reputation gates real privileges.)

When a user *changes or removes* a vote, you must **undo the previous effect before applying the new one**, or reputation drifts. Because votes are one-per-(user, target) (an UPSERT, same as Quora/Reddit), the Vote Service knows the *old* value and emits the *net* change; the Reputation Service then appends a compensating event rather than mutating history:

```
user had +1 on answer, switches to −1:
   emit reversal (undo +10) + new (apply −2)  → net rep_event = −12, appended to the log
```

**Badges** are awarded by a **rules engine** subscribing to the same event stream. Each badge is a rule (`answer score ≥ 25 → "Nice Answer"`, `first accepted answer → "Scholar"`, `100 answers in a tag → "Guru"`). When an event arrives, only the rules relevant to that event type are evaluated for that user, and awarded once (dedup on `(user_id, badge_id[, context])`). This is a **Strategy/Rules** pattern — new badges are new rules, no core code change — and it's async, so it never slows the vote path.

**Interview scenario — a viral answer gets 50,000 upvotes fast.** The vote *count* (`score`) is handled exactly like Quora/Reddit: don't `UPDATE ... +1` per vote (that's a hot-row lock); batch `VOTE_CAST` events and update the count once per batch — approximate/eventually-consistent is fine. The difference is that each of those 50,000 upvotes must *also* credit the author +10 durably. So alongside the fuzzy count, each vote appends a `rep_event`; the aggregate `users.reputation` is updated in batches too, but because the raw events are persisted, the total is exact and recomputable, not approximate:

```java
// Vote count: batch + approximate (like Quora/Reddit). Reputation: batch too, but events are durable.
@KafkaListener(topics = "VOTE_CAST")
void aggregate(List<VoteEvent> batch) {
    Map<Long, Integer> scoreDelta = new HashMap<>();   // per target
    List<RepEvent>      repEvents  = new ArrayList<>(); // per author (durable)
    for (VoteEvent v : batch) {
        scoreDelta.merge(v.targetId(), v.value(), Integer::sum);
        repEvents.add(RepEvent.forVote(v));            // +10 / -2 ...
    }
    scoreDelta.forEach(contentRepo::addToScore);       // approximate count
    repEventRepo.appendAll(repEvents);                 // durable source of truth
    repEvents.forEach(e -> users.addReputation(e.userId(), e.delta())); // cached aggregate
}
```

**Bounty flow (spending rep to attract answers).** A bounty is the mirror image of earning rep — the asker *spends* it to boost a question. The asker offers a bounty (say 100 rep); that amount is **deducted immediately and escrowed** (appended as a negative `rep_event` so the ledger stays balanced) and the question is featured for a fixed window. When the asker awards the bounty to an answer, the escrowed rep transfers to that answerer (`+bounty` rep_event); if no answer is awarded by the deadline, half is auto-awarded to the highest-voted eligible answer, and the rest is consumed (not refunded). Modeling it as escrow-then-transfer via rep_events means the total rep in the system always reconciles and every movement is auditable — the same event-sourced discipline as ordinary voting.

To stop reputation farming (sockpuppets upvoting a friend), guard rules run entirely off the event stream, with no impact on the vote path: reject **self-votes** outright, cap daily rep gained from votes, and run fraud detection that flags serial voting patterns and issues **reversal events** (more appended compensating `rep_events`, never a history mutation). Because reputation is event-sourced, a reversal is just more events, and the audit trail shows exactly what happened.

#### Q: What stops me from creating 10 fake accounts to upvote my own answers?

A few layers, all of which live **off the hot vote path** so they never slow down honest voting. First, the cheap deterministic guards: **self-votes are rejected** outright, and there's a **daily cap** on how much reputation you can gain from votes (so a sockpuppet ring has a low ceiling per day). Second, async **fraud detection** runs against the event log looking for serial-voting patterns — the same handful of new accounts repeatedly upvoting one user, votes clustered in time, shared device/IP fingerprints. When it flags abuse, it doesn't rewrite anyone's rep by hand; it **appends reversal `rep_events`** that cancel the fraudulent gains, so the ledger stays append-only and the audit trail shows exactly what was clawed back and why. Event-sourcing is what makes this clean: an "undo" is just more events, not a dangerous in-place edit.

---

## 6. Answer Ranking & Accepted Answer

Within a question, order answers: **accepted answer pinned first**, then by **score** (votes) — much simpler than Quora's ML.

```
answer order = [ accepted answer (if any) ]  then  ORDER BY score DESC, created_at ASC
(optional "active/oldest/votes" sort toggles; default = votes with accepted on top)
```

Two different signals coexist here: "the community thinks this is good" (votes) and "the person who asked says this solved *my* problem" (accepted). SO shows both — the accepted answer is pinned to the top (green check) even if another answer later gets more votes, because it's the authoritative "this fixed it" for the original context. Accepting is chosen by the **asker** alone (the endpoint checks `question.author_id == currentUser`) — contrast with votes, which anyone with enough rep can cast. This is why "accepted" and "most upvoted" can differ, and both are shown.

```sql
-- serve a question's answers: accepted first, then by score.
-- is_accepted + score are denormalized so this is a single indexed read.
SELECT * FROM answers
WHERE  question_id = :qid
ORDER  BY is_accepted DESC,     -- the accepted one floats to the top
          score       DESC,     -- then community votes
          created_at  ASC;      -- stable tiebreak
```

#### Q: What's the difference between the "accepted" answer and the "top-voted" answer — and why show both?

They answer two *different* questions. **Top-voted** = "the community thinks this is the best answer" (anyone with enough rep votes). **Accepted** (the green check) = "the person who asked says *this* solved *my* specific problem" — and only the **asker** can set it (the endpoint checks `question.author_id == currentUser`). They often disagree: a highly upvoted answer might be a great general explanation, while the accepted one fixed the asker's exact edge case; or an old accepted answer gets outdated and a newer answer overtakes it in votes. SO deliberately **pins the accepted answer on top regardless of votes**, then lists the rest by score, and shows both signals so readers can judge. That's why the ordering is `ORDER BY is_accepted DESC, score DESC` — accepted floats to the top, community consensus follows.

You *could* rank with Quora-style expertise/ML signals, but SO's product philosophy is transparency and determinism: users want to understand *why* an answer is on top ("it has the most votes / it's accepted"), and the same question shows the same order to everyone — great for SEO and caching, since one rendered page serves all. ML per-user ranking would break that shared, cacheable page. So SO keeps ranking simple and deterministic; the "quality" intelligence goes into *reputation* and *moderation* instead.

> 💡 **Deterministic ranking is a scaling decision, not just a product one.** Because the order is a fixed rule (accepted, then votes) with no per-user ML, *every* viewer sees the identical page — so it can be rendered once and **edge-cached/served to millions** and it's stable for **SEO** (Google indexes one canonical ordering). A personalized ML ranking would make every view different, breaking both the shared cache and consistent search-engine indexing. Simplicity here directly buys the cheap-anonymous-reads property the whole system depends on.

`score` and `is_accepted` are **denormalized columns** (a value copied/precomputed onto the row to avoid a join or recount at read time) kept fresh by the async vote aggregator / accept action, with an index on `(question_id, is_accepted, score)`. So "give me this question's answers in order" is a single indexed read, and the whole rendered page is then edge-cached — a vote changes `score`, the aggregator updates it, and a cache-invalidation event purges the page. No per-request recompute, ever.

---

## 7. Tags & Search

Questions are organized by **tags** (1–5 per question), not communities or personalized topics. Search = **tag filter + full-text**, plus **close-as-duplicate**.

```
Tags: question_tags(question_id, tag_id) — an INVERTED INDEX tag → questions
Search: Elasticsearch full-text (title/body/tags) + facet/filter by tag
Dedup: close-as-duplicate → duplicate question points to a canonical; page shows a banner + link
```

A **tag is an inverted index**: it maps a term (`kafka`) to the list of questions containing it, which makes "all `kafka` questions, newest first" a fast lookup instead of scanning every question.

```sql
CREATE TABLE tags ( tag_id BIGINT PRIMARY KEY, name VARCHAR(35) UNIQUE, question_count BIGINT DEFAULT 0 );
CREATE TABLE question_tags ( question_id BIGINT, tag_id BIGINT, PRIMARY KEY (tag_id, question_id) );
-- PK (tag_id, question_id) = the inverted index: "give me all questions for this tag" is a range scan.

-- newest questions in a tag:
SELECT q.* FROM question_tags qt JOIN questions q ON q.question_id = qt.question_id
WHERE qt.tag_id = :tagId ORDER BY q.created_at DESC LIMIT 20;
```

Search is deliberately **lexical** (Elasticsearch full-text over title/body/tags) rather than the semantic/embedding search Quora leans on, because the two platforms solve dedup differently. Quora tries to *automatically* fold near-duplicate questions together via embeddings; Stack Overflow instead relies on **humans closing duplicates** (askers/mods vote "this is a duplicate of X") plus strong lexical + tag search, and in practice Google doing most of the semantic matching for you. Semantic search can be *layered on*, but the core dedup mechanism is the human **close-as-duplicate**, not an ML auto-merge.

Close-as-duplicate works like Quora's `canonical_id`, but human-driven and non-destructive: the duplicate question gets a `duplicate_of` pointer to the canonical question and a `closed` status. The page still exists (preserving its inbound Google links) but shows a "This question already has answers here: …" banner linking to the canonical. Answers aren't merged; readers are funneled to the canonical question. It's a redirect-by-banner, not a rewrite.

### Close reasons & moderation review queues

Duplicate is the most-discussed close reason, but it's one of several. A close carries a **reason code**, and the page renders a banner explaining why:

| Close reason | Meaning |
| --- | --- |
| **Duplicate** | Already answered elsewhere → `duplicate_of` points to the canonical |
| **Needs details / unclear** | Not enough info to answer as written |
| **Opinion-based** | No single correct answer (not a good fit for the format) |
| **Off-topic** | Outside the site's scope |
| **Too broad** | Should be several focused questions |

Moderation is powered by **review queues** — worklists that surface items needing human eyes, fed by events and heuristics rather than one big scan. Two representative queues:

- **First Posts** — new users' first questions/answers, surfaced so experienced users can catch spam/low-quality early.
- **Late Answers** — answers posted long after a question (often to old, high-traffic questions), reviewed for quality/relevance.

> 💡 Model a review queue as an **inverted worklist**: a write event (`ANSWER_CREATED` by a new user, a flag raised, an edit suggested) enqueues an item; reviewers with enough reputation drain the queue. It's the same event-driven pattern as the rest of the system — moderation work happens **off the read path**, so it never slows down serving pages.

---

## 8. Data Model

### Database & storage choices (which DB, and why at scale)

No single database is best for every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question for the Q&A core is *"does this need strong consistency and transactions?"* — yes: votes, accepted-answer state, and reputation deltas must never double-count. That's exactly why Stack Overflow's real system famously runs on **SQL Server**, not a NoSQL store.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Questions, answers, comments, votes, `rep_events` (**source of truth**) | **RDBMS** (SQL Server in production) | Workload is **read-heavy and edge-cacheable** (§9) — writes (new answers, votes, accepts) are comparatively rare per question, so a relational engine's ACID guarantees (one vote per user per target via composite PK, atomic accept-answer + reputation update) matter far more than raw write throughput. | An eventually-consistent NoSQL store can't cheaply enforce "one vote per user per target" or keep `accepted_answer_id`/`is_accepted` in sync across two tables inside one transaction — you'd rebuild transactions by hand for a workload that doesn't need NoSQL's write scale in the first place. |
| Tag/full-text search | **Elasticsearch** | Tag faceting (`question_tags` inverted index) plus full-text over millions of questions needs relevance ranking and tokenization the RDBMS doesn't do well. | RDBMS `LIKE` scans over `title`/`body` don't rank results or scale past a small corpus; ES is the dedicated search read model. |
| Rendered question pages (anonymous reads) | **CDN edge cache** | The *anonymous* `GET /v1/questions/{id}` is the load-bearing endpoint (§9) — caching the fully rendered page at the edge, invalidated by write events, means the vast majority of traffic never reaches the RDBMS at all. | Hitting the RDBMS (even via Redis) for every anonymous pageview on a top-traffic site multiplies load for no benefit — anonymous visitors see identical content, so edge caching is strictly cheaper than app-tier caching. |
| Session/hot object cache (logged-in reads, vote-state) | **Redis** | Logged-in requests need per-user state (vote buttons, edit permissions) that can't be edge-cached — Redis absorbs that read load with sub-ms lookups instead of hitting the RDBMS per request. | Falling through to the RDBMS for every logged-in pageview would erase the benefit of the edge-cache tier for the traffic that can't use it. |
| Reputation (append-only ledger) | **RDBMS event table (`rep_events`)**, not a separate event store | The ledger needs to join/aggregate against `users` cheaply (`SUM(delta) WHERE user_id = ?`) and stay transactionally consistent with the vote/accept events that spawn it — keeping it in the same RDBMS avoids a cross-store consistency problem. | A dedicated event-streaming store (Kafka topic as the durable log) is used for **fan-out** of reputation events to consumers (badge evaluation, cache invalidation) but the durable, queryable ledger itself stays relational so `users.reputation` can be recomputed/audited with a plain `SUM`. |

**Why relational fits SO's read-heavy, edge-cached workload:** the traffic pattern is the opposite of Twitter/Instagram — most requests are **anonymous reads of old, unchanging content**, which the CDN absorbs entirely, leaving the RDBMS to handle a comparatively small, correctness-sensitive stream of votes/answers/accepts/reputation updates. That's precisely the profile where ACID transactions are cheap (low write volume) and valuable (exact-once voting, atomic accept + reputation). There's no natural high-cardinality shard key forced by scale here — reads dominate and are cache-absorbed, so the RDBMS can run as a primary + read replicas, with `question_id`/`answer_id` as the natural partition key if/when sharding is ever needed, keeping a question's answers/comments/votes co-located. (For the full engine trade-off matrix, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

```sql
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY, name TEXT,
    reputation BIGINT DEFAULT 1,           -- cached aggregate of rep_events
    created_at TIMESTAMP
);

CREATE TABLE questions (
    question_id BIGINT PRIMARY KEY, author_id BIGINT, title TEXT, body TEXT,
    score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    view_count BIGINT DEFAULT 0, answer_count INT DEFAULT 0,
    accepted_answer_id BIGINT,             -- the asker-accepted answer (nullable)
    status VARCHAR(16) DEFAULT 'open',     -- open | closed | duplicate | deleted
    duplicate_of BIGINT,                   -- canonical question if closed as duplicate
    created_at TIMESTAMP
);

CREATE TABLE answers (
    answer_id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, author_id BIGINT,
    body TEXT, score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    is_accepted BOOLEAN DEFAULT FALSE, created_at TIMESTAMP, updated_at TIMESTAMP
);
CREATE INDEX idx_answers_q_order ON answers(question_id, is_accepted DESC, score DESC);

CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY,
    parent_type VARCHAR(8), parent_id BIGINT,   -- comment on a question OR an answer
    author_id BIGINT, body TEXT, created_at TIMESTAMP );

CREATE TABLE votes (
    user_id BIGINT, target_type VARCHAR(8), target_id BIGINT,   -- 'question' | 'answer'
    value SMALLINT, created_at TIMESTAMP,
    PRIMARY KEY (user_id, target_type, target_id)    -- one vote per user per target
);

-- Event-sourced reputation: the append-only source of truth
CREATE TABLE rep_events (
    event_id BIGINT PRIMARY KEY, user_id BIGINT, source_id BIGINT,
    type VARCHAR(24), delta INT, created_at TIMESTAMP
);
CREATE INDEX idx_rep_events_user ON rep_events(user_id, created_at);

CREATE TABLE badges ( badge_id BIGINT PRIMARY KEY, name TEXT, tier VARCHAR(8) );  -- bronze/silver/gold
CREATE TABLE user_badges ( user_id BIGINT, badge_id BIGINT, awarded_at TIMESTAMP,
    context TEXT, PRIMARY KEY (user_id, badge_id, context) );   -- dedup award

CREATE TABLE tags ( tag_id BIGINT PRIMARY KEY, name VARCHAR(35) UNIQUE, question_count BIGINT DEFAULT 0 );
CREATE TABLE question_tags ( question_id BIGINT, tag_id BIGINT, PRIMARY KEY (tag_id, question_id) );

-- Rendered question pages edge-cached (CDN); search in Elasticsearch (full-text + tags)
```

| Group | Tables | What they represent |
| --- | --- | --- |
| **The content** | `questions`, `answers`, `comments` | The Q&A — question → many answers → comments |
| **The organization** | `tags`, `question_tags` | Tag inverted index (a question has 1–5 tags) |
| **The interactions** | `votes` | Up/down votes on questions and answers |
| **The reputation economy** | `rep_events`, `users.reputation`, `badges`, `user_badges` | Durable, auditable rep + gamification |

A few deliberate denormalizations are worth calling out because they recur throughout the design: `rep_events` is the append-only source of truth (every +10/−2/+15, auditable, replayable) while `users.reputation` is the cached aggregate so reads don't sum millions of events — if the number is ever disputed, recompute it with `SELECT SUM(delta) FROM rep_events WHERE user_id = ?`. Similarly, `questions.accepted_answer_id` and `answers.is_accepted` are two views of the same fact: the first answers "does this question have an accepted answer, and which one?" in one row without a join; the second lets the answer-ordering index `(question_id, is_accepted DESC, score DESC)` pin the accepted answer first. Keep both in sync when the asker accepts/unaccepts. And `votes` uses a composite `(user_id, target_type, target_id)` primary key — the tuple *is* the identity, enforcing "one vote per user per target" with a single index; `target_type` exists because on SO you vote on both questions and answers, unlike Quora where votes only target answers.

```sql
INSERT INTO votes (user_id, target_type, target_id, value, created_at)
VALUES (:user, :type, :target, :value, now())
ON CONFLICT (user_id, target_type, target_id) DO UPDATE SET value = EXCLUDED.value;
```

#### Q: How does the composite primary key `(user_id, target_type, target_id)` enforce "one vote per user"?

The primary key **is** the rule. By making the tuple `(user_id, target_type, target_id)` the PK, the database physically refuses to store two rows with the same combination — so a given user can have at most **one** vote row per target (a specific question or answer). There's no separate "check if they already voted" step that could race: a second vote from the same user on the same target collides with the existing key, and the `ON CONFLICT ... DO UPDATE` turns that collision into an **UPSERT** — it *updates* the existing row's `value` (e.g. flips +1 to −1) instead of inserting a duplicate. `target_type` is in the key because on SO you vote on both questions *and* answers, so you need it to tell a vote on question #42 apart from a vote on answer #42. This is the same one-vote-per-user machinery Quora/Reddit use; SO just adds `target_type` because it has two votable things.

---

## 9. API Design

```
POST /v1/questions              { title, body, tags[] }        # may warn "similar questions exist"
GET  /v1/questions/{id}                                        # rendered page (edge-cached for anon)
GET  /v1/questions/{id}/answers?sort=votes&cursor=
POST /v1/questions/{id}/answers { body }
POST /v1/answers/{id}/accept                                   # asker only → sets accepted
POST /v1/questions/{id}/vote    { value }                      # +1 / -1
POST /v1/answers/{id}/vote      { value }
POST /v1/questions/{id}/close   { duplicateOf? }               # community/mod: close / dupe
GET  /v1/tags/{name}/questions?sort=newest&cursor=             # tag inverted index
GET  /v1/search?q=...&tags=...                                 # lexical + tag facets
GET  /v1/users/{id}                                            # reputation, badges, activity
```

| You tap... | API call | What happens behind it |
| --- | --- | --- |
| "Ask Question" | `POST /v1/questions` | Suggests similar existing questions; creates + emits `QUESTION_CREATED` |
| Open a question | `GET /v1/questions/{id}` | Anon → **CDN edge cache**; logged-in/miss → app renders |
| "Post Answer" | `POST /v1/questions/{id}/answers` | Saves + emits `ANSWER_CREATED` |
| Green check (accept) | `POST /v1/answers/{id}/accept` | Asker-only; sets `is_accepted` → `ANSWER_ACCEPTED` → +15 rep |
| Upvote | `POST /v1/answers/{id}/vote` | Records vote; async aggregation + reputation event |
| "Close as duplicate" | `POST /v1/questions/{id}/close` | Sets `status=duplicate`, `duplicate_of`; page shows banner |

The anonymous `GET /v1/questions/{id}` is the load-bearing endpoint of the whole system, and it's designed to avoid the DB almost entirely: the rendered HTML is cached at the **CDN edge** keyed by question id, with a short TTL and event-driven invalidation (when the question/answers/votes change, a cache-invalidation event purges that key). Anonymous requests — the vast majority — are served entirely from the edge; only logged-in requests (which need vote-state, edit buttons, personalized chrome) or cache misses fall through to the origin.

#### Q: Why can anonymous reads skip the database entirely, but logged-in reads can't?

Because **every anonymous visitor sees the exact same page.** An anonymous view of a question shows the question, answers (accepted-first, then by votes), comments, and scores — none of which depend on *who* is looking. So the fully rendered HTML can be built once and cached at the CDN edge; a million anonymous readers of the same question all get the identical bytes from a nearby edge server, and the origin/DB is never touched. A **logged-in** view is different: it must show *your* vote arrows (did you already upvote?), edit/close buttons gated by *your* reputation, and personalized chrome — all per-user, so it can't be a single shared cached page and has to fall through to the app + DB. The design leans into this split: push the huge anonymous firehose to the edge, and only spend origin/DB capacity on the comparatively tiny logged-in + write traffic. (This is the opposite lever from Twitter/Instagram, where reads are personalized feeds that *can't* be shared — see §3.)

---

## 10. Sequences

**Ask a question (with duplicate suggestion):**

```
User → QuestionSvc:
  lexical + tag search over existing questions (ES)
  show "similar questions" (soft nudge — never hard-block)
  create question → emit QUESTION_CREATED → index in ES, warm tag index
(later) community/mod votes to close as duplicate → set duplicate_of + status=duplicate → banner
```

**Answer, vote, accept → reputation:**

```
User → AnswerSvc: create answer → emit ANSWER_CREATED
Vote → VoteSvc: UPSERT vote (one per user/target) → VOTE_CAST → Kafka
Accept → AnswerSvc: set is_accepted + accepted_answer_id → ANSWER_ACCEPTED → Kafka
ReputationSvc (consumer): append rep_event(+10/-2/+15...) → update users.reputation
BadgeSvc (consumer): evaluate badge rules → maybe award badge
CacheInvalidator (consumer): purge the question's CDN/edge page
Read question → serve edge-cached page (anon) OR render from denormalized score/is_accepted
```

The point to emphasize when walking through this: **the write is instant and the reputation/badges/cache all happen asynchronously off Kafka** — the voter never waits for any of it.

```
You upvote an answer
   │
   ▼
VoteSvc UPSERTs the vote ──► publishes VOTE_CAST ──► (you're done, instantly)
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
  ReputationSvc               BadgeSvc                    CacheInvalidator
  append +10 rep_event        "score ≥ 25? → Nice Answer" purge question page
  users.reputation += 10      award badge (once)          (next read re-renders)
```

The gap between voting and reputation updating is because reputation is updated by an async consumer, not synchronously in the vote request: the vote is saved and acknowledged immediately, and the author's rep ticks up a moment later. That keeps voting fast and lets reputation be computed carefully rather than under request-latency pressure. The lag is seconds and harmless — the event is durable, so the +10 is never lost.

---

## 11. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Duplicate question | Soft "similar questions" on ask; community **close-as-duplicate** → `duplicate_of` + banner |
| Vote exactness | Async aggregation → approximate cached `score` |
| **Reputation correctness** | **Event-sourced (`rep_events`); recomputable/auditable; may lag, never lost** |
| Vote change/removal | Emit net delta → append **compensating** rep_event (never mutate history) |
| One vote per user/target | `PRIMARY KEY (user_id, target_type, target_id)`; re-vote updates value |
| Accept/unaccept | Sync `is_accepted` + `accepted_answer_id`; emit `ANSWER_ACCEPTED` |
| Self-vote / vote fraud | Reject self-votes; fraud detection caps serial voting (rep reversal) |
| Deleted question/answer | Tombstone; reverse associated reputation; purge from index + CDN |
| Anonymous read spike (viral) | Served from CDN edge; origin barely touched |

Two edge cases deserve a bit more depth because they're the ones interviewers probe. **Edits and deletes staying consistent across search, reputation, and the CDN**: an edit to a question/answer triggers a re-index in Elasticsearch (via **CDC** — Change Data Capture, streaming the DB's committed row changes to downstream consumers like the search indexer) and a cache-invalidation event that purges the edge-cached page, so the next reader re-renders fresh; a delete tombstones the row, reverses any reputation it earned (more compensating `rep_events`), removes it from the search index, and purges the CDN entry. Tombstoning rather than hard-deleting keeps inbound links from 500-ing and lets moderation undo the action.

#### Q: If pages are cached at the edge, won't readers see stale content after someone edits an answer?

Briefly, yes — and that's an accepted trade-off. When an answer is edited (or voted, accepted, closed), the write publishes a **cache-invalidation event** that purges that question's edge entry; the *next* reader then triggers a fresh render that re-populates the cache. Between the edit and the purge propagating across edge nodes, some readers can get the old page for a short window (seconds). That's fine because a Q&A page is not money — nobody is harmed by seeing an answer's slightly-old wording or a score of 41 instead of 42 for a moment. We also set a **short TTL** as a backstop so even a missed invalidation self-heals quickly. The mental model: **invalidate on write for freshness, lean on TTL as a safety net, and accept eventual consistency** because the alternative — no edge cache, every read hitting the origin — would cost orders of magnitude more for a benefit (perfect freshness) that readers don't need. ⚠️ The one thing you *don't* let go stale is the reputation ledger; that's why it's CP (§13) while pages are AP.

**Reputation farming** (sockpuppets upvoting a friend) is guarded entirely off the write path, the same way as described in §5: reject self-votes, cap daily rep gains, and flag serial-voting patterns for a reversal event. None of these guards touch the hot vote path — they run against the event log asynchronously.

---

## 12. Scaling & Failure

- **Read-heavy + anonymous** → **CDN/edge-cache rendered question pages**; origin barely touched.
- **Votes** async-aggregated (Kafka); approximate cached `score`.
- **Reputation** event-sourced: durable append-only log + cached aggregate; recompute on dispute.
- **Search** on **Elasticsearch** (full-text + tags), rebuilt from the DB via **CDC**.
- **Tags** = inverted index for fast tag browsing.
- Partition Q&A; archive cold content; eventual consistency for counts/rep-lag acceptable.

The recurring theme across all of it: serve reads from the edge (the dominant traffic — anonymous, Google-driven — hits the CDN, so the app/DB handle only writes + logged-in reads + misses), do heavy work off the write path (reputation, badges, indexing, and cache invalidation all run as async Kafka consumers, so voting/accepting stay instant), and protect the truth (vote counts can be approximate, but reputation is event-sourced so it's never lost and can be audited/recomputed).

| If this breaks... | What users see | Why it's survivable |
| --- | --- | --- |
| Reputation Service down | Rep updates lag | Events queue in Kafka; processed (in order) on restart — nothing lost |
| Badge Service down | Badges awarded late | Same event stream; catches up; awards are idempotent |
| Vote aggregator lags | Score updates slowly | Votes are saved; totals reconcile from events |
| Search cluster down | Search degraded | Core Q&A read/write works; index rebuilds from CDC |
| Origin under load spike | Slightly stale pages | CDN keeps serving cached pages; TTL absorbs the spike |

Counts and freshness may lag, but the **reputation log is durable and ordered**, so the one thing users truly care about (their earned rep) is always recoverable.

---

## 13. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you pick consistency vs availability?" On SO the answer splits cleanly — the **reputation ledger is CP**, everything a reader sees is **AP**.

| Path | Choice | Why |
| --- | --- | --- |
| **Reputation ledger (`rep_events`)** | **CP** (strong + durable) | A user's rep gates real privileges (close-vote @3000). It may **lag** a few seconds, but it must never be **lost or wrong** — so it's an append-only, auditable, recomputable log. |
| **Vote counts (`score`)** | **AP** (eventual) | 1,240 vs 1,241 for a moment is invisible to users; async batch aggregation is fine (and avoids a hot-row lock on a viral post). |
| **Rendered question pages (anon reads)** | **AP** (eventual, CDN-cached) | The dominant traffic is served stale-for-seconds from the edge; a cache-invalidation event refreshes it after a write. |
| **Search index (Elasticsearch)** | **AP** (eventual) | Rebuilt from the DB via CDC; a few seconds of staleness in results is fine. |

> 💡 **The mental split:** *"CP for the money — the reputation ledger — because privileges depend on it; AP for everything a reader sees, because vote counts and cached pages can be a few seconds stale without anyone noticing."* Reputation is allowed to be **late but never wrong**; vote counts are allowed to be **approximate**.

> ⚠️ **Pitfall — don't make reputation synchronous for the sake of consistency.** It's tempting to update rep inside the vote request "so it's always right." That reintroduces the hot-row lock you avoided for the count, and couples voting latency to reputation work. Keep it async off Kafka; durability (the append-only log), not synchrony, is what makes it correct.

---

## 14. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min framing (1–4), then go deep on the **three twists** that make this Stack Overflow and not a generic Q&A clone.

1. **Clarify requirements** (functional + NFRs) — call out the two that matter: *reputation must be correct/durable*, and *reads are extreme + anonymous* — §2.
2. **Estimate scale** — establish reads ≫ writes and that reads are anonymous → the lever is **CDN bandwidth**, not DB QPS — §3.
3. **APIs + high-level architecture + data model** — §4, §8, §9.
4. **Name the three twists up front**, then go deep on each:
   - **Reputation & badges** — event-sourced, durable, auditable; gates privileges — §5.
   - **Accepted answer** — asker-chosen, pinned above votes; deterministic ranking for cacheability/SEO — §6.
   - **CDN read shape** — anonymous pages served from the edge, not per-user feed fan-out — §3, §9.
5. **Consistency story** — CP reputation ledger, AP counts/pages — §13.
6. **Scale + failure + abuse** — async pipelines, edge cache, reputation farming guards — §5, §11, §12.

> 🎤 **Lead with the framing:** *"~80% of this is a read-heavy Q&A system like Quora/Reddit — same votes, tags, async aggregation. The interesting 20% is three twists: **reputation**, **accepted answer**, and **CDN-served anonymous reads**."* Say that sentence early and you've shown you know exactly where the depth is — then spend your time there, not re-deriving generic voting.

---

## 15. Interview Cheat Sheet

> **"How is Stack Overflow different from Quora/Reddit?"**
> "Same Q&A + voting skeleton, but three twists: a **durable, event-sourced reputation system** with **badges** and **privilege gating**; an **asker-accepted answer** pinned above vote-ranked ones; and **anonymous, SEO-driven read traffic** served from a **CDN edge cache** rather than per-user feeds. Ranking is deterministic (accepted, then votes), not ML."

> **"How does reputation work at scale and stay correct?"**
> "**Event-sourced**: every vote/accept appends an immutable `rep_event`; `users.reputation` is a cached aggregate. Updated async off Kafka (may lag seconds) but never lost, and recomputable by summing the log. Vote *changes* append compensating events; daily caps + fraud reversals guard against farming."

> **"How do you serve billions of anonymous reads?"**
> "Cache the **rendered question page at the CDN edge**, keyed by question id, with event-driven invalidation on any change. Anonymous requests never hit the origin; only logged-in/miss requests do. Deterministic ranking means one page serves everyone (cache-friendly + SEO)."

> **"How do you rank answers?"**
> "**Accepted answer first** (asker's choice), then `ORDER BY score DESC` — with `is_accepted` and `score` denormalized and indexed `(question_id, is_accepted, score)`, so ordering is a single fast read, no ML, no per-request recompute."

> **"How do you handle duplicates?"**
> "Soft 'similar questions' suggestion on ask (never hard-block) + strong lexical/tag search; the real mechanism is **community close-as-duplicate** → `duplicate_of` pointer + a banner linking to the canonical (non-destructive, preserves SEO)."

### Indexes that matter

- `answers (question_id, is_accepted DESC, score DESC)` — serve a question's answers in display order (accepted first, then votes) in one read.
- `votes (user_id, target_type, target_id)` **PRIMARY KEY** — enforces one vote per user per target; makes the UPSERT (re-vote) a single keyed write.
- `rep_events (user_id, created_at)` — recompute/audit a user's reputation (`SUM(delta)`) and show their rep timeline.
- `question_tags (tag_id, question_id)` **PRIMARY KEY** — the tag inverted index: "all questions for this tag, newest first" is a range scan.
- `user_badges (user_id, badge_id, context)` **PRIMARY KEY** — dedup so a badge is awarded once.
- `questions (status, duplicate_of)` — find/serve closed & duplicate questions (banner rendering, moderation queues).

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Viral answer gets 50k upvotes fast** | Batch `VOTE_CAST` for the **count** (approximate, avoids hot-row lock); still append a durable `rep_event` per vote so author rep is exact. |
| **User flips +1 → −1 on an answer** | UPSERT the vote (one row per user/target); Vote Service emits the **net** delta; Reputation Service appends a **compensating** rep_event (undo +10, apply −2). |
| **Answer edited after page is edge-cached** | Publish cache-invalidation event → purge the edge entry; next read re-renders. Short TTL backstops a missed purge. Brief staleness is acceptable (§13). |
| **Rep updates lag / Reputation Service down** | Events queue in Kafka, processed in order on restart — **nothing lost**. Privileges just unlock a moment late. |
| **Sockpuppet upvote ring** | Reject self-votes, daily rep cap, async fraud detection → **reversal rep_events** (never mutate history). |
| **Accepted answer isn't the top-voted one** | Both shown by design: `ORDER BY is_accepted DESC, score DESC`. Accepted = asker's "this solved it"; top = community consensus. |
| **Duplicate question keeps getting asked** | Soft "similar questions" nudge on ask; community **close-as-duplicate** → `duplicate_of` + banner (non-destructive, preserves inbound Google links). |

---

## 16. Design Patterns

| Pattern | Where | Why |
| --- | --- | --- |
| **Event Sourcing** | Reputation (`rep_events` → `users.reputation`) | Durable, auditable, replayable |
| **Observer / Pub-Sub** | Vote/accept events → reputation, badges, index, cache purge | Decouple write from reactions |
| **Producer-Consumer** | Async vote aggregation via Kafka | Absorb the vote firehose |
| **CQRS + Edge Cache** | Rendered question pages vs write stores | Serve anonymous reads cheaply |
| **Strategy / Rules Engine** | Badge awarding, close-reason handling | Add badges/rules without core changes |
| **Template Method** | `Votable` base for Question/Answer (shared vote logic) | Reuse voting across types |
| **Inverted Index** | Tags (`tag → questions`), search | Fast tag/keyword lookup |
| **Composite** | Comment threads | Uniform tree ops |
| **Repository / Facade** | Data access; search/feed service | Testable, simple API |

Lead an interview answer with the three that make SO distinctive — **Event Sourcing** (durable reputation), **CQRS + edge caching** (serving the anonymous read firehose), and **Observer/Pub-Sub** (a vote fanning out to reputation/badges/index/cache) — and mention Template Method for the `Votable` question/answer sharing if LLD comes up.

---

## 17. Final Takeaways

- **Question → many answers**, **accepted-first then by votes** — deterministic, cacheable ranking, little ML, because a shared page and transparency matter more here than personalization.
- **Reputation is the signature system**: durable, auditable, event-sourced, gating privileges and badges — never approximate, even though vote *counts* are.
- **Read shape is the scaling story**: anonymous, Google-driven traffic pushed to the edge, so the origin only ever sees writes and the rare logged-in read.
- **Dedup is human, not ML**: close-as-duplicate + strong lexical/tag search, leaning on Google rather than semantic embeddings.
- Every async pipeline (votes, reputation, badges, cache invalidation, search indexing) exists to keep the one synchronous path — the request itself — instant.

### Related notes

- [Quora — System Design](quora-system-design.md) · [Reddit — System Design](reddit-system-design.md) · [Twitter / News Feed](twitter-news-feed-system-design.md) — sibling Q&A / feed / voting platforms
- [Stack Overflow — Machine Coding (LLD)](../machine-coding/stack-overflow/README.md) — the `Votable` / reputation OOP implementation
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) · [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md)

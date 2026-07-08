# Quora — System Design (Q&A Platform)

> **Core challenge:** users ask **questions**, others post **answers**, and the best answers are **ranked by quality** and surfaced in **topic + personalized feeds**. Read-heavy like Reddit, but organized around **question → many answers**, with two distinctive twists: **question dedup/semantic search** and **answer-quality ranking (author expertise matters)**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/SQL and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model & vs Reddit](#1-mental-model--vs-reddit)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Question Dedup & Semantic Search](#5-question-dedup--semantic-search)
- [6. Answer Ranking](#6-answer-ranking)
- [7. Feed Generation](#7-feed-generation)
- [8. Data Model (all tables)](#8-data-model-all-tables)
- [9. API Design](#9-api-design)
- [10. Sequences](#10-sequences)
- [11. Consistency & Edge Cases](#11-consistency--edge-cases)
- [12. Design Patterns (that can be used)](#12-design-patterns-that-can-be-used)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model & vs Reddit

```
Ask question → tag topics → many users answer → answers ranked by QUALITY
   → feeds surface Q&A by followed topics/people + personalization → upvote/comment/follow
```

| | **Reddit** | **Quora** |
| --- | --- | --- |
| Unit | Post in a subreddit | **Question** with **many answers** |
| Organization | Communities (subreddits) | **Topics** + people you follow |
| Ranking | Post hot/top | **Answer quality** within a question (expertise-weighted) |
| Dedup | Not central | **Question dedup** (merge duplicates → canonical) |

The feed/voting/read-heavy machinery is similar; the twists are **answer ranking** and **question dedup/semantic search**.

### What are we actually building?

Picture a giant Q&A website. Someone types a question — *"How do I learn Python?"* — and tags it with topics like `Python` and `Programming`. Other people write **answers**. Everyone else **upvotes** the answers they like, **follows** topics and people they care about, and scrolls a **home feed** of interesting Q&A.

So there are really four everyday actions, and the whole system is built to make each one fast and correct:

- **Ask** a question (and don't create the 101st copy of one that already exists).
- **Answer** a question (and let the *best* answers float to the top, not just the newest).
- **Vote** on answers (billions of these, so we can't do heavy math on every click).
- **Read** a personalized feed and read individual questions (this happens *way* more than everything else combined).

**Why compare it to Reddit?** Because 90% of the plumbing is the same read-heavy, vote-and-feed machinery. Reddit's unit is a *post in a community*; Quora's unit is a *question with many answers*. The two things that make Quora special:

1. **Answer ranking by quality** — a doctor's answer about medicine should beat a random stranger's, even with similar votes. Ranking uses *author expertise*, not just vote count.
2. **Question dedup** — "How do I learn Python?" and "Best way to get started with Python?" are the *same* question. We detect that and keep all answers together under one canonical question, instead of scattering them across 100 near-identical pages.

#### Q: Why not just store questions and answers in one big table and be done?

You can store them fine — the hard part is **serving reads fast at scale**. Billions of people read; only a tiny fraction write. If every page view recomputed "which answers are best?" and "what should this user's feed contain?" from scratch, the database would melt. The recurring trick in this whole design is: **do the expensive work once (rank answers, build feeds, aggregate votes) ahead of time, cache the result, and let reads just fetch it.** That's the CQRS idea you'll see repeated in every section below.

---

## 2. Requirements

**Functional**
- Ask questions (tagged with topics); post/edit answers; comment; upvote/downvote answers.
- Follow **topics** and **users**; personalized home feed.
- **Answer ranking** per question (best answers first).
- **Search** questions; **dedup** near-duplicate questions (merge to canonical); "ask to answer".

**Non-functional**
- Read-heavy (reads ≫ writes); **eventual consistency** ok; scale to billions of Q&A; **fast, semantic search**.

### Functional vs non-functional, and why "eventual consistency" is fine here

- **Functional** = *what the product does* (ask, answer, vote, follow, feed, search, dedup). If you removed one, a user would notice a missing button.
- **Non-functional** = *how well it must behave* (fast, scalable, mostly-correct). No button for these, but the app feels broken without them.

**Read-heavy** is the single most important constraint. On a Q&A site, one person writes an answer and then *millions* read it over months. Reads outnumber writes by a huge factor, so we optimize hard for reads (caching, precomputed lists) and can afford to be a bit slower/cleverer on writes.

#### Q: "Eventual consistency ok" — doesn't that mean the site shows wrong data?

It means *slightly stale is fine here because nobody's money or safety is on the line.* If you upvote an answer and its count shows **1,240** for a few seconds before ticking to **1,241**, no harm done. Compare that to a bank balance, which must be exact instantly (**strong** consistency). Quora deliberately trades a few seconds of staleness (in vote counts, rankings, feeds) for massive speed and scale. For example, a "most popular answers" list can refresh every few minutes rather than the instant each reader clicks.

---

## 3. Capacity Estimation

```
Users ~ 300M · questions ~ 100M's · answers ~ several per question · views ~ billions/day
Reads ≫ writes (browse/search) → cache + precomputed rankings
Search/dedup on every ask + every search → ES + vector store must scale
Storage: questions/answers grow forever → partition + archive; search index separate
```

### Reading the back-of-the-envelope numbers

These numbers exist to justify design choices, not to be exact. The story they tell:

- **~300M users, ~billions of views/day** → reads dominate. This is why we cache feeds and ranked answers instead of computing them live.
- **Reads ≫ writes** → it's worth spending effort at *write time* (rank answers, fan out feeds, aggregate votes) so *read time* is a cheap cache lookup. Doing expensive work for the rare writer to make life easy for the billions of readers is a great trade.
- **Search/dedup runs on every ask and every search** → the search stack (Elasticsearch + vector DB) is hit constantly and must scale independently of the main database.
- **Data grows forever** → questions and answers never really get deleted, so we partition (split across many machines) and archive cold, rarely-read content to cheaper storage.

#### Q: How do I turn a vague "billions of views" into a number I can reason about?

Pick a round number and divide by seconds in a day (~86,400, call it ~100k). Example:

```
2B views/day ÷ ~100,000 sec/day ≈ 20,000 reads/sec average
peak traffic is spikier → multiply by 3–5× → ~60k–100k reads/sec at peak
```

The exact figure barely matters — what matters is the *conclusion*: "tens of thousands of reads per second, so a single database can't serve reads directly; we need caches and read replicas." Estimation in interviews is about reaching that conclusion, not the arithmetic.

---

## 4. Architecture

```
Client → API Gateway
  ├── Question Service (ask, dedup, merge)   → RDBMS + ES + vector store
  ├── Answer Service (post/edit, rank)       → RDBMS + cache
  ├── Vote Service                           → votes + Kafka (async aggregation)
  ├── Feed Service (candidates + ML rank)    → Redis feed cache
  ├── Search Service                         → Elasticsearch + vector DB (embeddings)
  └── Graph Service (follows topics/users)
             │
          Kafka (QUESTION_CREATED, ANSWER_CREATED, VOTE_CAST → ranking, feed, index, notifications)
```

- **CQRS:** precomputed ranked answer lists + feeds (read) vs question/answer/vote stores (write).

### Why split it into services, and what each one does

Each service is a **microservice** — it owns one job and one slice of the data, so it can be scaled, deployed, and reasoned about on its own.

| Service | Its one job |
| --- | --- |
| **Question Service** | Create questions, detect duplicates, merge them |
| **Answer Service** | Post/edit answers, keep them ranked |
| **Vote Service** | Record votes, aggregate counts asynchronously |
| **Feed Service** | Build each user's personalized home feed |
| **Search Service** | Lexical + semantic search over questions |
| **Graph Service** | Who follows which topics/users |

#### Q: What is Kafka doing in the middle of this diagram?

When you post an answer, several things must happen: update rankings, refresh feeds, add it to the search index, notify followers. If the Answer Service did all of that *before* replying to you, posting would feel slow and one failing step (say, notifications) could break the whole action.

Instead, the Answer Service just saves the answer and **publishes an event** — `ANSWER_CREATED` — onto Kafka, then returns immediately. Kafka is a shared **event log**; other services *subscribe* and react on their own time: the ranking job re-ranks, the feed service fans it out, the indexer indexes it, notifications fire. This is the **Pub/Sub (Observer)** pattern: the writer doesn't know or wait for the reactors.

```
POST answer ─► Answer Service ─► save to DB ─► publish ANSWER_CREATED ─► (return to user, fast)
                                                      │
                        Kafka fans the event out to independent subscribers:
                                                      ├─► Ranking job   (re-rank this question)
                                                      ├─► Feed service  (push to followers' feeds)
                                                      ├─► Search indexer(add to ES + vector DB)
                                                      └─► Notifications (tell followers)
```

#### Q: What does "CQRS" mean in one sentence?

**Command Query Responsibility Segregation** = *separate the write path from the read path.* Writes (post an answer, cast a vote) go into normal databases. But reads don't run live queries against those — instead a background job precomputes the answers ("ranked answer list for question 42", "home feed for user 7") into a fast cache, and reads just fetch the finished result. Write side and read side have different shapes, optimized separately — the read path serves precomputed results so requests return instantly.

---

## 5. Question Dedup & Semantic Search

Avoid 100 copies of "How do I learn Python?" — consolidate answers under one canonical question.

```
On ask:
  1. lexical search (Elasticsearch) + SEMANTIC search (embedding of the question → vector DB, ANN)
  2. if a similar question scores above a threshold → suggest it; user may reuse it
  3. if truly new → create; a moderator/system merge can later fold a duplicate into a CANONICAL question
```

| Piece | Detail |
| --- | --- |
| **Lexical** | Elasticsearch full-text (title/body) — catches keyword overlaps |
| **Semantic** | Embed the question with an ML model → **vector DB (ANN/HNSW)** → find meaning-similar questions (different words, same intent) |
| **Threshold** | Above similarity threshold → treat as duplicate candidate |
| **Merge** | Duplicate points to a **`canonical_id`**; requests redirect; answers surface under the canonical question |

- **Why both?** Lexical misses paraphrases ("learn Python" vs "get started with Python programming"); semantic embeddings catch intent. (See the Vector DB section in Databases Deep Dive.)

### Catching duplicate questions two different ways

Consider searching for "getting started with snake programming." A keyword-only search matches just the literal words "snake programming" and finds nothing. A search that understands *meaning* recognizes this is really about Python. Quora needs both kinds of matching:

- **Lexical search (Elasticsearch)** = the word-matcher. Fast, great when people use the same keywords ("Python", "learn"). Blind to synonyms and rephrasing.
- **Semantic search (embeddings + vector DB)** = the meaning-matcher. It turns each question into a list of numbers (an **embedding**) that captures *meaning*, so two questions with different words but the same intent end up as *nearby* number-lists.

#### Q: What on earth is an "embedding" / "vector"?

An **embedding** is a question boiled down to a long list of numbers (e.g. 768 of them) by an ML model, positioned so that *similar meanings sit close together in space*. In that space, "How do I learn Python?" and "Best way to start Python programming?" land right next to each other even though they share almost no words. To find duplicates, we just look for the **nearest neighbors**. A **vector DB** (using an index like HNSW) is a database built to answer "which stored vectors are closest to this one?" extremely fast — that's called **ANN, Approximate Nearest Neighbor** search.

```java
// The dedup check that runs when someone asks a new question
class QuestionService {

    DedupResult checkForDuplicate(String title, String body) {
        String text = title + " " + body;

        // 1) LEXICAL: keyword match in Elasticsearch (catches same-words questions)
        List<Question> lexicalHits = elasticsearch.search(text, /*limit*/ 20);

        // 2) SEMANTIC: turn the question into a "meaning vector", then find
        //    the closest existing question-vectors (catches reworded duplicates)
        float[] embedding = embeddingModel.embed(text);          // e.g. 768 numbers
        List<Match> semanticHits = vectorDb.annSearch(embedding, /*topK*/ 10);

        // 3) If the closest match is similar ENOUGH, treat it as a duplicate candidate
        for (Match m : semanticHits) {
            if (m.similarity() >= SIMILARITY_THRESHOLD) {        // e.g. 0.90
                return DedupResult.suggestExisting(m.question()); // "Did you mean this?"
            }
        }
        return DedupResult.looksNew();   // no strong match → let them create it
    }
}
```

#### Q: What actually happens when two questions ARE duplicates?

We don't delete either one. We pick one to be the **canonical** (the "real" one everyone lands on) and point the other at it with a `canonical_id`. From then on, visiting the duplicate **redirects** to the canonical, and all answers show up in one place instead of being split across two pages.

```java
// A moderator or an automated job merges a duplicate into the canonical question
void mergeDuplicate(long dupId, long canonicalId) {
    Question dup = questions.find(dupId);
    dup.canonicalId = canonicalId;      // "I'm really the same as #canonicalId"
    questions.save(dup);

    // answers already live under their question_id; the read path follows
    // canonical_id so they surface under the canonical question.
    // requests to /questions/{dupId} now 301-redirect to the canonical.
}
```

#### Q: Why not block the user from posting the moment we find a match?

Because our matcher is a *guess*, not a fact. If we hard-blocked, a genuinely new question that merely *looks* similar would be impossible to ask, which infuriates users. So the friendly flow is **suggest, don't forbid**: "These existing questions look similar — want to read them instead?" The user decides. Merging duplicates that slip through is handled later by moderators/automation, where a human can confirm.

---

## 6. Answer Ranking

Within a question, order answers by **quality**, not recency — the signature feature.

```
answer_rank_score = f( upvotes/downvotes (Wilson lower bound),
                       author_credibility / topic_expertise,
                       answer quality signals (length, formatting, sources),
                       freshness, engagement (views, shares) )
```

- **Author expertise/credibility** is a strong, distinctive signal (an expert's answer outranks a random one with similar votes).
- **Wilson score** lower bound for vote fairness with few votes (like Reddit "best").
- **Precompute per-question ranked answer lists**, cache; recompute periodically as votes/edits arrive (not per request).
- Often an ML model; treat as a black box — emphasize the **signals + precompute-and-cache** approach.

### Why the *best* answer, not the *newest*

The goal of answer ranking is that **quality wins over recency**: a careful, well-sourced answer from a credible author should sit above an earlier, low-quality one, even if both have similar vote counts. This is the biggest difference from a plain comment section (which is usually newest-first or a simple vote count).

We compute a `rank_score` per answer from several **signals**:

| Signal | Plain meaning | Why it matters |
| --- | --- | --- |
| **Votes (Wilson score)** | Up/down votes, but *fair* when there are few votes | 10/10 upvotes shouldn't instantly beat 900/1000 |
| **Author credibility/expertise** | Is the author knowledgeable *in this topic*? | An expert's answer is probably more trustworthy |
| **Quality signals** | Length, formatting, sources/links | A thorough, cited answer beats one line |
| **Freshness** | How recent | Break ties, surface up-to-date info |
| **Engagement** | Views, shares | People found it useful |

#### Q: What's a "Wilson score" and why not just `upvotes − downvotes`?

Because raw subtraction is unfair to new answers. Compare two answers:

- Answer A: **5 up, 0 down** → looks perfect (100%), but it's only 5 votes; could be luck.
- Answer B: **900 up, 100 down** → 90%, but proven across 1,000 people.

Naive "% positive" ranks A above B, which is wrong — we're not *confident* in A yet. The **Wilson score lower bound** asks: *"Given the votes so far, what's the pessimistic-but-reasonable estimate of this answer's true quality?"* Few votes → less confidence → the score is pulled down. As votes pile up, the estimate tightens toward the real ratio. It's the same math Reddit uses for its "best" comment sort.

```java
// Wilson lower bound: a "confidence-adjusted" positive rating.
// Few votes → score stays cautious; many votes → score approaches the true ratio.
double wilsonLowerBound(int up, int down) {
    int n = up + down;
    if (n == 0) return 0.0;
    double z = 1.96;                     // ~95% confidence
    double phat = (double) up / n;       // observed fraction positive
    return (phat + z*z/(2*n) - z * Math.sqrt((phat*(1-phat) + z*z/(4*n)) / n))
           / (1 + z*z/n);
}
```

Combining the signals into one score (the real thing is often an ML model; this is the illustrative version):

```java
double answerRankScore(Answer a, Author author, String topic) {
    double voteScore    = wilsonLowerBound(a.upCount, a.downCount);        // fair votes
    double expertise    = author.topicExpertise(topic);                    // 0..1, distinctive signal
    double qualityBonus = qualitySignals(a);   // length, formatting, has sources → 0..1
    double freshness    = timeDecay(a.createdAt);                          // newer = slightly higher

    // weighted blend — weights are tuned/learned; expertise is heavily weighted on Quora
    return 0.45 * voteScore
         + 0.30 * expertise
         + 0.15 * qualityBonus
         + 0.10 * freshness;
}
```

#### Q: Do we recompute this ranking every time someone opens the question?

**No — that's the key scaling move.** A popular question is read millions of times but its votes change slowly. Recomputing the ranking on every read would be enormous wasted work. Instead a **background job recomputes the ranked list periodically** (or when enough votes/edits accumulate) and stores the finished, ordered list in a cache. Reads just fetch the cached order.

```java
// Runs periodically (or triggered after N new votes) — NOT on every page view
void recomputeRanking(long questionId) {
    List<Answer> answers = answerRepo.findByQuestion(questionId);
    for (Answer a : answers) {
        a.rankScore = answerRankScore(a, authorOf(a), topicOf(questionId));
    }
    answers.sort(Comparator.comparingDouble((Answer a) -> a.rankScore).reversed());

    // cache the finished, ordered id list → reads become a simple cache lookup
    redis.set("answers:q:" + questionId + ":ranked",
              answers.stream().map(a -> a.answerId).toList());
}
```

The cost: the ranking can be a little **stale** (a brand-new vote might not reorder answers for a minute or two). On a Q&A site that's completely acceptable — the eventual-consistency trade-off from §2 again.

---

## 7. Feed Generation

Personalized home feed from **followed topics + followed users + engagement** — same fan-out family as Reddit/Twitter, more ML-driven.

| Approach | Note |
| --- | --- |
| **Pull + rank** | Fetch recent/high-quality Q&A from followed topics/users, merge + rank at read |
| **Push (fan-out on write)** | Push new answers to followers' feeds (for followed users) |
| **Hybrid + ML ranking** ✅ | Candidate generation (follows/topics/recs) → ML ranking → cache |

- **Candidate generation** = followed topics' top Q&A + followed users' new answers + recommendations → **ML rank** → cache the id list → hydrate.
- See [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) for push/pull/hybrid trade-offs.

### How your home feed gets built

Your home feed is assembled from the topics you follow (Python, Cooking), the people you follow (their new answers), and a few recommendations the system thinks you'll like — then sorted best-first. Building it involves two questions: (1) *what could go in it?* (candidate generation) and (2) *in what order?* (ranking).

The classic dilemma is **push vs pull** — do we build feeds ahead of time, or on demand?

| Approach | What it does | Downside |
| --- | --- | --- |
| **Pull (fan-out on read)** | Build the feed when the user opens the app | Slow at read time; recomputed every visit |
| **Push (fan-out on write)** | When someone posts, push it into all followers' prebuilt feeds | A user with millions of followers = millions of writes (the "celebrity" problem) |
| **Hybrid** ✅ | Push for normal users; pull for celebrities/topics; ML-rank the merge | More moving parts |

#### Q: Why can't we just always push (fan-out on write)?

Because of **celebrities and huge topics.** If a person with 5 million followers posts an answer, pure push means writing that answer into 5 million feeds *immediately* — a write storm. So the hybrid rule: **push updates from normal accounts** (cheap, few followers), but for a celebrity or a giant topic, **don't push — pull their content in at read time** and merge it. Best of both: most feeds are pre-built and instant, and we avoid the write explosion for the few accounts that would cause it.

```java
class FeedService {

    // 1) CANDIDATE GENERATION — gather everything that *could* appear
    List<Long> buildHomeFeed(long userId) {
        Set<Long> candidates = new LinkedHashSet<>();

        // from followed TOPICS: their recent top-ranked questions (pulled)
        for (long topicId : graph.followedTopics(userId))
            candidates.addAll(topRankedQuestions(topicId, /*limit*/ 50));

        // from followed USERS: their new answers — normal users' were PUSHED here already;
        // celebrities are PULLED now to avoid fan-out storms
        candidates.addAll(prebuiltPushedItems(userId));                  // push path
        for (long celeb : graph.followedCelebrities(userId))
            candidates.addAll(recentAnswers(celeb, /*limit*/ 20));       // pull path

        // recommendations (topics/people you might like)
        candidates.addAll(recommender.suggestFor(userId));

        // 2) RANK the merged candidates with an ML model (relevance to THIS user)
        List<Long> ranked = mlRanker.rank(userId, new ArrayList<>(candidates));

        // 3) CACHE the finished id list; reads just fetch + hydrate
        redis.set("feed:home:" + userId, ranked);
        return ranked;
    }
}
```

#### Q: What does "hydrate the id list" mean?

The cached feed is just a list of **IDs** — `[q_42, a_17, q_88, ...]` — tiny and cheap to store. When you actually open the app, we **hydrate**: look up each ID's full content (title, body, author, current vote count) to render the page. Storing IDs (not full posts) keeps caches small and means the *content* stays fresh even if the *ordering* was computed a while ago.

---

## 8. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, credibility JSONB, created_at TIMESTAMP );
CREATE TABLE topics ( topic_id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, follower_count BIGINT DEFAULT 0 );

CREATE TABLE questions (
    question_id BIGINT PRIMARY KEY, author_id BIGINT, title TEXT, body TEXT,
    canonical_id BIGINT,                 -- if merged into another question (dedup)
    answer_count INT DEFAULT 0, follower_count INT DEFAULT 0, view_count BIGINT DEFAULT 0,
    created_at TIMESTAMP
);
CREATE TABLE question_topics ( question_id BIGINT, topic_id BIGINT, PRIMARY KEY(question_id, topic_id) );

CREATE TABLE answers (
    answer_id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, author_id BIGINT,
    body TEXT, score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    rank_score DOUBLE PRECISION, created_at TIMESTAMP, updated_at TIMESTAMP
);
CREATE INDEX idx_answers_q_rank ON answers(question_id, rank_score DESC);   -- ranked answers

CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY, answer_id BIGINT, author_id BIGINT, body TEXT, created_at TIMESTAMP );
CREATE TABLE votes (
    user_id BIGINT, answer_id BIGINT, value SMALLINT, created_at TIMESTAMP,
    PRIMARY KEY (user_id, answer_id)     -- one vote per user per answer
);

CREATE TABLE follows_topic ( user_id BIGINT, topic_id BIGINT, PRIMARY KEY(user_id, topic_id) );
CREATE TABLE follows_user  ( follower_id BIGINT, followee_id BIGINT, PRIMARY KEY(follower_id, followee_id) );
CREATE TABLE question_follows ( user_id BIGINT, question_id BIGINT, PRIMARY KEY(user_id, question_id) );
CREATE TABLE ask_to_answer ( question_id BIGINT, asked_user_id BIGINT, at TIMESTAMP, PRIMARY KEY(question_id, asked_user_id) );

-- Feeds/rankings precomputed → Redis: feed:home:{userId}, answers:q:{id}:ranked
-- Search: Elasticsearch (lexical) + vector DB (embeddings) for semantic dedup
```

> **Tables to consider:** users, topics, questions, question_topics, answers, comments, votes, follows_topic, follows_user, question_follows, ask_to_answer, precomputed feeds/rankings, moderation, search index (ES) + vector store.

### Reading the schema

Every table maps to a real thing you can point at in the UI. Grouping them:

| Group | Tables | What they represent |
| --- | --- | --- |
| **The content** | `questions`, `answers`, `comments` | The Q&A itself — a question has many answers, an answer has many comments |
| **The organization** | `topics`, `question_topics` | Tags that group questions (a question can have several topics) |
| **The people & interest graph** | `users`, `follows_topic`, `follows_user`, `question_follows` | Who exists and what they follow |
| **The interactions** | `votes`, `ask_to_answer` | Upvotes, and "please answer this" invites |

#### Q: Why the weird two-column primary keys like `PRIMARY KEY (user_id, answer_id)` on votes?

That's a **composite key**, and here it enforces a business rule: **one vote per user per answer.** The pair `(user, answer)` must be unique, so the same user physically *cannot* have two rows for the same answer. If they change their mind, we update the existing row's `value` instead of inserting a new one (an **UPSERT**). Same idea on `question_topics` — one row per (question, topic) pair, no accidental duplicate tags.

```sql
-- vote or change a vote: insert if new, otherwise overwrite the value.
-- The composite PK makes "one vote per user per answer" impossible to violate.
INSERT INTO votes (user_id, answer_id, value, created_at)
VALUES (:user, :answer, :value, now())
ON CONFLICT (user_id, answer_id)          -- the row already exists
DO UPDATE SET value = EXCLUDED.value;      -- just change up→down or down→up
```

#### Q: Why does `answers` store `score`, `up_count`, `down_count` AND `rank_score` — isn't that redundant?

They serve different purposes, and storing them (denormalizing) is deliberate so reads don't recompute:

- `up_count` / `down_count` — the raw tallies, updated by the async vote aggregation.
- `score` — a simple derived number (e.g. `up − down`) for quick display.
- `rank_score` — the *full* quality score from §6 (votes + expertise + signals). This is what the `idx_answers_q_rank` index sorts by, so "give me this question's answers, best first" is a single fast indexed read.

```sql
-- because rank_score is precomputed and indexed, serving ranked answers is trivial:
SELECT * FROM answers
WHERE  question_id = :qid
ORDER  BY rank_score DESC          -- uses idx_answers_q_rank
LIMIT  20;
```

#### Q: What's the `canonical_id` column doing on `questions` again?

It's the dedup pointer from §5. Normally it's null (the question stands alone). When a question is discovered to be a duplicate, `canonical_id` is set to the "real" question's id, and the app follows that pointer so all answers show up under one canonical question. A self-reference within the same table.

---

## 9. API Design

```
POST /v1/questions          { title, body, topicIds }   # dedup-check first → may suggest existing
GET  /v1/questions/{id}      GET /v1/questions/{id}/answers?sort=ranked&cursor=
POST /v1/questions/{id}/answers { body }
POST /v1/answers/{id}/vote  { value }
POST /v1/answers/{id}/comments { body }
GET  /v1/home?cursor=        # personalized feed
POST /v1/topics/{id}/follow  ·  POST /v1/users/{id}/follow  ·  POST /v1/questions/{id}/follow
POST /v1/questions/{id}/ask-to-answer { userId }
GET  /v1/search?q=           # lexical + semantic
```

### How the buttons map to API calls

Each endpoint is just "what happens when you tap something." Reading them as user actions:

| You tap... | API call | What happens behind it |
| --- | --- | --- |
| "Add Question" | `POST /v1/questions` | Runs dedup first — may reply "did you mean this?" before creating |
| Open a question | `GET /v1/questions/{id}/answers?sort=ranked` | Serves the *precomputed ranked* answer list (§6) |
| "Add Answer" | `POST /v1/questions/{id}/answers` | Saves + emits `ANSWER_CREATED` for ranking/feed/index |
| Upvote | `POST /v1/answers/{id}/vote` | Records the vote; counting is async (§11) |
| Home tab | `GET /v1/home?cursor=` | Fetches the cached, ranked feed (§7) |
| "Follow" | `POST /v1/topics/{id}/follow` etc. | Updates the follow graph → future feeds |

#### Q: What is `cursor=` in `GET /v1/home?cursor=` — why not `page=2`?

It's **cursor-based pagination**, which is better than page numbers for feeds. A `cursor` is a bookmark ("give me items *after this point*"). Page numbers break on live data: if you're on page 2 and 5 new items get inserted at the top, page-number paging shows you duplicates or skips items because everything shifted. A cursor points at a stable position, so "next 20" always continues cleanly from where you were — essential for infinite-scroll feeds that change constantly.

```
GET /v1/home                       → first 20 items + nextCursor="eyJvZmZzZXQiOiIyMCJ9"
GET /v1/home?cursor=eyJ...jJ9      → the NEXT 20, continuing exactly where you stopped
```

#### Q: Why does `POST /v1/questions` say "dedup-check first"?

Because the create endpoint doesn't blindly insert. It first runs the lexical+semantic check from §5. If it finds a strong match, it can return suggestions ("these look similar") so the user can reuse an existing question instead of creating a near-duplicate. Only if the user proceeds (or nothing similar exists) does it actually create the row and emit `QUESTION_CREATED`.

---

## 10. Sequences

### Ask with dedup

```
User → QuestionSvc:
  embed(title+body) → ANN search (vector DB) + lexical (ES)
  if similar >= threshold → return suggestions (user may reuse) 
  else → create question → emit QUESTION_CREATED → index (ES + vector)
(later) moderator/system merges a dup → set canonical_id → redirect + surface answers under canonical
```

### Answer + ranking

```
User → AnswerSvc: create answer → emit ANSWER_CREATED
Vote → VoteSvc: UPSERT vote (dedupe) → VOTE_CAST → Kafka
Ranking job (periodic): recompute rank_score per question (votes + expertise + signals) → cache answers:q:{id}:ranked
Read question → serve cached ranked answers → hydrate
```

### Walking through the two main flows

**Flow 1 — Asking a question (with dedup):** The system tries to *stop* you from creating a duplicate before it happens.

```
You type a question
   │
   ▼
QuestionSvc embeds it + searches (semantic vector DB + lexical ES)
   │
   ├── found something similar enough? ──► show "Did you mean these?" ──► you might reuse it (no new question)
   │
   └── nothing similar ──► create the question ──► publish QUESTION_CREATED
                                                        │
                                    (subscribers) index it in ES + vector DB
   (later) a moderator/job spots a dup that slipped through ──► set canonical_id ──► redirect + merge answers
```

**Flow 2 — Answering + ranking:** notice the write path and the ranking are *decoupled* — posting is instant; ranking catches up.

```
You post an answer ──► AnswerSvc saves it ──► publishes ANSWER_CREATED ──► (you're done, fast)
Someone votes    ──► VoteSvc UPSERTs the vote ──► publishes VOTE_CAST ──► Kafka
                                                          │
Ranking job (runs periodically, NOT per read):  recompute rank_score for the question
                                                → cache the ordered list "answers:q:{id}:ranked"
Someone reads the question ──► serve the CACHED ranked list ──► hydrate ids into full answers
```

#### Q: Why is there a gap between voting and the ranking updating?

Because ranking is done by a **periodic background job**, not synchronously when you vote. Your vote is saved instantly (so *your* view updates), but the *reordering* of everyone's answers waits for the next ranking pass. This is intentional: re-ranking on every single vote across billions of votes would be impossibly expensive, and a Q&A order that's a minute stale bothers nobody. Write fast, rank eventually.

---

## 11. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Duplicate question | Semantic + lexical dedup on ask; merge to `canonical_id`; redirect |
| Merged question | Answers surface under canonical; old id redirects |
| Question/answer edit | Re-index (ES + vector); re-rank |
| Vote exactness | Async aggregation → approximate cached counts |
| One vote per user/answer | `UNIQUE(user, answer)`; re-vote updates value |
| Ranking staleness | Periodic recompute; slightly stale ranking acceptable (eventual) |
| Deleted answer | Tombstone; skip on hydrate; recompute question rank |

### Vote counting at scale, and why "approximate" is okay

#### Q: An answer gets 50,000 upvotes in a minute — why not just `UPDATE ... SET up_count = up_count + 1` each time?

Same trap as any high-traffic counter: 50,000 people hammering the **same row** means they all queue behind a single database lock ("hot row"), and the write throughput is enormous. So we **don't count votes synchronously.** The vote is recorded fast, and the *count* is aggregated in the background — the exact number lags by a few seconds, which is fine (you're not billing anyone).

```java
class VoteService {

    void castVote(long userId, long answerId, int value) {
        // 1) Record the individual vote (UPSERT — one vote per user per answer).
        //    This is a small, unique-key write, not a fight over a shared counter.
        voteRepo.upsert(userId, answerId, value);

        // 2) Announce it — don't touch the running total here.
        kafka.publish("VOTE_CAST", new VoteEvent(answerId, value));
    }
}

// A SEPARATE aggregator batches many VOTE_CAST events and updates counts once,
// instead of every vote fighting for the same row.
@KafkaListener(topics = "VOTE_CAST")
void aggregate(List<VoteEvent> batch) {
    Map<Long, Long> delta = new HashMap<>();
    for (VoteEvent v : batch)
        delta.merge(v.answerId(), (long) v.value(), Long::sum);   // net change per answer

    // one combined update per answer per batch → far fewer, cheaper writes
    delta.forEach(answerRepo::addToScore);
}
```

The displayed count is therefore **approximate/eventually-consistent** — it might show 49,998 for a moment before settling on 50,000. Nobody cares on a Q&A site, and it lets votes scale enormously. (This is the same "aggregate a firehose of events in batches" idea as the ad-click counter, just smaller stakes.)

#### Q: Read-heavy caching — what if the cache is stale or a question gets a sudden traffic spike?

Reads are served from caches (ranked answer lists, feeds) so the database is barely touched. Two beginner worries:

- **Stale cache:** acceptable here. The ranking/feed job refreshes caches periodically; a slightly old order is fine (eventual consistency again). Content itself is hydrated fresh from IDs, so vote counts and text stay current even when *ordering* is a bit old.
- **Sudden hot question (goes viral):** a single popular question can get a spike of reads. Because its ranked answer list is a single cached entry, one cache lookup serves everyone — the DB isn't hit per reader. If even the cache node gets hot, you replicate that entry across cache nodes. The precompute-and-cache design turns a viral question from a database problem into a cheap cache read.

#### Q: How do deletes and edits stay consistent across search, ranking, and cache?

- **Deleted answer:** we don't rip it out everywhere instantly; we mark a **tombstone** (a "this is gone" flag). Readers skip tombstoned answers when hydrating, and the next ranking pass drops it and recomputes the question's order. Cleaner than trying to synchronously purge it from every cache and index.
- **Edited question/answer:** the text changed, so its meaning may have too — we **re-index** it (Elasticsearch + a fresh embedding in the vector DB, often via CDC) and **re-rank**. This keeps search results and dedup accurate after edits.

---

## 12. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Answer ranking, feed generation, dedup similarity model | Swap algorithms/models |
| **Observer / Pub-Sub** | Vote/answer events → counters, feed, index, notifications | Decouple aggregation |
| **CQRS + Materialized View** | Precomputed feeds & ranked answers | Fast reads |
| **Producer-Consumer** | Async vote/ranking aggregation (Kafka) | Absorb write volume |
| **Composite** | Comment threads | Tree ops |
| **Chain of Responsibility** | Ask flow: dedup → validate → topic-tag → publish | Composable steps |
| **Repository** | Data access | Testable |
| **Facade** | Feed/search service | Simple API |
| **Decorator** | Answer display (credentials, badges, sources) | Compose rendering |

### The patterns in everyday words

Patterns are just named solutions to recurring problems. The ones that matter most here:

- **Strategy** — "swap the algorithm without changing the caller." Ranking answers, ranking feeds, and measuring question similarity are all *pluggable components*: you can replace the ML model behind any of them and the surrounding code doesn't change.
- **Observer / Pub-Sub** — "announce an event; whoever cares reacts." A vote or new answer publishes an event to Kafka; the counter, feed, indexer, and notifier each react independently. The writer doesn't wait for or even know about them.
- **CQRS + Materialized View** — "prepare read-optimized copies ahead of time." Ranked answer lists and feeds are precomputed and cached so reads are instant. A materialized view = a saved, ready-to-serve answer to an expensive query.
- **Producer-Consumer** — "buffer bursty work and process it steadily." Votes pour in (producers); a background aggregator drains the queue in batches (consumer), so spikes don't overwhelm the database.
- **Composite** — "treat a tree uniformly." Comment threads are trees (replies to replies); the Composite pattern lets code walk them the same way at any depth.
- **Chain of Responsibility** — "a pipeline of steps." Asking a question flows through dedup → validate → topic-tag → publish; each step is a link you can add/remove.

#### Q: Do I need to memorize all nine for an interview?

No. Interviewers care that you can *justify structure*, not recite a catalog. Lead with the three that shape this design — **CQRS/Materialized View** (fast reads), **Observer/Pub-Sub** (decoupled event processing), and **Strategy** (swappable ranking/dedup models) — and mention the rest only if they come up naturally.

---

## 13. Scaling & Failure

- Read-heavy → **cache precomputed feeds + ranked answer lists**; DB rarely hit on reads.
- **Votes** async-aggregated (Kafka); approximate cached counts.
- **Search/dedup** on **Elasticsearch (lexical) + vector DB (semantic)**, rebuilt via CDC.
- **Ranking jobs** recompute per question/feed periodically.
- Partition questions/answers; archive cold content; eventual consistency acceptable.

### How this survives scale and things breaking

Every bullet above is one of two moves: **serve reads from cheap copies**, and **do heavy work in the background**.

- **Read-heavy → cache everything readers touch.** Feeds and ranked answers live in Redis, so the vast majority of requests never reach the main database. The DB mostly handles the rare writes.
- **Votes async.** Recording a vote is a tiny write; turning votes into counts happens in batches (§11), so a viral answer can't melt the database with a counter war.
- **Search on its own stack.** Elasticsearch (words) + vector DB (meaning) run separately from the main DB and are rebuilt from the source of truth via **CDC** (Change Data Capture — the DB streams out its changes so the search indexes stay in sync automatically).
- **Partition + archive.** Content grows forever, so we split it across many machines (partition) and move old, rarely-read Q&A to cheaper storage (archive).

#### Q: What actually happens when a piece breaks?

Because the system is **eventually consistent** and event-driven, most failures degrade gracefully rather than taking the site down:

| If this breaks... | What users see | Why it's survivable |
| --- | --- | --- |
| Ranking job is down | Answer order is a bit stale | Cached list still serves; catches up when the job restarts |
| Vote aggregator lags | Counts update slowly | Individual votes are still saved; totals reconcile later |
| Search cluster is down | Search/dedup degraded | Core read/write of Q&A still works; index rebuilds from CDC |
| A cache node dies | Brief slowdown for those keys | Rebuild the cache entry from the DB; other nodes unaffected |

The theme: nothing here needs bank-grade instant correctness, so we lean on caches, queues, and background recomputation, and let the rare failure heal itself.

---

## 14. Interview Cheat Sheet

> **"How is Quora different from Reddit?"**
> "Organized around a **question with many answers** ranked by **quality** (author expertise is a key signal, unlike Reddit's pure vote-based hot), plus **topic/people follows** and **question dedup/merge**. The feed/voting/read-heavy machinery is similar."

> **"How do you rank answers?"**
> "A quality score from votes (Wilson lower bound) + **author credibility/topic expertise** + quality signals + freshness, precomputed per question and cached; recomputed as votes/edits arrive."

> **"How do you avoid duplicate questions?"**
> "On ask, run **lexical search (Elasticsearch)** + **semantic search (embed → vector DB ANN)** over existing questions; above a similarity threshold, suggest the existing one so the user reuses it; duplicates get merged into a **canonical question** (via `canonical_id` + redirect) so answers stay together."

> **"Feed generation?"**
> "Candidate generation from followed topics/users (+ recs) → ML ranking → cache (hybrid fan-out): push for followed users, pull for topics."

---

## 15. Final Takeaways

- **Question → many answers**, ranked by **quality** with **author expertise** as a key signal — precomputed + cached.
- **Question dedup** = lexical (ES) + **semantic (embeddings + vector DB)**; merge into a **canonical** question.
- **Feeds** = follows/topics candidate generation + ML ranking + cache (hybrid fan-out).
- **Votes** async-aggregated; approximate counts; read-heavy → CQRS + materialized views.
- Patterns: Strategy (rank/dedup/feed), Observer/Producer-Consumer (votes), CQRS/Materialized View, Composite (comments), Chain (ask flow).

### Related notes

- [Reddit](reddit-system-design.md) · [Twitter / News Feed](twitter-news-feed-system-design.md) — sibling feed/voting platforms
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) (vector DB / Elasticsearch) · [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) · [Apache Kafka](../concepts/kafka.md)

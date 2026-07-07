# Quora — System Design (Q&A Platform)

> **Core challenge:** users ask **questions**, others post **answers**, and the best answers are **ranked by quality** and surfaced in **topic + personalized feeds**. Read-heavy like Reddit, but organized around **question → many answers**, with two distinctive twists: **question dedup/semantic search** and **answer-quality ranking (author expertise matters)**.

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

---

## 2. Requirements

**Functional**
- Ask questions (tagged with topics); post/edit answers; comment; upvote/downvote answers.
- Follow **topics** and **users**; personalized home feed.
- **Answer ranking** per question (best answers first).
- **Search** questions; **dedup** near-duplicate questions (merge to canonical); "ask to answer".

**Non-functional**
- Read-heavy (reads ≫ writes); **eventual consistency** ok; scale to billions of Q&A; **fast, semantic search**.

---

## 3. Capacity Estimation

```
Users ~ 300M · questions ~ 100M's · answers ~ several per question · views ~ billions/day
Reads ≫ writes (browse/search) → cache + precomputed rankings
Search/dedup on every ask + every search → ES + vector store must scale
Storage: questions/answers grow forever → partition + archive; search index separate
```

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

---

## 13. Scaling & Failure

- Read-heavy → **cache precomputed feeds + ranked answer lists**; DB rarely hit on reads.
- **Votes** async-aggregated (Kafka); approximate cached counts.
- **Search/dedup** on **Elasticsearch (lexical) + vector DB (semantic)**, rebuilt via CDC.
- **Ranking jobs** recompute per question/feed periodically.
- Partition questions/answers; archive cold content; eventual consistency acceptable.

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

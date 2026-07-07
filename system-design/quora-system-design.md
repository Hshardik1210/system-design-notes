# Quora — System Design (Q&A Platform)

> **Core challenge:** users ask **questions**, others post **answers**, and the best answers are **ranked** and surfaced in **topic + personalized feeds**. Read-heavy like Reddit, but organized around **question → many answers**, **topic follows**, **answer quality ranking**, and **search/dedup of questions**.

---

## Contents

- [1. Mental Model & vs Reddit](#1-mental-model--vs-reddit)
- [2. Requirements](#2-requirements)
- [3. Question Dedup & Search](#3-question-dedup--search)
- [4. Answer Ranking](#4-answer-ranking)
- [5. Feed Generation](#5-feed-generation)
- [6. Data Model (all tables)](#6-data-model-all-tables)
- [7. API Design](#7-api-design)
- [8. Design Patterns (that can be used)](#8-design-patterns-that-can-be-used)
- [9. Scaling & Failure](#9-scaling--failure)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Mental Model & vs Reddit

```
Ask question → tag topics → many users answer → answers ranked by quality
   → feeds surface Q&A by followed topics/people + personalization → upvote/comment/follow
```

| | **Reddit** | **Quora** |
| --- | --- | --- |
| Unit | Post in a subreddit | **Question** with **many answers** |
| Organization | Communities (subreddits) | **Topics** + people you follow |
| Ranking | Post hot/top | **Answer quality** ranking within a question |
| Dedup | Not central | **Question dedup** is important (merge duplicates) |

The feed/voting/read-heavy machinery is similar; the twists are **answer ranking** and **question dedup/search**.

---

## 2. Requirements

**Functional**
- Ask questions (tagged with topics); post/edit answers; comment; upvote/downvote answers.
- Follow **topics** and **users**; personalized home feed.
- **Answer ranking** per question (best answers first).
- **Search** questions; **dedup** near-duplicate questions; "ask to answer".

**Non-functional**
- Read-heavy; eventual consistency ok; scale to billions of Q&A; fast search.

---

## 3. Question Dedup & Search

Avoid 100 copies of "How do I learn Python?".

```
On ask:
  1. full-text + semantic search existing questions (Elasticsearch + embeddings)
  2. show similar questions → user may pick an existing one instead
  3. if truly new → create; else redirect/merge
```

- **Elasticsearch** for text search; **embedding/vector similarity** for semantic near-duplicates.
- Merged questions redirect to a canonical question (keep answers together).

---

## 4. Answer Ranking

Within a question, order answers by **quality**, not just recency.

```
answer_score = f(upvotes, downvotes, author_credibility/topic_expertise,
                 answer_length/quality signals, freshness, engagement)
```

- Precompute per-question ranked answer lists; cache; recompute periodically as votes arrive.
- Author **topic expertise/credibility** is a strong signal (unlike Reddit).
- Wilson-score-style confidence for fairness with few votes.

---

## 5. Feed Generation

Personalized home feed from **followed topics + followed users + engagement**.

| Approach | Note |
| --- | --- |
| **Pull + rank** | Fetch recent/high-quality Q&A from followed topics/users, merge + rank | 
| **Push (fan-out on write)** | Push new answers to followers' feeds (for followed users) |
| **Hybrid + ML ranking** | Candidate generation (follows/topics) → ML ranking → cache |

Same fan-out trade-offs as Reddit/Twitter; ranking is more ML-driven (relevance + quality + personalization).

---

## 6. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, credibility JSONB, created_at TIMESTAMP );
CREATE TABLE topics ( topic_id BIGINT PRIMARY KEY, name VARCHAR(100) UNIQUE, follower_count BIGINT DEFAULT 0 );

CREATE TABLE questions (
    question_id BIGINT PRIMARY KEY, author_id BIGINT, title TEXT, body TEXT,
    canonical_id BIGINT,                 -- if merged into another question
    answer_count INT DEFAULT 0, follower_count INT DEFAULT 0, view_count BIGINT DEFAULT 0,
    created_at TIMESTAMP
);
CREATE TABLE question_topics ( question_id BIGINT, topic_id BIGINT, PRIMARY KEY(question_id, topic_id) );

CREATE TABLE answers (
    answer_id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, author_id BIGINT,
    body TEXT, score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    rank_score DOUBLE PRECISION, created_at TIMESTAMP, updated_at TIMESTAMP
);
CREATE INDEX idx_answers_q_rank ON answers(question_id, rank_score DESC);

CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY, answer_id BIGINT, author_id BIGINT, body TEXT, created_at TIMESTAMP );

CREATE TABLE votes (
    user_id BIGINT, answer_id BIGINT, value SMALLINT, created_at TIMESTAMP,
    PRIMARY KEY (user_id, answer_id)     -- one vote per user per answer
);

CREATE TABLE follows_topic ( user_id BIGINT, topic_id BIGINT, PRIMARY KEY(user_id, topic_id) );
CREATE TABLE follows_user  ( follower_id BIGINT, followee_id BIGINT, PRIMARY KEY(follower_id, followee_id) );
CREATE TABLE question_follows ( user_id BIGINT, question_id BIGINT, PRIMARY KEY(user_id, question_id) );
CREATE TABLE ask_to_answer ( question_id BIGINT, asked_user_id BIGINT, at TIMESTAMP, PRIMARY KEY(question_id, asked_user_id) );

-- Feeds/rankings precomputed → Redis:  feed:home:{userId}, answers:q:{id}:ranked
```

> **Tables to consider:** users, topics, questions, question_topics, answers, comments, votes, follows_topic, follows_user, question_follows, ask_to_answer, precomputed feeds/rankings, moderation, search_index (ES).

---

## 7. API Design

```
POST /v1/questions          { title, body, topicIds }   # dedup-check first
GET  /v1/questions/{id}      GET /v1/questions/{id}/answers?sort=ranked
POST /v1/questions/{id}/answers { body }
POST /v1/answers/{id}/vote  { value }
GET  /v1/home?cursor=        # personalized feed
POST /v1/topics/{id}/follow  ·  POST /v1/users/{id}/follow
GET  /v1/search?q=           # question search + similar
```

---

## 8. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Answer ranking, feed generation, dedup similarity | Swap algorithms/models |
| **Observer / Pub-Sub** | Vote/answer events → counters, feed, notifications | Decouple aggregation |
| **CQRS + Materialized View** | Precomputed feeds & ranked answers | Fast reads |
| **Producer-Consumer** | Async vote/ranking aggregation (Kafka) | Absorb write volume |
| **Composite** | Comment threads | Tree ops |
| **Repository** | Data access | Testable |
| **Facade** | Feed/search service | Simple API |
| **Chain of Responsibility** | Ask flow: dedup → validate → topic-tag → publish | Composable steps |
| **Decorator** | Answer display (credentials, badges) | Compose rendering |

---

## 9. Scaling & Failure

- Read-heavy → **cache precomputed feeds + ranked answer lists**; DB rarely hit on reads.
- **Votes** async-aggregated (Kafka); approximate cached counts.
- **Search/dedup** on Elasticsearch (+ vector store for semantic similarity), rebuilt via CDC.
- **Ranking jobs** recompute per question/feed periodically.
- Partition questions/answers; archive cold content; eventual consistency acceptable.

---

## 10. Interview Cheat Sheet

> **"How is Quora different from Reddit?"**
> "Organized around a **question with many answers** ranked by **quality** (author expertise matters), plus **topic/people follows** and **question dedup**. The feed/voting/read-heavy machinery is similar."

> **"How do you rank answers?"**
> "A quality score from votes + author credibility/topic expertise + engagement + freshness, precomputed per question and cached; recomputed as votes arrive. Wilson-style confidence for fairness with few votes."

> **"How do you avoid duplicate questions?"**
> "On ask, run text + semantic (embedding) search over existing questions; surface similar ones so the user reuses an existing question; merge duplicates into a canonical question."

> **"Feed generation?"**
> "Candidate generation from followed topics/users → ML ranking → cache. Hybrid fan-out (push for followed users, pull for topics)."

---

## 11. Final Takeaways

- **Question → many answers**, ranked by **quality** (author expertise is a key signal) — precomputed + cached.
- **Question dedup** via text + semantic search (ES + embeddings); merge into canonical.
- **Feeds** = follows/topics candidate generation + ML ranking + cache (hybrid fan-out).
- **Votes** async-aggregated; approximate counts; read-heavy → CQRS + materialized views.
- Patterns: Strategy (rank/dedup/feed), Observer/Producer-Consumer (votes), CQRS/Materialized View, Composite (comments), Chain (ask flow).

### Related notes

- [Reddit — System Design](reddit-system-design.md) — sibling feed/voting platform
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)

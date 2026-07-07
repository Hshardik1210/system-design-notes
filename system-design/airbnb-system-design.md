# Airbnb — System Design (Lodging Marketplace)

> **Core challenge:** a **two-sided marketplace** connecting **hosts** (who list properties) with **guests** (who book them). Combines **geo + faceted search**, **date-range availability booking** (no double-booking), **payments with host payouts (escrow)**, **two-way reviews**, and **messaging** — plus **request-to-book vs instant-book**.

---

## Contents

- [1. Mental Model & vs Hotel/Booking.com](#1-mental-model--vs-hotelbookingcom)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Search & Discovery (with availability)](#5-search--discovery-with-availability)
- [6. Availability & Booking (no double-booking)](#6-availability--booking-no-double-booking)
- [7. Booking State Machine](#7-booking-state-machine)
- [8. Payments & Host Payouts (escrow)](#8-payments--host-payouts-escrow)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API Design](#10-api-design)
- [11. Sequences](#11-sequences)
- [12. Consistency & Edge Cases](#12-consistency--edge-cases)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model & vs Hotel/Booking.com

```
Host lists property (calendar, price) → Guest searches by location + dates + guests
   → books (instant or request) → pays (escrow) → stay → both review
```

vs a hotel chain: Airbnb has **many independent hosts**, each property usually **unique (count = 1)** rather than "10 identical Deluxe rooms", plus **host approval**, **host payouts**, and **two-way trust/reviews**. Availability correctness is the same family (date-range, no double-book) but per **listing calendar** instead of per room-type count.

---

## 2. Requirements

**Functional**
- Host: create/manage listings, set calendar availability + pricing, accept/decline requests.
- Guest: search (location, dates, guests, filters), view listing, book (instant/request), pay, review.
- Messaging host↔guest; cancellations/refunds per policy; wishlists.

**Non-functional**
- **No double-booking** (strong consistency on the booking write).
- Fast **geo + faceted search** (read-heavy).
- Trust & safety (reviews, verification); global (multi-currency, i18n).

---

## 3. Capacity Estimation

```
Listings ~ 7M · users ~ 100M's · searches ≫ bookings (browse-heavy, ~1000:1)
Search QPS (peak) ~ tens of thousands/sec → Elasticsearch + cache
Bookings ~ modest write rate but MUST be correct (money + no double-book)
Calendar rows: 7M listings × 365 days ≈ 2.5B rows/year → partition/prune past dates
Storage: listings + calendar + bookings; photos → blob/CDN (the bulk of bytes)
```

> Browse/search dominates → an ES read model + cache. Bookings are low-volume but **strongly consistent** (correctness over throughput).

---

## 4. Architecture

```
Client → API Gateway
  ├── Search Service      → Elasticsearch (geo + facets) + Redis cache  (read model)
  ├── Listing Service     → RDBMS (listings, calendar) + cache
  ├── Booking Service     → RDBMS (bookings, calendar writes) — strong consistency
  ├── Payment Service     → gateway + ledger + escrow + payouts
  ├── Messaging Service   → host↔guest chat
  └── Review Service
             │
          Kafka (BOOKING_CONFIRMED, LISTING_UPDATED → search index (CDC), notifications, payouts, analytics)
```

- **CQRS:** search = ES read model (rebuilt from listing/calendar changes via CDC); booking = RDBMS write model.

---

## 5. Search & Discovery (with availability)

The dominant read path: **location + date range + guests + filters** (price, type, amenities, superhost, instant-book).

| Concern | Approach |
| --- | --- |
| **Geo** | Geohash/S2/QuadTree, or Elasticsearch `geo_bounding_box` for the **map viewport** |
| **Faceted filters** | Elasticsearch (amenities, price band, type, instant-book) |
| **Availability filter** | **Hard at scale** — can't join a 2.5B-row calendar per search |
| **Ranking** | Relevance + price + quality + personalization (ML black box) |
| **Freshness** | ES index rebuilt from listings/calendar via **CDC/Kafka** |

**Handling the availability filter (the tricky part):**
- Precompute/denormalize a compact availability representation into the search index — e.g., a **bitmap/bitset of the next ~N days** per listing (bit = available). A date-range query intersects bits → cheap in-index filter.
- Or filter **approximately** in search (recently-known availability) and **re-check exactly** at booking time (search is best-effort; booking is authoritative).
- Update the availability index on calendar changes (CDC), with short staleness tolerance.

> Search reads a **read model** (ES), never the transactional DB. Map-viewport searches use bounding-box geo queries + the availability bitset filter.

---

## 6. Availability & Booking (no double-booking)

Each listing has a **calendar**; a booking must reserve **every night** in `[checkIn, checkOut)`. Same correctness family as BookMyShow's seat lock, applied per night.

### Atomic all-or-nothing calendar claim

```
book(listing, checkIn, checkOut, guest):
  BEGIN TX
    UPDATE listing_calendar
       SET status='BOOKED', booking_id=?
     WHERE listing_id=? AND date >= checkIn AND date < checkOut
       AND status='AVAILABLE'
    if rows_affected != nights:          -- some night wasn't free
        ROLLBACK → NOT_AVAILABLE
    INSERT booking (status = PENDING_PAYMENT or REQUESTED)
  COMMIT
```

- **Atomic conditional update** (`WHERE status='AVAILABLE'`) → if *any* night is taken, `rows_affected < nights` → roll back the whole booking. No overlap possible.
- **Hold with TTL** during checkout (a `HELD` status + `hold_expiry`), released by a sweeper if payment/host-approval doesn't complete → seat-lock analogy.
- **Calendar-per-night rows** are simple; an **interval/range model** (store booked ranges + an exclusion constraint) is more compact but trickier — the per-night model is the clean interview answer.
- **Request-to-book** inserts `REQUESTED` and holds the calendar pending host approval (a saga with a human-in-the-loop timeout).

---

## 7. Booking State Machine

```
REQUESTED ─host accept→ PENDING_PAYMENT ─pay→ CONFIRMED ─check-in/stay→ COMPLETED
    │ host decline / timeout        │ fail/timeout          │ cancel
    ▼                               ▼                        ▼
 DECLINED (release calendar)   EXPIRED (release)   CANCELLED (refund per policy, release calendar)

(instant-book skips REQUESTED → straight to PENDING_PAYMENT)
```

- Every terminal-fail path **releases the held calendar nights** (compensation).

---

## 8. Payments & Host Payouts (escrow)

- **Charge the guest** on confirm (instant) or at host acceptance (request). **Escrow** the funds.
- **Release payout to the host** ~24h after check-in (minus platform fee) → protects the guest if the place is misrepresented.
- **Double-entry ledger:** guest charge → platform commission + host payable → payout (see Payment System note).
- **Refunds** per **cancellation policy** (flexible/moderate/strict = Strategy).
- **Idempotent** payments + **outbox** so a confirmed booking reliably triggers charge + eventual payout; multi-currency + FX.

---

## 9. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE,
                     is_host BOOLEAN DEFAULT FALSE, is_verified BOOLEAN DEFAULT FALSE );

CREATE TABLE listings (
    listing_id BIGINT PRIMARY KEY, host_id BIGINT NOT NULL,
    title TEXT, type VARCHAR(30), city VARCHAR(100),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, geohash VARCHAR(12),
    base_price INT, cleaning_fee INT, max_guests INT, amenities JSONB,
    instant_book BOOLEAN DEFAULT FALSE, rating NUMERIC(2,1) DEFAULT 0, rating_count BIGINT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE'
);
CREATE INDEX idx_listing_geohash ON listings(geohash) WHERE status='ACTIVE';
CREATE TABLE listing_photos ( photo_id BIGINT PRIMARY KEY, listing_id BIGINT, url TEXT, sort_order INT );

-- Availability calendar (per listing per night) — the key table
CREATE TABLE listing_calendar (
    listing_id BIGINT, date DATE,
    status VARCHAR(15) NOT NULL DEFAULT 'AVAILABLE',   -- AVAILABLE, BLOCKED, HELD, BOOKED
    price INT, hold_expiry TIMESTAMP, booking_id BIGINT,
    PRIMARY KEY (listing_id, date)
);

CREATE TABLE bookings (
    booking_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) UNIQUE,
    listing_id BIGINT NOT NULL, guest_id BIGINT NOT NULL,
    check_in DATE, check_out DATE, guests INT,
    subtotal INT, fees INT, total_amount INT,
    status VARCHAR(30) NOT NULL, created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_bookings_guest ON bookings(guest_id, created_at DESC);

CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, booking_id BIGINT, amount INT,
                        status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE payouts  ( payout_id BIGINT PRIMARY KEY, host_id BIGINT, booking_id BIGINT, amount INT, status VARCHAR(20), release_at TIMESTAMP );
CREATE TABLE cancellation_policies ( listing_id BIGINT PRIMARY KEY, type VARCHAR(20) );  -- FLEXIBLE/MODERATE/STRICT
CREATE TABLE reviews (
    review_id BIGINT PRIMARY KEY, booking_id BIGINT,
    author_id BIGINT, subject_id BIGINT, role VARCHAR(20),  -- GUEST_REVIEWS_HOST / HOST_REVIEWS_GUEST
    rating SMALLINT, comment TEXT, created_at TIMESTAMP
);
CREATE TABLE conversations ( conversation_id BIGINT PRIMARY KEY, listing_id BIGINT, guest_id BIGINT, host_id BIGINT );
CREATE TABLE messages ( message_id BIGINT PRIMARY KEY, conversation_id BIGINT, sender_id BIGINT, body TEXT, created_at TIMESTAMP );
CREATE TABLE wishlists ( user_id BIGINT, listing_id BIGINT, PRIMARY KEY(user_id, listing_id) );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> **Tables to consider:** users, listings, listing_photos, **listing_calendar** (the key one), bookings, payments, payouts, cancellation_policies, reviews, conversations, messages, wishlists, outbox, pricing_rules. Photos → blob/CDN; search → ES.

---

## 10. API Design

```
GET  /v1/listings?lat=&lng=&checkIn=&checkOut=&guests=&filters=   # search (map/faceted + availability)
GET  /v1/listings/{id}     GET /v1/listings/{id}/availability?from=&to=
POST /v1/bookings          (Idempotency-Key) { listingId, checkIn, checkOut, guests }
POST /v1/bookings/{id}/accept | /decline     # host (request-to-book)
POST /v1/bookings/{id}/pay | /cancel
POST /v1/listings          # host create      PUT /v1/listings/{id}/calendar
POST /v1/bookings/{id}/review
POST /v1/conversations/{id}/messages
```

---

## 11. Sequences

### Instant-book (happy path, saga)

```
Guest  BookingSvc  Calendar  Payment  Kafka  Host
  │ book │           │          │        │      │
  ├─────►│ TX: claim nights (AVAILABLE→BOOKED, all-or-nothing) │
  │      │◄─ ok (rows==nights) → booking=PENDING_PAYMENT       │
  │      ├─ charge (escrow) ───►│ ok    │      │
  │      ├─ booking=CONFIRMED; outbox → BOOKING_CONFIRMED ────►│─► notify host, index, schedule payout
  │◄─ confirmed ─────────────────────────────────────────────┤
```

### Request-to-book (human-in-the-loop timeout)

```
Guest → book → claim nights as HELD (hold_expiry = +24h) → booking=REQUESTED → notify host
Host accepts within 24h → charge → CONFIRMED (nights → BOOKED)
Host declines / timeout → booking=DECLINED/EXPIRED → RELEASE held nights (compensation)
```

---

## 12. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Double-booking | Atomic all-or-nothing calendar claim (`WHERE status='AVAILABLE'`, check `rows==nights`) |
| Two guests race same dates | One wins the conditional update; the other gets `rows<nights` → NOT_AVAILABLE |
| Abandoned checkout | `HELD` + `hold_expiry` → sweeper releases; saga compensation |
| Host no-response (request) | Timeout → auto-decline + release calendar |
| Payment fails | Release calendar (compensation); notify |
| Duplicate booking tap | `UNIQUE(idempotency_key)` |
| Guest cancels | State → CANCELLED, release nights, refund per policy (Strategy) |
| Search shows unavailable listing | Search is approximate; **exact re-check at booking** rejects it |
| Host edits calendar during a hold | Held nights are locked; edits apply to free nights only |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Saga / Orchestration** | Request→accept→pay→confirm→payout, with compensation (release/refund) | Multi-step distributed txn (incl. human step) |
| **State** | Booking lifecycle | Guard transitions |
| **Optimistic Locking / CAS** | Calendar conditional update | No double-booking |
| **Strategy** | Search ranking, pricing, cancellation policy | Swap rules/algorithms |
| **CQRS** | ES search read model vs RDBMS write model | Optimized reads |
| **Outbox** | Reliable events (confirmed → notify, index, payout) | No dual-write loss |
| **Ports & Adapters** | Payment, search index, maps, notifications | Swap providers |
| **Decorator / Chain** | Price = base + cleaning + service fee + taxes − discount | Stack pricing |
| **Observer / Pub-Sub** | Booking events → notifications, indexing, payouts | Decouple |
| **Repository** | Data access | Testable |

---

## 14. Scaling & Failure

- **Search** on Elasticsearch (geo bounding-box + facets + **availability bitset**) + cache; rebuild via CDC; approximate, re-checked at booking.
- **Booking** on RDBMS, strong consistency, shard by `listing_id` (or region); calendar past-date pruning.
- **Hold + TTL** for checkout; **saga compensation** releases on payment/host failure; **request timeout** auto-declines.
- **Idempotency** prevents double bookings; **outbox** drives confirmation/payout events.
- **Payouts** escrowed, released post-check-in; **double-entry ledger** for money.
- Photos/media → blob + CDN.

---

## 15. Interview Cheat Sheet

> **"How is Airbnb different from a hotel booking system?"**
> "Many independent hosts; each listing is usually unique (count 1) with its own **calendar**; plus **request-to-book** (host approval), **host payouts** (escrow), and two-way reviews. The no-double-book correctness is the same per-night idea."

> **"How do you prevent double-booking?"**
> "Per-night calendar rows; book with an **atomic conditional update** marking all nights AVAILABLE→BOOKED in one transaction — if `rows_affected != nights`, roll back the whole thing. Hold with a TTL during checkout; a saga releases nights on payment/host failure."

> **"How do you filter search by availability at scale?"**
> "You can't join a billions-row calendar per search. Denormalize a compact **availability bitset** (next N days) into the ES index for a cheap in-index date filter, treat it as approximate, and **re-check exactly at booking**."

> **"Instant-book vs request-to-book?"**
> "Instant → claim nights → charge → confirm. Request → hold nights (HELD + expiry), notify host, wait for accept within a timeout (**saga with a human step**), then charge; decline/timeout releases the hold."

> **"Payments/payouts?"**
> "Charge guest into **escrow**, pay out the host minus fee ~24h after check-in; refunds per cancellation policy; idempotent + outbox + double-entry ledger."

---

## 16. Final Takeaways

- **Two-sided marketplace**: hosts + guests, unique listings with per-listing **calendars**.
- **No double-booking** via per-night **atomic conditional update** (all-or-nothing) + **HELD TTL** + saga compensation.
- **Search** = ES geo + facets + **availability bitset** (approximate); **booking** = RDBMS (exact).
- **Request-to-book** = saga with a human-approval timeout; **instant-book** skips it.
- **Payments** = escrow + delayed host payout + policy-based refunds + double-entry ledger.
- Patterns: Saga, State, Optimistic Locking, Strategy, CQRS, Outbox, Ports&Adapters.

### Related notes

- [Hotel Management & Reservation](hotel-management-system-design.md) — sibling availability/booking problem
- [BookMyShow](bookmyshow-system-design.md) — atomic conditional update / lock family
- [Payment System](payment-system-system-design.md) · [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md)

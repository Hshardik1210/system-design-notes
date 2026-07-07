# Airbnb — System Design (Lodging Marketplace)

> **Core challenge:** a **two-sided marketplace** connecting **hosts** (who list properties) with **guests** (who book them). Combines **geo + faceted search**, **date-range availability booking** (no double-booking), **payments with host payouts**, **reviews**, and **messaging** — plus a **request-to-book vs instant-book** flow.

---

## Contents

- [1. Mental Model & vs Hotel/Booking.com](#1-mental-model--vs-hotelbookingcom)
- [2. Requirements](#2-requirements)
- [3. Search & Discovery](#3-search--discovery)
- [4. Availability & Booking (no double-booking)](#4-availability--booking-no-double-booking)
- [5. Booking State Machine](#5-booking-state-machine)
- [6. Payments & Host Payouts](#6-payments--host-payouts)
- [7. Data Model (all tables)](#7-data-model-all-tables)
- [8. API Design](#8-api-design)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model & vs Hotel/Booking.com

```
Host lists property (calendar, price) → Guest searches by location+dates+guests
   → books (instant or request) → pays → stay → both review
```

vs a hotel chain: Airbnb has **many independent hosts**, each property is often **unique (count = 1)** rather than "10 identical Deluxe rooms", plus **host approval flows**, **host payouts**, and **trust/reviews on both sides**. Availability correctness is the same family as hotel booking (date-range, no double-book) but per **listing** rather than per room-type count.

---

## 2. Requirements

**Functional**
- Host: create/manage listings, set calendar availability + pricing, accept/decline requests.
- Guest: search (location, dates, guests, filters), view listing, book (instant/request), pay, review.
- Messaging between host & guest; cancellations/refunds per policy.

**Non-functional**
- No double-booking (strong on the booking write).
- Fast, geo + faceted **search** (read-heavy).
- Trust & safety (reviews, verification).

---

## 3. Search & Discovery

The dominant read path. Guests search by **location + date range + guests + filters** (price, type, amenities, superhost).

| Concern | Approach |
| --- | --- |
| **Geo** | Geohash/S2/QuadTree; or Elasticsearch `geo_bounding_box` for the map viewport |
| **Faceted filters** | Elasticsearch (amenities, price range, type, instant-book) |
| **Availability filter** | Approximate at search (cached calendar); exact re-check at booking |
| **Ranking** | Relevance + price + quality + personalization (ML black box) |
| **Freshness** | Index rebuilt from listings/calendar via CDC/Kafka |

> Search reads a **read model** (Elasticsearch), never the transactional DB. Map-viewport searches use bounding-box geo queries.

---

## 4. Availability & Booking (no double-booking)

Each listing has a **calendar** of available dates. A booking must reserve **every night** in `[checkIn, checkOut)`.

```
book(listing, checkIn, checkOut, guest):
  BEGIN TX
    # ensure every night is free, then mark them booked atomically
    UPDATE listing_calendar SET status='BOOKED', booking_id=?
     WHERE listing_id=? AND date IN [checkIn, checkOut) AND status='AVAILABLE'
    if rows_affected != nights:  ROLLBACK → NOT_AVAILABLE
    create booking (PENDING or REQUESTED)
  COMMIT
  → instant-book: initiate payment → CONFIRMED
    request-book: notify host → host accepts within N hrs → then charge → CONFIRMED
```

- **Calendar-per-night** rows (or a date-range/interval model) with atomic conditional update.
- **Hold with TTL** during checkout; release if abandoned.
- **Request-to-book** adds a host-approval step (a saga with a human-in-the-loop timeout).

---

## 5. Booking State Machine

```
REQUESTED ─host accept→ PENDING_PAYMENT ─pay→ CONFIRMED ─stay→ COMPLETED
    │ host decline / timeout        │ fail                │ cancel
    ▼                               ▼                      ▼
 DECLINED                       EXPIRED            CANCELLED (refund per policy) → release calendar

(instant-book skips REQUESTED → goes straight to PENDING_PAYMENT)
```

---

## 6. Payments & Host Payouts

- Charge guest on confirm (or at host acceptance); **escrow** until check-in, then **payout to host** minus platform fee.
- Split payment: guest charge → platform commission + host payout ledger.
- Refunds per cancellation policy (flexible/moderate/strict = Strategy).
- Idempotent payments + outbox; multi-currency.

---

## 7. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE,
                     is_host BOOLEAN DEFAULT FALSE, is_verified BOOLEAN DEFAULT FALSE );

CREATE TABLE listings (
    listing_id BIGINT PRIMARY KEY, host_id BIGINT NOT NULL,
    title TEXT, type VARCHAR(30), city VARCHAR(100),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, geohash VARCHAR(12),
    base_price INT, max_guests INT, amenities JSONB, instant_book BOOLEAN DEFAULT FALSE,
    rating NUMERIC(2,1) DEFAULT 0, rating_count BIGINT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE'
);
CREATE INDEX idx_listing_geohash ON listings(geohash) WHERE status='ACTIVE';

CREATE TABLE listing_photos ( photo_id BIGINT PRIMARY KEY, listing_id BIGINT, url TEXT, sort_order INT );

-- Availability calendar (per listing per night)
CREATE TABLE listing_calendar (
    listing_id BIGINT, date DATE,
    status VARCHAR(15) NOT NULL DEFAULT 'AVAILABLE',   -- AVAILABLE, BLOCKED, BOOKED
    price INT, booking_id BIGINT,
    PRIMARY KEY (listing_id, date)
);

CREATE TABLE bookings (
    booking_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) UNIQUE,
    listing_id BIGINT NOT NULL, guest_id BIGINT NOT NULL,
    check_in DATE, check_out DATE, guests INT,
    total_amount INT, status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_bookings_guest ON bookings(guest_id, created_at DESC);

CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, booking_id BIGINT, amount INT,
                        status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE payouts  ( payout_id BIGINT PRIMARY KEY, host_id BIGINT, booking_id BIGINT, amount INT, status VARCHAR(20) );
CREATE TABLE cancellation_policies ( listing_id BIGINT PRIMARY KEY, type VARCHAR(20) );  -- FLEXIBLE/MODERATE/STRICT

-- Reviews: both directions
CREATE TABLE reviews (
    review_id BIGINT PRIMARY KEY, booking_id BIGINT,
    author_id BIGINT, subject_id BIGINT, role VARCHAR(10),  -- GUEST_REVIEWS_HOST / HOST_REVIEWS_GUEST
    rating SMALLINT, comment TEXT, created_at TIMESTAMP
);

CREATE TABLE conversations ( conversation_id BIGINT PRIMARY KEY, listing_id BIGINT, guest_id BIGINT, host_id BIGINT );
CREATE TABLE messages ( message_id BIGINT PRIMARY KEY, conversation_id BIGINT, sender_id BIGINT, body TEXT, created_at TIMESTAMP );
CREATE TABLE wishlists ( user_id BIGINT, listing_id BIGINT, PRIMARY KEY(user_id, listing_id) );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> **Tables to consider:** users, listings, listing_photos, listing_calendar (the key one), bookings, payments, payouts, cancellation_policies, reviews, conversations, messages, wishlists, outbox, pricing_rules.

---

## 8. API Design

```
GET  /v1/listings?lat=&lng=&checkIn=&checkOut=&guests=&filters=   # search (map/faceted)
GET  /v1/listings/{id}   GET /v1/listings/{id}/availability?from=&to=
POST /v1/bookings        (Idempotency-Key) { listingId, checkIn, checkOut, guests }
POST /v1/bookings/{id}/accept | /decline     # host (request-to-book)
POST /v1/bookings/{id}/pay | /cancel
POST /v1/listings        # host create        PUT /v1/listings/{id}/calendar
POST /v1/bookings/{id}/review
POST /v1/conversations/{id}/messages
```

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Saga / Orchestration** | Request→accept→pay→confirm; payout; compensation on failure | Multi-step distributed (incl. human step) |
| **State** | Booking lifecycle | Guard transitions |
| **Strategy** | Search ranking, pricing, cancellation policy | Swap rules/algorithms |
| **Optimistic Locking / CAS** | Calendar conditional update | No double-booking |
| **CQRS** | ES search read model vs RDBMS write model | Optimized reads |
| **Outbox** | Reliable events (booking confirmed → notify, payout) | No dual-write loss |
| **Ports & Adapters** | Payment, search index, maps, notifications | Swap providers |
| **Repository** | Data access | Testable |
| **Observer/Pub-Sub** | Booking events → notifications, indexing, payouts | Decouple |
| **Decorator/Chain** | Price = base + cleaning + service fee + taxes − discount | Stack pricing |

---

## 10. Scaling & Failure

- **Search** on Elasticsearch (geo + facets) + cache; rebuild via CDC; approximate availability.
- **Booking** on RDBMS, strong consistency, shard by `listing_id` (or region).
- **Hold + TTL** for checkout; **saga compensation** releases calendar on payment/host failure.
- **Request-to-book timeout** auto-declines and releases the hold.
- **Idempotency** prevents double bookings.
- Photos/media → blob store + CDN.

---

## 11. Interview Cheat Sheet

> **"How is Airbnb different from a hotel booking system?"**
> "Many independent hosts; each listing is typically unique (count 1) with its own calendar; plus request-to-book (host approval), host payouts, and two-way reviews. Availability correctness is the same date-range/no-double-book idea, per listing calendar."

> **"How do you prevent double-booking?"**
> "Per-night calendar rows; book with an atomic conditional update marking all nights AVAILABLE→BOOKED in one transaction — if any night isn't free, roll back. Hold with TTL during checkout; saga releases on failure."

> **"Search at scale?"**
> "Elasticsearch with geo (bounding-box for the map) + faceted filters + ranking; availability is approximate/cached and re-checked exactly at booking; index rebuilt from listings/calendar via CDC."

> **"Instant-book vs request-to-book?"**
> "Instant → straight to payment→confirm. Request → notify host, wait for accept within a timeout (a saga with a human step), then charge. Both hold the calendar meanwhile."

> **"Payments/payouts?"**
> "Charge guest, escrow, pay out host minus fee after check-in; refunds per cancellation policy (Strategy); idempotent + outbox."

---

## 12. Final Takeaways

- **Two-sided marketplace**: hosts + guests, unique listings with per-listing **calendars**.
- **No double-booking** via per-night calendar + **atomic conditional update** + hold TTL + saga compensation.
- **Search** = Elasticsearch geo + facets + cache (approximate); **booking** = RDBMS (exact).
- **Request-to-book** = saga with a human-approval timeout; **instant-book** skips it.
- **Payments** = escrow + host payout + policy-based refunds.
- Patterns: Saga, State, Strategy, Optimistic Locking, CQRS, Outbox, Ports&Adapters.

### Related notes

- [Hotel Management & Reservation — System Design](hotel-management-system-design.md) — sibling availability/booking problem
- [BookMyShow — System Design](bookmyshow-system-design.md) · [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md)

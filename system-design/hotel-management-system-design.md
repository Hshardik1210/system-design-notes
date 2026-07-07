# Hotel Management & Reservation — System Design

> **Core challenge:** let guests **search available rooms for a date range**, **book without overbooking**, and handle **payments, check-in/out, cancellations, and inventory** across many hotels. The signature problem is **availability + concurrent booking correctness over date ranges** — never sell the same room twice, across *every* night.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Availability Model — The Core Problem](#5-availability-model--the-core-problem)
- [6. Booking Flow & Overbooking Prevention](#6-booking-flow--overbooking-prevention)
- [7. Overbooking as a Business Strategy](#7-overbooking-as-a-business-strategy)
- [8. Reservation State Machine](#8-reservation-state-machine)
- [9. Room Assignment (at check-in)](#9-room-assignment-at-check-in)
- [10. Search](#10-search)
- [11. Data Model (all tables)](#11-data-model-all-tables)
- [12. API Design](#12-api-design)
- [13. Sequences](#13-sequences)
- [14. Consistency & Edge Cases](#14-consistency--edge-cases)
- [15. Design Patterns (that can be used)](#15-design-patterns-that-can-be-used)
- [16. Scaling & Failure](#16-scaling--failure)
- [17. Interview Cheat Sheet](#17-interview-cheat-sheet)
- [18. Final Takeaways](#18-final-takeaways)

---

## 1. Mental Model

```
Search hotels/rooms for [checkIn, checkOut] → select room type → hold → pay → CONFIRMED
   → check-in (assign a physical room) → check-out → (or cancel → refund)
```

Unlike movie seats (a single instant) or Airbnb (a unique listing), hotels sell **room types** (Deluxe, Suite) — a **count of interchangeable rooms** — and availability must hold for **every night** in the range. That count-per-night model is the twist.

---

## 2. Requirements

**Functional**
- Search hotels by city/dates/guests; filter (price, rating, amenities).
- View room types + availability + price for a date range.
- Book a room type for a range; pay; confirm; check-in/out.
- Cancel/modify; refunds per policy; hotel-admin inventory & pricing.

**Non-functional**
- **No overbooking** (strong consistency on the booking write).
- Highly available search; scale for peaks (holidays, events).
- Accurate **dynamic pricing** (seasonal/demand).

---

## 3. Capacity Estimation

```
Hotels ~ 1M · room types ~ few per hotel · searches ≫ bookings (browse-heavy)
Inventory rows: 1M hotels × ~3 room types × 365 days ≈ 1B rows/year → prune past dates + partition
Search QPS (peak) ~ tens of thousands/sec → Elasticsearch + cache
Bookings ~ modest write rate, but STRONGLY consistent (money + no overbooking)
```

> Browse dominates → ES read model + cache. Booking is low-volume but must be exact. Inventory is the big table (hotel × room type × night).

---

## 4. Architecture

```
Client → API Gateway
  ├── Search Service      → Elasticsearch (geo + facets + availability) + cache
  ├── Inventory Service   → RDBMS (room_inventory per night) — the correctness core
  ├── Booking Service     → RDBMS (reservations) + saga
  ├── Payment Service     → gateway + ledger
  └── Admin (inventory/pricing)
             │
          Kafka (RESERVATION_CONFIRMED, INVENTORY_UPDATED → search index (CDC), notifications, analytics)
```

- **CQRS:** search = ES read model (rebuilt via CDC from inventory); booking = RDBMS write model with the atomic count updates.

---

## 5. Availability Model — The Core Problem

Hotels sell **room types**, each with a **count** of identical rooms — not individual seats. Availability is per **room type per night**.

```
room_inventory(hotel_id, room_type_id, date, total_count, booked_count)

A room type is available for [checkIn, checkOut) iff:
    for every night d in the range:  booked_count[d] < total_count[d]
```

```
Deluxe, total = 10
  2026-07-10  booked 9  → 1 free
  2026-07-11  booked 10 → FULL   ← any range that includes this night is NOT bookable
```

- **Why count-per-night, not per-room?** Guests don't care *which* Deluxe room — only that one is free. Tracking a **count** avoids assigning a specific room until check-in and keeps the hot path a simple integer update.
- A physical `rooms` table exists for **check-in assignment** and maintenance, decoupled from availability.

---

## 6. Booking Flow & Overbooking Prevention

```
book(hotel, roomType, checkIn, checkOut, guest):
  BEGIN TX
    # atomic conditional increment for EVERY night in the range
    UPDATE room_inventory
       SET booked_count = booked_count + 1
     WHERE hotel_id=? AND room_type_id=? AND date >= checkIn AND date < checkOut
       AND booked_count < total_count
    if rows_affected != number_of_nights:      # some night was full
        ROLLBACK → NO_AVAILABILITY
    INSERT reservation (status = PENDING_PAYMENT)   # or HELD with TTL
  COMMIT
  → initiate payment; success → CONFIRMED; fail/timeout → release (decrement each night)
```

| Technique | Note |
| --- | --- |
| **Atomic conditional update** (`WHERE booked_count < total_count`) | Prevents overbooking without explicit locks — the winner is whoever's increment keeps it ≤ total |
| **Hold / soft-lock with TTL** | Increment during checkout (like a seat-lock); a sweeper decrements if unpaid |
| **`SELECT ... FOR UPDATE`** on the range | Pessimistic alternative; simpler reasoning, less concurrency |
| **Saga** | Reserve → pay → confirm; **compensate** (decrement) on failure |

> **Key insight:** same correctness primitive as BookMyShow's seat lock, but applied to a **count per night across a date range** — **all nights succeed or none** (check `rows_affected == nights`, else roll back).

---

## 7. Overbooking as a Business Strategy

Unlike seats/Airbnb, hotels **intentionally overbook** (like airlines) because a predictable % of guests cancel or no-show.

```
allow booked_count up to total_count + buffer   (e.g. +5%)
if everyone shows up (rare) → "walk" the guest: rebook them at a nearby hotel + compensation
```

- Set the buffer from historical **cancellation/no-show rates** (ML/statistics).
- The conditional update becomes `WHERE booked_count < total_count + overbook_buffer`.
- Trade-off: higher occupancy/revenue vs occasional walk cost — a deliberate business decision, not a bug.

---

## 8. Reservation State Machine

```
PENDING_PAYMENT ─pay ok→ CONFIRMED ─check-in→ CHECKED_IN ─check-out→ COMPLETED
      │ fail/timeout            │ cancel / no-show
      ▼                         ▼
   EXPIRED / CANCELLED    CANCELLED (refund per policy) → release inventory (decrement each night)
```

- Every fail/cancel path **decrements `booked_count` for each night** (compensation).

---

## 9. Room Assignment (at check-in)

- Availability is tracked as a **count**; a **specific physical room** is assigned only at (or near) **check-in** — from `rooms WHERE room_type=? AND status='AVAILABLE'`.
- Decouples the booking hot path (count math) from housekeeping/maintenance state.
- Handles upgrades, adjoining-room requests, and maintenance blocks without touching availability counts.

---

## 10. Search

- **Read-optimized index (Elasticsearch):** hotels by city/geo + amenities + price, filtered by availability for the dates.
- **Availability at search is approximate/cached** — you can't check exact counts for every hotel per query. Denormalize a compact per-hotel availability summary (e.g., min free count over the next N days per room type) into the index; **re-check exact counts at booking**.
- Cache popular `city + date` searches; rebuild the index from inventory via **CDC/Kafka**.

---

## 11. Data Model (all tables)

```sql
CREATE TABLE hotels (
    hotel_id BIGINT PRIMARY KEY, name TEXT, city VARCHAR(100),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, rating NUMERIC(2,1), amenities JSONB
);
CREATE TABLE room_types (
    room_type_id BIGINT PRIMARY KEY, hotel_id BIGINT, name TEXT,   -- Deluxe, Suite
    capacity_guests INT, base_price INT, total_rooms INT
);
CREATE TABLE rooms (                       -- physical rooms (for check-in assignment + maintenance)
    room_id BIGINT PRIMARY KEY, hotel_id BIGINT, room_type_id BIGINT, room_number VARCHAR(10),
    status VARCHAR(20) DEFAULT 'AVAILABLE'  -- AVAILABLE, OCCUPIED, MAINTENANCE
);

-- The core availability table (per room type per night) — the correctness core
CREATE TABLE room_inventory (
    hotel_id BIGINT, room_type_id BIGINT, date DATE,
    total_count INT NOT NULL, booked_count INT NOT NULL DEFAULT 0,
    overbook_buffer INT DEFAULT 0, price INT,     -- per-night dynamic price
    PRIMARY KEY (hotel_id, room_type_id, date)
);

CREATE TABLE reservations (
    reservation_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) UNIQUE,
    guest_id BIGINT NOT NULL, hotel_id BIGINT, room_type_id BIGINT,
    check_in DATE NOT NULL, check_out DATE NOT NULL,
    guests INT, total_amount INT, status VARCHAR(30) NOT NULL,
    room_id BIGINT,                          -- assigned at check-in
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_res_guest ON reservations(guest_id, created_at DESC);

CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, reservation_id BIGINT, amount INT, status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE refunds  ( refund_id BIGINT PRIMARY KEY, reservation_id BIGINT, amount INT, status VARCHAR(20) );
CREATE TABLE cancellation_policies ( policy_id BIGINT PRIMARY KEY, hotel_id BIGINT, free_until_hours INT, penalty_pct INT );
CREATE TABLE guests ( guest_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255), phone VARCHAR(20) );
CREATE TABLE reviews ( review_id BIGINT PRIMARY KEY, hotel_id BIGINT, guest_id BIGINT, rating SMALLINT, comment TEXT );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> **Tables to consider:** hotels, room_types, rooms, **room_inventory** (the key one), reservations, payments, refunds, cancellation_policies, guests, reviews, outbox, pricing_rules.

---

## 12. API Design

```
GET  /v1/hotels?city=&checkIn=&checkOut=&guests=&filters=      # search
GET  /v1/hotels/{id}/availability?checkIn=&checkOut=
POST /v1/reservations     (Idempotency-Key) { hotelId, roomTypeId, checkIn, checkOut, guests }
                                              → { reservationId, status:PENDING_PAYMENT, amount }
POST /v1/reservations/{id}/pay
POST /v1/reservations/{id}/cancel
POST /v1/reservations/{id}/check-in    POST /v1/reservations/{id}/check-out
# Admin
PUT  /v1/hotels/{id}/inventory   { roomTypeId, date, totalCount, price, overbookBuffer }
```

---

## 13. Sequences

### Booking (saga)

```
Guest  BookingSvc  Inventory  Payment  Kafka
  │ book │            │          │        │
  ├─────►│ TX: increment booked_count for EACH night WHERE booked_count<total (+buffer) │
  │      │◄─ rows==nights? yes → reservation=PENDING_PAYMENT (else ROLLBACK → no availability)
  │      ├─ charge ───►│ ok      │        │
  │      ├─ CONFIRMED; outbox → RESERVATION_CONFIRMED ─────►│ → notify, index, analytics
  │◄─ confirmed ───────────────────────────────────────────┤
  (payment fail/timeout → compensation: decrement each night; reservation=EXPIRED)
```

---

## 14. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Overbooking (same room twice) | Atomic conditional increment per night; check `rows==nights`, else roll back |
| Two guests race the last room | One increment succeeds keeping `≤ total`; the other's `rows<nights` → NO_AVAILABILITY |
| Abandoned checkout | HELD increment + TTL → sweeper decrements; saga compensation |
| Payment fails | Decrement each night (compensation); reservation EXPIRED |
| Duplicate booking tap | `UNIQUE(idempotency_key)` |
| Cancellation | State → CANCELLED; decrement inventory; refund per policy (Strategy) |
| No-show | Charge per policy; overbook buffer absorbs; possibly walk another guest |
| Search shows sold-out hotel | Search is approximate; exact re-check at booking rejects it |
| Multi-night partial availability | All-or-nothing (any full night fails the whole range) |

---

## 15. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Saga / Orchestration** | Reserve → pay → confirm, with compensation (release inventory) | Distributed txn without 2PC |
| **State** | Reservation lifecycle | Guard transitions |
| **Optimistic Locking / CAS** | Inventory `booked_count < total_count` conditional update | Prevent overbooking |
| **Strategy** | Pricing (seasonal/dynamic), cancellation policy, room assignment, overbooking | Swap rules |
| **Outbox** | Reliable events (confirmed → notify) | No dual-write loss |
| **CQRS (lite)** | Search (ES read model) vs booking (RDBMS write) | Optimized reads |
| **Ports & Adapters** | Payment, search index, notifications | Swap providers |
| **Decorator / Chain** | Price = base + season + taxes + fees − discount | Stack pricing |
| **Repository / Factory** | Data access; notification/payment creation | Testable, extensible |

---

## 16. Scaling & Failure

- **Search** off Elasticsearch + cache; never scan inventory for every hotel; approximate, exact re-check at booking.
- **Booking** on RDBMS with strong consistency; **shard by `hotel_id`**; prune past-date inventory rows.
- **Hold + TTL** during checkout → abandoned carts release inventory; **saga compensation** on payment failure.
- **Idempotency key** prevents double booking on retry.
- **Overbooking buffer** (business choice) raises occupancy; **walk policy** handles the rare over-capacity night.
- **Dynamic pricing** job updates `room_inventory.price` per night by demand/season.

---

## 17. Interview Cheat Sheet

> **"How do you prevent overbooking over a date range?"**
> "Model inventory per room type **per night** (`booked_count`/`total_count`). Book with an **atomic conditional increment** for **every** night (`WHERE booked_count < total_count`); if `rows_affected != nights`, roll back the whole booking. Wrap reserve→pay→confirm in a **saga** that decrements (releases) on failure, with a **HELD TTL** for abandoned checkouts."

> **"How is this different from movie seats / Airbnb?"**
> "Seats are individually identified at a single instant; Airbnb is a unique listing (count 1). Hotels sell a **count of interchangeable rooms across a date range**, so all nights must simultaneously have `booked_count < total_count`. A specific physical room is only assigned at check-in."

> **"Do hotels ever overbook on purpose?"**
> "Yes — like airlines, they oversell by a small buffer based on historical cancellation/no-show rates to maximize occupancy, and 'walk' a guest (rebook nearby + compensate) on the rare full night."

> **"Search at scale?"**
> "Elasticsearch read model with a denormalized availability summary + cache; exact counts re-checked at booking. Search is approximate, booking is exact."

---

## 18. Final Takeaways

- **Availability = count per room type per night**; a range is bookable only if **every** night has `booked_count < total_count`.
- **Atomic conditional increment** per night prevents overbooking without heavy locks (all-or-nothing: `rows==nights`).
- **Saga** for reserve→pay→confirm with **compensation** (decrement) + **HELD TTL** for abandoned checkouts; **idempotency** stops double booking.
- **Overbooking buffer** is a deliberate business strategy (+ walk policy); **physical room assigned at check-in**.
- **Search** = ES read model + cache (approximate); **booking** = RDBMS (exact, strong); shard by hotel.
- Patterns: Saga, State, Optimistic Locking, Strategy (pricing/policy), Outbox, CQRS-lite.

### Related notes

- [BookMyShow](bookmyshow-system-design.md) — seat locking / atomic conditional update (same correctness family)
- [Airbnb](airbnb-system-design.md) — lodging marketplace sibling (per-listing calendar)
- [Payment System](payment-system-system-design.md) · [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md)

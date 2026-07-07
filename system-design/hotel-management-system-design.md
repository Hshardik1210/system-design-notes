# Hotel Management & Reservation — System Design

> **Core challenge:** let guests **search available rooms for a date range**, **book without overbooking**, handle **payments, check-in/out, cancellations**, and manage inventory across many hotels. The signature problem is **availability + concurrent booking correctness over date ranges** (never sell the same room twice).

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Availability Model — The Core Problem](#3-availability-model--the-core-problem)
- [4. Booking Flow & Overbooking Prevention](#4-booking-flow--overbooking-prevention)
- [5. Reservation State Machine](#5-reservation-state-machine)
- [6. Search](#6-search)
- [7. Data Model (all tables)](#7-data-model-all-tables)
- [8. API Design](#8-api-design)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
Search hotels/rooms for [checkIn, checkOut] → select room type → hold → pay → confirmed reservation
   → check-in → check-out → (or cancel → refund)
```

Unlike movie seats (a single instant), hotel booking is over a **date range** — availability must hold for **every night** in the range. That's the twist.

---

## 2. Requirements

**Functional**
- Search hotels by city/dates/guests; filter (price, rating, amenities).
- View room types + availability + price for the date range.
- Book a room for a date range; pay; get confirmation.
- Cancel/modify; refunds per policy. Check-in/check-out. Manage inventory (hotel admin).

**Non-functional**
- **No overbooking** (strong consistency on the booking write).
- Highly available search; scale for peak (holidays).
- Accurate pricing (seasonal/dynamic).

---

## 3. Availability Model — The Core Problem

Hotels sell **room types** (e.g. "Deluxe"), each with a **count** of identical rooms — not individual seats. Availability is per **room type per night**.

### Inventory-per-night table (the clean model)

```
room_inventory(hotel_id, room_type_id, date, total_count, booked_count)

A room type is available for [checkIn, checkOut) if:
    for every date d in range:  booked_count[d] < total_count[d]
```

Booking a range = **incrementing `booked_count` for each night** in `[checkIn, checkOut)`, atomically, only if capacity remains.

```
Deluxe, total=10
  2026-07-10  booked 9  → 1 free
  2026-07-11  booked 10 → FULL  ← range including this night is NOT bookable
```

---

## 4. Booking Flow & Overbooking Prevention

```
book(hotel, roomType, checkIn, checkOut, guest):
  BEGIN TX
    # atomic conditional update for EACH night in the range
    UPDATE room_inventory
       SET booked_count = booked_count + 1
     WHERE hotel_id=? AND room_type_id=? AND date IN (range)
       AND booked_count < total_count
    if rows_affected != number_of_nights:   # some night was full
        ROLLBACK → return NO_AVAILABILITY
    create reservation (status = PENDING_PAYMENT)
  COMMIT
  → initiate payment; on success → CONFIRMED; on fail/timeout → release (decrement) 
```

| Technique | Note |
| --- | --- |
| **Atomic conditional update** (`WHERE booked_count < total_count`) | Prevents overbooking without explicit locks |
| **Hold / soft-lock with TTL** | Reserve during checkout (like seat-lock); release if unpaid |
| **`SELECT ... FOR UPDATE`** on inventory rows | Pessimistic alternative for the range |
| **Saga** | Reserve → pay → confirm; compensate (release) on failure |

> **Key insight:** the correctness primitive is the same idea as BookMyShow seat locking, but applied to a **count per night across a date range** — all nights must succeed or none.

---

## 5. Reservation State Machine

```
PENDING_PAYMENT ─pay ok→ CONFIRMED ─check-in→ CHECKED_IN ─check-out→ COMPLETED
      │ fail/timeout            │ cancel
      ▼                         ▼
   EXPIRED / CANCELLED     CANCELLED (refund per policy) → release inventory
```

---

## 6. Search

- **Read-optimized index** (Elasticsearch): hotels by city/geo + amenities + price, filtered by availability for the dates.
- Availability for search is **approximate/cached** (avoid checking exact counts for every hotel); confirm exact availability at booking time.
- Cache popular city+date searches; rebuild index from inventory via CDC.

---

## 7. Data Model (all tables)

```sql
CREATE TABLE hotels (
    hotel_id BIGINT PRIMARY KEY, name TEXT, city VARCHAR(100),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, rating NUMERIC(2,1), amenities JSONB
);
CREATE TABLE room_types (
    room_type_id BIGINT PRIMARY KEY, hotel_id BIGINT, name TEXT,   -- Deluxe, Suite
    capacity_guests INT, base_price INT, total_rooms INT
);
CREATE TABLE rooms (                       -- physical rooms (optional; for check-in assignment)
    room_id BIGINT PRIMARY KEY, hotel_id BIGINT, room_type_id BIGINT, room_number VARCHAR(10),
    status VARCHAR(20) DEFAULT 'AVAILABLE' -- AVAILABLE, OCCUPIED, MAINTENANCE
);

-- The core availability table (per room type per night)
CREATE TABLE room_inventory (
    hotel_id BIGINT, room_type_id BIGINT, date DATE,
    total_count INT NOT NULL, booked_count INT NOT NULL DEFAULT 0,
    price INT,                              -- per-night dynamic price
    PRIMARY KEY (hotel_id, room_type_id, date)
);

CREATE TABLE reservations (
    reservation_id BIGINT PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE,
    guest_id BIGINT NOT NULL, hotel_id BIGINT, room_type_id BIGINT,
    check_in DATE NOT NULL, check_out DATE NOT NULL,
    guests INT, total_amount INT,
    status VARCHAR(30) NOT NULL,            -- see state machine
    room_id BIGINT,                          -- assigned at check-in
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_res_guest ON reservations(guest_id, created_at DESC);

CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, reservation_id BIGINT, amount INT,
                        status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE refunds  ( refund_id BIGINT PRIMARY KEY, reservation_id BIGINT, amount INT, status VARCHAR(20) );
CREATE TABLE cancellation_policies ( policy_id BIGINT PRIMARY KEY, hotel_id BIGINT, free_until_hours INT, penalty_pct INT );
CREATE TABLE guests ( guest_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255), phone VARCHAR(20) );
CREATE TABLE reviews ( review_id BIGINT PRIMARY KEY, hotel_id BIGINT, guest_id BIGINT, rating SMALLINT, comment TEXT );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> **Tables to consider:** hotels, room_types, rooms, room_inventory (the key one), reservations, payments, refunds, cancellation_policies, guests, reviews, outbox, pricing_rules.

---

## 8. API Design

```
GET  /v1/hotels?city=&checkIn=&checkOut=&guests=&filters=      # search
GET  /v1/hotels/{id}/availability?checkIn=&checkOut=
POST /v1/reservations     (Idempotency-Key) { hotelId, roomTypeId, checkIn, checkOut, guests }
                                              → { reservationId, status:PENDING_PAYMENT, amount }
POST /v1/reservations/{id}/pay
POST /v1/reservations/{id}/cancel
POST /v1/reservations/{id}/check-in    POST /v1/reservations/{id}/check-out
# Admin
PUT  /v1/hotels/{id}/inventory   { roomTypeId, date, totalCount, price }
```

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Saga / Orchestration** | Reserve → pay → confirm, with compensation (release inventory) | Distributed txn without 2PC |
| **State** | Reservation lifecycle | Guard transitions |
| **Strategy** | Pricing (seasonal/dynamic), cancellation policy, room-assignment | Swap rules |
| **Optimistic Locking / CAS** | Inventory `booked_count < total_count` conditional update | Prevent overbooking |
| **Outbox** | Reliable events (booking confirmed → notify) | No dual-write loss |
| **Repository** | Data access | Testable domain |
| **Ports & Adapters** | Payment, search index, notifications | Swap providers |
| **Decorator/Chain** | Price = base + season + taxes + fees − discount | Stack pricing |
| **Factory** | Notification/payment creation | Extensible |
| **CQRS (lite)** | Search (ES read model) vs booking (RDBMS write) | Optimized reads |

---

## 10. Scaling & Failure

- **Search** off Elasticsearch + cache; never scan inventory for every hotel.
- **Booking** on RDBMS with strong consistency; shard by `hotel_id`.
- **Hold + TTL** during checkout so abandoned carts release inventory.
- **Payment fail/timeout** → saga compensation decrements `booked_count`.
- **Idempotency key** prevents double booking on retry.
- Overbooking strategy (airline-style) is a *business* choice — can intentionally oversell with a small buffer + walk policy.

---

## 11. Interview Cheat Sheet

> **"How do you prevent overbooking over a date range?"**
> "Model inventory per room type **per night** (`booked_count`/`total_count`). Book with an atomic conditional update for **every** night in the range (`WHERE booked_count < total_count`); if any night fails, roll back the whole booking. Wrap reserve→pay→confirm in a saga that releases inventory on failure."

> **"How is this different from movie seat booking?"**
> "Seats are a single instant and individually identified; hotels sell a **count of interchangeable rooms across a range of nights**, so all nights must be simultaneously available. Same correctness idea (atomic conditional update), applied per night."

> **"How does search stay fast?"**
> "Elasticsearch read model with cached availability; exact availability is re-checked at booking time — search is approximate, booking is exact."

> **"Cancellation and refunds?"**
> "State machine → CANCELLED, release inventory (decrement each night), refund per the cancellation policy (Strategy)."

---

## 12. Final Takeaways

- **Availability = count per room type per night**; a range is bookable only if every night has capacity.
- **Atomic conditional update** (`booked_count < total_count`) per night prevents overbooking — no heavy locks needed.
- **Saga** for reserve→pay→confirm with **compensation** (release) + **hold TTL** for abandoned checkouts.
- **Idempotency** stops double bookings on retry.
- **Search** = ES read model + cache (approximate); **booking** = RDBMS (exact, strong).
- Patterns: Saga, State, Strategy (pricing/policy), Optimistic Locking, Outbox, CQRS-lite.

### Related notes

- [BookMyShow — System Design](bookmyshow-system-design.md) — seat locking / atomic conditional update (same correctness family)
- [Airbnb — System Design](airbnb-system-design.md) — lodging marketplace sibling
- [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md)

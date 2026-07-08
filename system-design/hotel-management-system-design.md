# Hotel Management & Reservation — System Design

> **Core challenge:** let guests **search available rooms for a date range**, **book without overbooking**, and handle **payments, check-in/out, cancellations, and inventory** across many hotels. The signature problem is **availability + concurrent booking correctness over date ranges** — never sell the same room twice, across *every* night.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

A hotel-booking site like MakeMyTrip / Booking.com. A guest tells you: *"I want a room in Goa from July 10 to July 13."* Your job is to:

- **Show** them which hotels have a free room **for all three of those nights**.
- **Take their money** and **guarantee** the room is theirs.
- **Never** promise the same room to two people (**overbooking** — a real nightmare: the guest arrives at midnight and there's no bed).

So the whole system is a giant, careful **"is a room free, and can I lock it for you"** machine. Everything else — search, payments, check-in — hangs off that one hard question.

### Why is a hotel harder than it sounds?

You might assume "a room is either free or taken." Two twists make hotels special:

- **Twist 1 — you book a *type*, not a specific room.** You don't ask for "room 412"; you ask for "a Deluxe room." The hotel has, say, 10 identical Deluxe rooms. As long as **at least one** is free, you're fine. So we track a **count** ("8 of 10 Deluxe booked"), not individual rooms.
- **Twist 2 — a booking spans *multiple nights*.** "July 10–13" means you need a Deluxe free on the **10th AND the 11th AND the 12th**. If even one of those nights is sold out, the whole stay is impossible.

The stay is bookable only if a room of that type is free on *every* night of the range — if any single night is sold out, the whole booking fails.

Here's the count-per-night idea as a tiny table — one row per (room type, night):

```
Deluxe room type, 10 rooms total:
  night        booked / total     free?
  2026-07-10     8 / 10            yes (2 free)
  2026-07-11     9 / 10            yes (1 free)
  2026-07-12    10 / 10            NO  → sold out this night
```

A guest wanting July 10–13 is **rejected**, because the 12th is full — even though the 10th and 11th had space. That "all nights must be free" rule is the heart of §5 and §6.

#### Q: Why not just track each physical room like a calendar (room 412 booked Jul 10–13)?

You *can*, and small B&Bs do. But at scale it's wasteful and slow: guests don't care which of 10 identical Deluxe rooms they get, so forcing the system to pick "room 412" at booking time means locking specific rooms, juggling maintenance/cleaning, and doing far more work on the busiest code path. Tracking a **count per night** turns the hot path into one tiny integer update ("booked_count: 8 → 9") and defers picking an actual room key until **check-in** (see §9).

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

### "Functional vs non-functional" in hotel terms

- **Functional = *what* the app lets you do** (the features): search hotels, see a price, book, pay, cancel, check in. If you can list it as a button on the screen, it's functional.
- **Non-functional = *how well* it must behave** (the qualities): "never double-book," "search stays fast even on New Year's Eve," "prices are correct." No button — but if these fail, the product is broken even though every button "works."

The single most important non-functional rule here is **"no overbooking."** It's worth calling out why it's so strict:

```
Search is allowed to be a little wrong (it says "available", you re-check at booking) — no harm done.
Booking is NOT allowed to be even slightly wrong — a wrong booking = a real guest with no bed = refunds, angry reviews, lawsuits.
```

That asymmetry — **relaxed reads, strict writes** — shapes the entire architecture (§4): fast/approximate search on one system, exact/strongly-consistent booking on another.

---

## 3. Capacity Estimation

```
Hotels ~ 1M · room types ~ few per hotel · searches ≫ bookings (browse-heavy)
Inventory rows: 1M hotels × ~3 room types × 365 days ≈ 1B rows/year → prune past dates + partition
Search QPS (peak) ~ tens of thousands/sec → Elasticsearch + cache
Bookings ~ modest write rate, but STRONGLY consistent (money + no overbooking)
```

> Browse dominates → ES read model + cache. Booking is low-volume but must be exact. Inventory is the big table (hotel × room type × night).

### Reading the back-of-the-envelope math

These numbers aren't trivia — each one points at a design decision.

| The number | Plain meaning | So we... |
| --- | --- | --- |
| **searches ≫ bookings** | For every person who books, hundreds just *browse* ("Goa? too pricey, let me try Manali"). | Optimize hard for **reads** — put search on a fast, replicated index (Elasticsearch) + cache. |
| **~1B inventory rows/year** | 1M hotels × ~3 room types × 365 nights. That's the biggest table by far. | **Partition** it and **prune** past dates (last week's availability is useless — delete it). |
| **Bookings = low rate, strongly consistent** | Money changes hands, and a wrong answer means a real person is stranded. | Keep booking on a proper **RDBMS** with transactions — correctness over raw speed. |

Reads dominate: for every booking there are hundreds of searches. So optimize reads (a fast, replicated index) and keep the rarer booking writes careful, exact, and serialized.

#### Q: Why prune past-date inventory? Isn't deleting data risky?

The `room_inventory` table only answers "is this room free *going forward*." Once July 10 is in the past, nobody can book it, so its row is dead weight slowing every query and inflating storage. The *reservations* (who stayed, what they paid) are kept forever in the `reservations`/`payments` tables for history and accounting — we only prune the forward-looking **availability counters**, not the business records.

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

### Who does what (the services)

Each box is a specialist service. A client never talks to them directly — it talks to the **API Gateway**, which routes each request to the right service.

| Service | Job |
| --- | --- |
| **API Gateway** | Single entry point; sends each request to the right service. |
| **Search Service** | Fast "what's available in Goa?" answers, from a pre-built index (Elasticsearch). Allowed to be slightly stale. |
| **Inventory Service** | The one source of truth for "how many Deluxe rooms are left each night." Guards against overbooking. |
| **Booking Service** | Runs the reserve → pay → confirm steps; undoes them if payment fails. |
| **Payment Service** | Talks to the card gateway; records who paid what. |
| **Admin** | Sets room counts and nightly prices. |
| **Kafka** | Event backbone: when something happens ("Reservation confirmed!"), it publishes an event; other services (search index, email, analytics) consume it and react. |

#### Q: What is CQRS, in one plain sentence?

**CQRS = use two different data stores: one tuned for *reading*, one tuned for *writing*.** Here, **reads** (search) hit Elasticsearch — fast, handles huge browse traffic, can be a few seconds behind. **Writes** (booking) hit the RDBMS — exact, transactional, never overbooks. When inventory changes in the RDBMS, that change is streamed (via **CDC** — Change Data Capture — over Kafka) into Elasticsearch to keep search fresh-ish.

```
RDBMS (write side)        = the exact, transactional source of truth (always correct)
Elasticsearch (read side) = a fast read copy, rebuilt continuously, so it can lag by seconds
```

The search index might briefly show "rooms available" for a hotel that just sold out — that's fine, because the **booking path re-checks the exact counts** the moment you actually try to pay (§10 "exact re-check at booking").

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

### The domain, as Java classes

Let's turn the nouns of a hotel into objects. Note how `RoomType` holds a **count** and the per-night availability lives in its own object — this mirrors the `room_inventory` table.

```java
// A hotel is just a place with rooms grouped into types.
class Hotel {
    long   hotelId;
    String name;
    String city;
    double lat, lng;
    List<RoomType> roomTypes;   // Deluxe, Suite, ...
}

// A RoomType = a bucket of INTERCHANGEABLE rooms (this is the "twist").
class RoomType {
    long   roomTypeId;
    String name;            // "Deluxe"
    int    capacityGuests;  // e.g. 2 adults
    int    basePrice;       // starting nightly price (before dynamic pricing)
    int    totalRooms;      // e.g. 10 identical Deluxe rooms
}

// A specific physical room — only needed at CHECK-IN, not for availability.
class Room {
    long   roomId;
    long   roomTypeId;
    String roomNumber;      // "412"
    RoomStatus status;      // AVAILABLE, OCCUPIED, MAINTENANCE
}

enum RoomStatus { AVAILABLE, OCCUPIED, MAINTENANCE }
```

The **star of the show** — availability is one row per (room type, night):

```java
// ONE of these per room type per night. This is what we increment when someone books.
class RoomInventory {
    long   hotelId;
    long   roomTypeId;
    LocalDate date;          // the specific NIGHT
    int    totalCount;       // rooms that exist that night (e.g. 10)
    int    bookedCount;      // rooms already sold that night (e.g. 9)
    int    overbookBuffer;   // deliberate oversell allowance (see §7)
    int    price;            // dynamic per-night price

    boolean hasSpace() {
        // room for one more? (buffer lets us oversell on purpose)
        return bookedCount < totalCount + overbookBuffer;
    }
}
```

Checking a whole date range = "**every** night must have space" (the all-or-nothing rule):

```java
class AvailabilityService {

    // is a room type bookable for ALL nights in [checkIn, checkOut)?
    boolean isAvailable(long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            RoomInventory night = inventoryFor(roomTypeId, d);
            if (night == null || !night.hasSpace()) {
                return false;   // ONE full night kills the whole stay
            }
        }
        return true;            // survived every night → bookable
    }
}
```

> Notice `checkOut` is **exclusive**: a July 10→13 stay occupies the nights of the 10th, 11th, and 12th — you leave on the morning of the 13th, so that night is *not* yours. That's why the loop is `d.isBefore(checkOut)`, not `<=`.

#### Q: Why is there both a `RoomType` (with `totalRooms`) and a per-night `RoomInventory` (with `totalCount`)?

Because availability changes **night by night**. `RoomType.totalRooms` is the "normal" number of rooms. But on a given night, some might be under maintenance, or the manager might add capacity — so each **night** gets its own `totalCount`/`bookedCount` counter. `RoomType` describes the product; `RoomInventory` tracks the live stock for each date.

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

### The double-booking problem, and the one trick that fixes it

**The scary scenario:** it's the last Deluxe room for July 10. Two guests, Alice and Bob, both tap "Book" at the *exact* same millisecond.

```
Deluxe on Jul 10:  booked 9 / total 10   → exactly 1 room left

Alice's request reads: "9 booked, 1 free → OK!"
Bob's request reads:   "9 booked, 1 free → OK!"     (at the same instant, before Alice wrote)
Both write "booked = 10"  →  💥 TWO people got the SAME last room. Overbooking bug.
```

This is a **race condition**: two people read the same value and both act on it. The naive "read, check, then write" is broken because another request can sneak in between the read and the write.

**The fix — make "check and increment" a single atomic step in the database**, so the DB itself only lets the increment through if there's still space:

```sql
UPDATE room_inventory
   SET booked_count = booked_count + 1
 WHERE hotel_id = ? AND room_type_id = ? AND date = ?
   AND booked_count < total_count;      -- the guard: only if a room is actually free
```

The database processes these **one at a time** for the same row. So for that last room:

```
Alice's UPDATE runs first: 9 < 10 is true → booked becomes 10. (1 row changed ✅)
Bob's UPDATE runs next:    10 < 10 is FALSE → nothing changes.  (0 rows changed ❌ → Bob rejected)
```

No lost room, no manual locking — the `WHERE booked_count < total_count` **is** the lock. This is called an **atomic conditional update** (a form of **compare-and-set / optimistic concurrency**).

### Booking a *range* — all nights or nothing

A stay is many nights, so we run that conditional increment for **every** night at once, inside one transaction, then check: *did all of them succeed?*

```java
class BookingService {

    // returns a PENDING reservation, or throws if any night is full
    Reservation book(long hotelId, long roomTypeId,
                     LocalDate checkIn, LocalDate checkOut,
                     long guestId, String idempotencyKey) {

        int nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);

        return tx.run(() -> {   // one database transaction: all-or-nothing

            // conditional increment for EVERY night in the range, in one statement
            int rowsAffected = db.execute("""
                UPDATE room_inventory
                   SET booked_count = booked_count + 1
                 WHERE hotel_id = ? AND room_type_id = ?
                   AND date >= ? AND date < ?
                   AND booked_count < total_count + overbook_buffer
                """, hotelId, roomTypeId, checkIn, checkOut);

            // THE all-or-nothing check: did we grab a room on EVERY night?
            if (rowsAffected != nights) {
                throw new NoAvailabilityException();   // some night was full → ROLL BACK everything
            }

            // hold the room while the guest pays (PENDING, with a TTL sweeper as backup)
            return db.insertReservation(new Reservation(
                hotelId, roomTypeId, checkIn, checkOut, guestId,
                ReservationStatus.PENDING_PAYMENT, idempotencyKey));
        });
        // caller now triggers payment → success = CONFIRMED, fail/timeout = release each night
    }
}
```

Why `rowsAffected != nights` is the whole game:

```
Want Jul 10, 11, 12 (3 nights). The UPDATE touches whichever nights still had space:
  Jul 10 free → incremented      ┐
  Jul 11 free → incremented      ├─ 3 rows changed == 3 nights → SUCCESS
  Jul 12 free → incremented      ┘

But if Jul 12 was full:
  Jul 10 incremented, Jul 11 incremented, Jul 12 skipped → 2 rows changed != 3 nights
  → ROLLBACK: the 10th and 11th increments are UNDONE too. Guest gets nothing (correct — we can't sell a partial stay).
```

The transaction's **rollback** is what makes it truly all-or-nothing: even the nights that succeeded get reverted, so we never leave the guest half-booked.

#### Q: What's a "hold" / "soft lock with TTL," and why not just book instantly?

Between "I want this room" and "payment succeeded" there's a gap (the guest is typing card details). We don't want two people fighting over a room during that gap, so we **increment `booked_count` immediately** and mark the reservation `PENDING_PAYMENT` — the room is *held* for them. But what if they close the tab and never pay? A background **sweeper** job finds holds older than, say, 10 minutes (the **TTL** — time to live) and **decrements** the count, freeing the room again.

```
book → booked_count++ (held) → PENDING_PAYMENT
   ├─ pays in time      → CONFIRMED (hold becomes permanent)
   └─ abandons / times out → sweeper decrements booked_count → room free again
```

#### Q: `SELECT ... FOR UPDATE` vs the atomic conditional update — what's the difference?

Both prevent the race; they're just two styles:

- **Atomic conditional update** (used above) = **optimistic**: no explicit lock, just "increment only if the condition still holds." The loser simply changes 0 rows. Higher concurrency, preferred for a hot path.
- **`SELECT ... FOR UPDATE`** = **pessimistic**: you explicitly **lock** the inventory rows first, then read, decide, and write while holding the lock. Easier to reason about, but everyone else for that room *waits in line* — less concurrency.

For a busy hotel site, the optimistic conditional update wins because most bookings *don't* collide, so paying a locking cost on every booking would be wasteful.

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

### Wait — I thought overbooking was the bug we're preventing?

This trips everyone up. There are **two different meanings**:

| "Overbooking" | Meaning | Is it OK? |
| --- | --- | --- |
| **Accidental** (§6) | A software race sells the *same* room twice by mistake. | ❌ A bug. Must never happen. |
| **Intentional** (this section) | The hotel *chooses* to sell, say, 105 rooms when it has 100, betting some will cancel. | ✅ A deliberate revenue strategy. |

Airlines do the same: a 180-seat flight may sell ~190 tickets because ~10 people reliably miss it, and on the rare full flight they bump someone. Hotels **"walk"** the guest on a rare full night — book and pay for a room at a nearby hotel plus compensation. The walk cost is small; the extra bookings earn far more.

The knob is a single number, `overbook_buffer`, and it plugs right into the same guard from §6 — that's the elegance:

```java
boolean hasSpace() {
    return bookedCount < totalCount + overbookBuffer;   // buffer = 0 → never oversell; buffer = 5 → oversell by 5
}
```

- `overbookBuffer = 0` → the strict "never oversell" behavior.
- `overbookBuffer = 5` on a 100-room type → the system will happily sell up to 105.

#### Q: How do they pick the buffer number?

From **history**. If, on average, 4% of guests cancel or no-show for this hotel on similar dates, a buffer around 4% is "safe." Fancier systems use ML on seasonality, day-of-week, event calendars, etc. Set it too low → empty rooms (lost money); too high → too many walks (angry guests, walk costs). It's a tuned business trade-off, not a technical constant.

---

## 8. Reservation State Machine

```
PENDING_PAYMENT ─pay ok→ CONFIRMED ─check-in→ CHECKED_IN ─check-out→ COMPLETED
      │ fail/timeout            │ cancel / no-show
      ▼                         ▼
   EXPIRED / CANCELLED    CANCELLED (refund per policy) → release inventory (decrement each night)
```

- Every fail/cancel path **decrements `booked_count` for each night** (compensation).

### The reservation lifecycle

A reservation is never "just booked" — it moves through **stages**, and only certain moves are legal. That's a **state machine**: a fixed set of states + rules for which state can go to which. A reservation can't jump from `PENDING_PAYMENT` straight to `COMPLETED`, and terminal states have no way back.

The states, in plain words:

| State | Means | What can happen next |
| --- | --- | --- |
| `PENDING_PAYMENT` | Room is held; we're waiting for the card to go through. | pay → `CONFIRMED`; fail/timeout → `EXPIRED` |
| `CONFIRMED` | Paid. The room is truly theirs. | check in → `CHECKED_IN`; cancel/no-show → `CANCELLED` |
| `CHECKED_IN` | Guest has arrived and holds a physical room key. | check out → `COMPLETED` |
| `COMPLETED` | Stay finished. Terminal. | (nothing) |
| `EXPIRED` / `CANCELLED` | Booking died before/after confirming. Terminal. | (nothing) — inventory released |

Encoding the rules so illegal jumps are impossible:

```java
enum ReservationStatus {
    PENDING_PAYMENT, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, EXPIRED;
}

class Reservation {
    ReservationStatus status = ReservationStatus.PENDING_PAYMENT;

    // only allow moves that make sense; block nonsense like COMPLETED -> CHECKED_IN
    void transitionTo(ReservationStatus next) {
        boolean allowed = switch (this.status) {
            case PENDING_PAYMENT -> next == CONFIRMED || next == EXPIRED;
            case CONFIRMED       -> next == CHECKED_IN || next == CANCELLED;
            case CHECKED_IN      -> next == COMPLETED;
            default              -> false;   // COMPLETED/CANCELLED/EXPIRED are terminal
        };
        if (!allowed) {
            throw new IllegalStateException("Illegal move: " + status + " -> " + next);
        }
        this.status = next;
    }
}
```

**The rule that ties back to inventory:** every path that *ends* a booking early (`EXPIRED`, `CANCELLED`, no-show) must **give the rooms back** — decrement `booked_count` for each night, or those rooms are lost forever (phantom "sold out" with an empty hotel). Undoing an earlier increment like this is called **compensation** (see the Saga pattern in §15).

```java
void cancel(Reservation r) {
    r.transitionTo(ReservationStatus.CANCELLED);
    releaseInventory(r);          // decrement booked_count for EACH night — the compensation
    refundPerPolicy(r);           // money back per cancellation policy (§14)
}
```

#### Q: Why have `PENDING_PAYMENT` at all — why not create the reservation only after payment?

Because you must **hold the room during payment**. If you waited until payment succeeded to touch inventory, two people could both "succeed" paying for the last room simultaneously. So we grab the room *first* (`PENDING_PAYMENT` + increment), then collect money. If money never comes, the state flips to `EXPIRED` and the room is released — you hold the room first and collect payment second.

---

## 9. Room Assignment (at check-in)

- Availability is tracked as a **count**; a **specific physical room** is assigned only at (or near) **check-in** — from `rooms WHERE room_type=? AND status='AVAILABLE'`.
- Decouples the booking hot path (count math) from housekeeping/maintenance state.
- Handles upgrades, adjoining-room requests, and maintenance blocks without touching availability counts.

### "You booked a Deluxe; which room do you actually get?"

Remember the twist: when you booked, the system only decided *"one of the 10 Deluxe rooms is yours"* — it never picked room **412** vs **415**. That choice is **deferred all the way to check-in**, when a specific room of that type that's clean and ready is assigned. You only ever needed *one* to be available, not a particular one.

Assignment at the desk, in code:

```java
class CheckInService {

    Room assignRoom(Reservation r) {
        // pick any physical room of the right type that's ready right now
        Room room = db.querySingle("""
            SELECT * FROM rooms
             WHERE hotel_id = ? AND room_type_id = ? AND status = 'AVAILABLE'
             LIMIT 1
            """, r.hotelId, r.roomTypeId);

        if (room == null) {
            // rare: counts said a room was sold, but no PHYSICAL room is ready
            // (e.g. all under maintenance) → upgrade the guest or walk them
            room = handleUpgradeOrWalk(r);
        }

        room.status = RoomStatus.OCCUPIED;   // now it's physically taken
        r.roomId = room.roomId;              // record which key they got
        r.transitionTo(ReservationStatus.CHECKED_IN);
        return room;
    }
}
```

#### Q: Why separate the *count* (availability) from the *physical room* (assignment)? Isn't that two sources of truth?

They answer **two different questions at two different times**:

- `room_inventory` (counts) answers *"can I still SELL a Deluxe for these nights?"* — needed on the hot, high-traffic booking path, so it must be a fast integer.
- `rooms` (physical) answers *"which key do I hand this guest at the desk?"* — needed once, at check-in, and must cope with messy real-world state (this room is being repainted, that one's being cleaned, guest wants adjoining rooms).

Mixing them would drag maintenance/cleaning logic onto the booking hot path and make every booking pick a specific room (more locking, less flexibility). Keeping counts for selling and physical rooms for assigning lets each stay simple. They only meet briefly at check-in.

---

## 10. Search

- **Read-optimized index (Elasticsearch):** hotels by city/geo + amenities + price, filtered by availability for the dates.
- **Availability at search is approximate/cached** — you can't check exact counts for every hotel per query. Denormalize a compact per-hotel availability summary (e.g., min free count over the next N days per room type) into the index; **re-check exact counts at booking**.
- Cache popular `city + date` searches; rebuild the index from inventory via **CDC/Kafka**.

### Why search is "approximate," and why that's fine

When someone searches "Goa, July 10–13, 2 guests," you might match **thousands** of hotels. Running the exact §5 "check every night for every room type" query against the booking database for *every* hotel on *every* keystroke would melt it.

So search uses a **pre-built, read-optimized index** (Elasticsearch) that stores a **rough summary** of availability, not exact counts:

```
Instead of exact per-night counts, the index stores a cheap hint per hotel/room type:
   "min free rooms over the next 30 nights" — e.g. Deluxe: minFree = 2  → probably bookable
If minFree = 0 → definitely full, hide it. Otherwise show it as "available".
```

The index gives a fast, good-enough hint ("available"); the exact, authoritative check happens at booking time.

The two-step "approximate then exact" flow:

```java
// STEP 1 — fast, approximate: fine to occasionally show a hotel that just sold out
List<Hotel> results = elasticsearch.search(city, checkIn, checkOut, guests, filters);

// STEP 2 — the moment the guest actually books, re-run the EXACT check (§6) on the RDBMS.
// If it's really full now, the atomic conditional update returns rows != nights → reject cleanly.
```

Keeping the index fresh: whenever `room_inventory` changes in the RDBMS, that change is streamed out via **CDC (Change Data Capture)** over **Kafka**, and the search index updates. It lags by seconds — acceptable, because booking re-checks for real.

#### Q: Isn't it bad UX to show a hotel that's actually sold out?

Slightly, but it's the right trade-off. The alternative — making search perfectly exact — would require hammering the booking database on every search, which browse-heavy traffic (§3) would crush. A rare "sorry, just sold out, please pick another" at booking time is far cheaper than a slow or down search for everyone. **Search optimizes for speed and availability; booking optimizes for correctness.**

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

### What each table is *for* (and the two sneaky ones)

Most tables are obvious nouns. Here's the mental map, then the two that confuse beginners:

| Table | Plain job |
| --- | --- |
| `hotels`, `room_types`, `rooms` | The catalog: places, the product buckets (Deluxe/Suite), and physical rooms. |
| `room_inventory` | **The correctness core.** One row per (hotel, room type, night) with `booked_count`/`total_count`. This is what §6 increments. |
| `reservations` | One row per booking: who, which type, which dates, what state, how much. |
| `payments` / `refunds` | Money in / money out, each tied to a reservation. |
| `cancellation_policies` | The rules for refunds ("free until 24h before, else 50% penalty"). |
| `guests`, `reviews` | The customer and their feedback. |
| `outbox` | A helper table for reliably sending events (explained below). |

**Sneaky table #1 — `room_inventory` is huge because of the `date` column.** It's not "one row per room type" — it's **one row per room type per night**. That single `date` column is what turns ~3M room types into ~1B rows/year (§3), and it's exactly what lets us say "the 12th is full but the 10th isn't."

**Sneaky column — `idempotency_key` on `reservations` and `payments`.** It has a `UNIQUE` constraint, and it prevents **accidental double-bookings from retries**:

```
Guest taps "Book" → request sent → phone's network hiccups → app auto-retries → SECOND request sent.
Both carry the SAME idempotency_key (generated once by the client).
First insert succeeds. Second hits UNIQUE(idempotency_key) → rejected → we return the EXISTING reservation.
Result: one booking, one charge, even though the request arrived twice.
```

```java
try {
    return db.insertReservation(res);            // idempotency_key column is UNIQUE
} catch (DuplicateKeyException e) {
    return db.findByIdempotencyKey(res.key);     // retry of an already-done booking → return the original
}
```

**Sneaky table #2 — `outbox`.** When a booking confirms, we must do two things: (a) save the reservation to the DB, and (b) publish a `RESERVATION_CONFIRMED` event to Kafka (so emails/search-index/analytics react). If we do them separately, a crash between them means we either saved-but-never-notified or notified-but-never-saved (the **dual-write problem**). The **outbox** trick: write the event into the `outbox` **table** *in the same transaction* as the reservation. A separate publisher later reads the outbox and pushes to Kafka. Since it's one transaction, the reservation and its "to-send" event can never disagree.

```
BEGIN TX
  INSERT reservation (CONFIRMED)
  INSERT outbox (event = RESERVATION_CONFIRMED)   -- same transaction → atomic
COMMIT
... a background publisher reads outbox rows → sends to Kafka → marks published = true
```

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

### The API is just the booking journey as URLs

Read the endpoints top-to-bottom and they retrace a guest's trip:

| Call | Guest's action | Behind the scenes |
| --- | --- | --- |
| `GET /v1/hotels?city=&checkIn=...` | "Show me Goa hotels for these dates." | Approximate search (§10). |
| `GET /v1/hotels/{id}/availability?...` | "Is *this* hotel actually free?" | Exact per-night check (§5). |
| `POST /v1/reservations` (`Idempotency-Key`) | "Book it." | Hold rooms + create `PENDING_PAYMENT` (§6). |
| `POST /v1/reservations/{id}/pay` | "Here's my card." | Charge → `CONFIRMED`, or release on failure. |
| `POST /v1/reservations/{id}/cancel` | "Never mind." | `CANCELLED` + release + refund (§8, §14). |
| `POST .../check-in` / `check-out` | Arrive / leave. | Assign physical room (§9) → `CHECKED_IN` → `COMPLETED`. |
| `PUT /v1/hotels/{id}/inventory` | (Manager) set counts/prices. | Admin-only; feeds `room_inventory`. |

#### Q: Why is `Idempotency-Key` a *header* on the create-reservation call?

Because creating a reservation is the one call that **must not happen twice** if the client retries (double charge, double room). The client generates a unique key once and sends it on every retry of that same intent; the server uses it (via the `UNIQUE` column from §11) to recognize "this is the same booking I already made" and return the original instead of making a new one. Read calls (`GET`) don't need it — repeating a search is harmless.

#### Q: Why split `pay`, `check-in`, `check-out` into separate calls instead of one big "book" call?

Because they happen at **different times, possibly days apart**, and each is its own state transition (§8). You pay now, check in next week, check out three days after that. Separate endpoints map cleanly to those real-world moments and let each be retried/handled independently.

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

### What a "saga" is, and why we need one

A booking touches **several services** — Inventory, Payment, then messaging. In a single database you'd wrap them in one transaction and "all or nothing" comes free. But these are **separate services** (separate databases), so there's no single transaction spanning them. A **saga** is the workaround: do the steps **one at a time**, and if a later step fails, run **compensating actions** to undo the earlier ones.

Across separate services there's no single "cancel everything" transaction. If a later step fails after earlier ones already committed, you must explicitly undo the earlier ones — that manual undo is **compensation**.

The booking saga, happy path vs failure:

```java
class BookingSaga {
    void execute(BookingRequest req) {
        // Step 1: reserve inventory (increment each night) — the forward action
        Reservation r = inventoryService.hold(req);        // compensation: release()
        try {
            // Step 2: take the money
            paymentService.charge(r);                       // compensation: refund()
            // Step 3: finalize + announce
            r.transitionTo(ReservationStatus.CONFIRMED);
            outbox.publish("RESERVATION_CONFIRMED", r);     // reliable event (§11 outbox)
        } catch (PaymentFailedException e) {
            // a later step failed → UNDO the earlier step
            inventoryService.release(r);                    // decrement each night — compensation
            r.transitionTo(ReservationStatus.EXPIRED);
        }
    }
}
```

The key idea: **there's no magic rollback across services** — *you* write the "undo" for each step. Here the only compensation usually needed is `release()` (give the rooms back) if payment fails after we held them.

#### Q: How is a saga different from the database transaction in §6?

Scope. The §6 transaction is **inside one database** (all the per-night increments) — the DB guarantees all-or-nothing automatically. The **saga** spans **multiple services** (inventory DB + payment provider + messaging), where no shared transaction exists, so we simulate all-or-nothing manually with compensations. In practice §6's transaction is *one step inside* the larger saga.

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

### The edge cases, as concrete hotel scenarios

These aren't abstract — each is a thing that genuinely happens at a front desk:

- **Two guests race the last room.** Alice and Bob tap "Book" together on the last Deluxe. The atomic conditional update (§6) lets exactly one increment through; the other gets 0 rows changed → "sorry, just sold out." No manager, no fistfight, no double-booking.
- **Abandoned checkout.** Someone holds a room, then closes the laptop to answer the door and never comes back. The **TTL sweeper** notices the hold is stale after ~10 min and frees the room — otherwise it'd be "sold" to nobody.
- **Payment fails.** Card declined after we held the rooms → the saga's **compensation** decrements each night, reservation → `EXPIRED`. Rooms are free again within seconds.
- **Duplicate booking tap.** Impatient double-tap or a network retry → same `idempotency_key` → the `UNIQUE` constraint means only one booking/charge sticks (§11).
- **Cancellation.** Guest cancels → state → `CANCELLED`, inventory released, refund computed by the **cancellation policy** (free if early, penalty if last-minute — a swappable **Strategy**, §15).
- **No-show.** Guest never arrives → charged per policy; the **overbook buffer** (§7) usually means the room was resold anyway.
- **Partial availability over a range.** Want 3 nights but night 2 is full → the whole stay is rejected (all-or-nothing). We can't sell someone "2 of your 3 nights."

```java
// Refund amount depends on WHEN you cancel — classic Strategy pattern
int refundFor(Reservation r, CancellationPolicy policy, Instant now) {
    long hoursBefore = Duration.between(now, r.checkIn.atStartOfDay(...)).toHours();
    if (hoursBefore >= policy.freeUntilHours) {
        return r.totalAmount;                                   // full refund — cancelled early
    }
    return r.totalAmount * (100 - policy.penaltyPct) / 100;     // partial — cancelled late
}
```

#### Q: Which of these is the "strong consistency" one everyone talks about?

**"Two guests race the last room"** and **"overbooking"** — those are the writes that must be *exactly* right, so they go through the strongly-consistent RDBMS path with the atomic conditional update. Everything read-side (search showing a stale sold-out hotel) is allowed to be eventually consistent, because booking re-checks for real. That split — **strict on the money/inventory write, relaxed on browse reads** — is the whole consistency philosophy of the design.

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

### The patterns, each in one line + where it applies

Patterns sound academic; here each is tied to a concrete hotel moment:

| Pattern | One-line meaning | The hotel moment |
| --- | --- | --- |
| **Saga** | Multi-step process with undo steps. | Reserve → pay → confirm; undo (release rooms) if payment fails. |
| **State** | Only legal status transitions allowed. | The reservation lifecycle (§8) — can't go `COMPLETED → CHECKED_IN`. |
| **Optimistic Locking / CAS** | "Update only if the value is still what I expect." | `WHERE booked_count < total_count` — stops the double-book. |
| **Strategy** | Swap a rule without changing the caller. | Pricing (seasonal), cancellation refund %, room-assignment logic. |
| **Outbox** | Save the "to-send" event in the same DB transaction. | `RESERVATION_CONFIRMED` reliably reaches Kafka (§11). |
| **CQRS** | Separate read store from write store. | Search on Elasticsearch, booking on RDBMS (§4). |
| **Decorator / Chain** | Stack small transformations. | Final price = base + season + taxes + fees − discount. |
| **Repository / Factory** | Hide data access / object creation behind an interface. | Swap DB or payment provider without touching business logic. |

The **Decorator/Chain** for pricing, made concrete — each layer wraps the previous:

```java
interface PriceRule { int apply(int runningPrice, BookingContext ctx); }

// each rule takes the price so far and returns a new one — stackable in any order
PriceRule seasonal = (p, ctx) -> ctx.isPeakSeason() ? (int)(p * 1.5) : p;   // +50% in peak
PriceRule taxes    = (p, ctx) -> (int)(p * 1.18);                            // +18% tax
PriceRule discount = (p, ctx) -> ctx.hasCoupon()   ? p - ctx.couponValue() : p;

int finalPrice = List.of(seasonal, taxes, discount)
    .stream()
    .reduce(basePrice, (price, rule) -> rule.apply(price, ctx), (a, b) -> b);
```

#### Q: Why so many patterns — isn't this over-engineering?

Each pattern here earns its place by absorbing a *specific* real-world messiness: distributed steps that can fail (**Saga**), illegal status jumps (**State**), concurrent last-room fights (**CAS**), rules that change per hotel/season (**Strategy**), and unreliable cross-service messaging (**Outbox**). In an interview you don't need all of them — but naming the *right one for the problem being discussed* shows you recognize the underlying shape.

---

## 16. Scaling & Failure

- **Search** off Elasticsearch + cache; never scan inventory for every hotel; approximate, exact re-check at booking.
- **Booking** on RDBMS with strong consistency; **shard by `hotel_id`**; prune past-date inventory rows.
- **Hold + TTL** during checkout → abandoned carts release inventory; **saga compensation** on payment failure.
- **Idempotency key** prevents double booking on retry.
- **Overbooking buffer** (business choice) raises occupancy; **walk policy** handles the rare over-capacity night.
- **Dynamic pricing** job updates `room_inventory.price` per night by demand/season.

### How it survives growth and failure

The two big scaling worries and their fixes:

- **"Search traffic explodes on holidays."** Search never touches the booking database — it hits Elasticsearch + a cache. Both are easy to **replicate** (add more read copies), so a browse spike doesn't threaten the correctness core.
- **"The inventory table is enormous / one region gets hammered."** **Shard by `hotel_id`** — split the data across many database machines by hotel. Since a booking only ever touches **one** hotel's rows, a booking never needs to span shards, so it stays a clean single-database transaction.

```
Shard by hotel_id:
   Shard A: hotels 1..1M      ┐
   Shard B: hotels 1M..2M     ├─ a booking for hotel #7 only ever touches Shard A → simple, fast, no cross-shard transaction
   Shard C: hotels 2M..3M     ┘
```

Sharding by hotel keeps every booking self-contained: all nights of a booking live on the same shard, so the "increment each night, all-or-nothing" write stays a plain local transaction.

#### Q: Why shard by `hotel_id` specifically, and not by, say, `guest_id` or `date`?

Because the **atomic booking transaction (§6) is scoped to a single hotel**. If you sharded by `guest_id`, one hotel's inventory would be scattered across many shards and a booking would need a slow, fragile cross-shard transaction. Sharding by `hotel_id` keeps every night of a booking on the **same** shard, so the "increment each night, all-or-nothing" write stays a plain local transaction. Choose the shard key that keeps your **most critical transaction** on one shard.

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

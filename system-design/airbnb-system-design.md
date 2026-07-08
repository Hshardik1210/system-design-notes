# Airbnb — System Design (Lodging Marketplace)

> **Core challenge:** a **two-sided marketplace** connecting **hosts** (who list properties) with **guests** (who book them). Combines **geo + faceted search**, **date-range availability booking** (no double-booking), **payments with host payouts (escrow)**, **two-way reviews**, and **messaging** — plus **request-to-book vs instant-book**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code/SQL, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

Airbnb is a marketplace where many independent hosts list places and guests book them for specific date ranges.

- **Hosts** are individuals (not a hotel company) listing an apartment, cabin, treehouse — each place is usually **one of a kind** (count = 1).
- **Guests** query "somewhere in Goa, for *these 4 nights*, for *2 people*" and want only listings that match the filters **and are still free on those exact nights**.
- When a guest books a listing, those nights must become **unavailable** so nobody else takes the same dates. That's the whole ballgame.

So the system does four jobs: (1) let hosts **list** places, (2) let guests **search** by place + dates + filters, (3) **book** without ever letting two guests take the same nights, and (4) handle the **money and trust** (payments, payouts, reviews) so strangers feel safe transacting.

### Why is this harder than a normal shopping site?

On Amazon, if 5 people buy "the same t-shirt," that's fine — there are thousands in the warehouse; you just decrement stock. On Airbnb, a listing is **a single thing tied to specific dates**:

- You aren't selling "a product," you're selling **"this exact apartment, on these exact nights."**
- Two guests wanting *July 10–14* is a **conflict** — only one can win, and the loser must be told "sorry, gone."
- And you can't just check "is it free?" then book — between the check and the book, someone else might grab it (a **race condition**). This is why booking needs **strong consistency**, covered in §6.

**Key insight that drives the whole design:**

> **Searching is cheap and can be a little stale; booking is sacred and must be exact.** So we split into a fast, approximate **search side** and a slow, bulletproof **booking side** (this is the CQRS split in §4).

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

### Two copies of the truth, on purpose

The confusing part of this diagram: **listing data lives in two places** — the RDBMS (Listing/Booking services) *and* Elasticsearch (Search). Why duplicate?

The RDBMS is the **authority**: it records exactly what's booked and owed, transactionally (must be correct). Elasticsearch is a **rebuilt-for-speed copy** used to browse by topic/filters fast. The search index is a *convenient copy* built for querying; the RDBMS is the *authority* for what's actually booked.

- **RDBMS (write side)** = the authority. When a booking happens, it's written here, transactionally. Money and calendars are never wrong here.
- **Elasticsearch (read side)** = a rebuilt-for-speed copy, optimized for "places near this map area, under ₹5000, with wifi, free next weekend." It can be a few seconds stale — that's fine.
- **CQRS** = **C**ommand **Q**uery **R**esponsibility **S**egregation = a fancy name for "**writes go to one model, reads come from a different model.**"

**How does the search copy stay fresh? CDC + Kafka.** When a host edits a listing or a night gets booked in the RDBMS, that change is captured (**C**hange **D**ata **C**apture) and published to Kafka; the search indexer consumes it and updates Elasticsearch.

```
Host edits price / a night gets booked
        │  (write)
        ▼
     RDBMS  ──CDC──►  Kafka (LISTING_UPDATED)  ──►  Search indexer  ──►  Elasticsearch
   (authority)                                                          (fast, ~seconds stale)
```

#### Q: If search can be stale, won't a guest sometimes book a place that's actually taken?

Yes — and that's *allowed*. Search is **best-effort**: it might show a listing that got booked 2 seconds ago. The safety net is that the **booking step re-checks the real calendar in the RDBMS** and rejects the stale one (see §6). Search gets you *candidates*; booking is the *authority*. Trying to make search perfectly live would make it slow and fragile for no real benefit.

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

### How does "near this location" even work?

A computer can't natively answer "which of 7 million listings are near *here*?" without a trick — otherwise it would measure the distance to all 7M for every search. Two common tricks:

- **Bounding box (map viewport):** when you drag the Airbnb map, the app knows the corners of the visible rectangle (min/max latitude & longitude). Search becomes "give me listings with `lat BETWEEN … AND …` and `lng BETWEEN … AND …`" — a simple range filter Elasticsearch does fast.
- **Geohash:** encode a lat/lng into a short string where **nearby places share a prefix**. `tdr1y` and `tdr1z` are neighbours. So "near me" ≈ "starts with `tdr1`" — a cheap prefix match instead of distance math to everything.

A geohash gets more precise as you add characters — a shorter prefix covers a larger area, a longer one a smaller area. Places with the same prefix are physically close, so you filter by prefix first, then fine-sort by exact distance.

```
lat/lng: 12.93, 77.62
   → geohash "tdr1y7"        (whole string = a tiny ~150m box)
   → prefix  "tdr1"          (a bigger neighbourhood box)

"places near me" ≈ WHERE geohash LIKE 'tdr1%'   ← cheap, no distance-to-everything
```

### The availability filter — why it's the hard part, and the bitset trick

You'd think "only show places free July 10–14" is easy. It's the **hardest** part, because the calendar has ~2.5 **billion** rows (7M listings × 365 days). Joining that to every search is impossible at scale.

**The trick: bake availability into the search index as a bitset.** For each listing, store a row of 0/1 bits, one per upcoming day: `1` = free, `0` = taken. A date-range search just checks "are all the bits for July 10–14 set to 1?" — a fixed-width bitwise check instead of scanning a calendar table.

```
Listing 42, next 10 days:   [1 1 0 0 1 1 1 1 1 0]
                             ^Jul8            ^Jul17
                                 └Jul10–13 are 0 0 1 1 → night Jul10 & 11 taken → REJECT
```

```java
// A listing's availability as a bitmask over the next N days (day 0 = today).
// bit = 1 means "free that night". Stored denormalized in the search index.
long availabilityMask; // e.g. 64 bits = next 64 nights

// Does this listing have ALL nights free in [checkInOffset, checkOutOffset)?
boolean isFreeForRange(long mask, int checkInOffset, int checkOutOffset) {
    int nights = checkOutOffset - checkInOffset;
    // build a mask of `nights` 1-bits, shifted to the check-in position:
    long wanted = ((1L << nights) - 1) << checkInOffset;   // e.g. 0b1111 << 2
    // all wanted nights are free  ⟺  every wanted bit is also set in the listing mask
    return (mask & wanted) == wanted;
}
```

This is **approximate** on purpose (the index lags reality by a few seconds via CDC), so the real calendar is re-checked at booking time (§6).

#### Q: Why not just query the calendar table with `WHERE date BETWEEN … AND status='AVAILABLE'`?

For **one** listing's detail page, you absolutely do (that read is small and exact). The problem is doing it for **every search over millions of listings at once** — that's the 2.5B-row join that melts. The bitset moves a compact, pre-computed answer *into* the search index so the filter is a cheap bitwise AND, not a giant join.

#### Q: What if the bitset says "free" but it just got booked?

Then the booking step rejects it (§6). Search is a fast **shortlist**; being occasionally wrong is acceptable because booking is the authority. The bitset just needs to be *mostly* fresh (short staleness), which CDC provides.

#### Q: How do filters like "wifi, pool, under ₹5000, superhost" work?

Those are **facets** — Elasticsearch indexes them as fields, so `amenities: ["wifi","pool"]`, `price <= 5000`, `is_superhost: true` all become filters combined with the geo + availability filters in one query. Facets are cheap because ES is built for exactly this kind of multi-field filtering.

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

### How do we stop two people booking the same nights?

This is *the* Airbnb interview question. The naive approach and why it fails:

```java
// ❌ THE CLASSIC BUG: check-then-act (a "race condition")
if (calendar.allNightsFree(listingId, checkIn, checkOut)) {   // step 1: look
    // ...another guest sneaks in RIGHT HERE and books the same nights...
    calendar.markBooked(listingId, checkIn, checkOut);        // step 2: grab
}
```

Between "look" (step 1) and "grab" (step 2), a second guest can do their own "look" and also see it free. **Both** proceed → double-booked. The gap between checking and acting is the enemy.

The fix is to make **check-and-claim a single, un-interruptible operation** so only one transaction can win: one flips the nights atomically; the other finds them already taken.

**The fix: one atomic conditional UPDATE.** Ask the database to flip the nights to `BOOKED` *only where they're still `AVAILABLE`*, in a single statement, and then check how many nights it actually changed:

```sql
BEGIN;

-- Atomically claim EVERY night in [checkIn, checkOut), but ONLY the free ones.
-- The DB locks these rows for us; two transactions can't both succeed.
UPDATE listing_calendar
   SET status = 'BOOKED', booking_id = :bookingId
 WHERE listing_id = :listingId
   AND date >= :checkIn AND date < :checkOut   -- note: checkout night is NOT occupied
   AND status = 'AVAILABLE';                    -- the guard: skip any taken night

-- How many nights did we actually flip?
--   nights_wanted = checkOut - checkIn
-- If we flipped fewer than that, SOME night was already taken → abort everything.
-- (application checks rows_affected below)

COMMIT;   -- only if rows_affected == nights_wanted
```

```java
int nightsWanted = (int) DAYS.between(checkIn, checkOut);
int rowsAffected = jdbc.update(CLAIM_NIGHTS_SQL, bookingId, listingId, checkIn, checkOut);

if (rowsAffected != nightsWanted) {
    // at least one night wasn't AVAILABLE → someone else has part of this range
    tx.rollback();                 // undo the partial claim — all-or-nothing
    return BookingResult.NOT_AVAILABLE;
}
tx.commit();                       // we got ALL nights, cleanly
insertBooking(bookingId, PENDING_PAYMENT);
```

Why this is bulletproof: the database only lets **one** transaction modify a given row at a time. If two guests race for July 10–14, one `UPDATE` flips all 4 rows first; the other's `UPDATE` matches **0** of those rows (they're no longer `AVAILABLE`) → `rows_affected < nights` → it rolls back and gets `NOT_AVAILABLE`. **No gap, no double-book.**

### The "hold" — why booking isn't instant

A guest clicks "book," but then spends 3 minutes typing card details (or the host takes hours to approve a request). We can't leave the nights free (someone else grabs them) *or* mark them permanently `BOOKED` (payment might fail). Middle ground: a **temporary HELD status with an expiry** — the nights are reserved for a fixed window, and released automatically if payment/approval doesn't complete in time.

```java
// Step 1: claim nights as HELD with a deadline (not BOOKED yet)
UPDATE listing_calendar
   SET status='HELD', hold_expiry = now() + interval '15 minutes', booking_id=:id
 WHERE listing_id=:lid AND date >= :in AND date < :out AND status='AVAILABLE';
// (same rows_affected == nights check as above)

// Step 2a: payment succeeds → promote HELD → BOOKED
// Step 2b: payment fails / never happens → a background "sweeper" frees expired holds:
UPDATE listing_calendar
   SET status='AVAILABLE', hold_expiry=NULL, booking_id=NULL
 WHERE status='HELD' AND hold_expiry < now();   // runs every minute
```

#### Q: Why lock every *night* instead of the whole listing?

Because two guests booking **non-overlapping** dates (Alice: Jul 1–3, Bob: Jul 10–12) should **both** succeed — they don't conflict. Locking per-night lets unrelated dates proceed in parallel; only *overlapping* nights ever contend. Locking the whole listing would needlessly block everyone.

#### Q: Why is the range `[checkIn, checkOut)` — checkout excluded?

Because the guest **leaves** on the checkout morning, so that night is free for the next guest. If Alice books "Jul 10 → Jul 14," she sleeps nights 10, 11, 12, 13 (four nights) and is gone on the 14th — so Bob can check in on the 14th. Marking the checkout night as occupied would wrongly block a legal booking.

#### Q: Is this "optimistic" or "pessimistic" locking?

This is closest to **optimistic / compare-and-set (CAS)**: we don't lock upfront and make others wait; we just *attempt* the conditional update and detect failure via `rows_affected != nights`. The loser isn't blocked — it's immediately told "no," and can retry with different dates. (A pessimistic alternative would `SELECT … FOR UPDATE` the rows first; the conditional-update style is the cleaner interview answer.)

#### Q: Per-night rows vs storing date *ranges* — which is better?

- **Per-night rows** (one row per listing per date): dead simple, the `WHERE status='AVAILABLE'` trick just works, easy to reason about. Downside: lots of rows (2.5B/year) — prune past dates.
- **Interval model** (store booked ranges + a DB "exclusion constraint" that forbids overlaps): far more compact, but the overlap logic is trickier and DB-specific. **Use per-night for interviews**; mention intervals as the compact optimization.

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

### The booking state machine

A booking is always in exactly one clearly-labelled state, and it can only move along legal transitions — it can't jump from "just requested" straight to "completed." That's a **state machine**: a fixed set of states + rules for which transitions are allowed.

- **REQUESTED** — guest asked; waiting for the host to say yes (request-to-book only).
- **PENDING_PAYMENT** — approved (or instant-book); waiting for the card to go through.
- **CONFIRMED** — paid & locked in; the stay is really happening.
- **COMPLETED** — guest checked out; now reviews + host payout can happen.
- **DECLINED / EXPIRED / CANCELLED** — dead ends; each one **must release the held nights** back to `AVAILABLE`.

**Why bother with explicit states?** So illegal things are *impossible by construction*. You can't pay for a booking the host already declined; you can't cancel one that never got confirmed. Each transition is guarded.

```java
enum BookingStatus { REQUESTED, PENDING_PAYMENT, CONFIRMED, COMPLETED,
                     DECLINED, EXPIRED, CANCELLED }

// Only these moves are legal; anything else throws.
static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
    REQUESTED,        Set.of(PENDING_PAYMENT, DECLINED, EXPIRED),
    PENDING_PAYMENT,  Set.of(CONFIRMED, EXPIRED),
    CONFIRMED,        Set.of(COMPLETED, CANCELLED)
    // COMPLETED / DECLINED / EXPIRED / CANCELLED are terminal (no exits)
);

void transition(Booking b, BookingStatus next) {
    if (!ALLOWED.getOrDefault(b.status, Set.of()).contains(next))
        throw new IllegalStateException(b.status + " ✗→ " + next);
    b.status = next;
    if (isTerminalFail(next))       // DECLINED / EXPIRED / CANCELLED
        releaseHeldNights(b);       // ALWAYS free the calendar — the "undo" step
}
```

#### Q: What happens to the calendar nights when a booking dies?

Every failure exit runs the **same cleanup**: flip those nights from `HELD`/`BOOKED` back to `AVAILABLE` so other guests can book them. This "undo the earlier step" is called **compensation** (it's the heart of the saga in §8). Forgetting it = nights stuck locked forever = lost revenue and angry hosts.

#### Q: How is instant-book different in this picture?

Instant-book simply **skips the REQUESTED state** — there's no host to wait for. It jumps straight to PENDING_PAYMENT → CONFIRMED. Same machine, one fewer stop.

---

## 8. Payments & Host Payouts (escrow)

- **Charge the guest** on confirm (instant) or at host acceptance (request). **Escrow** the funds.
- **Release payout to the host** ~24h after check-in (minus platform fee) → protects the guest if the place is misrepresented.
- **Double-entry ledger:** guest charge → platform commission + host payable → payout (see Payment System note).
- **Refunds** per **cancellation policy** (flexible/moderate/strict = Strategy).
- **Idempotent** payments + **outbox** so a confirmed booking reliably triggers charge + eventual payout; multi-currency + FX.

### Airbnb holds the money in the middle (escrow)

Airbnb does **not** pass your payment straight to the host. It **charges the guest, holds the cash itself, and only pays the host ~24h after check-in.**

This is **escrow**: the platform holds the funds and only releases them to the host once the guest has checked in and the stay isn't disputed. If the listing was misrepresented, Airbnb still holds the money and can refund the guest — which is what lets two parties who don't know each other transact safely.

```
Guest pays ₹10,000  ──►  Airbnb escrow (holds it)
                              │  guest checks in, ~24h passes, no complaint
                              ▼
                         pay host ₹8,500   (₹10,000 − ₹1,500 platform fee)
```

### Double-entry ledger (money is never a single number)

Real money systems never store "host balance = ₹8,500" as one editable number — too easy to lose track / cheat. They record **every movement as paired entries that must sum to zero** (money leaves one bucket, enters another). This is **double-entry bookkeeping**.

```java
// A guest pays ₹10,000; ₹1,500 is Airbnb's fee, ₹8,500 owed to host.
// Every transaction is a set of entries whose deltas net to zero.
ledger.record(bookingId, List.of(
    new Entry("guest:cash",      -10_000),  // guest paid out
    new Entry("airbnb:escrow",    +8_500),  // held for host
    new Entry("airbnb:revenue",   +1_500)   //           -10000 + 8500 + 1500 = 0 ✓
));

// Later, at payout time (24h after check-in):
ledger.record(bookingId, List.of(
    new Entry("airbnb:escrow",    -8_500),
    new Entry("host:payable",     +8_500)   // now owed to the host's bank
));
```

The invariant "**all entries for a transaction sum to zero**" means money can never be created or destroyed by a bug — a powerful audit/consistency guarantee.

### How the price is built up (stacking fees)

The number a guest pays isn't one figure — it's **base nightly price × nights, plus cleaning fee, plus service fee, plus taxes, minus discounts.** Each piece is applied as a layer on top of the running total, so you can insert/remove a layer (a promo, a new tax) without rewriting the whole calculation. That "wrap the running total with one more adjustment" shape is the **Decorator / Chain** pattern.

```java
// Each rule takes the running total and returns a new total. Order matters.
interface PriceRule { int apply(int runningTotal, BookingCtx ctx); }

int quote(BookingCtx ctx) {
    int nights = (int) DAYS.between(ctx.checkIn, ctx.checkOut);
    int total  = ctx.basePricePerNight * nights;   // 1. base: ₹8000 × 4 = 32000

    total += ctx.cleaningFee;                       // 2. + cleaning     = 34000
    total += (int) (total * 0.12);                  // 3. + 12% service  = 38080
    total += (int) (total * 0.05);                  // 4. + 5% tax       = 39984
    total -= ctx.discount;                          // 5. − promo        = 39984 − 984
    return total;                                   // final: 39000
}
```

Storing these as swappable **pricing_rules** (or seasonal/weekend surge rules) lets pricing change without redeploys — the same "config-driven, no deploy" idea used for hot ads elsewhere.

#### Q: Where does the price live — listing or calendar?

Both. The listing has a `base_price`, but `listing_calendar` also has a per-night `price`, so a host can charge more on weekends/holidays or a specific date. The quote sums the *per-night* calendar prices when they differ from the base.

### Idempotency — why the same tap doesn't charge twice

The guest taps "Pay" and the network stalls, so they tap again. Without protection, that's **two charges**. Fix: the client sends a unique **Idempotency-Key** with the request; the server remembers keys it has already processed and returns the *same* result for a repeat instead of charging again. The key identifies the request, so repeats of the same key resolve to a single transaction.

```java
@PostMapping("/v1/bookings")
Booking book(@RequestHeader("Idempotency-Key") String key, @RequestBody BookReq req) {
    Booking existing = bookings.findByIdempotencyKey(key);
    if (existing != null) return existing;    // duplicate tap → return the FIRST result, don't redo
    return createBookingAndCharge(key, req);  // first time → actually do it
}
```

In SQL this is backed by `idempotency_key VARCHAR(255) UNIQUE` on `bookings`/`payments` — a duplicate insert simply fails the unique constraint.

### The outbox — making "confirm booking" and "tell everyone" reliable

When a booking confirms, several things must follow: charge, notify host, update search index, schedule payout. Danger: you save the booking, then crash *before* sending the Kafka event → booking exists but nobody was told (a **dual-write** problem).

**Fix — the Outbox pattern:** in the *same transaction* that writes the booking, also write the event into an `outbox` table. A separate publisher reads the outbox and pushes to Kafka, marking rows `published`. Since both writes share one transaction, they **both** happen or **neither** does — no lost events.

```java
@Transactional
void confirmBooking(Booking b) {
    bookings.updateStatus(b.id, CONFIRMED);        // (1) business write
    outbox.insert("BOOKING_CONFIRMED", toJson(b)); // (2) event, SAME transaction
}   // commit → both saved atomically; a poller later ships (2) to Kafka
```

#### Q: Why pay the host *after* check-in instead of immediately?

To protect the **guest**. If the host misrepresented the place, the money is still in escrow and can be refunded. Paying upfront would remove that leverage and gut trust on the platform.

#### Q: What happens on a cancellation/refund?

The **cancellation policy** (FLEXIBLE / MODERATE / STRICT) decides how much is refunded — this is a swappable **Strategy**. The booking moves to `CANCELLED`, nights are released, and the ledger records the refund entries. Flexible = full refund near the date; strict = little/none.

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

### How the tables fit together

Read the schema as the lifecycle of a booking, not a pile of tables:

- A **user** who is a host owns **listings** (with **listing_photos**).
- Each listing has a **listing_calendar** — *one row per night* — the star of the show (§6).
- A guest creates a **booking** over a date range; a **payment** charges them; a **payout** later pays the host.
- After the stay, each side writes a **review**; throughout, they chat via **conversations** + **messages**.
- **outbox** reliably fans out events (§8); **cancellation_policies** decides refunds.

#### Q: Why is `listing_calendar` keyed by `(listing_id, date)` instead of having a booking store its own range?

Because the calendar is what we **lock against** to prevent double-booking. Having a concrete row per night lets the atomic `WHERE status='AVAILABLE'` claim (§6) work night-by-night. The `bookings` table stores the *range* (`check_in`, `check_out`) for display/history; the *truth about which nights are free* lives in `listing_calendar`.

```sql
-- The booking row: the guest-facing summary (range, price, status)
INSERT INTO bookings (booking_id, idempotency_key, listing_id, guest_id,
                      check_in, check_out, guests, total_amount, status)
VALUES (9001, 'tap-abc-123', 42, 777, '2026-07-10', '2026-07-14', 2, 40000, 'PENDING_PAYMENT');

-- The calendar rows it locks: FOUR nights (10,11,12,13) — checkout night 14 stays free
SELECT date, status FROM listing_calendar
 WHERE listing_id = 42 AND date >= '2026-07-10' AND date < '2026-07-14';
--  2026-07-10 | BOOKED
--  2026-07-11 | BOOKED
--  2026-07-12 | BOOKED
--  2026-07-13 | BOOKED     ← booking_id = 9001 on each
```

#### Q: Why store `geohash` on the listing *and* use Elasticsearch — isn't that redundant?

The RDBMS `geohash` (with `idx_listing_geohash`) supports simple/exact lookups and is the source of truth; **Elasticsearch** is the *rebuilt copy* tuned for fast multi-filter search (geo + facets + availability bitset). Same data, two shapes, per CQRS (§4).

#### Q: How do reviews stay honest (two-way)?

The `reviews` table has a `role` (`GUEST_REVIEWS_HOST` / `HOST_REVIEWS_GUEST`) tied to a real `booking_id`. Because a review requires a completed booking, you can't review a place you never stayed at. Airbnb also famously **hides both reviews until both are submitted** (or a window passes) so neither side retaliates — a nice detail to mention.

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

### Strong where it matters, relaxed where it doesn't

The single biggest idea tying this doc together: **not everything needs to be perfectly consistent.** Airbnb deliberately picks per feature:

| Part | Consistency | Why it's OK |
| --- | --- | --- |
| **Booking / calendar** | **Strong** (must be exact) | Money + no double-booking; a wrong answer is a disaster |
| **Search results / availability bitset** | **Eventual** (seconds stale) | A stale card is harmless — booking re-checks and rejects it |
| **Review counts, ratings on cards** | **Eventual** | Nobody's harmed if a rating updates a few seconds late |

The pattern: an account balance must be exact to the paisa (strong), while a "customers also viewed" widget can lag (eventual). Same app, different guarantees on purpose — you pay for strong consistency with speed/complexity, so you only buy it where it matters.

#### Q: Two guests race for the exact same dates — who wins and how does the loser find out?

The database serializes the two atomic `UPDATE`s (§6): the first flips all the nights, the second matches **0** free rows → `rows_affected < nights` → the loser's transaction rolls back and immediately gets `NOT_AVAILABLE`. No lock waiting, no double-book — the loser just retries with other dates.

#### Q: A guest abandons checkout — do the nights stay locked forever?

No. They were only `HELD` with a `hold_expiry`. A background **sweeper** periodically flips expired holds back to `AVAILABLE` (§6). This is the **saga compensation** — an automatic "undo" for a step that never completed.

#### Q: Why is it fine that search sometimes shows a place that's actually booked?

Because search is a **best-effort shortlist**, and the **booking write is the authority**. Making search perfectly live would require querying the transactional DB on every search (slow, fragile) for a problem the booking re-check already solves cheaply. Accepting rare stale cards is the right trade.

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

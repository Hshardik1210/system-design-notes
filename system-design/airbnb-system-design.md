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
- [12. Consistency & CAP Tradeoffs](#12-consistency--cap-tradeoffs)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Reviews & Trust](#14-reviews--trust)
- [15. Messaging](#15-messaging)
- [16. Reliability & Observability](#16-reliability--observability)
- [17. How to Drive the Interview (framework)](#17-how-to-drive-the-interview-framework)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Final Takeaways](#20-final-takeaways)

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

> 💡 **Always start the interview here.** Clarify scope out loud before designing — it frames every later decision and signals seniority. Say the core out loud: *"a two-sided marketplace where the sacred invariant is **no double-booking**."*

**Functional**

- Host: create/manage listings, set calendar availability + pricing, accept/decline requests.
- Guest: search (location, dates, guests, filters), view listing, book (instant/request), pay, review.
- Messaging host↔guest; cancellations/refunds per policy; wishlists.

### Non-functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Strong** on the booking/calendar write (no double-booking) and on money (ledger). **Eventual** is fine for search, availability bitset, review counts. |
| **Availability** | High for search/browse; the booking write may favor **consistency over availability** (CP for the calendar claim). |
| **Latency** | Search < 200ms (map viewport + facets); booking claim should feel instant. |
| **Scale** | Read-heavy: search ≫ bookings (**~1000:1**). Spiky traffic on popular destinations/dates. |
| **Durability** | A confirmed booking and every ledger entry must never be lost. |
| **Global** | Multi-currency + FX, i18n, regional data locality. |
| **Trust & safety** | Verified stays, two-way reviews, blocking/reporting, anti-fraud on payments. |

### Out of scope (state assumptions)

- Recommendations/personalized ranking internals (ML black box), pricing optimization, tax/regulatory compliance engines, dispute-resolution UI, Experiences/co-hosting. Mention, then defer.

---



## 3. Capacity Estimation

```
Listings ~ 7M · users ~ 100M's · searches ≫ bookings (browse-heavy, ~1000:1)
Search QPS (peak) ~ tens of thousands/sec → Elasticsearch + cache
Bookings ~ modest write rate but MUST be correct (money + no double-book)
Calendar rows: 7M listings × 365 days ≈ 2.5B rows/year → partition/prune past dates
Storage: listings + calendar + bookings; photos → blob/CDN (the bulk of bytes)
```

> Numbers are illustrative — the point is to **show the method**, not be exact.

### Reading the math (what each number drives)

| Quantity | Rough estimate | How you get there | What it drives in the design |
| --- | --- | --- | --- |
| **Search QPS (peak)** | ~tens of thousands/sec | ~1000 searches per booking, spiky on popular dates | ES read model + Redis cache + CDN; **never** hit the write DB on search |
| **Booking write rate** | modest (~hundreds/sec peak) | 1000:1 search:booking ratio | one RDBMS primary suffices; hard part is **correctness on hot dates**, not throughput |
| **Calendar write rate** | ~nights-per-booking × bookings | avg ~4 nights/booking flipped `AVAILABLE→BOOKED` | per-night rows + atomic claim; shard by `listing_id` so a claim stays on one shard |
| **Calendar rows** | ~2.5B/year | 7M listings × 365 nights | **partition by month + drop old partitions** (don't `DELETE` billions of rows) |
| **Availability bitset** | ~320 MB total | 365 bits (~46 B) × 7M listings | fits in the ES index/memory → cheap in-index date filter (§5) |
| **Photo storage** | the bulk of bytes | ~20–30 images/listing × millions | **blob + CDN**, never the DB |

> Browse/search dominates → an ES read model + cache. Bookings are low-volume but **strongly consistent** (correctness over throughput). The takeaway that drives everything: **scale reads with replicas/ES/cache; protect one strongly-consistent write path for money + calendar.**

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

Yes — and that's *allowed*. Search is **best-effort**: it might show a listing that got booked 2 seconds ago (the same applies to the availability bitset in §5 — it's precomputed from the last CDC update, so it can say "free" for a night that just got claimed). The safety net is that the **booking step re-checks the real calendar in the RDBMS** and rejects the stale one (see §6). Search gets you *candidates*; booking is the *authority* — it only needs to be *mostly* fresh, which CDC provides. Trying to make search perfectly live would make it slow and fragile for no real benefit.

---



## 5. Search & Discovery (with availability)

The dominant read path: **location + date range + guests + filters** (price, type, amenities, superhost, instant-book).


| Concern                 | Approach                                                                          |
| ----------------------- | --------------------------------------------------------------------------------- |
| **Geo**                 | Geohash/S2/QuadTree, or Elasticsearch `geo_bounding_box` for the **map viewport** |
| **Faceted filters**     | Elasticsearch (amenities, price band, type, instant-book)                         |
| **Availability filter** | **Hard at scale** — can't join a 2.5B-row calendar per search                     |
| **Ranking**             | Relevance + price + quality + personalization (ML black box)                      |
| **Freshness**           | ES index rebuilt from listings/calendar via **CDC/Kafka**                         |


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

#### The prefix length controls how "zoomed in" you are

A geohash is **not fixed-length**. The more characters, the smaller (more precise) the box:

| Geohash           | Box size (roughly) | 12.93, 12.94, 12.95 → ?           |
| ----------------- | ------------------ | --------------------------------- |
| `t` (1 char)      | ~5000 km           | all same                          |
| `tdr1` (4 chars)  | ~20 km             | all same (same neighbourhood)     |
| `tdr1y7` (6 chars)| ~1 km              | probably still same or adjacent   |
| `tdr1y7abc` (9)   | ~5 m               | now they differ                   |

So when you worry *"12.93, 12.94, 12.95 all get the same prefix"* — yes, at a **short** prefix they do, **because they genuinely are near each other** (a few km apart). If you need to tell them apart, you just use a **longer** geohash. Precision is a dial you turn by adding characters — sharing a prefix is the feature, not a bug.

#### How you actually use it in search

You don't pick "the" prefix per listing. You store the **full, long** geohash per listing, then query with a prefix whose length matches how big an area you're searching:

```
Guest is looking at a ~20km city view
  → compute the geohash of the map center, take 4 chars → "tdr1"
  → WHERE geohash LIKE 'tdr1%'    ← returns everything in that ~20km box

Guest zooms into a street (~1km)
  → take 6 chars → "tdr1y7"
  → WHERE geohash LIKE 'tdr1y7%'  ← much tighter set
```

So the flow is: **prefix-match to get a cheap shortlist → then compute exact distance only on that small shortlist and sort.** You never measure distance to all 7M; you first throw away 99.99% with a string prefix compare.

#### One correction to a common mental model

A geohash is **not** just "encode the latitude." It **interleaves latitude AND longitude bits together**, so a single prefix constrains *both* dimensions at once — that's why `LIKE 'tdr1%'` gives you a 2D box, not a horizontal stripe.

#### The one real gotcha (the "edge problem")

The genuine weakness of geohash prefixes: two points can be **physically adjacent but sit on opposite sides of a box boundary**, so their prefixes differ. Example: something at the very edge of `tdr1` and its neighbour just across the line in `tdr4` are 10 m apart but share no 4-char prefix. The standard fix is to **also query the 8 neighbouring cells** of your center cell, not just the one prefix. That's why real systems often prefer **S2 / QuadTree** (mentioned in the §5 table) or Elasticsearch's built-in `geo_bounding_box`, which sidestep the boundary issue.



### The availability filter — why it's the hard part, and the bitset trick

You'd think "only show places free July 10–14" is easy. It's the **hardest** part, because the calendar has ~2.5 **billion** rows (7M listings × 365 days). Joining that to every search is impossible at scale.

**The trick: bake availability into the search index as a bitset.** For each listing, store a row of 0/1 bits, one per upcoming day: `1` = free, `0` = taken. A date-range search just checks "are all the bits for July 10–14 set to 1?" — a fixed-width bitwise check instead of scanning a calendar table.

**Concrete 7-day example.** Say today is **Jul 8** and listing 42's next 7 nights look like this (bit index = day offset from today):

```
day offset:   0     1     2     3     4     5     6
date:        Jul8  Jul9  Jul10 Jul11 Jul12 Jul13 Jul14
bit:          1     1     0     0     1     1     1
                          └──taken──┘

Guest wants Jul 10 → Jul 12  (nights 10 & 11 → bits 2,3)
  wanted bits = positions {2,3} = 1 1
  actual bits at {2,3}          = 0 0   → NOT all free → REJECT ✗

Guest wants Jul 12 → Jul 15  (nights 12,13,14 → bits 4,5,6)
  wanted bits = positions {4,5,6} = 1 1 1
  actual bits at {4,5,6}          = 1 1 1 → all free → CANDIDATE ✓ (re-check at booking)
```

So a date-range availability filter is just "**do the requested bit positions all equal 1?**" — one bitwise AND, no calendar scan.

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

**How big is this bitset — can we really keep a year of it for 7M listings?** Yes, and it's tiny: **1 night = 1 bit**, so a full year is `365 bits ≈ 46 bytes` per listing → `7M × 46 bytes ≈ ~320 MB` total. That's the compressed shadow of the 2.5B-row calendar (§3) — a few hundred MB that fits in memory/index. In practice you often keep a smaller **rolling window** (next ~64/90/180 nights, since most bookings are near-term); the code above uses a single 64-bit `long` precisely so 64 nights fit in one CPU word for a one-instruction check. The window **slides forward daily** (day 0 = today; past dates drop off) as CDC updates it.

#### Q: Why not just query the calendar table with `WHERE date BETWEEN … AND status='AVAILABLE'` for every search, instead of building a bitset?

For **one** listing's detail page, you absolutely do (that read is small and exact). The problem is doing it for **every search over millions of listings at once** — that's the 2.5B-row join that melts. The bitset moves a compact, pre-computed answer *into* the search index so the filter is a cheap bitwise AND, not a giant join.

**Filters beyond geo and dates** — "wifi, pool, under ₹5000, superhost" — are **facets**. Elasticsearch indexes them as fields, so `amenities: ["wifi","pool"]`, `price <= 5000`, `is_superhost: true` all become filters combined with the geo + availability filters in one query. Facets are cheap because ES is built for exactly this kind of multi-field filtering.

---



## 6. Availability & Booking (no double-booking)

Each listing has a **calendar**; a booking must reserve **every night** in `[checkIn, checkOut)`. Same correctness family as BookMyShow's seat lock, applied per night.

### Atomic all-or-nothing calendar claim

- **Atomic conditional update** (`WHERE status='AVAILABLE'`) → if *any* night is taken, `rows_affected < nights` → roll back the whole booking. No overlap possible. (Full annotated SQL + `rows_affected` check in the deep dive below.)
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

**The fix: one atomic conditional UPDATE.** Ask the database to flip the nights to `BOOKED` *only where they're still* `AVAILABLE`, in a single statement, and then check how many nights it actually changed:

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

That covers per-night rows, but a natural follow-up is: why not just store date *ranges* instead of one row per night? There are actually three viable storage layouts, each with a different trade-off — worth laying out explicitly.

### Three ways to store the calendar (and which to use when)

People conflate three different storage layouts. Here's the plain-English version of each, plus when you'd reach for it.

| Approach | What's stored | "Available" means | Use it when |
| -------- | ------------- | ----------------- | ----------- |
| **Per-night, pre-seeded** (default) | one row per night, every status incl. `AVAILABLE` | a row exists **and** `status='AVAILABLE'` | interviews / most systems — simplest to reason about |
| **Sparse per-night** | a row per night **only when it's NOT free** (booked/held/blocked) | **no row at all = free** | you have tons of listings that are rarely booked and want to save space |
| **Interval model** | booked *ranges* `[from, to)` + a DB rule that forbids overlaps | no booked range covers that night | you want the most compact storage and can accept trickier, DB-specific SQL |

The rest of this section walks through the **pre-seeded** model (the default) end-to-end, then explains the **sparse** variant, since those are the two you'll actually discuss.

**Seeding the calendar when a listing is onboarded.** In the pre-seeded model, the moment a host publishes a listing you create one `AVAILABLE` row per night for a rolling horizon — commonly the **next ~365 days** (some go up to ~500). Nothing is booked yet, so every row just says "free."

```sql
-- Runs once, when listing 42 is created: fill the next year with free nights.
INSERT INTO listing_calendar (listing_id, date, status, price)
SELECT 42, d, 'AVAILABLE', 8000
FROM generate_series(CURRENT_DATE, CURRENT_DATE + INTERVAL '365 days', '1 day') AS d;
```

Do that for every listing and you land on the `7M × 365 ≈ 2.5B rows/year` estimate from §3. That's a lot of rows, which is exactly why you must actively manage the window (next).

**Keeping the table from growing forever: roll the window daily.** The calendar is a *sliding* one-year window, not an ever-growing log. A scheduled nightly job does two things: **add** a new night at the far-future edge, and **remove** nights that are now in the past. That keeps each listing pinned at ~365 rows instead of piling up years of dead history.

> This "rolling window" is the *same idea* as the search-side availability bitset (§5), but a **different mechanism**: the RDBMS calendar here is the exact source of truth; the bitset is its compact, approximate copy in Elasticsearch.

**How to drop/archive the past nights.** Three ways to trim the tail, from naive to production-grade:

```sql
-- A) Simplest: just delete old rows (keep a small buffer for disputes/refunds)
DELETE FROM listing_calendar WHERE date < CURRENT_DATE - INTERVAL '30 days';

-- B) Keep history: copy to a cheap "cold" table first, then delete
INSERT INTO listing_calendar_archive
SELECT * FROM listing_calendar WHERE date < CURRENT_DATE - INTERVAL '30 days';
DELETE FROM listing_calendar   WHERE date < CURRENT_DATE - INTERVAL '30 days';

-- C) Best at 2.5B-row scale: range-partition the table by month, then drop
--    whole partitions. Dropping a partition is an instant metadata operation —
--    no giant DELETE that has to scan and rewrite billions of rows.
ALTER TABLE listing_calendar DETACH PARTITION listing_calendar_2025_06;
DROP TABLE listing_calendar_2025_06;   -- or move it to cold storage
```

> **Interview tip:** say **partitioning (C)**. Deleting billions of individual rows is slow and bloats the table; dropping a partition is basically free.

### The sparse model and why it needs an "upsert"

The **sparse** model saves space by only storing rows for nights that are *not* free — a rarely-booked cabin might have almost no rows at all. The catch shows up in the booking claim.

Our atomic claim from earlier assumes the row already **exists** so it can flip `AVAILABLE → BOOKED`:

```sql
UPDATE listing_calendar SET status='BOOKED' ... WHERE ... AND status='AVAILABLE';
```

But in the sparse model a free night has **no row at all**. So this `UPDATE` matches **0 rows** — not because the night is taken, but because it was never created. Your `rows_affected == nights` check would then wrongly conclude `NOT_AVAILABLE`. The core problem: **`UPDATE` can only change rows that already exist.**

The fix is an **upsert** — `INSERT ... ON CONFLICT`, which means *"insert this row if it's missing; otherwise update the row that's already there,"* all in one atomic statement. That makes the "free night with no row" and the "free night with an existing row" cases behave identically:

```sql
INSERT INTO listing_calendar (listing_id, date, status, booking_id)
VALUES (:listingId, :night, 'BOOKED', :bookingId)
ON CONFLICT (listing_id, date)               -- a row already exists for this night?
DO UPDATE SET status = 'BOOKED', booking_id = :bookingId
WHERE listing_calendar.status = 'AVAILABLE'; -- ...then take it ONLY if still free
```

Walking through the cases:

- **No row yet** (free night in the sparse model) → the `INSERT` succeeds → the night is now booked.
- **Row already exists** → the primary key `(listing_id, date)` conflicts → it falls to `DO UPDATE`, but the `status='AVAILABLE'` guard means an already `BOOKED`/`HELD` night updates **0 rows** → the all-or-nothing check rolls the whole booking back.
- Because the PK guarantees **only one row per night can exist**, two guests racing for the same free night can't both win — one inserts, the other hits the conflict and is blocked by the guard. Same bulletproof CAS guarantee as the pre-seeded model, just without pre-creating 2.5B rows.

**Trade-off in one line:** sparse saves a huge amount of storage, at the cost of slightly more complex claim SQL (upsert instead of a plain update) — which is why the pre-seeded per-night model is the cleaner interview default.

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



Every failure exit runs the **same cleanup**: flip those nights from `HELD`/`BOOKED` back to `AVAILABLE` so other guests can book them. This "undo the earlier step" is called **compensation** (it's the heart of the saga in §8). Forgetting it = nights stuck locked forever = lost revenue and angry hosts. Instant-book is the same machine with one fewer stop — it just skips `REQUESTED` (no host to wait for) and jumps straight to `PENDING_PAYMENT → CONFIRMED`.

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

The price itself lives in two places, on purpose: the listing has a `base_price`, but `listing_calendar` also has a per-night `price`, so a host can charge more on weekends/holidays or a specific date. The quote above sums the *per-night* calendar prices when they differ from the base.

### Idempotency — why the same tap doesn't charge twice

The guest taps "Pay" and the network stalls, so they tap again. Without protection, that's **two charges**. Fix: the client sends a unique **Idempotency-Key** with the request; the server remembers keys it has already processed and returns the *same* result for a repeat instead of charging again.

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

### Cancellations and refunds

On a cancellation, the **cancellation policy** (FLEXIBLE / MODERATE / STRICT) decides how much is refunded — this is a swappable **Strategy**: flexible pays back close to full near the date, strict pays back little or none. The booking moves to `CANCELLED`, the held/booked nights are released back to `AVAILABLE`, and the ledger records the refund entries (mirroring the escrow entries above, just reversed).

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

### Database & storage choices (which DB, and why at scale)

Airbnb already draws this line explicitly in §4/§12 ("strong where it matters, relaxed where it doesn't") — the storage picks just make that concrete. The deciding question: *does this data decide who gets to book a specific night, or does it just help someone find a place?*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| `listing_calendar`, `bookings`, `payments`, `payouts` (ledger) | **RDBMS** (PostgreSQL/MySQL) | The no-double-booking guarantee rests on an **atomic conditional `UPDATE ... WHERE status='AVAILABLE'`** across every night in a range, inside one transaction (§6). Only an ACID engine with row-level locking gives you that "all nights or none" guarantee for free. The double-entry ledger (§8) also needs real transactions — money can't be "eventually" correct. | An eventually-consistent NoSQL store has no cross-row transaction to make "claim 4 nights atomically, roll back if any fails" safe — you'd have to hand-roll distributed locking for a problem the RDBMS solves natively. |
| Search & discovery (geo + facets + availability bitset) | **Elasticsearch** | Multi-filter queries (location, price, amenities, instant-book) plus a denormalized availability bitset (§5) for a cheap in-index date-range check — a read model rebuilt from the RDBMS via CDC, exactly what ES is built for. | Running `lat BETWEEN...`/facet filters plus a 2.5B-row calendar join (§3) directly against the RDBMS on every search would melt the same database that must stay fast for booking writes. |
| Listing/availability caching, checkout holds | **Redis** | Sub-ms reads for hot listing/availability lookups, and short-lived holds absorb read/contention load before it reaches the RDBMS. | Hitting the RDBMS for every browse-page render competes with the booking writes that need that same database to stay fast and uncontended. |
| Photos | **Blob store + CDN** (S3/CloudFront) | Large immutable bytes, served from the edge. | Storing images in the RDBMS bloats it and kills cache locality for the data that actually needs transactions. |

**Why calendar/bookings must be relational:** a booking is "this exact listing, these exact nights" (§1) — the same correctness family as BookMyShow's seat lock, just per night instead of per seat. Losing the atomic conditional update means losing the only thing that stops two guests both winning July 10–14. At Airbnb's actual write volume (bookings are a low, modest rate against the ~1000:1 search:booking ratio, §3), an RDBMS primary handles this comfortably — scale reads with **replicas + the ES CQRS read side**, and scale writes by **sharding on `listing_id`** (§13) so a booking never crosses shards. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### How the tables fit together

Read the schema as the lifecycle of a booking, not a pile of tables:

- A **user** who is a host owns **listings** (with **listing_photos**).
- Each listing has a **listing_calendar** — *one row per night* — the star of the show (§6).
- A guest creates a **booking** over a date range; a **payment** charges them; a **payout** later pays the host.
- After the stay, each side writes a **review**; throughout, they chat via **conversations** + **messages**.
- **outbox** reliably fans out events (§8); **cancellation_policies** decides refunds.



### `bookings` vs `listing_calendar` — related, but not the same table

A common beginner mix-up is thinking `listing_calendar` *is* the bookings table. It isn't — they're **two tables doing two different jobs**. The clearest way to see it: they're two *views of the same reservation*.

| | `bookings` | `listing_calendar` |
| --- | --- | --- |
| **Grain** | one row **per reservation** (a whole date range) | one row **per listing per night** |
| **Stores** | who booked, the range, price, status, payment | is *this one night* free / held / booked |
| **Answers** | "show me guest 777's trips" | "is listing 42 free on July 11?" |
| **Purpose** | guest-facing summary / history | the thing we **lock against** to prevent double-booking |

**Concrete example — one booking touches both tables.** A guest books listing 42 for `Jul 10 → Jul 14` (4 nights):

- `bookings` gets **1 row** — the summary: guest, listing, the range, total price, status.
- `listing_calendar` gets **4 rows flipped** to `BOOKED` — nights 10, 11, 12, 13 (checkout night 14 stays free).
- Each of those 4 calendar rows carries the same `booking_id` — that's the **link back** saying "this night is taken *by that booking*."

**Why split it into two tables instead of one?** A booking is naturally a *range* (`check_in` / `check_out`), but to stop double-booking you have to lock and check availability **one night at a time**. Storing one row per night is what makes the atomic `WHERE status='AVAILABLE'` claim work: you flip exactly the contested nights, so two guests booking *non-overlapping* dates on the same listing never block each other. Cramming both jobs into a single "range" table would make that per-night locking clumsy.

Putting real numbers on the same example makes it concrete — here's exactly what lands in each table for that Jul 10–14 booking:

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

Two-way trust is enforced through the schema, not just policy: the `reviews` table has a `role` (`GUEST_REVIEWS_HOST` / `HOST_REVIEWS_GUEST`) tied to a real `booking_id`, so you can't review a place you never actually stayed at. Airbnb also famously **hides both reviews until both are submitted** (or a window passes) so neither side retaliates — a nice detail to mention.

---



## 10. API Design

> Keep it RESTful; highlight the **`Idempotency-Key`** header on every money/booking write so retries never double-book or double-charge.

```
# ---- Search & listing (read, eventual is fine) ----
GET  /v1/listings?lat=&lng=&checkIn=&checkOut=&guests=&filters=   # search (map/faceted + availability)
GET  /v1/listings/{id}
GET  /v1/listings/{id}/availability?from=&to=                    # exact per-listing calendar

# ---- Booking (write, strong consistency) ----
POST /v1/bookings                                                # create booking / claim nights
     body:   { listingId, checkIn, checkOut, guests }
     header: Idempotency-Key: <uuid>
     → 201 { bookingId, status: REQUESTED | PENDING_PAYMENT }    # instant-book vs request
     → 409 { error: "DATES_UNAVAILABLE" }                        # lost the race / stale search

POST /v1/bookings/{id}/accept | /decline                        # host (request-to-book)
POST /v1/bookings/{id}/pay                                       # header: Idempotency-Key
     → 200 { status: CONFIRMED }
POST /v1/bookings/{id}/cancel                                   # refund per policy
GET  /v1/bookings/{id}                                           # poll status
GET  /v1/users/{id}/bookings                                     # history (cursor paginated)

# ---- Host, reviews, messaging ----
POST /v1/listings          # host create        PUT /v1/listings/{id}/calendar
POST /v1/bookings/{id}/review
POST /v1/conversations/{id}/messages

# ---- Webhooks (inbound, at-least-once → must be idempotent) ----
POST /v1/webhooks/payments   # gateway callback: charge succeeded/failed/refunded
POST /v1/webhooks/payouts    # payout settled / failed (host bank)
```

### The booking journey (which endpoint, which state)

| Step | Endpoint | Booking state after | Idempotent? |
| --- | --- | --- | --- |
| Guest searches | `GET /v1/listings?...` | — (read) | n/a |
| Guest opens a listing | `GET /v1/listings/{id}/availability` | — (exact calendar read) | n/a |
| Guest books | `POST /v1/bookings` + `Idempotency-Key` | `REQUESTED` (request) or `PENDING_PAYMENT` (instant) | ✅ key |
| Host accepts (request only) | `POST /v1/bookings/{id}/accept` | `PENDING_PAYMENT` | ✅ (state-guarded) |
| Guest pays | `POST /v1/bookings/{id}/pay` + `Idempotency-Key` | `CONFIRMED` | ✅ key |
| Gateway confirms | `POST /v1/webhooks/payments` | `CONFIRMED` (settle truth) | ✅ dedup event id |
| Guest cancels | `POST /v1/bookings/{id}/cancel` | `CANCELLED` (refund per policy) | ✅ (state-guarded) |

> The **create-booking** and **pay** endpoints are the ones that MUST be idempotent — a retried tap must return the *first* result, never a second hold or a second charge. Webhooks are **at-least-once**, so dedup them on the gateway event id.

#### Q: At the API level, how does instant-book differ from request-to-book?

It's the **same `POST /v1/bookings` endpoint** — the difference is the *state it returns* and whether a host step exists. For an **instant-book** listing the server claims the nights and immediately returns `PENDING_PAYMENT` (the guest can pay right away, no human in the loop). For a **request-to-book** listing the same call claims the nights as `HELD` and returns `REQUESTED`; the guest can't pay until the host calls `POST /v1/bookings/{id}/accept` (which flips it to `PENDING_PAYMENT`), and a `/decline` or a timeout releases the hold. So the client doesn't choose a different API — it reacts to the returned status, and the `instant_book` flag on the listing decides which path the server takes.

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

### Instant-book vs request-to-book (side by side)

> Same state machine, same atomic calendar claim — the only real difference is **whether a human approval step sits in the middle** (and therefore how long the hold lasts).

| Dimension | **Instant-book** | **Request-to-book** |
| --- | --- | --- |
| Host approval | none | host must accept |
| First state | `PENDING_PAYMENT` | `REQUESTED` |
| Calendar on click | claim nights (short hold, ~15 min) | claim nights `HELD` (long hold, ~24h) |
| When guest is charged | right after claim | only after host accepts |
| Hold expiry driver | payment timeout (minutes) | host-response timeout (~24h) |
| Failure → compensation | payment fails → release nights | decline **or** timeout → release nights |
| Saga shape | machine-only steps | machine **+ human-in-the-loop** step |
| Guest experience | instant confirmation | wait for host, may be declined |
| Best for | high-trust/high-volume hosts | hosts who screen guests |

> Interviewer one-liner: *"Instant-book is request-to-book with the host-approval state removed — so I reuse one state machine and one atomic claim, just with a shorter hold and no `REQUESTED` stop."*

---



## 12. Consistency & CAP Tradeoffs


| Case                              | Handling                                                                                |
| --------------------------------- | --------------------------------------------------------------------------------------- |
| Double-booking                    | Atomic all-or-nothing calendar claim (`WHERE status='AVAILABLE'`, check `rows==nights`) |
| Two guests race same dates        | One wins the conditional update; the other gets `rows<nights` → NOT_AVAILABLE           |
| Abandoned checkout                | `HELD` + `hold_expiry` → sweeper releases; saga compensation                            |
| Host no-response (request)        | Timeout → auto-decline + release calendar                                               |
| Payment fails                     | Release calendar (compensation); notify                                                 |
| Duplicate booking tap             | `UNIQUE(idempotency_key)`                                                               |
| Guest cancels                     | State → CANCELLED, release nights, refund per policy (Strategy)                         |
| Search shows unavailable listing  | Search is approximate; **exact re-check at booking** rejects it                         |
| Host edits calendar during a hold | Held nights are locked; edits apply to free nights only                                 |




### Strong where it matters, relaxed where it doesn't

The single biggest idea tying this doc together: **not everything needs to be perfectly consistent.** Airbnb deliberately picks per feature. Interviewers phrase this as **CAP** — under a network partition, do you sacrifice **C**onsistency (**AP**) or **A**vailability (**CP**)?

> 💡 **CP vs AP in one breath:** **CP** = refuse to answer rather than give a wrong one (booking write). **AP** = always answer, even if slightly stale (search). You pick per path, not once for the whole system.

| Path | CAP choice | Consistency | Why |
| --- | --- | --- | --- |
| **Booking / calendar write** | **CP** | **Strong** (must be exact) | Money + no double-booking; a wrong answer is a disaster, so we'd rather fail the write than double-book |
| **Money / ledger, payouts** | **CP** | **Strong** | Funds can't be "eventually" correct; double-entry must balance |
| **Search results / availability bitset** | **AP** | **Eventual** (seconds stale) | A stale card is harmless — booking re-checks and rejects it |
| **Review counts, ratings on cards** | **AP** | **Eventual** | Nobody's harmed if a rating updates a few seconds late |
| **Notifications / analytics / index** | **AP** | **Eventual** | Downstream, async, retryable (outbox → Kafka) |

The pattern: an account balance must be exact to the paisa (strong/CP), while a "customers also viewed" widget can lag (eventual/AP). Same app, different guarantees on purpose — you pay for strong consistency with speed/complexity, so you only buy it where it matters.

> 🎤 **Interviewer one-liner:** *"Strong consistency (CP) where money or inventory is involved — the calendar claim and the ledger — and eventual consistency (AP) everywhere else: search, the availability bitset, review counts, and downstream events."*

---



## 13. Scaling & Failure

- **Search** on Elasticsearch (geo bounding-box + facets + **availability bitset**) + cache; rebuild via CDC; approximate, re-checked at booking.
- **Booking** on RDBMS, strong consistency, shard by `listing_id` (or region); calendar past-date pruning.
- **Hold + TTL** for checkout; **saga compensation** releases on payment/host failure; **request timeout** auto-declines.
- **Idempotency** prevents double bookings; **outbox** drives confirmation/payout events.
- **Payouts** escrowed, released post-check-in; **double-entry ledger** for money.
- Photos/media → blob + CDN.

---



## 14. Reviews & Trust

> Two strangers transact money over the internet — reviews are the **trust engine** that makes that safe. The mechanics matter because naive reviews get gamed (retaliation, fake stays).

- **Verified-stay requirement:** a review can only be written against a **real `booking_id`** that reached `COMPLETED`. No stay → no review. Enforced in the schema (`reviews.booking_id` + `role`), not just policy, so you can't review a place you never booked.
- **Blind double-review window:** both sides can review, but **neither review is published until both are submitted or a fixed window (e.g. 14 days) passes.** Neither party sees the other's review before writing their own → kills **retaliation** ("you gave me 3 stars, I'll give you 1 back").
- **Two-way roles:** `GUEST_REVIEWS_HOST` and `HOST_REVIEWS_GUEST` — a host's rating of guests feeds instant-book eligibility and screening.
- **Rating aggregation is eventual:** the listing's `rating` / `rating_count` on the card are **denormalized aggregates** updated asynchronously (on `REVIEW_PUBLISHED` via Kafka). A card showing a rating a few seconds stale harms no one (**AP**, §12) — we don't recompute the average on the booking write path.
- **Blocking / reporting:** either side can **block** the other (no future messaging or booking between them) and **report** abuse; blocks are checked at booking + messaging time.

```
Guest submits review ──┐
                       ├─► both in? → publish BOTH atomically ─► REVIEW_PUBLISHED (Kafka)
Host submits review  ──┘                                              │
   (or 14-day window elapses → publish whatever exists)               ▼
                                                     async: recompute listing rating/count
```

#### Q: Why hide reviews until both are in — why not publish each immediately?

Because **immediate publish invites retaliation and bias.** If the host sees a guest's harsh-but-fair review first, the host can "punish back" with an unfair guest review; guests then self-censor to avoid revenge. Hiding both until each is committed (or the window closes) means each side reviews **honestly and independently** — they're writing blind. It's a small consistency delay (the review is written but not visible) bought deliberately for **trust**, which is the whole product.

---



## 15. Messaging

> Guests and hosts need to talk — before booking ("is parking included?") and after ("what's the door code?"). It's a scoped chat, not a general social inbox.

- **A conversation is scoped to a (listing, guest, host) — and, once a booking exists, to a `booking_id`.** This keeps context tight: a thread maps to a specific potential or actual stay, so support/disputes can pull the exact conversation.
- **Pre-book inquiries vs post-book support:**
  - **Pre-book inquiry** — guest messages a host about a listing *before* booking. No `booking_id` yet (conversation keyed by listing+guest+host). Often rate-limited / spam-filtered since there's no committed stay.
  - **Post-book support** — once a booking exists, the thread is tied to that `booking_id`; higher trust, richer actions (share address, modify dates, escalate to support).
- **Delivery:** messages persist in `messages` (source of truth) and fan out via push/websocket for real-time; **eventual consistency** is fine (a message arriving a second late is harmless — this is **not** the booking write path).
- **Safety hooks:** blocked users can't message; phone-number/PII masking pre-booking (keep payments on-platform); attachments → blob + CDN.

```
Pre-book:   conversation(listing_id, guest_id, host_id)          booking_id = NULL
              └─ inquiry → host replies → guest decides to book
Post-book:  same conversation now carries booking_id = 9001
              └─ scoped to the stay; support can attach it to disputes/refunds
```

> 💡 **Why scope to `booking_id`?** It makes every downstream job — disputes, refunds, "resend the door code", trust & safety review — a **single keyed lookup** instead of trawling a global chat log.

---



## 16. Reliability & Observability

- **No single point of failure** — RDBMS primary + replicas + failover, multi-AZ Redis/Kafka/ES, stateless services behind a load balancer across zones.
- **Idempotent retries** everywhere on the write path (booking create, pay); **webhooks deduped** on gateway event id.
- **Dead-letter queues** for failed events / saga compensations (release-nights, payout).
- **Graceful degradation** — if search (ES) is down, still serve direct listing pages + booking; if Redis is down, fall back to DB reads; if the payout job is behind, bookings still confirm (payout catches up).
- **Reconciliation** — a job compares `PENDING` payments/payouts against the gateway and settles stragglers; the double-entry ledger is the audit backstop.

### Signals worth alerting on

| Metric | Why it matters | Alert when |
| --- | --- | --- |
| **Search QPS + p99 latency** | the read firehose; ES/cache health | p99 > 200ms or QPS spikes beyond capacity |
| **Booking success / conflict rate** | a spike in `DATES_UNAVAILABLE` = hot dates or a bug | conflict rate jumps abnormally |
| **Calendar write rate / lock contention** | hot listing/date contention | contention or claim latency climbs |
| **CDC / search index lag** | stale search → guests book taken listings | lag > a few seconds/minutes |
| **Payout reconciliation backlog** | held/undelivered host money | backlog grows or ages past SLA |
| **Payment failure / webhook processing lag** | money stuck in ambiguity | failure rate up, webhook queue backs up |

> 💡 **The two lags to watch specifically for Airbnb:** **CDC/index lag** (search staleness → more booking-time rejections) and **payout reconciliation backlog** (host money held too long). Both are silent — nothing errors — so you only catch them with metrics.

---



## 17. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6.

1. **Clarify requirements** (functional + NFRs, out of scope) — §2
2. **Estimate scale** (search ≫ bookings, calendar rows) — §3
3. **Define APIs** (idempotent booking write, webhooks) — §10
4. **High-level architecture + CQRS split + data model** — §4, §9
5. **Deep dive: the hard part** → **no double-booking** (atomic per-night claim, hold+TTL) and **availability search at scale** (bitset) — §5, §6
6. **Deep dive: payments, escrow, saga, state machine** — §7, §8
7. **Address trust, scale, edge cases** — §12–§16
8. **Summarize tradeoffs** — §12, §18

> 🎤 **Lead with the core challenge:** state up front that "this is a two-sided marketplace whose crux is **no double-booking on a per-listing calendar**, split into a fast approximate **search** side and a bulletproof **booking** side (CQRS)." Then spend most of your time on the calendar claim + availability search.

---



## 18. Interview Cheat Sheet

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

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Two guests book the same dates** | Atomic per-night claim: one flips all nights `AVAILABLE→BOOKED`, the other gets `rows_affected < nights` → roll back → `DATES_UNAVAILABLE`. No overlap possible. |
| **Instant-book race on the last free night** | Same atomic claim — the DB serializes the row writes; exactly one wins, the rest fail fast (no lock-and-wait). |
| **Review posted before checkout** | Blocked by design: a review needs a `booking_id` in `COMPLETED`, and both reviews stay **hidden until both submit or the window closes** (§14). |
| **Payout held for a dispute** | Escrow stays put — payout job **skips bookings with an open dispute** and only releases ~24h post-check-in when clear; ledger tracks the held funds. |
| **Calendar/search index stale** | Search is **approximate** (CDC lag); the bitset may show a taken night as free → **exact re-check at booking** rejects it (§4/§5). |
| **Abandoned checkout / host never responds** | `HELD` + `hold_expiry`; a sweeper releases expired holds; request-to-book auto-declines on timeout (compensation). |
| **Duplicate "Pay" tap** | `UNIQUE(idempotency_key)` → return the first booking/charge, never a second. |
| **Payment succeeds but response lost** | Idempotency + webhook + reconciliation → settle to `CONFIRMED`, don't re-charge. |

> **Ultimate layer model:** atomic per-night claim = correctness · HELD+TTL = don't leak nights · idempotency = safe retries · saga compensation = clean failure · outbox = reliable events · escrow+ledger = safe money.

---



## 19. Design Patterns (that can be used)


| Pattern                      | Where                                                                 | Why                                           |
| ----------------------------- | --------------------------------------------------------------------- | --------------------------------------------- |
| **Saga / Orchestration**     | Request→accept→pay→confirm→payout, with compensation (release/refund) | Multi-step distributed txn (incl. human step) |
| **State**                    | Booking lifecycle                                                     | Guard transitions                             |
| **Optimistic Locking / CAS** | Calendar conditional update                                           | No double-booking                             |
| **Strategy**                 | Search ranking, pricing, cancellation policy                          | Swap rules/algorithms                         |
| **CQRS**                     | ES search read model vs RDBMS write model                             | Optimized reads                               |
| **Outbox**                   | Reliable events (confirmed → notify, index, payout)                   | No dual-write loss                            |
| **Ports & Adapters**         | Payment, search index, maps, notifications                            | Swap providers                                |
| **Decorator / Chain**        | Price = base + cleaning + service fee + taxes − discount              | Stack pricing                                 |
| **Observer / Pub-Sub**       | Booking events → notifications, indexing, payouts                     | Decouple                                      |
| **Repository**               | Data access                                                           | Testable                                      |


---



## 20. Final Takeaways

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


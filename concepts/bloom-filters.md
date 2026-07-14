# Bloom Filters

> A **probabilistic, space-efficient** set membership structure. It answers *"is X in the set?"* with **"definitely not"** or **"probably yes"** — **no false negatives, some false positives** — in tiny memory. Used to avoid expensive lookups (DB/disk/network) for items that don't exist.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. The Problem It Solves](#1-the-problem-it-solves)
- [2. How It Works](#2-how-it-works)
- [3. Properties & Trade-offs](#3-properties--trade-offs)
- [4. Sizing](#4-sizing)
- [5. Variants](#5-variants)
- [6. Where It's Used](#6-where-its-used)
- [Common Mistakes](#common-mistakes)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. The Problem It Solves

You want to check membership in a **huge set** without storing the whole set in memory or hitting a slow store every time.

```
"Has this user seen this article?"   "Is this URL already crawled?"
"Does this key exist before I hit disk?"   "Is this username taken?"
```

A Bloom filter answers most **negatives** instantly in RAM, so you only do the expensive lookup when it says "probably yes."

### When a Bloom filter helps

A Bloom filter is a cheap in-memory pre-check placed in front of an expensive lookup. It gives one of two answers about whether an item is in the set:

- **"definitely absent"** → skip the expensive lookup entirely. This answer is **never wrong**: if the item had been added, the filter would say otherwise.
- **"probably present"** → not sure. The item may be in the set, or it may be a false positive. *Only then* do you perform the real lookup to confirm.

That turns "do the expensive lookup every time" into "do it only for the fraction that pass the pre-check."

A Bloom filter shines exactly when:

- The set is **huge** (millions/billions of items) and you can't hold it all in memory.
- The real lookup is **expensive** (disk read, network call, DB query).
- **Most queries are for things that don't exist** (missing keys, uncrawled URLs, unseen articles) — so a fast "definitely absent" saves the most work.
- You can **tolerate an occasional false positive** (an extra unnecessary lookup) but **cannot** tolerate wrongly saying "absent" for something that exists.

If instead you need exact answers, or you must delete items freely, or nearly everything you look up *does* exist — a plain Bloom filter is a poor fit (see §3 and §5).

---

## 2. How It Works

A **bit array** of size `m` + `k` independent hash functions.

```
add(x):     set bits [ h1(x)%m, h2(x)%m, ..., hk(x)%m ] = 1
contains(x):
    if ALL of [ h1(x)%m ... hk(x)%m ] are 1 → "probably present"
    if ANY is 0                            → "definitely absent"
```

```
m = 10 bits, k = 3
add("cat"): set bits 1,4,7
contains("cat"): bits 1,4,7 all 1 → probably present ✅
contains("dog"): bit 2 is 0       → definitely absent ✅
contains("fox"): bits 1,4,7 happen to be 1 (set by others) → FALSE POSITIVE ⚠️
```

### How the bit array works

The structure is a bit array of `m` bits, all **0** to start, plus `k` hash functions that map any item to the **same** `k` positions each time.

- **Adding an item** = run it through the `k` hash functions to get `k` positions, and set those bits to **1**. Bits are never cleared.
- **Checking an item** = run it through the same `k` hash functions and inspect those `k` bits:
  - If **any** of them is still **0**, the item was *never* added (adding it would have set that bit to 1) → **"definitely absent."**
  - If **all** of them are **1**, the item is **"probably present"** — but those bits may have been set to 1 by *other* items that happened to hash to the same positions. That coincidence is a **false positive**.

Key point: the filter never stores the items themselves — only the array of set/unset bits. That's why it's tiny, and also why it can't tell you *which* item set a given bit.

### A false positive, drawn on the bit array

Text says "shared bits collide"; here's what that actually looks like. Take `m = 10`, `k = 3`, and add two items — `add("cat") → {1,4,7}` and `add("owl") → {4,7,9}`:

```
 index:  0    1    2    3    4    5    6    7    8    9
 bits:  [0]  [1]  [0]  [0]  [1]  [0]  [0]  [1]  [0]  [1]
              ▲              ▲              ▲         ▲
             cat          cat+owl        cat+owl    owl

now check contains("fox"), where fox hashes to {1,4,7}:
              ▲              ▲              ▲
            bit 1=1       bit 4=1        bit 7=1     → ALL 1 → "probably present"

but "fox" was never added. Bits 1,4,7 were switched on by cat and owl.
→ FALSE POSITIVE ⚠️  (no single item "owns" a bit; the filter only sees 1/0)
```

The filter reads three 1s and can't know they came from *other* items. That coincidence — every one of an unseen item's `k` bits already set — is precisely a false positive. Add more items and more bits flip to 1, so these coincidences get more common (see §3 and §4).

### The whole thing in ~30 lines of Java

```java
class BloomFilter {

    private final boolean[] bits;   // the bit array, all false (0) at start
    private final int m;            // number of bits
    private final int k;            // number of hash functions

    BloomFilter(int m, int k) {
        this.m = m;
        this.k = k;
        this.bits = new boolean[m];  // Java initializes every slot to false = 0
    }

    // ADD: flip ON the k positions this item hashes to
    void add(String item) {
        for (int i = 0; i < k; i++) {
            int pos = hash(item, i) % m;   // hash #i → a position in [0, m)
            bits[pos] = true;              // set that bit to 1 (never cleared)
        }
    }

    // CONTAINS: "probably present" only if ALL k positions are ON
    boolean contains(String item) {
        for (int i = 0; i < k; i++) {
            int pos = hash(item, i) % m;
            if (!bits[pos]) {
                return false;   // one bit is 0 → DEFINITELY absent (no false negatives)
            }
        }
        return true;            // every bit is 1 → PROBABLY present (could be a false positive)
    }

    // k "independent" hashes cheaply: mix one base hash with a seed i.
    // (Real impls use techniques like double hashing; this is the idea.)
    private int hash(String item, int seed) {
        int h = (item.hashCode() ^ (seed * 0x9E3779B1));   // combine item with the seed
        return Math.abs(h);
    }
}
```

Trace it against the dense example above (`m = 10`, `k = 3`):

```java
BloomFilter bf = new BloomFilter(10, 3);
bf.add("cat");                 // flips ON, say, positions 1, 4, 7
bf.contains("cat");   // → true  : 1,4,7 all ON  → probably present ✅
bf.contains("dog");   // → false : one of dog's positions (say 2) is OFF → definitely absent ✅
bf.contains("fox");   // → true  : fox's positions happen to be 1,4,7 (set by "cat") → FALSE POSITIVE ⚠️
```

Notice there is **no `remove` method** — because clearing a bit could silently un-add some *other* item that shares that position (see §5 for the fix, a Counting Bloom filter).

---

## 3. Properties & Trade-offs

| Property | Value |
| --- | --- |
| **False negatives** | **Never** — if it says "absent," it's truly absent |
| **False positives** | Possible — "probably present" may be wrong |
| **Space** | Tiny (bits, not full items) |
| **Operations** | `add` and `contains` are O(k) |
| **Deletion** | **Not supported** in a plain Bloom filter (use Counting Bloom filter) |

> The **guarantee that matters:** no false negatives → safe to use as a "skip the expensive lookup if absent" gate. A false positive just means an occasional unnecessary lookup (still correct, just not free).

### Why false positives but never false negatives

This is *the* question people trip on, so let's work through it with the bit-array model from §2.

#### Q: Why can it never give a false negative (say "absent" for something that IS there)?

Because **adding an item only ever sets bits to 1, and nothing ever clears them.** So if you truly added `"cat"`, then `"cat"`'s `k` bits are guaranteed 1 forever. When you later check `"cat"`, you inspect those exact same `k` bits — they *must* all be 1 — so the answer can never be "absent." A "definitely absent" happens only when *at least one* checked bit is 0, which is impossible for an item you actually added.

> One-liner: **"absent" is trustworthy because the only way to see a 0 bit is if nobody ever set it.**

#### Q: Then why CAN it give a false positive (say "present" for something that isn't)?

Because bits are **shared**. Different items can hash to overlapping positions. If `"cat"` set positions 1,4,7 and `"owl"` set 4,7,9, then a brand-new item `"fox"` whose positions happen to be 1,4,7 will find **all three set** — even though `"fox"` was never added. The filter can't tell "these bits are 1 *because of this item*" from "these bits are 1 *because of other items*." It only sees 1/0, not *which* item set them.

```
"present" really means: "every bit this item maps to is already 1 —
                         so MAYBE it was this item, or maybe a mix of others."
"absent"  really means: "a bit this item maps to is 0 —
                         so it was definitely never added." (rock solid)
```

#### Q: What happens to the false-positive rate as I add more items?

More items → more bits set to 1 → more collisions → **more false positives.** Eventually, if you overfill the filter, *most* bits are 1 and nearly everything reads "probably present" (useless). This is why sizing matters (§4): you pick `m` and `k` up front for how many items `n` you expect. False *negatives* never appear no matter how full it gets — only the false-*positive* rate degrades.

---

## 4. Sizing

```
Given n items and target false-positive rate p:
  m (bits)  = -(n * ln p) / (ln 2)^2
  k (hashes) = (m / n) * ln 2
```

- More bits (`m`) or items removed → lower false-positive rate.
- Too many items in a fixed filter → fills up → false-positive rate climbs.
- Example: ~10 bits/item + 7 hashes → ~1% false positives.

### How to actually pick `m` and `k`

You don't guess these — you **work backwards from two numbers you already know**:

1. `n` = roughly **how many items** you'll add (e.g. 10 million URLs).
2. `p` = the **false-positive rate you can live with** (e.g. 1% = `0.01`).

Plug those into the two formulas and you get the size `m` and the number of hashes `k`.

```java
// Given n items and target false-positive rate p, compute m (bits) and k (hashes).
static int bitsNeeded(long n, double p) {
    // m = -(n * ln p) / (ln 2)^2
    return (int) Math.ceil(-(n * Math.log(p)) / (Math.log(2) * Math.log(2)));
}

static int hashesNeeded(long n, int m) {
    // k = (m / n) * ln 2   → the k that MINIMIZES false positives for this m and n
    return Math.max(1, (int) Math.round((m / (double) n) * Math.log(2)));
}

// Example: 10 million items, want ~1% false positives
int m = bitsNeeded(10_000_000L, 0.01);   // ≈ 95.8 million bits ≈ 12 MB (!)
int k = hashesNeeded(10_000_000L, m);    // ≈ 7 hash functions
```

Handy rules of thumb worth memorizing:

- **~10 bits per item → ~1% false positives** (with the matching `k ≈ 7`). Storing 10M items exactly might cost hundreds of MB; the Bloom filter costs ~12 MB. That's the whole pitch.
- Want **10× fewer** false positives (0.1%)? It costs about **+4.8 bits per item** — cheap. FP rate drops *exponentially* as you add bits.

### Worked example: n = 1M items, target p = 1%

The doc keeps quoting results ("~10 bits/item, k ≈ 7"); here's the actual plug-in so the numbers aren't magic. Take `n = 1,000,000` and `p = 0.01`.

```
m = -(n · ln p) / (ln 2)^2

  ln p       = ln(0.01)  = -4.60517
  (ln 2)^2   = 0.69315^2 =  0.48045

  m = -(1,000,000 × -4.60517) / 0.48045
    =   4,605,170 / 0.48045
    ≈   9,585,059 bits
    ≈   9.6 M bits  =  9.6e6 / 8  ≈  1.2 MB
```

```
k = (m / n) · ln 2

  m / n = 9,585,059 / 1,000,000 = 9.585   (≈ 9.6 bits per item)
  k     = 9.585 × 0.69315       = 6.64    → round to 7 hashes
```

So **1M items at 1% FP → ~1.2 MB and 7 hashes.** Storing 1M items exactly (say 16-byte keys) would be ~16 MB+; the filter is ~13× smaller. Note the pattern: `m/n ≈ 9.6` bits/item and `k ≈ 7` fall out for *any* `n` at `p = 1%` — the ratios depend only on `p`, not on `n`.

### The exact false-positive formula

The two sizing formulas above come from one closed form for the FP rate:

```
p ≈ (1 - e^(-k·n/m))^k
```

One-line intuition, built from the bit array:

- After inserting `n` items with `k` hashes each, a **single** bit is still 0 with probability `(1 - 1/m)^(kn) ≈ e^(-kn/m)`.
- So a given bit is **1** with probability `(1 - e^(-kn/m))`.
- A false positive needs **all `k`** of an absent item's bits to already be 1 → raise that to the `k`. Hence `(1 - e^(-kn/m))^k`.

Minimizing this over `k` gives `k = (m/n)·ln 2`, and substituting back gives the `m` formula — that's *why* the sizing rules look the way they do.

> ⚠️ **pitfall:** this formula assumes the `k` hashes are **independent and uniform**. Real code often fakes `k` hashes cheaply with **double hashing** (`h_i = h1 + i·h2`), which is only an *approximation* of independence. It's good enough in practice, but the observed FP rate can be slightly worse than the formula predicts — always leave a little headroom.

#### Q: Why is there a "best" `k`? Wouldn't more hashes always be safer?

No — `k` has a sweet spot. Each hash you add sets **more** bits per item:

- **Too few hashes** → you check too few bits, so collisions slip through as false positives.
- **Too many hashes** → each add sets more bits, so the array **fills up faster** and *everything* starts colliding.

The formula `k = (m/n) · ln 2` gives the `k` that balances these — it's the point where about **half the bits are set** when the filter is full, which mathematically minimizes false positives. More is not better; it's a tuned knob.

#### Q: What if I don't know `n` in advance?

Either **over-provision** `m` for the largest `n` you expect (a Bloom filter degrades gracefully — extra room just means fewer false positives), or use a **Scalable Bloom filter** (§5) that grows on demand while keeping the FP rate bounded.

---

## 5. Variants

| Variant | Adds |
| --- | --- |
| **Counting Bloom filter** | Counters instead of bits → supports **deletion** |
| **Scalable Bloom filter** | Grows as items are added (keeps FP rate bounded) |
| **Cuckoo filter** | Supports deletion, often better space + lookup |

### Why plain Bloom filters can't delete (and how counting fixes it)

#### Q: Why can't I just clear the bits back to 0 to delete an item?

Because bits are **shared** (the §3 insight again). Say `"cat"` set positions 1,4,7 and `"owl"` set 4,7,9. If you "delete" `"cat"` by clearing 1,4,7 back to 0, you also just cleared positions 4 and 7 — which `"owl"` still needs. Now `contains("owl")` sees a 0 bit and wrongly reports **"definitely absent"** — a **false negative**, which destroys the one guarantee the whole structure is built on. So plain Bloom filters simply forbid deletion.

#### Q: How does a Counting Bloom filter allow deletion?

Replace each single **bit** with a small **counter** (say a 4-bit number). Instead of "ON/OFF," each slot tracks *how many* items are currently using it. Add increments; delete decrements.

```java
class CountingBloomFilter {
    private final int[] counts;   // instead of boolean[] bits — each slot is a small counter
    private final int m, k;

    CountingBloomFilter(int m, int k) {
        this.m = m; this.k = k;
        this.counts = new int[m];   // all zero
    }

    void add(String item) {
        for (int i = 0; i < k; i++) counts[hash(item, i) % m]++;   // bump each slot
    }

    void remove(String item) {
        for (int i = 0; i < k; i++) counts[hash(item, i) % m]--;   // decrement — SAFE now
    }

    boolean contains(String item) {
        for (int i = 0; i < k; i++) {
            if (counts[hash(item, i) % m] == 0) return false;      // a zero slot → absent
        }
        return true;                                               // all non-zero → probably present
    }

    private int hash(String item, int seed) {
        return Math.abs(item.hashCode() ^ (seed * 0x9E3779B1));
    }
}
```

Now deleting `"cat"` decrements 1,4,7. Positions 4 and 7 drop from 2 → 1 (still non-zero, because `"owl"` is still counted there), so `"owl"` remains "present." Correct deletion, no false negatives introduced. **Cost:** counters take more space than single bits (typically ~3–4× the memory), which is the price of supporting deletes.

#### Q: When would I reach for the other variants?

- **Scalable Bloom filter** — when you **don't know `n` up front** and the filter would otherwise overfill. It chains progressively larger sub-filters as you add items, keeping the overall false-positive rate bounded.
- **Cuckoo filter** — when you want **deletion *and* good space efficiency**. It stores small fingerprints in a cuckoo hash table; often smaller than a Counting Bloom filter for low target FP rates, with fast lookups and true deletes.

### Bloom vs Cuckoo filter vs HashSet

Three ways to answer "is X in the set?", trading exactness for space:

| | **Bloom filter** | **Cuckoo filter** | **HashSet** |
| --- | --- | --- | --- |
| **Space** | ~10 bits/item (1% FP) | similar, often smaller at low FP | stores full items (bytes–KB each) |
| **Supports delete** | ❌ (use Counting variant) | ✅ true deletes | ✅ |
| **Exact answer** | ❌ probabilistic (FP possible) | ❌ probabilistic (FP possible) | ✅ exact, no false positives |
| **Lookup cost** | O(k) | O(1) (2 buckets) | O(1) |
| **Stores values?** | no | no (fingerprints only) | yes (the items themselves) |

> 💡 **When a plain HashSet wins:** if `n` is small enough that the full set comfortably fits in RAM (say thousands to low millions of short keys), just use a `HashSet`. It's simpler, gives **exact** answers with **zero** false positives, and supports deletes for free. Bloom/Cuckoo filters only pay off when storing the items *exactly* is the thing you can't afford — huge `n`, or many filters (one per SSTable, per shard, per crawler node).

### Counter overflow and merging filters

Two practical details that come up with real deployments:

- **Counting filter overflow.** Counting Bloom counters are usually only **4 bits** (0–15) to keep the memory overhead down. If a slot is hit more than 15 times it **saturates** — the standard fix is to *stop incrementing* at the max rather than wrapping to 0 (wrapping would corrupt the filter). A saturated counter can no longer be safely decremented, so heavy-hit slots may leak a tiny amount of extra FP over time. Size counters for your busiest bit, not the average.
- **Merging Bloom filters.** Two plain Bloom filters with the **same `m` and `k`** can be merged by a simple **bitwise OR** of their arrays — the result is exactly the filter you'd get from adding both sets. This is why they're great for **distributed work**: e.g. web-crawler nodes each keep a local "seen URLs" filter and periodically OR them together to share progress. (Union is cheap; **intersection is not** meaningful — AND-ing two Bloom filters does *not* give the intersection's filter.)

> 💡 **tip:** don't hand-roll these in production. Use **RedisBloom** (Redis module with `BF.*`/`CF.*` commands for Bloom, Cuckoo, Counting, and scalable filters) or **Guava's `BloomFilter`** (JVM) — both handle sizing, hashing, and serialization for you.

---

## 6. Where It's Used

| System | Use |
| --- | --- |
| **Databases (Cassandra, HBase, Bigtable)** | Skip disk/SSTable reads for keys that don't exist |
| **Web crawler** | "Seen this URL?" without storing all URLs |
| **Caches** | Avoid **cache penetration** — skip lookups for keys known absent |
| **CDNs / proxies** | "Is this cacheable object present?" |
| **Username/email** | Fast "probably taken" pre-check before DB |
| **Recommendation/feed** | "Has the user already seen this item?" |

> Appears across these notes: URL Shortener (unknown codes), Web Crawler (seen URLs), Distributed Cache (penetration).

### When to reach for one (and when NOT to)

Notice the common shape of **every** row in the table above: *"before I do something slow/expensive, cheaply rule out the cases that don't exist."* That's the one job of a Bloom filter.

**Reach for a Bloom filter when all of these hold:**

- The backing lookup is **expensive** (disk seek, SSTable scan, network/DB call) — the filter earns its keep by *avoiding* it.
- **Many queries are for absent items** — e.g. a database gets asked for keys that don't exist; the filter turns those into instant in-RAM "no."
- You can **tolerate the occasional false positive** (an extra unnecessary lookup that still returns the correct result).
- You **rarely or never need to delete** (or you can afford a Counting/Cuckoo filter if you do).

Concrete walkthrough — **Cassandra skipping disk reads** (the classic use):

```
read("user:42"):
  1. Ask the Bloom filter (in RAM): "is user:42 maybe in this SSTable?"
        → "definitely absent"  → skip this SSTable entirely (saved a disk read!) ✅
        → "probably present"   → read the SSTable from disk to actually check
```

Most keys are absent from most SSTables, so the filter eliminates the vast majority of pointless disk reads. A false positive just means one wasted disk read — the data returned is still correct.

**Do NOT use one when:**

- You need the **actual value**, not just yes/no membership (a Bloom filter stores no values — use a hash map / cache).
- **False positives are unacceptable** for correctness (e.g. "is this the exact password hash?").
- **Almost everything you look up actually exists** — then the filter says "probably present" nearly every time and you do the expensive lookup anyway; it just adds overhead.
- You need **frequent deletions** and can't afford a counting variant.

---

## Common Mistakes

> ⚠️ **The three ways people break a Bloom filter in practice:**
>
> - **Sizing for `n`, then loading 10× `n`.** The FP rate isn't a gentle slope — it climbs *exponentially* past your design `n`. Size for `n = 1M` at 1% but insert 10M and nearly every bit is 1, so `contains` returns "probably present" for almost everything and the filter becomes useless (worse than useless — pure overhead). Size for **peak** `n`, or use a **Scalable** filter (§5). Bigger-than-needed is fine; smaller-than-actual is fatal.
> - **Using it where most lookups are positives.** A Bloom filter earns its keep by turning **absent** queries into an instant in-RAM "no." If almost everything you look up actually *exists*, it answers "probably present" nearly every time, so you do the expensive lookup anyway — you've just added a hash step for nothing. It's a filter for **misses**, not hits.
> - **Clearing bits to "delete."** Zeroing an item's `k` bits can un-set bits that **other** items still rely on, producing a **false negative** — the one thing the structure promises never happens (§5). Plain Bloom filters have no `remove` on purpose; if you need deletes, use a **Counting** or **Cuckoo** filter.

---

## 7. Interview Cheat Sheet

> **"What's a Bloom filter and when do you use it?"**
> "A bit array + k hashes giving probabilistic membership: **no false negatives, some false positives**, in tiny space. Use it to avoid expensive lookups (DB/disk/network) for items that don't exist — e.g. 'is this key/URL/username present?' If it says absent, skip the lookup; if 'probably present,' do the real check."

> **"What's the catch?"**
> "False positives (occasional unnecessary lookups) and **no deletion** in the plain version (use a Counting Bloom filter). It fills up as you add items, raising the false-positive rate — size it for your `n` and target `p`."

> **"Why is 'no false negatives' the key property?"**
> "It makes the filter safe as a gate: a definite 'absent' is trustworthy, so you never wrongly skip something that exists — you only occasionally do an extra lookup."

---

## 8. Final Takeaways

- Bloom filter = **space-efficient probabilistic set**: **no false negatives**, some false positives, O(k) ops.
- Use to **skip expensive lookups for absent items** (DB SSTables, crawled URLs, cache penetration).
- **No deletion** in plain form → Counting/Cuckoo/Scalable variants add it.
- Size `m` and `k` for your item count `n` and target false-positive rate `p`.

### Related notes

- [Caching Strategies](caching-strategies.md) — cache penetration defense
- [URL Shortener](../system-design/url-shortener-system-design.md) · [Web Crawler](../system-design/web-crawler-system-design.md) · [Distributed Cache](../system-design/distributed-cache-system-design.md)

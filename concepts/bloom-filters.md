# Bloom Filters

> A **probabilistic, space-efficient** set membership structure. It answers *"is X in the set?"* with **"definitely not"** or **"probably yes"** — **no false negatives, some false positives** — in tiny memory. Used to avoid expensive lookups (DB/disk/network) for items that don't exist.

> **How to read this doc:** each section has the dense summary first, then a **Plain-English** deep dive (analogies, annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

---

## Contents

- [1. The Problem It Solves](#1-the-problem-it-solves)
- [2. How It Works](#2-how-it-works)
- [3. Properties & Trade-offs](#3-properties--trade-offs)
- [4. Sizing](#4-sizing)
- [5. Variants](#5-variants)
- [6. Where It's Used](#6-where-its-used)
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

### Plain-English: the nightclub bouncer with a "definitely-not-on-the-list" gut

**Analogy: a bouncer who is great at rejecting, cautious about accepting.**

Imagine a club with a **guest list of 1 million names**. The bouncer can't memorize a million names, and phoning the office to check each name takes 10 seconds (the "expensive lookup"). So he uses a **cheap gut-check** he can do instantly:

- If his gut says **"nope, definitely never heard that name"** → he turns you away on the spot. He is **never wrong** about this — if you were really on the list, his gut would have twitched.
- If his gut says **"hmm, that rings a bell"** → he's **not sure**. Maybe you're on the list, maybe you just sound like someone who is. *Only then* does he phone the office to actually check.

That gut-check is the Bloom filter. It turns "check 1M names every time" into "phone the office only for the handful that sound familiar."

**The "have I definitely NOT seen this?" checklist** — a Bloom filter shines exactly when:

- The set is **huge** (millions/billions of items) and you can't hold it all in memory.
- The real lookup is **expensive** (disk read, network call, DB query).
- **Most queries are for things that don't exist** (missing keys, uncrawled URLs, unseen articles) — so a fast "definitely absent" saves the most work.
- You can **tolerate an occasional false alarm** (an extra unnecessary lookup) but **cannot** tolerate wrongly saying "absent" for something that exists.

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

### Plain-English: a row of light switches you flip ON

**Analogy: a long row of light switches, all OFF to start.**

Picture `m` light switches in a row, every one **OFF (0)**. You have `k` fixed "rules" (the hash functions) that, given a name, always point at the **same** handful of switch positions.

- **Adding a name** = run it through the `k` rules to get `k` positions, and flip those switches **ON**. You never turn a switch back off.
- **Checking a name** = run it through the same `k` rules and peek at those `k` switches:
  - If **any** of them is still **OFF**, the name was *never* added (adding it would have flipped that switch ON) → **"definitely absent."**
  - If **all** of them are **ON**, the name is **"probably present"** — but maybe those switches were flipped ON by *other* names that happened to share positions. That coincidence is a **false positive**.

Key mental model: the filter never stores the names themselves — only a smear of ON/OFF switches. That's why it's tiny, and also why it can't tell you *who* flipped a switch on.

### Plain-English: the whole thing in ~30 lines of Java

```java
class BloomFilter {

    private final boolean[] bits;   // the row of "light switches", all false (0) at start
    private final int m;            // number of bits (switches)
    private final int k;            // number of hash functions ("rules")

    BloomFilter(int m, int k) {
        this.m = m;
        this.k = k;
        this.bits = new boolean[m];  // Java initializes every slot to false = 0
    }

    // ADD: flip ON the k positions this item hashes to
    void add(String item) {
        for (int i = 0; i < k; i++) {
            int pos = hash(item, i) % m;   // rule #i → a position in [0, m)
            bits[pos] = true;              // flip that switch ON (never turned off)
        }
    }

    // CONTAINS: "probably present" only if ALL k positions are ON
    boolean contains(String item) {
        for (int i = 0; i < k; i++) {
            int pos = hash(item, i) % m;
            if (!bits[pos]) {
                return false;   // one switch is OFF → DEFINITELY absent (no false negatives)
            }
        }
        return true;            // every switch ON → PROBABLY present (could be a false positive)
    }

    // k "independent" hashes cheaply: mix one base hash with a seed i.
    // (Real impls use techniques like double hashing; this is the idea.)
    private int hash(String item, int seed) {
        int h = (item.hashCode() ^ (seed * 0x9E3779B1));   // combine item with the rule number
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

Notice there is **no `remove` method** — because turning a switch OFF could silently un-add some *other* name that shares that position (see §5 for the fix, a Counting Bloom filter).

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

### Plain-English: why false positives but NEVER false negatives

This is *the* question people trip on, so let's nail it with the light-switch model from §2.

#### Q: Why can it never give a false negative (say "absent" for something that IS there)?

Because **adding a name only ever flips switches ON, and nothing ever flips them back OFF.** So if you truly added `"cat"`, then `"cat"`'s `k` switches are guaranteed ON forever. When you later check `"cat"`, you look at those exact same `k` switches — they *must* all be ON — so the answer can never be "absent." A "definitely absent" happens only when *at least one* of the checked switches is OFF, and that's impossible for a name you actually added.

> One-liner: **"absent" is trustworthy because the only way to see an OFF switch is if nobody ever set it.**

#### Q: Then why CAN it give a false positive (say "present" for something that isn't)?

Because switches are **shared**. Different names can hash to overlapping positions. If `"cat"` turned ON positions 1,4,7 and `"owl"` turned ON 4,7,9, then a brand-new name `"fox"` whose positions happen to be 1,4,7 will find **all three ON** — even though `"fox"` was never added. The filter can't tell "these switches are ON *because of you*" from "these switches are ON *because of other names*." It only sees ON/OFF, not *who* flipped them.

```
"present" really means: "every switch you'd have flipped is already ON —
                         so MAYBE it was you, or maybe it was a mix of others."
"absent"  really means: "a switch you'd have flipped is OFF —
                         so it definitely wasn't you." (rock solid)
```

#### Q: What happens to the false-positive rate as I add more items?

More names → more switches flipped ON → more collisions → **more false positives.** Eventually, if you overfill the filter, *most* switches are ON and nearly everything reads "probably present" (useless). This is why sizing matters (§4): you pick `m` and `k` up front for how many items `n` you expect. False *negatives* never appear no matter how full it gets — only the false-*positive* rate degrades.

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

### Plain-English: how to actually pick `m` and `k`

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

#### Q: Why is there a "best" `k`? Wouldn't more hashes always be safer?

No — `k` has a sweet spot. Each hash you add flips **more** switches ON per item:

- **Too few hashes** → you check too few switches, so collisions slip through as false positives.
- **Too many hashes** → each add floods the array with ON switches, so the array **fills up faster** and *everything* starts colliding.

The formula `k = (m/n) · ln 2` gives the `k` that balances these — it's the point where about **half the bits are ON** when the filter is full, which mathematically minimizes false positives. More is not better; it's a tuned knob.

#### Q: What if I don't know `n` in advance?

Either **over-provision** `m` for the largest `n` you expect (a Bloom filter degrades gracefully — extra room just means fewer false positives), or use a **Scalable Bloom filter** (§5) that grows on demand while keeping the FP rate bounded.

---

## 5. Variants

| Variant | Adds |
| --- | --- |
| **Counting Bloom filter** | Counters instead of bits → supports **deletion** |
| **Scalable Bloom filter** | Grows as items are added (keeps FP rate bounded) |
| **Cuckoo filter** | Supports deletion, often better space + lookup |

### Plain-English: why plain Bloom filters can't delete (and how counting fixes it)

#### Q: Why can't I just flip the bits back OFF to delete an item?

Because bits are **shared** (the §3 insight again). Say `"cat"` set positions 1,4,7 and `"owl"` set 4,7,9. If you "delete" `"cat"` by flipping 1,4,7 back OFF, you also just turned OFF positions 4 and 7 — which `"owl"` still needs. Now `contains("owl")` sees an OFF switch and wrongly reports **"definitely absent"** — a **false negative**, which destroys the one guarantee the whole structure is built on. So plain Bloom filters simply forbid deletion.

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

### Plain-English: when to reach for one (and when NOT to)

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

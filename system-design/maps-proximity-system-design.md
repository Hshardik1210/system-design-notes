# Google Maps / Proximity Service — System Design

> **Core challenge:** two related problems — **"find places/friends/drivers near me"** (proximity search over billions of points) and **routing/ETA** (shortest path over a road graph with live traffic). The heart is a **geospatial index** (geohash / quadtree / S2 / H3) and, for maps, **graph routing with precomputation**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Two Sub-Problems](#1-two-sub-problems)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Geospatial Indexing](#5-geospatial-indexing)
- [6. Proximity Search ("nearby")](#6-proximity-search-nearby)
- [7. Routing & ETA](#7-routing--eta)
- [8. Live Location (moving objects)](#8-live-location-moving-objects)
- [9. Data Model](#9-data-model)
- [10. Sequences](#10-sequences)
- [11. Consistency & Edge Cases](#11-consistency--edge-cases)
- [12. Scaling & Failure](#12-scaling--failure)
- [13. Interview Cheat Sheet](#13-interview-cheat-sheet)
- [14. API Design](#14-api-design)
- [15. Consistency & CAP Tradeoffs](#15-consistency--cap-tradeoffs)
- [16. How to Drive the Interview (framework)](#16-how-to-drive-the-interview-framework)
- [17. Design Patterns (that can be used)](#17-design-patterns-that-can-be-used)
- [18. Final Takeaways](#18-final-takeaways)

---

## 1. Two Sub-Problems

```
A. Proximity search   → "restaurants / friends / drivers within 2km of (lat,lng)"   (Yelp, Nearby, Uber)
B. Routing + ETA      → "fastest path from A to B given current traffic"            (Google Maps directions)
```

Most interviews focus on **(A) proximity** (a clean geo-index problem); **(B) routing** is a graph/algorithms deep-dive.

### What problem are we even solving?

Open **Yelp** (or Google Maps) and tap **"restaurants near me."** In under a second you get a list: *Pizza place — 200m, Cafe — 450m, Sushi — 900m*, sorted closest-first. That is the whole game for **problem (A)**: out of **hundreds of millions** of places in the world, instantly find the handful near one point and rank them by distance.

Two flavors of "things," and they behave very differently:

- **Static points** — restaurants, ATMs, hospitals. They almost never move. Index them once, occasionally update.
- **Moving points** — Uber drivers, your friends sharing live location. They move every few seconds, so their positions are a **firehose** of constant updates.

**Problem (B)** is the *other* half of Google Maps: once you pick a destination, "**how do I drive there and how long will it take?**" That's not "find near me" — it's finding the **fastest road path** from A to B, adjusting for traffic. Different tool entirely (a graph, not a geo-index).

> One line: **(A) is "which points are near this dot?"** (a lookup problem), **(B) is "what's the best road route between two dots?"** (a pathfinding problem). This doc spends most of its time on (A) because that's the classic interview focus.

---

## 2. Requirements

> 💡 **Start here.** Clarify scope out loud — say up front whether you're building **(A) proximity**, **(B) routing**, or both, since they need completely different machinery.

**Functional**
- Given `(lat, lng, radius)`, return nearby entities (places/users/drivers) ranked by distance (+ filters).
- Add/update entity locations (static places or moving objects).
- (Maps) Directions + ETA between two points with live traffic.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Latency** | Proximity query p99 < ~100ms; driver ping ingest must be near-instant. |
| **Scale** | Billions of static points; millions of live movers; **very high read QPS** (every app-open / map pan). |
| **Freshness** | Live positions may be **seconds stale** (fine); place metadata edits should show up quickly. |
| **Availability** | AP for the read/serve path — a slightly old dot beats an error. |
| **Skew** | Handle dense cities (Manhattan) without overloading a single cell/shard. |

### Out of scope (state assumptions)

- Turn-by-turn voice nav, satellite tiles/rendering, offline maps, indoor maps, Street View (mention, then defer).

---

## 3. Capacity Estimation

```
Places ~ 100M's (static) · moving objects (drivers) ~ 1M+ live, pinging every few sec (firehose)
Proximity QPS ~ very high (every app open / map pan) → in-memory geo index + cache
Location writes ~ 250k+/sec for moving objects → Redis GEO + stream, NEVER the transactional DB
Road graph (routing): ~100M's of nodes/edges → precomputed shortcuts, held in memory/specialized store
```

> Proximity read path must be **in-memory / cache**; moving-object writes are a **firehose** (Redis + stream); routing is precompute-heavy.

### Show the method (back-of-envelope)

> Numbers are illustrative — the point is to **derive** them out loud, not to be exact.

```
Assume:
  DAU                       ~ 100M
  Live drivers (ride-hail)  ~ 1M online, ping every 4s

Driver-ping firehose (WRITES):
  1M drivers / 4s          = 250k location writes/sec     → Redis GEO overwrite, NEVER the SQL DB
  Rush hour (~2x)          ~ 500k writes/sec

Proximity QPS (READS):
  100M DAU × ~5 map-opens/searches per day
  100M × 5 / 86,400s       ~ 5,800 reads/sec (avg)
  Commute peak (~10x)      ~ 60k reads/sec                → in-memory geo index + hot-cell cache

Road-graph memory (ROUTING):
  ~100M intersections (nodes) + ~250M road segments (edges)
  node ~16 B (id+lat/lng), edge ~24 B (to+dist+time)
  100M×16B + 250M×24B      ~ 1.6 GB + 6 GB ≈ 8 GB base graph
  + CH shortcuts (~1–2× edges)  → ~15–20 GB → fits in RAM on a big box, or shard by region
```

**Takeaways that drive design:** writes are dominated by the **ping firehose** (Redis, not RDBMS); reads are dominated by **proximity QPS** (in-memory index + cache); routing cost is **memory + offline precompute**, not per-request CPU.

---

## 4. Architecture

```
Client → API Gateway
  ├── Proximity Service  → geo index (S2/geohash/PostGIS/Redis GEO) + cache (hot cells)
  ├── Location Service   → Redis GEO (moving objects) + Kafka stream (history)
  ├── Routing Service    → road graph + precomputed shortcuts (CH) + live-traffic weights
  └── Traffic Pipeline   → GPS probes → Kafka → per-edge speed estimates → update weights
             │
       Places store (PostGIS/ES) rebuilt via CDC ; road graph store (specialized)
```

---

## 5. Geospatial Indexing

Index 2D points so "nearby" is fast (not a full scan / O(n) distance compute).

| Technique | Idea | Note |
| --- | --- | --- |
| **Geohash** | Encode (lat,lng) → base32 string; shared prefix = spatial proximity | Simple; query cell + **8 neighbors**; boundary edge cases; longer prefix = smaller cell |
| **QuadTree** | Recursively split space into 4 quadrants until ≤ K points/node | **Adapts to density** (deeper where dense) |
| **S2 (Google)** | Sphere → 64-bit cell ids via a Hilbert space-filling curve | Excellent range queries; hierarchical levels |
| **H3 (Uber)** | **Hexagonal** hierarchical grid | Uniform neighbor distances → great for dispatch/coverage |
| **Redis GEO / PostGIS** | Built-in geo ops (`GEOSEARCH`, `ST_DWithin`, GIST index) | Practical serving layer |

- **Geohash precision ↔ cell size:** ~5 chars ≈ 5km, ~6 ≈ 1.2km, ~7 ≈ 150m. Pick precision so the query radius spans a few cells.
- **Why not a lat/lng B-tree?** A range on lat AND lng returns a big **rectangle** you'd still distance-filter, and it doesn't cluster nearby points. Geo-cells turn "nearby" into a **cell/prefix lookup**.

### Why not just measure distance to every place?

First instinct: to find restaurants near me, loop over **all** restaurants, compute the distance to each, keep the close ones.

```java
// The naive approach — DO NOT do this at scale
List<Place> nearby = new ArrayList<>();
for (Place p : ALL_PLACES) {                 // ALL 100,000,000 of them!
    double d = haversine(me.lat, me.lng, p.lat, p.lng);   // distance formula
    if (d <= radiusKm) nearby.add(p);
}
```

Why it collapses:

- **100M places × every query.** One "near me" tap does 100M distance calculations. Thousands of people tap at once → billions of calculations per second. Melts.
- **99.999% is wasted work.** A restaurant in Tokyo is obviously irrelevant to someone in Delhi, but this loop still measures the distance to it.

**Key insight that drives the whole design:**

> **Don't scan the whole world. First cheaply narrow down to "roughly the right neighborhood," then do exact distance math on just those few hundred candidates.**

The trick to "narrow down cheaply" is a **geospatial index**: chop the map into **cells** (little tiles) and label each place with the cell it sits in. To find places near me, I compute *my* cell, grab only the places in that cell (and its neighbors), and ignore the entire rest of the planet. 100M distance checks becomes ~200.

### Geohash — turn (lat, lng) into a prefix

A geohash turns any `(lat, lng)` into a short string like `tdr1y2`, with one key property: **the longer the prefix two points share, the closer they are.** A shorter shared prefix means a larger enclosing region; a longer one means a smaller, more exact cell — the same hierarchical narrowing you see in postal codes, but precise and global.

```java
// Encode a point to a geohash string. Longer 'precision' = smaller, more exact cell.
String cell = geohash(28.6139, 77.2090, 6);   // e.g. "ttnfv2"  (~1.2 km tile)

// The magic property: nearby points share a prefix.
geohash(28.6139, 77.2090, 6);  // "ttnfv2"
geohash(28.6140, 77.2091, 6);  // "ttnfv2"  ← same tile, next-door
geohash(28.7041, 77.1025, 6);  // "ttng8x"  ← shares "ttn" prefix → same broad area
```

So "find nearby" becomes a **string prefix lookup** — something every database is fast at:

```java
List<Place> nearbyCandidates(double lat, double lng) {
    String myCell = geohash(lat, lng, 6);              // my ~1.2km tile
    // "give me every place whose geohash starts with my tile's code"
    return db.query("SELECT * FROM places WHERE geohash LIKE ?", myCell + "%");
}
```

We went from "measure distance to 100M places" to "fetch the ~50 places that share my tile's code." That short list is then distance-filtered exactly (see §6).

### Quadtree — split only where it's crowded

Geohash cells are a **fixed grid** — every tile is the same size. Problem: a tile in the **Sahara desert** holds 0 places, but the same-size tile in **Manhattan** holds 5,000. One is empty, the other is overloaded.

**A quadtree fixes this by splitting a tile into 4 smaller tiles only when it gets too full** — like zooming in on a crowded map until each area is manageable.

```java
class QuadNode {
    Box region;                       // the rectangle this node covers
    List<Place> places = new ArrayList<>();
    QuadNode[] children;              // null until we split into 4
    static final int CAPACITY = 100;  // split once a node holds > 100 places

    void insert(Place p) {
        if (children != null) {                 // already split → hand to the right quadrant
            childContaining(p).insert(p);
            return;
        }
        places.add(p);
        if (places.size() > CAPACITY) {          // too crowded → split into 4 quadrants
            split();                             // NW, NE, SW, SE
            for (Place moved : places) childContaining(moved).insert(moved);
            places.clear();                      // pushed down into children
        }
    }
}
```

Result: **empty regions stay one big cell; dense regions become many tiny cells.** A "near me" search walks down the tree to the small node covering my location, so a dense city doesn't dump 5,000 candidates on me. This "adapts to density" is the quadtree's whole selling point.

### S2 and H3 — the industrial-strength versions

Geohash and quadtree are the intuition. Real companies often use two more advanced schemes that solve subtle problems:

- **S2 (Google):** wraps the grid around a **sphere** (the Earth is round, not a flat rectangle) and numbers every cell with a single 64-bit integer via a **space-filling curve** (a clever snake-like ordering so that nearby cells get nearby numbers). Benefit: "give me a region" becomes a fast **range query on integers** (`id BETWEEN a AND b`), and it has clean hierarchical levels (zoom in/out).
- **H3 (Uber):** uses **hexagons** instead of squares. Why hexagons? Every neighbor of a square isn't the same distance away (diagonal neighbors are farther than side neighbors), but **every hexagon neighbor is equidistant.** That makes "expand my search outward evenly" and driver-dispatch coverage much cleaner.

```java
// All four schemes answer the SAME question — "what cell is this point in?" —
// they just differ in cell SHAPE and how the id is encoded.
String gh = geohash(lat, lng, 7);   // "ttnfv2j"     → string, fixed square grid
long   s2 = s2CellId(lat, lng, 15); // 9749618...    → 64-bit int on a sphere
long   h3 = h3Index(lat, lng, 9);   // 0x8928308...  → 64-bit int, hexagons
```

```
Geohash : simplest; string prefixes = proximity;      fixed square grid
QuadTree: adapts to density (splits crowded areas);   tree you walk down
S2      : sphere-correct; integer range queries;      squares, Hilbert-curve ids
H3      : equal-distance neighbors;                   hexagons, great for dispatch/coverage
```

#### Q: Geohash vs quadtree vs S2 vs H3 — which do I pick?

They all do the same core job (map a point → a cell so "nearby" is a lookup). Pick by pain point:

| If you care about... | Use | Because |
| --- | --- | --- |
| Simplicity, works in any DB via string `LIKE` | **Geohash** | Prefix = proximity, zero special libraries |
| Wildly uneven density (empty deserts + packed cities) | **QuadTree** | Splits only where crowded — no overloaded cells |
| Fast region range-queries + correct on a round Earth | **S2** | Integer cell ids on a space-filling curve |
| Even, equidistant neighbors (ride dispatch, coverage) | **H3** | Hexagons neighbor each other uniformly |

In an interview: "I'd use a geospatial index like geohash/S2; geohash if I want simple prefix lookups, quadtree if density is very skewed, S2/H3 for a production system at scale."

#### Q: H3 (hexagons) vs S2 (squares) vs geohash — hexes or squares, and when?

The tie-breaker between the two production systems is the **cell shape**, and it maps onto two different jobs:

| Cell shape | Neighbors | Best at | Real user |
| --- | --- | --- | --- |
| **Hexagon (H3)** | **6, all equidistant** | expanding a search **evenly ring-by-ring**; per-cell aggregates (supply/demand, surge, coverage) | **Uber dispatch** |
| **Square (S2)** | 8 at **two** distances (4 sides + 4 diagonals) | fast **range queries over a region** (`id BETWEEN a AND b` on the Hilbert curve) | **Google Places / maps tiles** |
| **String (geohash)** | 8, prefix-based | dead-simple prefix lookups in **any** DB | quick prototypes, `LIKE` queries |

> 💡 **Why Uber picked hexagons:** with squares, a diagonal neighbor is ~1.4× farther than a side neighbor, so a "ring" of neighbors mixes distances and **biases** any per-cell average. Hex cells have **6 neighbors all the same distance away**, so "grow the search outward" and "average wait time per cell" are unbiased and uniform.

**Worked example — Uber matching a rider to the nearest driver:** start in the rider's H3 cell, then walk outward one **ring** at a time (ring 1 = 6 cells, ring 2 = 12, …). Because every cell in a ring is the same distance out, the first driver found is genuinely among the closest, and surge/supply computed per cell is fair. Do the same with square cells and the diagonal corners reach ~40% farther than the sides — the "nearest" you find is skewed toward the diagonals.

**Worked example — Google Places answering "all cafés in this map viewport":** the viewport is a **rectangle**, and S2's 64-bit Hilbert-curve ids make that a couple of integer **range scans** (`WHERE s2cell BETWEEN a AND b`). Hexagons don't tile a rectangle cleanly, so they're a poor fit for "everything inside this box" — which is exactly what a maps/search product needs. Different job, different shape.

> ⚠️ **Pitfall:** don't fixate on hex-vs-square in an interview. All three answer the same core question (point → cell). Pick H3 when the workload is *dispatch/coverage* (even outward growth), S2 when it's *region/viewport range queries*, geohash when you just want a prefix in a plain DB.

#### Q: The boundary problem — what if a close place sits in the *next* tile?

This is the classic gotcha. Cells have hard edges, but the real world doesn't. A restaurant **20m away** could be **just across a cell boundary**, in a *different* tile from me:

```
 my tile "ttnfv2"          neighbor tile "ttnfv3"
┌───────────────┐        ┌───────────────┐
│        [me]   │        │ [pizza — only │
│               │        │   20m away!]  │
└───────────────┘        └───────────────┘
        ↑ if I only search MY tile, I MISS this pizza place
```

**Fix: always search my cell PLUS its 8 surrounding neighbor cells** (up, down, left, right, and 4 diagonals):

```java
List<Place> candidates(double lat, double lng) {
    String myCell = geohash(lat, lng, 6);
    List<String> cellsToSearch = new ArrayList<>();
    cellsToSearch.add(myCell);
    cellsToSearch.addAll(neighbors8(myCell));   // the 8 tiles touching mine
    return db.placesInAnyCell(cellsToSearch);    // union of all 9 tiles
}
```

Searching 9 tiles instead of 1 guarantees you don't miss a close point hugging a border. (S2/H3 have neighbor lookups built in; H3's hexagons make "neighbors" especially tidy — 6 equal ones.)

Choosing the cell size (precision) is the practical knob: match the cell size to the **search radius** so the circle spans just **a few** cells — not one giant cell (too many candidates) and not thousands of tiny cells (too many lookups).

```
radius ~5 km   → geohash length 5  (~5 km tiles)
radius ~1 km   → geohash length 6  (~1.2 km tiles)
radius ~150 m  → geohash length 7  (~150 m tiles)
```

Rule of thumb: **cell size ≈ the query radius.** Big radius → coarser (bigger) cells; small radius → finer (smaller) cells.

#### Q: Why is a plain `(lat, lng)` database index not enough?

You might think: just index `lat` and `lng` and do `WHERE lat BETWEEN ... AND lng BETWEEN ...`. Two problems:

- It returns a **rectangle**, not a circle — you still distance-filter every point inside to get the true radius.
- A B-tree on `lat` clusters points by *latitude only*. Two points on the **same latitude but opposite sides of the Earth** sit next to each other in the index. It doesn't cluster **truly nearby** points together. A geo-cell does — that's the entire point of encoding both dimensions into one ordered cell id.

---

## 6. Proximity Search ("nearby")

- **Neighbor cells** handle the boundary case (a nearby point can sit in an adjacent cell).
- **Dense areas (Manhattan):** a cell may hold huge counts → **finer cells (quadtree adapts)** or cap/paginate; adaptively increase precision.
- **Sparse areas / large radius:** widen to coarser cells so you don't query thousands.
- **Static places** → precompute a geo-index (S2/PostGIS/Elasticsearch geo), **cache hot cells**.
- Full annotated `searchNearby()` in the deep dive below.

### The two-step approach (narrow, then measure)

Every "nearby" search is **two steps**, and mixing them up is the #1 beginner mistake:

1. **Narrow (cheap, approximate):** use the geo-index to grab the ~hundreds of places in my cell + neighbor cells. This throws away 99.999% of the world for almost free.
2. **Measure (exact, but only on the survivors):** compute the *real* distance to just those candidates, drop the ones outside the radius, and **sort closest-first**.

The cell lookup narrows the search space to a small region first, so the expensive exact-distance math runs on only a few hundred candidates instead of every point on the planet.

```java
List<Place> searchNearby(double lat, double lng, double radiusKm, int topN) {

    // STEP 1 — NARROW: cheap cell lookup (my tile + 8 neighbors), covers the radius + borders
    List<Place> candidates = candidates(lat, lng);   // maybe ~200 places, not 100,000,000

    // STEP 2 — MEASURE: exact distance only on the survivors
    return candidates.stream()
        .map(p -> new Ranked(p, haversine(lat, lng, p.lat, p.lng)))  // true distance
        .filter(r -> r.distanceKm <= radiusKm)        // inside the real circle (not the square)
        .sorted(Comparator.comparingDouble(r -> r.distanceKm))       // closest first
        .limit(topN)                                                  // top-N results
        .map(r -> r.place)
        .toList();
}
```

Why `haversine`? It's the formula for distance between two points **on a sphere** (the Earth is round), so it's more correct than flat-plane distance over long spans.

> 💡 **Haversine, for beginners.** It computes the **great-circle distance** — the straight "as the crow flies" path across the *curved* surface of the Earth (like a string pulled taut over a globe). The formula:
>
> ```
> a = sin²(Δφ/2) + cos φ₁ · cos φ₂ · sin²(Δλ/2)
> c = 2 · atan2(√a, √(1−a))
> d = R · c                    (R ≈ 6371 km = Earth's radius)
> ```
>
> where **φ = latitude, λ = longitude** (in radians) and **Δ = the difference** between the two points. **Intuition:** over a few km, flat-plane Pythagoras (`√(Δx²+Δy²)`) is basically fine; over hundreds of km the Earth's curvature matters and haversine is the honest measure. In an interview you don't recite the formula — you say *"exact distance uses haversine (great-circle), because the Earth is a sphere."*

### Ranking by more than distance

Yelp doesn't just show the *nearest* restaurant — a mediocre place 50m away shouldn't beat a beloved one 200m away. Real "nearby" ranks by a **blend of distance and relevance** (rating, popularity, whether it's open):

```java
double score(Place p, double distanceKm) {
    double distanceScore  = 1.0 / (1.0 + distanceKm);  // closer = higher (→1), farther → 0
    double ratingScore    = p.rating / 5.0;            // 0..1
    // weighted blend — tune the weights to taste; distance usually dominates
    return 0.7 * distanceScore + 0.3 * ratingScore;
}
```

The geo-index still does the heavy lifting (find the candidates); ranking is just how you *order* the short survivor list. This is why "nearby places" often layer a **search engine like Elasticsearch** (geo-filter + relevance scoring) on top of the raw geo-index.

Two edge cases on the candidate count are worth planning for. If a cell and its neighbors return *thousands* of candidates (busy downtown), two levers help: use a **finer cell size** so each tile holds fewer places (a quadtree does this automatically by splitting crowded areas), and **cap + paginate** — return the top 20, remember where you stopped, fetch more only if the user scrolls. You never need to distance-check 5,000 places to show a screen of 20. If instead the cell and neighbors return *too few* (middle of nowhere, big radius), flip it: use **coarser (bigger) cells** so a large radius doesn't force you to query hundreds of tiny tiles — match cell size to radius as above.

For the hot read path, the geo-index usually lives **in memory / a cache** (e.g. Redis GEO, or an in-process quadtree) rather than being queried from the database on every search, because proximity QPS is enormous (every app-open, every map pan). The transactional DB is the source of truth, but you don't hit it per keystroke — you **cache hot cells** (§12).

### Two close cousins: reverse geocoding & place autocomplete

Two features ride on top of the same geo-index and come up as follow-ups:

- **Reverse geocoding** — turn a raw `(lat, lng)` into a human address ("12 MG Road, Bengaluru"). It's a **nearest-neighbour lookup** against an indexed set of address points / road segments: snap the coordinate to the closest known feature, exactly the "narrow to a cell, then measure" pattern. (The forward direction — address → coordinate — is plain geocoding.)
- **Place autocomplete** — as the user types "piz…", suggest "Pizza Hut, Koramangala." This is a **prefix/typeahead search biased by location** (rank suggestions near the user first). It's really a text-search problem layered on geo-filtering, so it's usually served by a search engine (Elasticsearch) or a dedicated typeahead service — see [Typeahead / Autocomplete](typeahead-autocomplete-system-design.md).

---

## 7. Routing & ETA

Directions = shortest path over a **road graph** (nodes = intersections, edges = road segments; weight = travel time).

| Technique | Note |
| --- | --- |
| **Dijkstra / A\*** | Baseline; A* with a distance heuristic prunes the search → faster |
| **Bidirectional search** | Search from both source and target → meet in the middle |
| **Contraction Hierarchies (CH)** | Precompute shortcuts → answer continental queries in **ms** (real maps don't run raw Dijkstra live) |
| **Graph partitioning** | Split the map into regions; precompute cross-region paths |
| **Live traffic** | Edge weights updated from real-time GPS/probe speeds → dynamic ETA |

```
ETA = Σ edge travel times along the path, using CURRENT traffic-adjusted weights (+ ML from history)
Traffic pipeline: GPS probes → Kafka → per-edge speed estimate → update edge weights (near-real-time)
```

- **Interview depth:** mention A* + **precomputation (CH)** + **traffic-adjusted weights** — don't hand-roll Dijkstra unless asked.

### The map is a graph, driving directions are a shortest path

Forget geo-cells for a second — routing is a **different problem** with a **different data structure**.

The road network is a **weighted graph**: **intersections** are nodes, **road segments** are edges, and each edge's **weight** is its travel time (not just distance — a short road in gridlock is "expensive"). "Get me from A to B fastest" means finding the cheapest chain of edges from node A to node B.

```java
class RoadGraph {
    // node = intersection; edge = road segment with a travel-time cost
    Map<Long, List<Edge>> adjacency;   // nodeId -> roads leaving it
}
record Edge(long toNode, int distanceMeters, double travelTimeSeconds) {}
```

The textbook shortest-path algorithm is **Dijkstra** (explore outward from A, always expanding the cheapest-so-far frontier until you reach B). **A\*** is Dijkstra plus a hint: "B is to the north-east, so prefer exploring that way" — it prunes obviously-wrong directions and finishes faster.

#### Q: Why don't real maps just run Dijkstra live on every request?

Because the graph is **continental** — hundreds of millions of intersections. Running Dijkstra across a whole country per request would take seconds and cost a fortune, times millions of users. Way too slow.

**The fix is precomputation — Contraction Hierarchies (CH).** Offline (ahead of time), you compute **shortcut edges** that summarize long stretches of road ("from this highway on-ramp to that exit = 40 min, skip the 500 intermediate intersections"). At query time you hop across a few shortcuts instead of crawling every intersection, turning a cross-country route into a **millisecond** answer.

```
Without precompute:  A → (crawl 10,000,000 intersections with Dijkstra) → B   ← seconds, too slow
With CH shortcuts:   A → (hop across ~dozens of precomputed shortcuts)   → B   ← milliseconds
```

The shortcuts let the query reason at the level of long-haul highways instead of every intermediate side street — the long stretches are collapsed into single precomputed edges.

#### Q: What exactly is a "shortcut edge"?

A **shortcut edge** is a *fake, precomputed* edge that stands in for a whole chain of real roads. Suppose the fastest way through a minor junction `B` is always `A → B → C`. Contraction Hierarchies **"contract"** (hide) `B` and add a direct edge `A → C` whose weight = `weight(A→B) + weight(B→C)`. Now a query can hop `A → C` in **one** step instead of visiting `B`.

```
Real graph:      A ── B ── C ── D ── E     (must visit every node)
After contract:  A ─────────────────► E    (one shortcut edge, weight = sum of the chain)
```

> 💡 **Analogy:** it's the **express train that skips the local stops.** You contract nodes bottom-up (least-important first), which builds a *hierarchy*: side streets at the bottom, highway-level shortcuts at the top. A query rides shortcuts down/up the hierarchy and touches a few dozen edges, not millions.

> ⚠️ **Pitfall:** shortcuts encode the graph's **structure**, so they must be rebuilt when roads change (new construction). **Live traffic** only changes edge *weights*, which is handled separately (customizable CH / overlay weights) so you don't re-contract the whole planet every minute.

The ETA changes with traffic because the **edge weights are live.** A separate **traffic pipeline** collects GPS "probe" pings from millions of phones/cars, estimates the current speed on each road segment, and continuously updates that edge's travel-time weight.

```java
// A GPS-probe pipeline turns raw location pings into live edge speeds
void onProbe(GpsPing ping) {
    long edgeId = snapToNearestEdge(ping.lat, ping.lng);   // which road segment is this car on?
    edgeSpeed.update(edgeId, ping.speed);                  // rolling estimate of current speed
    graph.setWeight(edgeId, distance(edgeId) / edgeSpeed.get(edgeId));  // slower speed = higher time
}

// ETA = sum of (traffic-adjusted) edge times along the chosen path
double eta(List<Edge> path) {
    return path.stream().mapToDouble(Edge::travelTimeSeconds).sum();
}
```

So when a jam forms, those edges get "heavier," the router naturally prefers a detour, and it **re-routes mid-trip** as weights change. Production ETAs also blend in **ML from historical patterns** (this road is always slow at 6 PM).

### Snap-to-road & rerouting (brief)

- **Snap-to-road** — raw GPS is noisy and lands *beside* the road (in a building, on the wrong lane). Before routing or map-matching, **snap** each point to the nearest road **edge** (the `snapToNearestEdge` call above). It's the same nearest-neighbour geo-lookup as reverse geocoding, but the candidates are road segments, not addresses. It's what makes the blue dot glide along the road instead of jittering across rooftops.
- **Rerouting mid-trip** — the client periodically reports position; if the driver leaves the planned path (missed a turn) **or** live edge weights change enough that another route is now faster, the router recomputes from the **current** location to the same destination. Because CH answers in milliseconds, rerouting is cheap enough to run continuously.

---

## 8. Live Location (moving objects)

For drivers/friends (constantly moving):

- Store latest position in **Redis GEO** (`GEOADD`), updated on each ping; **never** persist every ping to the transactional DB (firehose — see Ride-Hailing).
- Movement re-indexes the point (GEOADD overwrites); serve "nearby" from the **in-memory** index.
- Stream pings to **Kafka** for analytics/history; downsample.

### Static places vs moving drivers

A restaurant's position is fixed — set it once and it stays. An Uber driver's position changes every few seconds, so it must be re-indexed constantly. Same "find near me" question, very different write pattern:

| | **Static places** (restaurants, ATMs) | **Moving objects** (drivers, live friends) |
| --- | --- | --- |
| How often position changes | Almost never | Every few seconds |
| Write volume | Tiny | **Firehose** (250k+/sec) |
| Where it lives | Indexed DB (PostGIS/ES/S2) | **Redis GEO** (in-memory) |
| Keep every update? | n/a | **No** — only the *latest* position matters |

The killer detail: a moving object generates a **flood of location updates**, and you only ever care about *where it is now*, not its 900 previous pings. So each new ping just **overwrites** the last one in an in-memory geo-index.

```java
// Each driver ping = overwrite their single latest position (NOT append a new row)
void onDriverPing(String driverId, double lat, double lng) {
    // Redis GEOADD re-indexes the point in place — old position is simply replaced
    redis.geoAdd("drivers:online", lng, lat, driverId);   // in-memory, ~microseconds
    kafka.send("driver-pings", driverId, new Ping(driverId, lat, lng, now()));  // history/analytics only
}

// "Which drivers are within 3km?" — answered straight from the in-memory index
List<String> driversNear(double lat, double lng) {
    return redis.geoSearch("drivers:online", lng, lat, /*radiusKm*/ 3, "ASC");
}
```

#### Q: Why not store every driver ping in the main SQL database?

Because it's a **firehose**: 1M drivers pinging every ~4 seconds = **250k+ writes/sec**, and 99.99% of that data is instantly stale (you overwrite it 4 seconds later). A transactional DB would melt, and you'd be paying to durably store positions nobody will ever read. Instead: **latest position in Redis** (overwrite, in-memory, fast), and **stream the raw pings to Kafka** (downsampled) only if you need history/analytics later.

That does mean the position is always slightly out of date — and that's **fine**. A driver's dot being 2 seconds stale doesn't hurt anyone; by the time you tap, they've barely moved. This is **eventual consistency by choice**: we trade perfect freshness for enormous scale. (Set a TTL so a driver who goes offline disappears from the index automatically.)

---

## 9. Data Model

```sql
CREATE TABLE places (
    place_id  BIGINT PRIMARY KEY, name TEXT, category VARCHAR(50),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, geohash VARCHAR(12), rating NUMERIC(2,1)
);
CREATE INDEX idx_places_geohash ON places(geohash);   -- or PostGIS GIST index on geography

-- Moving objects (drivers/users) → Redis GEO, not RDBMS:
--   GEOADD objects:online <lng> <lat> obj:{id}
--   GEOSEARCH objects:online FROMLONLAT <lng> <lat> BYRADIUS 3 km ASC

-- Road graph (routing): nodes/edges + precomputed shortcuts (specialized store / in-memory)
CREATE TABLE road_node ( node_id BIGINT PRIMARY KEY, lat DOUBLE PRECISION, lng DOUBLE PRECISION );
CREATE TABLE road_edge ( from_node BIGINT, to_node BIGINT, distance_m INT, base_time_s INT,
                         PRIMARY KEY (from_node, to_node) );
CREATE TABLE edge_traffic ( edge_id BIGINT PRIMARY KEY, current_speed DOUBLE PRECISION, updated_at TIMESTAMP );
```

> **Stores to consider:** places (+ geo index: PostGIS/ES/S2), Redis GEO for moving objects, road graph (nodes/edges) + CH shortcuts, traffic time-series, search index.

### Database & storage choices (which DB, and why at scale)

This system is really two storage problems glued together — "where is everything, right now" (proximity) and "how do I get from A to B" (routing) — and each demands a different store. Deciding question: *"is this position looked up by coordinate, or traversed edge-by-edge as a graph?"*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Place metadata (name, category, rating) | **PostGIS / RDBMS** | Places rarely move; need joins/filters (category, rating) alongside geo predicates (`ST_DWithin`); GIST index makes geo range queries fast | A plain KV store can't filter "cafes rated 4+ within 2km" in one query — you'd fetch everything and filter in app code |
| Nearby-search index (geo cells) | **Geospatial index** — geohash/S2/H3, in Redis or a specialized index | Turns "who's near this point" into an O(1) cell lookup + a handful of neighbor cells, not a scan (§5) | A B-tree on raw `(lat,lng)` clusters by latitude only — two points on the same latitude but opposite sides of the planet sit next to each other in the index, so it can't answer "nearby" cheaply |
| Moving objects (driver/friend pings) | **Redis GEO / time-series** | 250k+ writes/sec of positions that go stale within seconds — an in-memory overwrite-in-place index is the only thing that keeps up; TTL auto-expires offline drivers | Writing every ping to the transactional DB is a firehose that drowns it for data nobody reads after 4 seconds (§8) |
| Road graph (nodes, edges) + routing shortcuts | **Graph/adjacency store + precomputed contraction hierarchies** | Routing is edge-traversal, not row lookup; CH shortcuts collapse millions of intersections into a handful of hops so continental routes answer in ms | An RDBMS table of edges *can* store the graph, but running live Dijkstra/A* over it per request at continental scale takes seconds — the win is entirely in offline precomputation, not the storage engine |

**Why not a plain `(lat,lng)` B-tree index, and why routing precomputes instead of querying live:** proximity search is only cheap if nearby points are *stored* near each other — that's what a geo-cell (geohash/S2/H3) buys you and a lat/lng B-tree doesn't, since a B-tree only clusters by one dimension at a time and a range on both `lat` and `lng` still returns a big rectangle you'd have to distance-filter (§5). The proximity read path lives in-memory/cache because it's hit on every app-open and map pan; PostGIS/ES hold the durable copy, and hot cells get cached in front of it, sharded by **geo region** with neighbor-shard fan-out at region boundaries — the same "always check the neighbors" pattern as cell boundaries, one level up. Routing takes the opposite approach to scale: instead of adding read replicas, it **precomputes offline** — contraction hierarchies collapse the graph so a live query only hops across a handful of shortcuts, because a country-sized road graph is too large to traverse node-by-node inside a request's latency budget. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### How a place is actually stored and looked up

Each place row carries its coordinates **and** its precomputed geo-cell, so the "narrow" step is a plain indexed column lookup:

```java
// Storing a place = save its coords AND its geohash (computed once at write time)
void savePlace(Place p) {
    p.geohash = geohash(p.lat, p.lng, 7);   // precompute the cell now, so reads are cheap
    db.insert(p);                            // the idx_places_geohash index makes cell lookups fast
}
```

The `CREATE INDEX ... ON places(geohash)` line above is what makes `WHERE geohash LIKE 'ttnfv2%'` fast — without it, that query would scan every row (back to the naive full scan we were escaping). **The geo-cell column + its index are the whole "geospatial index" in a plain SQL setup.** PostGIS's GIST index is the fancier, purpose-built version of the same idea.

### Sharding — splitting the world across machines

100M+ places (and the driver firehose) won't fit or serve from one machine. So you **shard**: split the data across many servers. The natural key for maps is **geography** — put all of India's data on the India servers, all of the US's on the US servers.

A "restaurants near me" query is **inherently local** — it only touches one region's shard — so geo-sharding gives you both **scale** (spread the load across servers) and **locality** (each query hits one shard, not all of them).

```java
// Route a request to the shard that owns its region
String shardFor(double lat, double lng) {
    String region = geohash(lat, lng, 2);   // coarse cell = a big region ("tt" ≈ north India)
    return shardMap.get(region);             // → which server holds this region's places
}
```

A query right on a shard boundary (near a border) is the same idea as the cell **boundary problem** (§5), one level up: a search near the edge of a region might have relevant points on the *neighboring* shard. You handle it the same way — the query fans out to the **neighboring region shards** too, then merges results. Boundaries are a recurring theme in geo systems: cells have edges, shards have edges, and you always cover the neighbors.

And one region (a megacity) is inevitably way busier than another (a desert) — that's **skew**, and it's why fixed grids struggle. Hot regions get **finer splitting** (a quadtree-style subdivision, or more shards for that area) and **caching of hot cells**, while empty regions stay coarse. You size shards by *load*, not by equal map area.

---

## 10. Sequences

### Nearby search

```
Client → ProximitySvc: (lat,lng,radius)
  → compute cell + neighbors → gather candidates (cache hot cells) → haversine filter → sort → top-N
```

### Route + ETA

```
Client → RoutingSvc: (A, B)
  → snap A,B to nearest road nodes → CH/A* over graph with traffic-adjusted edge weights → path
  → ETA = Σ edge times ; return polyline + steps ; recompute on reroute/traffic change
```

---

## 11. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Boundary point missed | Query neighbor cells too |
| Dense-cell overload | Finer precision (quadtree) + pagination |
| Large radius | Coarser cells; cap results |
| Moving-object staleness | Redis GEO latest position (TTL); eventual — a slightly old position is fine |
| Location firehose | Redis + Kafka only; never the transactional DB |
| Routing at continental scale | Precompute (CH) + region partitioning; not live Dijkstra |
| Traffic changes mid-trip | Re-route with updated edge weights |

---

## 12. Scaling & Failure

- **Shard by geo region** — a query is local → natural partitioning + locality.
- **Redis GEO / in-memory index** for moving objects; **PostGIS/ES/S2** for static places; **cache hot cells**.
- **Skew:** dense cities → finer cells (quadtree) + pagination.
- **Routing:** precompute (CH) + region partitioning; don't run live Dijkstra continent-wide.
- **Location pings** never hit the transactional DB (firehose → Redis + stream).
- **Traffic pipeline** updates edge weights near-real-time from GPS probes.

---

## 13. Interview Cheat Sheet

> **"How do you find things near me efficiently?"**
> "A **geospatial index** — geohash/quadtree/S2/H3 (or Redis GEO/PostGIS). Map the point to a cell, gather candidates from that cell **+ neighbor cells** (covering the radius + boundaries), then **exact-distance filter (haversine)** and sort. It avoids scanning all points; a plain lat/lng B-tree returns a big rectangle you'd still have to filter."

> **"Dense city hotspots?"**
> "Adaptive cells (**quadtree** splits where dense) or finer geohash precision + pagination, so a single cell isn't overloaded; coarser cells for large radii."

> **"How does routing/ETA work?"**
> "Model roads as a weighted graph; use **A\*** with **precomputation (contraction hierarchies)** — real maps don't run raw Dijkstra live. **ETA = traffic-adjusted edge weights** (from a GPS-probe pipeline), often ML-tuned from historical data; re-route on traffic changes."

> **"Moving objects (drivers)?"**
> "Latest position in **Redis GEO**, updated per ping; serve 'nearby' from memory; stream pings to Kafka; never persist every ping to the DB."

### Tricky scenarios (rapid-fire)

| Scenario | What to do |
| --- | --- |
| **A close place sits across a cell boundary** | Search my cell **+ neighbors** (8 for squares, 6 for H3 hexes) before distance-filtering — never just my own cell. |
| **Dense downtown cell returns 5,000 candidates** | **Finer cells** (quadtree splits crowded areas) + **cap/paginate** — never distance-check all 5k for a 20-row screen. |
| **Driver ping firehose (250k+/s)** | **Redis GEO overwrite** + **Kafka** stream for history; never the transactional DB. |
| **Driver goes offline / app killed** | **TTL** on the Redis GEO entry auto-expires the dot — no stale drivers linger. |
| **Continental route in one request** | **Precomputed CH shortcuts**, not live Dijkstra continent-wide. |
| **Traffic jam forms mid-trip** | GPS-probe pipeline raises edge weights → router **reroutes** to a detour. |
| **Query on a shard / region boundary** | Fan out to **neighbor region shards** and merge — same "check the neighbors" rule, one level up. |
| **Antimeridian / near the poles** | Use **S2/H3** (sphere-correct) rather than naive flat lat/lng math that breaks at ±180°. |
| **Noisy GPS lands beside the road** | **Snap-to-road** to the nearest edge before routing / drawing the trail. |

---

## 14. API Design

> Proximity is a **GET with geo query params**; the driver ping is a tiny **high-frequency POST** you treat as fire-and-forget.

```
GET  /v1/nearby?lat=12.97&lng=77.59&radius=2000&type=restaurant&openNow=true&limit=20&cursor=..
     → 200 { results:[ {id, name, lat, lng, distanceM, rating, openNow}, ... ], nextCursor }

POST /v1/location                         (driver/user ping — high frequency)
     body: { entityId, lat, lng, heading, speed, ts }
     → 202 Accepted                       (overwrites latest position in Redis GEO; no body needed)

GET  /v1/directions?from=12.97,77.59&to=12.93,77.62&mode=drive&departAt=now
     → 200 { routes:[ {distanceM, durationS, trafficDelayS, polyline, steps:[...] } ] }

GET  /v1/geocode/reverse?lat=12.97&lng=77.59      → nearest address for a coordinate
GET  /v1/places/autocomplete?q=piz&lat=12.97&lng=77.59  → location-biased suggestions (see Typeahead)
```

### `GET /v1/nearby` — proximity search

| Param | Meaning |
| --- | --- |
| `lat`, `lng` | center point of the search |
| `radius` | search radius in **metres** |
| `type` / `filters` | category (`restaurant`, `atm`, …), plus filters like `openNow`, `minRating` |
| `limit`, `cursor` | page size + opaque cursor for the next page (dense cities return many results) |

```json
// 200 OK
{
  "results": [
    { "id": "p_912", "name": "Pizza Place", "lat": 12.971, "lng": 77.594, "distanceM": 210, "rating": 4.4, "openNow": true },
    { "id": "p_338", "name": "Corner Cafe", "lat": 12.968, "lng": 77.588, "distanceM": 450, "rating": 4.1, "openNow": true }
  ],
  "nextCursor": "eyJvZmZzZXQiOjIwfQ=="
}
```

### `POST /v1/location` — driver/user ping

> ⚠️ This is the **firehose** endpoint (§8). It just **overwrites** the entity's latest position in Redis GEO and (optionally) streams the raw ping to Kafka — it must **not** write a row to the transactional DB per ping. Return **`202 Accepted`** and don't make the caller wait.

### `GET /v1/directions` — routing / ETA

```json
// 200 OK
{
  "routes": [
    {
      "distanceM": 5400,
      "durationS": 960,            // 16 min, traffic-adjusted
      "trafficDelayS": 180,        // 3 min of that is current congestion
      "polyline": "yzw{Fbb...",    // encoded path for the map
      "steps": [
        { "instruction": "Head north on MG Rd", "distanceM": 800, "durationS": 120 }
      ]
    }
  ]
}
```

> `distanceM` is fixed by the path; **`durationS` is dynamic** — it's the sum of *traffic-adjusted* edge weights (§7), so the same route returns a different ETA at rush hour.

---

## 15. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you pick consistency vs availability?" Geo systems are **mostly AP** — a slightly stale dot beats an error — but **place metadata** writes want CP.

| Path | Choice | Why |
| --- | --- | --- |
| **Driver / friend live position** | **AP** — Redis overwrite, **last-write-wins** | The newest ping is the only truth; a 2-second-old dot is harmless, and losing an error to stay available is the right trade (§8). |
| **Place metadata write** (add/edit a business, hours, category) | **CP** — strong, in the RDBMS | A business's address/hours must be correct and not diverge across replicas; low write volume, so consistency is cheap. |
| **Traffic edge weights** | **Eventual** | Speeds are aggregated from probes and converge; being a few seconds behind reality is expected and fine. |
| **Proximity read (nearby search)** | **AP** — cache / replica | Served from hot-cell cache / in-memory index; brief staleness (a place just opened) is acceptable. |

> One-liner: **"Live positions and reads are AP (last-write-wins, eventually consistent); place-metadata writes are CP. Trade freshness for availability everywhere movement is involved."**

---

## 16. How to Drive the Interview (framework)

> Use this order so you never freeze. **Spend ~70% on the geospatial index** (problem A) unless the interviewer explicitly steers you to routing (problem B).

1. **Clarify requirements + NFRs** — which sub-problem: proximity, routing, or both? — §2
2. **Estimate scale** — separate the **ping firehose** (writes) from **proximity QPS** (reads) — §3
3. **Define APIs** — `nearby`, `location`, `directions` — §14
4. **Architecture + data model** — §4, §9
5. **Deep dive: the geospatial index** (cells → neighbors → boundary → density → exact haversine) — §5, §6
6. **Moving objects** — Redis GEO overwrite + TTL, firehose off the DB — §8
7. **(If steered) routing** — graph + A\* + **CH precomputation** + traffic-adjusted weights — §7
8. **Consistency, scale, edge cases** — §11, §12, §15

> 🎤 **Lead with the core split:** "(A) 'nearby' is a **geo-index lookup**, (B) routing is **graph pathfinding** — I'll spend most of my time on the geo index unless you'd like the routing deep-dive." That framing alone signals you understand the problem.

---

## 17. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Spatial Index (geohash/quadtree/S2/H3)** | Core proximity structure | Fast nearby queries |
| **Strategy** | Index type, distance metric, routing algorithm | Swap per need |
| **Cache-Aside** | Hot cells / popular queries | Latency |
| **Sharding** | Partition by geo region | Scale + locality |
| **Producer-Consumer** | Location ping ingestion, traffic updates | Absorb firehose |
| **Precomputation / Materialized View** | Routing shortcuts (CH), cell candidate lists | ms-latency answers |
| **Repository** | Data access | Testable |
| **Facade** | Proximity/routing service API | Simplicity |

---

## 18. Final Takeaways

- Proximity = **geospatial index** (geohash/quadtree/S2/H3, Redis GEO/PostGIS): **cell + neighbor cells → exact-distance filter**.
- Plain lat/lng B-tree is poor; geo-cells cluster nearby points; pick precision by radius.
- **Shard by region**, cache hot cells, adapt to density (quadtree) for skew.
- **Routing** = weighted road graph + **A\* + precomputation (CH)** + **traffic-adjusted weights** (GPS-probe pipeline).
- **Moving objects** in Redis GEO (firehose off the DB).
- Patterns: Spatial Index, Strategy, Sharding, Cache-Aside, Precomputation, Producer-Consumer.

### Related notes

- [Ride-Hailing (Uber/Ola)](ride-hailing-system-design.md) · [Food Ordering](food-ordering-system-design.md) — geo-matching siblings
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)

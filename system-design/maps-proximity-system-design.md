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
- [12. Design Patterns (that can be used)](#12-design-patterns-that-can-be-used)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

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

**Functional**
- Given `(lat, lng, radius)`, return nearby entities (places/users/drivers) ranked by distance (+ filters).
- Add/update entity locations (static places or moving objects).
- (Maps) Directions + ETA between two points with live traffic.

**Non-functional**
- **Low latency** proximity queries; **huge scale** (billions of points, high QPS); handle **skew** (dense cities); **freshness** for moving objects.

---

## 3. Capacity Estimation

```
Places ~ 100M's (static) · moving objects (drivers) ~ 1M+ live, pinging every few sec (firehose)
Proximity QPS ~ very high (every app open / map pan) → in-memory geo index + cache
Location writes ~ 250k+/sec for moving objects → Redis GEO + stream, NEVER the transactional DB
Road graph (routing): ~100M's of nodes/edges → precomputed shortcuts, held in memory/specialized store
```

> Proximity read path must be **in-memory / cache**; moving-object writes are a **firehose** (Redis + stream); routing is precompute-heavy.

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

### Common confusions (Q&A)

#### Q: Geohash vs quadtree vs S2 vs H3 — which do I pick?

They all do the same core job (map a point → a cell so "nearby" is a lookup). Pick by pain point:

| If you care about... | Use | Because |
| --- | --- | --- |
| Simplicity, works in any DB via string `LIKE` | **Geohash** | Prefix = proximity, zero special libraries |
| Wildly uneven density (empty deserts + packed cities) | **QuadTree** | Splits only where crowded — no overloaded cells |
| Fast region range-queries + correct on a round Earth | **S2** | Integer cell ids on a space-filling curve |
| Even, equidistant neighbors (ride dispatch, coverage) | **H3** | Hexagons neighbor each other uniformly |

In an interview: "I'd use a geospatial index like geohash/S2; geohash if I want simple prefix lookups, quadtree if density is very skewed, S2/H3 for a production system at scale."

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

#### Q: How do I choose the cell size (precision)?

Match the cell size to the **search radius** so the circle spans just **a few** cells — not one giant cell (too many candidates) and not thousands of tiny cells (too many lookups).

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

```
search(lat, lng, radiusKm):
  level = pick cell precision so the circle spans a few cells
  cells = [cell(lat,lng,level)] + its neighbor cells        # cover radius + boundaries
  candidates = union of points in those cells
  filter: haversine(point, query) <= radius                 # exact distance
  sort by distance (+ rating/relevance); return top-N
```

- **Neighbor cells** handle the boundary case (a nearby point can sit in an adjacent cell).
- **Dense areas (Manhattan):** a cell may hold huge counts → **finer cells (quadtree adapts)** or cap/paginate; adaptively increase precision.
- **Sparse areas / large radius:** widen to coarser cells so you don't query thousands.
- **Static places** → precompute a geo-index (S2/PostGIS/Elasticsearch geo), **cache hot cells**.

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

### Common confusions (Q&A)

#### Q: What if my cell/neighbors return *thousands* of candidates (busy downtown)?

Two levers: (1) use a **finer cell size** so each tile holds fewer places (a quadtree does this automatically by splitting crowded areas), and (2) **cap + paginate** — return the top 20, remember where you stopped, fetch more only if the user scrolls. You never need to distance-check 5,000 places to show a screen of 20.

#### Q: What if my cell/neighbors return *too few* (middle of nowhere, big radius)?

Flip it: use **coarser (bigger) cells** so a large radius doesn't force you to query hundreds of tiny tiles. Match cell size to radius (see §5 precision Q&A).

#### Q: Do I query the database on every search, or is this in memory?

For the hot read path, the geo-index usually lives **in memory / a cache** (e.g. Redis GEO, or an in-process quadtree), because proximity QPS is enormous (every app-open, every map pan). The transactional DB is the source of truth, but you don't hit it per keystroke — you **cache hot cells** (§13).

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

#### Q: How does the ETA change with traffic?

The **edge weights are live.** A separate **traffic pipeline** collects GPS "probe" pings from millions of phones/cars, estimates the current speed on each road segment, and continuously updates that edge's travel-time weight.

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

#### Q: Isn't the position slightly out of date then?

Yes — and that's **fine**. A driver's dot being 2 seconds stale doesn't hurt anyone; by the time you tap, they've barely moved. This is **eventual consistency by choice**: we trade perfect freshness for enormous scale. (Set a TTL so a driver who goes offline disappears from the index automatically.)

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

#### Q: What about a query right on a shard boundary (near a border)?

Same idea as the cell **boundary problem** (§5), one level up: a search near the edge of a region might have relevant points on the *neighboring* shard. You handle it the same way — the query fans out to the **neighboring region shards** too, then merges results. Boundaries are a recurring theme in geo systems: cells have edges, shards have edges, and you always cover the neighbors.

#### Q: Isn't one region (a megacity) way busier than another (a desert)?

Yes — that's **skew**, and it's why fixed grids struggle. Hot regions get **finer splitting** (a quadtree-style subdivision, or more shards for that area) and **caching of hot cells**, while empty regions stay coarse. You size shards by *load*, not by equal map area.

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

## 12. Design Patterns (that can be used)

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

## 13. Scaling & Failure

- **Shard by geo region** — a query is local → natural partitioning + locality.
- **Redis GEO / in-memory index** for moving objects; **PostGIS/ES/S2** for static places; **cache hot cells**.
- **Skew:** dense cities → finer cells (quadtree) + pagination.
- **Routing:** precompute (CH) + region partitioning; don't run live Dijkstra continent-wide.
- **Location pings** never hit the transactional DB (firehose → Redis + stream).
- **Traffic pipeline** updates edge weights near-real-time from GPS probes.

---

## 14. Interview Cheat Sheet

> **"How do you find things near me efficiently?"**
> "A **geospatial index** — geohash/quadtree/S2/H3 (or Redis GEO/PostGIS). Map the point to a cell, gather candidates from that cell **+ neighbor cells** (covering the radius + boundaries), then **exact-distance filter (haversine)** and sort. It avoids scanning all points; a plain lat/lng B-tree returns a big rectangle you'd still have to filter."

> **"Dense city hotspots?"**
> "Adaptive cells (**quadtree** splits where dense) or finer geohash precision + pagination, so a single cell isn't overloaded; coarser cells for large radii."

> **"How does routing/ETA work?"**
> "Model roads as a weighted graph; use **A\*** with **precomputation (contraction hierarchies)** — real maps don't run raw Dijkstra live. **ETA = traffic-adjusted edge weights** (from a GPS-probe pipeline), often ML-tuned from historical data; re-route on traffic changes."

> **"Moving objects (drivers)?"**
> "Latest position in **Redis GEO**, updated per ping; serve 'nearby' from memory; stream pings to Kafka; never persist every ping to the DB."

---

## 15. Final Takeaways

- Proximity = **geospatial index** (geohash/quadtree/S2/H3, Redis GEO/PostGIS): **cell + neighbor cells → exact-distance filter**.
- Plain lat/lng B-tree is poor; geo-cells cluster nearby points; pick precision by radius.
- **Shard by region**, cache hot cells, adapt to density (quadtree) for skew.
- **Routing** = weighted road graph + **A\* + precomputation (CH)** + **traffic-adjusted weights** (GPS-probe pipeline).
- **Moving objects** in Redis GEO (firehose off the DB).
- Patterns: Spatial Index, Strategy, Sharding, Cache-Aside, Precomputation, Producer-Consumer.

### Related notes

- [Ride-Hailing (Uber/Ola)](ride-hailing-system-design.md) · [Food Ordering](food-ordering-system-design.md) — geo-matching siblings
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)

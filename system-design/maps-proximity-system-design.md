# Google Maps / Proximity Service — System Design

> **Core challenge:** two related problems — **"find places/friends/drivers near me"** (proximity search over billions of points) and **routing/ETA** (shortest path over a road graph with live traffic). The heart is a **geospatial index** (geohash / quadtree / S2 / H3) and, for maps, **graph routing with precomputation**.

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

---

## 8. Live Location (moving objects)

For drivers/friends (constantly moving):

- Store latest position in **Redis GEO** (`GEOADD`), updated on each ping; **never** persist every ping to the transactional DB (firehose — see Ride-Hailing).
- Movement re-indexes the point (GEOADD overwrites); serve "nearby" from the **in-memory** index.
- Stream pings to **Kafka** for analytics/history; downsample.

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

# Google Maps / Proximity Service — System Design

> **Core challenge:** two related problems — **"find places/friends near me"** (proximity search over billions of points) and **routing/ETA** (shortest path over a road graph with live traffic). The heart is a **geospatial index** (geohash / quadtree / S2 / H3) and, for maps, **graph routing with precomputation**.

---

## Contents

- [1. Two Sub-Problems](#1-two-sub-problems)
- [2. Requirements](#2-requirements)
- [3. Geospatial Indexing](#3-geospatial-indexing)
- [4. Proximity Search ("nearby")](#4-proximity-search-nearby)
- [5. Routing & ETA](#5-routing--eta)
- [6. Live Location (moving objects)](#6-live-location-moving-objects)
- [7. Data Model](#7-data-model)
- [8. Design Patterns (that can be used)](#8-design-patterns-that-can-be-used)
- [9. Scaling & Failure](#9-scaling--failure)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Two Sub-Problems

```
A. Proximity search   → "restaurants / friends / drivers within 2km of (lat,lng)"   (Yelp, Nearby, Uber)
B. Routing + ETA      → "fastest path from A to B given current traffic"            (Google Maps directions)
```

Most interviews focus on **(A) proximity** (a clean geo-index problem); (B) routing is a graph/algorithms deep-dive.

---

## 2. Requirements

**Functional**
- Given `(lat, lng, radius)`, return nearby entities (places/users/drivers), ranked by distance.
- Add/update entity locations (static places or moving objects).
- (Maps) Directions + ETA between two points with traffic.

**Non-functional**
- **Low latency** proximity queries; **huge scale** (billions of points, high query QPS); handle **skew** (dense cities); freshness for moving objects.

---

## 3. Geospatial Indexing

The core: index 2D points so "nearby" is fast (not a full scan / O(n) distance compute).

| Technique | Idea | Note |
| --- | --- | --- |
| **Geohash** | Encode (lat,lng) → base32 string; shared prefix = spatial proximity | Simple; query cell + 8 neighbors; boundary edge cases |
| **QuadTree** | Recursively split space into 4 quadrants until ≤ K points/node | Adapts to density (deeper where dense) |
| **S2 (Google)** | Map sphere → 64-bit cell ids via a space-filling (Hilbert) curve | Great range queries; used widely |
| **H3 (Uber)** | Hexagonal hierarchical grid | Uniform neighbor distances; used for dispatch |
| **Redis GEO / PostGIS** | Built-in geo ops (`GEOSEARCH`, `ST_DWithin`) | Practical serving layer |

> **Why not lat/lng B-tree?** A range on lat AND lng returns a big rectangle you still must distance-filter, and it doesn't cluster nearby points. Geo-cell indexes turn "nearby" into a **cell/prefix lookup**.

---

## 4. Proximity Search ("nearby")

```
search(lat, lng, radiusKm):
  cell = geohash(lat, lng, precision ~ radius)
  candidates = points in [cell + neighbor cells]     # covers the radius + edges
  filter: haversine_distance(point, query) <= radius # exact distance
  sort by distance; return top-N
```

- **Precision ↔ radius**: choose the cell size so the query circle spans a manageable number of cells; include neighbor cells to handle boundaries.
- **Dense areas (Manhattan)**: a cell may hold huge counts → use finer cells (quadtree adapts) or cap/paginate.
- **Static places** → precompute a geo-index (Elasticsearch geo / PostGIS / S2), cache hot cells.

---

## 5. Routing & ETA

Directions = shortest path over a **road graph** (nodes = intersections, edges = roads with weights = travel time).

| Technique | Note |
| --- | --- |
| **Dijkstra / A\*** | Baseline shortest path; A* with a distance heuristic is faster |
| **Contraction Hierarchies / precomputed shortcuts** | Precompute to answer continental queries in ms (real maps don't run raw Dijkstra live) |
| **Graph partitioning** | Split map into regions; precompute cross-region paths |
| **Live traffic** | Edge weights updated from real-time speed data (probe/GPS) → dynamic ETA |

- **ETA** = sum of edge travel times with current traffic; often **ML-adjusted** from historical patterns.
- Interview depth: mention A*, precomputation (CH), and traffic-adjusted edge weights — don't hand-roll Dijkstra unless asked.

---

## 6. Live Location (moving objects)

For drivers/friends (constantly moving):

- Store latest position in **Redis GEO** (`GEOADD`), update on each ping; **don't** persist every ping to the DB (firehose — see Ride-Hailing note).
- Re-index on movement (remove/add to cells) or rely on Redis GEO which handles updates.
- Serve "nearby" from the in-memory geo-index; stream history to analytics.

---

## 7. Data Model

```sql
CREATE TABLE places (
    place_id  BIGINT PRIMARY KEY, name TEXT, category VARCHAR(50),
    lat DOUBLE PRECISION, lng DOUBLE PRECISION, geohash VARCHAR(12),
    rating NUMERIC(2,1)
);
CREATE INDEX idx_places_geohash ON places(geohash);   -- or PostGIS GIST index on geography

-- Moving objects (drivers/users) → Redis GEO, not RDBMS:
--   GEOADD objects:online <lng> <lat> obj:{id}
--   GEOSEARCH objects:online FROMLONLAT <lng> <lat> BYRADIUS 3 km ASC

-- Road graph (routing): nodes/edges, often in a specialized store / precomputed
CREATE TABLE road_node ( node_id BIGINT PRIMARY KEY, lat DOUBLE PRECISION, lng DOUBLE PRECISION );
CREATE TABLE road_edge ( from_node BIGINT, to_node BIGINT, distance_m INT, base_time_s INT,
                         PRIMARY KEY (from_node, to_node) );
```

> **Stores to consider:** places (+ geo index: PostGIS/ES/S2), Redis GEO for moving objects, road graph (nodes/edges) + precomputed shortcuts, traffic time-series, search index.

---

## 8. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Spatial Index (geohash/quadtree/S2/H3)** | Core proximity structure | Fast nearby queries |
| **Strategy** | Index type, distance metric, routing algorithm | Swap per need |
| **Cache-Aside** | Hot cells / popular queries | Latency |
| **Sharding** | Partition by geo region | Scale + locality |
| **Producer-Consumer** | Location ping ingestion, traffic updates | Absorb firehose |
| **Repository** | Data access | Testable |
| **Precomputation / Materialized View** | Routing shortcuts, cell candidate lists | ms-latency answers |
| **Facade** | Proximity/routing service API | Simplicity |

---

## 9. Scaling & Failure

- **Shard by geo region**; a query is local → natural partitioning + locality.
- **Redis GEO / in-memory index** for moving objects; **PostGIS/ES/S2** for static places; **cache hot cells**.
- **Skew**: dense cities → finer cells (quadtree) + pagination.
- **Routing**: precompute (contraction hierarchies) + region partitioning; don't run live Dijkstra continent-wide.
- Location pings never hit the transactional DB (firehose → Redis + stream).

---

## 10. Interview Cheat Sheet

> **"How do you find things near me efficiently?"**
> "A geospatial index — geohash/quadtree/S2/H3 (or Redis GEO/PostGIS). Map the point to a cell, gather candidates from that cell + neighbors (covering the radius + boundaries), then exact-distance filter and sort. Avoids scanning all points; a plain lat/lng B-tree returns a big rectangle you'd still have to filter."

> **"Dense city hotspots?"**
> "Adaptive cells (quadtree splits where dense) or finer geohash precision + pagination, so a cell isn't overloaded."

> **"How does routing/ETA work?"**
> "Model roads as a weighted graph; use A* with precomputation (contraction hierarchies) — real maps don't run raw Dijkstra live. ETA = traffic-adjusted edge weights, often ML-tuned from historical data."

> **"Moving objects (drivers)?"**
> "Latest position in Redis GEO updated per ping; serve 'nearby' from memory; never persist every ping to the DB."

---

## 11. Final Takeaways

- Proximity = **geospatial index** (geohash/quadtree/S2/H3, Redis GEO/PostGIS): cell lookup + neighbor cells + exact-distance filter.
- Plain lat/lng B-tree is poor; geo-cells cluster nearby points.
- **Shard by region**, cache hot cells, adapt to density (quadtree) for skew.
- **Routing** = weighted road graph + A* + precomputation (CH) + traffic-adjusted weights.
- **Moving objects** in Redis GEO (firehose off the DB).
- Patterns: Spatial Index, Strategy, Sharding, Cache-Aside, Precomputation, Producer-Consumer.

### Related notes

- [Ride-Hailing (Uber/Ola)](ride-hailing-system-design.md) · [Food Ordering](food-ordering-system-design.md) — geo-matching siblings
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md)

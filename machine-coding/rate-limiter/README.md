# 🚦 Rate Limiter

Limit how many requests a client can make in a time window. Thread-safe, per-client, with the algorithm swappable via **Strategy**.

## Algorithms implemented
### 1. Token Bucket
- Bucket holds up to `capacity` tokens; refills at a steady rate.
- Each request consumes 1 token; empty bucket ⇒ reject.
- **Allows bursts** up to capacity, then smooths to the refill rate.

### 2. Sliding Window Log
- Keep timestamps of recent requests; drop those older than the window.
- Allow if count in window `< max`. **Exact and smooth** (no fixed-window burst edge), but stores per-request timestamps.

| Algorithm | Burst? | Memory | Notes |
| --- | --- | --- | --- |
| Token bucket | Yes (up to capacity) | O(1) per client | Most common in practice |
| Sliding window log | No | O(requests in window) | Most accurate |

## Design
- `RateLimitStrategy` interface → `TokenBucket`, `SlidingWindowLog`.
- `RateLimiter` holds **one strategy instance per client key** (created lazily) and delegates.
- Each strategy guards its own mutable state with a lock (Java `synchronized` / C++ `std::mutex`).

> At scale this state lives in **Redis** (shared across app servers) — see `concepts/rate-limiting.md`. This is the single-node in-memory version.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```

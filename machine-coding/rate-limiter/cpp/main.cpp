// RATE LIMITER — thread-safe, per-client, pluggable algorithm (Strategy) (C++17)
//
// Two algorithms:
//   1) Token Bucket       -> allows bursts up to capacity; refills steadily.
//   2) Sliding Window Log -> exact count in the last window; smooth.
// Each strategy guards its own state with a mutex.

#include <iostream>
#include <string>
#include <unordered_map>
#include <deque>
#include <mutex>
#include <chrono>
#include <thread>
#include <functional>
#include <algorithm>
#include <memory>
using namespace std;

// Helper: current time in milliseconds. steady_clock never jumps backward, so it is safe for timing.
static long long nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// ---------- Strategy interface ----------
// Strategy pattern: the common contract every rate-limiting algorithm implements.
// RateLimiter talks to this base type, so any algorithm can be plugged in interchangeably.
struct RateLimitStrategy {
    virtual ~RateLimitStrategy() = default;   // virtual destructor so deleting via base pointer is safe
    virtual bool allow(long long now) = 0;    // pure virtual: each algorithm must decide allow/reject
};

// ---------- 1) Token Bucket ----------
// Algorithm: a bucket holds up to `capacity` tokens and refills steadily over time.
// Each request spends 1 token; an empty bucket means reject. Allows short bursts up to capacity.
class TokenBucket : public RateLimitStrategy {
    double capacity;        // max tokens the bucket can hold (the burst size)
    double refillPerMs;     // tokens added per millisecond
    double tokens;          // current tokens available right now
    long long lastRefill;   // timestamp of the last refill
    mutex mtx;              // guards the mutable fields above for thread safety
public:
    TokenBucket(double cap, double refillPerSecond)
        // Convert the rate to tokens-per-ms, and start the bucket full.
        : capacity(cap), refillPerMs(refillPerSecond / 1000.0),
          tokens(cap), lastRefill(nowMillis()) {}

    // Refill based on elapsed time, then try to spend one token.
    bool allow(long long now) override {
        lock_guard<mutex> g(mtx);   // lock the mutex; auto-unlocks when this scope ends
        double elapsed = (double)(now - lastRefill);
        // Refill math: tokens earned = elapsed ms * rate per ms; cap so it never exceeds capacity.
        tokens = min(capacity, tokens + elapsed * refillPerMs);
        lastRefill = now;
        // Allow only if a whole token is available; spend it.
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        return false;
    }
};

// ---------- 2) Sliding Window Log ----------
// Algorithm: keep the timestamps of recent requests; drop those older than the window,
// then allow only if fewer than maxRequests remain. Exact and smooth, but uses more memory.
class SlidingWindowLog : public RateLimitStrategy {
    int maxRequests;              // how many requests are allowed within one window
    long long windowMs;          // window length in milliseconds
    deque<long long> timestamps; // request times, oldest at front, newest at back
    mutex mtx;                   // guards the timestamp queue for thread safety
public:
    SlidingWindowLog(int maxReq, long long window) : maxRequests(maxReq), windowMs(window) {}

    // Allow only if the number of requests still inside the window is below the limit.
    bool allow(long long now) override {
        lock_guard<mutex> g(mtx);
        // Slide the window: remove any timestamp older than windowMs from the front.
        while (!timestamps.empty() && now - timestamps.front() >= windowMs)
            timestamps.pop_front();                 // drop expired
        // Room left in the window: record this request and allow it.
        if ((int)timestamps.size() < maxRequests) { timestamps.push_back(now); return true; }
        return false;
    }
};

// ---------- Front-facing limiter: one strategy per client ----------
// The object callers use. It keeps a SEPARATE strategy per client key so one client
// cannot use up another client's quota, and delegates the real decision to that strategy.
class RateLimiter {
    function<shared_ptr<RateLimitStrategy>()> factory;               // builds a fresh strategy on demand
    unordered_map<string, shared_ptr<RateLimitStrategy>> perClient;  // client id -> its own strategy
    mutex mtx;                                                       // guards the map above
public:
    explicit RateLimiter(function<shared_ptr<RateLimitStrategy>()> f) : factory(move(f)) {}

    // Find (or lazily create) this client's strategy, then ask it to allow/reject.
    bool allow(const string& clientId) {
        shared_ptr<RateLimitStrategy> s;
        {
            // Hold the lock only while looking up/creating the strategy, not during allow(),
            // so different clients don't block each other on the actual decision.
            lock_guard<mutex> g(mtx);
            auto it = perClient.find(clientId);
            // First time we see this client: build its strategy and store it. Otherwise reuse it.
            if (it == perClient.end()) { s = factory(); perClient[clientId] = s; }
            else s = it->second;
        }
        return s->allow(nowMillis());
    }
};

// Demo: run each algorithm and print ALLOW/REJECT for a series of requests.
int main() {
    cout << "--- Token Bucket: capacity 5, refill 2/sec ---\n";
    RateLimiter tb([] { return make_shared<TokenBucket>(5, 2); });
    for (int i = 1; i <= 7; i++)
        cout << "req " << i << " -> " << (tb.allow("userA") ? "ALLOW" : "REJECT") << "\n";
    cout << "sleep 1s (refills ~2 tokens)...\n";
    this_thread::sleep_for(chrono::seconds(1));
    for (int i = 8; i <= 10; i++)
        cout << "req " << i << " -> " << (tb.allow("userA") ? "ALLOW" : "REJECT") << "\n";

    cout << "--- Sliding Window: 3 requests / 1000ms ---\n";
    RateLimiter sw([] { return make_shared<SlidingWindowLog>(3, 1000); });
    for (int i = 1; i <= 5; i++)
        cout << "req " << i << " -> " << (sw.allow("userB") ? "ALLOW" : "REJECT") << "\n";
    return 0;
}

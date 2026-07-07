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

static long long nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// ---------- Strategy interface ----------
struct RateLimitStrategy {
    virtual ~RateLimitStrategy() = default;
    virtual bool allow(long long now) = 0;
};

// ---------- 1) Token Bucket ----------
class TokenBucket : public RateLimitStrategy {
    double capacity;
    double refillPerMs;
    double tokens;
    long long lastRefill;
    mutex mtx;
public:
    TokenBucket(double cap, double refillPerSecond)
        : capacity(cap), refillPerMs(refillPerSecond / 1000.0),
          tokens(cap), lastRefill(nowMillis()) {}

    bool allow(long long now) override {
        lock_guard<mutex> g(mtx);
        double elapsed = (double)(now - lastRefill);
        tokens = min(capacity, tokens + elapsed * refillPerMs); // accrue, cap at capacity
        lastRefill = now;
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        return false;
    }
};

// ---------- 2) Sliding Window Log ----------
class SlidingWindowLog : public RateLimitStrategy {
    int maxRequests;
    long long windowMs;
    deque<long long> timestamps;
    mutex mtx;
public:
    SlidingWindowLog(int maxReq, long long window) : maxRequests(maxReq), windowMs(window) {}

    bool allow(long long now) override {
        lock_guard<mutex> g(mtx);
        while (!timestamps.empty() && now - timestamps.front() >= windowMs)
            timestamps.pop_front();                 // drop expired
        if ((int)timestamps.size() < maxRequests) { timestamps.push_back(now); return true; }
        return false;
    }
};

// ---------- Front-facing limiter: one strategy per client ----------
class RateLimiter {
    function<shared_ptr<RateLimitStrategy>()> factory;
    unordered_map<string, shared_ptr<RateLimitStrategy>> perClient;
    mutex mtx;
public:
    explicit RateLimiter(function<shared_ptr<RateLimitStrategy>()> f) : factory(move(f)) {}

    bool allow(const string& clientId) {
        shared_ptr<RateLimitStrategy> s;
        {
            lock_guard<mutex> g(mtx);
            auto it = perClient.find(clientId);
            if (it == perClient.end()) { s = factory(); perClient[clientId] = s; }
            else s = it->second;
        }
        return s->allow(nowMillis());
    }
};

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

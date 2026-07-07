import java.util.*;

/**
 * RATE LIMITER — thread-safe, per-client, pluggable algorithm (Strategy).
 *
 * Two classic algorithms implemented:
 *   1) Token Bucket    -> allows bursts up to bucket capacity; refills at a steady rate.
 *   2) Sliding Window Log -> exact count of requests in the last window; smooth, no burst edge.
 *
 * The RateLimiter keeps one limiter object per client key and delegates to the
 * chosen Strategy. All state mutation is synchronized for thread-safety.
 */
public class Main {

    // ---------- Strategy interface ----------
    // Strategy pattern: the common contract every rate-limiting algorithm must follow.
    // Because both algorithms implement this same interface, RateLimiter can use either
    // one without knowing which concrete algorithm it is talking to (swappable at runtime).
    interface RateLimitStrategy {
        boolean allow(long nowMillis); // true => request permitted, false => rejected
    }

    // ---------- 1) Token Bucket ----------
    // Algorithm: imagine a bucket that holds tokens. Tokens are added at a steady rate.
    // Each request must take 1 token; if the bucket is empty the request is rejected.
    // This naturally ALLOWS SHORT BURSTS (up to capacity), then settles to the refill rate.
    static class TokenBucket implements RateLimitStrategy {
        private final double capacity;      // max tokens the bucket can hold (the burst size)
        private final double refillPerMs;   // tokens added per millisecond
        private double tokens;              // current tokens available right now
        private long lastRefill;            // timestamp of the last time we topped up tokens

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            // Store the rate in tokens-per-millisecond so refill math is easy later.
            this.refillPerMs = refillPerSecond / 1000.0;
            this.tokens = capacity;         // start full so early requests are allowed
            this.lastRefill = System.currentTimeMillis();
        }

        // Decide if a request is allowed: first refill based on elapsed time, then try to spend a token.
        // synchronized so two threads can't corrupt the token count when calling at the same time.
        public synchronized boolean allow(long now) {
            // Refill math: tokens earned = elapsed milliseconds * refill rate per ms.
            // Cap at capacity so unused time does not let tokens grow forever.
            double elapsed = now - lastRefill;
            tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
            lastRefill = now;
            // If at least one whole token is available, spend it and allow the request.
            if (tokens >= 1.0) { tokens -= 1.0; return true; }
            return false;
        }
    }

    // ---------- 2) Sliding Window Log ----------
    // Algorithm: remember the timestamp of every recent request. Before each new request,
    // forget the ones older than the window, then allow only if fewer than maxRequests remain.
    // This gives an EXACT, smooth limit (no burst edge) but costs memory per stored timestamp.
    static class SlidingWindowLog implements RateLimitStrategy {
        private final int maxRequests;      // how many requests are allowed inside one window
        private final long windowMs;        // window length in milliseconds
        // Queue of request timestamps, oldest at the front, newest at the back.
        private final Deque<Long> timestamps = new ArrayDeque<>();

        SlidingWindowLog(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }

        // Allow a request only if the count of recent requests is still under the limit.
        // synchronized so concurrent callers don't read/modify the timestamp queue at once.
        public synchronized boolean allow(long now) {
            // Slide the window: remove timestamps that are now older than windowMs.
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMs)
                timestamps.pollFirst();
            // If there is still room in the window, record this request and allow it.
            if (timestamps.size() < maxRequests) { timestamps.addLast(now); return true; }
            return false;
        }
    }

    // ---------- Front-facing limiter: one strategy per client ----------
    // This is the object callers actually use. It keeps a SEPARATE limiter per client key
    // so that one noisy client cannot use up another client's quota. It delegates the real
    // allow/reject decision to whichever Strategy was chosen.
    static class RateLimiter {
        // Maps a client id (e.g. "userA") to that client's own strategy instance.
        private final Map<String, RateLimitStrategy> perClient = new HashMap<>();
        // A factory that builds a fresh strategy on demand (so each client gets its own state).
        private final java.util.function.Supplier<RateLimitStrategy> factory;

        RateLimiter(java.util.function.Supplier<RateLimitStrategy> factory) { this.factory = factory; }

        // Look up (or lazily create) the limiter for this client, then ask it to decide.
        boolean allow(String clientId) {
            RateLimitStrategy s;
            // Lock the map only while finding/creating the per-client limiter, not during allow(),
            // so different clients don't block each other on the actual decision.
            synchronized (perClient) {
                // computeIfAbsent: reuse the existing limiter, or build one the first time we see this client.
                s = perClient.computeIfAbsent(clientId, k -> factory.get());
            }
            return s.allow(System.currentTimeMillis());
        }
    }

    // Demo: run each algorithm and print ALLOW/REJECT for a series of requests.
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Token Bucket: capacity 5, refill 2/sec ---");
        RateLimiter tb = new RateLimiter(() -> new TokenBucket(5, 2));
        for (int i = 1; i <= 7; i++)
            System.out.println("req " + i + " -> " + (tb.allow("userA") ? "ALLOW" : "REJECT"));
        System.out.println("sleep 1s (refills ~2 tokens)...");
        Thread.sleep(1000);
        for (int i = 8; i <= 10; i++)
            System.out.println("req " + i + " -> " + (tb.allow("userA") ? "ALLOW" : "REJECT"));

        System.out.println("--- Sliding Window: 3 requests / 1000ms ---");
        RateLimiter sw = new RateLimiter(() -> new SlidingWindowLog(3, 1000));
        for (int i = 1; i <= 5; i++)
            System.out.println("req " + i + " -> " + (sw.allow("userB") ? "ALLOW" : "REJECT"));
    }
}

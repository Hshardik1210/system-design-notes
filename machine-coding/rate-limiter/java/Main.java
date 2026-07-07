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
    interface RateLimitStrategy {
        boolean allow(long nowMillis); // true => request permitted
    }

    // ---------- 1) Token Bucket ----------
    static class TokenBucket implements RateLimitStrategy {
        private final double capacity;      // max tokens (burst size)
        private final double refillPerMs;   // tokens added per millisecond
        private double tokens;              // current tokens
        private long lastRefill;            // last time we refilled

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerMs = refillPerSecond / 1000.0;
            this.tokens = capacity;         // start full
            this.lastRefill = System.currentTimeMillis();
        }

        public synchronized boolean allow(long now) {
            // Add tokens accrued since last check, capped at capacity.
            double elapsed = now - lastRefill;
            tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
            lastRefill = now;
            if (tokens >= 1.0) { tokens -= 1.0; return true; }
            return false;
        }
    }

    // ---------- 2) Sliding Window Log ----------
    static class SlidingWindowLog implements RateLimitStrategy {
        private final int maxRequests;
        private final long windowMs;
        private final Deque<Long> timestamps = new ArrayDeque<>(); // request times

        SlidingWindowLog(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }

        public synchronized boolean allow(long now) {
            // Drop timestamps that fell out of the window.
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMs)
                timestamps.pollFirst();
            if (timestamps.size() < maxRequests) { timestamps.addLast(now); return true; }
            return false;
        }
    }

    // ---------- Front-facing limiter: one strategy per client ----------
    static class RateLimiter {
        private final Map<String, RateLimitStrategy> perClient = new HashMap<>();
        private final java.util.function.Supplier<RateLimitStrategy> factory;

        RateLimiter(java.util.function.Supplier<RateLimitStrategy> factory) { this.factory = factory; }

        boolean allow(String clientId) {
            RateLimitStrategy s;
            synchronized (perClient) {
                s = perClient.computeIfAbsent(clientId, k -> factory.get());
            }
            return s.allow(System.currentTimeMillis());
        }
    }

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

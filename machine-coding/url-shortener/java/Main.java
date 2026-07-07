import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL SHORTENER (TinyURL) — base62 encoding of an auto-incrementing ID.
 *
 * Approach:
 *   - Each new long URL gets a unique numeric ID (counter).
 *   - Encode that ID in base62 [0-9a-zA-Z] -> compact short code (e.g., "1c").
 *   - Store id -> longUrl so we can expand later (decode short -> id -> url).
 *   - Support custom aliases and idempotent shortening of the same URL.
 *
 * Why base62 of an ID (not hashing)?
 *   - Guaranteed no collisions (IDs are unique).
 *   - Short codes grow slowly: 62^7 ≈ 3.5 trillion URLs in 7 chars.
 *
 * Pattern: Singleton service.
 */
public class Main {

    static class UrlShortener {
        private static final String BASE62 =
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private static final int BASE = 62;
        private static final String DOMAIN = "https://sho.rt/";

        private final AtomicLong counter = new AtomicLong(1); // next ID to assign
        private final Map<Long, String> idToUrl = new ConcurrentHashMap<>();
        private final Map<String, String> codeToUrl = new ConcurrentHashMap<>(); // includes custom aliases
        private final Map<String, String> urlToCode = new ConcurrentHashMap<>();  // for idempotency

        // Encode a non-negative id into a base62 string.
        private String encode(long id) {
            if (id == 0) return String.valueOf(BASE62.charAt(0));
            StringBuilder sb = new StringBuilder();
            while (id > 0) {
                sb.append(BASE62.charAt((int) (id % BASE)));
                id /= BASE;
            }
            return sb.reverse().toString();
        }

        // Shorten a URL; returns the short link. Idempotent for repeated URLs.
        String shorten(String longUrl) {
            String existing = urlToCode.get(longUrl);
            if (existing != null) return DOMAIN + existing; // already shortened

            long id = counter.getAndIncrement();
            String code = encode(id);
            idToUrl.put(id, longUrl);
            codeToUrl.put(code, longUrl);
            urlToCode.put(longUrl, code);
            return DOMAIN + code;
        }

        // Custom alias (e.g. sho.rt/mylink). Fails if alias already taken.
        String shortenCustom(String longUrl, String alias) {
            if (codeToUrl.containsKey(alias))
                throw new IllegalArgumentException("alias already in use: " + alias);
            codeToUrl.put(alias, longUrl);
            urlToCode.putIfAbsent(longUrl, alias);
            return DOMAIN + alias;
        }

        // Expand a short link back to the original URL.
        String expand(String shortUrl) {
            String code = shortUrl.startsWith(DOMAIN) ? shortUrl.substring(DOMAIN.length()) : shortUrl;
            String url = codeToUrl.get(code);
            if (url == null) throw new NoSuchElementException("unknown short code: " + code);
            return url;
        }
    }

    public static void main(String[] args) {
        UrlShortener s = new UrlShortener();

        String a = s.shorten("https://example.com/a/very/long/path?x=1&y=2");
        String b = s.shorten("https://openai.com");
        System.out.println("short A : " + a);
        System.out.println("short B : " + b);

        // Idempotent: same long URL -> same short code.
        String aAgain = s.shorten("https://example.com/a/very/long/path?x=1&y=2");
        System.out.println("A again : " + aAgain + "  (same as A? " + a.equals(aAgain) + ")");

        // Custom alias.
        String custom = s.shortenCustom("https://github.com/torvalds/linux", "linux");
        System.out.println("custom  : " + custom);

        // Expand back.
        System.out.println("expand A      : " + s.expand(a));
        System.out.println("expand custom : " + s.expand(custom));
    }
}

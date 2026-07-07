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

    // The core service. It owns all the mappings and the logic to shorten/expand.
    // Used as a Singleton in spirit: one shared instance holds the whole mapping store.
    static class UrlShortener {
        // The 62 characters used for encoding: digits, then lowercase, then uppercase.
        // A number in base62 uses these as its "digits" (index 0='0', index 61='Z').
        private static final String BASE62 =
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private static final int BASE = 62;             // number of symbols in our alphabet
        private static final String DOMAIN = "https://sho.rt/"; // prefix shown in every short link

        // AtomicLong so IDs stay unique even if many threads shorten URLs at once.
        private final AtomicLong counter = new AtomicLong(1); // next ID to assign
        private final Map<Long, String> idToUrl = new ConcurrentHashMap<>();      // id -> original URL
        private final Map<String, String> codeToUrl = new ConcurrentHashMap<>(); // short code -> URL (includes custom aliases)
        private final Map<String, String> urlToCode = new ConcurrentHashMap<>();  // URL -> code, lets us reuse a code for the same URL (idempotency)

        // Turn a number (the id) into a short base62 string.
        // Works like converting to any base: repeatedly take id % 62 to get the
        // next digit, then divide by 62. We build the string backwards, so reverse at the end.
        private String encode(long id) {
            if (id == 0) return String.valueOf(BASE62.charAt(0)); // special case: 0 -> "0"
            StringBuilder sb = new StringBuilder();
            while (id > 0) {
                sb.append(BASE62.charAt((int) (id % BASE))); // remainder picks the next character
                id /= BASE;                                  // shift right by one base62 "digit"
            }
            return sb.reverse().toString(); // digits were collected least-significant first, so flip them
        }

        // Shorten a URL and return the full short link.
        // Idempotent: shortening the same URL twice gives the same code (no wasted IDs).
        String shorten(String longUrl) {
            String existing = urlToCode.get(longUrl);
            if (existing != null) return DOMAIN + existing; // seen this URL before, reuse its code

            long id = counter.getAndIncrement(); // grab a fresh unique id, then bump the counter
            String code = encode(id);            // that id becomes our short code
            idToUrl.put(id, longUrl);
            codeToUrl.put(code, longUrl);        // so expand() can look the URL back up
            urlToCode.put(longUrl, code);        // so a repeat shorten() is idempotent
            return DOMAIN + code;
        }

        // Let a user pick their own code (e.g. sho.rt/linux).
        // Rejected if that code is already used, since two URLs can't share one code.
        String shortenCustom(String longUrl, String alias) {
            if (codeToUrl.containsKey(alias)) // collision check: alias must be free
                throw new IllegalArgumentException("alias already in use: " + alias);
            codeToUrl.put(alias, longUrl);
            urlToCode.putIfAbsent(longUrl, alias); // only set if the URL had no code yet
            return DOMAIN + alias;
        }

        // Given a short link, return the original URL it points to.
        String expand(String shortUrl) {
            // Accept either a full link ("https://sho.rt/1c") or a bare code ("1c").
            String code = shortUrl.startsWith(DOMAIN) ? shortUrl.substring(DOMAIN.length()) : shortUrl;
            String url = codeToUrl.get(code); // reverse lookup: code -> original URL
            if (url == null) throw new NoSuchElementException("unknown short code: " + code);
            return url;
        }
    }

    // Demo: shorten a few URLs, show idempotency and custom aliases, then expand them back.
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

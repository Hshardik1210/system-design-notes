// URL SHORTENER (TinyURL) — base62 encoding of an auto-incrementing ID (C++17)
//
// - Each long URL gets a unique numeric id; base62-encode it -> short code.
// - Store code -> url for expansion, url -> code for idempotency.
// - Support custom aliases.
// base62 of a unique id => no collisions; 62^7 ~ 3.5 trillion codes in 7 chars.

#include <iostream>
#include <string>
#include <unordered_map>
#include <algorithm>
#include <stdexcept>
using namespace std;

// The core service. It owns all the mappings and the logic to shorten/expand a URL.
class UrlShortener {
    // The 62 characters used for encoding: digits, then lowercase, then uppercase.
    // A number in base62 uses these as its "digits" (index 0='0', index 61='Z').
    static constexpr const char* BASE62 =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static const int BASE = 62;              // number of symbols in our alphabet
    const string domain = "https://sho.rt/"; // prefix shown in every short link

    long long counter = 1;                   // next unique id to assign
    unordered_map<string, string> codeToUrl; // short code -> URL (includes custom aliases)
    unordered_map<string, string> urlToCode; // URL -> code, lets us reuse a code for the same URL (idempotency)

    // Turn a number (the id) into a short base62 string.
    // Works like converting to any base: repeatedly take id % 62 for the next digit,
    // then divide by 62. Characters come out least-significant first, so reverse at the end.
    string encode(long long id) {
        if (id == 0) return string(1, BASE62[0]); // special case: 0 -> "0"
        string s;
        while (id > 0) { s += BASE62[id % BASE]; id /= BASE; } // remainder picks the char, divide to shift
        reverse(s.begin(), s.end());
        return s;
    }

public:
    // Shorten a URL and return the full short link.
    // Idempotent: shortening the same URL twice gives the same code (no wasted IDs).
    string shorten(const string& longUrl) {
        auto it = urlToCode.find(longUrl);
        if (it != urlToCode.end()) return domain + it->second; // seen this URL before, reuse its code

        long long id = counter++;        // grab a fresh unique id, then bump the counter
        string code = encode(id);        // that id becomes our short code
        codeToUrl[code] = longUrl;       // so expand() can look the URL back up
        urlToCode[longUrl] = code;       // so a repeat shorten() is idempotent
        return domain + code;
    }

    // Let a user pick their own code (e.g. sho.rt/linux).
    // Rejected if that code is already used, since two URLs can't share one code.
    string shortenCustom(const string& longUrl, const string& alias) {
        if (codeToUrl.count(alias)) throw invalid_argument("alias already in use: " + alias); // collision check
        codeToUrl[alias] = longUrl;
        if (!urlToCode.count(longUrl)) urlToCode[longUrl] = alias; // only set if the URL had no code yet
        return domain + alias;
    }

    // Given a short link, return the original URL it points to.
    string expand(const string& shortUrl) {
        // Accept either a full link ("https://sho.rt/1c") or a bare code ("1c").
        // rfind(domain, 0) == 0 is the idiom for "does the string start with domain?".
        string code = (shortUrl.rfind(domain, 0) == 0) ? shortUrl.substr(domain.size()) : shortUrl;
        auto it = codeToUrl.find(code); // reverse lookup: code -> original URL
        if (it == codeToUrl.end()) throw runtime_error("unknown short code: " + code);
        return it->second;
    }
};

// Demo: shorten a few URLs, show idempotency and custom aliases, then expand them back.
int main() {
    UrlShortener s;

    string a = s.shorten("https://example.com/a/very/long/path?x=1&y=2");
    string b = s.shorten("https://openai.com");
    cout << "short A : " << a << "\n";
    cout << "short B : " << b << "\n";

    string aAgain = s.shorten("https://example.com/a/very/long/path?x=1&y=2");
    cout << "A again : " << aAgain << "  (same as A? " << (a == aAgain ? "true" : "false") << ")\n";

    string custom = s.shortenCustom("https://github.com/torvalds/linux", "linux");
    cout << "custom  : " << custom << "\n";

    cout << "expand A      : " << s.expand(a) << "\n";
    cout << "expand custom : " << s.expand(custom) << "\n";
    return 0;
}

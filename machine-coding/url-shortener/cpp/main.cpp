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

class UrlShortener {
    static constexpr const char* BASE62 =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static const int BASE = 62;
    const string domain = "https://sho.rt/";

    long long counter = 1;
    unordered_map<string, string> codeToUrl; // includes custom aliases
    unordered_map<string, string> urlToCode; // for idempotency

    string encode(long long id) {
        if (id == 0) return string(1, BASE62[0]);
        string s;
        while (id > 0) { s += BASE62[id % BASE]; id /= BASE; }
        reverse(s.begin(), s.end());
        return s;
    }

public:
    string shorten(const string& longUrl) {
        auto it = urlToCode.find(longUrl);
        if (it != urlToCode.end()) return domain + it->second; // idempotent

        long long id = counter++;
        string code = encode(id);
        codeToUrl[code] = longUrl;
        urlToCode[longUrl] = code;
        return domain + code;
    }

    string shortenCustom(const string& longUrl, const string& alias) {
        if (codeToUrl.count(alias)) throw invalid_argument("alias already in use: " + alias);
        codeToUrl[alias] = longUrl;
        if (!urlToCode.count(longUrl)) urlToCode[longUrl] = alias;
        return domain + alias;
    }

    string expand(const string& shortUrl) {
        string code = (shortUrl.rfind(domain, 0) == 0) ? shortUrl.substr(domain.size()) : shortUrl;
        auto it = codeToUrl.find(code);
        if (it == codeToUrl.end()) throw runtime_error("unknown short code: " + code);
        return it->second;
    }
};

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

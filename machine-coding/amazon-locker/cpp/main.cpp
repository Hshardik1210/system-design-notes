// AMAZON LOCKER — best-fit slot allocation by package size (C++17)
//
// Assign a package to the SMALLEST free locker that fits (size <= locker size),
// issue a pickup code, free the locker on pickup.
//
// Key pieces: Size (enum), Package/Locker (data), LockerStation (allocates
// lockers best-fit and maps pickup codes back to lockers). Pattern: best-fit
// resource allocation + code -> resource mapping for retrieval.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <random>
#include <cstdio>
using namespace std;

// The three locker/package sizes. Order matters: casting to int gives
// SMALL=0, MEDIUM=1, LARGE=2, which we use to compare sizes.
enum class Size { SMALL, MEDIUM, LARGE };
// Helper to turn a Size into a printable string for the demo output.
static string sizeStr(Size s) { return s == Size::SMALL ? "SMALL" : s == Size::MEDIUM ? "MEDIUM" : "LARGE"; }

// A package to be delivered: an id and its size.
struct Package { string id; Size size; };

// A physical locker of a fixed size holding at most one package.
struct Locker {
    string id; Size size; Package pkg; bool occupied = false; // occupied == false means free
    bool isFree() const { return !occupied; }
    // Fits if package size <= locker size (cast enums to int to compare).
    bool fits(const Package& p) const { return (int)p.size <= (int)size; }
};

// Manages all lockers: best-fit assignment, pickup-code tracking, freeing lockers.
class LockerStation {
    vector<Locker> lockers;
    unordered_map<string, string> codeToLocker; // code -> locker id
    mt19937 rng{1}; // fixed seed => same codes every run (predictable demo)
public:
    void addLocker(const Locker& l) { lockers.push_back(l); }

    // Assign the smallest free locker that fits; returns a pickup code, or "" if none.
    string deposit(const Package& p) {
        // Best-fit: keep the smallest free locker that fits, so big lockers stay
        // free for big packages.
        Locker* best = nullptr;
        for (auto& l : lockers)
            if (l.isFree() && l.fits(p))
                if (!best || (int)l.size < (int)best->size) best = &l;
        if (!best) { cout << "  ! no locker fits " << p.id << " (" << sizeStr(p.size) << ")\n"; return ""; }
        best->pkg = p; best->occupied = true; // place package and mark locker occupied
        char buf[8]; snprintf(buf, sizeof(buf), "%04d", (int)(rng() % 10000)); // 4-digit zero-padded pickup code
        string code = buf;
        codeToLocker[code] = best->id; // remember which locker this code opens
        printf("  %s -> locker %s (%s), code %s\n", p.id.c_str(), best->id.c_str(), sizeStr(best->size).c_str(), code.c_str());
        return code;
    }

    // Retrieve a package by its pickup code and free the locker.
    bool pickup(const string& code) {
        auto it = codeToLocker.find(code);
        if (it == codeToLocker.end()) { cout << "  ! invalid code " << code << "\n"; return false; }
        string lockerId = it->second; codeToLocker.erase(it); // consume the code (one-time use)
        for (auto& l : lockers) if (l.id == lockerId) {
            printf("  picked up %s from locker %s\n", l.pkg.id.c_str(), l.id.c_str());
            l.occupied = false; // locker is free again
            return true;
        }
        return false;
    }
};

// Small demo run showing deposit, best-fit selection, rejection, and pickup.
int main() {
    LockerStation station;
    station.addLocker({"L1", Size::SMALL, {}, false});
    station.addLocker({"L2", Size::MEDIUM, {}, false});
    station.addLocker({"L3", Size::LARGE, {}, false});

    string c1 = station.deposit({"PKG-1", Size::SMALL});   // L1
    string c2 = station.deposit({"PKG-2", Size::MEDIUM});   // L2
    string c3 = station.deposit({"PKG-3", Size::SMALL});    // L1 taken -> L3 (LARGE)
    station.deposit({"PKG-4", Size::LARGE});                // none large free -> rejected

    station.pickup(c1);                     // free L1
    station.deposit({"PKG-5", Size::SMALL}); // fits L1 again
    station.pickup("9999");                 // invalid
    (void)c2; (void)c3;
    return 0;
}

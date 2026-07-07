// AMAZON LOCKER — best-fit slot allocation by package size (C++17)
//
// Assign a package to the SMALLEST free locker that fits (size <= locker size),
// issue a pickup code, free the locker on pickup.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <random>
#include <cstdio>
using namespace std;

enum class Size { SMALL, MEDIUM, LARGE };
static string sizeStr(Size s) { return s == Size::SMALL ? "SMALL" : s == Size::MEDIUM ? "MEDIUM" : "LARGE"; }

struct Package { string id; Size size; };

struct Locker {
    string id; Size size; Package pkg; bool occupied = false;
    bool isFree() const { return !occupied; }
    bool fits(const Package& p) const { return (int)p.size <= (int)size; }
};

class LockerStation {
    vector<Locker> lockers;
    unordered_map<string, string> codeToLocker; // code -> locker id
    mt19937 rng{1};
public:
    void addLocker(const Locker& l) { lockers.push_back(l); }

    string deposit(const Package& p) {
        Locker* best = nullptr;
        for (auto& l : lockers)
            if (l.isFree() && l.fits(p))
                if (!best || (int)l.size < (int)best->size) best = &l;
        if (!best) { cout << "  ! no locker fits " << p.id << " (" << sizeStr(p.size) << ")\n"; return ""; }
        best->pkg = p; best->occupied = true;
        char buf[8]; snprintf(buf, sizeof(buf), "%04d", (int)(rng() % 10000));
        string code = buf;
        codeToLocker[code] = best->id;
        printf("  %s -> locker %s (%s), code %s\n", p.id.c_str(), best->id.c_str(), sizeStr(best->size).c_str(), code.c_str());
        return code;
    }

    bool pickup(const string& code) {
        auto it = codeToLocker.find(code);
        if (it == codeToLocker.end()) { cout << "  ! invalid code " << code << "\n"; return false; }
        string lockerId = it->second; codeToLocker.erase(it);
        for (auto& l : lockers) if (l.id == lockerId) {
            printf("  picked up %s from locker %s\n", l.pkg.id.c_str(), l.id.c_str());
            l.occupied = false;
            return true;
        }
        return false;
    }
};

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

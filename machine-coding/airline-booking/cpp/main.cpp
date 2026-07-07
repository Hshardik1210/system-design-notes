// AIRLINE / FLIGHT BOOKING — search by route, book by fare class (C++17)
//
// Each flight's seat map is partitioned by fare class (ECONOMY/BUSINESS/FIRST),
// each class priced separately. Booking takes the first free seat in a class;
// booking is mutex-guarded for concurrency.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <cstdio>
using namespace std;

enum class FareClass { ECONOMY, BUSINESS, FIRST };
static string fareStr(FareClass f) {
    return f == FareClass::ECONOMY ? "ECONOMY" : f == FareClass::BUSINESS ? "BUSINESS" : "FIRST";
}

struct Seat { string number; FareClass fareClass; bool booked = false; };

class Flight {
    mutex mtx;
public:
    string no, from, to; int day;
    vector<Seat> seats;
    map<FareClass, double> price;

    Flight(string n, string f, string t, int d) : no(move(n)), from(move(f)), to(move(t)), day(d) {}

    void addSeats(FareClass fc, int count, double fare) {
        price[fc] = fare;
        char prefix = fareStr(fc)[0];
        int start = (int)seats.size() + 1;
        for (int i = 0; i < count; i++) seats.push_back({string(1, prefix) + to_string(start + i), fc, false});
    }
    bool hasFree(FareClass fc) {
        for (auto& s : seats) if (s.fareClass == fc && !s.booked) return true;
        return false;
    }
    Seat* book(FareClass fc) {
        lock_guard<mutex> g(mtx);
        for (auto& s : seats) if (s.fareClass == fc && !s.booked) { s.booked = true; return &s; }
        return nullptr;
    }
};

class AirlineService {
    vector<Flight*> flights;
public:
    void addFlight(Flight* f) { flights.push_back(f); }
    vector<Flight*> search(const string& from, const string& to, int day, FareClass fc) {
        vector<Flight*> res;
        for (auto* f : flights)
            if (f->from == from && f->to == to && f->day == day && f->hasFree(fc)) res.push_back(f);
        return res;
    }
    string book(Flight* f, FareClass fc, const string& passenger) {
        Seat* s = f->book(fc);
        if (!s) { cout << "  ! no free " << fareStr(fc) << " seat on " << f->no << "\n"; return ""; }
        printf("  Booked %s seat %s (%s) for %s @ Rs %.0f\n",
               f->no.c_str(), s->number.c_str(), fareStr(fc).c_str(), passenger.c_str(), f->price[fc]);
        return s->number;
    }
};

int main() {
    Flight ai101("AI101", "DEL", "BLR", 5);
    ai101.addSeats(FareClass::FIRST, 1, 30000);
    ai101.addSeats(FareClass::BUSINESS, 2, 15000);
    ai101.addSeats(FareClass::ECONOMY, 3, 5000);

    Flight ai202("AI202", "DEL", "BLR", 5);
    ai202.addSeats(FareClass::ECONOMY, 2, 4500);

    AirlineService svc;
    svc.addFlight(&ai101);
    svc.addFlight(&ai202);

    cout << "Search DEL->BLR day 5, ECONOMY:\n";
    for (auto* f : svc.search("DEL", "BLR", 5, FareClass::ECONOMY)) cout << "  " << f->no << "\n";

    svc.book(&ai101, FareClass::FIRST, "Alice");
    svc.book(&ai101, FareClass::FIRST, "Bob");     // full
    svc.book(&ai101, FareClass::BUSINESS, "Carol");
    svc.book(&ai202, FareClass::ECONOMY, "Dan");

    cout << "Search DEL->BLR day 5, FIRST (ai101 now full):\n";
    for (auto* f : svc.search("DEL", "BLR", 5, FareClass::FIRST)) cout << "  " << f->no << "\n";
    return 0;
}

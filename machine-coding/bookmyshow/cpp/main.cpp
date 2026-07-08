// BOOKMYSHOW — movie ticket booking with concurrency-safe seat locking (C++17)
//
// WHAT THIS PROGRAM DOES:
//   Books seats for a movie show while guaranteeing the same seat is never sold
//   to two people, even when requests arrive at the same instant.
//
// TWO-PHASE BOOKING (how real ticketing systems prevent double-booking):
//   1) HOLD seats for a user (a temporary lock that expires after holdMillis).
//   2) CONFIRM after payment -> the held seats become BOOKED.
//   An unconfirmed hold auto-releases: it's treated as free once expired.
//
// WHY IT'S SAFE:
//   Every seat mutation for a show happens under that show's mutex, so the
//   "check all seats free -> then set them held" is one atomic critical section.
//   A competing thread runs fully before or after — never interleaved.
//
// KEY TYPES:
//   Seat           - one seat plus its status/holder/expiry.
//   Show           - a showing; owns its seats and the mutex guarding them.
//   BookingService - the API: addShow, hold (phase 1), confirm (phase 2).
//
// DESIGN PATTERNS:
//   State             - SeatStatus drives AVAILABLE -> HELD -> BOOKED.
//   Two-phase locking - HOLD then CONFIRM, to prevent double-booking.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <thread>
#include <chrono>
using namespace std;

// Current wall-clock time in milliseconds; used to timestamp and expire holds.
static long long nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

// State pattern: the lifecycle a seat moves through.
enum class SeatStatus { AVAILABLE, HELD, BOOKED };
// Human-readable status name, only for log output.
static string statusStr(SeatStatus s) {
    return s == SeatStatus::AVAILABLE ? "AVAILABLE" : s == SeatStatus::HELD ? "HELD" : "BOOKED";
}

// One seat: its id, current status, and (if held) who holds it and until when.
struct Seat {
    string id;
    SeatStatus status = SeatStatus::AVAILABLE;
    string heldBy;
    long long holdExpiry = 0;
    explicit Seat(string i) : id(move(i)) {}
};

// A single movie showing. Owns all its seats and the mutex that makes every
// seat change for this show atomic.
struct Show {
    string id, movie;
    map<string, shared_ptr<Seat>> seats; // ordered
    mutex mtx;                            // guards all seat mutations for this show
    Show(string i, string m, const vector<string>& seatIds) : id(move(i)), movie(move(m)) {
        for (auto& s : seatIds) seats[s] = make_shared<Seat>(s);
    }
};

// Format a list of seat ids like [A1,A2] for log output.
static string join(const vector<string>& v) {
    string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += v[i] + (i + 1 < v.size() ? "," : "");
    return s + "]";
}

// The booking API. Holds all shows and implements the two-phase hold/confirm flow.
class BookingService {
    unordered_map<string, shared_ptr<Show>> shows;
    long long holdMillis; // how long a hold stays valid before expiring
public:
    explicit BookingService(long long h) : holdMillis(h) {}
    // Register a show so users can book seats in it.
    void addShow(shared_ptr<Show> s) { shows[s->id] = move(s); }

    // Phase 1: try to HOLD the requested seats for a user. All-or-nothing.
    // The whole check-then-set runs under the show's mutex so two racing holds
    // can't both grab the same seat.
    bool hold(const string& showId, const vector<string>& seatIds, const string& user) {
        auto show = shows[showId];
        lock_guard<mutex> g(show->mtx); // critical section: unlocks automatically on return
        long long now = nowMillis();
        // First pass: verify EVERY requested seat is free (or its hold has expired).
        // Bail out before mutating anything if even one is unavailable.
        for (auto& sid : seatIds) {
            auto it = show->seats.find(sid);
            if (it == show->seats.end()) { cout << "  no such seat " << sid << "\n"; return false; }
            auto& seat = it->second;
            // A HELD seat past its expiry time counts as free again (lazy auto-release).
            bool expired = seat->status == SeatStatus::HELD && now > seat->holdExpiry;
            // Blocked if already BOOKED, or HELD by someone whose hold is still valid.
            if (seat->status == SeatStatus::BOOKED || (seat->status == SeatStatus::HELD && !expired)) {
                cout << "  HOLD failed for " << user << ": " << sid << " is " << statusStr(seat->status) << "\n";
                return false;
            }
        }
        // Second pass: commit the holds. Safe because pass one proved all seats free
        // AND we still hold the mutex (no other thread can slip in between).
        for (auto& sid : seatIds) {
            auto& seat = show->seats[sid];
            seat->status = SeatStatus::HELD;        // AVAILABLE/expired -> HELD
            seat->heldBy = user;
            seat->holdExpiry = now + holdMillis;    // start the expiry countdown
        }
        cout << "  HELD " << join(seatIds) << " for " << user << "\n";
        return true;
    }

    // Phase 2: after payment, CONFIRM the user's held seats -> BOOKED.
    // Only succeeds if this exact user still holds every seat and none have expired.
    bool confirm(const string& showId, const vector<string>& seatIds, const string& user) {
        auto show = shows[showId];
        lock_guard<mutex> g(show->mtx);
        long long now = nowMillis();
        // Validate all holds first (all-or-nothing again).
        for (auto& sid : seatIds) {
            auto& seat = show->seats[sid];
            // Valid only if still HELD, held by THIS user, and not yet expired.
            bool validHold = seat->status == SeatStatus::HELD && seat->heldBy == user && now <= seat->holdExpiry;
            if (!validHold) {
                cout << "  CONFIRM failed for " << user << ": hold on " << sid << " invalid/expired\n";
                return false;
            }
        }
        // All holds valid: finalize the sale by flipping HELD -> BOOKED.
        for (auto& sid : seatIds) show->seats[sid]->status = SeatStatus::BOOKED;
        cout << "  BOOKED " << join(seatIds) << " for " << user << "\n";
        return true;
    }
};

// Demo: exercises the full flow — a race for overlapping seats, a confirm,
// a blocked hold on a booked seat, and a hold that succeeds after expiry.
int main() {
    BookingService svc(2000); // 2s hold
    svc.addShow(make_shared<Show>("S1", "Inception", vector<string>{"A1", "A2", "A3", "A4"}));

    cout << "--- Concurrent holds ---\n";
    thread t1([&] { svc.hold("S1", {"A1", "A2"}, "Alice"); });
    t1.join();
    thread t2([&] { svc.hold("S1", {"A2", "A3"}, "Bob"); }); // A2 overlaps -> fails
    t2.join();

    cout << "--- Alice pays & confirms ---\n";
    svc.confirm("S1", {"A1", "A2"}, "Alice");

    cout << "--- Bob tries seats incl. A2 (booked) ---\n";
    svc.hold("S1", {"A2", "A3"}, "Bob");
    svc.hold("S1", {"A3", "A4"}, "Bob");

    cout << "--- Hold expiry demo ---\n";
    svc.hold("S1", {"A3"}, "Carol"); // held by Bob -> fails
    this_thread::sleep_for(chrono::milliseconds(2100));
    svc.hold("S1", {"A3"}, "Carol"); // Bob's hold expired -> succeeds
    return 0;
}

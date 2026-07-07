// BOOKMYSHOW — movie ticket booking with concurrency-safe seat locking (C++17)
//
// Two-phase booking to prevent double-booking:
//   1) HOLD seats for a user (expires after holdMillis).
//   2) CONFIRM after payment -> BOOKED.
// Every seat mutation for a show happens under that show's mutex (atomic check-then-set).

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

static long long nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

enum class SeatStatus { AVAILABLE, HELD, BOOKED };
static string statusStr(SeatStatus s) {
    return s == SeatStatus::AVAILABLE ? "AVAILABLE" : s == SeatStatus::HELD ? "HELD" : "BOOKED";
}

struct Seat {
    string id;
    SeatStatus status = SeatStatus::AVAILABLE;
    string heldBy;
    long long holdExpiry = 0;
    explicit Seat(string i) : id(move(i)) {}
};

struct Show {
    string id, movie;
    map<string, shared_ptr<Seat>> seats; // ordered
    mutex mtx;
    Show(string i, string m, const vector<string>& seatIds) : id(move(i)), movie(move(m)) {
        for (auto& s : seatIds) seats[s] = make_shared<Seat>(s);
    }
};

static string join(const vector<string>& v) {
    string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += v[i] + (i + 1 < v.size() ? "," : "");
    return s + "]";
}

class BookingService {
    unordered_map<string, shared_ptr<Show>> shows;
    long long holdMillis;
public:
    explicit BookingService(long long h) : holdMillis(h) {}
    void addShow(shared_ptr<Show> s) { shows[s->id] = move(s); }

    bool hold(const string& showId, const vector<string>& seatIds, const string& user) {
        auto show = shows[showId];
        lock_guard<mutex> g(show->mtx);
        long long now = nowMillis();
        // Verify all requested seats are free (or an expired hold).
        for (auto& sid : seatIds) {
            auto it = show->seats.find(sid);
            if (it == show->seats.end()) { cout << "  no such seat " << sid << "\n"; return false; }
            auto& seat = it->second;
            bool expired = seat->status == SeatStatus::HELD && now > seat->holdExpiry;
            if (seat->status == SeatStatus::BOOKED || (seat->status == SeatStatus::HELD && !expired)) {
                cout << "  HOLD failed for " << user << ": " << sid << " is " << statusStr(seat->status) << "\n";
                return false;
            }
        }
        for (auto& sid : seatIds) {
            auto& seat = show->seats[sid];
            seat->status = SeatStatus::HELD;
            seat->heldBy = user;
            seat->holdExpiry = now + holdMillis;
        }
        cout << "  HELD " << join(seatIds) << " for " << user << "\n";
        return true;
    }

    bool confirm(const string& showId, const vector<string>& seatIds, const string& user) {
        auto show = shows[showId];
        lock_guard<mutex> g(show->mtx);
        long long now = nowMillis();
        for (auto& sid : seatIds) {
            auto& seat = show->seats[sid];
            bool validHold = seat->status == SeatStatus::HELD && seat->heldBy == user && now <= seat->holdExpiry;
            if (!validHold) {
                cout << "  CONFIRM failed for " << user << ": hold on " << sid << " invalid/expired\n";
                return false;
            }
        }
        for (auto& sid : seatIds) show->seats[sid]->status = SeatStatus::BOOKED;
        cout << "  BOOKED " << join(seatIds) << " for " << user << "\n";
        return true;
    }
};

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

// HOTEL MANAGEMENT / BOOKING — date-range availability + reservations (C++17)
//
// Rooms are booked for half-open intervals [checkIn, checkOut). A new booking is
// allowed only if it doesn't overlap existing ones:
//   [s1,e1) and [s2,e2) overlap iff s1 < e2 && s2 < e1.
// Dates are day-number ints to stay dependency-free.
//
// WHAT THIS PROGRAM DOES (beginner overview):
//   It models a small hotel. You can search for free rooms of a given type over
//   a date range and then book one. The core trick is deciding whether two stays
//   clash in time, handled by the interval-overlap check below.
//
// KEY TYPES:
//   - RoomType    : the room categories (STANDARD, DELUXE, SUITE).
//   - DateRange   : a validated stay interval [checkIn, checkOut) with overlaps().
//   - Room        : one physical room plus its list of existing bookings.
//   - Reservation : a confirmed booking (id, room, guest, dates, price).
//   - Hotel       : holds all rooms; provides search() and book().
//
// DESIGN NOTES:
//   - Value object: DateRange validates itself in its constructor.
//   - Encapsulation: each Room checks its own bookings via isAvailable().
//   - Half-open intervals let back-to-back stays (checkout day == next check-in
//     day) coexist without conflict, just like real hotels.

#include <iostream>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstdio>
using namespace std;

// The room categories used to filter during search().
enum class RoomType { STANDARD, DELUXE, SUITE };
// Helper to turn a RoomType into a printable string.
static string typeStr(RoomType t) {
    return t == RoomType::STANDARD ? "STANDARD" : t == RoomType::DELUXE ? "DELUXE" : "SUITE";
}

// A stay interval [checkIn, checkOut); validates itself on construction.
struct DateRange {
    int checkIn, checkOut; // [checkIn, checkOut)
    DateRange(int in, int out) : checkIn(in), checkOut(out) {
        // Reject invalid ranges immediately so later code can trust the values.
        if (out <= in) throw invalid_argument("checkout must be after checkin");
    }
    // True if the two ranges share a night. Half-open, so touching ranges
    // (this.checkOut == o.checkIn) do NOT count as overlapping.
    bool overlaps(const DateRange& o) const { return checkIn < o.checkOut && o.checkIn < checkOut; }
};

// One physical room: label, category, nightly price, and its current bookings.
struct Room {
    string number; RoomType type; double pricePerNight;
    vector<DateRange> bookings;
    // Free for r only if it clashes with none of the existing bookings.
    bool isAvailable(const DateRange& r) const {
        for (auto& b : bookings) if (b.overlaps(r)) return false;
        return true;
    }
};

// A confirmed booking: who booked which room, for which dates, and the total price.
struct Reservation {
    string id; Room* room; string guest; DateRange range; double amount;
    Reservation(string i, Room* rm, string g, DateRange r)
        : id(move(i)), room(rm), guest(move(g)), range(r),
          // Price = number of nights (checkOut - checkIn) times the nightly rate.
          amount((r.checkOut - r.checkIn) * rm->pricePerNight) {}
};

// The hotel: owns all rooms and provides the search + booking operations.
class Hotel {
    int resSeq = 1; // counter used to hand out unique reservation ids
public:
    string name;
    vector<Room> rooms;
    explicit Hotel(string n) : name(move(n)) {}
    void addRoom(const Room& r) { rooms.push_back(r); }

    // Return pointers to all rooms matching the type that are free for the range.
    vector<Room*> search(RoomType type, const DateRange& range) {
        vector<Room*> result;
        for (auto& r : rooms) if (r.type == type && r.isAvailable(range)) result.push_back(&r);
        return result;
    }

    // Reserve a specific room. We re-check availability right before committing
    // so two near-simultaneous bookings can't both succeed on the same dates.
    Reservation book(Room& room, const string& guest, const DateRange& range) {
        if (!room.isAvailable(range))
            throw runtime_error("room " + room.number + " not available for those dates");
        room.bookings.push_back(range); // claim the dates for future overlap checks
        Reservation res("RES" + to_string(resSeq++), &room, guest, range);
        printf("Booked %s (%s) for %s [%d,%d) -> Rs %.0f\n",
               room.number.c_str(), typeStr(room.type).c_str(), guest.c_str(),
               range.checkIn, range.checkOut, res.amount);
        return res;
    }
};

// Small helper: format a list of rooms as "[num,num,...]" for printing.
static string names(const vector<Room*>& rooms) {
    string s = "[";
    for (size_t i = 0; i < rooms.size(); ++i) s += rooms[i]->number + (i + 1 < rooms.size() ? "," : "");
    return s + "]";
}

// Demo driver: sets up a hotel and walks through search + booking scenarios.
int main() {
    Hotel hotel("Grand Palace");
    hotel.addRoom({"101", RoomType::DELUXE, 5000, {}});
    hotel.addRoom({"102", RoomType::DELUXE, 5000, {}});
    hotel.addRoom({"201", RoomType::SUITE, 12000, {}});

    DateRange stay(10, 13); // 3 nights

    // Both DELUXE rooms are free at first.
    cout << "Available DELUXE for [10,13): " << names(hotel.search(RoomType::DELUXE, stay)) << "\n";
    Room& r101 = hotel.rooms[0];
    hotel.book(r101, "Alice", stay);

    // After booking 101, only 102 remains available for the same dates.
    cout << "Available DELUXE for [10,13): " << names(hotel.search(RoomType::DELUXE, stay)) << "\n";
    try { hotel.book(r101, "Bob", DateRange(12, 15)); } // overlaps [10,13) -> rejected
    catch (exception& e) { cout << "  rejected: " << e.what() << "\n"; }

    hotel.book(r101, "Bob", DateRange(13, 16)); // adjacent, no overlap -> OK
    return 0;
}

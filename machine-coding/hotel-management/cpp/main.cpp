// HOTEL MANAGEMENT / BOOKING — date-range availability + reservations (C++17)
//
// Rooms are booked for half-open intervals [checkIn, checkOut). A new booking is
// allowed only if it doesn't overlap existing ones:
//   [s1,e1) and [s2,e2) overlap iff s1 < e2 && s2 < e1.
// Dates are day-number ints to stay dependency-free.

#include <iostream>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstdio>
using namespace std;

enum class RoomType { STANDARD, DELUXE, SUITE };
static string typeStr(RoomType t) {
    return t == RoomType::STANDARD ? "STANDARD" : t == RoomType::DELUXE ? "DELUXE" : "SUITE";
}

struct DateRange {
    int checkIn, checkOut; // [checkIn, checkOut)
    DateRange(int in, int out) : checkIn(in), checkOut(out) {
        if (out <= in) throw invalid_argument("checkout must be after checkin");
    }
    bool overlaps(const DateRange& o) const { return checkIn < o.checkOut && o.checkIn < checkOut; }
};

struct Room {
    string number; RoomType type; double pricePerNight;
    vector<DateRange> bookings;
    bool isAvailable(const DateRange& r) const {
        for (auto& b : bookings) if (b.overlaps(r)) return false;
        return true;
    }
};

struct Reservation {
    string id; Room* room; string guest; DateRange range; double amount;
    Reservation(string i, Room* rm, string g, DateRange r)
        : id(move(i)), room(rm), guest(move(g)), range(r),
          amount((r.checkOut - r.checkIn) * rm->pricePerNight) {}
};

class Hotel {
    int resSeq = 1;
public:
    string name;
    vector<Room> rooms;
    explicit Hotel(string n) : name(move(n)) {}
    void addRoom(const Room& r) { rooms.push_back(r); }

    vector<Room*> search(RoomType type, const DateRange& range) {
        vector<Room*> result;
        for (auto& r : rooms) if (r.type == type && r.isAvailable(range)) result.push_back(&r);
        return result;
    }

    Reservation book(Room& room, const string& guest, const DateRange& range) {
        if (!room.isAvailable(range))
            throw runtime_error("room " + room.number + " not available for those dates");
        room.bookings.push_back(range);
        Reservation res("RES" + to_string(resSeq++), &room, guest, range);
        printf("Booked %s (%s) for %s [%d,%d) -> Rs %.0f\n",
               room.number.c_str(), typeStr(room.type).c_str(), guest.c_str(),
               range.checkIn, range.checkOut, res.amount);
        return res;
    }
};

static string names(const vector<Room*>& rooms) {
    string s = "[";
    for (size_t i = 0; i < rooms.size(); ++i) s += rooms[i]->number + (i + 1 < rooms.size() ? "," : "");
    return s + "]";
}

int main() {
    Hotel hotel("Grand Palace");
    hotel.addRoom({"101", RoomType::DELUXE, 5000, {}});
    hotel.addRoom({"102", RoomType::DELUXE, 5000, {}});
    hotel.addRoom({"201", RoomType::SUITE, 12000, {}});

    DateRange stay(10, 13);

    cout << "Available DELUXE for [10,13): " << names(hotel.search(RoomType::DELUXE, stay)) << "\n";
    Room& r101 = hotel.rooms[0];
    hotel.book(r101, "Alice", stay);

    cout << "Available DELUXE for [10,13): " << names(hotel.search(RoomType::DELUXE, stay)) << "\n";
    try { hotel.book(r101, "Bob", DateRange(12, 15)); }
    catch (exception& e) { cout << "  rejected: " << e.what() << "\n"; }

    hotel.book(r101, "Bob", DateRange(13, 16)); // adjacent, no overlap -> OK
    return 0;
}

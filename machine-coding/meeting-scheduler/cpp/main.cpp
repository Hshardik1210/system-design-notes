// MEETING ROOM SCHEDULER (C++17)
//
// Book [start,end) intervals into rooms with conflict detection, plus a
// sweep-line "minimum rooms needed" computation.
// Per room: map<start,end> sorted by start; only adjacent bookings can overlap.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <stdexcept>
#include <cstdio>
using namespace std;

// A meeting time slot as a half-open interval [start, end): 'start' is included
// but 'end' is not, so [9,10) and [10,12) touch but do not overlap.
struct Interval {
    int start, end; // [start, end)
    // Reject invalid meetings that end at or before they start.
    Interval(int s, int e) : start(s), end(e) { if (e <= s) throw invalid_argument("end<=start"); }
};

// A single bookable room that stores its bookings sorted by start time.
class Room {
    map<int, int> bookings; // start -> end
public:
    string name;
    explicit Room(string n) : name(move(n)) {}

    // Can this room host iv without overlapping an existing booking?
    // Because bookings are sorted and non-overlapping, only the neighbour just
    // before iv.start and the one at/after iv.start can possibly clash.
    bool canBook(const Interval& iv) const {
        // Booking starting at/before iv.start:
        auto it = bookings.upper_bound(iv.start); // first start > iv.start
        // Step back one to get the last booking that starts <= iv.start.
        if (it != bookings.begin()) {
            auto prevIt = prev(it);
            // It clashes only if that earlier meeting ends after iv starts.
            if (prevIt->second > iv.start) return false; // previous overlaps
        }
        // Booking starting at/after iv.start:
        auto ceil = bookings.lower_bound(iv.start); // first start >= iv.start
        // It clashes only if that later meeting starts before iv ends.
        if (ceil != bookings.end() && ceil->first < iv.end) return false; // next overlaps
        return true;
    }
    // Record the booking (assumes canBook was already verified).
    void book(const Interval& iv) { bookings[iv.start] = iv.end; }
};

// Owns the rooms and assigns bookings to them.
class Scheduler {
    vector<Room> rooms;
public:
    void addRoom(const Room& r) { rooms.push_back(r); }
    // First-fit booking: place the meeting in the first room with no conflict.
    Room* book(const Interval& iv, const string& title) {
        for (auto& r : rooms) {
            if (r.canBook(iv)) {
                r.book(iv);
                printf("Booked '%s' [%d,%d) in %s\n", title.c_str(), iv.start, iv.end, r.name.c_str());
                return &r;
            }
        }
        printf("No room free for '%s' [%d,%d)\n", title.c_str(), iv.start, iv.end);
        return nullptr;
    }
};

// Sweep-line: fewest rooms needed to host all meetings at once.
// Split meetings into sorted start times and end times, then sweep through time;
// the highest number of simultaneously running meetings is the answer.
int minRoomsNeeded(vector<Interval> meetings) {
    int n = meetings.size();
    vector<int> starts(n), ends(n);
    for (int i = 0; i < n; i++) { starts[i] = meetings[i].start; ends[i] = meetings[i].end; }
    sort(starts.begin(), starts.end());
    sort(ends.begin(), ends.end());
    // rooms = current concurrency; maxRooms = peak; i/j walk starts/ends.
    int rooms = 0, maxRooms = 0, i = 0, j = 0;
    while (i < n) {
        // A meeting starts before the earliest end: needs a new room now.
        if (starts[i] < ends[j]) { rooms++; i++; maxRooms = max(maxRooms, rooms); }
        // Otherwise a meeting has ended, freeing one room.
        else { rooms--; j++; }
    }
    return maxRooms;
}

// Demo: create two rooms, run bookings, and print the min rooms needed.
int main() {
    Scheduler s;
    s.addRoom(Room("Alpha"));
    s.addRoom(Room("Beta"));

    s.book(Interval(9, 10), "Standup");
    s.book(Interval(9, 11), "Design review");
    s.book(Interval(10, 12), "1:1");
    s.book(Interval(9, 10), "Sync");   // rejected
    s.book(Interval(11, 12), "Retro");

    vector<Interval> meetings{Interval(9, 10), Interval(9, 11), Interval(10, 12), Interval(11, 12)};
    cout << "Min rooms needed for those 4 meetings: " << minRoomsNeeded(meetings) << "\n";
    return 0;
}

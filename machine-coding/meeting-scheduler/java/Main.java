import java.util.*;

/**
 * MEETING ROOM SCHEDULER — book time intervals into rooms, detect conflicts,
 * and answer "minimum rooms needed" for a set of meetings.
 *
 * Booking: find any room whose existing intervals don't overlap the request.
 * Per room we keep a TreeMap<startTime, endTime> so we can find neighbouring
 * bookings in O(log n) and check only the adjacent ones for overlap.
 *
 * minRoomsNeeded: classic sweep-line — sort start & end times; a room is needed
 * when a meeting starts before another ends (max concurrent overlaps).
 */
public class Main {

    // A meeting time slot expressed as a half-open interval [start, end):
    // it includes 'start' but not 'end', so [9,10) and [10,12) sit back-to-back
    // without overlapping (10 is the shared boundary, owned by nobody).
    static class Interval {
        final int start, end; // [start, end)
        // Guard against nonsense input: a meeting must end after it starts.
        Interval(int s, int e) { if (e <= s) throw new IllegalArgumentException("end<=start"); start = s; end = e; }
    }

    // A single bookable room that remembers all the intervals booked into it.
    static class Room {
        final String name;
        // start -> end, sorted by start for fast neighbour lookup.
        // Using a TreeMap (sorted by key) lets us jump straight to the
        // neighbouring bookings around a new request in O(log n).
        private final TreeMap<Integer, Integer> bookings = new TreeMap<>();
        Room(String name) { this.name = name; }

        // Can this room host interval iv without overlapping an existing booking?
        // Key insight: in a sorted list of non-overlapping bookings, only the
        // booking just before iv.start and the one at/after iv.start can clash,
        // so we only need to inspect those two neighbours instead of scanning all.
        boolean canBook(Interval iv) {
            // Check the booking starting just before iv.start ...
            // floorEntry finds the booking with the largest start <= iv.start.
            Map.Entry<Integer, Integer> floor = bookings.floorEntry(iv.start);
            // It overlaps only if that earlier meeting ends after iv starts.
            if (floor != null && floor.getValue() > iv.start) return false; // previous overlaps
            // ... and the booking starting at/after iv.start.
            // ceilingEntry finds the booking with the smallest start >= iv.start.
            Map.Entry<Integer, Integer> ceil = bookings.ceilingEntry(iv.start);
            // It overlaps only if that later meeting starts before iv ends.
            if (ceil != null && ceil.getKey() < iv.end) return false;       // next overlaps
            return true;
        }
        // Record the booking (assumes canBook was already checked true).
        void book(Interval iv) { bookings.put(iv.start, iv.end); }
    }

    // Owns the list of rooms and decides which room gets a booking.
    static class Scheduler {
        private final List<Room> rooms = new ArrayList<>();
        void addRoom(Room r) { rooms.add(r); }

        // Book into the first room that has no conflict; else return null.
        // This is a "first-fit" strategy: scan rooms in order and take the first
        // one that is free for this interval.
        Room book(Interval iv, String title) {
            for (Room r : rooms) {
                if (r.canBook(iv)) {
                    r.book(iv);
                    System.out.printf("Booked '%s' [%d,%d) in %s%n", title, iv.start, iv.end, r.name);
                    return r;
                }
            }
            System.out.printf("No room free for '%s' [%d,%d)%n", title, iv.start, iv.end);
            return null;
        }
    }

    // Sweep-line: minimum rooms required to host all given meetings.
    // Idea: walk through time; every meeting that starts before the next one ends
    // forces a new concurrent room, and the peak concurrency is the answer.
    static int minRoomsNeeded(List<Interval> meetings) {
        int n = meetings.size();
        // Split every meeting into two separate event streams: its start times
        // and its end times, then sort each independently.
        int[] starts = new int[n], ends = new int[n];
        for (int i = 0; i < n; i++) { starts[i] = meetings.get(i).start; ends[i] = meetings.get(i).end; }
        Arrays.sort(starts); Arrays.sort(ends);
        // rooms = currently occupied rooms; maxRooms = peak seen so far.
        // i walks the sorted starts, j walks the sorted ends.
        int rooms = 0, maxRooms = 0, i = 0, j = 0;
        while (i < n) {
            // A meeting starts before the earliest unmatched end: it needs a new
            // room right now, so occupancy goes up and we track the new peak.
            if (starts[i] < ends[j]) { rooms++; i++; maxRooms = Math.max(maxRooms, rooms); }
            // Otherwise the earliest meeting has ended, freeing one room.
            else { rooms--; j++; }
        }
        return maxRooms;
    }

    // Demo: set up two rooms, run some bookings, and compute min rooms needed.
    public static void main(String[] args) {
        Scheduler s = new Scheduler();
        s.addRoom(new Room("Alpha"));
        s.addRoom(new Room("Beta"));

        s.book(new Interval(9, 10), "Standup");        // Alpha
        s.book(new Interval(9, 11), "Design review");  // Beta (overlaps standup)
        s.book(new Interval(10, 12), "1:1");           // Alpha (adjacent to standup)
        s.book(new Interval(9, 10), "Sync");           // none free -> rejected
        s.book(new Interval(11, 12), "Retro");         // Beta (after design review)

        List<Interval> meetings = Arrays.asList(
                new Interval(9, 10), new Interval(9, 11), new Interval(10, 12), new Interval(11, 12));
        System.out.println("Min rooms needed for those 4 meetings: " + minRoomsNeeded(meetings));
    }
}

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

    static class Interval {
        final int start, end; // [start, end)
        Interval(int s, int e) { if (e <= s) throw new IllegalArgumentException("end<=start"); start = s; end = e; }
    }

    static class Room {
        final String name;
        // start -> end, sorted by start for fast neighbour lookup.
        private final TreeMap<Integer, Integer> bookings = new TreeMap<>();
        Room(String name) { this.name = name; }

        boolean canBook(Interval iv) {
            // Check the booking starting just before iv.start ...
            Map.Entry<Integer, Integer> floor = bookings.floorEntry(iv.start);
            if (floor != null && floor.getValue() > iv.start) return false; // previous overlaps
            // ... and the booking starting at/after iv.start.
            Map.Entry<Integer, Integer> ceil = bookings.ceilingEntry(iv.start);
            if (ceil != null && ceil.getKey() < iv.end) return false;       // next overlaps
            return true;
        }
        void book(Interval iv) { bookings.put(iv.start, iv.end); }
    }

    static class Scheduler {
        private final List<Room> rooms = new ArrayList<>();
        void addRoom(Room r) { rooms.add(r); }

        // Book into the first room that has no conflict; else return null.
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
    static int minRoomsNeeded(List<Interval> meetings) {
        int n = meetings.size();
        int[] starts = new int[n], ends = new int[n];
        for (int i = 0; i < n; i++) { starts[i] = meetings.get(i).start; ends[i] = meetings.get(i).end; }
        Arrays.sort(starts); Arrays.sort(ends);
        int rooms = 0, maxRooms = 0, i = 0, j = 0;
        while (i < n) {
            if (starts[i] < ends[j]) { rooms++; i++; maxRooms = Math.max(maxRooms, rooms); }
            else { rooms--; j++; }
        }
        return maxRooms;
    }

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

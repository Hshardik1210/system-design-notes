import java.util.*;

/**
 * HOTEL MANAGEMENT / BOOKING — availability over date ranges + reservations.
 *
 * Core problem: a room can be booked for [checkIn, checkOut) intervals. A new
 * booking is allowed only if it does NOT overlap any existing booking for that
 * room. Two half-open intervals [s1,e1) and [s2,e2) overlap iff s1 < e2 && s2 < e1.
 *
 * search() returns rooms of a given type that are free for the requested dates.
 *
 * Dates are modelled as ints (day numbers) to keep the demo dependency-free.
 */
public class Main {

    enum RoomType { STANDARD, DELUXE, SUITE }

    static class DateRange {
        final int checkIn, checkOut; // half-open [checkIn, checkOut)
        DateRange(int in, int out) {
            if (out <= in) throw new IllegalArgumentException("checkout must be after checkin");
            this.checkIn = in; this.checkOut = out;
        }
        boolean overlaps(DateRange o) { return checkIn < o.checkOut && o.checkIn < checkOut; }
    }

    static class Room {
        final String number; final RoomType type; final double pricePerNight;
        // Existing bookings for this room.
        final List<DateRange> bookings = new ArrayList<>();
        Room(String number, RoomType type, double price) { this.number = number; this.type = type; this.pricePerNight = price; }

        boolean isAvailable(DateRange range) {
            for (DateRange b : bookings) if (b.overlaps(range)) return false;
            return true;
        }
    }

    static class Reservation {
        final String id; final Room room; final String guest; final DateRange range; final double amount;
        Reservation(String id, Room room, String guest, DateRange range) {
            this.id = id; this.room = room; this.guest = guest; this.range = range;
            this.amount = (range.checkOut - range.checkIn) * room.pricePerNight;
        }
    }

    static class Hotel {
        final String name;
        final List<Room> rooms = new ArrayList<>();
        private int resSeq = 1;
        Hotel(String name) { this.name = name; }
        void addRoom(Room r) { rooms.add(r); }

        // Available rooms of a type for the requested dates.
        List<Room> search(RoomType type, DateRange range) {
            List<Room> result = new ArrayList<>();
            for (Room r : rooms)
                if (r.type == type && r.isAvailable(range)) result.add(r);
            return result;
        }

        // Reserve a specific room; re-checks availability to avoid races.
        Reservation book(Room room, String guest, DateRange range) {
            if (!room.isAvailable(range))
                throw new IllegalStateException("room " + room.number + " not available for those dates");
            room.bookings.add(range);
            Reservation res = new Reservation("RES" + resSeq++, room, guest, range);
            System.out.printf("Booked %s (%s) for %s [%d,%d) -> Rs %.0f%n",
                    room.number, room.type, guest, range.checkIn, range.checkOut, res.amount);
            return res;
        }
    }

    public static void main(String[] args) {
        Hotel hotel = new Hotel("Grand Palace");
        hotel.addRoom(new Room("101", RoomType.DELUXE, 5000));
        hotel.addRoom(new Room("102", RoomType.DELUXE, 5000));
        hotel.addRoom(new Room("201", RoomType.SUITE, 12000));

        DateRange stay = new DateRange(10, 13); // 3 nights

        System.out.println("Available DELUXE for [10,13): " + names(hotel.search(RoomType.DELUXE, stay)));
        Room r101 = hotel.rooms.get(0);
        hotel.book(r101, "Alice", stay);

        // Overlapping request for 101 must be rejected; 102 still free.
        System.out.println("Available DELUXE for [10,13): " + names(hotel.search(RoomType.DELUXE, stay)));
        try { hotel.book(r101, "Bob", new DateRange(12, 15)); } // overlaps [10,13)
        catch (Exception e) { System.out.println("  rejected: " + e.getMessage()); }

        // Non-overlapping later stay for 101 is fine.
        hotel.book(r101, "Bob", new DateRange(13, 16)); // starts exactly at previous checkout -> OK
    }

    static List<String> names(List<Room> rooms) {
        List<String> n = new ArrayList<>();
        for (Room r : rooms) n.add(r.number);
        return n;
    }
}

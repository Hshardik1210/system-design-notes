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
 *
 * WHAT THIS PROGRAM DOES (beginner overview):
 *   It models a small hotel. You can search for free rooms of a given type over
 *   a date range and then book one. The tricky part is deciding whether two
 *   stays clash in time; that is handled by the interval-overlap check below.
 *
 * KEY CLASSES:
 *   - RoomType   : the categories of rooms (STANDARD, DELUXE, SUITE).
 *   - DateRange  : a validated stay interval [checkIn, checkOut) with overlaps().
 *   - Room       : one physical room plus the list of bookings already on it.
 *   - Reservation: a confirmed booking (who, which room, when, and the price).
 *   - Hotel      : the collection of rooms; offers search() and book().
 *
 * DESIGN NOTES / PATTERNS:
 *   - Value object: DateRange is a small immutable object that validates itself.
 *   - Encapsulation: each Room owns and checks its own bookings (isAvailable).
 *   - "Half-open" intervals make back-to-back stays (checkout day == next
 *     check-in day) legal, which mirrors how real hotels work.
 */
public class Main {

    // The kinds of rooms the hotel offers; used to filter during search().
    enum RoomType { STANDARD, DELUXE, SUITE }

    // A stay interval [checkIn, checkOut); immutable and self-validating.
    static class DateRange {
        final int checkIn, checkOut; // half-open [checkIn, checkOut)
        DateRange(int in, int out) {
            // Reject nonsense ranges up front so the rest of the code can trust them.
            if (out <= in) throw new IllegalArgumentException("checkout must be after checkin");
            this.checkIn = in; this.checkOut = out;
        }
        // True if this range and o share at least one night. Because intervals are
        // half-open, touching ranges (this.checkOut == o.checkIn) do NOT overlap.
        boolean overlaps(DateRange o) { return checkIn < o.checkOut && o.checkIn < checkOut; }
    }

    // One physical room: its label, category, nightly price, and current bookings.
    static class Room {
        final String number; final RoomType type; final double pricePerNight;
        // Existing bookings for this room.
        final List<DateRange> bookings = new ArrayList<>();
        Room(String number, RoomType type, double price) { this.number = number; this.type = type; this.pricePerNight = price; }

        // Free for the given range only if it clashes with none of the existing bookings.
        boolean isAvailable(DateRange range) {
            for (DateRange b : bookings) if (b.overlaps(range)) return false;
            return true;
        }
    }

    // A confirmed booking: who booked which room, for which dates, and the total price.
    static class Reservation {
        final String id; final Room room; final String guest; final DateRange range; final double amount;
        Reservation(String id, Room room, String guest, DateRange range) {
            this.id = id; this.room = room; this.guest = guest; this.range = range;
            // Price = number of nights (checkOut - checkIn) times the nightly rate.
            this.amount = (range.checkOut - range.checkIn) * room.pricePerNight;
        }
    }

    // The hotel: holds all rooms and provides the search + booking operations.
    static class Hotel {
        final String name;
        final List<Room> rooms = new ArrayList<>();
        private int resSeq = 1; // counter used to hand out unique reservation ids
        Hotel(String name) { this.name = name; }
        void addRoom(Room r) { rooms.add(r); }

        // Available rooms of a type for the requested dates.
        // Returns every room that matches the type AND is free for the whole range.
        List<Room> search(RoomType type, DateRange range) {
            List<Room> result = new ArrayList<>();
            for (Room r : rooms)
                if (r.type == type && r.isAvailable(range)) result.add(r);
            return result;
        }

        // Reserve a specific room; re-checks availability to avoid races.
        // Even if search() said the room was free, we check again right before
        // committing so two near-simultaneous bookings can't both succeed.
        Reservation book(Room room, String guest, DateRange range) {
            if (!room.isAvailable(range))
                throw new IllegalStateException("room " + room.number + " not available for those dates");
            room.bookings.add(range); // claim the dates so future overlap checks see them
            Reservation res = new Reservation("RES" + resSeq++, room, guest, range);
            System.out.printf("Booked %s (%s) for %s [%d,%d) -> Rs %.0f%n",
                    room.number, room.type, guest, range.checkIn, range.checkOut, res.amount);
            return res;
        }
    }

    // Demo driver: sets up a hotel and walks through search + booking scenarios.
    public static void main(String[] args) {
        Hotel hotel = new Hotel("Grand Palace");
        hotel.addRoom(new Room("101", RoomType.DELUXE, 5000));
        hotel.addRoom(new Room("102", RoomType.DELUXE, 5000));
        hotel.addRoom(new Room("201", RoomType.SUITE, 12000));

        DateRange stay = new DateRange(10, 13); // 3 nights

        // Both DELUXE rooms are free at first.
        System.out.println("Available DELUXE for [10,13): " + names(hotel.search(RoomType.DELUXE, stay)));
        Room r101 = hotel.rooms.get(0);
        hotel.book(r101, "Alice", stay);

        // Overlapping request for 101 must be rejected; 102 still free.
        // After booking 101, only 102 shows up as available for the same dates.
        System.out.println("Available DELUXE for [10,13): " + names(hotel.search(RoomType.DELUXE, stay)));
        try { hotel.book(r101, "Bob", new DateRange(12, 15)); } // overlaps [10,13)
        catch (Exception e) { System.out.println("  rejected: " + e.getMessage()); }

        // Non-overlapping later stay for 101 is fine.
        hotel.book(r101, "Bob", new DateRange(13, 16)); // starts exactly at previous checkout -> OK
    }

    // Small helper: turn a list of rooms into a list of their room numbers for printing.
    static List<String> names(List<Room> rooms) {
        List<String> n = new ArrayList<>();
        for (Room r : rooms) n.add(r.number);
        return n;
    }
}

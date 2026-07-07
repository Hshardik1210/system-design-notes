import java.util.*;

/**
 * AIRLINE / FLIGHT BOOKING — search flights by route, book seats by fare class.
 *
 * Each flight has a seat map partitioned by fare class (ECONOMY / BUSINESS /
 * FIRST), each class with its own price. Booking picks a free seat in the
 * requested class; search lists flights on a route that still have a free seat
 * in that class.
 *
 * Pattern: simple inventory + search; booking guarded for concurrency.
 */
public class Main {

    // The three fare classes a seat can belong to. Each class is priced separately per flight.
    enum FareClass { ECONOMY, BUSINESS, FIRST }

    // A single seat: its display number (e.g. "F1"), which fare class it is, and whether it is already booked.
    static class Seat {
        final String number; final FareClass fareClass; boolean booked = false;
        Seat(String number, FareClass fareClass) { this.number = number; this.fareClass = fareClass; }
    }

    // A flight: its route (from/to/day), the full list of seats, and the price for each fare class.
    static class Flight {
        final String no, from, to; final int day; // day number
        final List<Seat> seats = new ArrayList<>();
        // Price per fare class; EnumMap is a compact map keyed by the FareClass enum.
        final Map<FareClass, Double> price = new EnumMap<>(FareClass.class);
        // Lock used to guard seat booking so two threads can't grab the same seat.
        private final Object lock = new Object();

        Flight(String no, String from, String to, int day) { this.no = no; this.from = from; this.to = to; this.day = day; }

        // Add `count` seats of one fare class and record that class's price.
        void addSeats(FareClass fc, int count, double fare) {
            price.put(fc, fare);
            char prefix = fc.name().charAt(0); // first letter of class name -> seat prefix (F/B/E)
            int start = seats.size() + 1;      // continue numbering after any existing seats
            for (int i = 0; i < count; i++) seats.add(new Seat(prefix + String.valueOf(start + i), fc));
        }
        // True if at least one unbooked seat exists in the requested class.
        boolean hasFree(FareClass fc) {
            for (Seat s : seats) if (s.fareClass == fc && !s.booked) return true;
            return false;
        }
        // Book the first free seat in a class; returns seat or null.
        Seat book(FareClass fc) {
            // synchronized block: only one thread at a time can search-and-mark a seat,
            // which prevents two passengers from being assigned the same seat (double-booking).
            synchronized (lock) {
                for (Seat s : seats) if (s.fareClass == fc && !s.booked) { s.booked = true; return s; }
                return null;
            }
        }
    }

    // In-memory inventory of flights plus the search and booking operations over them.
    static class AirlineService {
        private final List<Flight> flights = new ArrayList<>();
        void addFlight(Flight f) { flights.add(f); }

        // Flights on a route/day that still have a free seat in the class.
        List<Flight> search(String from, String to, int day, FareClass fc) {
            List<Flight> res = new ArrayList<>();
            for (Flight f : flights)
                if (f.from.equals(from) && f.to.equals(to) && f.day == day && f.hasFree(fc)) res.add(f);
            return res;
        }

        // Attempt to book a seat on a flight for a passenger; prints the result and returns the seat number (or null if full).
        String book(Flight f, FareClass fc, String passenger) {
            Seat s = f.book(fc);
            if (s == null) { System.out.println("  ! no free " + fc + " seat on " + f.no); return null; }
            System.out.printf("  Booked %s seat %s (%s) for %s @ Rs %.0f%n",
                    f.no, s.number, fc, passenger, f.price.get(fc));
            return s.number;
        }
    }

    // Demo: set up flights with priced seats, then run a few searches and bookings.
    public static void main(String[] args) {
        Flight ai101 = new Flight("AI101", "DEL", "BLR", 5);
        ai101.addSeats(FareClass.FIRST, 1, 30000);
        ai101.addSeats(FareClass.BUSINESS, 2, 15000);
        ai101.addSeats(FareClass.ECONOMY, 3, 5000);

        Flight ai202 = new Flight("AI202", "DEL", "BLR", 5);
        ai202.addSeats(FareClass.ECONOMY, 2, 4500);

        AirlineService svc = new AirlineService();
        svc.addFlight(ai101);
        svc.addFlight(ai202);

        System.out.println("Search DEL->BLR day 5, ECONOMY:");
        for (Flight f : svc.search("DEL", "BLR", 5, FareClass.ECONOMY)) System.out.println("  " + f.no);

        svc.book(ai101, FareClass.FIRST, "Alice");     // F1
        svc.book(ai101, FareClass.FIRST, "Bob");        // full -> rejected
        svc.book(ai101, FareClass.BUSINESS, "Carol");   // B2
        svc.book(ai202, FareClass.ECONOMY, "Dan");      // E1

        System.out.println("Search DEL->BLR day 5, FIRST (ai101 now full):");
        for (Flight f : svc.search("DEL", "BLR", 5, FareClass.FIRST)) System.out.println("  " + f.no);
    }
}

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

    enum FareClass { ECONOMY, BUSINESS, FIRST }

    static class Seat {
        final String number; final FareClass fareClass; boolean booked = false;
        Seat(String number, FareClass fareClass) { this.number = number; this.fareClass = fareClass; }
    }

    static class Flight {
        final String no, from, to; final int day; // day number
        final List<Seat> seats = new ArrayList<>();
        final Map<FareClass, Double> price = new EnumMap<>(FareClass.class);
        private final Object lock = new Object();

        Flight(String no, String from, String to, int day) { this.no = no; this.from = from; this.to = to; this.day = day; }

        void addSeats(FareClass fc, int count, double fare) {
            price.put(fc, fare);
            char prefix = fc.name().charAt(0);
            int start = seats.size() + 1;
            for (int i = 0; i < count; i++) seats.add(new Seat(prefix + String.valueOf(start + i), fc));
        }
        boolean hasFree(FareClass fc) {
            for (Seat s : seats) if (s.fareClass == fc && !s.booked) return true;
            return false;
        }
        // Book the first free seat in a class; returns seat or null.
        Seat book(FareClass fc) {
            synchronized (lock) {
                for (Seat s : seats) if (s.fareClass == fc && !s.booked) { s.booked = true; return s; }
                return null;
            }
        }
    }

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

        String book(Flight f, FareClass fc, String passenger) {
            Seat s = f.book(fc);
            if (s == null) { System.out.println("  ! no free " + fc + " seat on " + f.no); return null; }
            System.out.printf("  Booked %s seat %s (%s) for %s @ Rs %.0f%n",
                    f.no, s.number, fc, passenger, f.price.get(fc));
            return s.number;
        }
    }

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

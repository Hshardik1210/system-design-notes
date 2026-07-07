import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BOOKMYSHOW — movie ticket booking with concurrency-safe seat locking.
 *
 * The hard part is: two users must never book the same seat. We solve it with a
 * two-phase flow (exactly like real systems):
 *   1) HOLD  -> temporarily lock seats for a user (expires after N seconds).
 *   2) CONFIRM (after payment) -> convert held seats to BOOKED.
 * A held seat that is not confirmed in time is auto-released.
 *
 * All seat state transitions for a show happen under that show's lock, so the
 * check-then-set is atomic (no double booking).
 *
 * Patterns: State (seat status), Singleton-ish service.
 */
public class Main {

    enum SeatStatus { AVAILABLE, HELD, BOOKED }

    static class Seat {
        final String id;
        SeatStatus status = SeatStatus.AVAILABLE;
        String heldBy;          // user holding it
        long holdExpiryMillis;  // when the hold lapses
        Seat(String id) { this.id = id; }
    }

    static class Show {
        final String id;
        final String movie;
        final Map<String, Seat> seats = new LinkedHashMap<>();
        final ReentrantLock lock = new ReentrantLock(); // guards all seat mutations
        Show(String id, String movie, List<String> seatIds) {
            this.id = id; this.movie = movie;
            for (String s : seatIds) seats.put(s, new Seat(s));
        }
    }

    static class BookingService {
        private final Map<String, Show> shows = new ConcurrentHashMap<>();
        private final long holdMillis;
        BookingService(long holdMillis) { this.holdMillis = holdMillis; }

        void addShow(Show s) { shows.put(s.id, s); }

        // Phase 1: try to HOLD the requested seats for a user. All-or-nothing.
        boolean hold(String showId, List<String> seatIds, String user) {
            Show show = shows.get(showId);
            show.lock.lock();
            try {
                long now = System.currentTimeMillis();
                // First pass: verify every seat is free (or an expired hold).
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    if (seat == null) { System.out.println("  no such seat " + sid); return false; }
                    boolean expired = seat.status == SeatStatus.HELD && now > seat.holdExpiryMillis;
                    if (seat.status == SeatStatus.BOOKED || (seat.status == SeatStatus.HELD && !expired)) {
                        System.out.println("  HOLD failed for " + user + ": " + sid + " is " + seat.status);
                        return false;
                    }
                }
                // Second pass: commit the holds (safe: we hold the lock the whole time).
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    seat.status = SeatStatus.HELD;
                    seat.heldBy = user;
                    seat.holdExpiryMillis = now + holdMillis;
                }
                System.out.println("  HELD " + seatIds + " for " + user);
                return true;
            } finally { show.lock.unlock(); }
        }

        // Phase 2: after payment, CONFIRM the user's held seats -> BOOKED.
        boolean confirm(String showId, List<String> seatIds, String user) {
            Show show = shows.get(showId);
            show.lock.lock();
            try {
                long now = System.currentTimeMillis();
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    boolean validHold = seat.status == SeatStatus.HELD
                            && user.equals(seat.heldBy) && now <= seat.holdExpiryMillis;
                    if (!validHold) {
                        System.out.println("  CONFIRM failed for " + user + ": hold on " + sid + " invalid/expired");
                        return false;
                    }
                }
                for (String sid : seatIds) show.seats.get(sid).status = SeatStatus.BOOKED;
                System.out.println("  BOOKED " + seatIds + " for " + user);
                return true;
            } finally { show.lock.unlock(); }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        BookingService svc = new BookingService(2000); // 2s hold window
        svc.addShow(new Show("S1", "Inception", Arrays.asList("A1", "A2", "A3", "A4")));

        // Two users race for overlapping seats; only one wins the overlap (A2).
        System.out.println("--- Concurrent holds ---");
        Runnable alice = () -> svc.hold("S1", Arrays.asList("A1", "A2"), "Alice");
        Runnable bob   = () -> svc.hold("S1", Arrays.asList("A2", "A3"), "Bob");
        Thread t1 = new Thread(alice), t2 = new Thread(bob);
        t1.start(); t1.join(); // sequential-ish for deterministic demo
        t2.start(); t2.join();

        System.out.println("--- Alice pays & confirms ---");
        svc.confirm("S1", Arrays.asList("A1", "A2"), "Alice");

        System.out.println("--- Bob tries seats incl. A2 (booked) ---");
        svc.hold("S1", Arrays.asList("A2", "A3"), "Bob");   // fails: A2 booked
        svc.hold("S1", Arrays.asList("A3", "A4"), "Bob");   // ok

        System.out.println("--- Hold expiry demo ---");
        svc.hold("S1", Collections.singletonList("A3"), "Carol"); // already held by Bob -> fails
        Thread.sleep(2100); // let Bob's hold on A3/A4 expire
        svc.hold("S1", Collections.singletonList("A3"), "Carol"); // now succeeds
    }
}

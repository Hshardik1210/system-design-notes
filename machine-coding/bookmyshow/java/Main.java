import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BOOKMYSHOW — movie ticket booking with concurrency-safe seat locking.
 *
 * WHAT THIS PROGRAM DOES:
 *   Lets users book seats for a movie show while guaranteeing that the same
 *   seat is never sold to two people, even if their requests arrive at the
 *   same instant.
 *
 * THE HARD PART — concurrency:
 *   Two users must never book the same seat. We solve it with a two-phase flow
 *   (exactly like real ticketing systems):
 *     1) HOLD  -> temporarily lock seats for a user (expires after N seconds).
 *     2) CONFIRM (after payment) -> convert held seats to BOOKED.
 *   A held seat that is not confirmed in time is auto-released (freed lazily on
 *   the next hold attempt), so abandoned carts don't block seats forever.
 *
 * WHY IT'S SAFE:
 *   All seat state transitions for a show happen under that show's lock, so the
 *   "check every seat is free -> then set them held" runs as one atomic critical
 *   section. A competing thread runs fully before or fully after — never mixed
 *   in the middle — so double booking is impossible.
 *
 * KEY CLASSES:
 *   Seat           - one physical seat plus its current status.
 *   Show           - a movie showing; owns its seats and the lock that guards them.
 *   BookingService - the API: addShow, hold (phase 1), confirm (phase 2).
 *
 * DESIGN PATTERNS:
 *   State           - SeatStatus drives the seat lifecycle AVAILABLE -> HELD -> BOOKED.
 *   Two-phase locking - HOLD then CONFIRM, to prevent double-booking.
 */
public class Main {

    // State pattern: the lifecycle a seat moves through.
    // AVAILABLE --hold--> HELD --confirm--> BOOKED (and HELD can lapse back to free).
    enum SeatStatus { AVAILABLE, HELD, BOOKED }

    // One seat: its id, current status, and (if held) who holds it and until when.
    static class Seat {
        final String id;
        SeatStatus status = SeatStatus.AVAILABLE;
        String heldBy;          // user holding it
        long holdExpiryMillis;  // when the hold lapses
        Seat(String id) { this.id = id; }
    }

    // A single movie showing. Owns all its seats and the lock that makes every
    // seat change for this show atomic.
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

    // The booking API. Holds all shows and implements the two-phase hold/confirm flow.
    static class BookingService {
        private final Map<String, Show> shows = new ConcurrentHashMap<>();
        private final long holdMillis;
        BookingService(long holdMillis) { this.holdMillis = holdMillis; }

        // Register a show so users can book seats in it.
        void addShow(Show s) { shows.put(s.id, s); }

        // Phase 1: try to HOLD the requested seats for a user. All-or-nothing:
        // either every seat gets held, or nothing changes. The whole check-then-set
        // runs under the show's lock so two racing holds can't both grab the same seat.
        boolean hold(String showId, List<String> seatIds, String user) {
            Show show = shows.get(showId);
            show.lock.lock(); // enter the critical section for this show
            try {
                long now = System.currentTimeMillis();
                // First pass: verify EVERY requested seat is free (or its hold has expired).
                // If any one is unavailable we bail out before changing anything.
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    if (seat == null) { System.out.println("  no such seat " + sid); return false; }
                    // A HELD seat past its expiry time counts as free again (lazy auto-release).
                    boolean expired = seat.status == SeatStatus.HELD && now > seat.holdExpiryMillis;
                    // Blocked if already BOOKED, or HELD by someone whose hold is still valid.
                    if (seat.status == SeatStatus.BOOKED || (seat.status == SeatStatus.HELD && !expired)) {
                        System.out.println("  HOLD failed for " + user + ": " + sid + " is " + seat.status);
                        return false;
                    }
                }
                // Second pass: commit the holds. Safe to mutate now because the first pass
                // proved all seats are free AND we still hold the lock (no thread can slip in).
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    seat.status = SeatStatus.HELD;              // AVAILABLE/expired -> HELD
                    seat.heldBy = user;
                    seat.holdExpiryMillis = now + holdMillis;   // start the expiry countdown
                }
                System.out.println("  HELD " + seatIds + " for " + user);
                return true;
            } finally { show.lock.unlock(); } // always release, even on early return
        }

        // Phase 2: after payment, CONFIRM the user's held seats -> BOOKED.
        // Only succeeds if this exact user still holds every seat and none have expired.
        boolean confirm(String showId, List<String> seatIds, String user) {
            Show show = shows.get(showId);
            show.lock.lock();
            try {
                long now = System.currentTimeMillis();
                // Validate all holds first (all-or-nothing again).
                for (String sid : seatIds) {
                    Seat seat = show.seats.get(sid);
                    // A hold is valid only if it's still HELD, held by THIS user, and not expired.
                    boolean validHold = seat.status == SeatStatus.HELD
                            && user.equals(seat.heldBy) && now <= seat.holdExpiryMillis;
                    if (!validHold) {
                        System.out.println("  CONFIRM failed for " + user + ": hold on " + sid + " invalid/expired");
                        return false;
                    }
                }
                // All holds valid: finalize the sale by flipping HELD -> BOOKED.
                for (String sid : seatIds) show.seats.get(sid).status = SeatStatus.BOOKED;
                System.out.println("  BOOKED " + seatIds + " for " + user);
                return true;
            } finally { show.lock.unlock(); }
        }
    }

    // Demo: exercises the full flow — a race for overlapping seats, a confirm,
    // a blocked hold on a booked seat, and a hold that succeeds after expiry.
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

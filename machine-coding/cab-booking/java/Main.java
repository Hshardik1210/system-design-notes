import java.util.*;

/**
 * CAB BOOKING (Uber/Ola core) — match a rider to the nearest available driver,
 * price the trip (with surge), and drive the trip through its state machine.
 *
 * Patterns:
 *   - Strategy : PricingStrategy (base + surge multiplier) and matching is a
 *                simple nearest-driver strategy (swap for ETA/rating-based).
 *   - State    : Trip goes REQUESTED -> ASSIGNED -> IN_PROGRESS -> COMPLETED.
 */
public class Main {

    // Simple 2D location + Euclidean distance.
    static class Location {
        final double x, y;
        Location(double x, double y) { this.x = x; this.y = y; }
        double distanceTo(Location o) { return Math.hypot(x - o.x, y - o.y); }
    }

    enum DriverStatus { AVAILABLE, ON_TRIP, OFFLINE }
    static class Driver {
        final String id; Location loc; DriverStatus status = DriverStatus.AVAILABLE;
        Driver(String id, Location loc) { this.id = id; this.loc = loc; }
    }
    static class Rider {
        final String id; Location loc;
        Rider(String id, Location loc) { this.id = id; this.loc = loc; }
    }

    enum TripState { REQUESTED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }
    static class Trip {
        final String id; final Rider rider; Driver driver;
        final Location from, to;
        TripState state = TripState.REQUESTED;
        double fare;
        Trip(String id, Rider rider, Location from, Location to) {
            this.id = id; this.rider = rider; this.from = from; this.to = to;
        }
    }

    // ---------- Strategy: pricing ----------
    interface PricingStrategy { double price(double distanceKm, double surge); }
    static class StandardPricing implements PricingStrategy {
        public double price(double distanceKm, double surge) {
            double base = 30, perKm = 12;
            return (base + perKm * distanceKm) * surge;
        }
    }

    static class RideService {
        private final List<Driver> drivers = new ArrayList<>();
        private final PricingStrategy pricing;
        private double surge = 1.0;
        private int tripSeq = 1;
        RideService(PricingStrategy pricing) { this.pricing = pricing; }

        void addDriver(Driver d) { drivers.add(d); }
        void setSurge(double s) { surge = s; } // raised when demand > supply

        // Match nearest AVAILABLE driver to the rider.
        Trip requestRide(Rider rider, Location to) {
            Driver best = null; double bestDist = Double.MAX_VALUE;
            for (Driver d : drivers) {
                if (d.status != DriverStatus.AVAILABLE) continue;
                double dist = d.loc.distanceTo(rider.loc);
                if (dist < bestDist) { bestDist = dist; best = d; }
            }
            Trip trip = new Trip("TR" + tripSeq++, rider, rider.loc, to);
            if (best == null) {
                trip.state = TripState.CANCELLED;
                System.out.println(trip.id + ": no drivers available -> CANCELLED");
                return trip;
            }
            best.status = DriverStatus.ON_TRIP;
            trip.driver = best;
            trip.state = TripState.ASSIGNED;
            double tripKm = rider.loc.distanceTo(to);
            trip.fare = pricing.price(tripKm, surge);
            System.out.printf("%s: assigned driver %s (%.2f km away), fare Rs %.2f (surge %.1fx)%n",
                    trip.id, best.id, bestDist, trip.fare, surge);
            return trip;
        }

        void startTrip(Trip t) {
            if (t.state != TripState.ASSIGNED) return;
            t.state = TripState.IN_PROGRESS;
            System.out.println(t.id + ": IN_PROGRESS");
        }
        void endTrip(Trip t) {
            if (t.state != TripState.IN_PROGRESS) return;
            t.state = TripState.COMPLETED;
            t.driver.status = DriverStatus.AVAILABLE;
            t.driver.loc = t.to; // driver ends at drop location
            System.out.printf("%s: COMPLETED, collected Rs %.2f%n", t.id, t.fare);
        }
    }

    public static void main(String[] args) {
        RideService svc = new RideService(new StandardPricing());
        svc.addDriver(new Driver("D1", new Location(0, 0)));
        svc.addDriver(new Driver("D2", new Location(5, 5)));
        svc.addDriver(new Driver("D3", new Location(1, 1)));

        Rider alice = new Rider("alice", new Location(1, 0)); // closest to D1 and D3

        Trip t1 = svc.requestRide(alice, new Location(10, 0)); // ~D1/D3
        svc.startTrip(t1);
        svc.endTrip(t1);

        System.out.println("--- Surge 2x, two more requests ---");
        svc.setSurge(2.0);
        Rider bob = new Rider("bob", new Location(4, 5));
        Trip t2 = svc.requestRide(bob, new Location(0, 0));
        Rider carol = new Rider("carol", new Location(9, 9));
        Trip t3 = svc.requestRide(carol, new Location(0, 0)); // maybe only 1 free driver left
        svc.startTrip(t2); svc.endTrip(t2);
    }
}

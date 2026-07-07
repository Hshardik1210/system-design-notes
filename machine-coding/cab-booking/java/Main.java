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

    // A point on the map. distanceTo() gives straight-line (Euclidean) distance,
    // used both for driver-to-rider matching and for the ride length used in pricing.
    static class Location {
        final double x, y;
        Location(double x, double y) { this.x = x; this.y = y; }
        double distanceTo(Location o) { return Math.hypot(x - o.x, y - o.y); }
    }

    // Lifecycle of a driver: only AVAILABLE drivers can be matched to a new ride.
    enum DriverStatus { AVAILABLE, ON_TRIP, OFFLINE }
    // A driver has an id, a current location, and a status. Location changes when
    // a trip ends (driver is left at the drop point).
    static class Driver {
        final String id; Location loc; DriverStatus status = DriverStatus.AVAILABLE;
        Driver(String id, Location loc) { this.id = id; this.loc = loc; }
    }
    // A rider who requests a trip; holds an id and their pickup location.
    static class Rider {
        final String id; Location loc;
        Rider(String id, Location loc) { this.id = id; this.loc = loc; }
    }

    // The States a trip moves through (see State pattern in README).
    enum TripState { REQUESTED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }
    // A single ride: who is riding, from/to locations, the matched driver,
    // the current state, and the computed fare.
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
    // Strategy interface: lets us swap out how a trip is priced without touching RideService.
    interface PricingStrategy { double price(double distanceKm, double surge); }
    // Default pricing formula: (base + perKm * distance) then multiplied by the surge factor.
    static class StandardPricing implements PricingStrategy {
        public double price(double distanceKm, double surge) {
            double base = 30, perKm = 12;
            return (base + perKm * distanceKm) * surge;
        }
    }

    // Core service: holds drivers, matches rides, prices them, and runs the trip state machine.
    static class RideService {
        private final List<Driver> drivers = new ArrayList<>();
        private final PricingStrategy pricing;
        private double surge = 1.0;
        private int tripSeq = 1;
        RideService(PricingStrategy pricing) { this.pricing = pricing; }

        void addDriver(Driver d) { drivers.add(d); }
        void setSurge(double s) { surge = s; } // raised when demand > supply

        // Create a trip and assign the nearest available driver, computing the fare.
        // If no driver is free the trip is immediately CANCELLED.
        Trip requestRide(Rider rider, Location to) {
            // Scan all drivers and keep the closest one that is AVAILABLE (nearest-driver matching).
            Driver best = null; double bestDist = Double.MAX_VALUE;
            for (Driver d : drivers) {
                if (d.status != DriverStatus.AVAILABLE) continue; // skip busy/offline drivers
                double dist = d.loc.distanceTo(rider.loc);
                if (dist < bestDist) { bestDist = dist; best = d; }
            }
            Trip trip = new Trip("TR" + tripSeq++, rider, rider.loc, to);
            if (best == null) {
                // No free driver -> the trip cannot proceed, so it is cancelled.
                trip.state = TripState.CANCELLED;
                System.out.println(trip.id + ": no drivers available -> CANCELLED");
                return trip;
            }
            best.status = DriverStatus.ON_TRIP; // reserve the driver so nobody else matches them
            trip.driver = best;
            trip.state = TripState.ASSIGNED;
            double tripKm = rider.loc.distanceTo(to); // ride length = pickup to drop distance
            trip.fare = pricing.price(tripKm, surge); // fare via the pricing strategy
            System.out.printf("%s: assigned driver %s (%.2f km away), fare Rs %.2f (surge %.1fx)%n",
                    trip.id, best.id, bestDist, trip.fare, surge);
            return trip;
        }

        // Move an ASSIGNED trip to IN_PROGRESS (guarded so states can't be skipped).
        void startTrip(Trip t) {
            if (t.state != TripState.ASSIGNED) return;
            t.state = TripState.IN_PROGRESS;
            System.out.println(t.id + ": IN_PROGRESS");
        }
        // Finish an IN_PROGRESS trip: mark it COMPLETED and free up the driver again.
        void endTrip(Trip t) {
            if (t.state != TripState.IN_PROGRESS) return;
            t.state = TripState.COMPLETED;
            t.driver.status = DriverStatus.AVAILABLE; // driver can take new rides
            t.driver.loc = t.to; // driver ends at drop location
            System.out.printf("%s: COMPLETED, collected Rs %.2f%n", t.id, t.fare);
        }
    }

    // Demo: set up drivers, run a normal trip, then apply 2x surge and request more rides.
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

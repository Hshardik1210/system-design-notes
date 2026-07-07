import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PARKING LOT — Low Level Design
 *
 * Requirements modelled:
 *  - Multiple floors, each with spots of different sizes (MOTORCYCLE, CAR, TRUCK).
 *  - A vehicle can park in a spot of equal-or-larger size (small vehicle fits big spot).
 *  - Issue a ticket on entry, compute fee on exit (pluggable pricing = Strategy pattern).
 *  - Thread-safe spot allocation so two cars never grab the same spot.
 *
 * Design patterns used:
 *  - Strategy  -> PricingStrategy (swap hourly / flat pricing without touching core).
 *  - Factory   -> VehicleFactory (create the right Vehicle subtype).
 *  - Singleton -> ParkingLot (one lot instance for the whole app).
 */
public class Main {

    // ---------- Enums ----------
    // The kinds of vehicles the lot accepts.
    enum VehicleType { MOTORCYCLE, CAR, TRUCK }
    // Spot size ordinal is used to decide "does vehicle fit": vehicleSize <= spotSize.
    // Order matters here: SMALL(0) < MEDIUM(1) < LARGE(2), and we compare these ordinals.
    enum SpotSize { SMALL, MEDIUM, LARGE }

    // ---------- Vehicle hierarchy (polymorphism) ----------
    // Base type for every vehicle; subclasses decide the spot size they need.
    static abstract class Vehicle {
        final String plate;
        final VehicleType type;
        Vehicle(String plate, VehicleType type) { this.plate = plate; this.type = type; }
        // Smallest spot size this vehicle needs. Each subclass answers differently (polymorphism).
        abstract SpotSize requiredSize();
    }
    // A motorcycle is the smallest vehicle, so it only needs a SMALL spot.
    static class Motorcycle extends Vehicle {
        Motorcycle(String p) { super(p, VehicleType.MOTORCYCLE); }
        SpotSize requiredSize() { return SpotSize.SMALL; }
    }
    // A car needs at least a MEDIUM spot.
    static class Car extends Vehicle {
        Car(String p) { super(p, VehicleType.CAR); }
        SpotSize requiredSize() { return SpotSize.MEDIUM; }
    }
    // A truck is the biggest vehicle, so it needs a LARGE spot.
    static class Truck extends Vehicle {
        Truck(String p) { super(p, VehicleType.TRUCK); }
        SpotSize requiredSize() { return SpotSize.LARGE; }
    }

    // Factory pattern: one place that builds the right Vehicle subtype so callers don't 'new' concrete classes.
    static class VehicleFactory {
        // Given a type + plate, return the matching Vehicle object.
        static Vehicle create(VehicleType type, String plate) {
            switch (type) {
                case MOTORCYCLE: return new Motorcycle(plate);
                case CAR:        return new Car(plate);
                case TRUCK:      return new Truck(plate);
                default: throw new IllegalArgumentException("unknown type");
            }
        }
    }

    // ---------- Parking spot ----------
    // A single physical parking space: it has a size and may hold one vehicle.
    static class ParkingSpot {
        final String id;
        final SpotSize size;
        private Vehicle vehicle; // null => free
        ParkingSpot(String id, SpotSize size) { this.id = id; this.size = size; }

        // A spot is free when no vehicle is currently assigned to it.
        boolean isFree() { return vehicle == null; }
        // A vehicle fits if the spot is at least as big as the vehicle needs.
        // Comparing ordinals: smaller ordinal = smaller size, so vehicle.size <= spot.size means "fits".
        boolean canFit(Vehicle v) { return v.requiredSize().ordinal() <= size.ordinal(); }
        // Park a vehicle here (marks the spot as taken).
        void assign(Vehicle v) { this.vehicle = v; }
        // Empty the spot so it can be reused.
        void release() { this.vehicle = null; }
    }

    // ---------- Floor: holds spots and finds a free one ----------
    // One level of the lot; owns a list of spots and knows how to pick a good one.
    static class ParkingFloor {
        final int number;
        final List<ParkingSpot> spots = new ArrayList<>();
        ParkingFloor(int number) { this.number = number; }
        void addSpot(ParkingSpot s) { spots.add(s); }

        // Return the smallest free spot that fits the vehicle (best-fit) or null.
        // Best-fit avoids wasting a LARGE spot on a bike when a SMALL one is available.
        ParkingSpot findSpot(Vehicle v) {
            ParkingSpot best = null;
            for (ParkingSpot s : spots) {
                if (s.isFree() && s.canFit(v)) {
                    // Keep the candidate only if it is smaller than the best found so far.
                    if (best == null || s.size.ordinal() < best.size.ordinal()) best = s;
                }
            }
            return best;
        }
    }

    // ---------- Ticket ----------
    // Proof of parking: remembers which vehicle is in which spot and the entry/exit times.
    static class Ticket {
        final String id;
        final Vehicle vehicle;
        final ParkingSpot spot;
        final long entryTimeMillis;
        long exitTimeMillis; // set on exit
        Ticket(String id, Vehicle v, ParkingSpot s, long entry) {
            this.id = id; this.vehicle = v; this.spot = s; this.entryTimeMillis = entry;
        }
    }

    // ---------- Strategy: pricing ----------
    // Strategy pattern: the "how to price" rule is behind an interface, so we can swap
    // hourly/flat/dynamic pricing without changing the ParkingLot.
    interface PricingStrategy {
        double calculate(Ticket t);
    }
    // One concrete strategy: charge per started hour, rate depends on vehicle type.
    static class HourlyPricing implements PricingStrategy {
        // Fee = (hours parked, rounded up, min 1) * per-hour rate for the vehicle type.
        public double calculate(Ticket t) {
            long ms = t.exitTimeMillis - t.entryTimeMillis;
            // Convert ms to hours, round UP so any part-hour counts as a full hour, and bill at least 1.
            long hours = Math.max(1, (long) Math.ceil(ms / 3600000.0)); // min 1 hour
            double rate;
            switch (t.vehicle.type) {
                case MOTORCYCLE: rate = 10; break;
                case CAR:        rate = 20; break;
                default:         rate = 40; break; // TRUCK
            }
            return hours * rate;
        }
    }

    // ---------- ParkingLot (Singleton, thread-safe) ----------
    // Singleton pattern: exactly one lot for the whole app, reached via getInstance().
    static class ParkingLot {
        // Created once when the class loads; INSTANCE is the single shared lot.
        private static final ParkingLot INSTANCE = new ParkingLot();
        static ParkingLot getInstance() { return INSTANCE; }

        private final List<ParkingFloor> floors = new ArrayList<>();
        // Ticket id -> ticket for vehicles currently parked; concurrent map is safe for many threads.
        private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
        private PricingStrategy pricing = new HourlyPricing(); // default strategy, swappable at runtime
        private final AtomicLong ticketSeq = new AtomicLong(1); // thread-safe counter for unique ticket ids
        private final Object lock = new Object(); // guards spot allocation

        // Private constructor: no one else can create a second ParkingLot (Singleton).
        private ParkingLot() {}

        void addFloor(ParkingFloor f) { floors.add(f); }
        // Strategy: swap pricing (hourly/flat/dynamic) without touching the lot logic.
        void setPricing(PricingStrategy p) { this.pricing = p; }

        // Park: find + reserve a spot atomically.
        // Returns a ticket if a spot was found, or null if the lot is full for this size.
        Ticket park(Vehicle v) {
            synchronized (lock) { // ensures no two threads grab the same spot
                // Try each floor in order; take the first floor that has a fitting spot.
                for (ParkingFloor f : floors) {
                    ParkingSpot spot = f.findSpot(v);
                    if (spot != null) {
                        spot.assign(v); // reserve the spot before releasing the lock
                        Ticket t = new Ticket("T" + ticketSeq.getAndIncrement(),
                                v, spot, System.currentTimeMillis());
                        activeTickets.put(t.id, t);
                        return t;
                    }
                }
            }
            return null; // lot full for this vehicle size
        }

        // Unpark: free the spot and compute the fee.
        double unpark(String ticketId) {
            Ticket t = activeTickets.remove(ticketId);
            // A missing ticket means it was never issued (or already used) -> reject.
            if (t == null) throw new NoSuchElementException("invalid ticket " + ticketId);
            t.exitTimeMillis = System.currentTimeMillis();
            synchronized (lock) { t.spot.release(); } // lock so the freed spot is visible to parkers
            return pricing.calculate(t); // Strategy decides the actual amount
        }
    }

    // ---------- Demo ----------
    // Wires everything together and prints a small end-to-end scenario.
    public static void main(String[] args) {
        ParkingLot lot = ParkingLot.getInstance();

        // Build a lot: 1 floor with mixed spots.
        ParkingFloor f1 = new ParkingFloor(1);
        f1.addSpot(new ParkingSpot("F1-S1", SpotSize.SMALL));
        f1.addSpot(new ParkingSpot("F1-M1", SpotSize.MEDIUM));
        f1.addSpot(new ParkingSpot("F1-L1", SpotSize.LARGE));
        lot.addFloor(f1);

        // Park a car and a bike.
        Vehicle car = VehicleFactory.create(VehicleType.CAR, "KA01-1234");
        Vehicle bike = VehicleFactory.create(VehicleType.MOTORCYCLE, "KA02-9999");

        Ticket carTicket = lot.park(car);
        Ticket bikeTicket = lot.park(bike);
        System.out.println("Car parked at   : " + carTicket.spot.id);
        System.out.println("Bike parked at  : " + bikeTicket.spot.id);

        // Truck needs LARGE; only F1-L1 fits.
        Vehicle truck = VehicleFactory.create(VehicleType.TRUCK, "KA03-0001");
        Ticket truckTicket = lot.park(truck);
        System.out.println("Truck parked at : " + truckTicket.spot.id);

        // Another truck -> no LARGE spot left -> null.
        Ticket noSpot = lot.park(VehicleFactory.create(VehicleType.TRUCK, "KA03-0002"));
        System.out.println("2nd truck parked: " + (noSpot == null ? "REJECTED (full)" : noSpot.spot.id));

        // Exit -> fee (min 1 hour billed).
        double fee = lot.unpark(carTicket.id);
        System.out.println("Car fee         : Rs " + fee);
    }
}

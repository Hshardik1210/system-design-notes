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
    enum VehicleType { MOTORCYCLE, CAR, TRUCK }
    // Spot size ordinal is used to decide "does vehicle fit": vehicleSize <= spotSize.
    enum SpotSize { SMALL, MEDIUM, LARGE }

    // ---------- Vehicle hierarchy (polymorphism) ----------
    static abstract class Vehicle {
        final String plate;
        final VehicleType type;
        Vehicle(String plate, VehicleType type) { this.plate = plate; this.type = type; }
        // Smallest spot size this vehicle needs.
        abstract SpotSize requiredSize();
    }
    static class Motorcycle extends Vehicle {
        Motorcycle(String p) { super(p, VehicleType.MOTORCYCLE); }
        SpotSize requiredSize() { return SpotSize.SMALL; }
    }
    static class Car extends Vehicle {
        Car(String p) { super(p, VehicleType.CAR); }
        SpotSize requiredSize() { return SpotSize.MEDIUM; }
    }
    static class Truck extends Vehicle {
        Truck(String p) { super(p, VehicleType.TRUCK); }
        SpotSize requiredSize() { return SpotSize.LARGE; }
    }

    // Factory: centralises object creation so callers don't 'new' concrete classes.
    static class VehicleFactory {
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
    static class ParkingSpot {
        final String id;
        final SpotSize size;
        private Vehicle vehicle; // null => free
        ParkingSpot(String id, SpotSize size) { this.id = id; this.size = size; }

        boolean isFree() { return vehicle == null; }
        // A vehicle fits if the spot is at least as big as the vehicle needs.
        boolean canFit(Vehicle v) { return v.requiredSize().ordinal() <= size.ordinal(); }
        void assign(Vehicle v) { this.vehicle = v; }
        void release() { this.vehicle = null; }
    }

    // ---------- Floor: holds spots and finds a free one ----------
    static class ParkingFloor {
        final int number;
        final List<ParkingSpot> spots = new ArrayList<>();
        ParkingFloor(int number) { this.number = number; }
        void addSpot(ParkingSpot s) { spots.add(s); }

        // Return the smallest free spot that fits the vehicle (best-fit) or null.
        ParkingSpot findSpot(Vehicle v) {
            ParkingSpot best = null;
            for (ParkingSpot s : spots) {
                if (s.isFree() && s.canFit(v)) {
                    if (best == null || s.size.ordinal() < best.size.ordinal()) best = s;
                }
            }
            return best;
        }
    }

    // ---------- Ticket ----------
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
    interface PricingStrategy {
        double calculate(Ticket t);
    }
    // Charge per started hour, rate depends on vehicle type.
    static class HourlyPricing implements PricingStrategy {
        public double calculate(Ticket t) {
            long ms = t.exitTimeMillis - t.entryTimeMillis;
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
    static class ParkingLot {
        private static final ParkingLot INSTANCE = new ParkingLot();
        static ParkingLot getInstance() { return INSTANCE; }

        private final List<ParkingFloor> floors = new ArrayList<>();
        private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
        private PricingStrategy pricing = new HourlyPricing();
        private final AtomicLong ticketSeq = new AtomicLong(1);
        private final Object lock = new Object(); // guards spot allocation

        private ParkingLot() {}

        void addFloor(ParkingFloor f) { floors.add(f); }
        void setPricing(PricingStrategy p) { this.pricing = p; }

        // Park: find + reserve a spot atomically.
        Ticket park(Vehicle v) {
            synchronized (lock) { // ensures no two threads grab the same spot
                for (ParkingFloor f : floors) {
                    ParkingSpot spot = f.findSpot(v);
                    if (spot != null) {
                        spot.assign(v);
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
            if (t == null) throw new NoSuchElementException("invalid ticket " + ticketId);
            t.exitTimeMillis = System.currentTimeMillis();
            synchronized (lock) { t.spot.release(); }
            return pricing.calculate(t);
        }
    }

    // ---------- Demo ----------
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

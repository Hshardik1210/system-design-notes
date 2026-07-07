import java.util.*;

/**
 * AMAZON LOCKER — assign a package to the smallest free locker that fits, issue
 * a one-time pickup code, and free the locker on pickup.
 *
 * "Fits" = package size <= locker size (a small package can use a bigger locker
 * if no exact size is free). We prefer the SMALLEST fitting locker (best-fit) to
 * keep large lockers available for large packages.
 *
 * Pattern: best-fit allocation; codes map to lockers for retrieval.
 */
// Main is the entry point and also holds all the small model classes for this demo.
public class Main {

    // The three locker/package sizes. Enum order (SMALL < MEDIUM < LARGE) is used
    // for size comparison via ordinal(), so declaration order matters here.
    enum Size { SMALL, MEDIUM, LARGE }

    // A package to be delivered: just an id and how big it is.
    static class Package {
        final String id; final Size size;
        Package(String id, Size size) { this.id = id; this.size = size; }
    }

    // A physical locker of a fixed size that can hold at most one package.
    static class Locker {
        final String id; final Size size; Package pkg; // pkg == null means the locker is empty/free
        Locker(String id, Size size) { this.id = id; this.size = size; }
        boolean isFree() { return pkg == null; }
        // A package fits if its size is <= the locker size. ordinal() turns the
        // enum into a number (SMALL=0, MEDIUM=1, LARGE=2) so we can compare sizes.
        boolean fits(Package p) { return p.size.ordinal() <= size.ordinal(); }
    }

    // Manages all lockers: assigns packages (best-fit), tracks pickup codes, frees lockers.
    static class LockerStation {
        private final List<Locker> lockers = new ArrayList<>();
        private final Map<String, String> codeToLocker = new HashMap<>(); // pickup code -> locker id
        private final Random rnd = new Random(1); // fixed seed => same codes every run (predictable demo)

        void addLocker(Locker l) { lockers.add(l); }

        // Assign best-fit locker; returns a pickup code or null if none available.
        String deposit(Package p) {
            // Scan every locker and keep the SMALLEST free one that still fits the
            // package (best-fit), so big lockers stay free for big packages.
            Locker best = null;
            for (Locker l : lockers)
                if (l.isFree() && l.fits(p))
                    if (best == null || l.size.ordinal() < best.size.ordinal()) best = l;
            if (best == null) { System.out.println("  ! no locker fits " + p.id + " (" + p.size + ")"); return null; }
            best.pkg = p; // place the package: locker is now occupied
            String code = String.format("%04d", rnd.nextInt(10000)); // 4-digit pickup code, zero-padded
            codeToLocker.put(code, best.id); // remember which locker this code opens
            System.out.printf("  %s -> locker %s (%s), code %s%n", p.id, best.id, best.size, code);
            return code;
        }

        // Retrieve by code; frees the locker.
        Package pickup(String code) {
            String lockerId = codeToLocker.remove(code); // look up + consume the code (one-time use)
            if (lockerId == null) { System.out.println("  ! invalid code " + code); return null; }
            for (Locker l : lockers) if (l.id.equals(lockerId)) {
                Package p = l.pkg; l.pkg = null; // hand back the package and mark the locker free
                System.out.printf("  picked up %s from locker %s%n", p.id, l.id);
                return p;
            }
            return null;
        }
    }

    // Small demo run showing deposit, best-fit selection, rejection, and pickup.
    public static void main(String[] args) {
        LockerStation station = new LockerStation();
        station.addLocker(new Locker("L1", Size.SMALL));
        station.addLocker(new Locker("L2", Size.MEDIUM));
        station.addLocker(new Locker("L3", Size.LARGE));

        // Small package should take the SMALL locker (best-fit), not the LARGE.
        String c1 = station.deposit(new Package("PKG-1", Size.SMALL));
        String c2 = station.deposit(new Package("PKG-2", Size.MEDIUM));
        String c3 = station.deposit(new Package("PKG-3", Size.SMALL)); // small locker taken -> uses LARGE (only fit left)
        station.deposit(new Package("PKG-4", Size.LARGE));             // no large free -> rejected

        station.pickup(c1);                     // frees L1 (SMALL)
        station.deposit(new Package("PKG-5", Size.SMALL)); // now fits L1 again
        station.pickup("9999");                 // invalid code
    }
}

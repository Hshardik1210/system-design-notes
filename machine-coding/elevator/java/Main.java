import java.util.*;

/**
 * ELEVATOR SYSTEM — single-car LOOK scheduling with a pluggable dispatcher.
 *
 * The elevator uses the LOOK algorithm (a practical variant of SCAN):
 *   keep moving in the current direction, stopping at every requested floor,
 *   until there are no more requests ahead; then reverse.
 *
 * We store pending stops in two sorted sets:
 *   - 'up'   : floors above/at current we still need to visit while going UP.
 *   - 'down' : floors below/at current we still need to visit while going DOWN.
 *
 * Patterns:
 *   - State    -> the elevator's Direction (IDLE/UP/DOWN) drives behaviour.
 *   - Strategy -> DispatchStrategy picks which elevator serves an external request
 *                 (here: nearest-car). Easy to swap for zoning / round-robin.
 */
public class Main {

    // The three movement states of an elevator. This enum IS the "State" pattern:
    // the elevator's behaviour in step() changes based on which Direction it is in.
    enum Direction { UP, DOWN, IDLE }

    // A single elevator car. It knows its current floor, its direction, and the
    // set of floors it still needs to visit. It implements the LOOK scheduling.
    static class Elevator {
        final int id;
        int currentFloor = 0;
        Direction direction = Direction.IDLE;
        // TreeSets keep stops sorted so we always pick the closest in a direction.
        // 'up' holds floors above current (we take the smallest = closest above).
        // 'down' holds floors below current (we take the largest = closest below).
        private final TreeSet<Integer> up = new TreeSet<>();
        private final TreeSet<Integer> down = new TreeSet<>();

        Elevator(int id) { this.id = id; }

        // Add a target floor (from inside the car or an assigned hall call).
        // We bucket the floor into 'up' or 'down' relative to where we are now,
        // then, if idle, pick a starting direction so the car begins moving.
        void addRequest(int floor) {
            if (floor > currentFloor) up.add(floor);        // needs to go up to reach it
            else if (floor < currentFloor) down.add(floor); // needs to go down to reach it
            // if == currentFloor, we're already there (open doors) — ignore for demo
            if (direction == Direction.IDLE)
                // Prefer UP if there is anything above; otherwise start heading DOWN.
                direction = up.isEmpty() ? Direction.DOWN : Direction.UP;
        }

        // True if this car still has any pending stops to serve.
        boolean hasWork() { return !up.isEmpty() || !down.isEmpty(); }

        // Perform one "step": move toward the next stop in the current direction.
        // This is the heart of the LOOK algorithm and the State pattern: what we do
        // depends entirely on the current 'direction'.
        void step() {
            if (!hasWork()) { direction = Direction.IDLE; return; }

            if (direction == Direction.UP) {
                Integer next = up.first();      // closest floor above
                currentFloor = next;
                up.pollFirst();                 // we have now served that stop, remove it
                System.out.printf("  E%d ▲ stop at %d%n", id, currentFloor);
                // LOOK reverse: nothing left above, so flip to DOWN (or go idle).
                if (up.isEmpty()) direction = down.isEmpty() ? Direction.IDLE : Direction.DOWN; // LOOK reverse
            } else if (direction == Direction.DOWN) {
                Integer next = down.last();     // closest floor below
                currentFloor = next;
                down.pollLast();                // served that stop, remove it
                System.out.printf("  E%d ▼ stop at %d%n", id, currentFloor);
                // LOOK reverse: nothing left below, so flip to UP (or go idle).
                if (down.isEmpty()) direction = up.isEmpty() ? Direction.IDLE : Direction.UP;
            }
        }

        // "Cost" for the dispatcher: simple distance heuristic (floors away).
        int distanceTo(int floor) { return Math.abs(currentFloor - floor); }
    }

    // ---------- Strategy: which elevator handles a hall call ----------
    // The Strategy pattern: this interface defines the "pick a car" policy as a
    // separate, swappable object so we can change the rule without touching Elevator.
    interface DispatchStrategy {
        Elevator select(List<Elevator> elevators, int floor, Direction wantDir);
    }
    // One concrete strategy: pick the elevator that is fewest floors away.
    // Nearest-car: pick the elevator with the smallest distance to the request.
    static class NearestCarDispatch implements DispatchStrategy {
        // Scan every elevator, compute its cost, and keep the cheapest one.
        public Elevator select(List<Elevator> elevators, int floor, Direction wantDir) {
            Elevator best = null; int bestCost = Integer.MAX_VALUE;
            for (Elevator e : elevators) {
                int cost = e.distanceTo(floor);
                // Small tie-break bonus if the car already moves toward the caller
                // (subtracting 1 makes an already-approaching car slightly preferred).
                if ((e.direction == Direction.UP && floor >= e.currentFloor) ||
                    (e.direction == Direction.DOWN && floor <= e.currentFloor)) cost -= 1;
                if (cost < bestCost) { bestCost = cost; best = e; }
            }
            return best;
        }
    }

    // The controller: owns all the elevators and the dispatch strategy, and is the
    // entry point for both hall calls (outside) and car calls (inside the car).
    static class ElevatorController {
        private final List<Elevator> elevators = new ArrayList<>();
        private final DispatchStrategy dispatch;
        // Build 'n' elevators (ids 1..n) and remember which dispatch policy to use.
        ElevatorController(int n, DispatchStrategy dispatch) {
            for (int i = 1; i <= n; i++) elevators.add(new Elevator(i));
            this.dispatch = dispatch;
        }

        // External (hall) call: someone on 'floor' wants to go 'dir'.
        // We ask the strategy which car should answer, then queue the stop on it.
        void requestElevator(int floor, Direction dir) {
            Elevator e = dispatch.select(elevators, floor, dir);
            System.out.printf("Hall call floor %d (%s) -> assigned E%d%n", floor, dir, e.id);
            e.addRequest(floor);
        }
        // Internal call: passenger in car 'id' presses 'floor' (ids are 1-based).
        void pressFloor(int id, int floor) { elevators.get(id - 1).addRequest(floor); }

        // Run until all elevators are idle.
        // Simple simulation loop: keep stepping every busy car; stop once a full
        // pass finds no car with remaining work.
        void run() {
            boolean working = true;
            while (working) {
                working = false;
                for (Elevator e : elevators) {
                    if (e.hasWork()) { e.step(); working = true; }
                }
            }
            System.out.println("All elevators idle.");
        }
    }

    // Demo driver: wires up 2 elevators with the nearest-car strategy and fires
    // a mix of hall calls and in-car presses to show the scheduling in action.
    public static void main(String[] args) {
        ElevatorController c = new ElevatorController(2, new NearestCarDispatch());

        // Hall calls (requests made from a floor, by someone waiting outside)
        c.requestElevator(5, Direction.UP);
        c.requestElevator(2, Direction.UP);
        c.requestElevator(8, Direction.DOWN);

        // Internal presses (passengers choosing destinations)
        c.pressFloor(1, 5);   // whoever is E1
        c.pressFloor(1, 3);
        c.pressFloor(2, 10);

        System.out.println("Running...");
        c.run();
    }
}

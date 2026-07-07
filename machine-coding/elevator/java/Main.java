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

    enum Direction { UP, DOWN, IDLE }

    static class Elevator {
        final int id;
        int currentFloor = 0;
        Direction direction = Direction.IDLE;
        // TreeSets keep stops sorted so we always pick the closest in a direction.
        private final TreeSet<Integer> up = new TreeSet<>();
        private final TreeSet<Integer> down = new TreeSet<>();

        Elevator(int id) { this.id = id; }

        // Add a target floor (from inside the car or an assigned hall call).
        void addRequest(int floor) {
            if (floor > currentFloor) up.add(floor);
            else if (floor < currentFloor) down.add(floor);
            // if == currentFloor, we're already there (open doors) — ignore for demo
            if (direction == Direction.IDLE)
                direction = up.isEmpty() ? Direction.DOWN : Direction.UP;
        }

        boolean hasWork() { return !up.isEmpty() || !down.isEmpty(); }

        // Perform one "step": move toward the next stop in the current direction.
        void step() {
            if (!hasWork()) { direction = Direction.IDLE; return; }

            if (direction == Direction.UP) {
                Integer next = up.first();      // closest floor above
                currentFloor = next;
                up.pollFirst();
                System.out.printf("  E%d ▲ stop at %d%n", id, currentFloor);
                if (up.isEmpty()) direction = down.isEmpty() ? Direction.IDLE : Direction.DOWN; // LOOK reverse
            } else if (direction == Direction.DOWN) {
                Integer next = down.last();     // closest floor below
                currentFloor = next;
                down.pollLast();
                System.out.printf("  E%d ▼ stop at %d%n", id, currentFloor);
                if (down.isEmpty()) direction = up.isEmpty() ? Direction.IDLE : Direction.UP;
            }
        }

        // "Cost" for the dispatcher: simple distance heuristic.
        int distanceTo(int floor) { return Math.abs(currentFloor - floor); }
    }

    // ---------- Strategy: which elevator handles a hall call ----------
    interface DispatchStrategy {
        Elevator select(List<Elevator> elevators, int floor, Direction wantDir);
    }
    // Nearest-car: pick the elevator with the smallest distance to the request.
    static class NearestCarDispatch implements DispatchStrategy {
        public Elevator select(List<Elevator> elevators, int floor, Direction wantDir) {
            Elevator best = null; int bestCost = Integer.MAX_VALUE;
            for (Elevator e : elevators) {
                int cost = e.distanceTo(floor);
                // Small tie-break bonus if the car already moves toward the caller.
                if ((e.direction == Direction.UP && floor >= e.currentFloor) ||
                    (e.direction == Direction.DOWN && floor <= e.currentFloor)) cost -= 1;
                if (cost < bestCost) { bestCost = cost; best = e; }
            }
            return best;
        }
    }

    static class ElevatorController {
        private final List<Elevator> elevators = new ArrayList<>();
        private final DispatchStrategy dispatch;
        ElevatorController(int n, DispatchStrategy dispatch) {
            for (int i = 1; i <= n; i++) elevators.add(new Elevator(i));
            this.dispatch = dispatch;
        }

        // External (hall) call: someone on 'floor' wants to go 'dir'.
        void requestElevator(int floor, Direction dir) {
            Elevator e = dispatch.select(elevators, floor, dir);
            System.out.printf("Hall call floor %d (%s) -> assigned E%d%n", floor, dir, e.id);
            e.addRequest(floor);
        }
        // Internal call: passenger in car 'id' presses 'floor'.
        void pressFloor(int id, int floor) { elevators.get(id - 1).addRequest(floor); }

        // Run until all elevators are idle.
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

    public static void main(String[] args) {
        ElevatorController c = new ElevatorController(2, new NearestCarDispatch());

        // Hall calls
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

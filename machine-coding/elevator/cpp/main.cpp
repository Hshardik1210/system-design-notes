// ELEVATOR SYSTEM — LOOK scheduling + pluggable dispatcher (C++17)
//
// LOOK algorithm: keep moving one direction, stopping at each requested floor,
// until nothing remains ahead, then reverse.
// 'up' / 'down' are sorted sets of pending stops so we always take the closest.
//
// Patterns: State (Direction drives behaviour), Strategy (DispatchStrategy).

#include <iostream>
#include <string>
#include <vector>
#include <set>
#include <memory>
#include <algorithm>
#include <climits>
#include <cstdio>
using namespace std;

// The three movement states of an elevator. This enum IS the "State" pattern:
// the behaviour inside step() depends on which Direction the car is in.
enum class Direction { UP, DOWN, IDLE };

// Small helper to turn a Direction into a printable string for logging.
static string dirStr(Direction d) {
    return d == Direction::UP ? "UP" : d == Direction::DOWN ? "DOWN" : "IDLE";
}

// A single elevator car: tracks its floor, direction, and the pending stops,
// and implements the LOOK scheduling in step().
class Elevator {
public:
    int id;
    int currentFloor = 0;
    Direction direction = Direction::IDLE;
private:
    // std::set keeps floors sorted so we can always grab the closest one.
    set<int> up;   // stops above current (ascending)
    set<int> down; // stops below current (ascending; we take the last)
public:
    explicit Elevator(int id) : id(id) {}

    // Queue a target floor (from an in-car press or an assigned hall call).
    // Bucket it into 'up' or 'down' relative to now; if idle, pick a start direction.
    void addRequest(int floor) {
        if (floor > currentFloor) up.insert(floor);        // must go up to reach it
        else if (floor < currentFloor) down.insert(floor); // must go down to reach it
        if (direction == Direction::IDLE)
            // Prefer UP if anything is above; otherwise begin heading DOWN.
            direction = up.empty() ? Direction::DOWN : Direction::UP;
    }

    // True if this car still has any pending stops.
    bool hasWork() const { return !up.empty() || !down.empty(); }

    // Perform one "step": move to the next stop in the current direction.
    // Heart of LOOK + State pattern: what we do depends on 'direction'.
    void step() {
        if (!hasWork()) { direction = Direction::IDLE; return; }
        if (direction == Direction::UP) {
            currentFloor = *up.begin();       // closest above
            up.erase(up.begin());             // served that stop, remove it
            printf("  E%d ^ stop at %d\n", id, currentFloor);
            // LOOK reverse: nothing left above, so flip to DOWN (or go idle).
            if (up.empty()) direction = down.empty() ? Direction::IDLE : Direction::DOWN;
        } else if (direction == Direction::DOWN) {
            auto it = prev(down.end());       // closest below (largest in the set)
            currentFloor = *it;
            down.erase(it);                   // served that stop, remove it
            printf("  E%d v stop at %d\n", id, currentFloor);
            // LOOK reverse: nothing left below, so flip to UP (or go idle).
            if (down.empty()) direction = up.empty() ? Direction::IDLE : Direction::UP;
        }
    }

    // "Cost" for the dispatcher: simple distance heuristic (floors away).
    int distanceTo(int floor) const { return abs(currentFloor - floor); }
};

// ---------- Strategy: dispatcher ----------
// Strategy pattern: an abstract "pick a car" policy so the assignment rule can be
// swapped (nearest / zoning / round-robin) without changing Elevator or controller.
struct DispatchStrategy {
    virtual ~DispatchStrategy() = default;
    virtual Elevator* select(vector<Elevator>& elevators, int floor, Direction wantDir) = 0;
};
// Concrete strategy: choose the elevator that is fewest floors away.
struct NearestCarDispatch : DispatchStrategy {
    // Scan all cars, compute each one's cost, and return the cheapest.
    Elevator* select(vector<Elevator>& elevators, int floor, Direction) override {
        Elevator* best = nullptr; int bestCost = INT_MAX;
        for (auto& e : elevators) {
            int cost = e.distanceTo(floor);
            // Tie-break bonus if the car is already heading toward the caller.
            if ((e.direction == Direction::UP && floor >= e.currentFloor) ||
                (e.direction == Direction::DOWN && floor <= e.currentFloor)) cost -= 1;
            if (cost < bestCost) { bestCost = cost; best = &e; }
        }
        return best;
    }
};

// The controller: owns the elevators and the dispatch strategy, and is the entry
// point for both hall calls (outside) and car calls (inside the car).
class ElevatorController {
    vector<Elevator> elevators;
    unique_ptr<DispatchStrategy> dispatch;
public:
    // Build 'n' elevators (ids 1..n) and take ownership of the dispatch policy.
    ElevatorController(int n, unique_ptr<DispatchStrategy> d) : dispatch(move(d)) {
        for (int i = 1; i <= n; i++) elevators.emplace_back(i);
    }

    // External (hall) call: ask the strategy which car answers, then queue the stop.
    void requestElevator(int floor, Direction dir) {
        Elevator* e = dispatch->select(elevators, floor, dir);
        printf("Hall call floor %d (%s) -> assigned E%d\n", floor, dirStr(dir).c_str(), e->id);
        e->addRequest(floor);
    }
    // Internal call: passenger in car 'id' presses 'floor' (ids are 1-based).
    void pressFloor(int id, int floor) { elevators[id - 1].addRequest(floor); }

    // Simulation loop: keep stepping every busy car until a full pass finds no work.
    void run() {
        bool working = true;
        while (working) {
            working = false;
            for (auto& e : elevators)
                if (e.hasWork()) { e.step(); working = true; }
        }
        cout << "All elevators idle.\n";
    }
};

// Demo driver: sets up 2 elevators with nearest-car dispatch and issues a mix of
// hall calls and in-car presses to show the scheduling at work.
int main() {
    ElevatorController c(2, make_unique<NearestCarDispatch>());

    // Hall calls (requests made from a floor by someone waiting outside)
    c.requestElevator(5, Direction::UP);
    c.requestElevator(2, Direction::UP);
    c.requestElevator(8, Direction::DOWN);

    // Internal presses (passengers choosing their destination floors)
    c.pressFloor(1, 5);
    c.pressFloor(1, 3);
    c.pressFloor(2, 10);

    cout << "Running...\n";
    c.run();
    return 0;
}

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

enum class Direction { UP, DOWN, IDLE };

static string dirStr(Direction d) {
    return d == Direction::UP ? "UP" : d == Direction::DOWN ? "DOWN" : "IDLE";
}

class Elevator {
public:
    int id;
    int currentFloor = 0;
    Direction direction = Direction::IDLE;
private:
    set<int> up;   // stops above current (ascending)
    set<int> down; // stops below current (ascending; we take the last)
public:
    explicit Elevator(int id) : id(id) {}

    void addRequest(int floor) {
        if (floor > currentFloor) up.insert(floor);
        else if (floor < currentFloor) down.insert(floor);
        if (direction == Direction::IDLE)
            direction = up.empty() ? Direction::DOWN : Direction::UP;
    }

    bool hasWork() const { return !up.empty() || !down.empty(); }

    void step() {
        if (!hasWork()) { direction = Direction::IDLE; return; }
        if (direction == Direction::UP) {
            currentFloor = *up.begin();       // closest above
            up.erase(up.begin());
            printf("  E%d ^ stop at %d\n", id, currentFloor);
            if (up.empty()) direction = down.empty() ? Direction::IDLE : Direction::DOWN;
        } else if (direction == Direction::DOWN) {
            auto it = prev(down.end());       // closest below
            currentFloor = *it;
            down.erase(it);
            printf("  E%d v stop at %d\n", id, currentFloor);
            if (down.empty()) direction = up.empty() ? Direction::IDLE : Direction::UP;
        }
    }

    int distanceTo(int floor) const { return abs(currentFloor - floor); }
};

// ---------- Strategy: dispatcher ----------
struct DispatchStrategy {
    virtual ~DispatchStrategy() = default;
    virtual Elevator* select(vector<Elevator>& elevators, int floor, Direction wantDir) = 0;
};
struct NearestCarDispatch : DispatchStrategy {
    Elevator* select(vector<Elevator>& elevators, int floor, Direction) override {
        Elevator* best = nullptr; int bestCost = INT_MAX;
        for (auto& e : elevators) {
            int cost = e.distanceTo(floor);
            if ((e.direction == Direction::UP && floor >= e.currentFloor) ||
                (e.direction == Direction::DOWN && floor <= e.currentFloor)) cost -= 1;
            if (cost < bestCost) { bestCost = cost; best = &e; }
        }
        return best;
    }
};

class ElevatorController {
    vector<Elevator> elevators;
    unique_ptr<DispatchStrategy> dispatch;
public:
    ElevatorController(int n, unique_ptr<DispatchStrategy> d) : dispatch(move(d)) {
        for (int i = 1; i <= n; i++) elevators.emplace_back(i);
    }

    void requestElevator(int floor, Direction dir) {
        Elevator* e = dispatch->select(elevators, floor, dir);
        printf("Hall call floor %d (%s) -> assigned E%d\n", floor, dirStr(dir).c_str(), e->id);
        e->addRequest(floor);
    }
    void pressFloor(int id, int floor) { elevators[id - 1].addRequest(floor); }

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

int main() {
    ElevatorController c(2, make_unique<NearestCarDispatch>());

    c.requestElevator(5, Direction::UP);
    c.requestElevator(2, Direction::UP);
    c.requestElevator(8, Direction::DOWN);

    c.pressFloor(1, 5);
    c.pressFloor(1, 3);
    c.pressFloor(2, 10);

    cout << "Running...\n";
    c.run();
    return 0;
}

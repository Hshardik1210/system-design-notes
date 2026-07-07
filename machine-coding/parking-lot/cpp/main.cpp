// PARKING LOT — Low Level Design (C++17)
//
// Mirrors the Java version:
//  - Multiple floors with spots of different sizes.
//  - Best-fit spot allocation (smallest spot that fits the vehicle).
//  - Ticket on entry, pluggable pricing (Strategy) on exit.
//  - std::mutex guards allocation so two cars never grab the same spot.
//
// Patterns: Strategy (pricing), Factory (vehicle creation), Singleton (ParkingLot).

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <stdexcept>
using namespace std;

// ---------- Enums ----------
enum class VehicleType { MOTORCYCLE, CAR, TRUCK };
enum class SpotSize { SMALL, MEDIUM, LARGE }; // ordering matters: fit if vehicle <= spot

// ---------- Vehicle hierarchy ----------
struct Vehicle {
    string plate;
    VehicleType type;
    Vehicle(string p, VehicleType t) : plate(move(p)), type(t) {}
    virtual ~Vehicle() = default;
    virtual SpotSize requiredSize() const = 0; // smallest spot this vehicle needs
};
struct Motorcycle : Vehicle {
    Motorcycle(string p) : Vehicle(move(p), VehicleType::MOTORCYCLE) {}
    SpotSize requiredSize() const override { return SpotSize::SMALL; }
};
struct Car : Vehicle {
    Car(string p) : Vehicle(move(p), VehicleType::CAR) {}
    SpotSize requiredSize() const override { return SpotSize::MEDIUM; }
};
struct Truck : Vehicle {
    Truck(string p) : Vehicle(move(p), VehicleType::TRUCK) {}
    SpotSize requiredSize() const override { return SpotSize::LARGE; }
};

// Factory: centralises creation, returns a shared_ptr to the base type.
struct VehicleFactory {
    static shared_ptr<Vehicle> create(VehicleType t, const string& plate) {
        switch (t) {
            case VehicleType::MOTORCYCLE: return make_shared<Motorcycle>(plate);
            case VehicleType::CAR:        return make_shared<Car>(plate);
            case VehicleType::TRUCK:      return make_shared<Truck>(plate);
        }
        throw invalid_argument("unknown type");
    }
};

// ---------- Parking spot ----------
struct ParkingSpot {
    string id;
    SpotSize size;
    shared_ptr<Vehicle> vehicle; // nullptr => free
    ParkingSpot(string i, SpotSize s) : id(move(i)), size(s) {}

    bool isFree() const { return vehicle == nullptr; }
    // Fits if spot is at least as big as the vehicle needs.
    bool canFit(const Vehicle& v) const {
        return static_cast<int>(v.requiredSize()) <= static_cast<int>(size);
    }
    void assign(shared_ptr<Vehicle> v) { vehicle = move(v); }
    void release() { vehicle = nullptr; }
};

// ---------- Floor ----------
struct ParkingFloor {
    int number;
    vector<shared_ptr<ParkingSpot>> spots;
    explicit ParkingFloor(int n) : number(n) {}
    void addSpot(shared_ptr<ParkingSpot> s) { spots.push_back(move(s)); }

    // Best-fit: smallest free spot that fits, or nullptr.
    shared_ptr<ParkingSpot> findSpot(const Vehicle& v) {
        shared_ptr<ParkingSpot> best = nullptr;
        for (auto& s : spots) {
            if (s->isFree() && s->canFit(v)) {
                if (!best || static_cast<int>(s->size) < static_cast<int>(best->size))
                    best = s;
            }
        }
        return best;
    }
};

// ---------- Ticket ----------
struct Ticket {
    string id;
    shared_ptr<Vehicle> vehicle;
    shared_ptr<ParkingSpot> spot;
    long long entryMillis;
    long long exitMillis = 0;
};

// ---------- Strategy: pricing ----------
struct PricingStrategy {
    virtual ~PricingStrategy() = default;
    virtual double calculate(const Ticket& t) const = 0;
};
struct HourlyPricing : PricingStrategy {
    double calculate(const Ticket& t) const override {
        long long ms = t.exitMillis - t.entryMillis;
        long long hours = max(1LL, (long long)ceil(ms / 3600000.0)); // min 1 hour
        double rate;
        switch (t.vehicle->type) {
            case VehicleType::MOTORCYCLE: rate = 10; break;
            case VehicleType::CAR:        rate = 20; break;
            default:                      rate = 40; break; // TRUCK
        }
        return hours * rate;
    }
};

static long long nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

// ---------- ParkingLot (Singleton, thread-safe) ----------
class ParkingLot {
public:
    static ParkingLot& getInstance() { static ParkingLot inst; return inst; }

    void addFloor(shared_ptr<ParkingFloor> f) { floors.push_back(move(f)); }
    void setPricing(shared_ptr<PricingStrategy> p) { pricing = move(p); }

    // Returns ticket ptr, or nullptr if full for this vehicle size.
    shared_ptr<Ticket> park(shared_ptr<Vehicle> v) {
        lock_guard<mutex> guard(mtx); // no two threads grab the same spot
        for (auto& f : floors) {
            auto spot = f->findSpot(*v);
            if (spot) {
                spot->assign(v);
                auto t = make_shared<Ticket>();
                t->id = "T" + to_string(seq++);
                t->vehicle = v;
                t->spot = spot;
                t->entryMillis = nowMillis();
                active[t->id] = t;
                return t;
            }
        }
        return nullptr;
    }

    double unpark(const string& ticketId) {
        lock_guard<mutex> guard(mtx);
        auto it = active.find(ticketId);
        if (it == active.end()) throw runtime_error("invalid ticket " + ticketId);
        auto t = it->second;
        t->exitMillis = nowMillis();
        t->spot->release();
        active.erase(it);
        return pricing->calculate(*t);
    }

private:
    ParkingLot() : pricing(make_shared<HourlyPricing>()) {}
    vector<shared_ptr<ParkingFloor>> floors;
    unordered_map<string, shared_ptr<Ticket>> active;
    shared_ptr<PricingStrategy> pricing;
    long long seq = 1;
    mutex mtx;
};

// ---------- Demo ----------
int main() {
    ParkingLot& lot = ParkingLot::getInstance();

    auto f1 = make_shared<ParkingFloor>(1);
    f1->addSpot(make_shared<ParkingSpot>("F1-S1", SpotSize::SMALL));
    f1->addSpot(make_shared<ParkingSpot>("F1-M1", SpotSize::MEDIUM));
    f1->addSpot(make_shared<ParkingSpot>("F1-L1", SpotSize::LARGE));
    lot.addFloor(f1);

    auto car  = VehicleFactory::create(VehicleType::CAR, "KA01-1234");
    auto bike = VehicleFactory::create(VehicleType::MOTORCYCLE, "KA02-9999");

    auto carTicket  = lot.park(car);
    auto bikeTicket = lot.park(bike);
    cout << "Car parked at   : " << carTicket->spot->id << "\n";
    cout << "Bike parked at  : " << bikeTicket->spot->id << "\n";

    auto truck = VehicleFactory::create(VehicleType::TRUCK, "KA03-0001");
    auto truckTicket = lot.park(truck);
    cout << "Truck parked at : " << truckTicket->spot->id << "\n";

    auto noSpot = lot.park(VehicleFactory::create(VehicleType::TRUCK, "KA03-0002"));
    cout << "2nd truck parked: " << (noSpot ? noSpot->spot->id : string("REJECTED (full)")) << "\n";

    double fee = lot.unpark(carTicket->id);
    cout << "Car fee         : Rs " << fee << "\n";
    return 0;
}

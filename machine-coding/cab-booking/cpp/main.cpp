// CAB BOOKING (Uber/Ola core) (C++17)
//
// Match rider to nearest AVAILABLE driver, price the trip (base + surge),
// drive it through REQUESTED -> ASSIGNED -> IN_PROGRESS -> COMPLETED.
// Patterns: Strategy (pricing), State (trip lifecycle).

#include <iostream>
#include <string>
#include <vector>
#include <limits>
#include <cmath>
#include <cstdio>
using namespace std;

struct Location {
    double x, y;
    double distanceTo(const Location& o) const { return hypot(x - o.x, y - o.y); }
};

enum class DriverStatus { AVAILABLE, ON_TRIP, OFFLINE };
struct Driver { string id; Location loc; DriverStatus status = DriverStatus::AVAILABLE; };
struct Rider { string id; Location loc; };

enum class TripState { REQUESTED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED };
struct Trip {
    string id; Rider rider; Driver* driver = nullptr;
    Location from, to;
    TripState state = TripState::REQUESTED;
    double fare = 0;
};

// ---------- Strategy: pricing ----------
struct PricingStrategy {
    virtual ~PricingStrategy() = default;
    virtual double price(double distanceKm, double surge) const = 0;
};
struct StandardPricing : PricingStrategy {
    double price(double distanceKm, double surge) const override {
        double base = 30, perKm = 12;
        return (base + perKm * distanceKm) * surge;
    }
};

class RideService {
    vector<Driver> drivers;
    const PricingStrategy& pricing;
    double surge = 1.0;
    int tripSeq = 1;
public:
    explicit RideService(const PricingStrategy& p) : pricing(p) {}
    void addDriver(const Driver& d) { drivers.push_back(d); }
    void setSurge(double s) { surge = s; }

    Trip requestRide(const Rider& rider, const Location& to) {
        Driver* best = nullptr; double bestDist = numeric_limits<double>::max();
        for (auto& d : drivers) {
            if (d.status != DriverStatus::AVAILABLE) continue;
            double dist = d.loc.distanceTo(rider.loc);
            if (dist < bestDist) { bestDist = dist; best = &d; }
        }
        Trip trip; trip.id = "TR" + to_string(tripSeq++); trip.rider = rider; trip.from = rider.loc; trip.to = to;
        if (!best) {
            trip.state = TripState::CANCELLED;
            cout << trip.id << ": no drivers available -> CANCELLED\n";
            return trip;
        }
        best->status = DriverStatus::ON_TRIP;
        trip.driver = best;
        trip.state = TripState::ASSIGNED;
        double tripKm = rider.loc.distanceTo(to);
        trip.fare = pricing.price(tripKm, surge);
        printf("%s: assigned driver %s (%.2f km away), fare Rs %.2f (surge %.1fx)\n",
               trip.id.c_str(), best->id.c_str(), bestDist, trip.fare, surge);
        return trip;
    }

    void startTrip(Trip& t) {
        if (t.state != TripState::ASSIGNED) return;
        t.state = TripState::IN_PROGRESS;
        cout << t.id << ": IN_PROGRESS\n";
    }
    void endTrip(Trip& t) {
        if (t.state != TripState::IN_PROGRESS) return;
        t.state = TripState::COMPLETED;
        t.driver->status = DriverStatus::AVAILABLE;
        t.driver->loc = t.to;
        printf("%s: COMPLETED, collected Rs %.2f\n", t.id.c_str(), t.fare);
    }
};

int main() {
    StandardPricing pricing;
    RideService svc(pricing);
    svc.addDriver({"D1", {0, 0}});
    svc.addDriver({"D2", {5, 5}});
    svc.addDriver({"D3", {1, 1}});

    Rider alice{"alice", {1, 0}};
    Trip t1 = svc.requestRide(alice, {10, 0});
    svc.startTrip(t1);
    svc.endTrip(t1);

    cout << "--- Surge 2x, two more requests ---\n";
    svc.setSurge(2.0);
    Rider bob{"bob", {4, 5}};
    Trip t2 = svc.requestRide(bob, {0, 0});
    Rider carol{"carol", {9, 9}};
    Trip t3 = svc.requestRide(carol, {0, 0});
    svc.startTrip(t2); svc.endTrip(t2);
    (void)t3;
    return 0;
}

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

// A point on the map. distanceTo() gives straight-line (Euclidean) distance,
// used both for driver-to-rider matching and for the ride length used in pricing.
struct Location {
    double x, y;
    double distanceTo(const Location& o) const { return hypot(x - o.x, y - o.y); }
};

// Lifecycle of a driver: only AVAILABLE drivers can be matched to a new ride.
enum class DriverStatus { AVAILABLE, ON_TRIP, OFFLINE };
// A driver: id, current location, and status (location moves to the drop point on trip end).
struct Driver { string id; Location loc; DriverStatus status = DriverStatus::AVAILABLE; };
// A rider who requests a trip; holds an id and their pickup location.
struct Rider { string id; Location loc; };

// The states a trip moves through (State pattern in README).
enum class TripState { REQUESTED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED };
// A single ride: rider, a pointer to the matched driver, from/to, state, and fare.
struct Trip {
    string id; Rider rider; Driver* driver = nullptr;
    Location from, to;
    TripState state = TripState::REQUESTED;
    double fare = 0;
};

// ---------- Strategy: pricing ----------
// Strategy interface: lets us swap out how a trip is priced without touching RideService.
struct PricingStrategy {
    virtual ~PricingStrategy() = default;
    virtual double price(double distanceKm, double surge) const = 0;
};
// Default pricing formula: (base + perKm * distance) then multiplied by the surge factor.
struct StandardPricing : PricingStrategy {
    double price(double distanceKm, double surge) const override {
        double base = 30, perKm = 12;
        return (base + perKm * distanceKm) * surge;
    }
};

// Core service: holds drivers, matches rides, prices them, and runs the trip state machine.
class RideService {
    vector<Driver> drivers;
    const PricingStrategy& pricing;
    double surge = 1.0;
    int tripSeq = 1;
public:
    explicit RideService(const PricingStrategy& p) : pricing(p) {}
    void addDriver(const Driver& d) { drivers.push_back(d); }
    void setSurge(double s) { surge = s; } // raised when demand > supply

    // Create a trip and assign the nearest available driver, computing the fare.
    // If no driver is free the trip is immediately CANCELLED.
    Trip requestRide(const Rider& rider, const Location& to) {
        // Scan all drivers and keep the closest one that is AVAILABLE (nearest-driver matching).
        Driver* best = nullptr; double bestDist = numeric_limits<double>::max();
        for (auto& d : drivers) {
            if (d.status != DriverStatus::AVAILABLE) continue; // skip busy/offline drivers
            double dist = d.loc.distanceTo(rider.loc);
            if (dist < bestDist) { bestDist = dist; best = &d; }
        }
        Trip trip; trip.id = "TR" + to_string(tripSeq++); trip.rider = rider; trip.from = rider.loc; trip.to = to;
        if (!best) {
            // No free driver -> the trip cannot proceed, so it is cancelled.
            trip.state = TripState::CANCELLED;
            cout << trip.id << ": no drivers available -> CANCELLED\n";
            return trip;
        }
        best->status = DriverStatus::ON_TRIP; // reserve the driver so nobody else matches them
        trip.driver = best;
        trip.state = TripState::ASSIGNED;
        double tripKm = rider.loc.distanceTo(to); // ride length = pickup to drop distance
        trip.fare = pricing.price(tripKm, surge); // fare via the pricing strategy
        printf("%s: assigned driver %s (%.2f km away), fare Rs %.2f (surge %.1fx)\n",
               trip.id.c_str(), best->id.c_str(), bestDist, trip.fare, surge);
        return trip;
    }

    // Move an ASSIGNED trip to IN_PROGRESS (guarded so states can't be skipped).
    void startTrip(Trip& t) {
        if (t.state != TripState::ASSIGNED) return;
        t.state = TripState::IN_PROGRESS;
        cout << t.id << ": IN_PROGRESS\n";
    }
    // Finish an IN_PROGRESS trip: mark it COMPLETED and free up the driver again.
    void endTrip(Trip& t) {
        if (t.state != TripState::IN_PROGRESS) return;
        t.state = TripState::COMPLETED;
        t.driver->status = DriverStatus::AVAILABLE; // driver can take new rides
        t.driver->loc = t.to; // driver ends at drop location
        printf("%s: COMPLETED, collected Rs %.2f\n", t.id.c_str(), t.fare);
    }
};

// Demo: set up drivers, run a normal trip, then apply 2x surge and request more rides.
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

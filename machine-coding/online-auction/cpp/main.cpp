// ONLINE AUCTION — bidding, lifecycle, outbid notifications (C++17)
//
// Bids must exceed the current highest by a minimum increment and are only
// accepted while OPEN. Bids are mutex-guarded so two concurrent bids can't both
// win against a stale highest. Observer notifies the previous leader on outbid.

#include <iostream>
#include <string>
#include <unordered_map>
#include <functional>
#include <mutex>
#include <algorithm>
#include <cstdio>
using namespace std;

// State pattern: the auction is either OPEN (accepting bids) or CLOSED.
enum class AuctionState { OPEN, CLOSED };

// Core domain object: holds the item, current highest bid, participants, and
// the rules for accepting bids and closing the auction.
class Auction {
    string itemId; long long startPrice, increment;
    AuctionState state = AuctionState::OPEN;
    string highestBidder; long long highestBid;
    // Each bidder's outbid callback (Observer), keyed by name.
    unordered_map<string, function<void(const string&, long long)>> participants;
    // Guards the read-then-write of highestBid so concurrent bids stay correct.
    mutex mtx;
public:
    // highestBid starts one increment below startPrice so the first valid bid
    // can equal exactly startPrice.
    Auction(string item, long long start, long long inc)
        : itemId(move(item)), startPrice(start), increment(inc), highestBid(start - inc) {}

    // Add a bidder and the callback used to notify them when they're outbid.
    void register_(const string& bidder, function<void(const string&, long long)> obs) {
        participants[bidder] = move(obs);
    }

    // Try to place a bid. Returns true if accepted. Checks the auction is open
    // and the amount is high enough, records the new highest, then notifies the
    // previous leader.
    bool bid(const string& bidder, long long amount) {
        // One critical section: check-then-update runs atomically so two
        // simultaneous bids can't both beat the same stale highest value.
        lock_guard<mutex> g(mtx);
        if (state != AuctionState::OPEN) { cout << "  ! auction closed\n"; return false; }
        // Minimum acceptable bid: at least the start price, and at least one
        // increment above the current highest.
        long long minRequired = max(startPrice, highestBid + increment);
        if (amount < minRequired) {
            printf("  ! bid %lld too low (need >= %lld)\n", amount, minRequired);
            return false;
        }
        // Remember who was leading before overwriting the highest bid.
        string previousLeader = highestBidder;
        highestBidder = bidder;
        highestBid = amount;
        printf("  %s bids %lld (new highest)\n", bidder.c_str(), amount);
        // Notify the outbid previous leader (Observer). Skip if there was no
        // prior leader or the same person re-bid.
        if (!previousLeader.empty() && previousLeader != bidder) {
            auto it = participants.find(previousLeader);
            if (it != participants.end()) it->second(itemId, amount);
        }
        return true;
    }

    // Close and declare the winner. After this, no more bids are accepted.
    string close() {
        lock_guard<mutex> g(mtx);
        state = AuctionState::CLOSED;
        // Empty bidder means nobody placed a valid bid: no sale.
        if (highestBidder.empty()) { cout << "  no sale (no bids)\n"; return ""; }
        printf("  SOLD %s to %s for %lld\n", itemId.c_str(), highestBidder.c_str(), highestBid);
        return highestBidder;
    }
};

// Demo run: set up an auction, register bidders, place a mix of valid and
// invalid bids, then close and confirm bids are rejected afterward.
int main() {
    Auction auction("painting", 1000, 100);

    auction.register_("Alice", [](const string& item, long long hi) { cout << "    -> Alice notified: outbid on " << item << " (now " << hi << ")\n"; });
    auction.register_("Bob",   [](const string& item, long long hi) { cout << "    -> Bob notified: outbid on " << item << " (now " << hi << ")\n"; });
    auction.register_("Carol", [](const string& item, long long hi) { cout << "    -> Carol notified: outbid on " << item << " (now " << hi << ")\n"; });

    auction.bid("Alice", 1000);
    auction.bid("Bob", 1050);   // too low
    auction.bid("Bob", 1100);
    auction.bid("Carol", 1300);
    auction.bid("Alice", 1350); // too low
    auction.bid("Alice", 1500);

    auction.close();
    auction.bid("Bob", 2000);   // closed
    return 0;
}

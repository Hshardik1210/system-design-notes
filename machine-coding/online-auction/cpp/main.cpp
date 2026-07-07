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

enum class AuctionState { OPEN, CLOSED };

class Auction {
    string itemId; long long startPrice, increment;
    AuctionState state = AuctionState::OPEN;
    string highestBidder; long long highestBid;
    unordered_map<string, function<void(const string&, long long)>> participants;
    mutex mtx;
public:
    Auction(string item, long long start, long long inc)
        : itemId(move(item)), startPrice(start), increment(inc), highestBid(start - inc) {}

    void register_(const string& bidder, function<void(const string&, long long)> obs) {
        participants[bidder] = move(obs);
    }

    bool bid(const string& bidder, long long amount) {
        lock_guard<mutex> g(mtx);
        if (state != AuctionState::OPEN) { cout << "  ! auction closed\n"; return false; }
        long long minRequired = max(startPrice, highestBid + increment);
        if (amount < minRequired) {
            printf("  ! bid %lld too low (need >= %lld)\n", amount, minRequired);
            return false;
        }
        string previousLeader = highestBidder;
        highestBidder = bidder;
        highestBid = amount;
        printf("  %s bids %lld (new highest)\n", bidder.c_str(), amount);
        if (!previousLeader.empty() && previousLeader != bidder) {
            auto it = participants.find(previousLeader);
            if (it != participants.end()) it->second(itemId, amount);
        }
        return true;
    }

    string close() {
        lock_guard<mutex> g(mtx);
        state = AuctionState::CLOSED;
        if (highestBidder.empty()) { cout << "  no sale (no bids)\n"; return ""; }
        printf("  SOLD %s to %s for %lld\n", itemId.c_str(), highestBidder.c_str(), highestBid);
        return highestBidder;
    }
};

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

import java.util.*;

/**
 * ONLINE AUCTION — bidding with a highest-bid rule, auction lifecycle, and
 * observer notifications to participants when they're outbid.
 *
 * Rules:
 *   - Each auction has a start price and a minimum increment.
 *   - A bid must exceed current highest by at least the increment.
 *   - Bidding is only allowed while the auction is OPEN (before it closes).
 *   - Closing determines the winner (highest bidder) or "no sale".
 *
 * Concurrency: bids are synchronized so two simultaneous bids can't both "win"
 * against a stale highest value.
 *
 * Pattern: Observer (bidders notified on outbid), State (auction lifecycle).
 */
public class Main {

    interface BidObserver { void onOutbid(String item, long newHighest); }

    enum AuctionState { OPEN, CLOSED }

    static class Auction {
        final String itemId; final long startPrice; final long increment;
        AuctionState state = AuctionState.OPEN;
        String highestBidder; long highestBid;
        // Track each participant so we can notify the previous leader on outbid.
        final Map<String, BidObserver> participants = new HashMap<>();
        private final Object lock = new Object();

        Auction(String itemId, long startPrice, long increment) {
            this.itemId = itemId; this.startPrice = startPrice; this.increment = increment;
            this.highestBid = startPrice - increment; // so first valid bid can equal startPrice
        }

        void register(String bidder, BidObserver obs) { participants.put(bidder, obs); }

        boolean bid(String bidder, long amount) {
            synchronized (lock) {
                if (state != AuctionState.OPEN) { System.out.println("  ! auction closed"); return false; }
                long minRequired = Math.max(startPrice, highestBid + increment);
                if (amount < minRequired) {
                    System.out.printf("  ! bid %d too low (need >= %d)%n", amount, minRequired);
                    return false;
                }
                String previousLeader = highestBidder;
                highestBidder = bidder;
                highestBid = amount;
                System.out.printf("  %s bids %d (new highest)%n", bidder, amount);
                // Notify the outbid previous leader (Observer).
                if (previousLeader != null && !previousLeader.equals(bidder)) {
                    BidObserver obs = participants.get(previousLeader);
                    if (obs != null) obs.onOutbid(itemId, amount);
                }
                return true;
            }
        }

        // Close and declare the winner.
        String close() {
            synchronized (lock) {
                state = AuctionState.CLOSED;
                if (highestBidder == null) { System.out.println("  no sale (no bids)"); return null; }
                System.out.printf("  SOLD %s to %s for %d%n", itemId, highestBidder, highestBid);
                return highestBidder;
            }
        }
    }

    public static void main(String[] args) {
        Auction auction = new Auction("painting", 1000, 100);

        // Observers just print when outbid.
        auction.register("Alice", (item, hi) -> System.out.println("    -> Alice notified: outbid on " + item + " (now " + hi + ")"));
        auction.register("Bob",   (item, hi) -> System.out.println("    -> Bob notified: outbid on " + item + " (now " + hi + ")"));
        auction.register("Carol", (item, hi) -> System.out.println("    -> Carol notified: outbid on " + item + " (now " + hi + ")"));

        auction.bid("Alice", 1000);  // ok (== start price)
        auction.bid("Bob", 1050);    // too low (need 1100)
        auction.bid("Bob", 1100);    // ok, Alice outbid
        auction.bid("Carol", 1300);  // ok, Bob outbid
        auction.bid("Alice", 1350);  // too low (need 1400)
        auction.bid("Alice", 1500);  // ok, Carol outbid

        auction.close();
        auction.bid("Bob", 2000);    // rejected: closed
    }
}

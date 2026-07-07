// CARD GAME / DECK OF CARDS (C++17)
//
// 52-card deck, Fisher-Yates shuffle, deal, and 5-card poker hand ranking.
//
// Key pieces:
//   - Suit / suitName        : the four suits and a helper to print them.
//   - rank stored as int 2..14, with rankName() to print (Ace = 14, high).
//   - Card                   : a value object = one (rank, suit) pair.
//   - Deck                   : holds all 52 cards; can shuffle and deal them.
//   - HandRank / handName    : poker categories (weakest -> strongest) + printing.
//   - evaluate()             : classifies 5 cards into one category.
//
// Idea: a Fisher-Yates shuffle gives a fair (unbiased) random ordering.

#include <iostream>
#include <string>
#include <vector>
#include <array>
#include <map>
#include <algorithm>
#include <random>
#include <stdexcept>
using namespace std;

// The four suits a card can belong to.
enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES };
// Turn a Suit into its printable name.
static const char* suitName(Suit s) {
    switch (s) { case Suit::CLUBS: return "CLUBS"; case Suit::DIAMONDS: return "DIAMONDS";
                 case Suit::HEARTS: return "HEARTS"; default: return "SPADES"; }
}
// Rank value 2..14 (ACE high). Map that number to a short display name.
static const char* rankName(int v) {
    static const char* names[] = {"","","2","3","4","5","6","7","8","9","10","J","Q","K","A"};
    return names[v]; // indices 0/1 unused so v==2 lands on "2"
}

// A single playing card: a rank number (2..14) plus its suit.
struct Card {
    int rank; Suit suit; // rank 2..14
    string str() const { return string(rankName(rank)) + " of " + suitName(suit); }
};

// Holds the 52 cards and tracks how many have been dealt so far.
class Deck {
    vector<Card> cards;
    size_t dealt = 0;
public:
    // Build the full deck: every suit (0..3) combined with every rank (2..14) => 52 cards.
    Deck() {
        for (int s = 0; s < 4; s++)
            for (int r = 2; r <= 14; r++)
                cards.push_back({r, static_cast<Suit>(s)});
    }
    // Fisher-Yates shuffle: gives a uniformly random (unbiased) ordering.
    // For each position from the end down to 1, swap it with a random earlier
    // (or equal) position, then reset the deal cursor.
    void shuffle(mt19937& rng) {
        for (size_t i = cards.size() - 1; i > 0; i--) {
            uniform_int_distribution<size_t> dist(0, i); // random index in [0, i]
            swap(cards[i], cards[dist(rng)]);
        }
        dealt = 0;
    }
    // Hand out the next card from the top; throw if the deck is empty.
    Card deal() { if (dealt >= cards.size()) throw runtime_error("deck empty"); return cards[dealt++]; }
    // Deal n cards in a row and return them as one hand.
    vector<Card> dealHand(int n) { vector<Card> h; for (int i = 0; i < n; i++) h.push_back(deal()); return h; }
};

// Poker hand categories, listed weakest -> strongest (enum order = ranking order).
enum class HandRank { HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_KIND, STRAIGHT_FLUSH };
// Turn a HandRank into its printable name.
static const char* handName(HandRank h) {
    switch (h) {
        case HandRank::HIGH_CARD: return "HIGH_CARD";
        case HandRank::ONE_PAIR: return "ONE_PAIR";
        case HandRank::TWO_PAIR: return "TWO_PAIR";
        case HandRank::THREE_KIND: return "THREE_KIND";
        case HandRank::STRAIGHT: return "STRAIGHT";
        case HandRank::FLUSH: return "FLUSH";
        case HandRank::FULL_HOUSE: return "FULL_HOUSE";
        case HandRank::FOUR_KIND: return "FOUR_KIND";
        default: return "STRAIGHT_FLUSH";
    }
}

// Classify a 5-card hand into a single poker category. Counts how many cards
// share each rank and each suit, then checks those counts (plus straight/flush
// flags) against the category rules, strongest first.
HandRank evaluate(const vector<Card>& hand) {
    array<int, 15> rankCount{};   // rankCount[v] = how many cards have rank v
    map<Suit, int> suitCount;     // how many cards of each suit
    vector<int> values;
    for (auto& c : hand) { rankCount[c.rank]++; suitCount[c.suit]++; values.push_back(c.rank); }
    bool flush = suitCount.size() == 1; // all five cards share one suit

    // Straight: sort values, then confirm each is exactly one more than the previous
    // (Ace-high only here for brevity).
    sort(values.begin(), values.end());
    bool straight = true;
    for (size_t i = 1; i < values.size(); i++)
        if (values[i] != values[i - 1] + 1) { straight = false; break; }

    // Tally how many ranks appear exactly twice / three / four times.
    int pairs = 0, trips = 0, quads = 0;
    for (int c : rankCount) { if (c == 2) pairs++; else if (c == 3) trips++; else if (c == 4) quads++; }

    // Check categories from strongest to weakest and return the first match.
    if (straight && flush) return HandRank::STRAIGHT_FLUSH;
    if (quads == 1)        return HandRank::FOUR_KIND;
    if (trips == 1 && pairs == 1) return HandRank::FULL_HOUSE; // three of a kind + a pair
    if (flush)             return HandRank::FLUSH;
    if (straight)          return HandRank::STRAIGHT;
    if (trips == 1)        return HandRank::THREE_KIND;
    if (pairs == 2)        return HandRank::TWO_PAIR;
    if (pairs == 1)        return HandRank::ONE_PAIR;
    return HandRank::HIGH_CARD; // nothing matched => ranked by highest card
}

// Build a readable "[card, card, ...]" string for a hand.
static string handStr(const vector<Card>& h) {
    string s = "[";
    for (size_t i = 0; i < h.size(); ++i) s += h[i].str() + (i + 1 < h.size() ? ", " : "");
    return s + "]";
}

// Demo: shuffle a deck, deal two hands, and evaluate two hand-picked hands.
int main() {
    Deck deck;
    mt19937 rng(7); // fixed seed => same shuffle every run (reproducible)
    deck.shuffle(rng);

    cout << "--- Two dealt 5-card hands ---\n";
    auto h1 = deck.dealHand(5);
    auto h2 = deck.dealHand(5);
    cout << "Hand 1: " << handStr(h1) << " => " << handName(evaluate(h1)) << "\n";
    cout << "Hand 2: " << handStr(h2) << " => " << handName(evaluate(h2)) << "\n";

    // Fixed hands so we can confirm the evaluator prints the expected category.
    cout << "--- Crafted hands ---\n";
    vector<Card> fullHouse = {{13, Suit::CLUBS}, {13, Suit::HEARTS}, {13, Suit::SPADES}, {2, Suit::CLUBS}, {2, Suit::DIAMONDS}};
    vector<Card> straightFlush = {{5, Suit::SPADES}, {6, Suit::SPADES}, {7, Suit::SPADES}, {8, Suit::SPADES}, {9, Suit::SPADES}};
    cout << "Full house    => " << handName(evaluate(fullHouse)) << "\n";
    cout << "Straight flush=> " << handName(evaluate(straightFlush)) << "\n";
    return 0;
}

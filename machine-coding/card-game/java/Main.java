import java.util.*;

/**
 * CARD GAME / DECK OF CARDS — build a 52-card deck, shuffle, deal, and rank a
 * 5-card poker hand.
 *
 * Focus: enums for Suit/Rank, Fisher-Yates shuffle, and a clean hand evaluator
 * that classifies a 5-card hand into the standard poker categories.
 */
public class Main {

    enum Suit { CLUBS, DIAMONDS, HEARTS, SPADES }
    enum Rank {
        TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), NINE(9),
        TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14);
        final int value;
        Rank(int v) { value = v; }
    }

    static class Card {
        final Rank rank; final Suit suit;
        Card(Rank rank, Suit suit) { this.rank = rank; this.suit = suit; }
        public String toString() { return rank + " of " + suit; }
    }

    static class Deck {
        private final List<Card> cards = new ArrayList<>();
        private int dealt = 0;
        Deck() { for (Suit s : Suit.values()) for (Rank r : Rank.values()) cards.add(new Card(r, s)); }

        // Fisher-Yates shuffle (uniformly random permutation).
        void shuffle(Random rnd) {
            for (int i = cards.size() - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                Collections.swap(cards, i, j);
            }
            dealt = 0;
        }
        Card deal() {
            if (dealt >= cards.size()) throw new NoSuchElementException("deck empty");
            return cards.get(dealt++);
        }
        List<Card> dealHand(int n) { List<Card> h = new ArrayList<>(); for (int i = 0; i < n; i++) h.add(deal()); return h; }
    }

    // Poker categories, ordered weakest -> strongest by ordinal.
    enum HandRank { HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_KIND, STRAIGHT_FLUSH }

    // Evaluate a 5-card hand into a category.
    static HandRank evaluate(List<Card> hand) {
        int[] rankCount = new int[15]; // index by Rank.value (2..14)
        Map<Suit, Integer> suitCount = new EnumMap<>(Suit.class);
        List<Integer> values = new ArrayList<>();
        for (Card c : hand) {
            rankCount[c.rank.value]++;
            suitCount.merge(c.suit, 1, Integer::sum);
            values.add(c.rank.value);
        }
        boolean flush = suitCount.size() == 1;

        // Straight: 5 distinct consecutive values (Ace-high only here for brevity).
        Collections.sort(values);
        boolean straight = true;
        for (int i = 1; i < values.size(); i++)
            if (values.get(i) != values.get(i - 1) + 1) { straight = false; break; }

        // Count of pairs/trips/quads.
        int pairs = 0, trips = 0, quads = 0;
        for (int c : rankCount) {
            if (c == 2) pairs++; else if (c == 3) trips++; else if (c == 4) quads++;
        }

        if (straight && flush) return HandRank.STRAIGHT_FLUSH;
        if (quads == 1)        return HandRank.FOUR_KIND;
        if (trips == 1 && pairs == 1) return HandRank.FULL_HOUSE;
        if (flush)             return HandRank.FLUSH;
        if (straight)          return HandRank.STRAIGHT;
        if (trips == 1)        return HandRank.THREE_KIND;
        if (pairs == 2)        return HandRank.TWO_PAIR;
        if (pairs == 1)        return HandRank.ONE_PAIR;
        return HandRank.HIGH_CARD;
    }

    public static void main(String[] args) {
        Deck deck = new Deck();
        deck.shuffle(new Random(7)); // fixed seed => reproducible

        System.out.println("--- Two dealt 5-card hands ---");
        List<Card> h1 = deck.dealHand(5);
        List<Card> h2 = deck.dealHand(5);
        System.out.println("Hand 1: " + h1 + " => " + evaluate(h1));
        System.out.println("Hand 2: " + h2 + " => " + evaluate(h2));

        System.out.println("--- Crafted hands ---");
        List<Card> fullHouse = Arrays.asList(
                new Card(Rank.KING, Suit.CLUBS), new Card(Rank.KING, Suit.HEARTS), new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.TWO, Suit.DIAMONDS));
        List<Card> straightFlush = Arrays.asList(
                new Card(Rank.FIVE, Suit.SPADES), new Card(Rank.SIX, Suit.SPADES), new Card(Rank.SEVEN, Suit.SPADES),
                new Card(Rank.EIGHT, Suit.SPADES), new Card(Rank.NINE, Suit.SPADES));
        System.out.println("Full house    => " + evaluate(fullHouse));
        System.out.println("Straight flush=> " + evaluate(straightFlush));
    }
}

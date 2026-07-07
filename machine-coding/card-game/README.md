# 🃏 Card Game / Deck of Cards

Build a 52-card deck, shuffle it fairly, deal hands, and rank a 5-card poker hand.

## Design
- **Enums** for `Suit` (4) and `Rank` (2–14, Ace high). A `Card` is a (rank, suit).
- **Deck** — all 52 combinations; deals sequentially from a cursor.
- **Fisher–Yates shuffle** — the correct O(n) uniform shuffle: for `i` from `n−1` down to `1`, swap `i` with a random `j ∈ [0, i]`. (Naive "swap each with any random" is biased.)
- **Hand evaluator** — counts ranks/suits to classify into the standard categories:
  `STRAIGHT_FLUSH > FOUR_KIND > FULL_HOUSE > FLUSH > STRAIGHT > THREE_KIND > TWO_PAIR > ONE_PAIR > HIGH_CARD`.

> For brevity, straights are Ace-high only (no wheel A-2-3-4-5) and we classify the category, not tie-break kickers.

## Design patterns
- Value objects (Card) + enums; algorithmic evaluation.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```

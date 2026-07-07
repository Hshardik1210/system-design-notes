# ⭕ Tic-Tac-Toe (N×N)

Two players alternate placing marks on an **N×N** board; first to fill a full row, column, or diagonal wins.

## Key trick: O(1) win detection
Naively you'd re-scan the board (O(N²)) after every move. Instead, keep **running counters**:
- one per row, one per column, plus the main diagonal and anti-diagonal.
- Player **X adds +1**, player **O adds −1** to the counters touched by their move.
- If any counter's absolute value hits **N**, that whole line is owned by one player ⇒ win.

This makes each move **O(1)** and trivially generalises from 3×3 to any N.

## Design
- `Board` — grid + counters + `place()` returning whether the move won.
- `Player` — name + mark.
- `Game` — alternates turns, prints the board, reports winner/draw.

Extensible: change `n` for a bigger board; add more players/marks by extending the counter logic.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```

// TIC-TAC-TOE — generalised N x N board (C++17)
//
// What this program does: two players alternately place marks on an N x N grid;
// the first to fill a full row, column, or diagonal wins.
//
// O(1) win check per move using running counters per row/col/diagonal:
//   X adds +1, O adds -1. If any line's |counter| == N, that line is won.
//
// Key classes:
//   - Board:  the N x N grid plus the running counters; knows how to place a mark.
//   - Player: a simple struct holding a player's name and mark.
//   - Game:   drives play, alternates turns, prints the board, reports the result.
//
// Design ideas: plain object-oriented composition (Game holds a Board and Players)
// plus the O(1) running-counter trick described above.

#include <iostream>
#include <string>
#include <vector>
#include <cstdlib>
#include <stdexcept>
using namespace std;

// The three possible states of a cell: empty, or owned by player X or player O.
enum class Mark { EMPTY, X, O };

// Convert a Mark into the character shown on the board ("." for an empty cell).
static string markStr(Mark m) {
    switch (m) { case Mark::X: return "X"; case Mark::O: return "O"; default: return "."; }
}

// Board: stores the grid and the running counters, and decides if a move wins.
class Board {
    int n;                          // board size (n rows and n columns)
    vector<vector<Mark>> grid;      // the actual n x n cells
    int movesPlayed = 0;            // how many marks have been placed so far
    vector<int> rows, cols;         // running counters, one per row and per column
    int diag = 0, antiDiag = 0;     // counters for the two diagonals
public:
    // Create an empty n x n board with all counters initialised to zero.
    explicit Board(int n) : n(n), grid(n, vector<Mark>(n, Mark::EMPTY)), rows(n, 0), cols(n, 0) {}

    // The board is full once every cell has a mark (used to detect a draw).
    bool isFull() const { return movesPlayed == n * n; }

    // Place a mark at (r, c); returns true if this move completes a line and wins.
    bool place(int r, int c, Mark m) {
        if (r < 0 || r >= n || c < 0 || c >= n) throw invalid_argument("out of bounds");
        if (grid[r][c] != Mark::EMPTY) throw invalid_argument("cell occupied");
        grid[r][c] = m;
        movesPlayed++;

        // X contributes +1 and O contributes -1 to every line this cell sits on.
        int delta = (m == Mark::X) ? 1 : -1;
        rows[r] += delta;
        cols[c] += delta;
        if (r == c) diag += delta;              // main diagonal (top-left to bottom-right)
        if (r + c == n - 1) antiDiag += delta;  // anti-diagonal (top-right to bottom-left)

        // |counter| == n means one player fully owns that line, so this move wins.
        return abs(rows[r]) == n || abs(cols[c]) == n || abs(diag) == n || abs(antiDiag) == n;
    }

    // Print the board to the console, e.g. "X | O | ." for one row.
    void print() const {
        for (int i = 0; i < n; i++) {
            string line = " ";
            for (int j = 0; j < n; j++) {
                line += markStr(grid[i][j]);
                if (j < n - 1) line += " | ";
            }
            cout << line << "\n";
        }
        cout << "\n";
    }
};

// Player: just pairs a display name with the mark that player uses.
struct Player {
    string name;
    Mark mark;
};

// Game: owns the board and the two players and runs one move at a time.
class Game {
    Board board;
    Player players[2];
    int turn = 0;               // index (0 or 1) of the player whose turn it is
public:
    Game(int n, Player p1, Player p2) : board(n) { players[0] = p1; players[1] = p2; }

    // Play one move for the current player. Returns a pointer to the winner if this
    // move ended the game, otherwise switches the turn and returns nullptr.
    Player* move(int r, int c) {
        Player& cur = players[turn];
        bool won = board.place(r, c, cur.mark);
        board.print();
        if (won) return &players[turn];
        turn = 1 - turn;        // flip between index 0 and 1 to swap players
        return nullptr;
    }
    // A draw happens when the board fills up with no winner.
    bool isDraw() const { return board.isFull(); }
};

// Entry point: sets up a 3x3 game and replays a fixed sequence of moves.
int main() {
    Game g(3, {"Alice", Mark::X}, {"Bob", Mark::O});
    int moves[][2] = {{0,0},{1,0},{0,1},{1,1},{0,2}};

    Player* winner = nullptr;
    for (auto& mv : moves) {
        winner = g.move(mv[0], mv[1]);
        if (winner) break;      // stop as soon as someone wins
    }

    if (winner) cout << "Winner: " << winner->name << " (" << markStr(winner->mark) << ")\n";
    else if (g.isDraw()) cout << "Draw!\n";
    return 0;
}

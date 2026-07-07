// TIC-TAC-TOE — generalised N x N board (C++17)
//
// O(1) win check per move using running counters per row/col/diagonal:
//   X adds +1, O adds -1. If any line's |counter| == N, that line is won.

#include <iostream>
#include <string>
#include <vector>
#include <cstdlib>
#include <stdexcept>
using namespace std;

enum class Mark { EMPTY, X, O };

static string markStr(Mark m) {
    switch (m) { case Mark::X: return "X"; case Mark::O: return "O"; default: return "."; }
}

class Board {
    int n;
    vector<vector<Mark>> grid;
    int movesPlayed = 0;
    vector<int> rows, cols; // running counters
    int diag = 0, antiDiag = 0;
public:
    explicit Board(int n) : n(n), grid(n, vector<Mark>(n, Mark::EMPTY)), rows(n, 0), cols(n, 0) {}

    bool isFull() const { return movesPlayed == n * n; }

    // Returns true if this move wins.
    bool place(int r, int c, Mark m) {
        if (r < 0 || r >= n || c < 0 || c >= n) throw invalid_argument("out of bounds");
        if (grid[r][c] != Mark::EMPTY) throw invalid_argument("cell occupied");
        grid[r][c] = m;
        movesPlayed++;

        int delta = (m == Mark::X) ? 1 : -1;
        rows[r] += delta;
        cols[c] += delta;
        if (r == c) diag += delta;
        if (r + c == n - 1) antiDiag += delta;

        return abs(rows[r]) == n || abs(cols[c]) == n || abs(diag) == n || abs(antiDiag) == n;
    }

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

struct Player {
    string name;
    Mark mark;
};

class Game {
    Board board;
    Player players[2];
    int turn = 0;
public:
    Game(int n, Player p1, Player p2) : board(n) { players[0] = p1; players[1] = p2; }

    // Returns pointer to winner, or nullptr if game continues.
    Player* move(int r, int c) {
        Player& cur = players[turn];
        bool won = board.place(r, c, cur.mark);
        board.print();
        if (won) return &players[turn];
        turn = 1 - turn;
        return nullptr;
    }
    bool isDraw() const { return board.isFull(); }
};

int main() {
    Game g(3, {"Alice", Mark::X}, {"Bob", Mark::O});
    int moves[][2] = {{0,0},{1,0},{0,1},{1,1},{0,2}};

    Player* winner = nullptr;
    for (auto& mv : moves) {
        winner = g.move(mv[0], mv[1]);
        if (winner) break;
    }

    if (winner) cout << "Winner: " << winner->name << " (" << markStr(winner->mark) << ")\n";
    else if (g.isDraw()) cout << "Draw!\n";
    return 0;
}

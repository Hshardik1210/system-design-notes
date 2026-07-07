// SNAKE & LADDER (C++17) — a small, self-contained model of the classic board game.
//
// WHAT THIS PROGRAM DOES:
//   Builds a 100-cell board with a few snakes and ladders, then lets two players
//   ("Alice" and "Bob") take turns rolling a die until one lands exactly on the
//   last cell. Every move is printed so the whole game is easy to follow.
//
// KEY TYPES (each is a small building block):
//   - Jump   : one snake OR one ladder (a teleport from a start cell to an end cell).
//   - Die    : produces a random dice roll (1..faces).
//   - Board  : the grid of cells plus the map of jumps, and the rule to apply them.
//   - Player : a competitor and their current position.
//   - Game   : owns the turn order and runs the roll-loop until someone wins.
//
// DESIGN IDEAS USED (mirrors the README):
//   - Unified 'Jump' abstraction: a snake and a ladder are both a jump from start->end.
//       ladder: end > start,  snake: end < start.  One type models both, so there is
//       no duplicated snake/ladder logic — the board just asks "is there a jump here?".
//   - Pluggable Die: the die is its own class, so it can be swapped (loaded/multi-dice)
//       without touching the game logic.
//   - Round-robin turns via a queue; you must land EXACTLY on the last cell to win
//       (overshoot => stay put and pass the turn on).

#include <iostream>
#include <string>
#include <unordered_map>
#include <queue>
#include <vector>
#include <memory>
#include <random>
using namespace std;

// Jump = one snake or one ladder: both are just a teleport from 'start' to 'end'.
struct Jump {
    int start, end;
    // end above start means you go UP the board, so it's a ladder (else a snake).
    bool isLadder() const { return end > start; }
};

// Die = the source of randomness for a roll; kept separate so it is easy to replace.
class Die {
    mt19937 rng;                        // Mersenne Twister random-number engine
    uniform_int_distribution<int> dist; // maps engine output to a fair 1..faces range
public:
    explicit Die(int faces) : rng(42), dist(1, faces) {} // fixed seed 42 => same game every run (reproducible)
    int roll() { return dist(rng); }
};

// Board = the playing surface: how many cells it has and where the jumps are.
class Board {
    int size_;
    unordered_map<int, Jump> jumps; // startCell -> Jump (fast lookup by landing cell)
public:
    explicit Board(int size) : size_(size) {}
    int size() const { return size_; }
    // Register a snake/ladder, keyed by its start cell so resolve() can find it.
    void addJump(const Jump& j) { jumps[j.start] = j; }
    // Given the landed-on cell, return where the player actually ends up:
    // if a jump starts here, teleport to its end; otherwise stay on the same cell.
    int resolve(int cell) const {
        auto it = jumps.find(cell);
        return it == jumps.end() ? cell : it->second.end;
    }
};

// Player = a competitor and the cell they are currently standing on.
struct Player {
    string name;
    int position = 0; // 0 = off-board / start (before the first cell)
};

// Game = the referee: it holds the turn order and runs the game until a winner.
class Game {
    Board board;
    Die die;
    queue<Player*> turnOrder;              // round-robin turns (front = plays next)
    vector<unique_ptr<Player>> players;    // owns the Player objects (queue only holds pointers)
public:
    // Create a Player per name, keep ownership in 'players', and line them up to play.
    Game(Board b, Die d, vector<string> names) : board(move(b)), die(move(d)) {
        for (auto& n : names) {
            players.push_back(make_unique<Player>());
            players.back()->name = n;
            turnOrder.push(players.back().get()); // queue points at the owned object
        }
    }

    // Run the game loop until someone lands exactly on the last cell, then return
    // that winner. Each iteration of the loop is one player's single turn.
    Player* play() {
        while (true) {
            Player* p = turnOrder.front(); turnOrder.pop(); // take whoever is up next
            int roll = die.roll();
            int target = p->position + roll; // where the roll would land, before jumps

            if (target > board.size()) { // overshoot the last cell => turn wasted, stay put
                cout << p->name << " rolled " << roll << " but overshoots; stays at " << p->position << "\n";
                turnOrder.push(p); // send to back of the queue for their next turn
                continue;
            }

            int afterJump = board.resolve(target); // apply a snake/ladder if one starts on 'target'
            if (afterJump != target) {
                // Position changed, so a jump fired: up = ladder, down = snake.
                string kind = afterJump > target ? "climbs a LADDER" : "hit a SNAKE";
                cout << p->name << " rolled " << roll << " -> " << target << ", " << kind << " -> " << afterJump << "\n";
            } else {
                cout << p->name << " rolled " << roll << " -> " << target << "\n";
            }
            p->position = afterJump; // commit the new position

            if (p->position == board.size()) return p; // exact landing on the last cell wins
            turnOrder.push(p); // otherwise requeue this player for the next round
        }
    }
};

// Entry point: build the board, add snakes/ladders, then play a 2-player game.
int main() {
    Board board(100);
    // Ladders (end > start): landing on the start cell sends you UP.
    board.addJump({2, 38});
    board.addJump({7, 14});
    board.addJump({28, 76});
    // Snakes (end < start): landing on the start cell sends you DOWN.
    board.addJump({47, 26});
    board.addJump({62, 18});
    board.addJump({99, 41});

    // Two players share one 6-sided die; play() runs until one of them wins.
    Game game(board, Die(6), {"Alice", "Bob"});
    Player* winner = game.play();
    cout << "\nWinner: " << winner->name << "\n";
    return 0;
}

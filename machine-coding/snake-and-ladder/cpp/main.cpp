// SNAKE & LADDER (C++17)
//
// Unified 'Jump' abstraction: a snake and a ladder are both a jump from start->end.
//   ladder: end > start,  snake: end < start.
// Turns are round-robin; you must land EXACTLY on the last cell to win (overshoot => stay).

#include <iostream>
#include <string>
#include <unordered_map>
#include <queue>
#include <vector>
#include <memory>
#include <random>
using namespace std;

struct Jump {
    int start, end;
    bool isLadder() const { return end > start; }
};

class Die {
    mt19937 rng;
    uniform_int_distribution<int> dist;
public:
    explicit Die(int faces) : rng(42), dist(1, faces) {} // fixed seed => reproducible
    int roll() { return dist(rng); }
};

class Board {
    int size_;
    unordered_map<int, Jump> jumps; // startCell -> Jump
public:
    explicit Board(int size) : size_(size) {}
    int size() const { return size_; }
    void addJump(const Jump& j) { jumps[j.start] = j; }
    int resolve(int cell) const {
        auto it = jumps.find(cell);
        return it == jumps.end() ? cell : it->second.end;
    }
};

struct Player {
    string name;
    int position = 0;
};

class Game {
    Board board;
    Die die;
    queue<Player*> turnOrder;
    vector<unique_ptr<Player>> players;
public:
    Game(Board b, Die d, vector<string> names) : board(move(b)), die(move(d)) {
        for (auto& n : names) {
            players.push_back(make_unique<Player>());
            players.back()->name = n;
            turnOrder.push(players.back().get());
        }
    }

    Player* play() {
        while (true) {
            Player* p = turnOrder.front(); turnOrder.pop();
            int roll = die.roll();
            int target = p->position + roll;

            if (target > board.size()) { // overshoot => stay
                cout << p->name << " rolled " << roll << " but overshoots; stays at " << p->position << "\n";
                turnOrder.push(p);
                continue;
            }

            int afterJump = board.resolve(target);
            if (afterJump != target) {
                string kind = afterJump > target ? "climbs a LADDER" : "hit a SNAKE";
                cout << p->name << " rolled " << roll << " -> " << target << ", " << kind << " -> " << afterJump << "\n";
            } else {
                cout << p->name << " rolled " << roll << " -> " << target << "\n";
            }
            p->position = afterJump;

            if (p->position == board.size()) return p;
            turnOrder.push(p);
        }
    }
};

int main() {
    Board board(100);
    board.addJump({2, 38});
    board.addJump({7, 14});
    board.addJump({28, 76});
    board.addJump({47, 26});
    board.addJump({62, 18});
    board.addJump({99, 41});

    Game game(board, Die(6), {"Alice", "Bob"});
    Player* winner = game.play();
    cout << "\nWinner: " << winner->name << "\n";
    return 0;
}

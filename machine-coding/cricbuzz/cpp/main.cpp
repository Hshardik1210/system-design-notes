// CRICBUZZ / LIVE SCOREBOARD — ball-by-ball scoring + observer updates (C++17)
//
// Rules: runs off the bat are legal balls; WIDE/NO_BALL add 1 and are NOT legal
// balls; WICKET is a legal ball. 6 legal balls = 1 over. Observers are notified
// after each ball.

#include <iostream>
#include <string>
#include <vector>
#include <functional>
using namespace std;

enum class BallType { RUN, WIDE, NO_BALL, WICKET };

struct Ball {
    BallType type; int runs;
    static Ball makeRuns(int r) { return {BallType::RUN, r}; }
    static Ball wide()   { return {BallType::WIDE, 0}; }
    static Ball noBall() { return {BallType::NO_BALL, 0}; }
    static Ball wicket() { return {BallType::WICKET, 0}; }
};

struct Innings {
    string battingTeam;
    int runs = 0, wickets = 0, legalBalls = 0;
    vector<function<void(const Innings&)>> observers;

    explicit Innings(string team) : battingTeam(move(team)) {}
    void subscribe(function<void(const Innings&)> o) { observers.push_back(move(o)); }

    void applyBall(const Ball& ball) {
        switch (ball.type) {
            case BallType::RUN:     runs += ball.runs; legalBalls++; break;
            case BallType::WIDE:    runs += 1; break;
            case BallType::NO_BALL: runs += 1; break;
            case BallType::WICKET:  wickets++; legalBalls++; break;
        }
        for (auto& o : observers) o(*this);
    }

    string overs() const { return to_string(legalBalls / 6) + "." + to_string(legalBalls % 6); }
    string summary() const { return battingTeam + " " + to_string(runs) + "/" + to_string(wickets) + " (" + overs() + " ov)"; }
};

int main() {
    Innings innings("India");

    innings.subscribe([](const Innings& i) { cout << "  [scoreboard] " << i.summary() << "\n"; });
    innings.subscribe([](const Innings& i) {
        if (i.wickets > 0 && i.legalBalls % 6 == 0)
            cout << "  [alert] end of over " << (i.legalBalls / 6) << "\n";
    });

    vector<Ball> over = {
        Ball::makeRuns(4), Ball::wide(), Ball::makeRuns(6), Ball::wicket(),
        Ball::makeRuns(1), Ball::noBall(), Ball::makeRuns(2), Ball::makeRuns(0)
    };
    for (auto& b : over) innings.applyBall(b);

    cout << "Final: " << innings.summary() << "\n";
    return 0;
}

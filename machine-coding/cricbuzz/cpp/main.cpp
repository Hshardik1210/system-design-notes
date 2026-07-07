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

// The four possible outcomes of a single delivery. Used to decide scoring.
enum class BallType { RUN, WIDE, NO_BALL, WICKET };

// Value object describing one delivery: its type plus runs scored.
// The static factory helpers make test data read like real cricket,
// e.g. Ball::makeRuns(4) or Ball::wicket().
struct Ball {
    BallType type; int runs;
    static Ball makeRuns(int r) { return {BallType::RUN, r}; }
    static Ball wide()   { return {BallType::WIDE, 0}; }
    static Ball noBall() { return {BallType::NO_BALL, 0}; }
    static Ball wicket() { return {BallType::WICKET, 0}; }
};

// The "subject" in the Observer pattern: holds the running score and the list
// of subscribers (stored as std::function callbacks). Applying a ball updates
// state and then notifies every observer.
struct Innings {
    string battingTeam;
    int runs = 0, wickets = 0, legalBalls = 0;
    vector<function<void(const Innings&)>> observers;

    explicit Innings(string team) : battingTeam(move(team)) {}
    // Register an observer callback to be invoked after every ball.
    void subscribe(function<void(const Innings&)> o) { observers.push_back(move(o)); }

    // Apply one ball's outcome, then notify observers.
    void applyBall(const Ball& ball) {
        switch (ball.type) {
            // Runs off the bat count and the delivery is a legal ball.
            case BallType::RUN:     runs += ball.runs; legalBalls++; break;
            // Wide/no-ball: +1 extra run but NOT a legal ball, so it is re-bowled.
            case BallType::WIDE:    runs += 1; break;
            case BallType::NO_BALL: runs += 1; break;
            // Wicket: no runs, but it is a legal ball that counts in the over.
            case BallType::WICKET:  wickets++; legalBalls++; break;
        }
        // Fan-out: push the latest score to every subscriber (Observer pattern).
        for (auto& o : observers) o(*this);
    }

    // Format legal balls as overs.balls, e.g. 8 legal balls -> "1.2".
    string overs() const { return to_string(legalBalls / 6) + "." + to_string(legalBalls % 6); }
    // Human-readable scoreline like "India 13/1 (1.0 ov)".
    string summary() const { return battingTeam + " " + to_string(runs) + "/" + to_string(wickets) + " (" + overs() + " ov)"; }
};

int main() {
    Innings innings("India");

    // First observer: print the full scoreline after every ball.
    innings.subscribe([](const Innings& i) { cout << "  [scoreboard] " << i.summary() << "\n"; });
    // Second observer: only speaks up at the end of an over once a wicket fell.
    innings.subscribe([](const Innings& i) {
        if (i.wickets > 0 && i.legalBalls % 6 == 0)
            cout << "  [alert] end of over " << (i.legalBalls / 6) << "\n";
    });

    // Simulate an over: 4, wide, 6, wicket, 1, no-ball, 2, 0
    vector<Ball> over = {
        Ball::makeRuns(4), Ball::wide(), Ball::makeRuns(6), Ball::wicket(),
        Ball::makeRuns(1), Ball::noBall(), Ball::makeRuns(2), Ball::makeRuns(0)
    };
    // Feed each delivery through the innings; observers react to each one.
    for (auto& b : over) innings.applyBall(b);

    cout << "Final: " << innings.summary() << "\n";
    return 0;
}

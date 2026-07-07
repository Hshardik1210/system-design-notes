import java.util.*;

/**
 * CRICBUZZ / LIVE SCOREBOARD — apply ball-by-ball scoring rules and push live
 * updates to observers (subscribers) after each ball.
 *
 * Scoring rules modelled:
 *   - Runs off the bat (0-6) count and are legal deliveries.
 *   - WIDE / NO_BALL add 1 extra run and do NOT count as a legal ball.
 *   - WICKET is a legal ball; increments wickets.
 *   - 6 legal balls = 1 over.
 *
 * Pattern: Observer (scoreboard subscribers), Strategy-ish ball classification.
 */
public class Main {

    // The four possible outcomes of a single delivery. Used to decide scoring.
    enum BallType { RUN, WIDE, NO_BALL, WICKET }

    // Immutable value object describing one delivery: its type plus runs scored.
    // The static factory methods (runs/wide/noBall/wicket) make test data read
    // like real cricket, e.g. Ball.runs(4) or Ball.wicket().
    static class Ball {
        final BallType type; final int runs;
        Ball(BallType type, int runs) { this.type = type; this.runs = runs; }
        static Ball runs(int r) { return new Ball(BallType.RUN, r); }
        static Ball wide()    { return new Ball(BallType.WIDE, 0); }
        static Ball noBall()  { return new Ball(BallType.NO_BALL, 0); }
        static Ball wicket()  { return new Ball(BallType.WICKET, 0); }
    }

    // Observer: anything that wants live score updates.
    // Each subscriber implements update() and is called after every ball.
    interface ScoreObserver { void update(Innings innings); }

    // The "subject" in the Observer pattern: holds the running score and the
    // list of subscribers. Applying a ball updates state and fans out to all.
    static class Innings {
        final String battingTeam;
        int runs = 0, wickets = 0, legalBalls = 0;
        private final List<ScoreObserver> observers = new ArrayList<>();
        Innings(String battingTeam) { this.battingTeam = battingTeam; }

        // Register an observer so it receives updates after every ball.
        void subscribe(ScoreObserver o) { observers.add(o); }

        // Apply one ball's outcome, then notify observers.
        void applyBall(Ball ball) {
            switch (ball.type) {
                // Runs off the bat count and the delivery is a legal ball.
                case RUN:     runs += ball.runs; legalBalls++; break;
                // Wide/no-ball: +1 extra run but NOT a legal ball, so it is re-bowled.
                case WIDE:    runs += 1; /* no legal ball */ break;
                case NO_BALL: runs += 1; /* no legal ball */ break;
                // Wicket: no runs, but it is a legal ball that counts in the over.
                case WICKET:  wickets++; legalBalls++; break;
            }
            // Fan-out: push the latest score to every subscriber (Observer pattern).
            for (ScoreObserver o : observers) o.update(this);
        }

        // Format legal balls as overs.balls, e.g. 8 legal balls -> "1.2".
        String overs() { return (legalBalls / 6) + "." + (legalBalls % 6); }
        // Human-readable scoreline like "India 13/1 (1.0 ov)".
        String summary() { return battingTeam + " " + runs + "/" + wickets + " (" + overs() + " ov)"; }
    }

    public static void main(String[] args) {
        Innings innings = new Innings("India");

        // Two subscribers: a compact scoreboard + a commentary feed.
        // First observer: print the full scoreline after every ball.
        innings.subscribe(i -> System.out.println("  [scoreboard] " + i.summary()));
        // Second observer: only speaks up at the end of an over once a wicket fell.
        innings.subscribe(i -> {
            if (i.wickets > 0 && i.legalBalls % 6 == 0)
                System.out.println("  [alert] end of over " + (i.legalBalls / 6));
        });

        // Simulate an over: 4, wide, 6, wicket, 1, no-ball, 2, 0
        Ball[] over = {
                Ball.runs(4), Ball.wide(), Ball.runs(6), Ball.wicket(),
                Ball.runs(1), Ball.noBall(), Ball.runs(2), Ball.runs(0)
        };
        // Feed each delivery through the innings; observers react to each one.
        for (Ball b : over) innings.applyBall(b);

        System.out.println("Final: " + innings.summary());
    }
}

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

    enum BallType { RUN, WIDE, NO_BALL, WICKET }

    static class Ball {
        final BallType type; final int runs;
        Ball(BallType type, int runs) { this.type = type; this.runs = runs; }
        static Ball runs(int r) { return new Ball(BallType.RUN, r); }
        static Ball wide()    { return new Ball(BallType.WIDE, 0); }
        static Ball noBall()  { return new Ball(BallType.NO_BALL, 0); }
        static Ball wicket()  { return new Ball(BallType.WICKET, 0); }
    }

    // Observer: anything that wants live score updates.
    interface ScoreObserver { void update(Innings innings); }

    static class Innings {
        final String battingTeam;
        int runs = 0, wickets = 0, legalBalls = 0;
        private final List<ScoreObserver> observers = new ArrayList<>();
        Innings(String battingTeam) { this.battingTeam = battingTeam; }

        void subscribe(ScoreObserver o) { observers.add(o); }

        // Apply one ball's outcome, then notify observers.
        void applyBall(Ball ball) {
            switch (ball.type) {
                case RUN:     runs += ball.runs; legalBalls++; break;
                case WIDE:    runs += 1; /* no legal ball */ break;
                case NO_BALL: runs += 1; /* no legal ball */ break;
                case WICKET:  wickets++; legalBalls++; break;
            }
            for (ScoreObserver o : observers) o.update(this);
        }

        String overs() { return (legalBalls / 6) + "." + (legalBalls % 6); }
        String summary() { return battingTeam + " " + runs + "/" + wickets + " (" + overs() + " ov)"; }
    }

    public static void main(String[] args) {
        Innings innings = new Innings("India");

        // Two subscribers: a compact scoreboard + a commentary feed.
        innings.subscribe(i -> System.out.println("  [scoreboard] " + i.summary()));
        innings.subscribe(i -> {
            if (i.wickets > 0 && i.legalBalls % 6 == 0)
                System.out.println("  [alert] end of over " + (i.legalBalls / 6));
        });

        // Simulate an over: 4, wide, 6, wicket, 1, no-ball, 2, 0
        Ball[] over = {
                Ball.runs(4), Ball.wide(), Ball.runs(6), Ball.wicket(),
                Ball.runs(1), Ball.noBall(), Ball.runs(2), Ball.runs(0)
        };
        for (Ball b : over) innings.applyBall(b);

        System.out.println("Final: " + innings.summary());
    }
}

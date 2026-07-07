import java.util.*;

/**
 * SNAKE & LADDER.
 *
 * Key design insight: a snake and a ladder are the SAME thing — a "jump" from a
 * start cell to an end cell. Ladder = end > start, Snake = end < start. Modelling
 * both as one 'Jump' abstraction removes duplicated logic (unified handling).
 *
 * Flow: players take turns, roll a die, advance; if they land on a jump's start,
 * teleport to its end. First to land exactly on the last cell wins.
 */
public class Main {

    // Unified abstraction for both snakes and ladders.
    static class Jump {
        final int start, end;
        Jump(int start, int end) { this.start = start; this.end = end; }
        boolean isLadder() { return end > start; }
    }

    // Die is pluggable (could be loaded, multi-dice, etc.).
    static class Die {
        private final Random rnd = new Random(42); // fixed seed => reproducible demo
        private final int faces;
        Die(int faces) { this.faces = faces; }
        int roll() { return rnd.nextInt(faces) + 1; }
    }

    static class Board {
        final int size; // number of cells (e.g., 100)
        private final Map<Integer, Jump> jumps = new HashMap<>(); // startCell -> Jump

        Board(int size) { this.size = size; }
        void addJump(Jump j) { jumps.put(j.start, j); }

        // Apply a jump if one starts at 'cell'; else return the same cell.
        int resolve(int cell) {
            Jump j = jumps.get(cell);
            return j == null ? cell : j.end;
        }
    }

    static class Player {
        final String name;
        int position = 0; // 0 = off-board / start
        Player(String name) { this.name = name; }
    }

    static class Game {
        private final Board board;
        private final Die die;
        private final Queue<Player> turnOrder = new LinkedList<>(); // round-robin turns

        Game(Board board, Die die, List<Player> players) {
            this.board = board; this.die = die;
            turnOrder.addAll(players);
        }

        Player play() {
            while (true) {
                Player p = turnOrder.poll(); // whose turn
                int roll = die.roll();
                int target = p.position + roll;

                if (target > board.size) {
                    // Must land exactly on the final cell; overshoot => stay put.
                    System.out.printf("%s rolled %d but overshoots; stays at %d%n", p.name, roll, p.position);
                    turnOrder.add(p);
                    continue;
                }

                int afterJump = board.resolve(target);
                if (afterJump != target) {
                    String kind = afterJump > target ? "climbs a LADDER" : "hit a SNAKE";
                    System.out.printf("%s rolled %d -> %d, %s -> %d%n", p.name, roll, target, kind, afterJump);
                } else {
                    System.out.printf("%s rolled %d -> %d%n", p.name, roll, target);
                }
                p.position = afterJump;

                if (p.position == board.size) return p; // exact landing wins
                turnOrder.add(p); // requeue for next round
            }
        }
    }

    public static void main(String[] args) {
        Board board = new Board(100);
        // Ladders (end > start)
        board.addJump(new Jump(2, 38));
        board.addJump(new Jump(7, 14));
        board.addJump(new Jump(28, 76));
        // Snakes (end < start)
        board.addJump(new Jump(47, 26));
        board.addJump(new Jump(62, 18));
        board.addJump(new Jump(99, 41));

        Game game = new Game(board, new Die(6),
                Arrays.asList(new Player("Alice"), new Player("Bob")));
        Player winner = game.play();
        System.out.println("\nWinner: " + winner.name);
    }
}

import java.util.*;

/**
 * SNAKE & LADDER  — a small, self-contained model of the classic board game.
 *
 * WHAT THIS PROGRAM DOES:
 *   Builds a 100-cell board with a few snakes and ladders, then lets two players
 *   ("Alice" and "Bob") take turns rolling a die until one of them lands exactly
 *   on the last cell. Each move is printed so you can follow the whole game.
 *
 * KEY CLASSES (each is a small building block):
 *   - Jump   : one snake OR one ladder (a teleport from a start cell to an end cell).
 *   - Die    : produces a random dice roll (1..faces).
 *   - Board  : the grid of cells plus the map of jumps, and the rule to apply them.
 *   - Player : a competitor and their current position.
 *   - Game   : owns the turn order and runs the roll-loop until someone wins.
 *
 * DESIGN IDEAS USED (mirrors the README):
 *   - Unified abstraction: a snake and a ladder are the SAME thing — a "jump" from
 *     a start cell to an end cell. Ladder = end > start, Snake = end < start.
 *     Treating both as one 'Jump' removes duplicated snake/ladder logic; the board
 *     simply asks "is there a jump starting on this cell?".
 *   - Strategy-style pluggability: the Die is a separate object, so it can be
 *     swapped for a loaded die, multi-dice, etc., without touching the game logic.
 *   - Queue for round-robin turns: players wait in line and are re-queued after
 *     each move, giving fair "take turns in order" behaviour.
 *
 * FLOW: players take turns, roll a die, advance; if they land on a jump's start,
 * they teleport to its end. First to land exactly on the last cell wins.
 */
public class Main {

    // Jump = one snake or one ladder. A single class models both because both are
    // just a teleport from 'start' to 'end' (direction decides which one it is).
    static class Jump {
        final int start, end;
        Jump(int start, int end) { this.start = start; this.end = end; }
        // end above start means you go UP the board, so it's a ladder (else a snake).
        boolean isLadder() { return end > start; }
    }

    // Die = the source of randomness for a roll. Kept as its own class so it is
    // easy to replace (e.g., a loaded die or multiple dice) without changing Game.
    static class Die {
        private final Random rnd = new Random(42); // fixed seed => same game every run (reproducible demo)
        private final int faces;
        Die(int faces) { this.faces = faces; }
        // nextInt(faces) gives 0..faces-1, so +1 shifts it to the real dice range 1..faces.
        int roll() { return rnd.nextInt(faces) + 1; }
    }

    // Board = the playing surface: how many cells it has and where the jumps are.
    static class Board {
        final int size; // number of cells (e.g., 100)
        private final Map<Integer, Jump> jumps = new HashMap<>(); // startCell -> Jump (fast lookup by landing cell)

        Board(int size) { this.size = size; }
        // Register a snake/ladder, keyed by its start cell so resolve() can find it.
        void addJump(Jump j) { jumps.put(j.start, j); }

        // Given the cell a player landed on, return where they actually end up:
        // if a jump starts here, teleport to its end; otherwise stay on the same cell.
        int resolve(int cell) {
            Jump j = jumps.get(cell);
            return j == null ? cell : j.end;
        }
    }

    // Player = a competitor and the cell they are currently standing on.
    static class Player {
        final String name;
        int position = 0; // 0 = off-board / start (before the first cell)
        Player(String name) { this.name = name; }
    }

    // Game = the referee: it holds the turn order and runs the game until a winner.
    static class Game {
        private final Board board;
        private final Die die;
        private final Queue<Player> turnOrder = new LinkedList<>(); // round-robin turns (front = plays next)

        // Wire the pieces together and line all players up in the turn queue.
        Game(Board board, Die die, List<Player> players) {
            this.board = board; this.die = die;
            turnOrder.addAll(players);
        }

        // Run the game loop forever until someone lands exactly on the last cell,
        // then return that winner. Each iteration is one player's single turn.
        Player play() {
            while (true) {
                Player p = turnOrder.poll(); // take the player at the front of the queue (whose turn it is)
                int roll = die.roll();
                int target = p.position + roll; // where the roll would land, before jumps

                if (target > board.size) {
                    // Must land exactly on the final cell; overshoot => turn is wasted, stay put.
                    System.out.printf("%s rolled %d but overshoots; stays at %d%n", p.name, roll, p.position);
                    turnOrder.add(p); // send them to the back to wait for their next turn
                    continue;
                }

                int afterJump = board.resolve(target); // apply a snake/ladder if one starts on 'target'
                if (afterJump != target) {
                    // Position changed, so a jump fired: up = ladder, down = snake.
                    String kind = afterJump > target ? "climbs a LADDER" : "hit a SNAKE";
                    System.out.printf("%s rolled %d -> %d, %s -> %d%n", p.name, roll, target, kind, afterJump);
                } else {
                    System.out.printf("%s rolled %d -> %d%n", p.name, roll, target);
                }
                p.position = afterJump; // commit the new position

                if (p.position == board.size) return p; // exact landing on the last cell wins
                turnOrder.add(p); // otherwise requeue this player for the next round
            }
        }
    }

    // Entry point: build the board, add snakes/ladders, then play a 2-player game.
    public static void main(String[] args) {
        Board board = new Board(100);
        // Ladders (end > start): landing on the start cell sends you UP.
        board.addJump(new Jump(2, 38));
        board.addJump(new Jump(7, 14));
        board.addJump(new Jump(28, 76));
        // Snakes (end < start): landing on the start cell sends you DOWN.
        board.addJump(new Jump(47, 26));
        board.addJump(new Jump(62, 18));
        board.addJump(new Jump(99, 41));

        // Two players share one 6-sided die; play() runs until one of them wins.
        Game game = new Game(board, new Die(6),
                Arrays.asList(new Player("Alice"), new Player("Bob")));
        Player winner = game.play();
        System.out.println("\nWinner: " + winner.name);
    }
}

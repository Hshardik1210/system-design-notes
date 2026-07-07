import java.util.*;

/**
 * TIC-TAC-TOE — generalised to an N x N board.
 *
 * Win rule: fill any full row, column, or one of the two diagonals with your mark.
 *
 * Efficient win detection: instead of scanning the whole board after each move,
 * we keep running counters per row, per column, and the two diagonals. A player
 * wins the moment any counter reaches N. This is O(1) per move.
 *
 * The 'marks' array counts +1 for player X and -1 for player O in each line;
 * an absolute value of N means that line is fully owned by one player.
 *
 * Key classes:
 *   - Board:  the N x N grid plus the running counters; knows how to place a mark.
 *   - Player: a simple holder for a player's name and their mark (X or O).
 *   - Game:   drives play, alternates turns, prints the board, and reports the result.
 *
 * Design patterns / ideas: plain object-oriented composition (Game "has-a" Board and
 * Players) plus the O(1) running-counter trick described above.
 */
public class Main {

    // The three possible states of a cell: empty, or owned by player X or player O.
    enum Mark { EMPTY, X, O }

    // Board: stores the grid and the running counters, and decides if a move wins.
    static class Board {
        final int n;                        // board size (n rows and n columns)
        private final Mark[][] grid;        // the actual n x n cells
        private int movesPlayed = 0;        // how many marks have been placed so far

        // Running counters (X adds +1, O adds -1). One per row and per column,
        // so we never have to re-scan the whole board to detect a win.
        private final int[] rows, cols;
        private int diag, antiDiag;         // counters for the two diagonals

        // Create an empty n x n board and zero out all the counters.
        Board(int n) {
            this.n = n;
            grid = new Mark[n][n];
            for (Mark[] row : grid) Arrays.fill(row, Mark.EMPTY);
            rows = new int[n];
            cols = new int[n];
        }

        // The board is full once every cell has a mark (used to detect a draw).
        boolean isFull() { return movesPlayed == n * n; }
        // True if the given cell has not been played yet.
        boolean isEmpty(int r, int c) { return grid[r][c] == Mark.EMPTY; }

        /**
         * Place a mark; return true if this move wins the game.
         * @throws IllegalArgumentException on out-of-bounds / occupied cells.
         */
        boolean place(int r, int c, Mark m) {
            if (r < 0 || r >= n || c < 0 || c >= n) throw new IllegalArgumentException("out of bounds");
            if (grid[r][c] != Mark.EMPTY) throw new IllegalArgumentException("cell occupied");
            grid[r][c] = m;
            movesPlayed++;

            // X contributes +1 and O contributes -1 to every line this cell sits on.
            int delta = (m == Mark.X) ? 1 : -1;
            rows[r] += delta;
            cols[c] += delta;
            if (r == c) diag += delta;             // main diagonal (top-left to bottom-right)
            if (r + c == n - 1) antiDiag += delta; // anti-diagonal (top-right to bottom-left)

            // |counter| == n => that whole line belongs to one player, so this move wins.
            return Math.abs(rows[r]) == n || Math.abs(cols[c]) == n
                    || Math.abs(diag) == n || Math.abs(antiDiag) == n;
        }

        // Print the board to the console, e.g. "X | O | ." for one row.
        void print() {
            for (int i = 0; i < n; i++) {
                StringBuilder sb = new StringBuilder(" ");
                for (int j = 0; j < n; j++) {
                    sb.append(grid[i][j] == Mark.EMPTY ? "." : grid[i][j].name());
                    if (j < n - 1) sb.append(" | ");
                }
                System.out.println(sb);
            }
            System.out.println();
        }
    }

    // Player: just pairs a display name with the mark that player uses.
    static class Player {
        final String name; final Mark mark;
        Player(String name, Mark mark) { this.name = name; this.mark = mark; }
    }

    // Game: owns the board and the two players and runs one move at a time.
    static class Game {
        private final Board board;
        private final Player[] players;
        private int turn = 0; // index (0 or 1) of the player whose turn it is

        Game(int n, Player p1, Player p2) { board = new Board(n); players = new Player[]{p1, p2}; }

        // Play one move for the current player. Returns the winner if this move
        // ended the game, otherwise switches the turn and returns null.
        Player move(int r, int c) {
            Player current = players[turn];
            boolean won = board.place(r, c, current.mark);
            board.print();
            if (won) return current;
            turn = 1 - turn; // flip between index 0 and 1 to swap players
            return null;
        }

        // A draw happens when the board fills up with no winner.
        boolean isDraw() { return board.isFull(); }
    }

    // Entry point: sets up a 3x3 game and replays a fixed sequence of moves.
    public static void main(String[] args) {
        Game g = new Game(3, new Player("Alice", Mark.X), new Player("Bob", Mark.O));

        // X plays the top row to win: (0,0),(0,1),(0,2). O plays elsewhere.
        int[][] moves = {{0,0},{1,0},{0,1},{1,1},{0,2}};
        Player winner = null;
        for (int[] mv : moves) {
            winner = g.move(mv[0], mv[1]);
            if (winner != null) break; // stop as soon as someone wins
        }

        if (winner != null) System.out.println("Winner: " + winner.name + " (" + winner.mark + ")");
        else if (g.isDraw()) System.out.println("Draw!");
    }
}

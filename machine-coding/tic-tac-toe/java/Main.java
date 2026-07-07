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
 */
public class Main {

    enum Mark { EMPTY, X, O }

    static class Board {
        final int n;
        private final Mark[][] grid;
        private int movesPlayed = 0;

        // Running counters (X adds +1, O adds -1).
        private final int[] rows, cols;
        private int diag, antiDiag;

        Board(int n) {
            this.n = n;
            grid = new Mark[n][n];
            for (Mark[] row : grid) Arrays.fill(row, Mark.EMPTY);
            rows = new int[n];
            cols = new int[n];
        }

        boolean isFull() { return movesPlayed == n * n; }
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

            int delta = (m == Mark.X) ? 1 : -1;
            rows[r] += delta;
            cols[c] += delta;
            if (r == c) diag += delta;             // main diagonal
            if (r + c == n - 1) antiDiag += delta; // anti-diagonal

            // |counter| == n => that whole line belongs to one player.
            return Math.abs(rows[r]) == n || Math.abs(cols[c]) == n
                    || Math.abs(diag) == n || Math.abs(antiDiag) == n;
        }

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

    static class Player {
        final String name; final Mark mark;
        Player(String name, Mark mark) { this.name = name; this.mark = mark; }
    }

    static class Game {
        private final Board board;
        private final Player[] players;
        private int turn = 0; // index into players

        Game(int n, Player p1, Player p2) { board = new Board(n); players = new Player[]{p1, p2}; }

        // Returns the winning player, or null if the move didn't end the game.
        Player move(int r, int c) {
            Player current = players[turn];
            boolean won = board.place(r, c, current.mark);
            board.print();
            if (won) return current;
            turn = 1 - turn; // switch player
            return null;
        }

        boolean isDraw() { return board.isFull(); }
    }

    public static void main(String[] args) {
        Game g = new Game(3, new Player("Alice", Mark.X), new Player("Bob", Mark.O));

        // X plays the top row to win: (0,0),(0,1),(0,2). O plays elsewhere.
        int[][] moves = {{0,0},{1,0},{0,1},{1,1},{0,2}};
        Player winner = null;
        for (int[] mv : moves) {
            winner = g.move(mv[0], mv[1]);
            if (winner != null) break;
        }

        if (winner != null) System.out.println("Winner: " + winner.name + " (" + winner.mark + ")");
        else if (g.isDraw()) System.out.println("Draw!");
    }
}

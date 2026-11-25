package ae.search;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Connect-k on a rows x cols board. With {@code gravity} a move is a column and the piece falls to the lowest
 * free cell (Connect Four); without it a move is a cell index in reading order, {@code row * cols + col}
 * counted from the top-left corner (tic-tac-toe: 0 is top-left, 8 bottom-right). {@link #parse} takes rows top
 * to bottom. Player X moves first.
 */
public final class ConnectK implements Position {
    private static final byte EMPTY = 0;
    private static final byte X = 1;
    private static final byte O = 2;
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private final int rows;
    private final int cols;
    private final int k;
    private final boolean gravity;
    private final byte[] cells;
    private final int ply;
    private final long key;
    private final Status status;
    private final long[] zobrist;

    private ConnectK(int rows, int cols, int k, boolean gravity, byte[] cells, int ply, long key, long[] zobrist) {
        this.rows = rows;
        this.cols = cols;
        this.k = k;
        this.gravity = gravity;
        this.cells = cells;
        this.ply = ply;
        this.key = key;
        this.zobrist = zobrist;
        byte lastPlayer = ply % 2 == 1 ? X : O;
        if (ply > 0 && hasLine(lastPlayer)) {
            this.status = Status.MOVER_LOST;
        } else if (ply == rows * cols) {
            this.status = Status.DRAW;
        } else {
            this.status = Status.ONGOING;
        }
    }

    /** An empty board. */
    public static ConnectK start(int rows, int cols, int k, boolean gravity) {
        checkShape(rows, cols, k);
        return new ConnectK(rows, cols, k, gravity, new byte[rows * cols], 0, 0L, zobristFor(rows, cols));
    }

    public static ConnectK ticTacToe() {
        return start(3, 3, 3, false);
    }

    public static ConnectK connectFour() {
        return start(6, 7, 4, true);
    }

    /** Builds a position from rows listed top to bottom using {@code X}, {@code O} and {@code .}. */
    public static ConnectK parse(int k, boolean gravity, String... rowsTopToBottom) {
        int rows = rowsTopToBottom.length;
        if (rows == 0) {
            throw new IllegalArgumentException("no rows");
        }
        int cols = rowsTopToBottom[0].length();
        checkShape(rows, cols, k);
        byte[] cells = new byte[rows * cols];
        int xs = 0;
        int os = 0;
        long[] z = zobristFor(rows, cols);
        long key = 0L;
        for (int i = 0; i < rows; i++) {
            String line = rowsTopToBottom[i];
            if (line.length() != cols) {
                throw new IllegalArgumentException("ragged row " + i + ": " + line);
            }
            int r = rows - 1 - i;
            for (int c = 0; c < cols; c++) {
                byte v;
                switch (line.charAt(c)) {
                    case 'X' -> { v = X; xs++; }
                    case 'O' -> { v = O; os++; }
                    case '.' -> v = EMPTY;
                    default -> throw new IllegalArgumentException("bad character in " + line);
                }
                cells[r * cols + c] = v;
                if (v != EMPTY) {
                    key ^= z[(r * cols + c) * 2 + v - 1];
                }
            }
        }
        if (xs != os && xs != os + 1) {
            throw new IllegalArgumentException("piece counts " + xs + " X / " + os + " O are impossible");
        }
        if (gravity) {
            for (int c = 0; c < cols; c++) {
                boolean gap = false;
                for (int r = 0; r < rows; r++) {
                    boolean empty = cells[r * cols + c] == EMPTY;
                    if (empty) {
                        gap = true;
                    } else if (gap) {
                        throw new IllegalArgumentException("floating piece in column " + c);
                    }
                }
            }
        }
        ConnectK p = new ConnectK(rows, cols, k, gravity, cells, xs + os, key, z);
        boolean xLine = p.hasLine(X);
        boolean oLine = p.hasLine(O);
        if (xLine && oLine) {
            throw new IllegalArgumentException("both players have a line");
        }
        byte mover = p.ply % 2 == 0 ? X : O;
        if (p.hasLine(mover)) {
            throw new IllegalArgumentException("the player to move already has a line");
        }
        return p;
    }

    private static void checkShape(int rows, int cols, int k) {
        if (rows < 1 || cols < 1 || rows * cols > 100) {
            throw new IllegalArgumentException("bad board size " + rows + "x" + cols);
        }
        if (k < 2 || k > Math.max(rows, cols)) {
            throw new IllegalArgumentException("bad line length " + k);
        }
    }

    private static long[] zobristFor(int rows, int cols) {
        SplittableRandom rnd = new SplittableRandom(0x9E3779B97F4A7C15L ^ (rows * 1000L + cols));
        long[] z = new long[rows * cols * 2];
        for (int i = 0; i < z.length; i++) {
            z[i] = rnd.nextLong();
        }
        return z;
    }

    /** Number of cells on the board. */
    public int capacity() {
        return rows * cols;
    }

    /** Storage index (row 0 is the bottom row) of a reading-order cell index. */
    private int storageCell(int readingIndex) {
        return (rows - 1 - readingIndex / cols) * cols + readingIndex % cols;
    }

    private byte mover() {
        return ply % 2 == 0 ? X : O;
    }

    private boolean hasLine(byte player) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cells[r * cols + c] != player) {
                    continue;
                }
                for (int[] d : DIRECTIONS) {
                    int len = 1;
                    int rr = r + d[0];
                    int cc = c + d[1];
                    while (len < k && rr >= 0 && rr < rows && cc >= 0 && cc < cols && cells[rr * cols + cc] == player) {
                        len++;
                        rr += d[0];
                        cc += d[1];
                    }
                    if (len >= k) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public int[] moves() {
        if (status != Status.ONGOING) {
            return new int[0];
        }
        int[] buf = new int[gravity ? cols : rows * cols];
        int n = 0;
        if (gravity) {
            for (int c = 0; c < cols; c++) {
                if (cells[(rows - 1) * cols + c] == EMPTY) {
                    buf[n++] = c;
                }
            }
        } else {
            for (int i = 0; i < cells.length; i++) {
                if (cells[storageCell(i)] == EMPTY) {
                    buf[n++] = i;
                }
            }
        }
        return Arrays.copyOf(buf, n);
    }

    @Override
    public Position play(int move) {
        if (status != Status.ONGOING) {
            throw new IllegalArgumentException("game is over");
        }
        int cell;
        if (gravity) {
            if (move < 0 || move >= cols) {
                throw new IllegalArgumentException("no such column " + move);
            }
            cell = -1;
            for (int r = 0; r < rows; r++) {
                if (cells[r * cols + move] == EMPTY) {
                    cell = r * cols + move;
                    break;
                }
            }
            if (cell < 0) {
                throw new IllegalArgumentException("column " + move + " is full");
            }
        } else {
            if (move < 0 || move >= cells.length || cells[storageCell(move)] != EMPTY) {
                throw new IllegalArgumentException("illegal cell " + move);
            }
            cell = storageCell(move);
        }
        byte player = mover();
        byte[] next = cells.clone();
        next[cell] = player;
        return new ConnectK(rows, cols, k, gravity, next, ply + 1, key ^ zobrist[cell * 2 + player - 1], zobrist);
    }

    @Override
    public Status status() {
        return status;
    }

    /** Sum over every window of k cells holding pieces of one player only: +n*n for X, -n*n for O, mover's view. */
    @Override
    public int heuristic() {
        int total = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                for (int[] d : DIRECTIONS) {
                    int er = r + d[0] * (k - 1);
                    int ec = c + d[1] * (k - 1);
                    if (er < 0 || er >= rows || ec < 0 || ec >= cols) {
                        continue;
                    }
                    int xs = 0;
                    int os = 0;
                    for (int i = 0; i < k; i++) {
                        byte v = cells[(r + d[0] * i) * cols + c + d[1] * i];
                        if (v == X) {
                            xs++;
                        } else if (v == O) {
                            os++;
                        }
                    }
                    if (os == 0) {
                        total += xs * xs;
                    } else if (xs == 0) {
                        total -= os * os;
                    }
                }
            }
        }
        return mover() == X ? total : -total;
    }

    @Override
    public long key() {
        return key;
    }

    @Override
    public int ply() {
        return ply;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConnectK q && q.rows == rows && q.cols == cols && q.k == k
            && q.gravity == gravity && Arrays.equals(q.cells, cells);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(key);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = rows - 1; r >= 0; r--) {
            for (int c = 0; c < cols; c++) {
                sb.append(".XO".charAt(cells[r * cols + c]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}

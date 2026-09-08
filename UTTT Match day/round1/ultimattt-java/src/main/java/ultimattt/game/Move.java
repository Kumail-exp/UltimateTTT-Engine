package ultimattt.game;

/**
 * A move is specified by the global (local-board) index 0-8
 * and the local square 0-8 inside that board.
 *
 * Notation uses letters a-i for both.
 * Interned instances for the 81 possible moves to reduce allocation in search.
 */
public final class Move {
    private final int global;
    private final int local;

    private static final Move[] CACHE = new Move[81];
    static {
        for (int g = 0; g < 9; g++) {
            for (int l = 0; l < 9; l++) {
                CACHE[g * 9 + l] = new Move(g, l);
            }
        }
    }

    private Move(int global, int local) {
        this.global = global;
        this.local = local;
    }

    public static Move of(int global, int local) {
        return CACHE[global * 9 + local];
    }

    public int global() { return global; }
    public int local() { return local; }

    public static Move fromNotation(String s) {
        if (s == null || s.length() != 2) {
            throw new IllegalArgumentException("Move notation must be two letters a-i: " + s);
        }
        int g = s.charAt(0) - 'a';
        int l = s.charAt(1) - 'a';
        if (g < 0 || g > 8 || l < 0 || l > 8) {
            throw new IllegalArgumentException("Invalid move letters: " + s);
        }
        return of(g, l);
    }

    public String toNotation() {
        return "" + (char) ('a' + global) + (char) ('a' + local);
    }

    @Override
    public String toString() {
        return toNotation();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Move m)) return false;
        return global == m.global && local == m.local;
    }

    @Override
    public int hashCode() {
        return global * 9 + local;
    }
}

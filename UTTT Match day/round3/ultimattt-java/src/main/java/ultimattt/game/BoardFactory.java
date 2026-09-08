package ultimattt.game;

/** Package-private factory to reconstruct a Board from raw state (used by Notation). */
final class BoardFactory {
    private BoardFactory() {}

    static Board create(int[] xBits, int[] oBits, byte[] globalState,
                        Player next, int forced, GameState gs) {
        // We need access to the private constructor. Since we're in the same package
        // we can add a package-visible constructor to Board.
        return new Board(xBits, oBits, globalState, next, forced, gs);
    }
}

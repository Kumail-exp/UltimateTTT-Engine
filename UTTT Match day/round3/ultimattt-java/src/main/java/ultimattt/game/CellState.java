package ultimattt.game;

public enum CellState {
    EMPTY,
    X,
    O;

    public static CellState of(Player p) {
        return p == Player.X ? X : O;
    }

    public Player asPlayer() {
        return switch (this) {
            case X -> Player.X;
            case O -> Player.O;
            default -> null;
        };
    }
}

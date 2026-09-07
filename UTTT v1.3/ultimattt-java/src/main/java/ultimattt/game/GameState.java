package ultimattt.game;

public enum GameState {
    IN_PLAY,
    DRAWN,
    WON_X,
    WON_O;

    public boolean isTerminal() {
        return this != IN_PLAY;
    }

    public static GameState won(Player p) {
        return p == Player.X ? WON_X : WON_O;
    }

    public Player winner() {
        return switch (this) {
            case WON_X -> Player.X;
            case WON_O -> Player.O;
            default -> null;
        };
    }
}

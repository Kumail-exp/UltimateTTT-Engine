package ultimattt.game;

public enum Player {
    X, O;

    public Player other() {
        return this == X ? O : X;
    }

    public String asString() {
        return this == X ? "X" : "O";
    }

    public int asBit() {
        return this == X ? 0 : 1;
    }
}

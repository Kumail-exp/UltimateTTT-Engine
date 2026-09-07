package ultimattt.game;

/**
 * Position notation compatible with the original Rust engine.
 *
 * Format: PLAYER;GLOBAL;LOCAL0/LOCAL1/.../LOCAL8
 * Example:
 * O;@........;X.OO...../X..X.O.O./...
 *
 * GLOBAL uses . for empty/in-play, X/O for won, @ for the forced next board.
 * LOCAL boards use X, O, . for the 9 squares.
 */
public final class Notation {

    private Notation() {}

    public static Board parse(String pos) {
        String[] parts = pos.trim().split(";");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected 3 semicolon-separated parts: " + pos);
        }

        Player next = parts[0].equals("X") ? Player.X :
                      parts[0].equals("O") ? Player.O :
                      throwArg("Bad player: " + parts[0]);

        String globalStr = parts[1];
        if (globalStr.length() != 9) {
            throw new IllegalArgumentException("Global must be 9 chars");
        }

        int forced = -1;
        byte[] globalState = new byte[9];
        for (int i = 0; i < 9; i++) {
            char c = globalStr.charAt(i);
            switch (c) {
                case '.' -> globalState[i] = 0;
                case 'X' -> globalState[i] = 2;
                case 'O' -> globalState[i] = 3;
                case '@' -> {
                    globalState[i] = 0;
                    forced = i;
                }
                default -> throw new IllegalArgumentException("Bad global char: " + c);
            }
        }

        String[] locals = parts[2].split("/");
        if (locals.length != 9) {
            throw new IllegalArgumentException("Expected 9 local boards");
        }

        int[] xBits = new int[9];
        int[] oBits = new int[9];
        for (int g = 0; g < 9; g++) {
            String loc = locals[g];
            if (loc.length() != 9) {
                throw new IllegalArgumentException("Local board " + g + " must be 9 chars");
            }
            for (int l = 0; l < 9; l++) {
                char c = loc.charAt(l);
                if (c == 'X') xBits[g] |= (1 << l);
                else if (c == 'O') oBits[g] |= (1 << l);
                else if (c != '.') {
                    throw new IllegalArgumentException("Bad local char: " + c);
                }
            }
        }

        // Recompute game state from global
        GameState gs = computeGameState(globalState);

        // Use reflection-free construction via a package-private helper would be nicer,
        // but for simplicity we rebuild via successive plays is too heavy.
        // We'll use a package-visible constructor approach by creating empty and mutating
        // through a dedicated factory (kept internal).

        return BoardFactory.create(xBits, oBits, globalState, next, forced, gs);
    }

    private static GameState computeGameState(byte[] global) {
        int xG = 0, oG = 0, filled = 0;
        for (int i = 0; i < 9; i++) {
            if (global[i] == 2) xG |= (1 << i);
            else if (global[i] == 3) oG |= (1 << i);
            if (global[i] != 0) filled |= (1 << i);
        }
        int[] WIN = {0x7, 0x38, 0x1c0, 0x49, 0x92, 0x124, 0x111, 0x54};
        for (int w : WIN) {
            if ((xG & w) == w) return GameState.WON_X;
            if ((oG & w) == w) return GameState.WON_O;
        }
        if (filled == 0x1ff) return GameState.DRAWN;
        return GameState.IN_PLAY;
    }

    private static <T> T throwArg(String msg) {
        throw new IllegalArgumentException(msg);
    }

    public static String format(Board b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.getNextPlayer().asString()).append(';');

        for (int i = 0; i < 9; i++) {
            if (b.getNextBoard() == i) {
                sb.append('@');
            } else {
                GameState s = b.getLocalState(i);
                sb.append(switch (s) {
                    case WON_X -> 'X';
                    case WON_O -> 'O';
                    case DRAWN -> 'D'; // original uses . for drawn? wait, original uses . for both in-play and drawn on global? 
                    // Looking at original: global uses . for empty (in-play or drawn?), X/O for won, @ for forced.
                    // Actually original treats drawn as . on global display in the compact form? 
                    // For safety we use . for non-won.
                    default -> '.';
                });
            }
        }
        sb.append(';');

        for (int g = 0; g < 9; g++) {
            if (g > 0) sb.append('/');
            for (int l = 0; l < 9; l++) {
                CellState c = b.getCell(g, l);
                sb.append(switch (c) {
                    case X -> 'X';
                    case O -> 'O';
                    default -> '.';
                });
            }
        }
        return sb.toString();
    }
}

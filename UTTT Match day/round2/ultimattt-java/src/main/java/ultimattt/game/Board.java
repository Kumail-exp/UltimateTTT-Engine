package ultimattt.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mutable Ultimate Tic-Tac-Toe board with make/unmake and incremental Zobrist.
 * Designed for high node throughput in search.
 */
public final class Board {

    private static final int[] WIN_MASKS = {
            0b000000111, 0b000111000, 0b111000000,
            0b001001001, 0b010010010, 0b100100100,
            0b100010001, 0b001010100
    };
    private static final int BOARD_MASK = 0x1FF;

    // Zobrist tables: [global][local][playerBit 0=X 1=O]
    private static final long[][][] ZOBRIST_CELL = new long[9][9][2];
    private static final long[] ZOBRIST_NEXT_BOARD = new long[10]; // 0..8 + 9 for "any"
    private static final long ZOBRIST_SIDE;

    static {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Fixed seed for reproducibility across runs
        java.util.Random r = new java.util.Random(0xC0FFEE123456789L);
        for (int g = 0; g < 9; g++) {
            for (int l = 0; l < 9; l++) {
                ZOBRIST_CELL[g][l][0] = r.nextLong();
                ZOBRIST_CELL[g][l][1] = r.nextLong();
            }
        }
        for (int i = 0; i < 10; i++) {
            ZOBRIST_NEXT_BOARD[i] = r.nextLong();
        }
        ZOBRIST_SIDE = r.nextLong();
    }

    // Board state
    private final int[] xBits = new int[9];
    private final int[] oBits = new int[9];
    private final byte[] globalState = new byte[9]; // 0=inplay, 1=drawn, 2=X, 3=O

    private Player nextPlayer = Player.X;
    private int nextBoard = -1; // -1 = any
    private GameState gameState = GameState.IN_PLAY;
    private long zobrist;

    // Undo stack (fixed capacity, no allocation in search)
    private static final int MAX_PLY = 81;
    private final int[] undoGlobal = new int[MAX_PLY];
    private final int[] undoLocal = new int[MAX_PLY];
    private final int[] undoPrevNextBoard = new int[MAX_PLY];
    private final byte[] undoPrevGlobalState = new byte[MAX_PLY];
    private final GameState[] undoPrevGameState = new GameState[MAX_PLY];
    private final Player[] undoPrevPlayer = new Player[MAX_PLY];
    private int ply;

    public Board() {
        recomputeZobrist();
    }

    /** Deep copy constructor (for root / cloning outside search). */
    public Board(Board other) {
        System.arraycopy(other.xBits, 0, this.xBits, 0, 9);
        System.arraycopy(other.oBits, 0, this.oBits, 0, 9);
        System.arraycopy(other.globalState, 0, this.globalState, 0, 9);
        this.nextPlayer = other.nextPlayer;
        this.nextBoard = other.nextBoard;
        this.gameState = other.gameState;
        this.zobrist = other.zobrist;
        this.ply = 0;
    }

    // Package-visible for Notation reconstruction
    Board(int[] xBits, int[] oBits, byte[] globalState,
          Player nextPlayer, int nextBoard, GameState gameState) {
        System.arraycopy(xBits, 0, this.xBits, 0, 9);
        System.arraycopy(oBits, 0, this.oBits, 0, 9);
        System.arraycopy(globalState, 0, this.globalState, 0, 9);
        this.nextPlayer = nextPlayer;
        this.nextBoard = nextBoard;
        this.gameState = gameState;
        recomputeZobrist();
    }

    public Player getNextPlayer() { return nextPlayer; }
    public int getNextBoard() { return nextBoard; }
    public GameState getGameState() { return gameState; }
    public boolean isTerminal() { return gameState.isTerminal(); }
    public long zobristKey() { return zobrist; }

    public CellState getCell(int global, int local) {
        int bit = 1 << local;
        if ((xBits[global] & bit) != 0) return CellState.X;
        if ((oBits[global] & bit) != 0) return CellState.O;
        return CellState.EMPTY;
    }

    public GameState getLocalState(int global) {
        return switch (globalState[global]) {
            case 1 -> GameState.DRAWN;
            case 2 -> GameState.WON_X;
            case 3 -> GameState.WON_O;
            default -> GameState.IN_PLAY;
        };
    }

    /** Fill moves into a pre-sized array; returns count. Avoids allocation. */
    public int legalMoves(Move[] buf) {
        if (gameState.isTerminal()) return 0;
        int count = 0;
        if (nextBoard >= 0 && globalState[nextBoard] == 0) {
            int free = freeSquares(nextBoard);
            for (int local = 0; local < 9; local++) {
                if ((free & (1 << local)) != 0) {
                    buf[count++] = Move.of(nextBoard, local);
                }
            }
        } else {
            for (int g = 0; g < 9; g++) {
                if (globalState[g] != 0) continue;
                int free = freeSquares(g);
                for (int local = 0; local < 9; local++) {
                    if ((free & (1 << local)) != 0) {
                        buf[count++] = Move.of(g, local);
                    }
                }
            }
        }
        return count;
    }

    /** Convenience allocating version (for UI / root). */
    public List<Move> legalMoves() {
        Move[] buf = new Move[81];
        int n = legalMoves(buf);
        List<Move> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf[i]);
        return list;
    }

    public int legalMoveCount() {
        if (gameState.isTerminal()) return 0;
        int count = 0;
        if (nextBoard >= 0 && globalState[nextBoard] == 0) {
            return Integer.bitCount(freeSquares(nextBoard));
        }
        for (int g = 0; g < 9; g++) {
            if (globalState[g] == 0) count += Integer.bitCount(freeSquares(g));
        }
        return count;
    }

    private int freeSquares(int global) {
        return ~(xBits[global] | oBits[global]) & BOARD_MASK;
    }

    /** Immutable-style play (allocates). Used by UI / analysis outside search. */
    public Board play(Move m) {
        Board copy = new Board(this);
        copy.makeMove(m);
        return copy;
    }

    public Board play(int global, int local) {
        return play(Move.of(global, local));
    }

    /** In-place make. Must be paired with unmakeMove. */
    public void makeMove(Move m) {
        int global = m.global();
        int local = m.local();

        undoGlobal[ply] = global;
        undoLocal[ply] = local;
        undoPrevNextBoard[ply] = nextBoard;
        undoPrevGlobalState[ply] = globalState[global];
        undoPrevGameState[ply] = gameState;
        undoPrevPlayer[ply] = nextPlayer;

        // Place stone
        int bit = 1 << local;
        if (nextPlayer == Player.X) {
            xBits[global] |= bit;
            zobrist ^= ZOBRIST_CELL[global][local][0];
        } else {
            oBits[global] |= bit;
            zobrist ^= ZOBRIST_CELL[global][local][1];
        }

        // Local result
        GameState localResult = checkLocalWinner(global, nextPlayer);
        if (localResult != GameState.IN_PLAY) {
            globalState[global] = encode(localResult);
        }

        // Next forced board
        zobrist ^= ZOBRIST_NEXT_BOARD[nextBoard < 0 ? 9 : nextBoard];
        int forced = local;
        if (globalState[forced] != 0) forced = -1;
        nextBoard = forced;
        zobrist ^= ZOBRIST_NEXT_BOARD[nextBoard < 0 ? 9 : nextBoard];

        // Global result
        gameState = checkGlobalWinner(nextPlayer);

        // Side to move
        zobrist ^= ZOBRIST_SIDE;
        nextPlayer = nextPlayer.other();

        ply++;
    }

    public void unmakeMove() {
        ply--;
        int global = undoGlobal[ply];
        int local = undoLocal[ply];

        nextPlayer = undoPrevPlayer[ply];
        gameState = undoPrevGameState[ply];

        // Restore nextBoard zobrist
        zobrist ^= ZOBRIST_NEXT_BOARD[nextBoard < 0 ? 9 : nextBoard];
        nextBoard = undoPrevNextBoard[ply];
        zobrist ^= ZOBRIST_NEXT_BOARD[nextBoard < 0 ? 9 : nextBoard];

        // Restore local board state
        globalState[global] = undoPrevGlobalState[ply];

        // Remove stone
        int bit = 1 << local;
        if (nextPlayer == Player.X) {
            xBits[global] &= ~bit;
            zobrist ^= ZOBRIST_CELL[global][local][0];
        } else {
            oBits[global] &= ~bit;
            zobrist ^= ZOBRIST_CELL[global][local][1];
        }

        zobrist ^= ZOBRIST_SIDE;
    }

    private static byte encode(GameState s) {
        return switch (s) {
            case DRAWN -> 1;
            case WON_X -> 2;
            case WON_O -> 3;
            default -> 0;
        };
    }

    private GameState checkLocalWinner(int global, Player who) {
        int mask = (who == Player.X) ? xBits[global] : oBits[global];
        for (int w : WIN_MASKS) {
            if ((mask & w) == w) return GameState.won(who);
        }
        if ((xBits[global] | oBits[global]) == BOARD_MASK) return GameState.DRAWN;
        return GameState.IN_PLAY;
    }

    private GameState checkGlobalWinner(Player whoJustPlayed) {
        int xG = 0, oG = 0, filled = 0;
        for (int i = 0; i < 9; i++) {
            if (globalState[i] == 2) xG |= (1 << i);
            else if (globalState[i] == 3) oG |= (1 << i);
            if (globalState[i] != 0) filled |= (1 << i);
        }
        int mask = (whoJustPlayed == Player.X) ? xG : oG;
        for (int w : WIN_MASKS) {
            if ((mask & w) == w) return GameState.won(whoJustPlayed);
        }
        if (filled == BOARD_MASK) return GameState.DRAWN;
        return GameState.IN_PLAY;
    }

    private void recomputeZobrist() {
        long h = 0;
        for (int g = 0; g < 9; g++) {
            for (int l = 0; l < 9; l++) {
                if ((xBits[g] & (1 << l)) != 0) h ^= ZOBRIST_CELL[g][l][0];
                if ((oBits[g] & (1 << l)) != 0) h ^= ZOBRIST_CELL[g][l][1];
            }
        }
        h ^= ZOBRIST_NEXT_BOARD[nextBoard < 0 ? 9 : nextBoard];
        if (nextPlayer == Player.O) h ^= ZOBRIST_SIDE;
        zobrist = h;
    }

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        for (int bigRow = 0; bigRow < 3; bigRow++) {
            for (int smallRow = 0; smallRow < 3; smallRow++) {
                for (int bigCol = 0; bigCol < 3; bigCol++) {
                    int g = bigRow * 3 + bigCol;
                    for (int smallCol = 0; smallCol < 3; smallCol++) {
                        int l = smallRow * 3 + smallCol;
                        CellState c = getCell(g, l);
                        sb.append(switch (c) {
                            case X -> 'X';
                            case O -> 'O';
                            default -> '.';
                        });
                        if (smallCol < 2) sb.append(' ');
                    }
                    if (bigCol < 2) sb.append(" | ");
                }
                sb.append('\n');
            }
            if (bigRow < 2) sb.append("------+-------+------\n");
        }
        sb.append("Next: ").append(nextPlayer.asString());
        if (nextBoard >= 0) sb.append(" must play in board ").append((char) ('a' + nextBoard));
        else sb.append(" (any board)");
        sb.append("  State: ").append(gameState);
        return sb.toString();
    }

    @Override
    public Board clone() {
        return new Board(this);
    }
}

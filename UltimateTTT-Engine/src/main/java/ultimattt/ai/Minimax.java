package ultimattt.ai;

import ultimattt.game.Board;
import ultimattt.game.CellState;
import ultimattt.game.GameState;
import ultimattt.game.Move;
import ultimattt.game.Player;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized minimax for Ultimate Tic-Tac-Toe.
 *
 * Safety (Tier 0): always returns a legal move; two-tier soft/hard deadlines;
 * overhead buffer; frequent abort checks.
 *
 * Performance (Tier 1): works with mutable Board make/unmake + incremental
 * Zobrist; fixed-size open-addressing TT; preallocated move buffers.
 *
 * Search quality (Tier 2): PVS, killer moves, history heuristic, mate-distance
 * scoring, single-reply extensions.
 *
 * Hard constraint: never exceed the caller-supplied time budget (~9s).
 */
public class Minimax {

    public static class Result {
        public final Move bestMove;
        public final int score;
        public final long nodes;
        public final int depth;

        public Result(Move bestMove, int score, long nodes, int depth) {
            this.bestMove = bestMove;
            this.score = score;
            this.nodes = nodes;
            this.depth = depth;
        }
    }

    // ---- TT: fixed-size open addressing (no boxing, no resize) ----
    private static final int TT_SIZE = 1 << 22; // ~4M entries
    private static final int TT_MASK = TT_SIZE - 1;
    private final long[] ttKeys = new long[TT_SIZE];
    private final int[] ttDepth = new int[TT_SIZE];
    private final int[] ttScore = new int[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];
    private final int[] ttMovePacked = new int[TT_SIZE]; // global*9+local, or -1

    private static final byte TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    // ---- Search state ----
    private final AtomicLong nodes = new AtomicLong();
    private final AtomicBoolean forceStop = new AtomicBoolean();
    private long softDeadlineNanos;
    private long hardDeadlineNanos;

    private final Move[][] killers = new Move[64][2];
    private final int[][] history = new int[9][9];

    // Per-thread-ish move buffers (reused)
    private final Move[][] moveBuf = new Move[64][];
    {
        for (int i = 0; i < 64; i++) moveBuf[i] = new Move[81];
    }

    private static final int INF = 1_000_000;
    private static final int WIN_SCORE = 100_000;

    private static final int[] LOCAL_POS = {
            3, 2, 3, 2, 4, 2, 3, 2, 3
    };
    private static final int[] GLOBAL_WEIGHT = {
            3, 2, 3, 2, 5, 2, 3, 2, 3
    };
    private static final int[] WIN_MASKS = {
            0b000000111, 0b000111000, 0b111000000,
            0b001001001, 0b010010010, 0b100100100,
            0b100010001, 0b001010100
    };

    public Result search(Board rootBoard, int maxDepth, long timeLimitMs) {
        // Clear TT lightly (don't zero entire arrays every time - just rely on key mismatch)
        // For safety on first moves we can leave stale entries; key check handles it.
        nodes.set(0);
        forceStop.set(false);
        for (int i = 0; i < 64; i++) {
            killers[i][0] = killers[i][1] = null;
        }
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) history[i][j] = 0;
        }

        // Tier 0.3: reserve overhead margin so wall-clock stays under competition limit
        long budgetMs = Math.max(500, Math.min(timeLimitMs, 8800));
        long start = System.nanoTime();
        softDeadlineNanos = start + (long) (budgetMs * 0.88) * 1_000_000L;
        hardDeadlineNanos = start + (long) (budgetMs * 0.96) * 1_000_000L;

        Board board = new Board(rootBoard); // working copy

        Move[] rootBuf = moveBuf[0];
        int rootCount = board.legalMoves(rootBuf);
        if (rootCount == 0) {
            return new Result(null, 0, 0, 0);
        }

        // Tier 0.1: always have a legal move ready before any search
        Move best = rootBuf[0];
        int bestH = moveHeuristic(board, best);
        for (int i = 1; i < rootCount; i++) {
            int h = moveHeuristic(board, rootBuf[i]);
            if (h > bestH) {
                bestH = h;
                best = rootBuf[i];
            }
        }
        int bestScore = 0;
        int reachedDepth = 0;
        long lastDepthTimeNs = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            long now = System.nanoTime();
            if (now >= softDeadlineNanos || forceStop.get()) break;

            // Tier 0.2: don't start a depth we probably can't finish
            if (depth > 1 && lastDepthTimeNs > 0) {
                long estimate = lastDepthTimeNs * 5; // conservative branching
                if (now + estimate > softDeadlineNanos) break;
            }

            long depthStart = System.nanoTime();
            int alpha = -INF, beta = INF;

            // Aspiration after depth 3
            if (depth >= 4 && Math.abs(bestScore) < WIN_SCORE / 2) {
                int window = 30 + depth * 4;
                alpha = bestScore - window;
                beta = bestScore + window;
            }

            Move currentBest = null;
            int currentScore = -INF;
            boolean completed = true;

            for (int attempt = 0; attempt < 3; attempt++) {
                currentBest = null;
                currentScore = -INF;
                int a = alpha, b = beta;
                completed = true;

                // Order root moves
                orderMovesInPlace(rootBuf, rootCount, best, 0, board);

                for (int i = 0; i < rootCount; i++) {
                    if (System.nanoTime() >= hardDeadlineNanos || forceStop.get()) {
                        completed = false;
                        break;
                    }
                    Move m = rootBuf[i];
                    board.makeMove(m);
                    int score;
                    if (i == 0) {
                        score = -negamax(board, depth - 1, -b, -a, board.getNextPlayer(), 1);
                    } else {
                        // PVS null window
                        score = -negamax(board, depth - 1, -a - 1, -a, board.getNextPlayer(), 1);
                        if (!forceStop.get() && score > a && score < b) {
                            score = -negamax(board, depth - 1, -b, -a, board.getNextPlayer(), 1);
                        }
                    }
                    board.unmakeMove();

                    if (forceStop.get()) {
                        completed = false;
                        break;
                    }
                    if (score > currentScore) {
                        currentScore = score;
                        currentBest = m;
                    }
                    a = Math.max(a, score);
                    if (a >= b) break;
                }

                if (!completed) break;

                if (currentScore <= alpha) {
                    alpha = -INF;
                    beta = currentScore + 1;
                } else if (currentScore >= beta) {
                    alpha = currentScore - 1;
                    beta = INF;
                } else {
                    break;
                }
            }

            lastDepthTimeNs = System.nanoTime() - depthStart;

            if (completed && currentBest != null) {
                best = currentBest;
                bestScore = currentScore;
                reachedDepth = depth;
            } else if (currentBest != null && currentScore > bestScore - 30) {
                best = currentBest;
                bestScore = currentScore;
            }

            if (Math.abs(bestScore) >= WIN_SCORE - 200) break;
        }

        return new Result(best, bestScore, nodes.get(), reachedDepth);
    }

    private int negamax(Board board, int depth, int alpha, int beta, Player perspective, int ply) {
        nodes.incrementAndGet();
        if ((nodes.get() & 0x7F) == 0) {
            if (System.nanoTime() >= hardDeadlineNanos) {
                forceStop.set(true);
                return 0;
            }
        }
        if (forceStop.get()) return 0;

        if (board.isTerminal()) {
            return evaluateTerminal(board, perspective, ply);
        }

        // Single-reply extension (Tier 2.4)
        int ext = 0;
        if (depth > 0 && board.legalMoveCount() == 1) {
            ext = 1;
        }

        if (depth + ext <= 0) {
            return evaluate(board, perspective);
        }

        long key = board.zobristKey();
        int idx = (int) (key & TT_MASK);
        if (ttKeys[idx] == key && ttDepth[idx] >= depth) {
            int s = ttScore[idx];
            byte f = ttFlag[idx];
            if (f == TT_EXACT) return s;
            if (f == TT_LOWER) alpha = Math.max(alpha, s);
            else if (f == TT_UPPER) beta = Math.min(beta, s);
            if (alpha >= beta) return s;
        }

        Move[] buf = moveBuf[Math.min(ply, 63)];
        int count = board.legalMoves(buf);
        if (count == 0) return evaluate(board, perspective);

        Move ttMove = null;
        if (ttKeys[idx] == key && ttMovePacked[idx] >= 0) {
            int p = ttMovePacked[idx];
            ttMove = Move.of(p / 9, p % 9);
        }
        orderMovesInPlace(buf, count, ttMove, ply, board);

        int bestScore = -INF;
        Move bestMove = null;
        byte flag = TT_UPPER;
        boolean first = true;

        for (int i = 0; i < count; i++) {
            Move m = buf[i];
            board.makeMove(m);
            int score;
            if (first) {
                score = -negamax(board, depth + ext - 1, -beta, -alpha, perspective.other(), ply + 1);
                first = false;
            } else {
                score = -negamax(board, depth + ext - 1, -alpha - 1, -alpha, perspective.other(), ply + 1);
                if (!forceStop.get() && score > alpha && score < beta) {
                    score = -negamax(board, depth + ext - 1, -beta, -alpha, perspective.other(), ply + 1);
                }
            }
            board.unmakeMove();
            if (forceStop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = m;
            }
            if (score > alpha) {
                alpha = score;
                flag = TT_EXACT;
            }
            if (alpha >= beta) {
                flag = TT_LOWER;
                if (ply < 64) {
                    if (!m.equals(killers[ply][0])) {
                        killers[ply][1] = killers[ply][0];
                        killers[ply][0] = m;
                    }
                }
                history[m.global()][m.local()] += (depth + 1) * (depth + 1);
                break;
            }
        }

        // Store TT (always-replace / depth preferred simple)
        if (ttKeys[idx] != key || ttDepth[idx] <= depth) {
            ttKeys[idx] = key;
            ttDepth[idx] = depth;
            ttScore[idx] = bestScore;
            ttFlag[idx] = flag;
            ttMovePacked[idx] = bestMove != null ? bestMove.global() * 9 + bestMove.local() : -1;
        }
        return bestScore;
    }

    private void orderMovesInPlace(Move[] buf, int count, Move ttBest, int ply, Board board) {
        // Simple insertion-ish: score and selection sort for small N (UTTT branching is modest)
        for (int i = 0; i < count; i++) {
            int bestIdx = i;
            int bestSc = scoreMove(buf[i], ttBest, ply, board);
            for (int j = i + 1; j < count; j++) {
                int sc = scoreMove(buf[j], ttBest, ply, board);
                if (sc > bestSc) {
                    bestSc = sc;
                    bestIdx = j;
                }
            }
            if (bestIdx != i) {
                Move tmp = buf[i];
                buf[i] = buf[bestIdx];
                buf[bestIdx] = tmp;
            }
        }
    }

    private int scoreMove(Move m, Move ttBest, int ply, Board board) {
        if (ttBest != null && m.equals(ttBest)) return 2_000_000;
        if (ply < 64) {
            if (killers[ply][0] != null && m.equals(killers[ply][0])) return 1_500_000;
            if (killers[ply][1] != null && m.equals(killers[ply][1])) return 1_400_000;
        }
        return history[m.global()][m.local()] + moveHeuristic(board, m);
    }

    private int moveHeuristic(Board board, Move m) {
        int h = GLOBAL_WEIGHT[m.global()] * 12 + LOCAL_POS[m.local()] * 4;
        // Cheap static estimates only - avoid make/unmake here for speed at root
        return h;
    }

    private int evaluateTerminal(Board board, Player perspective, int ply) {
        GameState s = board.getGameState();
        if (s == GameState.DRAWN) return 0;
        // Mate distance: prefer faster wins / slower losses
        if (s.winner() == perspective) return WIN_SCORE - ply;
        return -WIN_SCORE + ply;
    }

    private int evaluate(Board board, Player perspective) {
        int score = 0;
        for (int g = 0; g < 9; g++) {
            GameState ls = board.getLocalState(g);
            int w = GLOBAL_WEIGHT[g];
            if (ls == GameState.WON_X) {
                score += (perspective == Player.X ? 140 : -140) * w;
            } else if (ls == GameState.WON_O) {
                score += (perspective == Player.O ? 140 : -140) * w;
            } else if (ls == GameState.IN_PLAY) {
                score += evaluateLocalThreats(board, g, perspective) * w;
            }
        }
        return score;
    }

    private int evaluateLocalThreats(Board board, int g, Player perspective) {
        int myBits = 0, oppBits = 0, empty = 0;
        for (int l = 0; l < 9; l++) {
            CellState c = board.getCell(g, l);
            if (c == CellState.X) {
                if (perspective == Player.X) myBits |= (1 << l);
                else oppBits |= (1 << l);
            } else if (c == CellState.O) {
                if (perspective == Player.O) myBits |= (1 << l);
                else oppBits |= (1 << l);
            } else {
                empty |= (1 << l);
            }
        }
        int score = 0;
        for (int l = 0; l < 9; l++) {
            if ((myBits & (1 << l)) != 0) score += LOCAL_POS[l];
            if ((oppBits & (1 << l)) != 0) score -= LOCAL_POS[l];
        }
        for (int mask : WIN_MASKS) {
            int my = Integer.bitCount(myBits & mask);
            int opp = Integer.bitCount(oppBits & mask);
            int emp = Integer.bitCount(empty & mask);
            if (my == 2 && emp == 1) score += 28;
            else if (my == 1 && emp == 2) score += 5;
            if (opp == 2 && emp == 1) score -= 28;
            else if (opp == 1 && emp == 2) score -= 5;
        }
        return score;
    }
}

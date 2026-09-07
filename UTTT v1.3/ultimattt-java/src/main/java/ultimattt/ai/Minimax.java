package ultimattt.ai;

import ultimattt.game.Board;
import ultimattt.game.CellState;
import ultimattt.game.GameState;
import ultimattt.game.Move;
import ultimattt.game.Player;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized single-threaded minimax for Ultimate Tic-Tac-Toe.
 * No worker threads - all search runs on the calling thread.
 *
 * Improvements for competition:
 *  - Stronger global-board threat evaluation
 *  - Deeper selective extensions on forced / low-branch lines
 *  - Complexity-seeking when the position is losing
 *  - Mate-distance scoring, PVS, killers, history, TT
 *  - Hard time guarantee under ~9s
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

    private static final int TT_SIZE = 1 << 22;
    private static final int TT_MASK = TT_SIZE - 1;
    private final long[] ttKeys = new long[TT_SIZE];
    private final int[] ttDepth = new int[TT_SIZE];
    private final int[] ttScore = new int[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];
    private final int[] ttMovePacked = new int[TT_SIZE];

    private static final byte TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    private final AtomicLong nodes = new AtomicLong();
    private final AtomicBoolean forceStop = new AtomicBoolean();
    private long softDeadlineNanos;
    private long hardDeadlineNanos;

    private final Move[][] killers = new Move[64][2];
    private final int[][] history = new int[9][9];
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
        nodes.set(0);
        forceStop.set(false);
        for (int i = 0; i < 64; i++) {
            killers[i][0] = killers[i][1] = null;
        }
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) history[i][j] = 0;
        }

        long budgetMs = Math.max(500, Math.min(timeLimitMs, 8800));
        long start = System.nanoTime();
        softDeadlineNanos = start + (long) (budgetMs * 0.88) * 1_000_000L;
        hardDeadlineNanos = start + (long) (budgetMs * 0.96) * 1_000_000L;

        Board board = new Board(rootBoard);

        Move[] rootBuf = moveBuf[0];
        int rootCount = board.legalMoves(rootBuf);
        if (rootCount == 0) {
            return new Result(null, 0, 0, 0);
        }

        // Always seed a legal move
        Move best = rootBuf[0];
        int bestH = rootMoveScore(board, best, 0);
        for (int i = 1; i < rootCount; i++) {
            int h = rootMoveScore(board, rootBuf[i], 0);
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

            if (depth > 1 && lastDepthTimeNs > 0) {
                long estimate = lastDepthTimeNs * 5;
                if (now + estimate > softDeadlineNanos) break;
            }

            long depthStart = System.nanoTime();
            int alpha = -INF, beta = INF;

            if (depth >= 4 && Math.abs(bestScore) < WIN_SCORE / 2) {
                int window = 35 + depth * 4;
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

                // When losing, prefer complexity (more open boards / more replies for opponent)
                boolean seekingComplexity = bestScore < -150;
                orderMovesInPlace(rootBuf, rootCount, best, 0, board, seekingComplexity);

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

                    // Complexity bonus only at root when already losing: prefer lines
                    // that leave the opponent with more legal replies (harder to finish)
                    if (seekingComplexity && score < -100) {
                        board.makeMove(m);
                        int replies = board.legalMoveCount();
                        board.unmakeMove();
                        score += Math.min(replies, 12); // small tie-break, does not invent wins
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

        // Selective extensions: forced single-reply, or very low branching
        int ext = 0;
        int mc = board.legalMoveCount();
        if (depth > 0) {
            if (mc == 1) ext = 2;          // forced sequence - search deeper
            else if (mc == 2 && depth <= 6) ext = 1;
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
        orderMovesInPlace(buf, count, ttMove, ply, board, false);

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

        if (ttKeys[idx] != key || ttDepth[idx] <= depth) {
            ttKeys[idx] = key;
            ttDepth[idx] = depth;
            ttScore[idx] = bestScore;
            ttFlag[idx] = flag;
            ttMovePacked[idx] = bestMove != null ? bestMove.global() * 9 + bestMove.local() : -1;
        }
        return bestScore;
    }

    private void orderMovesInPlace(Move[] buf, int count, Move ttBest, int ply, Board board, boolean complexity) {
        for (int i = 0; i < count; i++) {
            int bestIdx = i;
            int bestSc = scoreMove(buf[i], ttBest, ply, board, complexity);
            for (int j = i + 1; j < count; j++) {
                int sc = scoreMove(buf[j], ttBest, ply, board, complexity);
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

    private int scoreMove(Move m, Move ttBest, int ply, Board board, boolean complexity) {
        if (ttBest != null && m.equals(ttBest)) return 2_000_000;
        if (ply < 64) {
            if (killers[ply][0] != null && m.equals(killers[ply][0])) return 1_500_000;
            if (killers[ply][1] != null && m.equals(killers[ply][1])) return 1_400_000;
        }
        int h = history[m.global()][m.local()] + rootMoveScore(board, m, complexity ? 1 : 0);
        // Critical send: avoid giving opponent an immediate local win on the target board
        h += criticalSendPenalty(board, m, board.getNextPlayer());
        return h;
    }

    /** Static + light tactical preference. complexityBias>0 prefers sending opp to open boards. */
    private int rootMoveScore(Board board, Move m, int complexityBias) {
        int h = GLOBAL_WEIGHT[m.global()] * 12 + LOCAL_POS[m.local()] * 4;
        if (complexityBias > 0) {
            h += (9 - GLOBAL_WEIGHT[m.local()]) * 3;
        }
        // Prefer sending opponent to a finished board only when we benefit from chaos;
        // otherwise prefer sending into quiet boards.
        int target = m.local();
        if (board.getLocalState(target) != GameState.IN_PLAY) {
            h += complexityBias > 0 ? 30 : -25;
        }
        return h;
    }

    private int evaluateTerminal(Board board, Player perspective, int ply) {
        GameState s = board.getGameState();
        if (s == GameState.DRAWN) return 0;
        if (s.winner() == perspective) return WIN_SCORE - ply;
        return -WIN_SCORE + ply;
    }

    /**
     * Competition-tuned evaluation (research-aligned):
     *  - Local ownership + threats (scaled down so meta dominates)
     *  - Strong meta two-in-a-rows / almost-game-wins
     *  - Bonus when a local win creates/blocks a meta threat
     *  - Free-move value (sent to finished board)
     *  - Mild penalty for orphan local wins (no meta line open)
     *  - Next-board quality
     */
    private int evaluate(Board board, Player perspective) {
        int score = 0;
        int myGlobal = 0, oppGlobal = 0, openMask = 0, drawnMask = 0;

        for (int g = 0; g < 9; g++) {
            GameState ls = board.getLocalState(g);
            int w = GLOBAL_WEIGHT[g];
            if (ls == GameState.WON_X) {
                if (perspective == Player.X) myGlobal |= (1 << g);
                else oppGlobal |= (1 << g);
            } else if (ls == GameState.WON_O) {
                if (perspective == Player.O) myGlobal |= (1 << g);
                else oppGlobal |= (1 << g);
            } else if (ls == GameState.IN_PLAY) {
                openMask |= (1 << g);
                // Local threats scaled modestly; meta terms carry more weight
                score += evaluateLocalThreats(board, g, perspective) * (2 + w);
            } else {
                drawnMask |= (1 << g);
            }
        }

        // Base ownership of finished boards (center heavier)
        for (int g = 0; g < 9; g++) {
            if ((myGlobal & (1 << g)) != 0) score += 80 * GLOBAL_WEIGHT[g];
            if ((oppGlobal & (1 << g)) != 0) score -= 80 * GLOBAL_WEIGHT[g];
        }

        // Meta threats: almost-game-win is huge
        score += globalThreatScore(myGlobal, oppGlobal, openMask);

        // Orphan local wins: board won but sits on no open meta line for us
        score += orphanWinPenalty(myGlobal, oppGlobal, openMask, drawnMask);

        // Free move: side to move can play anywhere (next board finished/drawn)
        int forced = board.getNextBoard();
        if (forced < 0 || board.getLocalState(forced) != GameState.IN_PLAY) {
            // Advantage to the player about to move
            score += 25;
        } else {
            // Quality of the forced board for the side to move
            score += nextBoardQuality(board, forced, perspective);
        }

        return score;
    }

    /**
     * Meta-board threats. Scales match literature (meta >> local noise).
     * 2-in-a-row with one open board left ~= near win of the whole game.
     */
    private int globalThreatScore(int my, int opp, int open) {
        int s = 0;
        for (int mask : WIN_MASKS) {
            int mine = Integer.bitCount(my & mask);
            int theirs = Integer.bitCount(opp & mask);
            int empties = Integer.bitCount(open & mask);
            // Our threats
            if (theirs == 0) {
                if (mine == 2 && empties == 1) s += 420;      // one local win from winning game
                else if (mine == 2 && empties == 0) s += 0;  // blocked/drawn third
                else if (mine == 1 && empties == 2) s += 55;
                else if (mine == 1 && empties == 1) s += 15;
            }
            // Opponent threats
            if (mine == 0) {
                if (theirs == 2 && empties == 1) s -= 420;
                else if (theirs == 1 && empties == 2) s -= 55;
                else if (theirs == 1 && empties == 1) s -= 15;
            }
            // Contested lines (both have presence) - mild tension
            if (mine >= 1 && theirs >= 1) {
                // already blocked for pure 3-in-a-row
            }
        }
        return s;
    }

    /**
     * Mild penalty if we own boards that do not participate in any open meta line.
     * Discourages collecting "pretty" local wins that never threaten the game.
     */
    private int orphanWinPenalty(int my, int opp, int open, int drawn) {
        int s = 0;
        for (int g = 0; g < 9; g++) {
            if ((my & (1 << g)) == 0) continue;
            boolean useful = false;
            for (int mask : WIN_MASKS) {
                if ((mask & (1 << g)) == 0) continue;
                // line has no opponent win and at least one open (or our other win)
                if ((opp & mask) != 0) continue;
                int rest = mask & ~(1 << g);
                if ((open & rest) != 0 || (my & rest) != 0) {
                    useful = true;
                    break;
                }
            }
            if (!useful) s -= 35; // orphan
        }
        for (int g = 0; g < 9; g++) {
            if ((opp & (1 << g)) == 0) continue;
            boolean useful = false;
            for (int mask : WIN_MASKS) {
                if ((mask & (1 << g)) == 0) continue;
                if ((my & mask) != 0) continue;
                int rest = mask & ~(1 << g);
                if ((open & rest) != 0 || (opp & rest) != 0) {
                    useful = true;
                    break;
                }
            }
            if (!useful) s += 35; // their orphan is good for us
        }
        return s;
    }

    /** Prefer forcing opponent into weak/dead-ish boards; prefer receiving a rich board. */
    private int nextBoardQuality(Board board, int g, Player perspective) {
        GameState ls = board.getLocalState(g);
        if (ls != GameState.IN_PLAY) return 0;
        // Local threat snapshot for the forced board
        int local = evaluateLocalThreats(board, g, perspective);
        // Being forced into a board where opponent already has 2-in-a-row is bad
        return local / 2;
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
            else if (my == 1 && emp == 2) score += 4;
            if (opp == 2 && emp == 1) score -= 32; // blocking slightly more urgent
            else if (opp == 1 && emp == 2) score -= 4;
        }
        return score;
    }

    /**
     * Critical-send penalty for move ordering: playing local square L sends opponent
     * to board L. If board L is open and opponent already has a 2-in-a-row there,
     * this move is extremely dangerous.
     */
    private int criticalSendPenalty(Board board, Move m, Player toMove) {
        int target = m.local(); // send opponent to this global board
        GameState ls = board.getLocalState(target);
        if (ls != GameState.IN_PLAY) {
            // Sending to dead board = free move for opponent - often bad unless we must
            return -40;
        }
        // Does opponent have a local 2-in-a-row on target board?
        Player opp = toMove.other();
        int oppBits = 0, empty = 0;
        for (int l = 0; l < 9; l++) {
            CellState c = board.getCell(target, l);
            if (c == CellState.EMPTY) empty |= (1 << l);
            else if ((opp == Player.X && c == CellState.X) || (opp == Player.O && c == CellState.O))
                oppBits |= (1 << l);
        }
        for (int mask : WIN_MASKS) {
            if (Integer.bitCount(oppBits & mask) == 2 && Integer.bitCount(empty & mask) == 1)
                return -800; // almost gives opponent the local board for free
        }
        return 0;
    }
}

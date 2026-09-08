package ultimattt.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ultimattt.ai.Minimax;
import ultimattt.ai.OpeningBook;
import ultimattt.game.Board;
import ultimattt.game.Move;
import ultimattt.game.Notation;
import ultimattt.game.Player;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(name = "ultimattt",
        mixinStandardHelpOptions = true,
        version = "ultimattt-java 1.1",
        description = "Ultimate Tic-Tac-Toe (Java port of nelhage/ultimattt)",
        subcommands = {Main.PrettyPrint.class, Main.Play.class, Main.Analyze.class})
public class Main implements Callable<Integer> {

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        // Default action: start interactive play
        return new Play().call();
    }

    // ---------- pp ----------
    @Command(name = "pp", description = "Pretty-print a position")
    static class PrettyPrint implements Callable<Integer> {
        @Parameters(index = "0", description = "Position in compact notation")
        String position;

        @Override
        public Integer call() {
            try {
                Board b = Notation.parse(position);
                System.out.println(b.toPrettyString());
                System.out.println();
                System.out.println("Compact: " + Notation.format(b));
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    // ---------- play ----------
    @Command(name = "play", description = "Play a game on the command line")
    static class Play implements Callable<Integer> {

        @Option(names = {"-x"}, description = "Player X: human or ai")
        String xPlayer;

        @Option(names = {"-o"}, description = "Player O: human or ai")
        String oPlayer;

        @Option(names = {"--depth"}, description = "Max search depth for AI", defaultValue = "24")
        int depth;

        @Option(names = {"--limit"}, description = "Time limit per move in ms (hard cap 15s)", defaultValue = "15000")
        long limitMs;

        @Option(names = {"--log"}, description = "Optional path to save full game transcript")
        String logFile;

        @Override
        public Integer call() {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream logStream = null;
            if (logFile != null && !logFile.isBlank()) {
                try {
                    logStream = new PrintStream(new FileOutputStream(logFile, false), true);
                    System.setOut(new TeePrintStream(originalOut, logStream));
                    System.setErr(new TeePrintStream(originalErr, logStream));
                    System.out.println("(logging to " + logFile + ")");
                } catch (Exception e) {
                    System.err.println("Could not open log file: " + e.getMessage());
                    logStream = null;
                }
            }
            try {
            Scanner sc = new Scanner(System.in);

            // Interactive side selection if not given on command line
            if (xPlayer == null && oPlayer == null) {
                System.out.println("=================================");
                System.out.println("   Ultimate Tic-Tac-Toe");
                System.out.println("=================================");
                System.out.println();
                System.out.println("Who should the BOT play?");
                System.out.println("  1) Bot plays X  (you play O)");
                System.out.println("  2) Bot plays O  (you play X)   [default]");
                System.out.println("  3) Bot vs Bot");
                System.out.print("Choice [2]: ");
                String choice = sc.nextLine().trim();
                if (choice.isEmpty()) choice = "2";

                switch (choice) {
                    case "1" -> {
                        xPlayer = "ai";
                        oPlayer = "human";
                    }
                    case "3" -> {
                        xPlayer = "ai";
                        oPlayer = "ai";
                    }
                    default -> {
                        xPlayer = "human";
                        oPlayer = "ai";
                    }
                }
            } else {
                if (xPlayer == null) xPlayer = "human";
                if (oPlayer == null) oPlayer = "ai";
            }

            Board board = new Board();
            Minimax ai = new Minimax();

            System.out.println();
            System.out.println("X = " + xPlayer.toUpperCase() + "   |   O = " + oPlayer.toUpperCase());
            System.out.println("AI settings: depth up to " + depth + ", time limit " + limitMs + " ms");
            System.out.println("Moves are two letters a-i (board + square), e.g. ae");
            System.out.println("Type 'moves' to list legal moves, 'quit' to exit.");
            System.out.println();

            while (!board.isTerminal()) {
                System.out.println(board.toPrettyString());
                System.out.println();

                Player toMove = board.getNextPlayer();
                boolean isHuman = (toMove == Player.X && xPlayer.equalsIgnoreCase("human"))
                        || (toMove == Player.O && oPlayer.equalsIgnoreCase("human"));

                Move move;
                if (isHuman) {
                    move = readHumanMove(sc, board);
                } else {
                    // Opening book: instant move if we know a strong line
                    Move bookMove = OpeningBook.probe(board);
                    if (bookMove != null) {
                        move = bookMove;
                        System.out.printf("AI (%s) plays %s   [opening book]%n",
                                toMove.asString(), move);
                    } else {
                        System.out.printf("AI (%s) thinking...%n", toMove.asString());
                        long start = System.currentTimeMillis();
                        Minimax.Result res = ai.search(board, depth, limitMs);
                        long took = System.currentTimeMillis() - start;
                        if (res.bestMove == null) {
                            System.out.println("AI has no moves!");
                            break;
                        }
                        move = res.bestMove;
                        System.out.printf("AI plays %s   (score=%d, depth=%d, nodes=%d, %d ms)%n",
                                move, res.score, res.depth, res.nodes, took);
                    }
                }

                try {
                    board = board.play(move);
                } catch (Exception e) {
                    System.err.println("Illegal move: " + e.getMessage());
                    if (isHuman) continue;
                    else break;
                }
            }

            System.out.println();
            System.out.println(board.toPrettyString());
            System.out.println();
            System.out.println("===== GAME OVER: " + board.getGameState() + " =====");
            if (logFile != null && !logFile.isBlank()) {
                System.out.println("(transcript saved to " + logFile + ")");
            }
            return 0;
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                if (logStream != null) logStream.close();
            }
        }

        private Move readHumanMove(Scanner sc, Board board) {
            List<Move> legal = board.legalMoves();
            while (true) {
                System.out.print("Your move (" + board.getNextPlayer().asString() + "): ");
                String line = sc.nextLine().trim().toLowerCase();
                if (line.equals("moves") || line.equals("l")) {
                    System.out.println("Legal: " + legal);
                    continue;
                }
                if (line.equals("quit") || line.equals("q")) {
                    System.exit(0);
                }
                try {
                    Move m = Move.fromNotation(line);
                    if (legal.contains(m)) return m;
                    System.out.println("Illegal move. Type 'moves' to list legal ones.");
                } catch (Exception e) {
                    System.out.println("Invalid notation. Use two letters a-i, e.g. ae");
                }
            }
        }
    }

    // ---------- analyze ----------
    @Command(name = "analyze", description = "Analyze a position with minimax")
    static class Analyze implements Callable<Integer> {

        @Parameters(index = "0", description = "Position (or empty for starting position)", arity = "0..1")
        String position;

        @Option(names = {"--depth"}, description = "Max depth", defaultValue = "24")
        int depth;

        @Option(names = {"--limit"}, description = "Time limit ms (hard cap 15s)", defaultValue = "15000")
        long limitMs;

        @Override
        public Integer call() {
            Board board;
            if (position == null || position.isBlank()) {
                board = new Board();
            } else {
                try {
                    board = Notation.parse(position);
                } catch (Exception e) {
                    System.err.println("Parse error: " + e.getMessage());
                    return 1;
                }
            }

            System.out.println(board.toPrettyString());
            System.out.println();
            System.out.printf("Analyzing (depth=%d, limit=%dms)...%n", depth, limitMs);

            Minimax ai = new Minimax();
            long start = System.currentTimeMillis();
            Minimax.Result res = ai.search(board, depth, limitMs);
            long took = System.currentTimeMillis() - start;

            if (res.bestMove == null) {
                System.out.println("No moves available (terminal position).");
            } else {
                System.out.printf("Best move: %s%n", res.bestMove);
                System.out.printf("Score: %d%n", res.score);
                System.out.printf("Depth reached: %d%n", res.depth);
                System.out.printf("Nodes: %d%n", res.nodes);
                System.out.printf("Time: %d ms%n", took);
            }
            return 0;
        }
    }

    /** Mirrors all output to console and to a log file. */
    static final class TeePrintStream extends PrintStream {
        private final PrintStream other;
        TeePrintStream(PrintStream primary, PrintStream other) {
            super(primary, true);
            this.other = other;
        }
        @Override public void write(int b) {
            super.write(b);
            other.write(b);
        }
        @Override public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            other.write(buf, off, len);
        }
        @Override public void flush() {
            super.flush();
            other.flush();
        }
    }
}

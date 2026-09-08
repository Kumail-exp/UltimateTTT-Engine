package ultimattt.ai;

import ultimattt.game.Board;
import ultimattt.game.CellState;
import ultimattt.game.Move;

import java.util.HashMap;
import java.util.Map;

/**
 * Opening book aligned with known UTTT theory + deeper engine analysis.
 * Root: ee (center-center) - academic/AI consensus best first move.
 * Follow-ups calculated with extended search on main lines.
 * Lookup is O(1); no effect on mid-game search cost.
 */
public final class OpeningBook {

    private static final Map<String, Move> BOOK = new HashMap<>();

    static {
        BOOK.put("Xx.................................................................................", Move.fromNotation("ee"));
        BOOK.put("Oe........................................X........................................", Move.fromNotation("ea"));
        BOOK.put("Xa....................................O...X........................................", Move.fromNotation("ai"));
        BOOK.put("Oi........X...........................O...X........................................", Move.fromNotation("ic"));
        BOOK.put("Xc........X...........................O...X.................................O......", Move.fromNotation("cc"));
        BOOK.put("Oc........X...........X...............O...X.................................O......", Move.fromNotation("cg"));
        BOOK.put("Xb.....................................O..X........................................", Move.fromNotation("bd"));
        BOOK.put("Of..............X......................O..X........................................", Move.fromNotation("ff"));
        BOOK.put("Xf..............X......................O..X.........O..............................", Move.fromNotation("fd"));
        BOOK.put("Od..............X......................O..X.......X.O..............................", Move.fromNotation("dd"));
        BOOK.put("Xc......................................O.X........................................", Move.fromNotation("cc"));
        BOOK.put("Oc....................X.................O.X........................................", Move.fromNotation("cg"));
        BOOK.put("Xg....................X...O.............O.X........................................", Move.fromNotation("ga"));
        BOOK.put("Oa....................X...O.............O.X.............X..........................", Move.fromNotation("ai"));
        BOOK.put("Xd.......................................OX........................................", Move.fromNotation("db"));
        BOOK.put("Of................................X......OX........................................", Move.fromNotation("ff"));
        BOOK.put("Xf................................X......OX.........O..............................", Move.fromNotation("fd"));
        BOOK.put("Od................................X......OX.......X.O..............................", Move.fromNotation("dc"));
        BOOK.put("Xf........................................XO.......................................", Move.fromNotation("fd"));
        BOOK.put("Of........................................XO........X..............................", Move.fromNotation("fd"));
        BOOK.put("Xd........................................XO......O.X..............................", Move.fromNotation("dd"));
        BOOK.put("Od..............................X.........XO......O.X..............................", Move.fromNotation("dg"));
        BOOK.put("Xg........................................X.O......................................", Move.fromNotation("ga"));
        BOOK.put("Oa........................................X.O...........X..........................", Move.fromNotation("ac"));
        BOOK.put("Xc..O.....................................X.O...........X..........................", Move.fromNotation("cc"));
        BOOK.put("Oc..O.................X...................X.O...........X..........................", Move.fromNotation("ci"));
        BOOK.put("Xh........................................X..O.....................................", Move.fromNotation("hd"));
        BOOK.put("Of........................................X..O........................X............", Move.fromNotation("ff"));
        BOOK.put("Xf........................................X..O......O.................X............", Move.fromNotation("fd"));
        BOOK.put("Od........................................X..O....X.O.................X............", Move.fromNotation("dd"));
        BOOK.put("Xi........................................X...O....................................", Move.fromNotation("ic"));
        BOOK.put("Oc........................................X...O.............................X......", Move.fromNotation("ca"));
        BOOK.put("Xa..................O.....................X...O.............................X......", Move.fromNotation("ag"));
        BOOK.put("Og......X...........O.....................X...O.............................X......", Move.fromNotation("gg"));
        BOOK.put("Oa....................................X............................................", Move.fromNotation("aa"));
        BOOK.put("XaO...................................X............................................", Move.fromNotation("ac"));
        BOOK.put("Xb.O..................................X............................................", Move.fromNotation("bf"));
        BOOK.put("Xc..O.................................X............................................", Move.fromNotation("cc"));
        BOOK.put("Oe......................X..........................................................", Move.fromNotation("ea"));
        BOOK.put("Xa......................X.............O............................................", Move.fromNotation("aa"));
        BOOK.put("Xb......................X..............O...........................................", Move.fromNotation("bf"));
        BOOK.put("Xc......................X...............O..........................................", Move.fromNotation("cg"));
        BOOK.put("Oe..........................................................X......................", Move.fromNotation("ec"));
        BOOK.put("Xa....................................O.....................X......................", Move.fromNotation("ac"));
        BOOK.put("Xb.....................................O....................X......................", Move.fromNotation("bd"));
        BOOK.put("Xc......................................O...................X......................", Move.fromNotation("ca"));
    }

    private OpeningBook() {}

    public static String key(Board b) {
        StringBuilder sb = new StringBuilder(83);
        sb.append(b.getNextPlayer().asString());
        sb.append(b.getNextBoard() < 0 ? 'x' : (char) ('a' + b.getNextBoard()));
        for (int g = 0; g < 9; g++) {
            for (int l = 0; l < 9; l++) {
                CellState c = b.getCell(g, l);
                sb.append(c == CellState.X ? 'X' : c == CellState.O ? 'O' : '.');
            }
        }
        return sb.toString();
    }

    public static Move probe(Board board) {
        return BOOK.get(key(board));
    }

    public static int size() {
        return BOOK.size();
    }
}

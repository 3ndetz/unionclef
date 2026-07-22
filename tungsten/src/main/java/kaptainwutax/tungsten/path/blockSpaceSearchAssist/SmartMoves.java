package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import java.util.ArrayList;
import java.util.List;

import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;

/**
 * Tungsten-native smart block-space move generation, modelled on baritone's
 * Movements (Traverse / Ascend / Descend / Parkour) but implemented on tungsten's
 * own world/shape API — baritone is NOT imported. For a feet position it emits
 * only the few VALID neighbour moves (each already walkable/landable) instead of
 * a blind r=8 scan, so the A* branching factor collapses and the search can route
 * stepped/gap terrain within its node budget.
 *
 * A "feet" position is the block the player's feet occupy; the player stands on
 * feet.down() with feet and feet.up() clear (head room).
 *
 * Standalone by design (this step): nothing calls it yet. It is wired into the
 * block-space search in a later, separately-tested step, so course A (the
 * staircase canary) cannot regress from adding this class.
 */
public final class SmartMoves {

    /** Max horizontal reach of a running parkour jump (flat). */
    private static final int MAX_PARKOUR = 4;
    /** Max safe drop generated as a Descend (deeper drops = fall damage). */
    private static final int MAX_DESCEND = 3;

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public static final class Move {
        public final BlockPos dest;   // feet position after the move
        public final double cost;
        public final boolean jump;    // needs a jump (ascend / parkour)
        Move(BlockPos dest, double cost, boolean jump) {
            this.dest = dest; this.cost = cost; this.jump = jump;
        }
    }

    private SmartMoves() {}

    /** A solid floor / collider is present at pos (something to stand on). */
    private static boolean solid(WorldView w, BlockPos pos) {
        return BlockShapeChecker.getShapeVolume(pos, w) > 0;
    }
    /** pos is clear for the body to occupy (no collision). */
    private static boolean passable(WorldView w, BlockPos pos) {
        return BlockShapeChecker.getShapeVolume(pos, w) == 0;
    }
    /** The player can stand with feet at pos: solid below, body + head clear. */
    private static boolean standable(WorldView w, BlockPos feet) {
        return solid(w, feet.down()) && passable(w, feet) && passable(w, feet.up());
    }

    /** All valid moves from a feet position, nearest/cheapest per direction. */
    public static List<Move> generate(WorldView w, BlockPos feet) {
        List<Move> moves = new ArrayList<>();
        boolean headClear = passable(w, feet.up().up()); // room to jump

        for (Direction d : HORIZONTALS) {
            BlockPos ahead = feet.offset(d);

            // --- Traverse: flat step into an adjacent standable cell. ---
            if (standable(w, ahead)) {
                moves.add(new Move(ahead, ActionCosts.WALK_ONE_BLOCK_COST, false));
                // a flat step exists here; no need for parkour/descend in this dir
                continue;
            }

            // --- Ascend: +1y step-up onto an adjacent higher cell. ---
            BlockPos up = ahead.up();
            if (headClear && standable(w, up)) {
                moves.add(new Move(up, ActionCosts.JUMP_ONE_BLOCK_COST, true));
                continue;
            }

            // --- Descend: step down 1..MAX_DESCEND onto the first solid floor. ---
            boolean descended = false;
            if (passable(w, ahead)) {
                for (int drop = 1; drop <= MAX_DESCEND; drop++) {
                    // the column we fall through must be clear
                    boolean columnClear = true;
                    for (int c = 0; c < drop; c++) {
                        if (!passable(w, ahead.down(c))) { columnClear = false; break; }
                    }
                    if (!columnClear) break;
                    BlockPos down = ahead.down(drop);
                    if (standable(w, down)) {
                        moves.add(new Move(down,
                                ActionCosts.WALK_ONE_BLOCK_COST + drop * ActionCosts.FALL_ONE_BLOCK_COST, false));
                        descended = true;
                        break;
                    }
                }
            }
            if (descended) continue;

            // --- Parkour (flat): running jump across a 2..MAX_PARKOUR gap to a
            //     same-level landing. Requires an immediate gap ahead. ---
            if (headClear && passable(w, ahead) && !solid(w, ahead.down())) {
                boolean landed = false;
                for (int dist = 2; dist <= MAX_PARKOUR; dist++) {
                    if (!flightClear(w, feet, d, dist)) break;
                    BlockPos land = feet.offset(d, dist);
                    if (standable(w, land)) {
                        moves.add(new Move(land,
                                ActionCosts.JUMP_ONE_BLOCK_COST + (dist - 1) * ActionCosts.PARKOUR_ONE_BLOCK_COST, true));
                        landed = true;
                        break;
                    }
                }
                if (landed) continue;
            }

            // --- Parkour-ascend: jump up +1y AND across a 2..3 gap (baritone's
            //     MovementParkour ascend variant). Covers "steep" terrain — a
            //     1-block step every OTHER cell (+2 across, +1 up). ---
            if (headClear) {
                for (int dist = 2; dist <= 3; dist++) {
                    if (!flightClear(w, feet, d, dist)) break;
                    BlockPos land = feet.offset(d, dist).up();
                    if (standable(w, land)) {
                        moves.add(new Move(land,
                                ActionCosts.JUMP_ONE_BLOCK_COST + (dist - 1) * ActionCosts.PARKOUR_ONE_BLOCK_COST, true));
                        break;
                    }
                }
            }
        }
        return moves;
    }

    /** The flight path over the gap is clear: every intermediate foot+head cell
     *  passable (so we don't clip a wall mid-jump). */
    private static boolean flightClear(WorldView w, BlockPos feet, Direction d, int dist) {
        for (int s = 1; s < dist; s++) {
            BlockPos mid = feet.offset(d, s);
            if (!passable(w, mid) || !passable(w, mid.up())) return false;
        }
        return true;
    }
}

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
 * ⛔ CORRECTED 2026-09-05: this used to say "nothing calls it yet... wired in later,
 * separately-tested step" -- stale. {@code generate} IS called, live, from
 * {@code BlockNode.getChildren} (behind {@code TungstenConfig.smartMoves}, default
 * {@code false}). See that flag's javadoc in TungstenConfig.java for the measured A/B
 * history: smartMoves ON currently fails nav_water (final_dist=25.5, 9 freezes) even
 * with the water moves below present and firing (smWater counter measured non-zero) --
 * an open, tracked investigation, not a reason to distrust this file's own logic in
 * isolation. Read that flag's comment before changing anything here; it has the
 * current state of the actual measurements.
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
    /**
     * The player can stand with feet at pos — measured on the REAL body
     * (0.6x1.8) against REAL collision shapes. The old test was
     * "solid below && volume==0 here && volume==0 above", where volume is an XZ
     * AREA with the height discarded: it accepted a cell floored by a slab and
     * capped 2 blocks up (1.5 real clearance), which the physics engine then
     * refused to execute. See PlayerFit.
     */
    private static boolean standable(WorldView w, BlockPos feet) {
        return kaptainwutax.tungsten.helpers.PlayerFit.standable(w, feet);
    }

    /** Water moves emitted: entering from the bank, and strokes once wet. Read as smWater. */
    public static volatile int waterMoves;

    /** Is this cell water we can move through? Lava is a hazard, not a route. */
    private static boolean isWater(WorldView w, BlockPos p) {
        return w.getBlockState(p).getBlock() == net.minecraft.block.Blocks.WATER;
    }

    /** All valid moves from a feet position, nearest/cheapest per direction. */
    public static List<Move> generate(WorldView w, BlockPos feet) {
        List<Move> moves = new ArrayList<>();
        boolean headClear = passable(w, feet.up().up()); // room to jump

        // WITH THE FEET WET, NOTHING BELOW CAN FIRE: every move here is built on `standable`, and
        // water is not standable. Six directions, because leaving water is as often vertical as
        // sideways. Counted separately so the next question -- do these survive into the executed
        // path -- can be asked of a number instead of a guess.
        if (isWater(w, feet)) {
            for (Direction d : HORIZONTALS) {
                BlockPos ahead = feet.offset(d);
                if (isWater(w, ahead) || standable(w, ahead)) {
                    moves.add(new Move(ahead, ActionCosts.SWIM_ONE_BLOCK_COST, false));
                    waterMoves++;
                }
            }
            if (isWater(w, feet.up()) || passable(w, feet.up())) {
                moves.add(new Move(feet.up(), ActionCosts.SWIM_ONE_BLOCK_COST, false));
                waterMoves++;
            }
            if (isWater(w, feet.down())) {
                moves.add(new Move(feet.down(), ActionCosts.SWIM_ONE_BLOCK_COST, false));
                waterMoves++;
            }
            return moves;
        }

        for (Direction d : HORIZONTALS) {
            BlockPos ahead = feet.offset(d);

            // --- Enter the water from the bank. ---
            // Without this edge the search stops at the shore: a water cell is not standable, so
            // nothing below ever proposes it, and the swim branch above only helps once already in.
            if (isWater(w, ahead) && passable(w, ahead.up())) {
                moves.add(new Move(ahead, ActionCosts.SWIM_ONE_BLOCK_COST, false));
                waterMoves++;
                continue;
            }

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

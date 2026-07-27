package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import java.util.ArrayList;
import java.util.List;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.PlayerFit;
import kaptainwutax.tungsten.path.BreakRules;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlimeBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;

/**
 * THE block-space move generator. For a feet position it emits the handful of moves that
 * are actually possible from there, each already validated against the real player body
 * ({@link PlayerFit}, 0.6 x 1.8 against real collision shapes) and priced in TICKS.
 *
 * <p>WHY IT LOOKS LIKE THIS (2026-07-27 rework). There used to be TWO generators and you
 * had to pick your poison:
 * <ul>
 *   <li>a blind radius-8 spherical scan that produced ~1086 candidates per expansion
 *       (~15 000 in the deep retry) and filtered them through ~200 lines of {@code
 *       instanceof} special cases. It supported break/place, ladders and water, but was so
 *       expensive it exhausted its budget on anything non-trivial; and</li>
 *   <li>an early version of this class: cheap and body-accurate, but {@code
 *       BlockNode.getChildren} returned early to use it and therefore never reached the
 *       filter where the break and place hooks lived — so it could not mine, bridge,
 *       climb a ladder, swim or move diagonally.</li>
 * </ul>
 * Neither could pass the nav suite (baseline 3/10, docs/ai/nav-baseline-2026-07-27.md).
 * This is the merge: typed, pre-validated, cheap AND capability-complete.
 *
 * <p>Everything is emitted as a {@link Move}; break and place moves carry the cells the
 * executor must mine / pave, exactly as the old filter's plan hooks did.
 */
public final class SmartMoves {

    /** Max horizontal reach of a running parkour jump (flat). */
    private static final int MAX_PARKOUR = 4;
    /** Max drop generated as a plain Descend; deeper drops mean fall damage. */
    private static final int MAX_DESCEND = 3;
    /** How far a gravity-block column above a mined passage is paid for up front. */
    private static final int GRAVITY_SCAN = 5;

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** Diagonal offsets paired with the two cardinals that must BOTH be clear. */
    private static final int[][] DIAGONALS = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public static final class Move {
        public final BlockPos dest;   // feet position after the move
        public final double cost;     // TICKS
        public final boolean jump;    // needs a jump (ascend / parkour)
        /** Cells to mine before this move is walkable (top-down), or null. */
        public final List<BlockPos> toBreak;
        /** Cells to place before this move is walkable, or null. */
        public final List<BlockPos> toPlace;

        Move(BlockPos dest, double cost, boolean jump) {
            this(dest, cost, jump, null, null);
        }

        Move(BlockPos dest, double cost, boolean jump,
             List<BlockPos> toBreak, List<BlockPos> toPlace) {
            this.dest = dest;
            this.cost = cost;
            this.jump = jump;
            this.toBreak = toBreak;
            this.toPlace = toPlace;
        }
    }

    private SmartMoves() {}

    // ── primitives ───────────────────────────────────────────────────────────
    private static boolean solid(WorldView w, BlockPos pos) {
        return BlockShapeChecker.getShapeVolume(pos, w) > 0;
    }

    private static boolean passable(WorldView w, BlockPos pos) {
        return BlockShapeChecker.getShapeVolume(pos, w) == 0;
    }

    /** The player can stand with feet here — REAL body against REAL collision shapes. */
    private static boolean standable(WorldView w, BlockPos feet) {
        return PlayerFit.standable(w, feet) && !isLava(w, feet) && !isLava(w, feet.down());
    }

    private static boolean isLava(WorldView w, BlockPos pos) {
        return w.getBlockState(pos).isOf(Blocks.LAVA);
    }

    private static boolean isWater(WorldView w, BlockPos pos) {
        return BlockStateChecker.isAnyWater(w.getBlockState(pos));
    }

    private static boolean isLadder(WorldView w, BlockPos pos) {
        return w.getBlockState(pos).getBlock() instanceof LadderBlock;
    }

    private static boolean isSlime(WorldView w, BlockPos pos) {
        return w.getBlockState(pos).getBlock() instanceof SlimeBlock;
    }

    /** The flight path over a gap is clear: every intermediate foot+head cell passable. */
    private static boolean flightClear(WorldView w, BlockPos feet, Direction d, int dist) {
        for (int s = 1; s < dist; s++) {
            BlockPos mid = feet.offset(d, s);
            if (!passable(w, mid) || !passable(w, mid.up())) return false;
        }
        return true;
    }

    // ── entry point ──────────────────────────────────────────────────────────

    /**
     * All valid moves from {@code feet}.
     *
     * @param player       needed to price mining (tool in hand, hardness)
     * @param parentPlaced cells the PARENT node already plans to pave; a bridge cell's
     *                     planned floor counts as solid for the next step, which is what
     *                     lets ONE search plan a whole multi-cell bridge instead of
     *                     exploring endless 1-cell bridges until the budget dies.
     */
    public static List<Move> generate(WorldView w, BlockPos feet, PlayerEntity player,
                                      List<BlockPos> parentPlaced) {
        List<Move> moves = new ArrayList<>();
        boolean headClear = passable(w, feet.up().up());   // room to jump
        boolean inWater = isWater(w, feet);

        // ── water: swimming is its own little world ──────────────────────────
        if (inWater) {
            addWaterMoves(w, feet, moves);
            // a swimmer can also just climb out onto a normal ledge, so fall through
        }

        // ── ladders: vertical movement while on/against one ──────────────────
        if (isLadder(w, feet)) {
            BlockPos up = feet.up();
            if (isLadder(w, up) || standable(w, up)) {
                moves.add(new Move(up, ActionCosts.LADDER_ONE_BLOCK_COST, false));
            }
            BlockPos down = feet.down();
            if (isLadder(w, down) || standable(w, down)) {
                moves.add(new Move(down, ActionCosts.LADDER_ONE_BLOCK_COST, false));
            }
        }

        for (Direction d : HORIZONTALS) {
            BlockPos ahead = feet.offset(d);

            // stepping ONTO a ladder column (from the ground, to start climbing)
            if (isLadder(w, ahead) && passable(w, ahead.up())) {
                moves.add(new Move(ahead, ActionCosts.LADDER_ONE_BLOCK_COST, false));
                continue;
            }

            // --- Traverse: flat step into an adjacent standable cell ---
            if (standable(w, ahead)) {
                moves.add(new Move(ahead, ActionCosts.WALK_ONE_BLOCK_COST, false));
                continue;                       // cheapest option in this direction
            }

            // --- Ascend: +1y step-up ---
            BlockPos up = ahead.up();
            if (headClear && standable(w, up)) {
                moves.add(new Move(up, ActionCosts.JUMP_ONE_BLOCK_COST, true));
                continue;
            }

            // --- Descend: step down 1..MAX_DESCEND onto the first solid floor ---
            boolean descended = false;
            if (passable(w, ahead)) {
                for (int drop = 1; drop <= MAX_DESCEND; drop++) {
                    boolean columnClear = true;
                    for (int c = 0; c < drop; c++) {
                        if (!passable(w, ahead.down(c))) { columnClear = false; break; }
                    }
                    if (!columnClear) break;
                    BlockPos down = ahead.down(drop);
                    if (standable(w, down)) {
                        moves.add(new Move(down, ActionCosts.WALK_ONE_BLOCK_COST
                                + drop * ActionCosts.FALL_ONE_BLOCK_COST, false));
                        descended = true;
                        break;
                    }
                }
            }
            if (descended) continue;

            // --- Slime bounce: dropping onto slime throws us back up, so a ledge
            //     that is too high to jump becomes reachable. ---
            if (passable(w, ahead)) {
                for (int drop = 1; drop <= 6; drop++) {
                    BlockPos below = ahead.down(drop);
                    if (!passable(w, below)) {
                        if (isSlime(w, below)) {
                            addSlimeBounce(w, ahead.down(drop - 1), moves);
                        }
                        break;
                    }
                }
            }

            // --- Parkour (flat): running jump across a 2..MAX_PARKOUR gap ---
            boolean landed = false;
            if (headClear && passable(w, ahead) && !solid(w, ahead.down())) {
                for (int dist = 2; dist <= MAX_PARKOUR; dist++) {
                    if (!flightClear(w, feet, d, dist)) break;
                    BlockPos land = feet.offset(d, dist);
                    if (standable(w, land)) {
                        moves.add(new Move(land, ActionCosts.JUMP_ONE_BLOCK_COST
                                + (dist - 1) * ActionCosts.PARKOUR_ONE_BLOCK_COST, true));
                        landed = true;
                        break;
                    }
                }
            }
            if (landed) continue;

            // --- Parkour-ascend: jump up +1y AND across a 2..3 gap (steep terrain:
            //     a 1-block step every OTHER cell). ---
            if (headClear) {
                for (int dist = 2; dist <= 3; dist++) {
                    if (!flightClear(w, feet, d, dist)) break;
                    BlockPos land = feet.offset(d, dist).up();
                    if (standable(w, land)) {
                        moves.add(new Move(land, ActionCosts.JUMP_ONE_BLOCK_COST
                                + (dist - 1) * ActionCosts.PARKOUR_ONE_BLOCK_COST, true));
                        break;
                    }
                }
            }

            // --- Break-through: the cell is blocked only by breakable blocks ---
            addBreakMove(w, feet, d, player, moves);

            // --- Place/bridge: the cell is a gap we can pave ---
            addBridgeMove(w, feet, d, parentPlaced, moves);
        }

        // --- Diagonals: cheap extra connectivity on open ground. Both shared
        //     cardinals must be clear so we never clip a corner. ---
        for (int[] dg : DIAGONALS) {
            BlockPos dest = feet.add(dg[0], 0, dg[1]);
            if (!standable(w, dest)) continue;
            BlockPos sideA = feet.add(dg[0], 0, 0);
            BlockPos sideB = feet.add(0, 0, dg[1]);
            if (!passable(w, sideA) || !passable(w, sideA.up())) continue;
            if (!passable(w, sideB) || !passable(w, sideB.up())) continue;
            moves.add(new Move(dest,
                    ActionCosts.WALK_ONE_BLOCK_COST * ActionCosts.DIAGONAL_MULTIPLIER, false));
        }

        // --- Pillar up: place a block under our own feet to gain a level. The way
        //     onto a 2-block wall when there is no ramp. ---
        addPillarMove(w, feet, moves);

        return moves;
    }

    // ── water ────────────────────────────────────────────────────────────────
    private static void addWaterMoves(WorldView w, BlockPos feet, List<Move> moves) {
        // swim to any adjacent water cell, and up/down through the column
        BlockPos[] around = {
                feet.north(), feet.south(), feet.east(), feet.west(), feet.up(), feet.down()
        };
        for (BlockPos p : around) {
            if (isWater(w, p)) {
                moves.add(new Move(p, ActionCosts.SWIM_ONE_BLOCK_COST, false));
            } else if (p.getY() >= feet.getY() && passable(w, p) && passable(w, p.up())) {
                // surfacing, or climbing out onto a bank
                moves.add(new Move(p, ActionCosts.SWIM_ONE_BLOCK_COST, false));
            }
        }
    }

    // ── slime ────────────────────────────────────────────────────────────────
    private static void addSlimeBounce(WorldView w, BlockPos aboveSlime, List<Move> moves) {
        // A bounce carries us up several blocks; offer the reachable ledges above.
        for (int rise = 1; rise <= 6; rise++) {
            BlockPos land = aboveSlime.up(rise);
            if (!passable(w, land)) break;
            for (Direction d : HORIZONTALS) {
                BlockPos ledge = land.offset(d);
                if (standable(w, ledge)) {
                    moves.add(new Move(ledge,
                            ActionCosts.JUMP_ONE_BLOCK_COST + rise * ActionCosts.FALL_ONE_BLOCK_COST,
                            true));
                }
            }
        }
    }

    // ── break ────────────────────────────────────────────────────────────────
    /**
     * Mine through into the adjacent cell. Unlike the old filter this also handles the
     * ASCEND and DESCEND variants, so the bot can dig its way up a step or down a slope
     * instead of only sideways on one level — the reason `@gamer` mining was not
     * expressible at all.
     */
    private static void addBreakMove(WorldView w, BlockPos feet, Direction d,
                                     PlayerEntity player, List<Move> moves) {
        if (!TungstenConfig.get().allowBreak || player == null) return;

        for (int dy : new int[]{0, 1, -1}) {
            BlockPos dest = feet.offset(d).up(dy);
            // need something to stand on over there (or the floor is itself minable-through)
            if (!solid(w, dest.down())) continue;
            if (isLava(w, dest) || isLava(w, dest.up())) continue;

            List<BlockPos> plan = new ArrayList<>();
            double ticks = 0;
            boolean impossible = false;
            // head first: break order matters, a top block falls into the gap otherwise
            for (BlockPos pos : new BlockPos[]{dest.up(), dest}) {
                if (passable(w, pos)) continue;
                double t = breakTicks(w, pos, player);
                if (t < 0) { impossible = true; break; }
                ticks += t;
                plan.add(pos);
            }
            if (impossible || plan.isEmpty()) continue;

            // gravity blocks above the passage will fall into it — pay for them now
            BlockPos above = dest.up(2);
            for (int i = 0; i < GRAVITY_SCAN; i++) {
                BlockState st = w.getBlockState(above);
                if (!(st.getBlock() instanceof net.minecraft.block.FallingBlock)) break;
                double t = breakTicks(w, above, player);
                if (t < 0) { impossible = true; break; }
                ticks += t;
                above = above.up();
            }
            if (impossible) continue;

            double cost = ActionCosts.WALK_ONE_BLOCK_COST
                    + ticks * TungstenConfig.get().breakCostMultiplier;
            moves.add(new Move(dest, cost, dy > 0, plan, null));
        }
    }

    /** Vanilla mining duration in ticks, or -1 when the rules forbid breaking it. */
    private static double breakTicks(WorldView w, BlockPos pos, PlayerEntity player) {
        BlockState state = w.getBlockState(pos);
        if (!BreakRules.canBreak(w, pos, state)) return -1;
        float delta = state.calcBlockBreakingDelta(player, w, pos);
        if (delta <= 0) return -1;
        if (delta >= 1) return 1;
        return Math.ceil(1f / delta);
    }

    // ── place ────────────────────────────────────────────────────────────────
    /** Pave the floor of an adjacent gap cell and step onto it (bridging). */
    private static void addBridgeMove(WorldView w, BlockPos feet, Direction d,
                                      List<BlockPos> parentPlaced, List<Move> moves) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.planPlaceMoves || !cfg.allowPlace) return;

        BlockPos dest = feet.offset(d);
        BlockPos support = dest.down();
        if (!passable(w, dest) || !passable(w, dest.up())) return;   // body must fit
        if (solid(w, support)) return;                                // already floored
        if (!PlaceRules.canPlace(w, support)) return;

        // We must have something to stand on to place from. CHAINING: if this cell's own
        // floor is itself a planned bridge cell, it will have been paved by the time we
        // get here, so one search can plan a whole multi-cell bridge.
        BlockPos myFloor = feet.down();
        boolean floorSolid = solid(w, myFloor);
        boolean floorPlanned = parentPlaced != null && parentPlaced.contains(myFloor);
        if (!floorSolid && !floorPlanned) return;

        List<BlockPos> plan = new ArrayList<>();
        plan.add(support);
        moves.add(new Move(dest, ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_PENALTY,
                false, null, plan));
    }

    /**
     * Place a block under our own feet and rise one level (pillaring). This is what gets
     * the bot onto a 2-block wall with no ramp; the old search had NO upward place move at
     * all, so such a goal was simply unreachable no matter how many blocks it carried.
     */
    private static void addPillarMove(WorldView w, BlockPos feet, List<Move> moves) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.planPlaceMoves || !cfg.allowPlace) return;

        BlockPos dest = feet.up();
        if (!passable(w, dest) || !passable(w, dest.up())) return;   // room to rise
        if (!solid(w, feet.down()) && !isWater(w, feet)) return;      // something to jump off
        if (!PlaceRules.canPlace(w, feet)) return;

        List<BlockPos> plan = new ArrayList<>();
        plan.add(feet);          // the block goes where our feet currently are
        moves.add(new Move(dest, ActionCosts.JUMP_ONE_BLOCK_COST + ActionCosts.JUMP_PENALTY,
                true, null, plan));
    }
}

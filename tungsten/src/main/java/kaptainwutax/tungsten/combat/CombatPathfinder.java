package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.block.*;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.*;

/**
 * Lightweight BFS pathfinder for combat — runs on block grid.
 *
 * Two paths maintained:
 *   attackPath  — shortest walkable safe route to target
 *   retreatPath — best escape route (far from target + high ground + safe)
 *
 * Hazard blocks (lava, fire, magma, campfire, cactus) are impassable.
 * Slowdown blocks (water, cobweb, soul sand, honey) get extra cost in BFS.
 *
 * Jump trajectories are visualized as arcs between waypoints.
 */
public class CombatPathfinder {

    private static final int MAX_RADIUS = 25;
    // 2000 nodes × deep block scans every 10 ticks on the client thread caused
    // visible hitching; combat paths are short, 800 is plenty
    private static final int MAX_NODES = 800;

    /** Shape of what bfsPath returns: calls, budget exhaustions, stubs (<2 cells), cells returned. */
    public static volatile int cpCalls, cpExhausted, cpStub, cpCells, cpDistinct;
    /** Diagonal neighbours withheld from a walking route the queue could not execute. */
    public static volatile int gridDiagonalDropped;
    /** Diagonals rewritten as two cardinal steps, and those with no passable corner. */
    public static volatile int gridDiagonalExpanded, gridDiagonalUnturnable;
    // Max horizontal reach of a running parkour jump (goto pathing only). A
    // sprint-jump clears ~4 flat / ~3 with a +1 rise.
    private static final int MAX_PARKOUR = 4;
    private static final Color COL_ATTACK    = new Color(255, 100, 50);  // orange
    private static final Color COL_RETREAT   = new Color(50, 150, 255);  // blue
    private static final Color COL_JUMP_ARC  = new Color(255, 220, 50);  // yellow arcs

    // sprint-jump covers ~4 blocks horizontal, ~1.25 up
    private static final double JUMP_HORIZ = 3.5;
    private static final int JUMP_ARC_SEGMENTS = 8;

    private List<BlockPos> attackPath = Collections.emptyList();
    private List<BlockPos> retreatPath = Collections.emptyList();
    private WorldView lastWorld = null;
    private int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 10;

    // ── tick ─────────────────────────────────────────────────────────────────

    public void tick(BlockPos playerPos, BlockPos targetPos, Vec3d enemyVelocity, WorldView world) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        // attack path targets predicted position (~20 ticks ahead based on enemy avg speed)
        // ⛔ THE VERTICAL COMPONENT USED TO GO IN HERE TOO, AND IT AIMED THE SEARCH UNDERGROUND.
        // Same bug as SafetySystem.java:249-263 (⛔ line corrected 2026-09-02, was :216 — that file
        // has grown since this was written; fixed there: the danger look-ahead extrapolated the bot
        // through the floor and read its own crit hop as a fatal fall) but with a 20-tick horizon
        // instead of 10,
        // so it is worse in scale: an opponent mid-jump carrying vy about -0.3 put this BFS target
        // roughly SIX BLOCKS UNDERGROUND. The search then tried to reach a cell inside terrain or in
        // void, which is the shape of the "Ran out of nodes" chase failures already noted in this
        // repo.
        // A pathfinding target has to be a walkable cell. Lead the enemy across the GROUND, which is
        // what a chase actually needs, and let the BFS resolve the height itself.
        Vec3d predicted = Vec3d.ofBottomCenter(targetPos)
                .add(enemyVelocity.x * 20, 0, enemyVelocity.z * 20);
        BlockPos predictedTarget = BlockPos.ofFloored(predicted);
        lastWorld = world;
        attackPath = bfsPath(playerPos, predictedTarget, world, false);

        // retreat path uses current target pos for "away from" scoring
        retreatPath = findRetreatPath(playerPos, targetPos, world);
    }

    // ── render ───────────────────────────────────────────────────────────────

    public void renderUpdate(float tickDelta) {
        renderPathWithJumps(attackPath, COL_ATTACK);
        renderPathWithJumps(retreatPath, COL_RETREAT);
    }

    private void renderPathWithJumps(List<BlockPos> path, Color col) {
        if (path.size() < 2) return;

        // find jump waypoints: points where a sprint-jump would land
        // skip intermediate blocks that fall within one jump distance
        List<BlockPos> jumpPoints = lastWorld != null
                ? computeJumpWaypoints(path, lastWorld)
                : computeJumpWaypointsSimple(path);

        // render waypoint cubes
        for (BlockPos bp : jumpPoints) {
            Vec3d center = Vec3d.ofBottomCenter(bp);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    center.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.15, 0.4), col));
        }

        // render jump arcs between waypoints
        for (int i = 0; i < jumpPoints.size() - 1; i++) {
            Vec3d from = Vec3d.ofBottomCenter(jumpPoints.get(i)).add(0, 0.1, 0);
            Vec3d to = Vec3d.ofBottomCenter(jumpPoints.get(i + 1)).add(0, 0.1, 0);
            renderJumpArc(from, to, COL_JUMP_ARC);
        }
    }

    /**
     * From a dense block path, pick waypoints a sprint-jump apart.
     * Verify LOS between consecutive waypoints — if blocked by wall,
     * insert intermediate point so jump arcs don't clip through blocks.
     */
    private List<BlockPos> computeJumpWaypoints(List<BlockPos> path, WorldView world) {
        List<BlockPos> waypoints = new ArrayList<>();
        waypoints.add(path.get(0));

        double accumulated = 0;
        int lastWpIndex = 0;

        for (int i = 1; i < path.size(); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            double step = Math.sqrt(prev.getSquaredDistance(curr));
            accumulated += step;

            if (accumulated >= JUMP_HORIZ || i == path.size() - 1) {
                // check LOS from last waypoint to candidate
                BlockPos lastWp = waypoints.get(waypoints.size() - 1);
                if (hasBlockLOS(lastWp, curr, world)) {
                    waypoints.add(curr);
                } else {
                    // LOS blocked — use midpoint from dense path
                    int midIndex = (lastWpIndex + i) / 2;
                    if (midIndex > lastWpIndex && midIndex < path.size()) {
                        waypoints.add(path.get(midIndex));
                    }
                    waypoints.add(curr);
                }
                lastWpIndex = i;
                accumulated = 0;
            }
        }
        return waypoints;
    }

    /** Simple LOS check between two block positions at eye height (+1.5). */
    private static boolean hasBlockLOS(BlockPos from, BlockPos to, WorldView world) {
        Vec3d start = Vec3d.ofBottomCenter(from).add(0, 1.5, 0);
        Vec3d end = Vec3d.ofBottomCenter(to).add(0, 1.5, 0);
        // manual raycast: step along line, check for solid blocks
        double dist = start.distanceTo(end);
        int steps = (int) Math.ceil(dist * 2);
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.floor(start.x + (end.x - start.x) * t);
            int y = (int) Math.floor(start.y + (end.y - start.y) * t);
            int z = (int) Math.floor(start.z + (end.z - start.z) * t);
            BlockPos check = new BlockPos(x, y, z);
            if (!check.equals(from) && !check.equals(to)
                    && !world.getBlockState(check).getCollisionShape(world, check).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Fallback: distance-only waypoints, no LOS check. */
    private List<BlockPos> computeJumpWaypointsSimple(List<BlockPos> path) {
        List<BlockPos> waypoints = new ArrayList<>();
        waypoints.add(path.get(0));
        double accumulated = 0;
        for (int i = 1; i < path.size(); i++) {
            accumulated += Math.sqrt(path.get(i - 1).getSquaredDistance(path.get(i)));
            if (accumulated >= JUMP_HORIZ || i == path.size() - 1) {
                waypoints.add(path.get(i));
                accumulated = 0;
            }
        }
        return waypoints;
    }

    /** Render a parabolic arc from → to (simple ballistic curve). */
    private void renderJumpArc(Vec3d from, Vec3d to, Color col) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double dy = to.y - from.y;

        // peak height: ~1.25 blocks above start for sprint-jump
        double peakH = 1.25 + Math.max(0, dy);

        Vec3d prev = from;
        for (int s = 1; s <= JUMP_ARC_SEGMENTS; s++) {
            double t = (double) s / JUMP_ARC_SEGMENTS;
            double x = from.x + dx * t;
            double z = from.z + dz * t;
            // parabola: y = start + peak * 4t(1-t) + dy*t
            double y = from.y + peakH * 4 * t * (1 - t) + dy * t;

            Vec3d curr = new Vec3d(x, y, z);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(prev, curr, col));
            prev = curr;
        }
    }

    // ── BFS ──────────────────────────────────────────────────────────────────

    /** When the no-expansion diagnosis above last printed; it repeats every tick otherwise. */
    private static volatile long lastNoExpandLogMs = 0L;

    private static List<BlockPos> bfsPath(BlockPos start, BlockPos goal, WorldView world, boolean allowParkour) {
        return bfsPath(start, goal, world, allowParkour, false);
    }

    private static List<BlockPos> bfsPath(BlockPos start, BlockPos goal, WorldView world,
                                          boolean allowParkour, boolean cardinalOnly) {
        // WHAT SHAPE DOES THIS ACTUALLY RETURN? Its own javadoc calls it an instant grid BFS
        // for FollowEntityTask to chase a nearby entity while the physics A* computes -- yet
        // the walking drive uses it as its route source (CustomBaritoneGoalTask:715). With
        // MAX_NODES=800 and survival goals fourteen blocks out through real terrain, it may
        // exhaust the budget every time and hand back a stub. Measured live during a stall:
        // mqRefused(short)=2337 against 2299 BFS ticks, one refusal per tick, while
        // FastPlanner produced healthy 48-cell routes that went nowhere (navRes=0).
        cpCalls++;
        if (start.equals(goal)) return Collections.emptyList();

        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(start);
        cameFrom.put(start, null);
        int explored = 0;
        // Decided once, from where the search begins: a bot already in the water is the only case
        // that gets floorless moves, so a land route can never start preferring a pond.
        boolean swimming = isLiquid(start, world) || isLiquid(start.up(), world);

        while (!queue.isEmpty() && explored < MAX_NODES) {
            BlockPos current = queue.poll();
            explored++;

            if (current.equals(goal) || current.isWithinDistance(goal, 1.5)) {
                return note(reconstructPath(cameFrom, current), explored);
            }

            for (BlockPos neighbor : getWalkableNeighbors(current, world, allowParkour, swimming, cardinalOnly)) {
                if (cameFrom.containsKey(neighbor)) continue;
                if (!start.isWithinDistance(neighbor, MAX_RADIUS)) continue;
                cameFrom.put(neighbor, current);
                queue.add(neighbor);
            }
        }

        // A SEARCH THAT NEVER LEFT ITS OWN CELL SHOULD SAY SO, AND SAY WHY.
        // "primDrive gridBFS sz1" appears in the hundreds on a failing @gamer run: the route is one
        // cell long, which means NOT ONE neighbour of the bot's own cell was accepted. Which of the
        // four tests in isWalkable rejected them is the whole question, and the answer is three
        // block states away -- but only at the moment it happens, so it is logged here rather than
        // guessed at later. Rate-limited to once a second: the situation repeats every tick.
        if (cameFrom.size() == 1) {
            long now = System.currentTimeMillis();
            if (now - lastNoExpandLogMs > 1000) {
                lastNoExpandLogMs = now;
                StringBuilder why = new StringBuilder();
                for (int[] off : HORIZONTAL) {
                    BlockPos c = start.add(off[0], 0, off[1]);
                    why.append(' ').append(off[0]).append(',').append(off[1]).append(':');
                    if (!isSolid(c.down(), world)) why.append("noFloor");
                    else if (isHazard(c.down(), world)) why.append("hazardBelow");
                    else if (!canPassThrough(c, world)) why.append("feetBlocked=")
                            .append(world.getBlockState(c).getBlock().getTranslationKey());
                    else if (isHazardOrSlow(c, world)) why.append("feetSlow");
                    else if (!canPassThrough(c.up(), world)) why.append("headBlocked=")
                            .append(world.getBlockState(c.up()).getBlock().getTranslationKey());
                    else if (isHazardOrSlow(c.up(), world)) why.append("headSlow");
                    else why.append("OK?");
                }
                kaptainwutax.tungsten.Debug.logMessage("BFS stuck at " + start.getX() + ","
                        + start.getY() + "," + start.getZ() + " —" + why);
            }
        }

        // partial path to closest reached node
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos p : cameFrom.keySet()) {
            double d = p.getSquaredDistance(goal);
            if (d < closestDist) { closestDist = d; closest = p; }
        }
        return note(closest != null ? reconstructPath(cameFrom, closest) : Collections.emptyList(), explored);
    }

    private static List<BlockPos> findRetreatPath(BlockPos playerPos, BlockPos targetPos, WorldView world) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(playerPos);
        cameFrom.put(playerPos, null);

        BlockPos bestRetreat = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int explored = 0;

        while (!queue.isEmpty() && explored < MAX_NODES) {
            BlockPos current = queue.poll();
            explored++;

            double distFromTarget = Math.sqrt(current.getSquaredDistance(targetPos));
            double heightBonus = (current.getY() - targetPos.getY()) * 2.0;
            double edgePenalty = VoidDetector.edgeScore(Vec3d.ofBottomCenter(current), world) * -10.0;

            // direction bonus: prefer points in the hemisphere AWAY from target
            double awayDirX = playerPos.getX() - targetPos.getX();
            double awayDirZ = playerPos.getZ() - targetPos.getZ();
            double awayLen = Math.sqrt(awayDirX * awayDirX + awayDirZ * awayDirZ);
            double directionBonus = 0;
            if (awayLen > 0.1) {
                double toCandidateX = current.getX() - playerPos.getX();
                double toCandidateZ = current.getZ() - playerPos.getZ();
                double candidateLen = Math.sqrt(toCandidateX * toCandidateX + toCandidateZ * toCandidateZ);
                if (candidateLen > 0.1) {
                    // dot product: +1 = same direction as away, -1 = toward target
                    double dot = (awayDirX * toCandidateX + awayDirZ * toCandidateZ) / (awayLen * candidateLen);
                    directionBonus = dot * 5.0; // strong preference for away direction
                }
            }

            double score = distFromTarget + heightBonus + edgePenalty + directionBonus;

            if (score > bestScore && !current.equals(playerPos)) {
                bestScore = score;
                bestRetreat = current;
            }

            for (BlockPos neighbor : getWalkableNeighbors(current, world, false)) {
                if (cameFrom.containsKey(neighbor)) continue;
                if (!playerPos.isWithinDistance(neighbor, MAX_RADIUS)) continue;
                cameFrom.put(neighbor, current);
                queue.add(neighbor);
            }
        }

        return bestRetreat != null ? reconstructPath(cameFrom, bestRetreat) : Collections.emptyList();
    }

    // ── neighbors + block checks ─────────────────────────────────────────────

    /** Diagonal steps refused because the body would have to squeeze past a solid corner. */
    public static volatile int gridCornerRefused = 0;

    private static final int[][] HORIZONTAL = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private static List<BlockPos> getWalkableNeighbors(BlockPos pos, WorldView world, boolean allowParkour) {
        return getWalkableNeighbors(pos, world, allowParkour, false, false);
    }

    /**
     * @param allowSwim the search STARTED in liquid, so cells with no floor are still traversable
     */
    private static List<BlockPos> getWalkableNeighbors(BlockPos pos, WorldView world,
                                                       boolean allowParkour, boolean allowSwim) {
        return getWalkableNeighbors(pos, world, allowParkour, allowSwim, false);
    }

    /**
     * @param cardinalOnly drop the four diagonals -- the WALKING queue cannot execute one.
     */
    private static List<BlockPos> getWalkableNeighbors(BlockPos pos, WorldView world,
                                                       boolean allowParkour, boolean allowSwim,
                                                       boolean cardinalOnly) {
        List<BlockPos> result = new ArrayList<>();

        if (allowSwim) {
            // A SWIMMER NEEDS NO FLOOR, AND THAT IS WHY THE BOT STOPS DEAD IN WATER.
            // isWalkable demands a solid block underneath, so in open water every neighbour fails
            // it. Measured on a failing @gamer run, printed by the diagnosis above:
            //   BFS stuck at 3636,60,3309 — 1,0:noFloor -1,0:noFloor 0,1:noFloor 0,-1:noFloor ...
            // all eight, at sea level. The route comes back one cell long ("gridBFS sz1" in the
            // hundreds), the drive has nothing to follow, and the run reaches nothing.
            // Six directions, because getting OUT of water is as often up or down as sideways, and
            // the queue already types a liquid edge as MovementSwim.
            // Only when the search began in liquid: on land nothing changes, so no land route can
            // start preferring a pond.
            for (int[] off : SWIM_DIRS) {
                BlockPos c = pos.add(off[0], off[1], off[2]);
                if (isLiquid(c, world) || (isWalkable(c, world) && !isHazard(c.down(), world))) {
                    result.add(c);
                }
            }
            return result;
        }

        for (int[] off : HORIZONTAL) {
            // ⛔ NO CORNER CUTTING. THIS IS THE ROOT OF THE PLAYTHROUGH'S WORST STALL.
            //
            // HORIZONTAL includes the four diagonals, and the only test applied to one was whether
            // the DESTINATION is walkable. Nothing looked at the two cells the body must pass
            // BETWEEN, so this grid happily routes a diagonal through a notch with solid blocks on
            // both sides -- a step vanilla cannot make. FastPlanner has always refused exactly
            // this (expand() calls sideClear on both orthogonals before offering a diagonal); this
            // producer never did, and it is the one that drives the playthrough: "primDrive
            // gridBFS sz13" is the line in the log.
            //
            // What that costs, traced end to end. The queue ACCEPTS the diagonal, MovementDiagonal
            // cannot execute it and holds forward at v=0.00, and because a RUNNING chain makes the
            // mixin return early, NOTHING else ticks -- not BlockPathWalker, not the build
            // primitives, not the physics executor (walkMode=36/0/0, pdWalking=0 against
            // pdEnter=733) -- while FastNavigator counts isRunning() as "building" and never
            // replans. One impossible edge freezes every engine for the rest of the run:
            // mqStarted=64 against mqSteps=9, dbTargets=12/0, no rungs.
            //
            // The blocks at the traced spot, read rather than assumed:
            //     cornerA (85,125,-55) grass_block   SOLID
            //     cornerB (84,125,-54) dirt          SOLID
            // Both full, and the diagonal between them was offered as a route.
            // THE WALKING QUEUE CANNOT EXECUTE A DIAGONAL -- but the SEARCH needs them.
            // MovementQueue.isSupportedEdge accepts traverse/pillar/climb but NOT diagonals
            // (queueDiagonals is off BY MEASUREMENT: within one batch they read 19/23/11,
            // a spread of 12 where every other configuration sat at 1-3). traversePrefix
            // stops at the first unsupported edge, so ONE leading diagonal costs the WHOLE
            // route: covered=1 < 2 and it is refused as 'short'. Measured live during a
            // stall: mqRefused(short)=2337 against 2299 BFS ticks -- one refusal per tick.
            // ⛔ FIXED, SAME DAY, DOWNSTREAM OF THIS FUNCTION -- READ THIS BEFORE TRUSTING THE
            // PARAGRAPH ABOVE AS A LIVE PROBLEM (2026-09-02, caught re-reading TODOS.md ProfileD
            // continuation without checking git log first, then caught the same way). Dropping
            // diagonals from the search here (a `cardinalOnly` goto search) was tried first, in
            // the same commit that measured the numbers quoted above, and reverted 70 minutes
            // later for being far worse (cp exhausted 99% of calls, 98% stubs -- the search needs
            // its diagonals to reach anything in 800 nodes). The kept fix is expandDiagonals()
            // below, applied in findPath() (the goto entry point) behind
            // TungstenConfig.gridRouteMatchesQueueMoves, default true: the search keeps every
            // diagonal, and the FINISHED route has each one rewritten into two cardinal steps
            // before the queue ever sees it. Validated on the stand in that same commit (nav
            // 13/14, craft 22/22, zero gate regressions). So today: combat keeps its diagonals
            // raw (calls bfsPath directly, no translation needed for the chase use case); goto
            // also keeps them in the search, but never hands one to the queue.
            if (kaptainwutax.tungsten.TungstenConfig.get().gridBfsRefusesCornerCut
                    && off[0] != 0 && off[1] != 0) {
                BlockPos sideA = pos.add(off[0], 0, 0);
                BlockPos sideB = pos.add(0, 0, off[1]);
                if (!canPassThrough(sideA, world) || !canPassThrough(sideB, world)) {
                    gridCornerRefused++;
                    continue;
                }
            }
            BlockPos candidate = pos.add(off[0], 0, off[1]);
            boolean flatWalk = isWalkable(candidate, world);
            if (flatWalk) {
                result.add(candidate);
            } else {
                BlockPos up = candidate.up();
                if (isWalkable(up, world) && canPassThrough(pos.up().up(), world)) result.add(up);
                BlockPos down = candidate.down();
                if (isWalkable(down, world) && canPassThrough(candidate, world)) result.add(down);
            }
            // Parkour (goto only): a running jump across a gap to a same-level or +1
            // landing — the pillar-to-pillar move a plain walk/step can't make (course
            // B, natural gapped/stepped terrain). Only when NO flat walk exists in this
            // direction (a real gap/pillar) and only in a cardinal direction, so flat
            // terrain keeps a small branching factor and combat pathing is untouched.
            if (allowParkour && !flatWalk && off[0] * off[1] == 0) {
                addParkourNeighbors(result, pos, off, world);
            }
        }
        return result;
    }

    /** Running-jump landings 2..MAX_PARKOUR away in {@code dir} (flat or +1 up), each
     *  with the whole flight path (feet + head) clear so the arc doesn't clip a wall. */
    private static void addParkourNeighbors(List<BlockPos> result, BlockPos pos, int[] dir, WorldView world) {
        if (!canPassThrough(pos.up().up(), world)) return; // no head room to jump
        for (int dist = 2; dist <= MAX_PARKOUR; dist++) {
            boolean flightClear = true;
            for (int s = 1; s < dist; s++) {
                BlockPos mid = pos.add(dir[0] * s, 0, dir[1] * s);
                if (!canPassThrough(mid, world) || !canPassThrough(mid.up(), world)) { flightClear = false; break; }
            }
            if (!flightClear) break;
            BlockPos flat = pos.add(dir[0] * dist, 0, dir[1] * dist);
            if (isWalkable(flat, world) && !result.contains(flat)) result.add(flat);
            if (dist <= 3) { // a running jump reaches +1y over at most ~3 blocks
                BlockPos upLand = flat.up();
                if (isWalkable(upLand, world) && !result.contains(upLand)) result.add(upLand);
            }
        }
    }

    public static boolean isWalkable(BlockPos feetPos, WorldView world) {
        BlockPos below = feetPos.down();
        if (!isSolid(below, world)) return false;
        if (isHazard(below, world)) return false;           // standing ON hazard
        if (!canPassThrough(feetPos, world)) return false;
        if (isHazardOrSlow(feetPos, world)) return false;   // feet IN hazard/slow
        if (!canPassThrough(feetPos.up(), world)) return false;
        if (isHazardOrSlow(feetPos.up(), world)) return false;
        return true;
    }

    /** Six neighbours for a swimmer: four sides plus up and down. */
    private static final int[][] SWIM_DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}
    };

    /** Is this cell liquid we can move through? Lava is a hazard, not a route. */
    public static boolean isLiquid(BlockPos pos, WorldView world) {
        return world.getBlockState(pos).getBlock() == Blocks.WATER;
    }

    public static boolean isSolid(BlockPos pos, WorldView world) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    public static boolean canPassThrough(BlockPos pos, WorldView world) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /** Blocks that deal damage — never walk here. */
    public static boolean isHazard(BlockPos pos, WorldView world) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        return block == Blocks.LAVA
                || block instanceof FireBlock
                || block == Blocks.MAGMA_BLOCK
                || block instanceof CampfireBlock
                || block == Blocks.CACTUS
                || block == Blocks.WITHER_ROSE
                || block instanceof SweetBerryBushBlock;
    }

    /** Hazard OR slowdown — avoid in pathfinding. */
    public static boolean isHazardOrSlow(BlockPos pos, WorldView world) {
        if (isHazard(pos, world)) return true;
        Block block = world.getBlockState(pos).getBlock();
        return block == Blocks.WATER
                || block instanceof CobwebBlock
                || block == Blocks.SOUL_SAND
                || block == Blocks.HONEY_BLOCK
                || block == Blocks.POWDER_SNOW;
    }

    // ── path reconstruction ──────────────────────────────────────────────────

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos current = end;
        while (current != null) {
            path.add(current);
            current = cameFrom.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    // ── public static BFS ─────────────────────────────────────────────────────

    /**
     * Instant BFS path on block grid. Used by FollowEntityTask for
     * immediate movement while physics A* computes.
     */
    public static List<BlockPos> findPath(BlockPos start, BlockPos goal, WorldView world) {
        List<BlockPos> route = bfsPath(start, goal, world, true, false);
        return kaptainwutax.tungsten.TungstenConfig.get().gridRouteMatchesQueueMoves
                ? expandDiagonals(route, world) : route;
    }

    // ── getters ──────────────────────────────────────────────────────────────

    public List<BlockPos> getAttackPath()  { return attackPath; }
    public List<BlockPos> getRetreatPath() { return retreatPath; }

    public void reset() {
        attackPath = Collections.emptyList();
        retreatPath = Collections.emptyList();
        tickCounter = 0;
    }

    /** Record the shape of a route this BFS hands back, and whether it spent its budget. */
    private static List<BlockPos> note(List<BlockPos> path, int explored) {
        if (explored >= MAX_NODES) cpExhausted++;
        if (path.size() < 2) cpStub++;
        cpCells += path.size();
        cpDistinct += (int) path.stream().distinct().count();
        return path;
    }

    /**
     * Turn every diagonal step of a finished route into the two cardinal steps around it.
     *
     * <p>The queue cannot execute a diagonal (queueDiagonals is off by measurement) and
     * traversePrefix stops at the first unsupported edge, so ONE leading diagonal costs the
     * whole route -- measured live at mqRefused(short)=2337 against 2299 BFS ticks.
     *
     * <p>Removing diagonals from the SEARCH was tried first and is much worse: the grid then
     * cannot reach anything inside its 800-node budget and returns a stub almost every time
     * (cp went from 107/39/0 to 2077/2052/2027 -- 98% stubs). Diagonals earn their keep in the
     * search; they only need translating before the queue sees them.
     *
     * <p>The corner is taken through whichever orthogonal is passable, so the body never
     * clips a block the diagonal cut past.
     */
    private static List<BlockPos> expandDiagonals(List<BlockPos> path, WorldView world) {
        if (path == null || path.size() < 2) return path;
        List<BlockPos> out = new ArrayList<>(path.size() * 2);
        out.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            if (a.getY() == b.getY() && dx != 0 && dz != 0) {
                BlockPos viaX = a.add(dx, 0, 0);
                BlockPos viaZ = a.add(0, 0, dz);
                BlockPos via = isWalkable(viaX, world) ? viaX
                        : (isWalkable(viaZ, world) ? viaZ : null);
                if (via == null) {
                    gridDiagonalUnturnable++;
                    out.add(b);
                    continue;
                }
                gridDiagonalExpanded++;
                out.add(via);
            }
            out.add(b);
        }
        return out;
    }
}

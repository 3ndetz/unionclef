package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.agent.Agent;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Sidestep-an-incoming-arrow execution primitive: drive the movement keys along a world-space
 * direction for N ticks.
 *
 * <h2>Why this exists as a primitive instead of a few key presses in the chain</h2>
 *
 * It already existed as a few key presses in the chain, and it had never once moved the bot.
 * {@code MobDefenseChain} pressed SPRINT / MOVE_FORWARD / JUMP from altoclef's task runner, which
 * ticks BEFORE {@code MovementQueue} and {@code BlockPathWalker} -- and
 * {@code Movement.update()} releases every key and then presses exactly what its own tick declared.
 * So on every tick the walker was driving the approach, the dodge's keys were wiped before the game
 * read them.
 *
 * <p>That is pitfall P1, and this repo has already paid for it once in this exact shape: the flee
 * keys were driven from {@code RunAwayTask.tick}, measured 22 hits against 23, and were filed as
 * REFUTED when they had simply never run. The cure there was to move the writer to the established
 * final-word position in {@code MixinClientPlayerEntity}, after every owner. This is the same cure
 * for the same defect.
 *
 * <p>It also explains four separate dodge hypotheses that were each measured and each came back
 * indistinguishable from baseline -- yield the dodge to the kill order, choose the dodge side by
 * ground, steer off the arrow's velocity instead of a near-zero vector. None of them could move a
 * number, because none of them ever reached the keys.
 *
 * <h2>Strafe, do not steer</h2>
 *
 * The direction is converted into the player's own frame and pressed as forward/back/left/right,
 * so the CAMERA is never touched. Two reasons, both load-bearing: the bot keeps looking at the
 * thing it is fighting (the swing gate refuses at 40 degrees off, and a dodge that turns the head
 * away cannot also attack), and nothing here fights the rotation writers -- snapping the yaw to a
 * dodge heading is exactly the anti-cheat tell that TODOS #11 exists to remove.
 */
public class ProjectileDodge {

    private static int holdTicks = 0;
    private static double dirX, dirZ;
    /** Ticks the primitive actually drove the keys. Read over py4j as dodgeDrive. */
    public static volatile int driveTicks;

    /**
     * Sidestep along {@code (x, z)} for {@code ticks}.
     *
     * <p>Re-arming refreshes rather than accumulates: an arrow is re-evaluated every tick it is in
     * flight, and the newest heading is the right one.
     */
    public static synchronized void hold(double x, double z, int ticks) {
        planned = false;
        dirX = x;
        dirZ = z;
        holdTicks = Math.max(holdTicks, ticks);
    }

    // ── the planned dodge ─────────────────────────────────────────────────────

    /** Set by {@link #plan}: the keys to hold, in the player's own frame. */
    private static boolean planned, pFwd, pBack, pLeft, pRight, pJump, jumpHeld;
    /** Searches run, searches that left the chain's heading for a safer or clearer one, searches
     *  in which even standing still was unsafe, candidates refused as unsafe. Read over py4j. */
    public static volatile int searches, searchDeviated, searchAllUnsafe, searchRejected, searchJumped;

    /** Ticks after the keys are released that the search keeps simulating: momentum carries a
     *  sprinting body about a block further, and that block has to be safe too. */
    private static final int SETTLE_TICKS = 6;
    /** A clearance past this is a certain miss; beyond it the candidates only differ by heading. */
    private static final double CLEAR_ENOUGH = 1.0;
    /** baritone's maxFallHeightNoWater (baritone/src/main/java/baritone/api/Settings.java:536). */
    private static final int MAX_SAFE_FALL = 3;

    /**
     * Sidestep an arrow, choosing the keys by search rather than trusting a heading.
     *
     * <p>{@link #hold} strafes along whatever heading the chain computed, and the chain computes it
     * from the arrow alone: it has never looked at the ground. On a ledge, a bridge or beside a lava
     * lake the right sidestep and the fatal one differ only by which side of the arrow line they are.
     *
     * <p>So this does what tungsten's planner does, cut down to one step: each of the nine key
     * combinations (eight strafes and standing still), each with and without a jump, is simulated with the physics agent for
     * {@code ticks} ticks plus {@link #SETTLE_TICKS} of coasting, against the arrow's own flight.
     * A candidate is refused if the body touches anything baritone refuses to walk into
     * (MovementHelper.avoidWalkingInto, baritone/src/main/java/baritone/pathing/movement/
     * MovementHelper.java:420 -- fluids, magma, cactus, berry bush, fire, cobweb) or drops further
     * than {@link #MAX_SAFE_FALL}. Of the rest, the one whose body stays furthest from the arrow
     * wins, and the chain's heading breaks ties -- it carries the bias toward the shooter. If
     * nothing is safe, not even standing, nothing is pressed.
     *
     * <p>Eighteen candidates at about a dozen agent ticks each is two hundred ticks of physics,
     * small next to what the planner simulates per search, so it runs every tick an arrow is close.
     */
    public static synchronized void plan(double x, double z, int ticks,
                                         Vec3d arrowPos, Vec3d arrowVel, double gravity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;
        WorldView world = mc.world;
        searches++;

        double yaw = Math.toRadians(player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -fz, rz = fx;
        double pref = Math.hypot(x, z);
        double px = pref > 1e-6 ? x / pref : 0, pz = pref > 1e-6 ? z / pref : 0;
        // Already in water: every candidate touches it, and refusing them all would pin the body in
        // the arrow's path.
        boolean startWet = player.isTouchingWater();

        double bestScore = Double.NEGATIVE_INFINITY;
        int bestF = 0, bestS = 0;
        boolean any = false;
        boolean bestJ = false;
        for (int j = 0; j <= 1; j++) for (int f = -1; f <= 1; f++) {
            for (int s = -1; s <= 1; s++) {
                boolean jump = j == 1;
                // A jump only starts from the ground; in the air the candidate is the same as j=0.
                if (jump && !player.isOnGround()) continue;
                double clear = simulate(player, world, f, s, jump, ticks, arrowPos, arrowVel, gravity, startWet);
                if (Double.isNaN(clear)) {
                    searchRejected++;
                    continue;
                }
                double wx = f * fx + s * rx, wz = f * fz + s * rz;
                double wl = Math.hypot(wx, wz);
                double along = wl > 1e-6 ? (wx * px + wz * pz) / wl : 0;
                // A jump is kept for when it clears the arrow better: it lands where it lands, and a
                // bot hopping at every arrow is a tell.
                double score = Math.min(clear, CLEAR_ENOUGH) + 0.1 * along - (jump ? 0.15 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    bestF = f;
                    bestS = s;
                    bestJ = jump;
                    any = true;
                }
            }
        }
        if (!any) {
            searchAllUnsafe++;
        } else {
            // What the blind heading would have pressed, to count the times the search overruled it.
            double hf = x * fx + z * fz, hs = x * rx + z * rz;
            int hF = hf > 0.25 ? 1 : hf < -0.25 ? -1 : 0, hS = hs > 0.25 ? 1 : hs < -0.25 ? -1 : 0;
            if (hF != bestF || hS != bestS || bestJ) searchDeviated++;
            if (bestJ) searchJumped++;
        }
        planned = true;
        pFwd = bestF > 0;
        pBack = bestF < 0;
        pRight = bestS > 0;
        pLeft = bestS < 0;
        pJump = bestJ;
        dirX = x;
        dirZ = z;
        holdTicks = Math.max(holdTicks, ticks);
    }

    /**
     * The closest the arrow comes to the body under these keys, or NaN when the keys are unsafe.
     * {@code f}: +1 forward, -1 back; {@code s}: +1 right, -1 left (the frame {@link #tick} uses).
     */
    private static double simulate(ClientPlayerEntity player, WorldView world, int f, int s, boolean jump, int ticks,
                                   Vec3d arrowPos, Vec3d arrowVel, double gravity, boolean startWet) {
        Agent sim = Agent.of(player);
        sim.yaw = player.getYaw();
        sim.pitch = player.getPitch();
        double startY = sim.posY;
        double ax = arrowPos.x, ay = arrowPos.y, az = arrowPos.z;
        double vx = arrowVel.x, vy = arrowVel.y, vz = arrowVel.z;
        double best = Double.POSITIVE_INFINITY;
        double prevX = sim.posX, prevY = sim.posY, prevZ = sim.posZ;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int t = 0; t < ticks + SETTLE_TICKS; t++) {
            boolean keys = t < ticks;
            sim.keyForward = keys && f > 0;
            sim.keyBack = keys && f < 0;
            sim.keyRight = keys && s > 0;
            sim.keyLeft = keys && s < 0;
            sim.keySprint = keys && f > 0;
            sim.keyJump = jump && t == 0;
            sim.keySneak = false;
            sim.tick(world);
            if (sim.isInLava() || (!startWet && sim.touchingWater)) return Double.NaN;
            int bx = MathHelper.floor(sim.posX), by = MathHelper.floor(sim.posY + 0.01), bz = MathHelper.floor(sim.posZ);
            for (int dy = -1; dy <= 1; dy++) {
                BlockState st = world.getBlockState(m.set(bx, by + dy, bz));
                if (avoidWalkingInto(st, startWet || dy < 0)) return Double.NaN;
            }
            if (startY - sim.posY > MAX_SAFE_FALL) return Double.NaN;
            // The arrow covers ~2.5 blocks a tick, more than the body is wide, so each tick is
            // sampled along its length rather than only at its ends.
            double nx = ax + vx, ny = ay + vy, nz = az + vz;
            for (int k = 1; k <= 8; k++) {
                double u = k / 8.0;
                double qx = ax + (nx - ax) * u, qy = ay + (ny - ay) * u, qz = az + (nz - az) * u;
                double bxp = prevX + (sim.posX - prevX) * u;
                double byp = prevY + (sim.posY - prevY) * u;
                double bzp = prevZ + (sim.posZ - prevZ) * u;
                best = Math.min(best, boxDistance(qx, qy, qz, bxp, byp, bzp));
            }
            ax = nx; ay = ny; az = nz;
            vx *= 0.99; vy = vy * 0.99 - gravity; vz *= 0.99;
            prevX = sim.posX; prevY = sim.posY; prevZ = sim.posZ;
        }
        // Where it comes to rest: a drop past the limit there is a fall the coasting has not
        // finished yet. VoidDetector counts lava below as a bottomless drop.
        if (kaptainwutax.tungsten.combat.VoidDetector.fallHeight(sim.getPos(), world) > MAX_SAFE_FALL) {
            return Double.NaN;
        }
        return best;
    }

    /** Distance from a point to a standing player's box (0.6 wide, 1.8 tall) with feet at (x,y,z). */
    private static double boxDistance(double px, double py, double pz, double x, double y, double z) {
        double dx = Math.max(Math.max(x - 0.3 - px, 0), px - (x + 0.3));
        double dy = Math.max(Math.max(y - py, 0), py - (y + 1.8));
        double dz = Math.max(Math.max(z - 0.3 - pz, 0), pz - (z + 0.3));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * baritone's MovementHelper.avoidWalkingInto (baritone/src/main/java/baritone/pathing/movement/
     * MovementHelper.java:420), with one difference: water is allowed when {@code waterOk} -- the
     * body is already in it, or it is the cell under the feet, where only lava and the damaging
     * blocks matter.
     */
    private static boolean avoidWalkingInto(BlockState state, boolean waterOk) {
        Block block = state.getBlock();
        if (!state.getFluidState().isEmpty()) {
            if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
            if (!waterOk) return true;
        }
        return block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof AbstractFireBlock
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    public static boolean isActive() {
        return holdTicks > 0;
    }

    public static synchronized void release() {
        holdTicks = 0;
        clearKeys();
    }

    private static void clearKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
        }
    }

    /**
     * Called every game tick from MixinClientPlayerEntity, at the final-word position.
     *
     * <p>Deliberately NOT gated on {@code movementOwnsTick}. The walker exemption exists for
     * writers that would fight a planned route for the whole leg; this one lasts a handful of ticks
     * and its entire purpose is to override the approach for exactly as long as an arrow is in the
     * air. The queue's own timeout absorbs the interruption -- what it cannot absorb is the bot
     * walking a straight line into the shot.
     */
    public static void tick(ClientPlayerEntity player) {
        if (holdTicks <= 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || mc.options == null) {
            // ⛔ RELEASE BEFORE ABANDONING THE HOLD. This used to drop holdTicks and return with
            // the keys still PRESSED -- a violation of checklist rule 4l in the very primitive
            // that rule was written about.
            //
            // Why it matters beyond tidiness: the player reference goes away when the bot DIES,
            // which on these courses happens mid-dodge. Sprint stays held through the respawn and
            // the bot immediately runs off the arena again. That is a candidate mechanism for the
            // CASCADE seen on 2026-08-12, where runs after a fall opened with the bot already at
            // Y=-234 and every later run in the series measured a corpse.
            // Clearing needs mc.options, so it is only attempted when that half is available.
            holdTicks = 0;
            if (mc.options != null) {
                clearKeys();
            }
            return;
        }
        holdTicks--;

        // The player's own frame. MC yaw 0 faces +Z, and the right hand points -X from there.
        double yaw = Math.toRadians(player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -fz, rz = fx;

        double fwd = dirX * fx + dirZ * fz;
        double side = dirX * rx + dirZ * rz;

        // A component this small is noise in the heading, and pressing on it would jitter the keys
        // between two opposite presses on consecutive ticks.
        final double DEADZONE = 0.25;
        boolean kF = planned ? pFwd : fwd > DEADZONE;
        mc.options.forwardKey.setPressed(kF);
        mc.options.backKey.setPressed(planned ? pBack : fwd < -DEADZONE);
        mc.options.rightKey.setPressed(planned ? pRight : side > DEADZONE);
        mc.options.leftKey.setPressed(planned ? pLeft : side < -DEADZONE);
        // Sprint only earns its speed going forwards, and a backwards sprint is not a thing.
        mc.options.sprintKey.setPressed(kF);
        // The jump is one tap: pressed on the first tick of the hold, released on the next, so it
        // is not left held for whoever writes the keys after the dodge.
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
        }
        if (planned && pJump) {
            jumpHeld = player.isOnGround();
            mc.options.jumpKey.setPressed(jumpHeld);
            pJump = false;
        }
        driveTicks++;

        // ⛔ KNOWN DEFECT, FOUND BY RE-READING, NOT YET FIXED OR MEASURED.
        //
        // This clear runs at the FINAL-WORD position, i.e. after MovementQueue has already pressed
        // its keys for this tick. So on the tick a dodge expires we wipe the WALKER's movement too
        // and the bot stalls for one tick. Same "two writers, last one wins" family as the bug this
        // primitive exists to fix -- one layer down, and caused by the fix.
        //
        // Not patched blind: the obvious cure (stop setting the keys instead of clearing them)
        // leaves them stuck pressed whenever nothing else writes them that tick, which is worse.
        // A correct version must release only what THIS primitive pressed, and only when no other
        // owner is driving -- and it has to be measured, because a one-tick stall a few times a
        // fight is exactly the size of effect this course cannot resolve without a pinned
        // same-session A/B (checklist 4j).
        if (holdTicks == 0) {
            clearKeys();
        }
    }
}

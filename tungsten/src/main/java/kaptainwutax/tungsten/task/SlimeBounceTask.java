package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.block.SlimeBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Crossing a slime pad, driven tick by tick: hold the heading and full sprint across the
 * WHOLE chain of bounces, and only stop once the far landing is under us.
 *
 * <p>Why this is a task and not a few rules inside {@link BlockPathWalker}: the walker
 * treats a bouncing surface as ordinary walking, and every rule bolted onto it fixed one
 * phase of the crossing and broke another. All of these were built and measured on the
 * bounce course before this class existed:
 *
 * <ul>
 *   <li>hold the landing waypoint while airborne above it — took a guaranteed void fall
 *       (20.7 blocks short) down to ~8.4 short with no falls, but only one run in three;</li>
 *   <li>release that hold once past the waypoint — WORSE, three failures in three;</li>
 *   <li>cut the throttle over the landing — required, or the arc overshoots, but it also
 *       bled the chain from 0.25 to 0.00 blocks per tick;</li>
 *   <li>exempt bouncy landings from that cut — no measurable change;</li>
 *   <li>charge for horizontal air travel — WORSE, one landing in four.</li>
 * </ul>
 *
 * <p>What none of them could express is the thing that actually matters: a crossing is ONE
 * manoeuvre, not a series of independent waypoints. Speed must be carried through every
 * bounce, which means the throttle may only be cut above the FINAL landing.
 */
public class SlimeBounceTask {

    private static boolean active = false;
    private static BlockPos target;      // the far landing we are crossing to
    private static int ticks;
    private static int bounces;
    private static double lastY;
    /**
     * Has the crossing actually begun — left the ground or touched the pad? Without this the
     * "ended off-target" exit fires on the very first tick, while we are still standing on the
     * launch pad, and the walker restarts it the next tick: measured 2024 starts and 0 bounces
     * in a single run, a pure start/stop thrash.
     */
    private static boolean launched;
    /** Crossings STARTED — distinguishes "never triggered" from "ran but bounced zero times". */
    public static volatile int starts = 0;

    /** Ticks to give a crossing before calling it stuck (a bounce chain is a few seconds). */
    private static final int TICK_BUDGET = 200;

    /**
     * Start a crossing.
     *
     * @param landing the cell to end up standing in — the first waypoint beyond the pad
     *                whose floor is NOT slime.
     */
    public static synchronized boolean startTo(BlockPos landing) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || landing == null) return false;
        target = landing;
        ticks = 0;
        bounces = 0;
        lastY = p.getY();
        launched = false;
        active = true;
        starts++;
        Debug.logMessage("Slime crossing to " + landing.toShortString());
        return true;
    }

    public static boolean isActive() { return active; }
    public static int getBounces() { return bounces; }

    public static void stop() {
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }
        WindMouseRotation.INSTANCE.clearTarget();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        WorldView world = player.getEntityWorld();
        Vec3d pos = player.getEntityPos();
        Vec3d tgt = Vec3d.ofBottomCenter(target);

        double horiz = Math.hypot(tgt.x - pos.x, tgt.z - pos.z);
        boolean onSlime = player.isOnGround()
                && world.getBlockState(player.getBlockPos().down()).getBlock() instanceof SlimeBlock;

        if (!player.isOnGround() || onSlime) launched = true;

        // Arrived: standing on the far side, close enough that the walker can take over.
        if (launched && player.isOnGround() && !onSlime && horiz < 1.6) {
            Debug.logMessage("Slime crossing done (" + bounces + " bounce(s))");
            stop();
            return;
        }
        // Landed on something solid that is NOT the target and not slime — the crossing is
        // over one way or the other; hand back rather than keep driving blind.
        if (launched && player.isOnGround() && !onSlime && horiz >= 1.6) {
            Debug.logMessage("Slime crossing ended off-target at "
                    + player.getBlockPos().toShortString());
            stop();
            return;
        }
        if (++ticks > TICK_BUDGET) {
            Debug.logMessage("Slime crossing gave up after " + ticks + " ticks");
            stop();
            return;
        }

        // Count bounces for the log — an upward turn while over the pad.
        if (player.getY() > lastY + 0.3) bounces++;
        lastY = player.getY();

        // HOLD THE HEADING AND THE THROTTLE. This is the whole point of the task: the speed
        // that carries the last bounce onto the ledge is built over the ones before it, so
        // it must never be released mid-chain.
        WindMouseRotation.INSTANCE.setTargetFast(
                (float) DirectionHelper.calcYawFromVec3d(pos, tgt), 0);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        // NO JUMP. Jumping as you meet the slime turns a passive bounce into a launched one,
        // which sounds like the extra height the far ledge needs — measured, it is not: with
        // the launch the bot cleared the pad entirely and died 5 to 6 times per run. The
        // passive bounce is the one the planner's model was measured against.
        mc.options.jumpKey.setPressed(false);

        if (TungstenConfig.get().verboseDebugLogging && (ticks % 8 == 0)) {
            Vec3d v = player.getVelocity();
            Debug.logMessage(String.format(
                    "BOUNCE t=%d pos=(%.1f,%.1f,%.1f) vel=(%.2f,%.2f) horiz=%.1f slime=%b",
                    ticks, pos.x, pos.y, pos.z, v.x, v.y, horiz, onSlime));
        }
    }
}

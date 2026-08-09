package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Shared void-safety movement clamp — the final word on the movement keys so no
 * subsystem walks or launches the bot off a ledge. Used by the combat aura
 * (SafetySystem) and by the flee task (RunAwayTask, applied after the pathfinder
 * executor sets keys). Leverages vanilla sneak edge-protection.
 *
 * The rule: near a rim never sprint; when the heading (pressed keys OR raw
 * momentum) points at a serious drop, never jump toward it and plant with sneak.
 * The drive is only cancelled when the bot is actively STEERING off (its own
 * keys point at the drop) — when only momentum points there (knockback / a brake
 * pushing back toward the island) the toward-island input is kept and sneak is
 * merely added, so recovery isn't stranded at the edge.
 */
public final class VoidGuard {

    private VoidGuard() {}

    /**
     * @param pos feet position to test from (tick pos in combat, entity pos elsewhere)
     * @param vel current velocity (for the momentum/overshoot check)
     */
    /** How often the guard runs, and how often it actually sees an edge; read over py4j. */
    public static volatile int vgCalls, vgEdgeSeen;
    /**
     * STATE AT THE MOMENT THE FALL BEGINS, which is the one thing the existing counters cannot
     * give. edgeAir accumulates a tick at a time while the bot is already in the void, so a single
     * fall that the harness takes seconds to fish out dominates it -- 317 ticks read as "airborne a
     * quarter of the course" when it was two or three falls being counted while they lasted. That
     * is the consequence, not the cause.
     *
     * <p>These four fire ONCE, on the rising edge of "off the ground with nothing underneath", and
     * record what the bot was doing as it left: hurt (so knockback launched it), sprinting, and
     * whether the guard had seen the rim on the previous tick. Between them the self-fall gets an
     * origin instead of a correlation.
     */
    public static volatile int vgFallOnset, vgFallHurt, vgFallSprint, vgFallAfterEdge;
    /**
     * HOW FAR A BLOW ACTUALLY THROWS US, measured instead of assumed.
     *
     * <p>CombatController's KNOCKBACK_REACH is 3.0 and that number is a guess I wrote. Everything
     * downstream depends on it: how wide the danger line behind the bot is, how often NO orbit side
     * clears the rim, and therefore how big the residue is that two reverted fixes were aimed at.
     * Vanilla applies roughly 0.4 velocity on an ordinary hit, which decays over a handful of ticks
     * -- if the real throw is closer to one block than three, the guard fires far more often than
     * the danger warrants and part of that residue is an artefact of my own constant.
     *
     * <p>Accumulated as hundredths of a block so it can travel through an int counter: kbThrowMax is
     * the furthest single throw seen, kbThrowSum/kbThrowN the mean. The window is ten ticks from the
     * hit, which is longer than knockback survives.
     */
    public static volatile int kbThrowMax = 0, kbThrowSum = 0, kbThrowN = 0;
    private static net.minecraft.util.math.Vec3d kbAnchor = null;
    private static int kbTicksLeft = 0;
    private static boolean wasHurt = false;
    private static boolean wasAirborneOverVoid = false;
    private static boolean sawEdgeLastTick = false;

    public static void protect(ClientPlayerEntity player, Vec3d pos, Vec3d vel, WorldView world) {
        // IS THE GUARD EVEN ON DUTY? Two thirds of the bot's real deaths are "fell from a high
        // place" -- six of nine in every run of a clean sweep. Either this never runs, or it runs
        // and sees no edge, or it sees one and the bot goes over anyway. Counting the three apart
        // is the only way to tell, and guessing has gone eight wrong out of nine today.
        vgCalls++;
        MinecraftClient mc = MinecraftClient.getInstance();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean nearVoid = VoidDetector.voidWithin(pos, world, 3, 3);
        // speed-scaled lookahead so a sprinting bot sees the rim with room to stop
        double look = Math.max(1.4, horizSpeed * 10.0);
        double[] keyHeading = pressedHeading(player, mc);
        boolean edgeByKey = keyHeading != null
                && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, look);
        boolean edgeByVel = horizSpeed > 0.04
                && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, look);

        if (nearVoid) {
            mc.options.sprintKey.setPressed(false);
        }
        // Jump suppression uses a LONGER lookahead: a jump carries the bot ~4
        // blocks, so a rim that's still 2-3 blocks ahead (invisible to the walk
        // lookahead above) is exactly where a crit- or brake-jump launches us off.
        double jumpLook = Math.max(3.2, horizSpeed * 16.0);
        boolean jumpTowardEdge =
                (keyHeading != null && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, jumpLook))
                || (horizSpeed > 0.04 && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, jumpLook));
        if (jumpTowardEdge) {
            mc.options.jumpKey.setPressed(false);
        }
        // Knockback throw distance, measured from the hit rather than assumed.
        boolean hurtNow = player.hurtTime > 0;
        if (hurtNow && !wasHurt) {
            kbAnchor = pos;
            kbTicksLeft = 10;
        }
        wasHurt = hurtNow;
        if (kbTicksLeft > 0 && kbAnchor != null) {
            double dx = pos.x - kbAnchor.x, dz = pos.z - kbAnchor.z;
            int cm = (int) Math.round(Math.sqrt(dx * dx + dz * dz) * 100.0);
            if (--kbTicksLeft == 0) {
                if (cm > kbThrowMax) kbThrowMax = cm;
                kbThrowSum += cm;
                kbThrowN++;
                kbAnchor = null;
            }
        }

        // Rising edge of "airborne over a void": snapshot WHY, once, before the fall buries it.
        boolean airborneOverVoid = !player.isOnGround()
                && kaptainwutax.tungsten.combat.VoidDetector.fallHeight(pos, world) > 20;
        if (airborneOverVoid && !wasAirborneOverVoid) {
            vgFallOnset++;
            if (player.hurtTime > 0) vgFallHurt++;
            if (player.isSprinting()) vgFallSprint++;
            if (sawEdgeLastTick) vgFallAfterEdge++;
        }
        wasAirborneOverVoid = airborneOverVoid;
        sawEdgeLastTick = edgeByKey || edgeByVel;

        if (edgeByKey || edgeByVel) {
            vgEdgeSeen++;
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(true);
            if (edgeByKey) {
                mc.options.forwardKey.setPressed(false);
                mc.options.backKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                mc.options.sprintKey.setPressed(false);
            }
        } else {
            // Clear of the rim — release the guard's edge-sneak so it can't persist (and
            // stick over the player's control after the driving task ends).
            mc.options.sneakKey.setPressed(false);
        }
    }

    /** Convenience overload using the player's current entity pos/velocity. */
    public static void protect(ClientPlayerEntity player, WorldView world) {
        protect(player, player.getEntityPos(), player.getVelocity(), world);
    }

    /**
     * Intent-based variant of {@link #protect} — same rules, but it vetoes a
     * {@link CombatMoveIntent} instead of writing the keys itself.
     *
     * <p>The combat pipeline resolves all movement into ONE intent per tick and writes the
     * keys exactly once (see {@link CombatMoveIntent}), so the guard must be able to act
     * BEFORE that write. The key-writing {@link #protect} overloads stay for the non-combat
     * callers (the flee task clamps after the pathfinder executor has already set keys).
     *
     * @param pos feet position to test from
     * @param vel current velocity (for the momentum/overshoot check)
     */
    public static void apply(CombatMoveIntent intent, ClientPlayerEntity player,
                             Vec3d pos, Vec3d vel, WorldView world) {
        if (intent == null || !intent.active) return;
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean nearVoid = VoidDetector.voidWithin(pos, world, 3, 3);
        double look = Math.max(1.4, horizSpeed * 10.0);

        double[] keyHeading = intent.heading(player.getYaw());
        boolean edgeByKey = keyHeading != null
                && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, look);
        boolean edgeByVel = horizSpeed > 0.04
                && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, look);

        if (nearVoid) {
            intent.sprint = false;
        }
        // Jump suppression uses a LONGER lookahead: a jump carries the bot ~4 blocks, so a
        // rim still 2-3 blocks ahead is exactly where a crit- or brake-jump launches us off.
        double jumpLook = Math.max(3.2, horizSpeed * 16.0);
        boolean jumpTowardEdge =
                (keyHeading != null && VoidDetector.edgeAhead(pos, keyHeading[0], keyHeading[1], world, 3, jumpLook))
                || (horizSpeed > 0.04 && VoidDetector.edgeAhead(pos, vel.x, vel.z, world, 3, jumpLook));
        if (jumpTowardEdge) {
            intent.jump = false;
        }
        if (edgeByKey || edgeByVel) {
            intent.jump = false;
            intent.sneak = true;
            if (edgeByKey) {
                // Only the steering that points AT the drop is cancelled. Momentum-only
                // danger keeps the toward-island input so recovery isn't stranded.
                intent.forward = false;
                intent.back = false;
                intent.left = false;
                intent.right = false;
                intent.sprint = false;
            }
        }
    }

    /** World-space horizontal heading (dx,dz) implied by the pressed movement
     *  keys relative to yaw, or null if none. MC convention: sideways +1 = LEFT. */
    public static double[] pressedHeading(ClientPlayerEntity player, MinecraftClient mc) {
        double fwd = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        if (fwd == 0 && strafe == 0) return null;
        double yawRad = Math.toRadians(player.getYaw());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double dx = strafe * cos - fwd * sin;
        double dz = fwd * cos + strafe * sin;
        return new double[]{dx, dz};
    }
}

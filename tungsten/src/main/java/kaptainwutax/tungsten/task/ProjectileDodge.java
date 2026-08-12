package kaptainwutax.tungsten.task;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

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
        dirX = x;
        dirZ = z;
        holdTicks = Math.max(holdTicks, ticks);
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
            holdTicks = 0;
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
        mc.options.forwardKey.setPressed(fwd > DEADZONE);
        mc.options.backKey.setPressed(fwd < -DEADZONE);
        mc.options.rightKey.setPressed(side > DEADZONE);
        mc.options.leftKey.setPressed(side < -DEADZONE);
        // Sprint only earns its speed going forwards, and a backwards sprint is not a thing.
        mc.options.sprintKey.setPressed(fwd > DEADZONE);
        driveTicks++;

        // ⛔ CLEAR ONLY WHEN NOBODY ELSE OWNS THE KEYS. Fixed after being recorded here.
        //
        // This ran clearKeys() unconditionally on the expiring tick -- at the FINAL-WORD position,
        // i.e. AFTER MovementQueue had already pressed its keys for that tick. So the tick a dodge
        // ended also wiped the WALKER's movement and the bot stalled for one tick. Same
        // "two writers, last one wins" family as the bug this primitive exists to fix, one layer
        // down, and caused by the fix.
        //
        // The asymmetry that makes this correct: Movement.update() RELEASES every key and then
        // presses exactly what its own tick declared. So while the walker drives, nothing of ours
        // survives to this point -- there is nothing to clean up, and clearing only destroys what
        // the walker just set. When nothing drives, our presses DO persist and must be released or
        // they stick. Hence: release only when no other owner is running.
        boolean otherOwner = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.BlockPathWalker.isRunning();
        if (holdTicks == 0 && !otherOwner) {
            clearKeys();
        }
    }
}

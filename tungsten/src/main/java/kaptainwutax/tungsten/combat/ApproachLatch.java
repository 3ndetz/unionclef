package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Keeps the bot walking at a committed target on the ticks where nothing else does.
 *
 * <h2>The defect, measured in blocks rather than ticks</h2>
 *
 * During re-approach -- beyond reach, after the bot has already been in contact once -- 47% of
 * ticks reach the game with NO movement key pressed at all. On those ticks {@code Nav.isPathing()}
 * is true through {@code PATHFINDER.active}, i.e. the search is running, while the walker, the
 * movement queue and the physics executor are all off. Nothing is driving, because the path that
 * would drive is being computed.
 *
 * <p>Counting those ticks made them look negligible: 15% of an engagement, median window one tick.
 * Counting BLOCKS says the opposite, because of what the margin is:
 *
 * <pre>
 *     clean sprint tick    0.244 b/t     net vs a retreating skeleton (0.215)   +0.029
 *     idle tick            0.067 b/t     net                                    -0.148
 * </pre>
 *
 * An idle tick loses five times what a sprint tick gains. At 47% idle the average is 0.160 b/t,
 * BELOW the target's retreat, so the bot loses ground on average and settles at the ~5-block
 * equilibrium the distance histogram shows. At 0% idle the average is 0.244 and it gains. A
 * one-tick key release is not one tick of lost progress; it is about five.
 *
 * <h2>Why a latch and not the combat controller</h2>
 *
 * Handing these ticks to {@code closeQuarters} was tried -- {@code combatCloseOwnsBand} -- and
 * measured WORSE: reachMean 3.53 -> 4.71, inReachRate 0.375 -> 0.143. That controller orbits, holds
 * a strike band and backs off, which is right in contact and wrong while closing. This presses
 * exactly one thing, forward, and only when the tick would otherwise be empty:
 *
 * <ul>
 *   <li>no band, no strafe, no back-off, no jump -- it cannot fight the controller because it never
 *       runs on a tick the controller drove;</li>
 *   <li>only while the defence chain is COMMITTED to a kill (it stamps this class from the branch
 *       that returns 65), and only for a couple of ticks after that stamp, so it dies with the
 *       fight rather than lingering;</li>
 *   <li>only beyond {@link TriggerBot#REACH}: inside reach the swing and the spacing matter more
 *       than another step, and that zone already has an owner.</li>
 * </ul>
 *
 * <h2>Direction, not facing</h2>
 *
 * The heading is converted into the player's own frame and pressed as forward/back/left/right, the
 * same way {@link kaptainwutax.tungsten.task.ProjectileDodge} does, so this never touches the
 * camera and works regardless of where the head is pointing. Pressing a bare "forward" would walk
 * whichever way the crosshair happened to be, and the aim is owned by a different subsystem.
 */
public final class ApproachLatch {

    private ApproachLatch() {
    }

    /** Ticks after the chain's last commit stamp during which the latch may still fire. */
    private static final int FRESH_TICKS = 3;

    /** Set by MobDefenseChain on the branch that commits to a kill. */
    public static volatile Vec3d target;
    /** Client tick at which {@link #target} was stamped. */
    public static volatile long stampedAt;
    /** Ticks the latch actually pressed keys. The mechanism gate for its A/B. */
    public static volatile int latched;
    /** Ticks it was eligible but something else was already driving -- the control for `latched`. */
    public static volatile int declined;

    private static long tickCounter;

    public static void reset() {
        target = null;
        stampedAt = 0;
        latched = 0;
        declined = 0;
        tickCounter = 0;
    }

    /** Called from the chain when it commits to killing something. */
    public static void stamp(Vec3d pos) {
        target = pos;
        stampedAt = tickCounter;
    }

    /**
     * Final-word position: after every other writer, so "nothing else pressed anything" is a fact
     * rather than a guess. Never throws.
     */
    public static void tick(ClientPlayerEntity player) {
        tickCounter++;
        try {
            if (!kaptainwutax.tungsten.TungstenConfig.get().combatApproachLatch) {
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (player == null || mc == null || mc.options == null) {
                return;
            }
            Vec3d tgt = target;
            if (tgt == null || tickCounter - stampedAt > FRESH_TICKS) {
                return;
            }
            Vec3d self = player.getEntityPos();
            double dx = tgt.x - self.x;
            double dz = tgt.z - self.z;
            double flat = Math.sqrt(dx * dx + dz * dz);
            // Inside reach the spacing and the swing are worth more than another step, and that
            // zone already has an owner. Use the flat gap here rather than eyeToHitbox: this is a
            // movement decision, and the vertical component of a jump is not a reason to stop
            // closing (the ground-distance experiment that failed was about the ATTACK gate).
            if (flat <= TriggerBot.REACH || flat > 24.0) {
                return;
            }
            // ⛔ ONLY ON A TICK NOBODY ELSE DROVE. This is what keeps the latch from fighting the
            // pathfinder, the walker, the dodge or the controller: if any of them pressed a
            // direction, they own the tick and this does nothing but count it.
            if (mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                    || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()) {
                declined++;
                return;
            }
            double yaw = Math.toRadians(player.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double rx = -fz, rz = fx;
            double nx = dx / flat, nz = dz / flat;
            double fwd = nx * fx + nz * fz;
            double side = nx * rx + nz * rz;
            final double DEADZONE = 0.25;
            mc.options.forwardKey.setPressed(fwd > DEADZONE);
            mc.options.backKey.setPressed(fwd < -DEADZONE);
            mc.options.rightKey.setPressed(side > DEADZONE);
            mc.options.leftKey.setPressed(side < -DEADZONE);
            // Sprint only earns its speed going forwards, and the whole point is the 0.244 b/t
            // figure, which is a sprinting number.
            mc.options.sprintKey.setPressed(fwd > DEADZONE);
            latched++;
        } catch (Exception ignored) {
            // a movement aid must never be the thing that breaks a fight
        }
    }
}

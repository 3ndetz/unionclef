package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;

/**
 * A tick-by-tick record of one fight: how far away the target was, who owned the legs, and which
 * keys actually reached the game.
 *
 * <h2>Why this exists</h2>
 *
 * Five pre-registered hypotheses about mob_skeleton's approach have now returned null -- widen the
 * engage test, give combat the legs across the band, close to reach instead of to inRange, suppress
 * the orbit, shorten the dodge. Each was plausible, each had a mechanism, and each was judged on an
 * AGGREGATE: total band ticks, mean reach, a per-run counter. Aggregates are what produced four
 * confounded totals in a single day (checklist rule 4t) and they cannot say WHERE a fight's time
 * goes, only how much of it there was.
 *
 * <p>The quantity that matters is ~38 ticks: the gap between entering the killing band and landing
 * the first swing. At a skeleton's ~40-tick shot cycle that is one free arrow, and the gate allows
 * none. Nothing on record says how those 38 ticks are spent, and the remaining candidates each
 * predict a DIFFERENT trace:
 *
 * <ul>
 *   <li>the circle-strafe diluting the approach -> forward held with left/right also held, and the
 *       distance falling at well under sprint speed;</li>
 *   <li>the skeleton simply outrunning the approach -> forward and sprint held cleanly, distance
 *       flat or falling very slowly, no other writer in sight;</li>
 *   <li>the arrow dodge overriding the approach -> dodge=1 on the ticks where forward drops out;</li>
 *   <li>arbitration handing the legs away -> owner flipping while the keys change under it.</li>
 * </ul>
 *
 * One run separates those four. Five series did not, because none of them could see a single tick.
 *
 * <h2>Sampled at the final-word position</h2>
 *
 * Called from {@code MixinClientPlayerEntity} immediately after {@link
 * kaptainwutax.tungsten.task.ProjectileDodge#tick}, i.e. after every writer has had its say, so
 * the keys recorded are the ones the game is about to read rather than the ones some layer asked
 * for. That distinction is the whole history of this file: the dodge and the flee keys were both
 * measured as refuted while being erased before the game ever saw them.
 *
 * <p>Off unless {@link kaptainwutax.tungsten.TungstenConfig#combatTrace} is pinned. A ring buffer
 * of {@link #CAP} ticks is about 20 seconds of fight, which covers an engagement without letting a
 * stalled run grow unbounded -- and a stalled run is exactly the 9%-of-runs case worth catching.
 */
public final class CombatTrace {

    private CombatTrace() {
    }

    /** Ticks retained. 400 is ~20 s, long enough for an engagement and bounded for a stall. */
    private static final int CAP = 400;

    private static final String[] RING = new String[CAP];
    private static int head;
    private static int count;
    private static int tick;

    /** Cleared per run alongside the other counters, so one run's trace is one run's trace. */
    public static synchronized void reset() {
        head = 0;
        count = 0;
        tick = 0;
        java.util.Arrays.fill(RING, null);
    }

    /**
     * Record one tick. Never throws: an instrument must not be the thing that breaks a fight, which
     * is the same guard {@code noticeDraws} carries for the same reason.
     */
    public static synchronized void sample(ClientPlayerEntity player) {
        if (!kaptainwutax.tungsten.TungstenConfig.get().combatTrace) {
            return;
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (player == null || mc == null || mc.options == null || mc.world == null) {
                return;
            }
            Entity nearest = null;
            double bestSq = Double.MAX_VALUE;
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof HostileEntity) || !e.isAlive()) {
                    continue;
                }
                double d2 = e.squaredDistanceTo(player);
                if (d2 < bestSq) {
                    bestSq = d2;
                    nearest = e;
                }
            }
            // ⛔ ONLY FIGHT TICKS GO IN THE RING, and the first version of this did not do that.
            // Sampling every tick meant a 400-tick buffer full of post-kill idling: the first
            // capture read 400 lines of dist=-1 with no hostile alive, and the engagement it was
            // built to show had been overwritten by the wait that followed it. Skipping the ticks
            // with nothing to fight makes the buffer hold the LAST 400 ticks OF A FIGHT, which is
            // the window every question here is about, and it freezes automatically once the target
            // dies rather than needing a stop signal.
            if (nearest == null) {
                return;
            }
            tick++;
            // The SAME metric the swing gate uses. Recording centre-to-centre here and comparing it
            // against a 3.0 eye-to-hitbox threshold is how this course once concluded the bot was
            // holding a distance at which it could never hit -- true, but off by the metric.
            double dist = TriggerBot.eyeToHitbox(player, nearest);
            String keys = ""
                    + (mc.options.forwardKey.isPressed() ? "F" : ".")
                    + (mc.options.backKey.isPressed() ? "B" : ".")
                    + (mc.options.leftKey.isPressed() ? "L" : ".")
                    + (mc.options.rightKey.isPressed() ? "R" : ".")
                    + (mc.options.sprintKey.isPressed() ? "S" : ".")
                    + (mc.options.jumpKey.isPressed() ? "J" : ".");
            // ⛔ MONOTONIC COUNTERS, DIFFED BY THE READER -- NOT controlledThisTick.
            // That flag looks exactly right for this and cannot be used here: DamageWatch clears it
            // in ITS tick, so whether it still reads true at the final-word position depends on an
            // ordering between two subsystems that neither one documents. Recording the running
            // totals instead means the reader sees "this tick incremented ctl" by subtracting the
            // previous line, and no ordering assumption enters the instrument at all.
            RING[head] = String.format(
                    "%d dist=%.2f keys=%s dodge=%d ctl=%d cqe=%d swings=%d stage=%s vel=%.3f hurt=%d",
                    tick, dist, keys,
                    kaptainwutax.tungsten.task.ProjectileDodge.isActive() ? 1 : 0,
                    CombatController.controlTicks,
                    CombatController.cqEntry,
                    TriggerBot.gPassed,
                    CombatController.lastStage == null ? "-" : CombatController.lastStage.name(),
                    Math.sqrt(player.getVelocity().x * player.getVelocity().x
                            + player.getVelocity().z * player.getVelocity().z),
                    player.hurtTime);
            head = (head + 1) % CAP;
            if (count < CAP) {
                count++;
            }
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a fight
        }
    }

    /** The retained ticks, oldest first, one per line. Empty when nothing was recorded. */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        int start = count < CAP ? 0 : head;
        for (int i = 0; i < count; i++) {
            String line = RING[(start + i) % CAP];
            if (line != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}

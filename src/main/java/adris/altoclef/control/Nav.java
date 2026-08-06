package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.util.goals.AltoGoal;

/**
 * The one place altoclef talks to a pathfinder.
 *
 * <h2>Why this exists</h2>
 *
 * G-0 is "stop depending on baritone", and after the goal TYPE (see {@link AltoGoal}) the second
 * thing holding the two together is the ENGINE, reached through {@code mod.getClientBaritone()}.
 * Counted across src/main that is about sixty calls in thirty files, and almost all of them say one
 * of four things: stop navigating, are we navigating, is it safe to interrupt, go here.
 *
 * <p>Scattered like that the dependency cannot be removed — every task would have to be edited on
 * the day the engine changes. Behind this facade it can: the tasks state intent, and WHICH engine
 * serves it is decided here, in one file. When the legacy half goes, it goes from this file only.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It does not change behaviour. Each method does exactly what the call sites do today, including
 * which engine they address — a sweep that quietly alters semantics cannot be measured, and the
 * cancel calls in particular are load-bearing in ways that need their own pass (today
 * {@link #cancel()} stops the legacy engine and leaves a tungsten walk running, because that is
 * what the call sites currently do; the stuck-handler in CustomBaritoneGoalTask calls it every time
 * the progress checker trips, and stopping tungsten there would abort a healthy leg).
 *
 * <p>It is null-safe throughout, which the raw calls were not: {@code getClientBaritone()} returns
 * null when the engine did not initialise, and a task that cancels pathing on that path threw.
 */
public final class Nav {

    private Nav() {
    }

    private static baritone.Baritone engine() {
        AltoClef mod = AltoClef.getInstance();
        return mod == null ? null : mod.getClientBaritone();
    }

    /** Stop navigating. Safe to call when nothing is. */
    public static void cancel() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getPathingBehavior().forceCancel();
        }
    }

    /** Is a route being followed right now? */
    public static boolean isPathing() {
        baritone.Baritone b = engine();
        return b != null && b.getPathingBehavior().isPathing();
    }

    /** Can navigation be interrupted at this instant without leaving the bot mid-air? */
    public static boolean isSafeToCancel() {
        baritone.Baritone b = engine();
        return b == null || b.getPathingBehavior().isSafeToCancel();
    }

    /** Is there a goal set and being worked on? */
    public static boolean hasGoal() {
        baritone.Baritone b = engine();
        return b != null && b.getCustomGoalProcess().isActive();
    }

    /** Forget the current goal. */
    public static void clearGoal() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getCustomGoalProcess().onLostControl();
        }
    }

    /**
     * Is the bot wandering off to look for something it cannot see yet?
     *
     * <p>Exploring is the second-largest thing altoclef says to the engine after the four sentences
     * above: 26 calls across the task tree, and 25 of them are these two questions -- am I
     * exploring, and stop exploring. They belong here for the same reason the others do; the one
     * remaining caller that actually STARTS an exploration passes a target and stays where it is
     * until there is somewhere else to send it.
     */
    public static boolean isExploring() {
        baritone.Baritone b = engine();
        return b != null && b.getExploreProcess().isActive();
    }

    /** Stop exploring. Safe to call when nothing is. */
    public static void stopExploring() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getExploreProcess().onLostControl();
        }
    }
}

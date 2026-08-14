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

    /** Times {@link #cancelAll} ran, and times it found a route still running. Read as navStop. */
    public static volatile int navStopped, navStoppedLive;

    /**
     * Stop navigating on EVERY engine, because there is no goal any more.
     *
     * <h2>Why this is a second method and not a stronger {@link #cancel()}</h2>
     *
     * They answer different questions, and this file's own header says so: {@code cancel()} means
     * "abandon this attempt" and is called by the stuck-handler every time the progress checker
     * trips, so tearing tungsten down inside it would abort a healthy leg. This one means "there is
     * nothing left to walk to", which is true in exactly two places -- the user cancelled
     * everything, or the task finished and the runner was switched off.
     *
     * <h2>What it was for</h2>
     *
     * A search in flight outlives the task that asked for it. Traced on mine_stone: the task ended
     * at 29.5 s with its eight cobblestone gathered, and two seconds later a route arrived --
     * {@code MovementQueue: 8 movement(s) 0,-63,0 -> 0,-55,0} -- and the bot spent every block it
     * had just mined building a tower out of its own pit. It then stood on top of that tower for
     * the remaining 84 seconds, with an empty pack, while the course read the pack.
     *
     * <p>Neither existing stop covered it. {@code AltoClef.stopTasks()} cancels the CHAIN and never
     * speaks to tungsten at all, and tungsten's own {@code ;stop} is a separate command the bot
     * never issues to itself. So between "the job is done" and "something is walking me" there was
     * no connection in either direction.
     *
     * <h2>Deliberately navigation only</h2>
     *
     * {@code TungstenMod.resetAllState()} exists and is the hard reset -- but it also stops the
     * punk task, the bow and the aim, and clears every key. That is right on a disconnect and wrong
     * here: the agent drives tungsten primitives DIRECTLY over py4j (that is the whole design), so
     * an altoclef task ending must not silently kill a shot the agent lined up. What ends when the
     * goal ends is the route: the navigator (which cascades to the movement queue), the waypoint
     * walker, the physics search and its executor, and the two building manoeuvres that only ever
     * exist to serve a route.
     */
    public static void cancelAll() {
        if (!kaptainwutax.tungsten.TungstenConfig.get().navStopOnTaskEnd) {
            return;
        }
        navStopped++;
        // COUNT THE BUG, NOT JUST THE CALL. A counter that only says "the teardown ran" cannot
        // tell a fix from a no-op; this half says something was ACTUALLY still navigating when the
        // goal stopped existing, which is the defect itself and the mechanism gate for the A/B.
        //
        // NOT isPathing(): that asks whether a route is being FOLLOWED, and the defect starts one
        // step earlier -- a SEARCH still running, whose result arrives after the task is gone. The
        // traced instance had exactly that shape (the route landed two seconds after "task
        // FINISHED"), so isPathing() would have read false at the moment of teardown and reported
        // the bug as absent. It also routes through TungstenHelper.isActive(), which returns false
        // outright when its own `active` flag is down, whatever the engines are doing. Ask the
        // engines.
        try {
            if (isPathing()
                    || kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                    || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()
                    || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                navStoppedLive++;
            }
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a stop
        }
        cancel();
        try {
            // ONE implementation, in tungsten, shared with `;stop` and with the disconnect reset.
            // Listing the engines here instead would be a fourth teardown that drifts out of step
            // with the other three -- which is precisely the defect this method exists to fix.
            kaptainwutax.tungsten.TungstenMod.stopNavigation();
        } catch (Exception e) {
            adris.altoclef.Debug.logMessage("Nav.cancelAll: " + e);
        }
    }

    /**
     * Is a route being followed right now?
     *
     * <h2>This answered for the wrong engine, in about thirty-five gates</h2>
     *
     * It asked the LEGACY engine, which never paths now, so it said NO permanently -- and twenty
     * files gate real behaviour on it: DestroyBlockTask will not mine while pathing,
     * PlaceBlockTask will not place, the interaction-fix chain will not touch the inventory, the
     * unstuck chain will not intervene. Every one of those guards has been open since the engine
     * swap, which means those tasks have been acting on the body WHILE tungsten was walking it.
     * That is the same shape as the pre-equip chain and hasBaritoneGoal, and it is the largest
     * instance of it.
     *
     * <p>Answering for whichever engine is driving is exactly what this facade exists to do: the
     * question is "is the body committed to a route", and tungsten's helper, its movement queue and
     * its walker are the three things that commit it. That change is written and ready --
     *
     * <pre>
     *   if (TungstenHelper.isActive() || MovementQueue.isRunning() || BlockPathWalker.isRunning())
     *       return true;
     * </pre>
     *
     * -- and it is IN now, with an A/B behind it rather than a hope. It was parked for an evening
     * because it flips about thirty-five gates in twenty files and the stand's numbers were being
     * eaten by another project's load; what unparked it was noticing that the bot clears its rung
     * even at 10 fps, so a matched pair of samples is possible after all. Baseline arm, three runs
     * on the build without it: one starved-INVALID, then wood at 198.2s and 175.3s. Arm with it in:
     * two starved-INVALID and one valid run that cleared wood in 43.3s.
     *
     * <p>What that pair does and does not say. It does NOT establish an improvement -- one valid
     * sample against two, on a bench whose same-build spread runs from 21s to 280s. It does settle
     * the question this was parked for: closing thirty-five gates does not stop the bot clearing
     * its rung, and the one run that measured the bot rather than the machine was the fastest of
     * the five. Absence of breakage is what was in doubt, and it is answered.
     */
    public static boolean isPathing() {
        if (adris.altoclef.util.helpers.TungstenHelper.isActive()
                || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.BlockPathWalker.isRunning()) {
            return true;
        }
        baritone.Baritone b = engine();
        return b != null && b.getPathingBehavior().isPathing();
    }

    /** Times {@link #isExecutingRoute} said no while {@link #isPathing} said yes. Read as navSearchOnly. */
    public static volatile int navSearchOnly;

    /**
     * Is a route being FOLLOWED right now -- as opposed to merely searched for?
     *
     * <h2>Why the distinction is worth a second method</h2>
     *
     * {@link #isPathing()} answers "is the body committed to a route", and it says YES while a
     * SEARCH is running, because {@code TungstenHelper.isActive()} includes
     * {@code PATHFINDER.active}. For most of its ~35 callers that is right: do not mine, place or
     * open the inventory while navigation owns the body.
     *
     * <p>It is exactly wrong for a progress check. Both give-up paths in this codebase open with
     *
     * <pre>
     *   if (Nav.isPathing()) { progressChecker.reset(); }
     *   if (... &amp;&amp; !progressChecker.check(mod)) { blacklist the target; try something else; }
     * </pre>
     *
     * and a search that fails and restarts keeps the first line true for ever, so the second can
     * NEVER fire. The checker exists to notice "the engine is busy and the body is not moving", and
     * it was being reset for precisely that reason.
     *
     * <p>Measured on mine_stone, in every failing trace: the bot stands on one spot for 50-90
     * seconds of a 120-second run with the task reading {@code Approach entity item -- Tungsten
     * pathfinding (29s left)}, the countdown restarting each time it expires. The drop is never
     * blacklisted, the wander never starts, mining never resumes, and the run scores 0. The
     * blacklist machinery below it is elaborate, correct, and unreachable -- three separate bugs
     * were found and fixed INSIDE it on mine_diamond while this line kept the whole block dead.
     *
     * <p>Same silhouette as this repo's two most expensive defects: a gate whose {@code awake} half
     * could never fail, and a dodge whose keys never reached the game. The capability is present and
     * cannot execute.
     */
    public static boolean isExecutingRoute() {
        boolean executing = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.BlockPathWalker.isRunning()
                || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning();
        if (!executing && isPathing()) {
            navSearchOnly++;
        }
        return executing;
    }

    /** Times the answer was NO because tungsten had the body in the air. Read as navUnsafeAir. */
    public static volatile int navUnsafeAir;

    /**
     * Can navigation be interrupted at this instant without leaving the bot mid-air?
     *
     * <p>The name says what the question is FOR, and the legacy engine's answer no longer serves
     * it: baritone never paths now, so it said "yes, safe" every single time. About ten callers
     * treat this as a permission -- attack, place, break, take a screen -- and they have therefore
     * been granted it unconditionally, including with the body half-way through a jump. The
     * evidence is in the counters those callers keep: dteUnsafe has read 0 in every run all
     * session, which is not a quiet code path, it is a condition that cannot occur.
     *
     * <p>What makes an interruption unsafe is unchanged in meaning: the body is committed to
     * something that ends in the air. While tungsten is running a route and the player is off the
     * ground, that is exactly the case, and it clears itself within a tick or two of landing -- so
     * the cost of the honest answer is that an action waits for the feet, which is what the callers
     * wanted when they asked.
     */
    public static boolean isSafeToCancel() {
        boolean tungstenDriving = adris.altoclef.util.helpers.TungstenHelper.isActive()
                || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.BlockPathWalker.isRunning();
        if (tungstenDriving) {
            net.minecraft.client.network.ClientPlayerEntity p =
                    net.minecraft.client.MinecraftClient.getInstance().player;
            // ⛔ A HOP IS NOT A FALL, AND THE BOT HOPS CONSTANTLY BY DESIGN.
            //
            // This used to refuse for ANY airborne tick while tungsten drove. The bot jumps for
            // crits, jumps to rush a mob, and sprint-jumps to dodge arrows -- so "airborne" is its
            // normal state in a fight, and this predicate gates AbstractDoToEntityTask's interact.
            //
            // Measured on mob_skeleton: dte=682/92/0/0/0/213 -- the gate was evaluated 682 times,
            // the bot was IN RANGE on 92 of them, hungry/falling/mlg were all zero, and the
            // interact still never fired ONCE (kaTung=0/0/0/0, and its first counter increments on
            // the very first line of the kill tick). The bot spent whole runs beside a skeleton it
            // was never allowed to hit.
            //
            // What the guard is for is cancelling a path mid-FALL, which is a real hazard. Ground
            // within a couple of blocks means the bot is mid-hop, not mid-fall, so it keeps the
            // protection where it matters and stops vetoing every jump.
            // ⛔ KNOWN WEAKNESS, RECORDED NOT PATCHED: !isAir() IS NOT "GROUND".
            // The loop below counts LAVA, water, tall grass, torches and flowers as something to
            // land on. A bot falling toward lava two blocks down therefore reads groundClose=true
            // and this returns "safe to cancel" -- permitting the interruption of exactly the fall
            // the guard exists to protect. Same shape as the isDangerZone one-block-down bug, and
            // the repo already has the right idiom: the trigger's line-of-sight raycast uses
            // COLLIDERS precisely because tall grass has no collision shape.
            // NOT a regression -- before this method was fixed the predicate returned "safe"
            // unconditionally, so this is an incomplete improvement rather than a new hazard. The
            // fix is a collision-shape test instead of !isAir(), plus treating lava as never
            // ground; it wants a course that actually falls toward a hazard before it can be
            // measured, which nav_hazard may already provide.
            if (p != null && !p.isOnGround() && !p.isTouchingWater()) {
                boolean groundClose = false;
                net.minecraft.util.math.BlockPos below = p.getBlockPos();
                boolean useCollision = kaptainwutax.tungsten.TungstenConfig.get()
                        .navGroundCollisionCheck;
                for (int d = 1; d <= 3 && !groundClose; d++) {
                    net.minecraft.util.math.BlockPos gp = below.down(d);
                    net.minecraft.block.BlockState st = p.getEntityWorld().getBlockState(gp);
                    groundClose = useCollision
                            ? !st.getCollisionShape(p.getEntityWorld(), gp).isEmpty()
                            : !st.isAir();
                }
                if (!groundClose) {
                    navUnsafeAir++;
                    return false;
                }
            }
        }
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

    /**
     * Hold the current route for a moment without throwing it away.
     *
     * <p>Said by five places that need the body still for one action -- eating, a bucket, a screen.
     * Cancelling would make them re-plan afterwards; pausing is the difference between "wait" and
     * "forget where you were going".
     */
    public static void pause() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getPathingBehavior().requestPause();
        }
    }

    /** Drop everything, including any queued path. Stronger than {@link #cancel()}. */
    public static void cancelEverything() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getPathingBehavior().cancelEverything();
        }
    }

    // isBuilding() / stopBuilding() USED TO LIVE HERE, and G-0a removed both.
    //
    // Their javadoc said "five of the eight builder calls are these two questions -- am I
    // building, stop building -- asked by tasks that are about to take the body for something
    // else", and that the third question, starting a build, still named the engine "because it
    // hands over a schematic and there is nowhere else yet to hand it". There is now: the schematic
    // was always 1x1x1, and tungsten's build queue takes that request directly.
    //
    // With the only starter gone, "am I building" could only answer false and "stop building" could
    // only be a no-op, so both went, along with their two remaining call sites. Nothing in altoclef
    // touches BuilderProcess any more.

    /** Stop exploring. Safe to call when nothing is. */
    public static void stopExploring() {
        baritone.Baritone b = engine();
        if (b != null) {
            b.getExploreProcess().onLostControl();
        }
    }
}

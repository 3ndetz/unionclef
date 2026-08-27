package adris.altoclef.chains;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetOutOfWaterTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedList;
import java.util.Optional;

public class UnstuckChain extends SingleTaskChain {

    private final LinkedList<Vec3d> posHistory = new LinkedList<>();

    /** Times the chain acted on a bot that had a goal, no path and had not moved. */
    public static volatile int strandedRescues;
    /** Frozen-with-a-goal states left alone because a block break was still progressing. */
    public static volatile int strandedSkippedDigging;
    /**
     * Ticks this chain OWNED the bot. A 73%-dead run was captured with Main task:
     * <Shimmying> and everything else idle (pdWalking=0, dbTick=0, wander=0), which raises
     * the question of whether the rescue is now paying for itself. Rescues alone do not
     * answer it -- 12 of them could be a second or a minute. This counts the cost.
     */
    public static volatile int unstuckOwnedTicks;
    /** How stale the break clock must be before a motionless bot counts as stranded. */
    private static final long DIG_GRACE_MS = 6000L;
    /** Which guard last stopped checkGenerallyStuck, and the history size when it did.
     *  Three placement fixes were spent guessing this; naming it is cheaper. */
    public static volatile String lastSkip = "-";
    /** History size at the last "too short" bail. An int, because this is written every tick. */
    public static volatile int lastSkipSize;
    /** The last skip that was NOT "tooShort". lastSkip alone is useless: after a guard clears the
     *  history, every following tick writes tooShort over it, which is how I concluded the check
     *  "never runs" when it runs, fires a guard and rebuilds. */
    public static volatile String lastRealSkip = "-";
    /** Why getPriority() returned BEFORE appending a position: notInGame/paused/noUserTask/
     *  container, against the ticks where it did append. The history never reaches its 200-tick
     *  threshold and no clear can run below it, so the append path is the only suspect left. */
    public static volatile int gpNotInGame, gpPaused, gpNoUserTask, gpContainer, gpAppended;
    private boolean isProbablyStuck = false;
    private int eatingTicks = 0;
    private boolean interruptedEating = false;
    private TimerGame shimmyTaskTimer = new TimerGame(5);
    private boolean startedShimmying = false;
    // Prevent rapid-fire shimmy loops (issue #13): cooldown grows with consecutive detections.
    // Start elapsed (interval=0) so the first detection fires immediately.
    private TimerGame stuckCooldown = new TimerGame(0);
    /**
     * Where the last shimmy rescue fired, and how often a rescue landed near the previous
     * one. THE ESCALATION USED TO BE DEFEATED BY ITS OWN RESCUE: the cooldown climbs
     * 30 -> 60 -> 120s only while detections stay CONSECUTIVE, and the counter was cleared
     * the moment the window showed 1.5 blocks of movement -- movement the shimmy itself had
     * just produced. So the bot froze, got shimmied, drifted, walked back, froze again, and
     * was rescued afresh at 30s forever. That is the shuttling the user reported as worse
     * than the freeze: "walks to a block, goes off, comes back, endlessly".
     */
    private Vec3d lastRescuePos = null;
    private boolean nearCounted = false;
    public static volatile int rescueNearPrevious;
    public static volatile int rescueMovedOn;
    private static final double LEFT_THE_SPOT_SQ = 8.0D * 8.0D;

    private int consecutiveStuckDetections = 0;

    public UnstuckChain(TaskRunner runner) {
        super(runner);
    }


    private void checkStuckInWater() {
        if (posHistory.size() < 100) return;

        ClientWorld world = AltoClef.getInstance().getWorld();
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        // is not in water
        if (!world.getBlockState(player.getSteppingPos()).getBlock().equals(Blocks.WATER)
                && !world.getBlockState(player.getSteppingPos().down()).getBlock().equals(Blocks.WATER))
            return;

        // everything should be fine
        if (player.isOnGround()) {
            posHistory.clear();
            return;
        }

        // do NOT do anything if underwater
        if (player.getAir() < player.getMaxAir()) {
            return;
        }

        Vec3d pos1 = posHistory.get(0);
        for (int i = 1; i < 100; i++) {
            Vec3d pos2 = posHistory.get(i);
            if (Math.abs(pos1.getX() - pos2.getX()) > 0.75 || Math.abs(pos1.getZ() - pos2.getZ()) > 0.75) {
                return;
            }
        }

        posHistory.clear();
        setTask(new GetOutOfWaterTask());
    }

    private void checkStuckInPowderedSnow() {
        AltoClef mod = AltoClef.getInstance();

        PlayerEntity player = mod.getPlayer();
        ClientWorld world = mod.getWorld();

        if (PlayerVer.inPowderedSnow(player)) {
            isProbablyStuck = true;
            BlockPos destroyPos = null;

            Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.POWDER_SNOW);
            if (nearest.isPresent()) {
                destroyPos = nearest.get();
            }

            BlockPos headPos = WorldHelper.toBlockPos(player.getEyePos()).down();
            if (world.getBlockState(headPos).getBlock() == Blocks.POWDER_SNOW) {
                destroyPos = headPos;
            } else if (world.getBlockState(player.getBlockPos()).getBlock() == Blocks.POWDER_SNOW) {
                destroyPos = player.getBlockPos();
            }

            if (destroyPos != null) {
                setTask(new DestroyBlockTask(destroyPos));
            }
        }
    }

    /**
     * Is the bot actually TRYING to move right now? Standing still on purpose
     * (fighting in place, crafting, waiting for a search, reading a menu) is not
     * being stuck, and shimmying through it actively breaks the current action.
     * "Trying" = a movement key is held (by us or by a mod driving the client).
     */
    private boolean isTryingToMove() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()
                || mc.options.jumpKey.isPressed();
    }

    /** In a fight the bot legitimately holds a small area (strafe/kite around a target). */
    private boolean isInCombat(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        if (player.hurtTime > 0) return true;                       // being hit right now
        return adris.altoclef.util.helpers.TungstenHelper.isCombatActive();
    }

    private void checkGenerallyStuck() {
        // !! AN INSTRUMENT MUST NOT COST THE THING IT MEASURES -- and this one did.
        // The first version built "tooShort/" + size on EVERY tick of EVERY course, unconditionally,
        // i.e. a string allocation per tick in the shipped path just to record a diagnostic nobody
        // reads unless a flag is on. TaskRunner's own note says the same thing one file over.
        // Now the number is an int (free) and the string is only assembled when someone asks.
        if (posHistory.size() < 200) { lastSkipSize = posHistory.size(); return; }

        boolean strandedWithGoal = false;
        if (kaptainwutax.tungsten.TungstenConfig.get().unstuckWhenGoalButNoPath
                && !isTryingToMove() && posHistory.size() >= 200) {
            Vec3d first = posHistory.getFirst();
            boolean frozen = true;
            for (Vec3d v : posHistory) {
                if (v.squaredDistanceTo(first) > 0.25) { frozen = false; break; }
            }
            AltoClef ac = AltoClef.getInstance();
            boolean hasGoal = ac != null && ac.getUserTaskChain() != null
                    && ac.getUserTaskChain().isActive();
            // DIGGING IS NOT STRANDED, AND THE KEY-PRESS TEST CANNOT TELL THEM APART.
            // A bot mining downward stands still with an active goal and presses nothing,
            // exactly like a bot walled into a hole. Judged on keys alone this rescue fired
            // 127 times in ONE course and took mine_diamond red with it. So ask for the one
            // signal that separates them: has a block break MADE PROGRESS recently. The
            // digger keeps that clock fresh every tick; the wedged bot -- which is wandering,
            // not mining (wander=4125 against wanderMoved=0) -- lets it go stale.
            boolean digging = adris.altoclef.control.PlayerExtraController.lastBreakProgressMs > 0
                    && System.currentTimeMillis()
                       - adris.altoclef.control.PlayerExtraController.lastBreakProgressMs < DIG_GRACE_MS;
            if (frozen && hasGoal && digging) strandedSkippedDigging++;
            strandedWithGoal = frozen && hasGoal && !digging;
            if (strandedWithGoal) strandedRescues++;
        }


        // Tungsten-primary (drop-in swap): tungsten drives movement and handles
        // its own stuck recovery (executor). The shimmy would preempt the user
        // task chain every tick, starving the tungsten-primary hook — never fire.
        // !! THIS EXEMPTION IS WHY NOTHING EVER RESCUES A STRANDED BOT.
        // Its reasoning is sound and stays: tungsten drives movement, so a shimmy here would
        // preempt the user chain every tick. But it rests on "tungsten handles its own stuck
        // recovery", and on mine_stone that does not happen -- the bot climbs onto the arena wall
        // at y=-57 and stands there to the end of the run, in ~30% of runs.
        //
        // Established by elimination rather than by guessing, after three blind patches that
        // deserved none of the time: gp=370/0/0/0/0 (getPriority appended 370 positions, zero early
        // returns) against a history of 75-149 proved the history WAS reaching 200 and being
        // cleared, and lastRealSkip="-" ruled out combat, tungsten-isActive and no-keys. This was
        // the only early return left.
        //
        // So the exemption keeps its job and gains the one exception it lacked: a bot with a live
        // goal, no path and a position frozen across the whole window is not something tungsten is
        // about to recover -- it has already had ten seconds to.
        if (adris.altoclef.util.helpers.TungstenHelper.isPrimary() && !strandedWithGoal) {
            lastRealSkip = lastSkip = "primary/" + posHistory.size();
            posHistory.clear();
            startedShimmying = false;
            // MOVING IS NOT PROGRESS, AND THIS RESET COULD NOT TELL THE DIFFERENCE.
            // The cooldown climbs 30 -> 60 -> 120s only while detections stay CONSECUTIVE,
            // and this line runs on EVERY tick the bot is not frozen -- including the ticks
            // immediately after a shimmy, which displaces the bot by design. So the escalation
            // was extinguished by its own rescue and every rescue restarted at 30s forever:
            // freeze, shimmy, drift, walk back, freeze. That is the shuttling reported as
            // worse than the freeze -- "walks to a block, goes off, comes back, endlessly".
            // Ask what separates a rescue that WORKED from one that merely jiggled: is the
            // bot somewhere else now. Come back to the same spot and the cooldown keeps
            // climbing, so the thrashing decays on its own instead of running out the clock.
            if (consecutiveStuckDetections > 0
                    && kaptainwutax.tungsten.TungstenConfig.get().rescueEscalationSurvivesItsOwnShimmy) {
                net.minecraft.client.MinecraftClient mc0 = MinecraftClient.getInstance();
                Vec3d here = mc0 != null && mc0.player != null ? mc0.player.getPos() : null;
                if (lastRescuePos == null || here == null
                        || here.squaredDistanceTo(lastRescuePos) > LEFT_THE_SPOT_SQ) {
                    rescueMovedOn++;
                    nearCounted = false;
                    consecutiveStuckDetections = 0;
                    lastRescuePos = null;
                } else if (!nearCounted) {
                    // ONCE PER RESCUE, NOT ONCE PER TICK. This branch runs on every tick the
                    // bot is not frozen, so the raw increment counted TICKS and made back21
                    // look like twenty-one rescues when it was twenty-one ticks. away++ resets
                    // the detection immediately, so it was already per-event -- the pair was
                    // asymmetric and the ratio meant nothing. Ninth bad instrument, and the
                    // only one whose number I had already quoted before catching it.
                    nearCounted = true;
                    rescueNearPrevious++;
                }
            } else {
                consecutiveStuckDetections = 0;
            }
            return;
        }

        AltoClef mod = AltoClef.getInstance();

        // Don't interfere with eating or mining
        if (mod.getFoodChain().isTryingToEat()) return;
        if (mod.getControllerExtras().isBreakingBlock()) return;
        // Only trigger when there's an active user task
        if (!mod.getUserTaskChain().isActive()) return;

        // ⭐ FALSE-POSITIVE GUARDS (user 2026-07-24: "Stuck fix активируется ПОСТОЯННО
        // даже когда не застряли", GitHub issue). "No displacement" alone is NOT stuck:
        //  - COMBAT holds a small area on purpose (circle-strafe/kite around a target);
        //    shimmying mid-fight throws the aim away and gets the bot killed.
        //  - TUNGSTEN driving (search running / executor / walker) owns the movement;
        //    the old code only skipped when tungsten was PRIMARY, so every non-primary
        //    tungsten segment (follow, punk approach, ;goto) could be shimmied into.
        //  - NOTHING PRESSED means the bot is deliberately standing (waiting for a
        //    search, a craft, a menu) — a shimmy there is pure damage.
        if (isInCombat(mod)) { lastRealSkip = lastSkip = "combat/" + posHistory.size(); posHistory.clear(); return; }
        // !! THE DISCRIMINATOR MUST COME BEFORE THIS GUARD, NOT AFTER IT.
        // TungstenHelper.isActive() is true while PATHFINDER.active -- which is EXACTLY the
        // stranded state, a search spinning with no path to show for it. Placed after it, the
        // stranded check could never run: measured as stranded=0 with the flag pinned ON, i.e. a
        // flag that could not fire and a 40-launch series that was void by its own mechanism gate.
        if (adris.altoclef.util.helpers.TungstenHelper.isActive() && !strandedWithGoal) {
            lastRealSkip = lastSkip = "tungsten/" + posHistory.size();
            posHistory.clear();
            return;
        }
        // !! "PRESSES NO KEYS" CONFLATES TWO STATES, AND ONE OF THEM IS BEING STUCK.
        //
        // The guard below is right about waiting on purpose -- shimmying at an open chest or
        // mid-craft is worse than standing still, and that is why it exists. But a bot that has a
        // GOAL and no PATH also presses nothing, and it is not waiting: it is stranded.
        //
        // Measured on mine_stone: the bot digs a pit, climbs out onto the arena rim wall at y=-57
        // and stands there for the rest of the run with path=-1 and nothing in reach. In the n=20
        // baseline that is six runs of ZERO against eight of 8-9 -- the whole remaining failure of
        // the rung. UnstuckChain is what should recover it and never runs, because this guard, the
        // TungstenHelper one above and Nav.isPathing() below are ALL true in exactly that state.
        //
        // So distinguish the two rather than remove the guard: a goal that exists, a position that
        // has not moved for the whole history window, and no keys, is stranded, not patient.
        // Everything the guard protects -- chests, crafting, menus, combat -- is still excluded by
        // the checks around it, which are unchanged.
        // !! AND THE CLEAR IS WHAT DESTROYS THE EVIDENCE THE CHECK NEEDS.
        // strandedWithGoal cannot be true until posHistory has 200 entries (~10 s), and while the
        // bot is stranded !isTryingToMove() is true every tick -- so this line cleared the history
        // on every one of them and it never reached 200. Chicken and egg: the guard wiped the very
        // record its own exception depends on, which is why the flag measured stranded=0 with the
        // counter pinned on and two placement fixes already spent.
        //
        // So while a goal is live, RETURN without clearing. The bot still does nothing (this chain
        // takes no action on that path), but the position record survives long enough for
        // "frozen with a goal and no path" to become decidable. Everything else -- no goal, a
        // menu, combat -- clears as before.
        if (!isTryingToMove() && !strandedWithGoal) {
            boolean goalLive = mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive();
            lastRealSkip = lastSkip = "noKeys/" + posHistory.size() + "/goal=" + goalLive;
            if (!goalLive) {
                posHistory.clear();
            }
            return;
        }

        // Don't trigger when baritone is actively pathfinding (calculating a path)
        if (Nav.isPathing()) {
            posHistory.clear();
            return;
        }

        // Don't trigger when any GUI/container is open (bot is interacting with inventory, chest, etc.)
        if (MinecraftClient.getInstance().currentScreen != null) {
            posHistory.clear();
            return;
        }

        // Don't trigger when interacting with a container (different from currentScreen in some contexts)
        if (StorageHelper.isChestOpen() || StorageHelper.isBlastFurnaceOpen()
                || StorageHelper.isSmokerOpen() || StorageHelper.isBigCraftingOpen()) {
            posHistory.clear();
            return;
        }

        Vec3d current = posHistory.getFirst();
        Vec3d old = posHistory.get(199);

        double dx = Math.abs(current.getX() - old.getX());
        double dz = Math.abs(current.getZ() - old.getZ());
        double dy = Math.abs(current.getY() - old.getY());

        if (dx < 1.5 && dz < 1.5 && dy < 1.5) {
            // Cooldown check: don't re-trigger shimmy too frequently (issue #13)
            if (!stuckCooldown.elapsed()) {
                posHistory.clear();
                return;
            }
            consecutiveStuckDetections++;
            // Exponential backoff: 30s → 60s → 120s max 120s
            int cooldownSec = Math.min(30 << (consecutiveStuckDetections - 1), 120);
            stuckCooldown = new TimerGame(cooldownSec);
            Debug.logMessage("Bot appears generally stuck (no movement for ~10s), triggering shimmy (cooldown=" + cooldownSec + "s, detection #" + consecutiveStuckDetections + ")");
            startedShimmying = true;
            shimmyTaskTimer.reset();
            lastRescuePos = current;
            nearCounted = false;
            posHistory.clear();
        } else {
            // UNREACHABLE IN THE TUNGSTEN-PRIMARY PATH, PROVEN BY back0/away0 AGAINST
            // stranded=45/own404: reaching this line at all requires passing the primary
            // exemption, which only a FROZEN bot passes -- so the frozen branch above always
            // wins and this else never runs. The live reset is the one inside that exemption.
            if (consecutiveStuckDetections > 0) {
                consecutiveStuckDetections = 0;
            }
        }
    }

    private void checkStuckOnEndPortalFrame(AltoClef mod) {
        BlockState state = mod.getWorld().getBlockState(mod.getPlayer().getSteppingPos());

        // if we are standing on an end portal frame that is NOT filled, get off otherwise we will get stuck
        if (state.getBlock() == Blocks.END_PORTAL_FRAME && !state.get(EndPortalFrameBlock.EYE)) {
            if (!mod.getFoodChain().isTryingToEat()) {
                isProbablyStuck = true;

                // for now let's just hope the other mechanisms will take care of cases where moving forward will get us in danger
                mod.getInputControls().tryPress(Input.MOVE_FORWARD);
            }
        }
    }

    private void checkEatingGlitch() {
        FoodChain foodChain = AltoClef.getInstance().getFoodChain();

        if (interruptedEating) {
            foodChain.shouldStop(false);
            interruptedEating = false;
        }

        if (foodChain.isTryingToEat()) {
            eatingTicks++;
        } else {
            eatingTicks = 0;
        }

        if (eatingTicks > 7*20) {
            Debug.logMessage("the bot is probably stuck trying to eat... resetting action");
            foodChain.shouldStop(true);

            eatingTicks = 0;
            interruptedEating = true;
            isProbablyStuck = true;
        }
    }

    @Override
    public float getPriority() {
        if (mainTask instanceof GetOutOfWaterTask && mainTask.isActive()) {
            unstuckOwnedTicks++;
            return 55;
        }

        isProbablyStuck = false;

        AltoClef mod = AltoClef.getInstance();

        if (!AltoClef.inGame()) { gpNotInGame++; return Float.NEGATIVE_INFINITY; }
        if (MinecraftClient.getInstance().isPaused()) { gpPaused++; return Float.NEGATIVE_INFINITY; }
        if (!mod.getUserTaskChain().isActive()) { gpNoUserTask++; return Float.NEGATIVE_INFINITY; }

        if (StorageHelper.isBlastFurnaceOpen() || StorageHelper.isSmokerOpen() || StorageHelper.isChestOpen() || StorageHelper.isBigCraftingOpen()) {
            gpContainer++;
            return Float.NEGATIVE_INFINITY;
        }

        PlayerEntity player = mod.getPlayer();
        gpAppended++;
        posHistory.addFirst(player.getPos());
        if (posHistory.size() > 500) {
            posHistory.removeLast();
        }

        checkStuckInWater();
        checkStuckInPowderedSnow();
        checkEatingGlitch();
        checkStuckOnEndPortalFrame(mod);
        checkGenerallyStuck();


        if (isProbablyStuck) {
            unstuckOwnedTicks++;
            return 55;
        }

        if (startedShimmying && !shimmyTaskTimer.elapsed()) {
            setTask(new SafeRandomShimmyTask());
            unstuckOwnedTicks++;
            return 55;
        }
        startedShimmying = false;

        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public String getName() {
        return "Unstuck Chain";
    }
}

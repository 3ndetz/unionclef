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
    private boolean isProbablyStuck = false;
    private int eatingTicks = 0;
    private boolean interruptedEating = false;
    private TimerGame shimmyTaskTimer = new TimerGame(5);
    private boolean startedShimmying = false;
    // Prevent rapid-fire shimmy loops (issue #13): cooldown grows with consecutive detections.
    // Start elapsed (interval=0) so the first detection fires immediately.
    private TimerGame stuckCooldown = new TimerGame(0);
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
        if (posHistory.size() < 200) return; // ~10 seconds of ticks

        // Tungsten-primary (drop-in swap): tungsten drives movement and handles
        // its own stuck recovery (executor). The shimmy would preempt the user
        // task chain every tick, starving the tungsten-primary hook — never fire.
        if (adris.altoclef.util.helpers.TungstenHelper.isPrimary()) {
            posHistory.clear();
            startedShimmying = false;
            consecutiveStuckDetections = 0;
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
        if (isInCombat(mod)) { posHistory.clear(); return; }
        // !! THE DISCRIMINATOR MUST COME BEFORE THIS GUARD, NOT AFTER IT.
        // TungstenHelper.isActive() is true while PATHFINDER.active -- which is EXACTLY the
        // stranded state, a search spinning with no path to show for it. Placed after it, the
        // stranded check could never run: measured as stranded=0 with the flag pinned ON, i.e. a
        // flag that could not fire and a 40-launch series that was void by its own mechanism gate.
        boolean strandedWithGoal = false;
        if (kaptainwutax.tungsten.TungstenConfig.get().unstuckWhenGoalButNoPath
                && !isTryingToMove() && posHistory.size() >= 200) {
            Vec3d first = posHistory.getFirst();
            boolean frozen = true;
            for (Vec3d v : posHistory) {
                if (v.squaredDistanceTo(first) > 0.25) { frozen = false; break; }
            }
            boolean hasGoal = mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive();
            strandedWithGoal = frozen && hasGoal;
            if (strandedWithGoal) strandedRescues++;
        }
        if (adris.altoclef.util.helpers.TungstenHelper.isActive() && !strandedWithGoal) {
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
        if (!isTryingToMove() && !strandedWithGoal) { posHistory.clear(); return; }

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
            posHistory.clear();
        } else {
            // Bot is moving — reset consecutive counter
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
            return 55;
        }

        isProbablyStuck = false;

        AltoClef mod = AltoClef.getInstance();

        if (!AltoClef.inGame() || MinecraftClient.getInstance().isPaused() || !mod.getUserTaskChain().isActive())
            return Float.NEGATIVE_INFINITY;

        if (StorageHelper.isBlastFurnaceOpen() || StorageHelper.isSmokerOpen() || StorageHelper.isChestOpen() || StorageHelper.isBigCraftingOpen()) {
            return Float.NEGATIVE_INFINITY;
        }

        PlayerEntity player = mod.getPlayer();
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
            return 55;
        }

        if (startedShimmying && !shimmyTaskTimer.elapsed()) {
            setTask(new SafeRandomShimmyTask());
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

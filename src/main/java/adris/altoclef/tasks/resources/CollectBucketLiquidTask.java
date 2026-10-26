package adris.altoclef.tasks.resources;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.function.Predicate;

public class CollectBucketLiquidTask extends ResourceTask {

    private final HashSet<BlockPos> blacklist = new HashSet<>();
    private final TimerGame tryImmediatePickupTimer = new TimerGame(3);
    private final TimerGame pickedUpTimer = new TimerGame(0.5);
    private final int count;

    private final Item target;
    private final Block toCollect;
    private final String liquidName;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();

    private boolean wasWandering = false;

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        super(filledBucket, targetCount);
        this.liquidName = liquidName;
        target = filledBucket;
        count = targetCount;
        this.toCollect = toCollect;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        // Track fluids
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == toCollect);

        mod.setAvoidUpdatingFallingBlocks(true);
        //_blacklist.clear();

        progressChecker.reset();
    }


    @Override
    protected Task onTick() {
        Task result = super.onTick();
        // Reset our "first time" timeout/wander flag.
        if (!thisOrChildAreTimedOut()) {
            wasWandering = false;
        }
        return result;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // TODOS.md, the stall-detector-wipe pattern already fixed in TimeoutWanderTask/
        // DestroyBlockTask: a mere search makes Nav.isPathing() true too, so resetting
        // unconditionally could hide a genuine stall for as long as a search never resolves.
        progressChecker.resetIfPathingWithGrace(mod, Nav.isPathing());
        // If we're standing inside a liquid, go pick it up.
        if (tryImmediatePickupTimer.elapsed() && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            Block standingInside = mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock();
            if (standingInside == toCollect && WorldHelper.isSourceBlock(mod.getPlayer().getBlockPos(), false)) {
                setDebugState("Trying to collect (we are in it)");
                mod.getInputControls().forceLook(0, 90);
                // (G-0: the legacy look behaviour that stood here is gone.)
                //Debug.logMessage("Looking at " + _toCollect + ", picking up right away.");
                tryImmediatePickupTimer.reset();
                if (mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    pickedUpTimer.reset();
                    progressChecker.reset();
                }
                return null;
            }
        }

        if (!pickedUpTimer.elapsed()) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            progressChecker.reset();
            // Wait for force pickup
            return null;
        }

        // Get buckets if we need em
        int bucketsNeeded = count - mod.getItemStorage().getItemCount(Items.BUCKET) - mod.getItemStorage().getItemCount(target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }

        Predicate<BlockPos> isSafeSourceLiquid = blockPos -> {
            if (blacklist.contains(blockPos)) return false;
            if (!WorldHelper.canReach(blockPos)) return false;
            if (!WorldHelper.canReach(blockPos.up())) return false; // We may try reaching the block above.
            assert MinecraftClient.getInstance().world != null;

            Block above = mod.getWorld().getBlockState(blockPos.up()).getBlock();
            // We break the block above. If it's bedrock, ignore.
            if (above == Blocks.BEDROCK || above == Blocks.WATER) {
                return false;
            }
            // ⛔ A SUBMERGED SOURCE CANNOT BE SCOOPED (2026-10-15). With the same liquid above it,
            // the SOURCE_ONLY ray always meets the upper source first, so getReach(blockPos) is
            // never true; the fallback then asks to get within 1 of blockPos.up() -- a lava cell --
            // which every route refuses as lethal. Measured: three of three 15-minute runs from
            // full59-before-portal stuck in "Collecting lava"; one stood 1.5 blocks from its pool
            // for 3.5 minutes on "Getting within 1 blocks of (-110,45,1072)" -> "next movements are
            // lethal" -> replan, the target being the source UNDER that cell. A lake always has a
            // surface source; take those. Flowing liquid above is transparent to SOURCE_ONLY, so
            // only a SOURCE above disqualifies.
            if (above == toCollect && WorldHelper.isSourceBlock(blockPos.up(), false)) {
                return false;
            }

            // check if surrounding blocks are not water, so it doesn't spill everywhere
            for (Direction direction : Direction.values()) {
                if (direction.getAxis().isVertical()) continue;

                if (mod.getWorld().getBlockState(blockPos.up().offset(direction)).getBlock() == Blocks.WATER) {
                    return false;
                }
            }

            return WorldHelper.isSourceBlock(blockPos, false);
        };

        // Find nearest water and right click it
        if (mod.getBlockScanner().anyFound(isSafeSourceLiquid, toCollect)) {
            // We want to MINIMIZE this distance to liquid.
            setDebugState("Trying to collect...");
            //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);

            return new DoToClosestBlockTask(blockPos -> {
                // Clear above if lava because we can't enter.
                // but NOT if we're standing right above.
                if (mod.getWorld().getBlockState(blockPos.up()).isSolid()) {
                    if (!progressChecker.check(mod)) {
                        Nav.cancelEverything();
                        Nav.cancel();
                        Nav.stopExploring();
                        Nav.clearGoal();
                        Debug.logMessage("Failed to break, blacklisting.");
                        mod.getBlockScanner().requestBlockUnreachable(blockPos);
                        blacklist.add(blockPos);
                    }
                    return new DestroyBlockTask(blockPos.up());
                }

                if (tries > 75) {
                    if (timeoutTimer.elapsed()) {
                        tries = 0;
                    }
                    mod.log("trying to wander "+timeoutTimer.getDuration());
                    return new TimeoutWanderTask();
                }
                timeoutTimer.reset();

                // We can reach the block.
                if (LookHelper.getReach(blockPos).isPresent() &&
                        Nav.isSafeToCancel()) {
                    tries++;
                    approachTarget = null; // in reach: the approach clock starts again next time
                    return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), blockPos, toCollect != Blocks.LAVA, new Vec3i(0, 1, 0));
                }
                // Get close enough.
                // up because if we go below we'll try to move next to the liquid (for lava, not a good move)
                if (this.thisOrChildAreTimedOut() && !wasWandering) {
                    mod.getBlockScanner().requestBlockUnreachable(blockPos.up());
                    wasWandering = true;
                }
                // ⛔ AN APPROACH THAT STOPS CLOSING IN GIVES THE SOURCE UP (2026-10-26). GetCloseToBlockTask
                // only ever SHRINKS its radius, and beside a lava pool there may be no safe cell nearer
                // than the one it stands on. Measured (native30 on 0.95.64): 14 minutes at
                // (-256.4,29,1100.9) on "Getting within 3 blocks of -256,26,1103", 4.6 blocks from the
                // source -- a tenth past the bucket's reach -- then a route into the lava and a death.
                // Same answer as baritone's GetToBlockProcess blacklistClosestOnFailure: give this one
                // up and take the next source. AbstractDoToClosestObjectTask's own 180 s ceiling did
                // fire, but with nothing else pursued its ban is lifted at once and the chooser hands
                // the same source back; the blacklist here goes through isSafeSourceLiquid and holds.
                // The clock survives a restart and pauses while this is not ticked: measured on the
                // first build, Unstuck Chain interrupted the stall every ~25 s, and a clock reset on
                // onResourceStart never reached 20 s (approachGivenUp=0 after minutes in place).
                long nowMs = System.currentTimeMillis();
                if (approachLastTickMs > 0 && nowMs - approachLastTickMs > 1_000) {
                    approachSinceMs += nowMs - approachLastTickMs;
                }
                approachLastTickMs = nowMs;
                double dist = mod.getPlayer().getPos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(blockPos));
                if (!blockPos.equals(approachTarget)) {
                    approachTarget = blockPos;
                    approachBest = dist;
                    approachSinceMs = System.currentTimeMillis();
                } else if (dist < approachBest - 0.5) {
                    approachBest = dist;
                    approachSinceMs = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - approachSinceMs > APPROACH_PATIENCE_MS) {
                    approachGivenUp++;
                    Debug.logMessage("No closer to the liquid at " + blockPos.toShortString() + " for "
                            + APPROACH_PATIENCE_MS / 1000 + " s; trying another source");
                    blacklist.add(blockPos);
                    approachTarget = null;
                    return null;
                }
                return new GetCloseToBlockTask(blockPos.up());
            }, isSafeSourceLiquid, toCollect);
        }

        // Dimension
        if (toCollect == Blocks.WATER && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // Oof, no liquid found.
        setDebugState("Searching for liquid by wandering around aimlessly");

        return new TimeoutWanderTask();
    }
    int tries = 0;
    TimerGame timeoutTimer = new TimerGame(2);
    /** Approach progress toward the current source; see collectFrom. */
    private static final long APPROACH_PATIENCE_MS = 20_000;
    private BlockPos approachTarget;
    private double approachBest;
    private long approachSinceMs;
    private long approachLastTickMs;
    public static volatile int approachGivenUp;

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        //mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);

        mod.setAvoidUpdatingFallingBlocks(false);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBucketLiquidTask task) {
            if (task.count != count) return false;
            return task.toCollect == toCollect;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + count + " " + liquidName + " buckets";
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }

    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

}

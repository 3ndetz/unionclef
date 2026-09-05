package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.*;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PickupDroppedItemTask extends AbstractDoToClosestObjectTask<ItemEntity> implements ITaskRequiresGrounded {
    private static final Task getPickaxeFirstTask = new SatisfyMiningRequirementTask(MiningRequirement.STONE);
    // Not clean practice, but it helps keep things self contained I think.
    private static boolean isGettingPickaxeFirstFlag = false;
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final ItemTarget[] itemTargets;

    // This happens all the time in mineshafts and swamps/jungles
    private final Set<ItemEntity> _blacklist = new HashSet<>();
    // Prevent soft lock when there are too many items on the ground
    private static final int MAX_BLACKLIST_SIZE = 50;
    private final boolean _freeInventoryIfFull;
    Block[] annoyingBlocks = new Block[]{
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            Blocks.SHORT_GRASS
    };
    private Task unstuckTask = null;
    // Am starting to regret not making this a singleton
    private AltoClef _mod;
    private boolean _collectingPickaxeForThisResource = false;
    private ItemEntity _currentDrop = null;
    /** Which drop the current pursuit is about, and when it started -- see the budget above. */
    // THE PURSUIT CLOCK BELONGS TO THE TARGET, NOT TO THE TASK INSTANCE.
    // These were per-instance, and this task is REBUILT constantly -- the freeze dump of
    // the slow opening shows 'Pickup Dropped Items' at two levels of one chain, the bot
    // bouncing pickup -> wander -> pickup. Every rebuild restarted the clock at zero, so a
    // pursuit could never spend its two-minute budget: dropBudget=0 across a ten-minute run
    // whose first rung took 299 s, with TimeoutWanderTask:255x2032 and wanderMoved=0 while
    // a wooden_pickaxe sat 2.3 blocks away and two blocks down.
    // Static, so 'this drop has already cost two minutes' survives the rebuild and reaches
    // requestEntityUnreachable, which is global anyway.
    private static ItemEntity pursuitTarget = null;
    private static long pursuitStartMs = 0L;
    /** Clock restarts (target genuinely changed) and the longest pursuit seen, in seconds. */
    public static volatile int pursuitRestarts, pursuitMaxSec;
    /** Two minutes: a drop worth a minute of walking is worth having, one that took two is not. */
    private static final long PURSUIT_BUDGET_MS = 120_000L;
    /** Drops abandoned because the pursuit ran past its budget. */
    public static volatile int dropBudgetSpent;

    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull) {
        this.itemTargets = itemTargets;
        _freeInventoryIfFull = freeInventoryIfFull;
    }

    public PickupDroppedItemTask(ItemTarget target, boolean freeInventoryIfFull) {
        this(new ItemTarget[]{target}, freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount, boolean freeInventoryIfFull) {
        this(new ItemTarget(item, targetCount), freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount) {
        this(item, targetCount, true);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.add(1,0,0),
                pos.add(-1,0,0),
                pos.add(0,0,1),
                pos.add(0,0,-1),
                pos.add(1,0,-1),
                pos.add(1,0,1),
                pos.add(-1,0,-1),
                pos.add(-1,0,1)
        };
    }

    public static boolean isIsGettingPickaxeFirst(AltoClef mod) {
        return isGettingPickaxeFirstFlag && mod.getModSettings().shouldCollectPickaxeFirst();
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        // ⛔ FIXED 2026-09-05: same copy-paste bug found and fixed in the sibling
        // GetToEntityTask.isAnnoying() in this same package -- the loop returned
        // unconditionally on its FIRST iteration regardless of match, so only
        // annoyingBlocks[0] was ever actually compared and the other entries (nether
        // sprouts, cave/twisting/weeping vines, ladder, dripleaf, tall/short grass, sweet
        // berry bush) were silently never checked. CustomBaritoneGoalTask.isAnnoying() in
        // this same package has the correct loop shape this was ported from.
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (annoyingBlocks != null) {
            for (Block annoyingBlock : annoyingBlocks) {
                if (block == annoyingBlock) return true;
            }
        }
        return block instanceof DoorBlock ||
                block instanceof FenceBlock ||
                block instanceof FenceGateBlock ||
                block instanceof FlowerBlock;
    }

    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.up())) return p.up();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.up());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    public boolean isCollectingPickaxeForThis() {
        return _collectingPickaxeForThisResource;
    }

    @Override
    protected void onStart() {
        wanderTask.reset();
        progressChecker.reset();
        stuckCheck.reset();
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    /** Traced pickup decisions; see the note at the branch. */
    private static volatile int puTrace = 0;

    @Override
    protected Task onTick() {
        // Prevent soft lock: if we've blacklisted too many items, there are too many drops.
        // Clear the blacklist and wander to let the area settle / items despawn.
        if (_blacklist.size() > MAX_BLACKLIST_SIZE) {
            Debug.logMessage("Too many blocked items (" + _blacklist.size() + "), clearing blacklist and wandering.");
            _blacklist.clear();
            wanderTask.reset();
            setDebugState("Too many items, wandering.");
            return wanderTask;
        }

        if (wanderTask.isActive() && !wanderTask.isFinished()) {
            setDebugState("Wandering.");
            return wanderTask;
        }
        AltoClef mod = AltoClef.getInstance();

        // A SEARCH IS NOT PROGRESS. isPathing() is true while the pathfinder is merely LOOKING, and
        // a search that fails and restarts keeps it true for ever -- so this line reset the very
        // checker that the give-up path below depends on, and that path could never run. Measured:
        // the bot stands on one spot for 50-90 s of a 120-second run with "Approach entity item --
        // Tungsten pathfinding (29s left)" restarting, never blacklisting the drop, never
        // wandering, never mining again. See Nav.isExecutingRoute.
        if (kaptainwutax.tungsten.TungstenConfig.get().progressCheckIgnoresSearch
                ? Nav.isExecutingRoute() : Nav.isPathing()) {
            progressChecker.reset();
        }
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            Nav.clearGoal();
            Nav.stopExploring();
            return unstuckTask;
        }
        if (!progressChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            stuckCheck.reset();
        }
        _mod = mod;

        // If we're getting a pickaxe for THIS resource...
        // WHY IS THE PICKUP NOT PICKING UP? The mine_stone course breaks blocks, the drops lie on the
        // floor (rcon: three item entities) and the pack stays empty. This branch can divert the
        // pickup into fetching a better pickaxe, and it asks for a STONE one -- which needs the very
        // cobblestone that is not being collected. Print its three inputs rather than assume.
        if (puTrace < 6) {
            puTrace++;
            Debug.logMessage("PICKUPDEC gettingPickaxeFirst=" + isIsGettingPickaxeFirst(mod)
                    + " forThisResource=" + _collectingPickaxeForThisResource
                    + " stoneMet=" + StorageHelper.miningRequirementMetInventory(MiningRequirement.STONE));
        }
        if (isIsGettingPickaxeFirst(mod) && _collectingPickaxeForThisResource && !StorageHelper.miningRequirementMetInventory(MiningRequirement.STONE)) {
            progressChecker.reset();
            setDebugState("Collecting pickaxe first");
            return getPickaxeFirstTask;
        } else {
            if (StorageHelper.miningRequirementMetInventory(MiningRequirement.STONE)) {
                isGettingPickaxeFirstFlag = false;
            }
            _collectingPickaxeForThisResource = false;
        }

        // ⛔ GIVING UP ON "NO PROGRESS" CANNOT CATCH A PURSUIT THAT IS PROGRESSING.
        //
        // Everything below fires when the progress checker trips. A bot walking steadily toward a
        // drop forty blocks away is making progress the whole time, so it never trips -- and a
        // twenty-minute run was spent exactly that way, following its own wooden pickaxe into a
        // cave: lock=wooden_pickaxe:41.6>41.6, h34.0, dy-24.0, ending with dirt and a mushroom and
        // no rungs at all.
        //
        // The instrument settled what this is NOT: deep picks read 15324 on that run and 0 on the
        // two after it, and every sampled choice had one candidate (of=1, of=1, of=2), taking the
        // cheaper one when there were two. So the ranking is fine and the descent price is fine --
        // it is ONE target, chosen once, pursued for fifteen thousand ticks because nothing puts a
        // ceiling on what a single drop may cost.
        //
        // A budget is that ceiling, and it is deliberately generous: a drop worth a minute of
        // walking is worth having, and one that has taken two is not.
        if (kaptainwutax.tungsten.TungstenConfig.get().dropPursuitHasBudget
                && _currentDrop != null && _currentDrop.isAlive()) {
            if (kaptainwutax.tungsten.TungstenConfig.get().dropBudgetSurvivesTaskRebuild
                    ? !sameDrop(_currentDrop, pursuitTarget)
                    : _currentDrop != pursuitTarget) {
                pursuitRestarts++;
                pursuitTarget = _currentDrop;
                pursuitStartMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - pursuitStartMs > PURSUIT_BUDGET_MS) {
                Debug.logMessage("Drop has cost more than its budget — marking it unreachable.");
                dropBudgetSpent++;
                pursuitMaxSec = Math.max(pursuitMaxSec,
                        (int) ((System.currentTimeMillis() - pursuitStartMs) / 1000L));
                _blacklist.add(_currentDrop);
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                _currentDrop = null;
                pursuitTarget = null;
                progressChecker.reset();
                return null;
            }
        }
        if (!progressChecker.check(mod)) {
            Nav.cancel();
            // ⛔ WHAT ACTUALLY LOSES mine_diamond, MEASURED — AND IT IS NOT ANY OF THE THREE BUGS
            // FIXED IN THIS FILE. The bot mines all three ores (drops at 4.88, 6.78, 8.82 against
            // ore at x=4,6,8) and fails to collect every one. The closest it EVER comes to them,
            // horizontally, across a whole 300 s run:
            //     drop (4.88, 0.88)   1.35 blocks, at t=5 s
            //     drop (6.78, 0.35)   2.45 blocks, at t=20 s
            //     drop (8.82, 0.13)   3.57 blocks, at t=20 s
            // It stops short and never closes, and both best approaches are in the first twenty
            // seconds -- everything after that is leaving.
            //
            // A previous version of this note explained it as vanilla's pickup box: an item in the
            // one-deep hole sits at y=-61, its box expanded by 0.5 reaches -60.25, and a player on
            // the rim has its feet at -60.0, so they miss by a quarter block. The arithmetic is
            // right and IRRELEVANT -- the bot never gets over the hole for it to matter. Kept as a
            // correction because it was committed as the cause and it is not.
            //
            // AND THE ARRIVAL DISTANCE IS NOT IT EITHER -- TRIED, MEASURED, REVERTED.
            // GetToEntityTask decides "close enough" with player.isInRange(entity, 1), which is a
            // centre-to-centre test; a player's position is its feet and an item in a one-deep hole
            // is a full block below, so the vertical separation ALONE is exactly 1.0 and the test
            // asks for strictly less. Unsatisfiable even standing on top of the drop. That
            // arithmetic is right, and raising the distance to 1.75 changed nothing: diamonds=0
            // again, still four "unreachable" lines.
            //
            // Because that branch does not FINISH anything. All it does is
            //     if (isInRange) { _progress.reset(); TungstenHelper.stop(); }
            // -- reset the checker and stop pathing. Completion is the item being COLLECTED, which
            // is a physical collision. So a bigger radius only makes the bot stop FURTHER OUT and
            // never touch the drop, which is worse. Reverted rather than shipped.
            //
            // What is left, and it is now the only candidate standing: nothing in this chain makes
            // the bot ENTER the hole, and a collision is the only thing that ends the task. The
            // goal is the item's own position, the navigator walks to the rim, and there it stays.
            // Read what the navigator does with a goal one cell below the surface before touching
            // anything -- four fixes in this file tonight, three of them real bugs, none the cause.
            //
            // ⛔ A DISCARDED ITEM ENTITY STILL HAS ITS STACK, SO THIS TEST NEVER ENDS.
            // The condition below asks "is there a drop and does it still hold something", and a
            // removed=DISCARDED entity answers yes to both forever -- getStack() is untouched by
            // removal. So once the target vanished (picked up, despawned, merged) the task kept
            // failing against a ghost: blacklist, wander, come back, blacklist again.
            //
            // Measured on mine_diamond, which is where this was found: the bot mined its diamond,
            // the drop was discarded, and the log reads
            //     Blacklist: class_1542['Diamond'/3634, ..., removed=DISCARDED]: Try 4144 / 3
            // four thousand times against a limit of three, while the timeline shows the bot at
            // EXACTLY (-10.7, -60.0, -7.479) from t=28 s to t=284 s of a 300 s course. Not slow
            // mining -- a hard stall on an item that no longer exists.
            //
            // The tracker's own filter has always had this right (`obj.isAlive() && !blacklisted`,
            // line ~310); only this branch, which runs off the CACHED _currentDrop, did not ask.
            // Clearing the reference is what lets the task pick another target instead of a ghost.
            if (_currentDrop != null && !_currentDrop.isAlive()) {
                Debug.logMessage("Drop vanished before we reached it — dropping the reference.");
                _currentDrop = null;
                progressChecker.reset();
                return null;
            }
            if (_currentDrop != null && !_currentDrop.getStack().isEmpty()) {
                // We might want to get a pickaxe first.
                if (!isGettingPickaxeFirstFlag && mod.getModSettings().shouldCollectPickaxeFirst() && !StorageHelper.miningRequirementMetInventory(MiningRequirement.STONE)) {
                    Debug.logMessage("Failed to pick up drop, will try to collect a stone pickaxe first and try again!");
                    _collectingPickaxeForThisResource = true;
                    isGettingPickaxeFirstFlag = true;
                    return getPickaxeFirstTask;
                }
                Debug.logMessage(StlHelper.toString(_blacklist, element -> element == null ? "(null)" : element.getStack().getItem().getTranslationKey()));
                Debug.logMessage("Failed to pick up drop, suggesting it's unreachable.");
                _blacklist.add(_currentDrop);
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                // ⛔ AND LET GO OF IT. We have just declared this drop unreachable; keeping it as
                // the current target means the next failure blacklists the SAME entity again,
                // without ever asking the tracker for another. The tracker's selector honours the
                // blacklist correctly (EntityTracker:139) -- it simply was never consulted again.
                //
                // That is the rest of mine_diamond's stall, and it is visible in the counter: a
                // LIVE diamond (no removed=DISCARDED this time) logged "Try 1920 / 3", i.e. 1920
                // failures against a limit of three, on one entity. A limit of three that a single
                // target can exceed six hundred times over is not a limit; it is a target that was
                // never re-selected.
                _currentDrop = null;
                return wanderTask;
            }
        }

        return super.onTick();
    }


    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof PickupDroppedItemTask task) {
            return Arrays.equals(task.itemTargets, itemTargets) && task._freeInventoryIfFull == _freeInventoryIfFull;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append("Pickup Dropped Items: [");
        int c = 0;
        for (ItemTarget target : itemTargets) {
            result.append(target.toString());
            if (++c != itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected Vec3d getPos(AltoClef mod, ItemEntity obj) {
        if (!obj.isOnGround() && !obj.isTouchingWater()) {
            // Assume we'll land down one or two blocks from here. We could do this more advanced but whatever.
            BlockPos p = obj.getBlockPos();
            if (!WorldHelper.isSolidBlock(p.down(3))) {
                return obj.getPos().subtract(0, 2, 0);
            }
            return obj.getPos().subtract(0, 1, 0);
        }
        return obj.getPos();
    }

    @Override
    protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3d pos) {
        return mod.getEntityTracker().getClosestItemDrop(
                pos,
                itemTargets);
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(ItemEntity itemEntity) {
        if (!itemEntity.equals(_currentDrop)) {
            _currentDrop = itemEntity;
            progressChecker.reset();
            // ⛔ A NEW TARGET SPENDS THE LAST FAILURE'S ESCALATION. WITHOUT THIS IT COMPOUNDS.
            //
            // This task holds ONE TimeoutWanderTask(5, true) for its whole life, and `true` is
            // increaseRange: every completed wander adds another 5 blocks to the distance the next
            // one must cover. Nothing resets it -- onStart calls Task.reset(), which is not
            // resetWander() -- so across a run the radius goes 5, 10, 15, 20, 25 without bound.
            //
            // The escalation is right for being stuck on ONE item: push further each time. It is
            // wrong across items, and mine_diamond is what that costs. Three ores sit 4, 6 and 8
            // blocks from spawn; the timeline shows the bot walking to -10.3 and sitting there
            // from t=58 s to t=210 s of a 300 s course, and in another run stepping out 8.1 ->
            // 11.7 -> 14.7 -> 17.1. It is not lost. It is satisfying a wander radius that grew
            // every time a pickup failed.
            //
            // So: finding something new to go for is progress, and progress spends the escalation.
            // A bot that is stuck on one drop still gets the bigger jumps, because _currentDrop
            // does not change in that case and this branch does not run.
            wanderTask.resetWander();
            if (isGettingPickaxeFirstFlag && _collectingPickaxeForThisResource) {
                Debug.logMessage("New goal, no longer collecting a pickaxe.");
                _collectingPickaxeForThisResource = false;
                isGettingPickaxeFirstFlag = false;
            }
        }
        // Ensure our inventory is free if we're close
        boolean touching = _mod.getEntityTracker().isCollidingWithPlayer(itemEntity);
        if (touching) {
            if (_freeInventoryIfFull) {
                if (_mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(itemEntity.getStack(), false).isEmpty()) {
                    return new EnsureFreeInventorySlotTask();
                }
            }
        }
        // ⛔ A DROP IS COLLECTED BY TOUCHING IT, SO "CLOSE ENOUGH" MUST NOT BE ONE BLOCK.
        //
        // GetToEntityTask stops driving the moment isInRange(entity, closeEnough) is true, and the
        // default is 1.0. Collection is a physical collision, so stopping at one block GUARANTEES
        // the collision never happens: the bot parks on the rim and waits for something that can
        // only occur if it keeps walking.
        //
        // Traced on mine_coal, the new course for the rung the playthrough dies on. The ore was at
        // (14,-61,4); the bot froze at (14.79,-60.00,5.03) -- about 1.17 blocks from the drop lying
        // in the hole it had just mined -- from t=78s to the end of the run, with coal=0 and the
        // tracker reporting the drop 2393 times (drop=2438/2393). No ban, no barren lock,
        // cb=0/0/0/0. It could see the coal the whole time and had stopped being driven toward it.
        //
        // The file already records the OTHER direction being tried: raising this to 1.75 changed
        // nothing and the note concluded "a bigger radius only makes the bot stop FURTHER OUT and
        // never touch the drop". Tighter is the untried direction and the one the physics argues
        // for -- vanilla picks an item up on box overlap, roughly a third of a block, not one.
        //
        // Behind a flag because it changes every pickup approach in the mod. Gate: mine_coal, which
        // is red 1 run in 3 today, and mine_diamond, whose recorded failure is this same shape
        // ("closest approach 1.35, 2.45 and 3.57 blocks, never collected, three ores of three").
        return kaptainwutax.tungsten.TungstenConfig.get().pickupClosesToContact
                ? new GetToEntityTask(itemEntity, 0.1)
                : new GetToEntityTask(itemEntity);
    }

    @Override
    protected boolean isValid(AltoClef mod, ItemEntity obj) {
        return obj.isAlive() && !_blacklist.contains(obj);
    }


    /**
     * Is this the same pursuit as the one the clock is running for?
     *
     * <p>Identity alone is not enough: the tracker can hand back a DIFFERENT ItemEntity object
     * for the same physical drop after a rescan, and under the old reference test that counted
     * as a new target and reset the budget. Same item type within half a block is the same
     * pursuit.
     */
    private static boolean sameDrop(ItemEntity a, ItemEntity b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!a.getStack().getItem().equals(b.getStack().getItem())) return false;
        // A drop DRIFTS -- half a block was too tight and the budget never accrued
        // (pursuit=15/0s with dropBudget=0 at a measured stall, where the target moved 82 cm).
        return a.getPos().squaredDistanceTo(b.getPos()) <= 4.0D;
    }
}

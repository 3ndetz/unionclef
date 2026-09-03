package adris.altoclef.tasks.construction;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.multiversion.versionedfields.Items;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import kaptainwutax.tungsten.helpers.BlockPlaceHelper;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Place a block type at a position
 */
public class PlaceBlockTask extends Task implements ITaskRequiresGrounded {

    private static final int MIN_MATERIALS = 1;
    private static final int PREFERRED_MATERIALS = 32;
    private final BlockPos target;
    private final Block[] toPlace;
    private final boolean useThrowaways;
    private final boolean autoCollectStructureBlocks;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5); // This can get stuck forever, so we increase the range.
    private Task materialTask;
    private int failCount = 0;

    public PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks) {
        this.target = target;
        this.toPlace = toPlace;
        this.useThrowaways = useThrowaways;
        this.autoCollectStructureBlocks = autoCollectStructureBlocks;
    }

    public PlaceBlockTask(BlockPos target, Block... toPlace) {
        this(target, toPlace, false, false);
    }

    public int getMaterialCount(AltoClef mod) {
        int count = mod.getItemStorage().getItemCount(ItemHelper.blocksToItems(toPlace));

        if (useThrowaways) {
            count += mod.getItemStorage().getItemCount(mod.getThrowawayItems().toArray(new Item[0]));
        }
        return count;
    }

    public static Task getMaterialTask(int count) {
        return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.DIRT, count), new ItemTarget(Items.COBBLESTONE,
                count), new ItemTarget(Items.NETHERRACK, count), new ItemTarget(Items.COBBLED_DEEPSLATE, count));
    }

    @Override
    protected void onStart() {
        progressChecker.reset();
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (WorldHelper.isInNetherPortal()) {
            // TODOS.md, the same "a search is not progress" substitution already proven for the
            // drowning guard (WorldSurvivalChain.handleDrowning): Nav.isPathing() is true while a
            // background search merely computes, driving nothing. A stalled search could suppress
            // this manual walk-out-of-the-portal escape indefinitely, and prolonged portal contact
            // eventually teleports the bot to the other dimension via plain vanilla mechanics.
            // Nav.isExecutingRoute() asks the narrower, correct question: a genuinely executing
            // route through the portal is untouched.
            if (!Nav.isExecutingRoute()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (Nav.isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        // Perform timeout wander
        if (wanderTask.isActive() && !wanderTask.isFinished()) {
            setDebugState("Wandering.");
            progressChecker.reset();
            return wanderTask;
        }

        if (autoCollectStructureBlocks) {
            if (materialTask != null && materialTask.isActive() && !materialTask.isFinished()) {
                setDebugState("No structure items, collecting cobblestone + dirt as default.");
                if (getMaterialCount(mod) < PREFERRED_MATERIALS) {
                    return materialTask;
                } else {
                    materialTask = null;
                }
            }

            if (getMaterialCount(mod) < MIN_MATERIALS) {
                // TODO: Mine items, extract their resource key somehow.
                materialTask = getMaterialTask(PREFERRED_MATERIALS);
                progressChecker.reset();
                return materialTask;
            }
        }


        // Check if we're approaching our point. If we fail, wander for a bit.
        if (!progressChecker.check(mod)) {
            failCount++;
            if (!tryingAlternativeWay()) {
                Debug.logMessage("Failed to place, wandering timeout.");
                return wanderTask;
            } else {
                Debug.logMessage("Trying alternative way of placing block...");
            }
        }


        // Place block
        if (tryingAlternativeWay()) {
            setDebugState("Alternative way: Trying to go above block to place block.");
            return new GetToBlockTask(target.up(), false);
        } else {
            // TUNGSTEN PLACES IT NOW (G-0a). What stood here asked baritone's BuilderProcess to
            // build a schematic that was 1x1x1 -- "put one of these blocks in that cell" dressed
            // up as a structure. Tungsten's build queue is that request without the costume: it
            // walks to a placing position, aims with a live raytrace, and goes through the same
            // rate gate as every other placement in this project.
            //
            // The block is chosen HERE rather than by the queue because the choice is altoclef's
            // question, not the placer's: `toPlace` is a preference list and `useThrowaways` opens
            // it to whatever the settings call disposable. The queue equips by name from the
            // hotbar, so the item has to be in the hotbar first -- which is what forceEquipItem
            // is for, and it also tells us WHICH of the candidates we actually got.
            if (!mod.getSlotHandler().forceEquipItem(acceptableItems(mod))) {
                setDebugState("No placeable block in the inventory.");
                return null;
            }
            String blockId = heldBlockId(mod);
            if (blockId == null) {
                setDebugState("Held item is not a block.");
                return null;
            }
            setDebugState("Placing " + blockId + " at " + target.toShortString());
            if (BlockPlaceHelper.queued() == 0) {
                // beginBatch, not enqueue: a previous cell that the drain gave up on must not sit
                // in front of this one. See BlockPlaceHelper.beginBatch — a //sphere once left 37
                // cells queued and the next four-block structure never got built.
                BlockPlaceHelper.beginBatch(List.of(target), blockId);
            }
        }
        return null;
    }

    /** Items this task will accept in hand: the requested blocks, plus the throwaways when the
     *  caller allowed them. Order matters — forceEquipItem takes the first one it finds. */
    private Item[] acceptableItems(AltoClef mod) {
        Item[] wanted = ItemHelper.blocksToItems(toPlace);
        if (!useThrowaways) return wanted;
        return ArrayUtils.addAll(wanted,
                mod.getThrowawayItems().toArray(new Item[0]));
    }

    /** Registry id of the block the held item would place, or null when it would place nothing. */
    private static String heldBlockId(AltoClef mod) {
        Item held = mod.getPlayer().getMainHandStack().getItem();
        if (!(held instanceof net.minecraft.item.BlockItem bi)) return null;
        return net.minecraft.registry.Registries.BLOCK.getId(bi.getBlock()).toString();
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Drop OUR cell, not "whatever the builder was doing": the queue is shared, and clearing
        // it is the same contract onLostControl had on the process it replaced.
        BlockPlaceHelper.clearQueue();
    }

    //TODO: Place structure where a leaf block was???? Might need to delete the block first if it's not empty/air/water.

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockTask task) {
            return task.target.equals(target) && task.useThrowaways == useThrowaways && Arrays.equals(task.toPlace, toPlace);
        }
        return false;
    }

    @Override
    public boolean isFinished() {
        assert MinecraftClient.getInstance().world != null;
        if (useThrowaways) {
            return WorldHelper.isSolidBlock(target);
        }
        BlockState state = AltoClef.getInstance().getWorld().getBlockState(target);
        return ArrayUtils.contains(toPlace, state.getBlock());
    }

    @Override
    protected String toDebugString() {
        return "Place structure" + ArrayUtils.toString(toPlace) + " at " + target.toShortString();
    }

    private boolean tryingAlternativeWay() {
        return failCount % 4 == 3;
    }

}

package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.TungstenHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

public class GetToEntityTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _progress = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);
    private final Entity _entity;
    private final double _closeEnoughDistance;
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
            Blocks.SHORT_GRASS,
            Blocks.SWEET_BERRY_BUSH
    };
    private Task _unstuckTask = null;

    public GetToEntityTask(Entity entity, double closeEnoughDistance) {
        _entity = entity;
        _closeEnoughDistance = closeEnoughDistance;
    }

    public GetToEntityTask(Entity entity) {
        this(entity, 1);
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

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        if (annoyingBlocks != null) {
            for (Block AnnoyingBlocks : annoyingBlocks) {
                return mod.getWorld().getBlockState(pos).getBlock() == AnnoyingBlocks ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof DoorBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FenceBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FenceGateBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FlowerBlock;
            }
        }
        return false;
    }

    // This happens all the time in mineshafts and swamps/jungles
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

    @Override
    protected void onStart() {
        Nav.cancel();
        TungstenHelper.reset();
        _progress.reset();
        stuckCheck.reset();
        _wanderTask.resetWander();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (Nav.isPathing()) {
            _progress.reset();
        }
        if (WorldHelper.isInNetherPortal()) {
            if (!Nav.isPathing()) {
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
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            Nav.clearGoal();
            Nav.stopExploring();
            return _unstuckTask;
        }
        if (!_progress.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        boolean parkourMode = AltoClef.getInstance().getModSettings().isSuperParkourMode()
                && TungstenHelper.isTungstenLoaded();

        // ── superParkourMode: Tungsten is PRIMARY, start immediately ──
        if (parkourMode && !TungstenHelper.isLocked() && !TungstenHelper.isActive()) {
            if (TungstenHelper.tryPathToEntity(_entity)) {
                Nav.cancel();
            }
        }

        // ── Tungsten lock: exclusive 30s control, Baritone stays off ──
        if (TungstenHelper.isLocked()) {
            TungstenHelper.tickLock();
            Nav.cancel();
            _progress.reset();
            long remaining = Math.max(0, (TungstenHelper.lockUntilMs() - System.currentTimeMillis()) / 1000);
            setDebugState("Tungsten pathfinding (" + remaining + "s left)");
            return null;
        }

        // If Tungsten is actively pathfinding (outside lock), let it finish
        if (TungstenHelper.isActive()) {
            _progress.reset();
            setDebugState("Tungsten pathfinding...");
            return null;
        }

        if (_wanderTask.isActive() && !_wanderTask.isFinished()) {
            _progress.reset();
            setDebugState("Failed to get to target, wandering for a bit.");
            return _wanderTask;
        }

        // G-0: THE BARITONE FALLBACK HERE COULD NOT DO ANYTHING, SO IT GOES.
        // It was guarded by !TungstenHelper.isActive(), i.e. it only ran when tungsten was NOT
        // driving -- and it handed the goal to the legacy engine, which has not driven the body
        // since tungsten became the default. The real driver is the tryPathToEntity call below,
        // on the progress-checker path. Deleting the call removes the last user of GoalFollowEntity
        // and one more baritone import; nothing else in this method changes.
        // Gated on the mob suite rather than craft, because entity following lives there:
        // mob_melee before = PASS (zombie dead, mdTung total=51, min_hp=14.0).

        if (mod.getPlayer().isInRange(_entity, _closeEnoughDistance)) {
            _progress.reset();
            TungstenHelper.stop();
        }

        if (!_progress.check(mod)) {
            // Baritone failed — try Tungsten (acquires 30s lock)
            if (TungstenHelper.tryPathToEntity(_entity)) {
                Nav.cancel();
                setDebugState(parkourMode ? "Tungsten retrying" : "Baritone stuck → Tungsten locked for 30s");
                return null;
            }
            if (!parkourMode) return _wanderTask;
        }

        // ⛔ AND SOMETHING HAS TO ACTUALLY START THE WALK. G-0 LEFT THIS METHOD WITH NO MOVER.
        //
        // The deleted baritone call used to drive the approach; the note above says the real driver
        // is "the tryPathToEntity call below, on the progress-checker path". That path only runs
        // AFTER progress has already stalled, so on the happy path this method set a debug string
        // and returned null -- issuing no movement at all. Whether the bot ever reached an entity
        // depended on some other task having left a path running.
        //
        // Measured on mine_diamond, which is what exposed it: across six runs EVERY route tungsten
        // ever planned ended at (3,-60,0) -- the standing cell beside the ore at x=4, i.e. the
        // MINING approach. Not one route was ever planned to a dropped item. The bot mines all
        // three ores, the drops land 1.9 blocks away at y=-61, and nothing moves it those two
        // blocks: closest approach 1.35, 2.45 and 3.57 blocks, never collected, three ores out of
        // three, every run.
        //
        // So ask for the path on the NORMAL path too, not only after a stall. The guards inside
        // tryPathToEntity already make this cheap and idempotent -- a cooldown, a fail counter, and
        // a retarget interval -- and it returns false without doing anything when tungsten is
        // already busy, which is why the two branches above return early before reaching here.
        if (!mod.getPlayer().isInRange(_entity, _closeEnoughDistance)
                && TungstenHelper.tryPathToEntity(_entity)) {
            setDebugState("Walking to entity");
            return null;
        }
        setDebugState(parkourMode ? "Tungsten chasing entity" : "Going to entity");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Nav.cancel();
        TungstenHelper.stop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToEntityTask task) {
            return task._entity.equals(_entity) && Math.abs(task._closeEnoughDistance - _closeEnoughDistance) < 0.1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Approach entity " + _entity.getType().getTranslationKey();
    }
}

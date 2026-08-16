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
    /**
     * Times a tungsten search that was owning the approach without moving the body was released.
     *
     * <p>Rule ONE: a fix that cannot be shown to have RUN is a fix nobody can argue about. This
     * reads zero when the flag is off, and reading zero with the flag ON means the stall being
     * blamed is not this one -- which is the answer the counter exists to be able to give.
     */
    public static volatile int entityReleased;

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

        // ⛔ ASK WHETHER THE BODY MOVED, NOT WHETHER NAVIGATION SAYS IT IS BUSY.
        //
        // This reset was first made conditional on Nav.isExecutingRoute() -- "a route is really
        // being followed" rather than "a search is running". That was still wrong, and the counter
        // proved it: entityReleased read 0 across two full A/Bs with the flag ON, because
        // isExecutingRoute() is MovementQueue/BlockPathWalker/executor RUNNING, and an executor can
        // sit there replaying inputs that move the bot nowhere. Nav was reporting progress that the
        // position did not show.
        //
        // MovementProgressChecker already answers the real question -- 0.1 blocks in 6 seconds --
        // so with this flag on, nothing resets it on navigation state at all. It measures the body,
        // which is the only witness that cannot be talked round.
        boolean mustMove = kaptainwutax.tungsten.TungstenConfig.get().entitySearchMustMove;
        if (!mustMove && Nav.isPathing()) {
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
        //
        // ⛔ AND THE LOCK IS THE BRANCH THAT ACTUALLY STALLS THIS COURSE. MEASURED, NOT ASSUMED.
        //
        // The same fix was first written only for the isActive() branch below, on the reasoning
        // that barren locks already have their own guard. The A/B said otherwise: entityReleased
        // read 0 on all eight runs INCLUDING the pinned arm -- the patched branch was never reached
        // -- while both failures carried lock=1/0/0 (barren/productive/findRefused) with the drop
        // seen ~6000 times against ~100 on every pass. So the stall goes through the LOCK, and
        // without that counter the first A/B's PASS/FAIL pattern would have been read as a win.
        //
        // The existing guard cannot help here: it scores a lock only when the lock EXPIRES, and it
        // takes MAX_BARREN_LOCKS = 2 before it refuses. One barren lock per course is thirty
        // seconds already spent, and the second never comes. Asking at six seconds whether the body
        // has moved is the same judgement made before the thirty seconds are gone -- and
        // releaseIdleLock still counts it as barren, so the escalation converges rather than
        // letting this release and re-lock for ever.
        if (TungstenHelper.isLocked()) {
            TungstenHelper.tickLock();
            Nav.cancel();
            long remaining = Math.max(0, (TungstenHelper.lockUntilMs() - System.currentTimeMillis()) / 1000);
            if (!mustMove) {
                _progress.reset();
                setDebugState("Tungsten pathfinding (" + remaining + "s left)");
                return null;
            }
            if (_progress.check(mod)) {
                setDebugState("Tungsten locked (" + remaining + "s left)");
                return null;
            }
            entityReleased++;
            TungstenHelper.releaseIdleLock();
            _progress.reset();
            stuckCheck.reset();
            setDebugState("Tungsten lock moved nothing — released");
            return null;
        }

        // ⛔ A SEARCH IS NOT PROGRESS, AND THIS IS WHERE THE APPROACH TO A DROP DIES.
        //
        // This branch fires on TungstenHelper.isActive(), which is `PATHFINDER.active` OR
        // `isExecutorRunning()` -- i.e. it is TRUE while tungsten is merely LOOKING. It then does
        // two things, and both are wrong when nothing is being followed: it resets the progress
        // checker every tick, so the stall can never be noticed, and it returns early, so the
        // recovery below (retry the path, then wander) is unreachable even if it were noticed.
        //
        // The result is a permanent park. Measured three times on the playthrough, always in the
        // same place -- "Pickup Dropped Items -> Approach entity -> Tungsten pathfinding..." --
        // motionless for 70-90 seconds with every drive counter flat at zero, and once for a whole
        // run that reached no rung at all. It is also the recorded "parks 1.17 blocks from the
        // drop" case: ore at (14,-61,4), bot stopped at (14.79,-60.00,5.03) with the drop lying one
        // block BELOW it in the hole it had just mined, seeing it 2393 times and never stepping in.
        //
        // ⭐ AND IT IS WHY BOTH RADIUS ATTEMPTS MEASURED NOTHING. closeEnough was raised to 1.75
        // ("stops further out and never touches") and cut to 0.1 (`pickupClosesToContact`, refuted
        // on its own A/B, tighter is worse). Neither could work: the radius decides when to STOP
        // driving, and the bot here is not driving at all. With the recovery switched off, no
        // choice of radius has anything to act on.
        //
        // The repo already names this defect and already has the tool for it -- MineAndCollectTask
        // carries the identical two lines with `progressCheckIgnoresSearch` and
        // `Nav.isExecutingRoute()`, which is MovementQueue/BlockPathWalker/executor actually
        // running. Same flag on purpose: one defect, one switch, no second source of truth.
        //
        // Releasing is the part that matters. Falling through alone would not help, because
        // tryPathToEntity refuses while tungsten is busy -- so a search that has moved the body
        // nowhere for six seconds is STOPPED, and the next tick may plan afresh. Six seconds is
        // MovementProgressChecker's own default (0.1 blocks in 6 s), so an ordinary search that
        // finishes in time is untouched; only a search that owns the approach and goes nowhere is.
        if (TungstenHelper.isActive()) {
            if (!mustMove) {
                _progress.reset();
                setDebugState("Tungsten pathfinding...");
                return null;
            }
            if (_progress.check(mod)) {
                // active, body still: allowed, but on the clock rather than for ever
                setDebugState("Tungsten active (body still)");
                return null;
            }
            entityReleased++;
            TungstenHelper.stop();
            _progress.reset();
            stuckCheck.reset();
            setDebugState("Tungsten searched without moving — released");
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

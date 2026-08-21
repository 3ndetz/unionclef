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

    /**
     * Times the approach wandered because navigation had REFUSED and the body was not moving.
     *
     * <p>Read together with {@link #entityReleased} as {@code entityReleased=released/wandered}.
     */
    public static volatile int entityWandered;

    /** Ticks spent walking straight at a target that navigation would not deliver. */
    /**
     * WHERE the drop sits when the close walk cannot reach it: below / above / on top / beside.
     *
     * <p>Read as closeWalkGeom=below/above/onTop/beside. A large "below" means the walk is
     * steering horizontally at something under its feet, which no amount of forward can fix.
     */
    public static volatile int closeWalkBelow, closeWalkAbove, closeWalkOnTop, closeWalkBeside;

    /** Ticks our forward press was still down on entry, and ticks something had released it. */
    public static volatile int closeWalkFwdKept, closeWalkFwdLost;

    /** Ticks the release was skipped because the close walk was driving; 0 with the flag off. */
    public static volatile int closeWalkKeysKept;
    private static boolean closeWalkHeldLast = false;
    private static boolean closeWalkProbePending = false;

    public static volatile int entityCloseWalk;

    /**
     * Of those ticks, how many the BODY ACTUALLY MOVED on. Read as entityCloseWalk=ticks/moved.
     *
     * <p>This is the one datum that splits the two surviving explanations of goto_then_mine, and
     * it needs no failure to be caught. Holding forward at a drop for 482 ticks does not collect
     * it -- measured -- and there are only two ways that can be true. Either the keys are held and
     * the body does not move, in which case something else owns the inputs and the walk was never
     * real; or the body moves and the drop still is not collected, in which case it cannot be
     * reached from above and the bot needs to get INTO its own excavation. Counting the ticks the
     * position changed on says which, from any run where the branch fires at all -- pass or fail.
     */
    public static volatile int entityCloseWalkMoved;

    /**
     * Of the moving ticks, how many actually got CLOSER. Read as entityCloseWalk=ticks/moved/closer.
     *
     * <p>"The body moved" is not "the body approached", and reading the first as the second is an
     * error I made and had to take back: 241/180 says it moved on 180 ticks, while the capture has
     * the bot finishing 5.5 blocks from the drop rather than at the lip of the hole. Moving without
     * closing means the WALK IS AIMED WRONG -- something else owning the camera while
     * hold(MOVE_FORWARD) carries the bot off in whatever direction it happens to face -- which is a
     * different bug from a drop that cannot be reached. This counter separates them.
     */
    public static volatile int entityCloseWalkCloser;

    /**
     * Ticks where the bot was actually FACING the target at the START of the tick.
     *
     * <p>Read as entityCloseWalk=ticks/moved/closer/aimed. LookHelper.lookAt SNAPS yaw and pitch
     * (setYaw/setPitch, no smoothing, no baritone path), so after this branch runs the bot is
     * pointed at the drop by construction. If the yaw is still wrong when the NEXT tick begins,
     * something overwrote it in between and hold(MOVE_FORWARD) has been carrying the bot off in
     * whatever direction that something chose -- which would explain 462 moving ticks yielding 33
     * closer. If the yaw is right and it still does not close, the body is blocked and the fault is
     * geometric. Those are the last two candidates and this tells them apart.
     */
    public static volatile int entityCloseWalkAimed;

    /** Close-walk ticks during which a WindMouse aim LEASE was live, i.e. a second camera owner. */
    public static volatile int entityCloseWalkLeased;

    /** Close-walk ticks on which the yaw we snapped last tick was still there this tick. */
    public static volatile int entityCloseWalkYawKept;
    private static Float closeWalkSetYaw = null;

    /** Close-walk ticks whose target is a DIFFERENT entity than the previous close-walk tick. */
    public static volatile int entityCloseWalkRetarget;
    private static Integer closeWalkLastEntityId = null;

    private net.minecraft.util.math.Vec3d closeWalkLastPos = null;
    private double closeWalkLastDist = -1;

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

    /**
     * Close enough that a route is pointless and a straight walk collects it.
     *
     * <p>⛔ THIS WAS 3.5 AND THAT NUMBER WAS A GUESS MADE BEFORE THE DISTANCE WAS MEASURED, which
     * is why the branch fired on ONE run in sixteen and the A/B came back "not established". The
     * failures were never at 3.5 blocks. Two captures, with the drop position finally recorded:
     *
     * <pre>
     *   bot=[25.02,-60.0,-3.01]   drop=[20.875,-61.0,0.539]   ~5.5 blocks
     *   bot=[24.29,-60.0, 5.29]   drop=[20.398,-61.0,0.125]   ~6.6 blocks
     * </pre>
     *
     * The drop lies one block below the surface in the hole the bot just dug, and the bot ends up
     * five to seven blocks away on top of it, pursuing it for the whole run (idrop about 3690, a
     * drop handed over on every ask) and never arriving. Eight blocks covers that and is still
     * short enough that a straight walk is a sane thing to do -- and it only ever runs after the
     * progress checker says the body is not moving.
     */
    private static final double CLOSE_WALK_RANGE = 8.0;

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

        // ⛔ SAMPLE THE KEY BEFORE THIS METHOD TOUCHES IT. The first version of this probe sat
        // inside the close-walk block -- which runs AFTER the Nav.isPathing() release below -- and
        // read 0 kept of 120. That number cannot distinguish "someone else released it" from
        // "this very method released it four lines earlier", and I nearly reported the first.
        // Here it reports what the PREVIOUS tick left behind, which is the question.
        // ⛔ THE PROBE AND THE FIX NEED SEPARATE LATCHES. This block used to clear
        // closeWalkHeldLast, which the key-retention branch below reads to decide whether the
        // close walk is driving -- so the measurement silently disabled the fix and the A/B ran
        // with closeWalkKeysKept=0 in BOTH arms. The probe gets its own one-shot flag; the drive
        // latch is owned by the drive.
        if (closeWalkProbePending) {
            try {
                if (net.minecraft.client.MinecraftClient.getInstance()
                        .options.forwardKey.isPressed()) {
                    closeWalkFwdKept++;
                } else {
                    closeWalkFwdLost++;
                }
            } catch (Exception ignored) {
                // an instrument never breaks the tick it rides on
            }
            closeWalkProbePending = false;
        }

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
            // ⛔ DO NOT TAKE THE KEYS OFF THE THING THAT IS DRIVING. isPathing() is true while the
            // pathfinder merely SEARCHES, and it searches all through a close walk -- the walk only
            // runs because navigation already failed. So this released the MOVE_FORWARD the walk
            // had just pressed, on half its ticks: closeWalkFwd=241/240, measured at the top of the
            // tick before this method touches the key.
            boolean walkDrove = kaptainwutax.tungsten.TungstenConfig.get().closeWalkKeepsKeys
                    && closeWalkHeldLast;
            if (Nav.isPathing() && !walkDrove) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            } else if (Nav.isPathing()) {
                closeWalkKeysKept++;
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
            // ⛔ DO NOT RESET THE PROGRESS CHECKER HERE. Releasing a lock is not the body moving,
            // and resetting wipes the one piece of evidence the wander recovery below needs.
            // Measured: with both halves on, entityReleased read 49/0 -- released 49 times, wandered
            // ZERO -- because every release cleared the checker before the wander site could see it.
            // The two fixes cancelled each other, which is a thing only the paired counter shows.
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
            // Same reason as the lock branch: a release is not movement, so the checker stands.
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

        // ⛔ AT TWO BLOCKS THE ANSWER IS TO WALK AT IT, NOT TO ABANDON IT.
        //
        // This is my own recovery making a case WORSE, caught on goto_then_mine. The bot mines its
        // cobblestone, the drops land at its feet, the approach stalls, and entityWanderWhenNavRefuses
        // fires -- moving it from (21.1, 1.5), where it was standing ON the drops, out to
        // (24.3, 5.3), where it froze for the rest of the run. Final verdict cobblestone=0, with
        // idrop=3697/0/0/3697 (the tracker handed over a drop on every single ask) and
        // entityReleased=2/2 (both releases and both wanders fired). The recovery is right that
        // navigation has given up; it is wrong about what to do next when the thing is RIGHT THERE.
        //
        // A drop is collected by TOUCHING it, so inside a couple of blocks the useful primitive is
        // the one a human uses: face it and hold forward. No search, no route, no lock. That is
        // also why the radius attempts on closeEnoughDistance measured nothing -- they moved the
        // line at which the bot stops DRIVING, and nothing was driving.
        //
        // Deliberately last-resort: it runs only once the progress checker says the body is not
        // moving, so a healthy approach is untouched.
        if (kaptainwutax.tungsten.TungstenConfig.get().entityCloseRangeWalk
                && !_progress.check(mod)
                && mod.getPlayer().isInRange(_entity, CLOSE_WALK_RANGE)
                && !mod.getPlayer().isInRange(_entity, _closeEnoughDistance)) {
            entityCloseWalk++;
            net.minecraft.util.math.Vec3d nowPos = mod.getPlayer().getPos();
            if (closeWalkLastPos != null && closeWalkLastPos.squaredDistanceTo(nowPos) > 0.0025) {
                entityCloseWalkMoved++;
            }
            // WHERE is the drop? "mostly below" and "mostly beside" want opposite fixes, so tally
            // the shape rather than an average of the two. Measured: beside on 963 of 963, which
            // is what refuted the degenerate-direction theory this counter was added to test.
            try {
                double hdx = _entity.getX() - mod.getPlayer().getX();
                double hdz = _entity.getZ() - mod.getPlayer().getZ();
                double horiz = Math.sqrt(hdx * hdx + hdz * hdz);
                double dy = _entity.getY() - mod.getPlayer().getY();
                if (horiz < 0.8 && dy < -0.4) closeWalkBelow++;
                else if (horiz < 0.8 && dy > 0.4) closeWalkAbove++;
                else if (horiz < 0.8) closeWalkOnTop++;
                else closeWalkBeside++;
            } catch (Exception ignored) {
                // an instrument never breaks the tick it rides on
            }
            // Measured BEFORE this tick's snap, so it reports what the previous tick left behind.
            net.minecraft.util.math.Vec3d toTarget = _entity.getPos().subtract(nowPos);
            float wantYaw = (float) Math.toDegrees(-Math.atan2(toTarget.x, toTarget.z));
            if (Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                    wantYaw - mod.getPlayer().getYaw())) < 20.0f) {
                entityCloseWalkAimed++;
            }
            double nowDist = nowPos.distanceTo(_entity.getPos());
            if (closeWalkLastDist >= 0 && nowDist < closeWalkLastDist - 0.01) {
                entityCloseWalkCloser++;
            }
            closeWalkLastDist = nowDist;
            closeWalkLastPos = nowPos;
            // ⛔ WHO ELSE IS STEERING THIS CAMERA?
            //
            // LookHelper.lookAt is a hard snap -- it calls setYaw directly -- so after this branch
            // runs, the yaw IS the yaw of the item. Yet the aim measured at the TOP of the next
            // tick was within 20 degrees on only 208 of 482 ticks in the arm where this walk fired,
            // and the body moved without approaching: 286 ticks moved, 13 closer, net 2.5 blocks in
            // roughly twenty-four seconds. A snap that does not survive the tick means a second
            // owner is putting the camera back.
            //
            // WindMouseRotation is a LEASE: it holds a target for 600 ms and steers the camera back
            // to it every render frame. LookHelper's own comment warns that arming it from a snap
            // would fight the snap path on the same camera. If a lease is live while we walk, that
            // fight is the mechanism -- so count it rather than assume it.
            if (kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.hasTarget()) {
                entityCloseWalkLeased++;
            }
            // ⛔ DID OUR OWN SNAP SURVIVE THE TICK? The aim reads 9 of 241, and there are exactly
            // two ways to get that number, wanting opposite fixes:
            //   1. something overwrites the yaw between ticks -- then find and arbitrate the owner;
            //   2. the aim COUNTER is wrong -- then the bot was aimed all along and the reason it
            //      does not arrive is somewhere else entirely, and one more fix would have been
            //      built on a broken instrument.
            // The lease was the obvious candidate for (1) and measured zero, so guessing again is
            // not the move. This compares the yaw we SET last tick with the yaw we find now: if it
            // survived, the aim counter is the thing that is lying.
            if (closeWalkSetYaw != null
                    && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                            closeWalkSetYaw - mod.getPlayer().getYaw())) < 1.0f) {
                entityCloseWalkYawKept++;
            }
            // ⛔ AND IS IT THE SAME DROP AS LAST TICK? This is the only reading left that makes the
            // other two agree. The yaw we set survives the tick 240 times in 241, and the aim
            // formula here is byte-identical to the one lookAt uses -- yet the aim reads 9 of 241.
            // Both can be true at once if the two numbers are about DIFFERENT TARGETS: the counters
            // are static, the task is rebuilt every tick by its parent, and mine_coal drops THREE
            // pieces of coal. A bot alternating between two of them would snap at one, be measured
            // against the other, and walk a zigzag that moves the body without ever closing on
            // either -- which is exactly the 241/239/8 shape.
            if (closeWalkLastEntityId != null && closeWalkLastEntityId != _entity.getId()) {
                entityCloseWalkRetarget++;
            }
            closeWalkLastEntityId = _entity.getId();
            TungstenHelper.stop();
            Nav.cancel();
            adris.altoclef.util.helpers.LookHelper.lookAt(mod, _entity.getPos());
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            closeWalkHeldLast = true;
            closeWalkProbePending = true;
            closeWalkSetYaw = mod.getPlayer().getYaw();   // what the snap actually left behind
            setDebugState("Walking straight at it (navigation would not)");
            return null;
        }
        if (!_progress.check(mod)) {
            // Baritone failed — try Tungsten (acquires 30s lock)
            if (TungstenHelper.tryPathToEntity(_entity)) {
                Nav.cancel();
                setDebugState(parkourMode ? "Tungsten retrying" : "Baritone stuck → Tungsten locked for 30s");
                return null;
            }
            // ⛔ A REFUSAL WITH NOWHERE TO GO IS HOW THIS TASK FREEZES FOR THE REST OF THE RUN.
            //
            // tryPathTo returns false PERMANENTLY once failCount reaches MAX_FAIL_COUNT -- it is the
            // first line of the method -- and failCount only clears on reset(). So after five failed
            // attempts navigation refuses every tick for the remainder of the task. The wander below
            // is the recovery for exactly that, and `!parkourMode` made it unreachable in the mode
            // that SHIPS: tungsten is primary on the bench, so parkourMode is true and the branch is
            // skipped. What follows is a second tryPathToEntity that refuses for the same reason, a
            // setDebugState, and `return null` -- no movement, every tick, for ever.
            //
            // Measured on mine_diamond, a captured failing run: the bot froze at (6.7,-61.0,0.4) at
            // t=8.5s and did not move again for the remaining ~290 s, 76 polls and three distinct
            // positions, with the ore still standing in the ground in the end-of-run screenshot.
            // Its counters say lock=0/1/0 -- ONE lock, scored PRODUCTIVE, no barren ones -- so
            // nothing was thrashing; navigation had simply stopped being asked and MineOrCollectTask
            // went on scanning (scan=6123 against ~180 on a pass, drop seen 6033 times).
            //
            // The guard was there so a wander would not interrupt tungsten while it drives. That
            // reasoning does not reach this line: we are here because tungsten REFUSED, so there is
            // nothing to interrupt. Wandering resets the situation, and the cooldown and fail count
            // get their chance to clear, which is the recovery this task already owns.
            if (kaptainwutax.tungsten.TungstenConfig.get().entityWanderWhenNavRefuses) {
                entityWandered++;
                setDebugState("Navigation refused and nothing moved — wandering");
                return _wanderTask;
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

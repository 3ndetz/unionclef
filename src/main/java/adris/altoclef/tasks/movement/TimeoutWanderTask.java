package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

// TODO improve wandering
/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final float distanceToWander;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final boolean increaseRange;
    private final TimerGame timer = new TimerGame(60);
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
    private Vec3d origin;
    /** Wander destinations chosen for tungsten, and those it accepted. Proof this fired. */
    public static volatile int wanderTungPicked = 0, wanderTungDriven = 0;
    /** Of those picks, how many named a cell the bot cannot stand in. Read as wanderTung=p/d/u. */
    public static volatile int wanderTargetUnstandable = 0;
    /** Picks refused by the drive with the spiral index left where it was. Mechanism counter for
     *  {@link kaptainwutax.tungsten.TungstenConfig#wanderSpiralCountsLegsNotTries}; 0 in control. */
    public static volatile int wanderSpiralHeld = 0;

    /** How far above and below the anchor's height a wander target may be pulled to find footing.
     *  Wide enough for the hills a 120-block leg crosses, narrow enough that the destination is
     *  still the direction the spiral asked for. */
    private static final int GROUND_SCAN_UP = 24, GROUND_SCAN_DOWN = 48;

    /**
     * The same XZ, at a height the bot could stand on -- or the point unchanged if there is none.
     *
     * <p>Searches outward from the requested Y rather than from the world surface: the surface of
     * a mountain 120 blocks away is not where a wander wants to go, and a cave mouth at the
     * requested height often is. Nearest-first keeps the leg the length the spiral intended.
     */
    private static net.minecraft.util.math.Vec3d groundedNear(
            adris.altoclef.AltoClef mod, net.minecraft.util.math.Vec3d dest) {
        try {
            net.minecraft.world.World w = mod.getWorld();
            if (w == null) return dest;
            int x = (int) Math.floor(dest.x), z = (int) Math.floor(dest.z);
            int y0 = (int) Math.floor(dest.y);
            for (int d = 0; d <= Math.max(GROUND_SCAN_UP, GROUND_SCAN_DOWN); d++) {
                if (d <= GROUND_SCAN_DOWN
                        && adris.altoclef.tasks.movement.CustomBaritoneGoalTask.standable(w, x, y0 - d, z)) {
                    return new net.minecraft.util.math.Vec3d(dest.x, y0 - d, dest.z);
                }
                if (d != 0 && d <= GROUND_SCAN_UP
                        && adris.altoclef.tasks.movement.CustomBaritoneGoalTask.standable(w, x, y0 + d, z)) {
                    return new net.minecraft.util.math.Vec3d(dest.x, y0 + d, dest.z);
                }
            }
        } catch (Exception ignored) {
            // never let the search for footing break the wander
        }
        return dest;
    }
    //private DistanceProgressChecker _distanceProgressChecker = new DistanceProgressChecker(10, 0.1f);
    private boolean _forceExplore;
    private Task _unstuckTask = null;
    private int failCounter;
    private double _wanderDistanceExtension;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        this.distanceToWander = distanceToWander;
        this.increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
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
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        for (Block annoyingBlock : annoyingBlocks) {
            if (block == annoyingBlock) return true;
        }
        return block instanceof DoorBlock ||
                block instanceof FenceBlock ||
                block instanceof FenceGateBlock ||
                block instanceof FlowerBlock;
    }

    public void resetWander() {
        _wanderDistanceExtension = 0;
        wanderAnchor = null;
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
        AltoClef mod = AltoClef.getInstance();

        timer.reset();
        Nav.cancel();
        origin = mod.getPlayer().getPos();
        // No carry-over between legs: the gap between two wanders is not ground this task covered.
        lastTickPos = null;
        progressChecker.reset();
        stuckCheck.reset();
        failCounter = 0;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // AN ODOMETER THAT ONLY RUNS WHILE THIS TASK DOES.
        //
        // The first attempt to prove the wander fix measured how far the bot got from its start
        // over a whole course, and the A/B threw it out: the unfixed build travelled FURTHER
        // (38.0 blocks against 26.8), because the search task's own approach and the shimmy in the
        // stuck branch walk the body regardless. A gate like that answers "did the bot go
        // anywhere", which was never in question.
        //
        // This counts ground covered ONLY on ticks where this task is the one running, so no other
        // task's movement can satisfy it. That is the difference between a measurement and a
        // reassurance, and it is what makes the course able to FAIL on a build where wandering
        // issues no movement of its own.
        wanderTicks++;
        ClientPlayerEntity self = mod.getPlayer();
        if (self != null) {
            if (lastTickPos != null) {
                double dx = self.getX() - lastTickPos.x;
                double dz = self.getZ() - lastTickPos.z;
                wanderMovedCm += (int) Math.round(Math.sqrt(dx * dx + dz * dz) * 100);
            }
            lastTickPos = self.getPos();
        }

        // ⛔ PATHING IS NOT PROGRESS. See TungstenConfig.stallCheckNeedsMovement: a stall IS the
        // state where Nav says it is pathing and the body does not move, so resetting on that
        // condition wipes the detector exactly when it is needed. wanderFail=0 across 4406 ticks
        // that covered 10.6 blocks is what that looks like from the outside.
        if (self != null) {
            if (_lastMoveTickPos != null
                    && self.getPos().squaredDistanceTo(_lastMoveTickPos) > 0.0004) {
                _ticksSinceMoved = 0;
                _lastMoveTickPos = self.getPos();
            } else {
                _ticksSinceMoved++;
                if (_lastMoveTickPos == null) _lastMoveTickPos = self.getPos();
            }
        }
        if (Nav.isPathing()) {
            if (!kaptainwutax.tungsten.TungstenConfig.get().stallCheckNeedsMovement
                    || _ticksSinceMoved < STALL_MOVE_GRACE) {
                progressChecker.reset();
            } else {
                wanderResetDenied++;
                // NAME THE SOURCE, DO NOT GUESS IT. This branch IS the stall: nav says it is
                // pathing and the body has not moved for STALL_MOVE_GRACE ticks. Which of the
                // five things isPathing() ORs together is saying yes decides what the fix even
                // looks like -- see Nav.noteStallSources.
                Nav.noteStallSources();
            }
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
            // ⛔ THIS LINE TAKES THE FORWARD KEY OFF A WALK RUNNING IN ANOTHER TASK.
            // GetToEntityTask's close-range walk presses MOVE_FORWARD and returns; this task runs
            // as the pickup's fallback in the same tick and releases it again whenever
            // Nav.isPathing() -- which is true while the pathfinder merely SEARCHES, and it
            // searches throughout, because the close walk only exists for when navigation failed.
            //
            // Named rather than guessed: the release inside GetToEntityTask was the obvious
            // suspect, was gated behind a flag, and its counter read ZERO while the press was
            // still lost 118 times. Instrumenting InputControls to name the caller produced
            // "TimeoutWanderTask:225 x267" against closeWalkFwd=0/240/0 -- this line, this many
            // times, on the run where the press was lost that many times.
            // ⛔ AND THE OTHER DRIVER, WHICH THIS NEVER ASKED ABOUT.
            //
            // The guard above covers the close walk to an ITEM and nothing else. BlockPathWalker
            // drives almost everything else the bot does, and this line takes MOVE_FORWARD out of
            // its hands while it is walking. The same instrument that caught this task at :225
            // caught it again, and the number is not marginal:
            //
            //     TimeoutWanderTask:238 x1290   DestroyBlockTask:594 x24   DestroyBlockTask:686 x8
            //
            // Twelve hundred and ninety steals in a ten-minute run, against thirty-two from the
            // miner. That is the shuffling a viewer sees, and it is why a whole session of reading
            // counters never found it -- the thief instrument only watched the close walk, so the
            // dominant case was invisible by construction.
            boolean walkDriving = (kaptainwutax.tungsten.TungstenConfig.get().closeWalkKeepsKeys
                    && adris.altoclef.tasks.movement.GetToEntityTask.closeWalkDrivingNow())
                    || (kaptainwutax.tungsten.TungstenConfig.get().wanderKeepsWalkerKeys
                        && kaptainwutax.tungsten.task.BlockPathWalker.isRunning());
            if (Nav.isPathing() && !walkDriving) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            } else if (Nav.isPathing()) {
                wanderKeysKept++;
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
        if (!progressChecker.check(mod) || !stuckCheck.check(mod)) {
            List<Entity> closeEntities = mod.getEntityTracker().getCloseEntities();
            for (Entity CloseEntities : closeEntities) {
                if (CloseEntities instanceof MobEntity &&
                        CloseEntities.getPos().isInRange(mod.getPlayer().getPos(), 1)) {
                    setDebugState("Killing annoying entity.");
                    return new KillEntitiesTask(CloseEntities.getClass());
                }
            }
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                failCounter++;
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            // Not in annoying block — force baritone to recompute
            // ⛔ AND Nav.cancel() HAS AN EMPTY BODY. Read it: the method is a deliberate,
            // documented no-op, because the version that stopped tungsten killed the search on
            // the same tick it was started. So in the ordinary case -- no mob adjacent, not
            // wedged in a fence -- the ENTIRE give-up branch of the wander is stuckCheck.reset()
            // and nothing else. It does not re-pick a destination, does not stop the search, and
            // does not drop the lock.
            //
            // Which matters because the re-pick below is gated on
            // !isExecutingRoute() && !TungstenHelper.isActive(): if the thing holding the bot
            // still holds it after the trip, the next tick stalls in exactly the same state, and
            // the 41 trips a run buy nothing. tripBlocked is that question as a number -- was
            // the re-pick still refused at the moment the give-up fired -- and it is sampled
            // HERE rather than inferred from the gate, so its denominator is a trip.
            if (Nav.isExecutingRoute() || adris.altoclef.util.helpers.TungstenHelper.isActive()) {
                wanderTripBlocked++;
            }
            Nav.cancel();
            stuckCheck.reset();
        }
        setDebugState("Exploring.");
        switch (WorldHelper.getCurrentDimension()) {
            case END -> {
                if (timer.getDuration() >= 30) {
                    timer.reset();
                }
            }
            case OVERWORLD, NETHER -> {
                if (timer.getDuration() >= 30) {
                }
                if (timer.elapsed()) {
                    timer.reset();
                }
            }
        }
        // REVERTED, ON A MEASUREMENT THAT REFUTED MY OWN REASONING. KEEP THIS NOTE.
        //
        // The reasoning looked airtight: explore() and Nav.isExploring() BOTH address the legacy
        // engine, which stopped driving the body when tungsten became the default, so this branch
        // returns null having issued no movement -- and "Failed exploring." x11 appeared on the one
        // craft_iron_pickaxe run that failed. I replaced it with a real destination on the live
        // engine (GetToXZTask) and the craft suite stayed 6/6.
        //
        // Then the A/B, with an odometer that counts ground covered ONLY on ticks where this task
        // is the one running, so no other task's movement can flatter either side:
        //
        //   with the replacement:  wanderMoved=24.6   overallMoved=30.0
        //   with THIS code:        wanderMoved=42.6   overallMoved=41.8
        //
        // The bot covers MORE ground under the supposedly dead code. So wandering was never a
        // no-op -- something here does move the body (the shimmy in the stuck branch is the
        // obvious candidate) -- and the replacement measurably did LESS. The call below is still a
        // dead-engine call and still has to go for G-0, but it has to go as a real port of
        // exploration onto tungsten, not a one-line substitution that trades measured coverage for
        // a tidier dependency graph.
        // ⛔ THE LAST LIVE HAND-OFF TO THE LEGACY ENGINE, AND IT IS NOT A DEAD CALL.
        //
        // The note above says this branch issues no movement. The counters say otherwise:
        // legacyDrive reads 8558/18/9384 and 8079/2134/8603 -- the legacy engine executing a
        // path for eight thousand ticks a run and exploring for nine thousand, up to 2134 of
        // them while the tungsten executor is also driving. Two engines, one body.
        //
        // A real port, not the one-line swap the note rightly refused: pick a point on the
        // wander circle, drive it with tungsten, pick another when reached or refused.
        if (kaptainwutax.tungsten.TungstenConfig.get().wanderUsesTungsten) {
            if (!Nav.isExecutingRoute() && !adris.altoclef.util.helpers.TungstenHelper.isActive()) {
                // ⛔ FROM WHERE THE BOT IS, NOT AROUND WHERE IT STARTED.
                //
                // The first version picked points on a circle of fixed radius around `origin`.
                // Once the body reached that circle every later pick was on it too, so the legs
                // cancelled out and the ground covered stopped growing: wander_recovery measures
                // wanderMoved and wants more than 15 blocks, and the circle version delivered 8.25.
                //
                // Searching is walking AWAY. Each leg starts from the current position and reaches
                // a little further than the last, so the distance accumulates instead of orbiting.
                // THE SPIRAL WAS ANCHORED TO THE FEET, WHICH IS WHY IT ORBITED.
                // 137 degrees is the golden angle: it fills a disc evenly around a FIXED
                // centre. Re-anchoring it to the player every pick turns it into a bounded
                // walk instead -- leg n points 137 degrees off leg n-1, so consecutive legs
                // largely cancel and the bot ends up back where it began. Walk out, turn
                // most of the way round, walk back: that is the shuttling the user reported,
                // and the comment right below already stated the intent the code was missing.
                // Anchor the spiral where the wandering STARTED and the radius genuinely
                // accumulates, which is what an expanding search is.
                net.minecraft.util.math.Vec3d feet = mod.getPlayer().getPos();
                if (wanderAnchor == null
                        || !kaptainwutax.tungsten.TungstenConfig.get().wanderSpiralsFromItsAnchor) {
                    wanderAnchor = feet;
                }
                net.minecraft.util.math.Vec3d from = wanderAnchor;
                double ang = ((wanderTungPicked * 137) % 360) * Math.PI / 180.0;
                // ⛔ distanceToWander CAN BE INFINITE, AND THAT POISONED THE WHOLE SEARCH.
                //
                // TimeoutWanderTask(Float.POSITIVE_INFINITY) means "wander without a limit" and is
                // a normal construction -- isFinished() has an explicit isInfinite() branch for it.
                // This line then produced r = Infinity, and cos/sin of the sampled angle turned that
                // into a destination of (Infinity, y, NaN): 0 * Infinity is NaN.
                //
                // Every distance to a NaN target is NaN, every comparison against it is false, so
                // isPathComplete could never be true however many nodes were expanded. Confirmed by
                // raw coordinates: agent[0.5,-60.0,0.5] -> tgt[Infinity,-60.0,NaN] d=NaN, with
                // goalTests=93 and tryEmit=0/0.
                //
                // Mine, introduced porting the wander off the legacy engine. A wander radius is a
                // distance to walk, and there is no such thing as walking infinitely far in one leg.
                double base = distanceToWander + _wanderDistanceExtension;
                if (!Double.isFinite(base)) base = 32.0;
                double r = Math.max(8.0, Math.min(base, 96.0))
                        + Math.min(24.0, wanderTungPicked * 2.0);
                net.minecraft.util.math.Vec3d dest = new net.minecraft.util.math.Vec3d(
                        from.x + Math.cos(ang) * r, from.y, from.z + Math.sin(ang) * r);
                // AIM AT A CELL THAT EXISTS. The Y above is the ANCHOR's, while the XZ walks a
                // spiral of up to 120 blocks over real terrain -- so the point is inside rock or in
                // mid-air on 94% of picks (measured: 85 of 94, 158 of 164, 9 of 9). Scan a band
                // around that height for somewhere the bot could actually stand; keep the original
                // when the band holds nothing, so this can only leave a pick where it already was.
                if (kaptainwutax.tungsten.TungstenConfig.get().wanderTargetFollowsTheGround) {
                    dest = groundedNear(mod, dest);
                }
                // ⛔ THE SPIRAL INDEX MUST COUNT LEGS, NOT ATTEMPTS. Both the angle and the
                // radius above are derived from wanderTungPicked, and this increment used to
                // happen whether or not tryPathTo took the target -- so a run reading
                // wanderTung=316/33 had turned the golden angle 316 times to walk 33 legs, which
                // spaces ATTEMPTS evenly and leaves the legs at arbitrary angles to each other.
                // The radius term saturates at +24 within a dozen refused ticks too. Most of
                // those refusals are the one-second cooldown between path starts.
                boolean tookIt = adris.altoclef.util.helpers.TungstenHelper.tryPathTo(dest);
                if (tookIt) {
                    wanderTungDriven++;
                } else if (kaptainwutax.tungsten.TungstenConfig.get().wanderSpiralCountsLegsNotTries) {
                    wanderSpiralHeld++;
                }
                if (tookIt
                        || !kaptainwutax.tungsten.TungstenConfig.get().wanderSpiralCountsLegsNotTries) {
                    wanderTungPicked++;
                }
                // ⛔ CAN THE BOT EVEN STAND WHERE THIS IS SENDING IT?
                //
                // dest takes its Y from the ANCHOR and its XZ from a spiral of up to 120 blocks, so
                // on any terrain with relief the point is routinely inside rock or hanging in air.
                // The dead time sits here -- the wander holds the tick by the thousand, covers half
                // a block, and cannot re-pick while TungstenHelper.isActive() is true, which it is
                // for as long as the search keeps retrying an unreachable point. Whether that is
                // what happens is a question about the TARGET, and it has never been asked.
                // Counted, not assumed: wanderTargetUnstandable against wanderTungPicked.
                try {
                    net.minecraft.world.World w = mod.getWorld();
                    if (w != null && !adris.altoclef.tasks.movement.CustomBaritoneGoalTask.standable(
                            w, (int) Math.floor(dest.x), (int) Math.floor(dest.y),
                            (int) Math.floor(dest.z))) {
                        wanderTargetUnstandable++;
                    }
                } catch (Exception ignored) {
                    // a counter must never break the wander it watches
                }

            }
        }
        boolean progressing = progressChecker.check(mod);
        if (progressing) {
            wanderCheckOk++;
        } else {
            wanderCheckTrip++;
        }
        wanderFailPeak = Math.max(wanderFailPeak, failCounter);
        if (!progressing) {
            // TODOS.md, the self-resetting sawtooth (2026-09-02/03): resetting the checker here
            // unconditionally re-anchors it to wherever the bot is stalled RIGHT NOW, so a stall
            // longer than one ~6s window is never visible as one, even though failCounter still
            // climbs correctly toward isFinished()'s threshold. Mirrors GetToEntityTask's
            // entitySearchMustMove fix: when tungsten itself is the thing not moving, actually
            // release it (TungstenHelper.stop(), not the documented no-op Nav.cancel() a few
            // lines up) instead of resetting -- "a release is not movement, so the checker
            // stands" (same reasoning, same file that pattern already lives in).
            if (kaptainwutax.tungsten.TungstenConfig.get().wanderSearchMustMove
                    && adris.altoclef.util.helpers.TungstenHelper.isActive()) {
                wanderSearchReleased++;
                adris.altoclef.util.helpers.TungstenHelper.stop();
            } else {
                progressChecker.reset();
            }
            // COUNT THE FAILURE EVEN WHEN EXPLORING WAS FORCED, or the escape above can never
            // trigger for the one caller that most needs it. _forceExplore only ever meant "do not
            // shout about it" -- it was silencing the counter as well as the message, which is how
            // the search fallback ended up in a wander with both exits sealed.
            failCounter++;
            if (!_forceExplore) {
                Debug.logMessage("Failed exploring.");
            }
        }
        return null;
    }

    /** Ticks this task spent running. Read as wander. */
    /** Where the outward spiral is measured from; null until the first pick of a wander. */
    private net.minecraft.util.math.Vec3d wanderAnchor = null;

    public static volatile int wanderTicks;

    /** Ticks the body has not moved; the odometer that replaces "Nav says it is pathing". */
    private int _ticksSinceMoved = 0;
    private net.minecraft.util.math.Vec3d _lastMoveTickPos = null;

    /** How long the body may be still before a pathing claim stops counting as progress. */
    private static final int STALL_MOVE_GRACE = 40;

    /** Resets REFUSED because the body had not moved; reads 0 with the flag off. */
    public static volatile int wanderResetDenied;

    /** Ticks this task did NOT take the keys off a close walk; 0 with closeWalkKeepsKeys off. */
    public static volatile int wanderKeysKept;

    /**
     * What the give-up machinery actually saw. Read as wanderChk=ok/trip and wanderFail=peak.
     *
     * <p>Three fixes in a row were made from reading this task and every one landed on the same
     * course score, so the next move is not another edit: these say whether the progress checker
     * EVER trips during a failing run, and how high failCounter climbs before the run ends. The
     * escape needs eleven trips; if the checker is satisfied by a bot crawling 0.1 blocks per six
     * seconds, it will never get there and no amount of unsealing the exit will matter.
     */
    public static volatile int wanderCheckOk, wanderCheckTrip, wanderFailPeak;

    /** Give-up trips that fired while the re-pick was still blocked. Read as wanderTripBlocked
     *  against wanderChk's trip half; equal counts mean the give-up branch changes nothing. */
    public static volatile int wanderTripBlocked;

    /** Trips that released a stuck tungsten search instead of resetting the checker; mechanism
     *  counter for {@link kaptainwutax.tungsten.TungstenConfig#wanderSearchMustMove}. Reads 0
     *  with the flag off, and should track wanderTripBlocked when it is on -- both count the
     *  same "give-up fired while tungsten still held it" moment, one before the fix and one
     *  after. */
    public static volatile int wanderSearchReleased;

    /**
     * Ground covered, in centimetres, on ticks where THIS task was the one running. Read as
     * wanderMoved. Centimetres so it stays an int in the stats line; a course divides by 100.
     */
    public static volatile int wanderMovedCm;

    private Vec3d lastTickPos;

    @Override
    protected void onStop(Task interruptTask) {
        Nav.cancel();
        if (isFinished()) {
            if (increaseRange) {
                _wanderDistanceExtension += distanceToWander;
                Debug.logMessage("Increased wander range");
            }
        }
    }

    @Override
    public boolean isFinished() {
        // Why the heck did I add this in?
        //if (_origin == null) return true;

        // A WANDER THAT CANNOT END IS A DEAD END, AND IT IS THE ONE EVERY SEARCH FALLS INTO.
        //
        // AbstractDoToClosestObjectTask hands back `new TimeoutWanderTask(true)` when it cannot
        // find its target, and that constructor routes to the INFINITE distance one. So the guard
        // below used to answer "not finished" for ever, and the give-up counter underneath it was
        // unreachable for exactly the wander that needed it. The bot entered this task and never
        // came back to searching.
        //
        // Measured on chop_canopy, from the task's own odometer:
        //   passing runs:  wanderTicks=0        (never entered)
        //   failing runs:  wanderTicks=3089     wanderMoved=2135cm
        // Three thousand ticks is the WHOLE run -- about 154 seconds spent covering twenty-one
        // blocks and never retrying the search, which is what the frozen positions in the timeline
        // look like from the inside.
        //
        // Order matters: the give-up test goes FIRST, so an endless wander can still end. The
        // infinite distance keeps its meaning -- "no target distance, just get away" -- it simply
        // no longer means "for ever".
        if (failCounter > 10) {
            return true;
        }

        if (Float.isInfinite(distanceToWander)) return false;

        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        if (player != null && player.getPos() != null && (player.isOnGround() ||
                player.isTouchingWater())) {
            double sqDist = player.getPos().squaredDistanceTo(origin);
            double toWander = distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task.distanceToWander) || Float.isInfinite(distanceToWander)) {
                return Float.isInfinite(task.distanceToWander) == Float.isInfinite(distanceToWander);
            }
            return Math.abs(task.distanceToWander - distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Wander for " + (distanceToWander + _wanderDistanceExtension) + " blocks";
    }
}

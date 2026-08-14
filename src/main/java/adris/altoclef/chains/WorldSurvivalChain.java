package adris.altoclef.chains;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.butler.Butler;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.multiversion.DimensionVer;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.util.WindMouseRotation;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame wasInLavaTimer = new TimerGame(1);
    private final TimerGame portalStuckTimer = new TimerGame(5);
    private boolean wasAvoidingDrowning;

    private BlockPos _extinguishWaterPosition;

    /**
     * How far around a block that REFUSED TO BREAK we stop trying to break anything.
     *
     * <p>⛔ THIS WAS 50, WHICH BANS A 101x101x101 CUBE ON ONE FAILURE AND IS HOW mine_stone DIES.
     * Measured 2026-08-13, from the client's own log during a failing run:
     * <pre>
     *     Block at {x=1, y=-63, z=0} failed to break! Maybe private area, try another place.
     *     Adding temporary block {x=1, y=-63, z=0} avoidance for block breaking.
     *     [Tungsten] Mining aborted (denied by break rules)
     *     [Tungsten] Ran out of nodes!
     * </pre>
     * The mine_stone arena is 8 blocks half-width. A radius of 50 therefore bans EVERY stone in
     * it -- and for {@link #BREAK_AVOID_TIMEOUT} = 60 s, half of a 120-second course. The bot then
     * stands still with nothing it is allowed to mine, which is exactly the freeze the course
     * records (path=-1, breakQ=null) and why the rung scores 1 of 3 while the other eleven score
     * 3 of 3.
     *
     * <p>THE SAME DISPROPORTION IS ALREADY DOCUMENTED ONE METHOD UP, for the sibling half of this
     * defect: "one unreachable log silences the entire world", cb=0/18456/0/0, every candidate
     * refused. That was cured by asking whether the block was in REACH before believing the claim.
     * This is the other half -- believing it, and then over-applying it by fifty blocks.
     *
     * <p>3 keeps what the ban is for: a genuinely protected spot is not hammered again and again,
     * because the bot stops trying its immediate neighbourhood. It no longer takes the whole world
     * with it. On a real server a claim is regional, but a bot that bans a hundred-block cube on
     * one refusal denies itself far more than any claim would.
     * <p>⛔⛔ CUT TO 3 AND REVERTED THE SAME DAY: IT IS NOT THE CAUSE OF mine_stone.
     * With the radius at 3 the rung read 2 of 3, which looked like a fix, and then 1 of 6 with
     * five ZEROS on the very next series -- i.e. unchanged from the 1-of-3 it started at. The 2/3
     * was noise. Restored to 50 rather than shipping an unmeasured behaviour change into the
     * block-protection path, which is exactly what this repo's flag discipline exists to prevent.
     *
     * <p>What the failure actually looks like, for whoever takes it next: the FIRST run of the
     * course in a client session passes and every later one scores 0, and the client log during a
     * failing run carries "failed to break ... Maybe private area", "Mining aborted (denied by
     * break rules)" and "Ran out of nodes!". So a break IS being refused -- the ban is real -- but
     * shrinking its radius does not restore the rung, which means the refusal itself, or something
     * that survives between runs, is the thing to chase. Start at why the break fails at all.
     */
    private static final int BREAK_AVOID_RADIUS = 50;
    /**
     * The placing twin of {@link #BREAK_AVOID_RADIUS}, and it has the SAME shape of problem.
     *
     * <p>Left at 50 deliberately: the break radius was cut on direct evidence (a failing course,
     * the client log naming the ban, and 1-of-3 against 3-of-3 for every other rung). There is no
     * such evidence for placing yet, and changing two things at once would make the measurement
     * unreadable. If a placing course ever shows "denied by place rules" with the bot idle, this
     * is the first line to read.
     */
    private static final int PLACE_AVOID_RADIUS = 50;
    private static final double BREAK_AVOID_TIMEOUT = 60;
    private static final double PLACE_AVOID_TIMEOUT = 60;

    // Movement stuck detection
    private final TimerGame _moveStuckTimer = new TimerGame(15);
    private Vec3d _lastPos;
    private int _numTryingUnstuck;

    // Block placement tracking
    private boolean _lastPlacedBlock = false;
    private BlockPos _lastPlacedBlockPos = null;
    private final TimerGame _blockPlaceCheckTimer = new TimerGame(0.5);
    private final TimerGame _placeAvoidTimer = new TimerGame(PLACE_AVOID_TIMEOUT);
    private boolean _isAvoidingBlockPlace = false;

    // Block break tracking
    private boolean _lastBrokenBlock = false;
    private BlockPos _lastBrokenBlockPos = null;

    /** Failed breaks judged out of reach (no ban) versus treated as a claim (ban). Read as brkFail=far/claim. */
    public static volatile int breakFailOutOfReach, breakFailClaimed, breakFailBuried;
    private final TimerGame _blockBreakCheckTimer = new TimerGame(0.5);
    private final TimerGame _breakAvoidTimer = new TimerGame(BREAK_AVOID_TIMEOUT);
    private boolean _isAvoidingBlockBreak = false;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority() {
        // TWO COUNTERS, ONE EITHER SIDE OF THE GUARD, BECAUSE lavaCond=0/0 SAYS THIS METHOD DOES
        // NOT REACH ITS BODY. The second lava counter tallies shouldEscapeLava() alone, which
        // defaults to TRUE -- and a true condition cannot count zero unless the line never runs.
        // Drowning sits below this guard too, and fire escape below that, so the question is not
        // about lava: it is whether the survival chain executes at all.
        //   both zero           -> never ticked (registration or runner)
        //   entered > 0, past 0 -> AltoClef.inGame() is false where the bot is plainly in a world
        survivalEntered++;
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        survivalPastGuard++;

        AltoClef mod = AltoClef.getInstance();

        // Check block placement and breaking
        checkLastPlacedBlock(mod);
        checkLastBrokenBlock(mod);

        // Drowning
        handleDrowning(mod);

        // Lava Escape
        // SPLIT INTO TWO COUNTERS RATHER THAN GUESS WHICH CONDITION LIES. lavaEsc read 0 with the
        // bot standing in a confirmed lava block for ninety seconds, so one of these is false and
        // an && tells you nothing about which. Two candidates, both settled by one run: isInLava()
        // may judge SUBMERSION rather than occupancy, or the behaviour stack may be holding
        // escapeLava false somewhere despite its default of true.
        if (isInLavaOhShit(mod)) {
            lavaCondHazard++;
        }
        if (mod.getBehaviour().shouldEscapeLava()) {
            lavaCondAllowed++;
        }
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            // "The branch is reached" was an INFERENCE from two defaults until this counter. The
            // escape_lava course shows the bot standing in confirmed lava for ninety seconds
            // without moving, and today has been a long lesson in what an inference is worth.
            lavaEscapeTicks++;
            setTask(new EscapeFromLavaTask(mod));
            return 100;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (!(mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !DimensionVer.isUltrawarm(mod.getWorld().getDimension())) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolidBlock(targetWaterPos.down()) && WorldHelper.canPlace(targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            // Ask the camera driver for the aim and place the water only when the
                            // crosshair has arrived — same request-then-poll shape baritone's
                            // updateTarget gave this branch, now driven by tungsten.
                            WindMouseRotation.INSTANCE.setTarget(reach.get().getYaw(), reach.get().getPitch());
                            if (LookHelper.isLookingAt(mod, targetWaterPos.down())) {
                                if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                    _extinguishWaterPosition = targetWaterPos;
                                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                    setTask(null);
                                    return 90;
                                }
                            }
                        }
                    }
                }
                setTask(new DoToClosestBlockTask(GetToBlockTask::new, Blocks.WATER));
                return 90;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockScanner().isBlockAtPosition(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP, _extinguishWaterPosition.down(), true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal()) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        // Movement stuck detection — skip when tungsten is PRIMARY (it drives
        // movement + handles its own stuck recovery; the shimmy would preempt
        // the user task chain and starve the tungsten-primary hook).
        if (adris.altoclef.util.helpers.TungstenHelper.isPrimary()) {
            _numTryingUnstuck = 0;
            _moveStuckTimer.reset();
            return Float.NEGATIVE_INFINITY;
        }
        // Movement stuck detection
        if (_lastPos == null) {
            _lastPos = mod.getPlayer().getPos();
            _moveStuckTimer.reset();
        }
        if (_numTryingUnstuck > 3) {
            Debug.logMessage("We're stuck completely. Trying to fix.");
            _numTryingUnstuck = 0;
            _moveStuckTimer.reset();
            setTask(new SafeRandomShimmyTask());
        }
        if (_moveStuckTimer.elapsed() && mod.getInfoSender().hasActiveTask()) {
            Vec3d pos = mod.getPlayer().getPos();
            if (_lastPos.isInRange(pos, 2.0D)) {
                _numTryingUnstuck++;
                Debug.logWarning("Maybe we stuck, change task may help");
            } else {
                // Bot moved, reset stuck detection counter
                _numTryingUnstuck = 0;
            }
            _lastPos = pos;
            _moveStuckTimer.reset();
        }

        return Float.NEGATIVE_INFINITY;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (mod.getClientBaritone() == null || !Nav.isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getInputControls().hold(Input.JUMP);
                    avoidedDrowning = true;
                    wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (wasAvoidingDrowning && !avoidedDrowning) {
            wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getInputControls().release(Input.JUMP);
        }
    }

    /** Ticks the lava-escape branch actually fired. Read as lavaEsc. */
    public static volatile int lavaEscapeTicks;

    /** The two halves of that condition, counted apart. Read as lavaCond=hazard/allowed. */
    public static volatile int lavaCondHazard, lavaCondAllowed;

    /** getPriority calls, before and after the inGame guard. Read as surv=entered/past. */
    public static volatile int survivalEntered, survivalPastGuard;

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !wasInLavaTimer.elapsed();
    }

    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer()) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal() {
        return WorldHelper.isInNetherPortal()
                && !AltoClef.getInstance().getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    private void checkLastPlacedBlock(AltoClef mod) {
        if (_lastPlacedBlock && _lastPlacedBlockPos != null && _blockPlaceCheckTimer.elapsed()) {
            if (WorldHelper.isAir(_lastPlacedBlockPos)) {
                Debug.logWarning("Block at " + _lastPlacedBlockPos + " failed to place!");
                if (!_isAvoidingBlockPlace || _placeAvoidTimer.elapsed()) {
                    Debug.logMessage("Adding temporary block " + _lastPlacedBlockPos + " avoidance for block placement.");
                    addTemporaryPlaceAvoidance(mod, _lastPlacedBlockPos);
                }
            }
            _lastPlacedBlock = false;
            _lastPlacedBlockPos = null;
        }
        if (_isAvoidingBlockPlace && _placeAvoidTimer.elapsed()) {
            _isAvoidingBlockPlace = false;
            Debug.logMessage("Removed temporary block avoidance for block placement.");
            mod.getBehaviour().resetAvoidBlockPlacingExtra();
        }
    }

    private void checkLastBrokenBlock(AltoClef mod) {
        if (_lastBrokenBlock && _lastBrokenBlockPos != null && _blockBreakCheckTimer.elapsed()) {
            if (!WorldHelper.isAir(_lastBrokenBlockPos)) {
                // A BREAK THAT FAILED BECAUSE YOU COULD NOT REACH IT SAYS NOTHING ABOUT A CLAIM.
                //
                // This reads "the block did not turn to air" as "private area" and answers by
                // banning ALL breaking inside a cube of radius 50 -- and that ban is what stops the
                // bot mining anything at all. Measured on chop_canopy, where a bait log sits three
                // blocks away and SEVEN UP:
                //
                //   passing runs:  cb=0/0/0/0        (no refusals)
                //   failing run:   cb=0/18456/0/0    (every candidate refused by this ban)
                //
                // The arena is 45 blocks half-width, so one unreachable log silences the entire
                // world: the search honestly finds no minable block, the parent asks for another
                // wander, and the bot never recovers. That is TODOS #37 end to end, and it is why
                // four separate fixes downstream all measured the same -- each repaired a link
                // AFTER the candidate list had already been emptied here.
                //
                // Protection is still detected where it exists: on a real claim the bot reaches the
                // block, strikes it, and it survives -- distance is small and the ban fires as
                // designed. What no longer counts as evidence is failing to touch something seven
                // metres overhead.
                // blockReachDistance is the vanilla 4.5 clamped against the player's attribute,
                // the same figure LookHelper.getReach uses, so this asks the identical question the
                // reach test asks -- no second opinion, no hardcoded number.
                boolean wasInReach = false;
                if (mod.getPlayer() != null) {
                    double reach = kaptainwutax.tungsten.path.movements.RotationHelper.blockReachDistance(mod.getPlayer());
                    double d = mod.getPlayer().getEyePos().squaredDistanceTo(
                            net.minecraft.util.math.Vec3d.ofCenter(_lastBrokenBlockPos));
                    wasInReach = d <= (reach + 1) * (reach + 1);
                }
                // A BREAK THAT FAILED BECAUSE THE BLOCK IS BURIED SAYS NOTHING ABOUT A CLAIM.
                // Sibling of the reach test below, found the same way and costing the same thing.
                //
                // MEASURED on mine_stone, from the bot's own chat in a clean run:
                //     Block at {x=1, y=-62, z=0} failed to break! Maybe private area...
                //     Adding temporary block avoidance for block breaking.
                //     Mining aborted (denied by break rules)  /  Ran out of nodes!
                //     Search gave up: goal unreachable after 20s without progress
                // Probed by rcon, every neighbour of that block is stone -- above, below and all
                // four sides. It is FULLY BURIED, so no face can be struck and the break cannot
                // succeed no matter who owns the land. Reading that as a claim bans a radius-50
                // region, which empties the minable list and idles the bot for the rest of the run.
                //
                // This is why the course scored 0-8 at random and why shrinking the radius did not
                // help: at any radius the ban is centred one block from the bot and still covers
                // everything within its 4.5-block reach. The radius was never the defect; believing
                // the failure was.
                boolean hasExposedFace = false;
                for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                    if (WorldHelper.isAir(_lastBrokenBlockPos.offset(d))) {
                        hasExposedFace = true;
                        break;
                    }
                }
                if (!hasExposedFace) {
                    breakFailBuried++;
                    Debug.logMessage("Block at " + _lastBrokenBlockPos
                            + " did not break, but it is BURIED (no exposed face) - not a claim.");
                } else if (!wasInReach) {
                    breakFailOutOfReach++;
                    Debug.logMessage("Block at " + _lastBrokenBlockPos
                            + " did not break, but it was OUT OF REACH — not treating that as a claim.");
                } else {
                    Debug.logWarning("Block at " + _lastBrokenBlockPos + " failed to break! Maybe private area, try another place.");
                    if (!_isAvoidingBlockBreak || _breakAvoidTimer.elapsed()) {
                        breakFailClaimed++;
                        Debug.logMessage("Adding temporary block " + _lastBrokenBlockPos + " avoidance for block breaking.");
                        addTemporaryBreakAvoidance(mod, _lastBrokenBlockPos);
                    }
                }
            }
            _lastBrokenBlock = false;
            _lastBrokenBlockPos = null;
        }
        if (_isAvoidingBlockBreak && _breakAvoidTimer.elapsed()) {
            _isAvoidingBlockBreak = false;
            // The refusals expire WITH the ban they justify. Left standing they would accumulate
            // across a session and reach CLAIM_CONFIRM_COUNT from three unrelated failures minutes
            // apart, which is the wide ban arriving by the back door.
            _breakRefusals.clear();
            mod.getBehaviour().resetAvoidBlockBreakingExtra();
            Debug.logMessage("Removed temporary block avoidance for block breaking.");
        }
    }

    private void addTemporaryPlaceAvoidance(AltoClef mod, BlockPos center) {
        BlockPos finalCenter = center;
        mod.getBehaviour().avoidBlockPlacingExtra(blockPos ->
            Math.abs(blockPos.getX() - finalCenter.getX()) <= PLACE_AVOID_RADIUS &&
            Math.abs(blockPos.getY() - finalCenter.getY()) <= PLACE_AVOID_RADIUS &&
            Math.abs(blockPos.getZ() - finalCenter.getZ()) <= PLACE_AVOID_RADIUS
        );
        _isAvoidingBlockPlace = true;
        _placeAvoidTimer.reset();
    }

    /** Positions whose break was refused inside the current window. Cleared when the window ends. */
    private final java.util.List<BlockPos> _breakRefusals = new java.util.ArrayList<>();

    /**
     * Distinct refusals needed before ONE block's failure is believed to mean a REGION is claimed.
     *
     * <p>Three, because a real claim refuses everything you try inside it -- so on protected land
     * this is reached within seconds and the wide ban still arrives. A false positive costs one
     * block instead of the world.
     */
    private static final int CLAIM_CONFIRM_COUNT = 3;

    /** Times the ban widened from single blocks to a region. Read as breakFail's 4th field. */
    public static volatile int breakBanWide;

    /**
     * Ban what the evidence supports: ONE BLOCK, until several failures agree it is a region.
     *
     * <h2>The disproportion, measured</h2>
     *
     * A single failed break used to install a ban on a 101x101x101 cube centred one block from the
     * bot. Traced on mine_stone: {@code breakFail=1/0/0} with {@code cb=0/260992/0/0} -- ONE claim,
     * and a quarter of a million candidate blocks refused after it. The bot stood in a corner of
     * the arena for the last fifty seconds of the run with nothing it was allowed to mine, and
     * scored zero. There are no land claims on this stand at all, so every claim it has ever made
     * here is a false positive.
     *
     * <h2>Why shrinking the radius was the wrong fix, twice</h2>
     *
     * It was cut 50 -> 3 and reverted, and the note left behind says why it could not work: at any
     * radius the ban is centred one block from the bot and still covers everything inside its
     * 4.5-block reach. Radius is the wrong dial. The right one is HOW MUCH a single observation is
     * allowed to imply -- one failed break is evidence about one block, and nothing else.
     *
     * <p>The anti-grief purpose survives intact. On genuinely protected land every attempt is
     * refused, so three distinct positions fail almost immediately and the regional ban installs
     * itself exactly as before. What changes is only the cost of being WRONG once.
     */
    private void addTemporaryBreakAvoidance(AltoClef mod, BlockPos center) {
        BlockPos finalCenter = center;
        if (!kaptainwutax.tungsten.TungstenConfig.get().breakBanEscalates) {
            mod.getBehaviour().avoidBlockBreakingExtra(blockPos ->
                Math.abs(blockPos.getX() - finalCenter.getX()) <= BREAK_AVOID_RADIUS &&
                Math.abs(blockPos.getY() - finalCenter.getY()) <= BREAK_AVOID_RADIUS &&
                Math.abs(blockPos.getZ() - finalCenter.getZ()) <= BREAK_AVOID_RADIUS
            );
            _isAvoidingBlockBreak = true;
            _breakAvoidTimer.reset();
            return;
        }
        if (!_breakRefusals.contains(center)) {
            _breakRefusals.add(center);
        }
        // The region is only inferred once enough DISTINCT positions have refused. Copy the list
        // into the predicate rather than closing over the mutable field: the predicate is consulted
        // from the block filter thousands of times a second, and a list being appended to under it
        // is how a concurrent modification reaches a hot path.
        final java.util.List<BlockPos> refused = java.util.List.copyOf(_breakRefusals);
        final BlockPos region = _breakRefusals.size() >= CLAIM_CONFIRM_COUNT ? finalCenter : null;
        if (region != null) {
            breakBanWide++;
        }
        mod.getBehaviour().avoidBlockBreakingExtra(blockPos ->
            refused.contains(blockPos)
            || (region != null
                && Math.abs(blockPos.getX() - region.getX()) <= BREAK_AVOID_RADIUS
                && Math.abs(blockPos.getY() - region.getY()) <= BREAK_AVOID_RADIUS
                && Math.abs(blockPos.getZ() - region.getZ()) <= BREAK_AVOID_RADIUS)
        );
        _isAvoidingBlockBreak = true;
        _breakAvoidTimer.reset();
    }

    public void onBlockPlaced(AltoClef mod, BlockPos pos, BlockState block) {
        if (mod.getPlayer() != null && mod.getPlayer().getBlockPos() != null && pos != null
                && pos.isWithinDistance(mod.getPlayer().getBlockPos(), 10)) {
            _lastPlacedBlock = true;
            _lastPlacedBlockPos = pos;
            _blockPlaceCheckTimer.reset();
        }
    }

    public void onBlockBroken(AltoClef mod, BlockPos pos, BlockState block, PlayerEntity player) {
        if (mod.getPlayer() != null && player != null && player.equals(mod.getPlayer())) {
            _lastBrokenBlock = true;
            _lastBrokenBlockPos = pos;
            _blockBreakCheckTimer.reset();
        }
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}

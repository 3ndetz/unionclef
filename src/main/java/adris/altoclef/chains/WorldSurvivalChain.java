package adris.altoclef.chains;

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
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
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

    private static final int BREAK_AVOID_RADIUS = 50;
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
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        // Check block placement and breaking
        checkLastPlacedBlock(mod);
        checkLastBrokenBlock(mod);

        // Drowning
        handleDrowning(mod);

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
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
            if (!(mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !mod.getWorld().getDimension().ultrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolidBlock(targetWaterPos.down()) && WorldHelper.canPlace(targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(targetWaterPos.down())) {
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
            _numTryingUnstuck++;
            Vec3d pos = mod.getPlayer().getPos();
            if (_lastPos.isInRange(pos, 2.0D)) {
                if (_moveStuckTimer.getDuration() < 15) {
                    setTask(new SafeRandomShimmyTask());
                    return 60;
                } else {
                    Debug.logWarning("Maybe we stuck, change task may help");
                    if (Butler.IsStuckFixAllow()) {
                        _numTryingUnstuck++;
                    }
                }
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
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (wasAvoidingDrowning && !avoidedDrowning) {
            wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

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
                Debug.logWarning("Block at " + _lastBrokenBlockPos + " failed to break! Maybe private area, try another place.");
                if (!_isAvoidingBlockBreak || _breakAvoidTimer.elapsed()) {
                    Debug.logMessage("Adding temporary block " + _lastBrokenBlockPos + " avoidance for block breaking.");
                    addTemporaryBreakAvoidance(mod, _lastBrokenBlockPos);
                }
            }
            _lastBrokenBlock = false;
            _lastBrokenBlockPos = null;
        }
        if (_isAvoidingBlockBreak && _breakAvoidTimer.elapsed()) {
            _isAvoidingBlockBreak = false;
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

    private void addTemporaryBreakAvoidance(AltoClef mod, BlockPos center) {
        BlockPos finalCenter = center;
        mod.getBehaviour().avoidBlockBreakingExtra(blockPos ->
            Math.abs(blockPos.getX() - finalCenter.getX()) <= BREAK_AVOID_RADIUS &&
            Math.abs(blockPos.getY() - finalCenter.getY()) <= BREAK_AVOID_RADIUS &&
            Math.abs(blockPos.getZ() - finalCenter.getZ()) <= BREAK_AVOID_RADIUS
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

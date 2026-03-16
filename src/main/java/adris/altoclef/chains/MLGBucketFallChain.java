package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

@SuppressWarnings("UnnecessaryLocalVariable")
public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    private final TimerGame tryCollectWaterTimer = new TimerGame(4);
    private final TimerGame pickupRepeatTimer = new TimerGame(0.25);
    private MLGBucketTask lastMLG = null;
    private ThrowEnderPearlSimpleProjectileTask lastEP = null;
    private boolean wasPickingUp = false;
    private boolean doingChorusFruit = false;
    private BlockPos lastGroundBlockPos = null;
    private final TimerGame voidFallTimer = new TimerGame(0.25);

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        //_lastMLG = null;
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        // Track last safe ground position and try ender pearl save in void/hell-hole
        if (mod.getPlayer().isOnGround()) {
            lastGroundBlockPos = mod.getPlayer().getBlockPos();
            voidFallTimer.reset();
        } else if (isInHellHole(mod)) {
            if (mod.getItemStorage().hasItem(Items.ENDER_PEARL) && lastGroundBlockPos != null) {
                if (voidFallTimer.elapsed()) {
                    Optional<Entity> closestPlayer = mod.getEntityTracker().getClosestEntity(
                            mod.getPlayer().getPos(),
                            p -> pearlAllowable(mod, (PlayerEntity) p),
                            PlayerEntity.class);
                    if (closestPlayer.isPresent()) {
                        voidFallTimer.reset();
                        Debug.logMessage("Pearl clutch to nearest player!");
                        setTask(new ThrowEnderPearlSimpleProjectileTask(closestPlayer.get().getBlockPos()));
                        lastEP = (ThrowEnderPearlSimpleProjectileTask) mainTask;
                        return 100;
                    } else {
                        voidFallTimer.reset();
                        Debug.logMessage("Pearl clutch to last ground block! Vel: " + mod.getPlayer().getVelocity().getY());
                        setTask(new ThrowEnderPearlSimpleProjectileTask(lastGroundBlockPos.add(0,
                                (int) (-0.9 - mod.getPlayer().getVelocity().getY()), 0)));
                        lastEP = (ThrowEnderPearlSimpleProjectileTask) mainTask;
                        return 100;
                    }
                }
            }
        }

        if (isFalling(mod)) {
            tryCollectWaterTimer.reset();
            setTask(new MLGBucketTask());
            lastMLG = (MLGBucketTask) mainTask;
            return 100;
        } else if (!tryCollectWaterTimer.elapsed()) { // Why -0.5? Cause it's slower than -0.7.
            // We just placed water, try to collect it.
            if (mod.getItemStorage().hasItem(Items.BUCKET) && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                if (lastMLG != null) {
                    BlockPos placed = lastMLG.getWaterPlacedPos();
                    boolean isPlacedWater;
                    try {
                        isPlacedWater = mod.getWorld().getBlockState(placed).getBlock() == Blocks.WATER;
                    } catch (Exception e) {
                        isPlacedWater = false;
                    }
                    //Debug.logInternal("PLACED: " + placed);
                    if (placed != null && placed.isWithinDistance(mod.getPlayer().getPos(), 5.5) && isPlacedWater) {
                        BlockPos toInteract = placed;
                        // Allow looking at fluids
                        mod.getBehaviour().push();
                        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);
                        Optional<Rotation> reach = LookHelper.getReach(toInteract, Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(toInteract)) {
                                if (mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                                    if (pickupRepeatTimer.elapsed()) {
                                        // Pick up
                                        pickupRepeatTimer.reset();
                                        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                        wasPickingUp = true;
                                    } else if (wasPickingUp) {
                                        // Stop picking up, wait and try again.
                                        wasPickingUp = false;
                                    }
                                }
                            }
                        } else {
                            // Eh just try collecting water the regular way if all else fails.
                            setTask(TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1));
                        }
                        mod.getBehaviour().pop();
                        return 60;
                    }
                }
            }
        }
        if (wasPickingUp) {
            wasPickingUp = false;
            lastMLG = null;
        }
        if (mod.getPlayer().hasStatusEffect(StatusEffects.LEVITATION) &&
                !mod.getPlayer().getItemCooldownManager().isCoolingDown(Items.CHORUS_FRUIT) &&
                mod.getPlayer().getActiveStatusEffects().get(StatusEffects.LEVITATION).getDuration() <= 70 &&
                mod.getItemStorage().hasItemInventoryOnly(Items.CHORUS_FRUIT) &&
                !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            doingChorusFruit = true;
            mod.getSlotHandler().forceEquipItem(Items.CHORUS_FRUIT);
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else if (doingChorusFruit) {
            doingChorusFruit = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        lastMLG = null;
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket Fall Chain";
    }

    @Override
    public boolean isActive() {
        // We're always checking for mlg.
        return true;
    }

    public boolean doneMLG() {
        return lastMLG == null;
    }

    public boolean isChorusFruiting() {
        return doingChorusFruit;
    }

    public boolean isFalling(AltoClef mod) {
        if (!mod.getModSettings().shouldAutoMLGBucket()) {
            return false;
        }
        if (mod.getPlayer().isSwimming() || mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround() || mod.getPlayer().isClimbing()) {
            // We're grounded.
            return false;
        }
        double ySpeed = mod.getPlayer().getVelocity().y;
        return ySpeed < -0.7;
    }

    public boolean isInHellHole(AltoClef mod) {
        return WorldHelper.isHellHole(mod, mod.getPlayer().getBlockPos());
    }

    private boolean pearlAllowable(AltoClef mod, PlayerEntity player) {
        if (player.equals(mod.getPlayer())) return false;
        return LookHelper.cleanLineOfSight(player.getPos(), 100)
                && !WorldHelper.isHellHole(mod, player.getBlockPos());
    }
}

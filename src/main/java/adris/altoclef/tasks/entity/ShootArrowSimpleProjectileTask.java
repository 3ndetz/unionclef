package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;

import static adris.altoclef.util.helpers.ItemHelper.ARROWS;
import static net.minecraft.item.CrossbowItem.isCharged;

public class ShootArrowSimpleProjectileTask extends Task {

    private final Entity target;
    private boolean shooting = false;
    private boolean shot = false;
    private boolean failed = false;
    private Item _rangedItem = Items.BOW;
    private boolean _highAng = false;
    // 0.2-second timer between bow press cycles (prevents spam re-draws)
    private final TimerGame _shotTimer = new TimerGame(0.2);
    // Rapid-fire mode: less charge time required when close to target
    private final double RAPID_FIRE_DISTANCE = 10.0;
    private boolean _rapidFireMode = false;

    public ShootArrowSimpleProjectileTask(Entity target) {
        this.target = target;
    }

    @Override
    protected void onStart() {
        shooting = false;
        AltoClef.getInstance().getBehaviour().push();
    }

    // --- Static helpers (used by KillPlayerTask etc.) ---

    public static boolean shouldUseHighAngRanged(Entity target) {
        return !LookHelper.cleanLineOfSight(target.getEyePos(), 100);
    }

    /**
     * Full ballistic trajectory calculation with predictive lead targeting.
     * Matches autoclef exactly: uses target.prevX/Z for velocity extrapolation,
     * supports high-angle (artillery) mode, handles NaN gracefully.
     * NO direct setYaw/setPitch — only returns a Rotation for smoothLook.
     */
    public static Rotation calculateThrowLook(AltoClef mod, Entity target, Item rangedItem) {
        return calculateThrowLook(mod, target, shouldUseHighAngRanged(target), rangedItem);
    }

    public static Rotation calculateThrowLook(AltoClef mod, Entity target, boolean highAng, Item rangedItem) {
        float velocity = 1;
        if (rangedItem != null && rangedItem.equals(Items.BOW)) {
            int useTime = mod.getPlayer().getItemUseTime();
            if (useTime > 5) {
                velocity = useTime / 20f;
                velocity = (velocity * velocity + velocity * 2) / 3;
                if (velocity < 0.5f) velocity = 0.5f;
                if (velocity > 1f) velocity = 1f;
            }
        }

        // Lead multiplier: direct fire = 11.4x, artillery = 100x (longer flight time)
        double velMult = highAng ? 100 : 11.4;

        double velX = (target.getPos().getX() - target.prevX) * velMult;
        double velZ = (target.getPos().getZ() - target.prevZ) * velMult;

        // Predicted position: current + 1 tick movement + lead offset
        double posX = target.getPos().getX() + (target.getPos().getX() - target.prevX) + velX;
        double posY = target.getPos().getY() + (target.getPos().getY() - target.prevY);
        double posZ = target.getPos().getZ() + (target.getPos().getZ() - target.prevZ) + velZ;

        // Adjust for hitbox height
        posY -= 1.9f - target.getHeight();

        double relativeX = posX - mod.getPlayer().getX();
        double relativeY = posY - mod.getPlayer().getY();
        double relativeZ = posZ - mod.getPlayer().getZ();

        double hDistance = Math.sqrt(relativeX * relativeX + relativeZ * relativeZ);
        double hDistanceSq = hDistance * hDistance;
        float g = 0.006f;
        float velocitySq = velocity * velocity;
        float pitch = mod.getPlayer().getPitch();

        if (highAng) {
            // Artillery mode: arrow loses ~30% speed at apex
            velocitySq = velocitySq * 0.7f;
            pitch = (float) -Math.toDegrees(Math.atan2(
                    (velocitySq + Math.sqrt(velocitySq * velocitySq - g * (g * hDistanceSq + 2 * relativeY * velocitySq))),
                    (g * hDistance)));
        } else {
            pitch = (float) -Math.toDegrees(Math.atan(
                    (velocitySq - Math.sqrt(velocitySq * velocitySq - g * (g * hDistanceSq + 2 * relativeY * velocitySq)))
                            / (g * hDistance)));
        }

        if (Float.isNaN(pitch)) {
            // NaN fallback: retry with full velocity = 1
            velocity = 1;
            velocitySq = velocity * velocity;
            if (highAng) {
                velocitySq = velocitySq * 0.7f;
                pitch = (float) -Math.toDegrees(Math.atan2(
                        (velocitySq + Math.sqrt(velocitySq * velocitySq - g * (g * hDistanceSq + 2 * relativeY * velocitySq))),
                        (g * hDistance)));
            } else {
                pitch = (float) -Math.toDegrees(Math.atan(
                        (velocitySq - Math.sqrt(velocitySq * velocitySq - g * (g * hDistanceSq + 2 * relativeY * velocitySq)))
                                / (g * hDistance)));
            }
            if (Float.isNaN(pitch))
                return new Rotation(mod.getPlayer().getYaw(), mod.getPlayer().getPitch());
            else
                return new Rotation(Vec3dToYaw(mod, new Vec3d(posX, posY, posZ)), pitch);
        } else {
            return new Rotation(Vec3dToYaw(mod, new Vec3d(posX, posY, posZ)), pitch);
        }
    }

    private static float Vec3dToYaw(AltoClef mod, Vec3d vec) {
        return (mod.getPlayer().getYaw() +
                MathHelper.wrapDegrees((float) Math.toDegrees(
                        Math.atan2(vec.getZ() - mod.getPlayer().getZ(),
                                vec.getX() - mod.getPlayer().getX())) - 90f - mod.getPlayer().getYaw()));
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // --- Weapon selection ---
        if (hasArrows(mod)) {
            if (mod.getItemStorage().hasItemInventoryOnly(Items.BOW)) {
                setDebugState("Bow");
                _rangedItem = Items.BOW;
            } else if (mod.getItemStorage().hasItemInventoryOnly(Items.CROSSBOW)) {
                setDebugState("Crossbow (EXPERIMENTAL)");
                _rangedItem = Items.CROSSBOW;
            } else {
                setDebugState("DON'T HAVE RANGED WEAPON!");
                failed = true;
                return null;
            }
        } else {
            setDebugState("DON'T HAVE ARROWS!");
            failed = true;
            return null;
        }

        // --- Rapid fire detection ---
        double distanceToTarget = mod.getPlayer().distanceTo(target) / 2.0;
        _rapidFireMode = _rangedItem == Items.BOW && distanceToTarget <= RAPID_FIRE_DISTANCE;

        int useTime = mod.getPlayer().getItemUseTime();

        // --- Smooth look (WindMouse only — NO direct setYaw/setPitch!) ---
        // Only update aim direction once the bow is being drawn (useTime > 1)
        // At useTime <= 1 we skip to avoid fighting Baritone during approach
        if (useTime > 1) {
            _highAng = shouldUseHighAngRanged(target);
            Rotation lookTarget = calculateThrowLook(mod, target, _highAng, _rangedItem);
            LookHelper.smoothLook(mod, lookTarget, 1.0f);
        }

        mod.getSlotHandler().forceEquipItem(_rangedItem);

        // --- Charge state ---
        boolean charged;
        boolean projectileReady = false;
        boolean isBow = _rangedItem == Items.BOW;

        if (isBow) {
            int requiredChargeTime;
            if (_rapidFireMode) {
                // Rapid fire: charge less when close
                if (distanceToTarget < 4) {
                    requiredChargeTime = MathHelper.clamp(useTime * 2, 4, 6);
                } else if (distanceToTarget < 7) {
                    requiredChargeTime = MathHelper.clamp(useTime * 2, 8, 10);
                } else {
                    requiredChargeTime = MathHelper.clamp(useTime * 2, 10, 12);
                }
            } else {
                requiredChargeTime = 20;
            }
            charged = mod.getPlayer().getActiveItem().getItem() == _rangedItem && useTime > requiredChargeTime;

            if (_rapidFireMode && charged) {
                setDebugState("Bow (RAPID FIRE MODE)");
            }
        } else if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == _rangedItem) {
            if (_rangedItem == Items.CROSSBOW) {
                projectileReady = isCharged(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()));
                setDebugState("Crossbow ready=" + projectileReady + " use=" + useTime);
                if (!projectileReady) {
                    charged = useTime > 40;
                    if (!charged) {
                        setDebugState("charging crossbow...");
                        LookHelper.tryAvoidingInteractable(mod);
                        mod.getInputControls().hold(Input.CLICK_RIGHT);
                        return null;
                    } else {
                        mod.getInputControls().release(Input.CLICK_RIGHT);
                    }
                } else {
                    setDebugState("crossbow ready!");
                    charged = true;
                }
            } else {
                projectileReady = true;
                charged = true;
            }
        } else {
            setDebugState("Item not active");
            charged = false;
            return null;
        }

        // --- Start drawing bow (timer-based, no rotation check required) ---
        if (isBow) {
            if (!shooting || _shotTimer.elapsed()) {
                LookHelper.tryAvoidingInteractable(mod);
                mod.getInputControls().hold(Input.CLICK_RIGHT);
                shooting = true;
                _shotTimer.reset();
            }
        } else {
            shooting = true;
        }

        // --- Release when charged, no friendly arrow already in flight ---
        if (shooting && charged) {
            List<ProjectileEntity> arrows = mod.getEntityTracker().getTrackedEntities(ProjectileEntity.class);
            for (ProjectileEntity arrow : arrows) {
                if (arrow.getOwner() == mod.getPlayer()) {
                    Vec3d velocity = arrow.getVelocity();
                    Vec3d delta = target.getPos().subtract(arrow.getPos());
                    boolean isMovingTowardsTarget = velocity.dotProduct(delta) > 0;
                    if (isMovingTowardsTarget) {
                        return null; // wait for in-flight arrow to land
                    }
                }
            }

            // Increase simulation distance for far targets (prevents arrow despawn)
            if (BeatMinecraft2Task.getConfig().renderDistanceManipulation
                    && MinecraftClient.getInstance().options.getSimulationDistance().getValue() < 32) {
                MinecraftClient.getInstance().options.getSimulationDistance().setValue(32);
            }

            if (isBow) {
                mod.getInputControls().release(Input.CLICK_RIGHT);
                shot = true;
            } else if (projectileReady) {
                Debug.logMessage("Performed ranged weapon shot " + target.getName().getString());
                LookHelper.tryAvoidingInteractable(mod);
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                shot = true;
            }
        }

        // High-angle: tilt EpicCamera to see the arc
        if (_highAng) {
            mod.getBehaviour().setCameraRotationModifer(LookHelper.getLookRotation(mod, target.getPos()).getPitch());
        } else {
            mod.getBehaviour().resetCameraRotationModifer();
        }

        setDebugState("Charging?");
        return null;
    }

    // --- Static utility methods ---

    public static boolean hasArrows(AltoClef mod) {
        List<Item> requiredArrows = Arrays.asList(ARROWS);
        return requiredArrows.stream().anyMatch(mod.getItemStorage()::hasItemInventoryOnly);
    }

    public static boolean hasShootingWeapon(AltoClef mod) {
        return Arrays.stream(ItemHelper.ShootWeapons).anyMatch(mod.getItemStorage()::hasItemInventoryOnly);
    }

    public static boolean readyForBow(AltoClef mod) {
        return hasArrows(mod) && mod.getItemStorage().hasItemInventoryOnly(Items.BOW);
    }

    public static boolean readyForCrossbow(AltoClef mod) {
        return hasArrows(mod) && mod.getItemStorage().hasItemInventoryOnly(Items.CROSSBOW);
    }

    public static boolean readyForRanged(AltoClef mod) {
        return hasArrows(mod) && (mod.getItemStorage().hasItemInventoryOnly(Items.BOW)
                || mod.getItemStorage().hasItemInventoryOnly(Items.CROSSBOW));
    }

    public static boolean checkRangedAttackTrajectory(AltoClef mod, Entity target) {
        Vec3d playerPos = mod.getPlayer().getEyePos();
        Vec3d targetPos = target.getEyePos();
        double distance = playerPos.distanceTo(targetPos);
        if (distance > 100) return false;

        // Direct LOS
        if (LookHelper.cleanLineOfSight(target.getEyePos(), distance)) return true;

        // Check if blocks directly above both player and target are clear (high-angle path)
        for (int i = 0; i <= 10; i++) {
            int y_p = (int) playerPos.y + i;
            int y_t = (int) targetPos.y + i;
            if (!WorldHelper.isAir(new BlockPos((int) playerPos.x, y_p, (int) playerPos.z))
                    || !WorldHelper.isAir(new BlockPos((int) targetPos.x, y_t, (int) targetPos.z))) {
                return false;
            }
        }

        // Parabolic arc check
        double heightAtApex = Math.min(playerPos.y + 20, 319);
        int checkPoints = 10;
        for (int i = 1; i <= checkPoints; i++) {
            double progress = (double) i / checkPoints;
            double x = playerPos.x + (targetPos.x - playerPos.x) * progress;
            double z = playerPos.z + (targetPos.z - playerPos.z) * progress;
            double y = playerPos.y + (heightAtApex - playerPos.y) * Math.sin(Math.PI * progress);
            if (!WorldHelper.isAir(new BlockPos((int) x, (int) y, (int) z))) return false;
        }
        return true;
    }

    public static boolean canUseRanged(AltoClef mod, Entity target) {
        return readyForRanged(mod) && checkRangedAttackTrajectory(mod, target);
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getBehaviour().resetCameraRotationModifer();
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished() {
        return shot || failed;
    }

    public boolean isFailed() {
        return failed;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ShootArrowSimpleProjectileTask;
    }

    @Override
    protected String toDebugString() {
        if (_rapidFireMode) {
            return "Rapid firing at " + target.getType().getName().getString() + " using " + _rangedItem.getName().getString();
        } else if (_highAng) {
            return "Shooting at " + target.getType().getName().getString() + " using " + _rangedItem.getName().getString() + " (artillery arc)";
        } else {
            return "Shooting at " + target.getType().getName().getString() + " using " + _rangedItem.getName().getString();
        }
    }
}

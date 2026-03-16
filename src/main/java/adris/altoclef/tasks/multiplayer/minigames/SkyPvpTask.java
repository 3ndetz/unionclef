package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillPlayerTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * SkyPvP task for MineLegacy server.
 * <p>
 * Spawn at 827.5 45 -791.5, safe radius 17 blocks around spawn.
 * Bot does not attack inside spawn zone.
 * If bot has no armor — avoids armored players (unless no other choice).
 * Otherwise — attacks the closest valid player.
 */
public class SkyPvpTask extends Task {

    private static final Vec3d SPAWN = new Vec3d(827.5, 45, -791.5);
    private static final double SPAWN_SAFE_RADIUS = 17.0;
    private static final double TARGET_RANGE = 30.0;
    private static final double CAUTIOUS_RANGE = 15.0; // avoid armored players within this range when naked

    private Task _currentKillTask;
    private Task _armorTask;
    private Task _pickupTask;
    private String _lastTargetName;

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
        Debug.logMessage("[SkyPvP] Started. Spawn safe zone: 17 blocks around spawn.");
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null || mod.getPlayer().isDead()) return null;

        Vec3d playerPos = mod.getPlayer().getPos();
        boolean weAreInSpawn = playerPos.distanceTo(SPAWN) < SPAWN_SAFE_RADIUS;
        boolean weHaveArmor = hasAnyArmor(mod);

        // Equip best armor first (if not in combat)
        if (_currentKillTask == null || !_currentKillTask.isActive()) {
            Task armorEquip = tryEquipArmor(mod);
            if (armorEquip != null) {
                _armorTask = armorEquip;
                return _armorTask;
            }
        }
        if (shouldForce(_armorTask)) return _armorTask;

        // Pick up nearby drops (swords, armor, gapples, pearls)
        Task pickup = tryPickupLoot(mod);
        if (pickup != null && (_currentKillTask == null || !_currentKillTask.isActive())) {
            _pickupTask = pickup;
            return _pickupTask;
        }

        // Find target (can target enemies outside spawn even if we're in spawn)
        Optional<Entity> target = findTarget(mod, weHaveArmor);

        if (target.isPresent()) {
            PlayerEntity enemy = (PlayerEntity) target.get();
            String name = enemy.getName().getString();

            // If target changed or task finished, create new kill task
            if (_currentKillTask == null || !_currentKillTask.isActive()
                    || _currentKillTask.isFinished() || !name.equals(_lastTargetName)) {
                _lastTargetName = name;
                _currentKillTask = new KillPlayerTask(name);
                Debug.logMessage("[SkyPvP] Target: " + name);
            }
            setDebugState("Attacking: " + name);
            return _currentKillTask;
        }

        // No target — idle
        setDebugState(weAreInSpawn ? "In spawn zone, waiting..." : "Searching for targets...");
        _lastTargetName = null;
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().pop();
        Debug.logMessage("[SkyPvP] Stopped.");
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SkyPvpTask;
    }

    @Override
    protected String toDebugString() {
        return "SkyPvP (MineLegacy)";
    }

    // ── target selection ─────────────────────────────────────────────────────

    private Optional<Entity> findTarget(AltoClef mod, boolean weHaveArmor) {
        Optional<Entity> bestUnarmored = Optional.empty();
        Optional<Entity> bestArmored = Optional.empty();
        double bestUnarmoredDist = Double.MAX_VALUE;
        double bestArmoredDist = Double.MAX_VALUE;

        for (PlayerEntity player : mod.getWorld().getPlayers()) {
            if (!isValidEnemy(mod, player)) continue;

            Vec3d enemyPos = player.getPos();
            double dist = mod.getPlayer().getPos().distanceTo(enemyPos);
            if (dist > TARGET_RANGE) continue;

            // Don't attack players inside spawn zone (they're protected)
            if (enemyPos.distanceTo(SPAWN) < SPAWN_SAFE_RADIUS) continue;

            boolean enemyArmored = hasAnyArmorEntity(player);

            if (enemyArmored) {
                if (dist < bestArmoredDist) {
                    bestArmoredDist = dist;
                    bestArmored = Optional.of(player);
                }
            } else {
                if (dist < bestUnarmoredDist) {
                    bestUnarmoredDist = dist;
                    bestUnarmored = Optional.of(player);
                }
            }
        }

        // If we have no armor — prefer unarmored targets, avoid armored unless no choice
        if (!weHaveArmor) {
            if (bestUnarmored.isPresent()) return bestUnarmored;
            // Only attack armored if they're close (aggressive self-defense)
            if (bestArmored.isPresent() && bestArmoredDist < CAUTIOUS_RANGE) return bestArmored;
            return Optional.empty();
        }

        // If we have armor — attack closest regardless
        if (bestUnarmoredDist <= bestArmoredDist) {
            return bestUnarmored.isPresent() ? bestUnarmored : bestArmored;
        }
        return bestArmored.isPresent() ? bestArmored : bestUnarmored;
    }

    private boolean isValidEnemy(AltoClef mod, PlayerEntity player) {
        if (player == null || player == mod.getPlayer()) return false;
        if (player.isDead() || !player.isAlive()) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        if (player.isInvisible()) return false;
        return !mod.getButler().isUserAuthorized(player.getName().getString());
    }

    // ── armor checks ─────────────────────────────────────────────────────────

    private boolean hasAnyArmor(AltoClef mod) {
        for (var stack : mod.getPlayer().getArmorItems()) {
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

    private boolean hasAnyArmorEntity(PlayerEntity player) {
        for (var stack : player.getArmorItems()) {
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

    private Task tryEquipArmor(AltoClef mod) {
        Item[][] armorSets = {
                ItemHelper.HelmetsTopPriority,
                ItemHelper.ChestplatesTopPriority,
                ItemHelper.LeggingsTopPriority,
                ItemHelper.BootsTopPriority
        };
        for (Item[] set : armorSets) {
            int need = isArmorNeededToEquip(mod, set);
            if (need != -1) {
                return new EquipArmorTask(Arrays.stream(set).toList().get(need));
            }
        }
        return null;
    }

    private int isArmorNeededToEquip(AltoClef mod, Item[] armorsTopPriority) {
        int equippedLevel = -1;
        int bestAvailable = 7;
        int idx = 0;
        for (Item armorItem : armorsTopPriority) {
            if (StorageHelper.isArmorEquipped(armorItem)) {
                equippedLevel = idx;
            }
            if (mod.getItemStorage().hasItem(armorItem) && idx < bestAvailable) {
                bestAvailable = idx;
            }
            idx++;
        }
        if (equippedLevel == -1) equippedLevel = 7;
        return bestAvailable < equippedLevel ? bestAvailable : -1;
    }

    // ── loot pickup ──────────────────────────────────────────────────────────

    private static final List<Item> LOOT_ITEMS = List.of(
            Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD,
            Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.DIAMOND_HELMET, Items.IRON_HELMET,
            Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS,
            Items.DIAMOND_BOOTS, Items.IRON_BOOTS,
            Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ENDER_PEARL, Items.BOW, Items.ARROW, Items.SPECTRAL_ARROW
    );

    private Task tryPickupLoot(AltoClef mod) {
        for (Item item : LOOT_ITEMS) {
            if (mod.getEntityTracker().itemDropped(item)) {
                Optional<ItemEntity> drop = mod.getEntityTracker().getClosestItemDrop(
                        ent -> mod.getPlayer().getPos().isInRange(ent.getEyePos(), 10), item);
                if (drop.isPresent()) {
                    return new PickupDroppedItemTask(new ItemTarget(item, 1), true);
                }
            }
        }
        return null;
    }

    private static boolean shouldForce(Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }
}

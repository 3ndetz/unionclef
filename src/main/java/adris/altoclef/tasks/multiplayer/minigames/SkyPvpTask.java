package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.TungstenPunkTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.multiplayer.SignShopTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.trackers.storage.ContainerType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final double SPAWN_SAFE_RADIUS = 18.0;

    /** Second no-PvP zone (shop area). XZ-only check, full Y column. */
    private static final Vec3d SAFE_ZONE_2 = new Vec3d(854, 44, -807);
    private static final double SAFE_ZONE_2_RADIUS = 18.0;

    /** SignShop item IDs for free equipment. */
    private static final String SIGN_ITEM_SWORD = "268";
    private static final String SIGN_ITEM_APPLE = "260";

    /** Free leather armor sign IDs → expected MC item (for "already have it" check). */
    private static final Map<String, Item> FREE_LEATHER_ARMOR = new LinkedHashMap<>(Map.of(
            "298", Items.LEATHER_HELMET,
            "299", Items.LEATHER_CHESTPLATE,
            "300", Items.LEATHER_LEGGINGS,
            "301", Items.LEATHER_BOOTS,
            "302", Items.LEATHER_BOOTS  // 302 = leather boots on some sign NPCs
    ));

    private Task _currentKillTask;
    private Task _armorTask;
    private Task _pickupTask;
    private Task _equipmentTask;
    private Task _wanderTask;
    private String _lastTargetName;

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
        // Exclude players in safe zones from attack/forcefield
        mod.getBehaviour().addAttackExclusion(entity -> isInSafeZone(entity.getPos()));
        mod.getBehaviour().addForceFieldExclusion(entity -> isInSafeZone(entity.getPos()));
        Debug.logMessage("[SkyPvP] Started.");
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null || mod.getPlayer().isDead()) return null;

        // ── Handle open mode selection menu: click "SkyPvP" slot ─────────
        if (ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
            Text title = MinecraftClient.getInstance().currentScreen != null
                    ? MinecraftClient.getInstance().currentScreen.getTitle() : null;
            if (title != null) {
                String t = title.getString().toLowerCase();
                if (t.contains("выбери режим") || t.contains("выбор режима")) {
                    Slot slot = ItemHelper.getCustomItemSlot(mod, "SkyPvP", "skypvp", "Sky PvP");
                    if (slot != null) {
                        mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                        setDebugState("Lobby: clicking SkyPvP...");
                    } else {
                        setDebugState("Lobby: menu open, SkyPvP slot not found");
                    }
                    return null;
                }
            }
        }

        // ── Lobby detection: compass "Выбор режима" means we're in hub ──────
        if (isInLobby(mod)) {
            // Click compass to open mode selection menu
            if (ItemHelper.clickCustomItem(mod, "Выбор режима", "выбор режима")) {
                setDebugState("Lobby: opening mode menu...");
            } else {
                setDebugState("In lobby, waiting...");
            }
            return null;
        }

        // ── Get equipment from SignShop if missing ───────────────────────────
        if (shouldForce(_equipmentTask)) return _equipmentTask;

        if (!hasSword(mod)) {
            Task swordTask = tryGetFromSignShop(mod, SIGN_ITEM_SWORD);
            if (swordTask != null) {
                _equipmentTask = swordTask;
                setDebugState("Getting sword from SignShop");
                return _equipmentTask;
            }
        }

        if (!hasApples(mod)) {
            Task appleTask = tryGetFromSignShop(mod, SIGN_ITEM_APPLE);
            if (appleTask != null) {
                _equipmentTask = appleTask;
                setDebugState("Getting apples from SignShop");
                return _equipmentTask;
            }
        }

        // ── Equip best armor (if not in combat) ─────────────────────────────
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
        Optional<Entity> target = findTarget(mod);

        if (target.isPresent()) {
            PlayerEntity enemy = (PlayerEntity) target.get();
            String name = enemy.getName().getString();

            // If target changed or task finished, create new kill task
            if (_currentKillTask == null || !_currentKillTask.isActive()
                    || _currentKillTask.isFinished() || !name.equals(_lastTargetName)) {
                _lastTargetName = name;
                _currentKillTask = new TungstenPunkTask(name);
            }
            setDebugState("Attacking: " + name);
            return _currentKillTask;
        }

        // Nobody around — explore and opportunistically collect free armor from signs
        _lastTargetName = null;

        Task freeArmor = tryGetFreeArmorFromSign(mod);
        if (freeArmor != null) {
            _equipmentTask = freeArmor;
            setDebugState("Grabbing free armor from sign");
            return _equipmentTask;
        }

        setDebugState("Exploring...");
        if (_wanderTask == null || _wanderTask.isFinished()) {
            _wanderTask = new TimeoutWanderTask(true);
        }
        return _wanderTask;
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

    // ── equipment from SignShop ────────────────────────────────────────────────

    private boolean hasSword(AltoClef mod) {
        return mod.getItemStorage().hasItem(
                Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
                Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
                Items.DIAMOND_AXE, Items.NETHERITE_AXE
        );
    }

    private boolean hasApples(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.APPLE, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);
    }

    private Task tryGetFromSignShop(AltoClef mod, String signItemId) {
        Optional<BlockPos> signPos = SignShopTask.findNearestFreeSign(mod, signItemId);
        if (signPos.isPresent()) {
            return new SignShopTask(signPos.get());
        }
        return null;
    }

    // ── lobby detection ────────────────────────────────────────────────────────

    /** If the player has a compass named "Выбор режима" in inventory, we're in the hub lobby. */
    private boolean isInLobby(AltoClef mod) {
        return ItemHelper.getCustomItemSlot(mod, "Выбор режима", "выбор режима") != null;
    }

    // ── safe zones ────────────────────────────────────────────────────────────

    private static boolean isInSafeZone(Vec3d pos) {
        return distXZ(pos, SPAWN) < SPAWN_SAFE_RADIUS
                || distXZ(pos, SAFE_ZONE_2) < SAFE_ZONE_2_RADIUS;
    }

    private static double distXZ(Vec3d a, Vec3d center) {
        double dx = a.x - center.x;
        double dz = a.z - center.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ── target selection ─────────────────────────────────────────────────────

    private Optional<Entity> findTarget(AltoClef mod) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity player : mod.getWorld().getPlayers()) {
            if (player == mod.getPlayer()) continue;
            if (!isValidEnemy(mod, player)) continue;

            Vec3d enemyPos = player.getPos();

            // Don't target players inside safe zones (XZ circle only)
            if (isInSafeZone(enemyPos)) continue;

            double dist = mod.getPlayer().getPos().distanceTo(enemyPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }

        // No spam logging for "no target" — happens every tick
        return Optional.ofNullable(best);
    }

    private boolean isValidEnemy(AltoClef mod, PlayerEntity player) {
        if (player == null || player == mod.getPlayer()) return false;
        if (player.isDead() || !player.isAlive()) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        // No butler check — in PvP everyone is an enemy
        return true;
    }

    // ── armor checks ─────────────────────────────────────────────────────────

    private boolean hasAnyArmor(AltoClef mod) {
        //#if MC >= 12111
        //$$ for (var stack : java.util.List.of(mod.getPlayer().getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD), mod.getPlayer().getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST), mod.getPlayer().getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS), mod.getPlayer().getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET))) {
        //#else
        for (var stack : mod.getPlayer().getArmorItems()) {
        //#endif
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

    private boolean hasAnyArmorEntity(PlayerEntity player) {
        //#if MC >= 12111
        //$$ for (var stack : java.util.List.of(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET))) {
        //#else
        for (var stack : player.getArmorItems()) {
        //#endif
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

    /** Finds a visible free sign for any leather armor piece we're missing. */
    private Task tryGetFreeArmorFromSign(AltoClef mod) {
        for (Map.Entry<String, Item> entry : FREE_LEATHER_ARMOR.entrySet()) {
            if (StorageHelper.isArmorEquipped(entry.getValue())
                    || mod.getItemStorage().hasItem(entry.getValue())) continue;
            Optional<BlockPos> sign = SignShopTask.findNearestFreeSign(mod, entry.getKey());
            if (sign.isPresent()) {
                return new SignShopTask(sign.get());
            }
        }
        return null;
    }

    private static boolean shouldForce(Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }
}

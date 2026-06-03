package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.entity.TungstenPunkTask;
import adris.altoclef.tasks.entity.ShiftEntityTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.screen.slot.SlotActionType;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class SkyWarsTask extends Task {

    private final Predicate<PlayerEntity> _canTerminate;
    private final ScanChunksInRadius _scanTask;
    private Vec3d _closestPlayerLastPos;
    private Vec3d _closestPlayerLastObservePos;
    private boolean _forceWait = false;
    boolean _thePitTask = false;
    private BlockPos _startedPos;
    private boolean _finishOnKilled = false;

    private static final int SEARCH_RADIUS = 10;
    private static final int TARGET_RANGE = 20;
    private static final int LOOT_RANGE = 10;
    private static final double COMBAT_RANGE = 3.0;

    private Task _armorTask;
    private int searchRadius = SEARCH_RADIUS;
    private int targetRange = TARGET_RANGE;
    private int lootRange = LOOT_RANGE;
    private double combatRange = COMBAT_RANGE;
    private boolean _started = false;
    private Task _lootTask;
    private Task _structureMaterialsTask;
    private TimerGame _buildBlocksCollectTimer = new TimerGame(3);
    private final TimerGame _inventoryCleanupTimer = new TimerGame(30);
    // State machine for staggered junk throwing
    private int _cleanIndex = -1;
    private final List<Integer> _junkSlots = new ArrayList<>();
    private final TimerGame _cleanThrowTimer = new TimerGame(0);
    private Block[] buildableBlocks = {Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT, Blocks.GRASS_BLOCK};
    private List<Block> handBuildableBlocks = new ArrayList<>();

    private List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        lootable.addAll(armorAndToolsNeeded(mod));
        lootable.addAll(Arrays.stream(ItemHelper.PLANKS).toList());
        lootable.addAll(Arrays.stream(ItemHelper.blocksToItems(buildableBlocks)).toList());
        lootable.addAll(Arrays.stream(ItemHelper.SwordsTopPriority).toList());
        lootable.addAll(Arrays.stream(ItemHelper.AxesTopPriority).toList());
        lootable.addAll(Arrays.stream(ItemHelper.ShootWeapons).toList());
        lootable.addAll(Arrays.stream(ItemHelper.ARROWS).toList());
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.COBBLESTONE);
        lootable.add(Items.STONE);
        lootable.add(Items.DIRT);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.GOLDEN_CARROT);
        lootable.add(Items.GUNPOWDER);
        lootable.add(Items.ENDER_PEARL);
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            lootable.add(Items.WATER_BUCKET);
        }
        return lootable;
    }

    private static final Block[] TO_SCAN = Stream.concat(
            Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}),
            Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))).toArray(Block[]::new);

    public SkyWarsTask(BlockPos center, double scanRadius, Predicate<PlayerEntity> canTerminate, boolean finishOnKilled, boolean thePitTask) {
        _thePitTask = thePitTask;
        _canTerminate = canTerminate;
        _finishOnKilled = finishOnKilled;
        _startedPos = center;
        handBuildableBlocks.addAll(Arrays.stream(new Block[]{Blocks.DIRT, Blocks.GRASS_BLOCK}).toList());
        handBuildableBlocks.addAll(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.WOOL)).toList());
        handBuildableBlocks.addAll(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.WOOD)).toList());
        handBuildableBlocks.addAll(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.PLANKS)).toList());
        _structureMaterialsTask = new GetBuildingMaterialsTask(32);
        _scanTask = new ScanChunksInRadius(center, scanRadius);
    }

    public SkyWarsTask(BlockPos center, double scanRadius, Predicate<PlayerEntity> canTerminate, boolean finishOnKilled) {
        this(center, scanRadius, canTerminate, finishOnKilled, false);
    }

    public SkyWarsTask(BlockPos center, boolean thePitTask, boolean finishOnKilled) {
        this(center, 100, accept -> true, finishOnKilled, thePitTask);
    }

    public SkyWarsTask(BlockPos center, double scanRadius, boolean finishOnKilled) {
        this(center, scanRadius, accept -> true, finishOnKilled);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
        if (_thePitTask) {
            mod.getBehaviour().avoidBlockBreaking(this::avoidBlockBreak);
            mod.getBehaviour().avoidBlockPlacing(this::avoidBlockBreak);
        }
    }

    private boolean avoidBlockBreak(BlockPos pos) {
        return true;
    }

    private BlockPos _lastLootPos;

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getFoodChain().isTryingToEat()) return null;

        if (ButlerConfig.getInstance().autoJoin) {
            if (ItemHelper.clickCustomItem(mod, "новая игра", "начать игру", "быстро играть (пкм)")) {
                setDebugState("Проиграли, начинаем новую игру");
                return null;
            }
        }

        if (_thePitTask) {
            setDebugState("ThePit");
            if (mod.getPlayer().getPos().getY() > 85) {
                setDebugState("МЫ НА СПАВНЕ! НАДО ВЫБРАТЬСЯ");
                mod.getInputControls().tryPress(Input.JUMP);
                mod.getInputControls().tryPress(Input.MOVE_FORWARD);
                if (WorldHelper.isBlock(new BlockPos(-17, 96, 19), Blocks.GLASS)) {
                    return new GetToBlockTask(new BlockPos(0, 96, 0));
                } else {
                    return new GetToBlockTask(new BlockPos(20, 96, 16));
                }
            }
        }

        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Eat first.");
            return null;
        }

        // Periodic inventory cleanup — throw out junk not in lootable whitelist
        if (_inventoryCleanupTimer.elapsed()) {
            _inventoryCleanupTimer.reset();
            startInventoryCleanup(mod);
        }
        if (_cleanIndex >= 0 && _cleanThrowTimer.elapsed()) {
            mod.getSlotHandler().clickSlot(new PlayerSlot(_junkSlots.get(_cleanIndex)), 1, SlotActionType.THROW);
            _cleanIndex++;
            if (_cleanIndex >= _junkSlots.size()) {
                _cleanIndex = -1;
                _junkSlots.clear();
            } else {
                _cleanThrowTimer.setInterval(0.5 + Math.random() * 2.0);
                _cleanThrowTimer.reset();
            }
        }

        if (shouldForce(_armorTask)) {
            return _armorTask;
        }

        if (_lootTask != null && _lootTask instanceof LootContainerTask) {
            if (shouldForce(_lootTask)) {
                return _lootTask;
            }
        }

        _armorTask = autoArmor(mod);
        if (_armorTask != null) {
            return _armorTask;
        }

        Optional<Entity> target = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> shouldPunk(mod, (PlayerEntity) toPunk),
                PlayerEntity.class);

        Vec3d pos = mod.getPlayer().getPos();
        float minCost = Float.POSITIVE_INFINITY;

        Optional<BlockPos> closestCont = mod.getBlockScanner().getNearestBlock(
                blockPos -> WorldHelper.isUnopenedChest(blockPos)
                        && mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 50)
                        && WorldHelper.canReach(blockPos),
                Blocks.CHEST);

        Optional<ItemEntity> closestDrop = mod.getEntityTracker().getClosestItemDrop(
                pos,
                entity -> shouldPickupDrop(mod, entity),
                toItemTargets(lootableItems(mod).toArray(new Item[0])));

        boolean nonReachable = getCurrentCalculatedHeuristic(mod) == Double.POSITIVE_INFINITY;

        float costContainer = Float.POSITIVE_INFINITY;
        float costTarget = Float.POSITIVE_INFINITY;
        float costDrop = Float.POSITIVE_INFINITY;

        if (closestCont.isPresent()) {
            costContainer = getPathCost(mod, pos, closestCont.get());
        }
        if (target.isPresent()) {
            costTarget = getPathCost(mod, pos, target.get().getPos());
        }
        if (closestDrop.isPresent()) {
            costDrop = getPathCost(mod, pos, closestDrop.get().getPos());
        }

        if (costContainer < minCost) minCost = costContainer;
        if (costTarget < minCost) minCost = costTarget;
        if (costDrop < minCost) minCost = costDrop;

        // Handle combat
        if (target.isPresent()) {
            PlayerEntity player = (PlayerEntity) target.get();
            boolean alert = mod.getPlayer().distanceTo(player) <= 10;
            if (alert) {
                setDebugState("Уничтожить срочно");
                return swKillPlayerTask(player);
            }
            if (LookHelper.cleanLineOfSight(player.getPos(), 100)) {
                if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) > 2) {
                    setDebugState("Кинуть пёрл");
                    return new ThrowEnderPearlSimpleProjectileTask(player.getBlockPos().add(0, -1, 0));
                }
            }
            if (canUseRangedWeapon(mod) && ShootArrowSimpleProjectileTask.canUseRanged(mod, player)) {
                setDebugState("Наказать дальним оружием");
                return new ShootArrowSimpleProjectileTask(player);
            }
        }

        if (minCost == Float.POSITIVE_INFINITY || minCost > 150
                || (nonReachable && !_structureMaterialsTask.isActive())) {
            int buildCount = mod.getItemStorage().getItemCount(ItemHelper.blocksToItems(buildableBlocks));
            if (buildCount < 32 && _structureMaterialsTask != null) {
                setDebugState("Добыча ресурсов...");
                return _structureMaterialsTask;
            }
        }

        _buildBlocksCollectTimer.reset();

        if (minCost != Float.POSITIVE_INFINITY) {
            if (minCost == costTarget && target.isPresent()
                    && target.get() instanceof PlayerEntity player) {
                setDebugState("Уничтожить");
                return swKillPlayerTask(player);
            } else if (minCost == costDrop) {
                return new PickupDroppedItemTask(
                        toItemTargets(lootableItems(mod).toArray(new Item[0])), true);
            } else if (minCost == costContainer) {
                setDebugState("Поиск ресурсов -> контейнеры: дорога");
                _lastLootPos = closestCont.get();
                boolean startLoot = WorldHelper.canReach(closestCont.get());
                if (!startLoot) {
                    _lootTask = new GetCloseToBlockTask(closestCont.get().up());
                } else {
                    _lootTask = new LootContainerTask(closestCont.get(), lootableItems(mod));
                }
                return _lootTask;
            }
        }

        return null;
    }

    public Task swKillPlayerTask(PlayerEntity player) {
        if (player.isInvulnerable() || player.isInCreativeMode() || player.isSneaking()) {
            return new ShiftEntityTask(player, ShiftEntityTask.ShiftType.Forward);
        } else {
            return new TungstenPunkTask(player.getName().getString());
        }
    }

    private double getCurrentCalculatedHeuristic(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            Optional<Double> ticksRemainingOp = mod.getClientBaritone().getPathingBehavior().ticksRemainingInSegment();
            return ticksRemainingOp.orElse(Double.POSITIVE_INFINITY);
        }
        return Double.NEGATIVE_INFINITY;
    }

    public static ItemTarget[] toItemTargets(Item... items) {
        return Arrays.stream(items).map(item -> new ItemTarget(item, 1)).toArray(ItemTarget[]::new);
    }

    public static ItemTarget[] toItemTargets(Item item, int count) {
        return new ItemTarget[]{new ItemTarget(item, count)};
    }

    public float getPathCost(AltoClef mod, Vec3d startPos, Vec3d goalPos) {
        return (float) BaritoneHelper.calculateGenericHeuristic(startPos, goalPos);
    }

    public float getPathCost(AltoClef mod, Vec3d startPos, BlockPos goalPos) {
        return getPathCost(mod, WorldHelper.toVec3d(goalPos), startPos);
    }

    private boolean canUseRangedWeapon(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.BOW)
                && (mod.getItemStorage().hasItem(Items.ARROW)
                || mod.getItemStorage().hasItem(Items.SPECTRAL_ARROW));
    }

    private Task autoArmor(AltoClef mod) {
        int armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.HelmetsTopPriority);
        if (armorEquipNeed != -1) {
            return new EquipArmorTask(
                    Arrays.stream(ItemHelper.HelmetsTopPriority).toList().get(armorEquipNeed));
        }
        armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.ChestplatesTopPriority);
        if (armorEquipNeed != -1) {
            return new EquipArmorTask(
                    Arrays.stream(ItemHelper.ChestplatesTopPriority).toList().get(armorEquipNeed));
        }
        armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.LeggingsTopPriority);
        if (armorEquipNeed != -1) {
            return new EquipArmorTask(
                    Arrays.stream(ItemHelper.LeggingsTopPriority).toList().get(armorEquipNeed));
        }
        armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.BootsTopPriority);
        if (armorEquipNeed != -1) {
            return new EquipArmorTask(
                    Arrays.stream(ItemHelper.BootsTopPriority).toList().get(armorEquipNeed));
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SkyWarsTask;
    }

    @Override
    protected String toDebugString() {
        return "Активна игра в SkyWars";
    }

    private List<Item> armorAndToolsNeeded(AltoClef mod) {
        List<Item> needed = new ArrayList<>();
        needed.addAll(itemsNeeded(mod, ItemHelper.HelmetsTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.ChestplatesTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.LeggingsTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.BootsTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.SwordsTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.AxesTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.PickaxesTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.ShovelsTopPriority));
        needed.addAll(itemsNeeded(mod, ItemHelper.HoesTopPriority));
        return needed;
    }

    private List<Item> itemsNeeded(AltoClef mod, Item[] priorityArr) {
        List<Item> neededItems = new ArrayList<>();
        int level = getHighestItemLevel(mod, priorityArr);
        int idx = 0;
        for (Item ignored : priorityArr) {
            if (idx < level) {
                neededItems.add(Arrays.stream(priorityArr).toList().get(idx));
            }
            idx++;
        }
        return neededItems;
    }

    private int getHighestItemLevel(AltoClef mod, Item[] priorityArr) {
        int idx = 0;
        int level = 7;
        for (Item i : priorityArr) {
            if (StorageHelper.isArmorEquipped(i) || mod.getItemStorage().hasItem(i)) {
                if (level > idx) level = idx;
            }
            idx++;
        }
        return level;
    }

    private int isArmorNeededToEquip(AltoClef mod, Item[] armorPriority) {
        int equippedLevel = -1;
        for (int i = 0; i < armorPriority.length; i++) {
            if (StorageHelper.isArmorEquipped(armorPriority[i])) {
                equippedLevel = i;
                break;
            }
        }
        int bestAvailable = -1;
        for (int i = 0; i < armorPriority.length; i++) {
            if (mod.getItemStorage().hasItem(armorPriority[i])) {
                bestAvailable = i;
                break;
            }
        }
        return (bestAvailable != -1 && (equippedLevel == -1 || bestAvailable < equippedLevel))
                ? bestAvailable : -1;
    }

    private boolean shouldPunk(AltoClef mod, PlayerEntity player) {
        return player != null
                && player.isAlive()
                && !player.isCreative()
                && !player.isSpectator()
                && !mod.getButler().isUserAuthorized(player.getName().getString());
    }

    // Groups of items where we only keep the best one; extras are junk
    private static final Item[][] DEDUPE_CATEGORIES = {
            ItemHelper.SwordsTopPriority,
            ItemHelper.AxesTopPriority,
            ItemHelper.PickaxesTopPriority,
            ItemHelper.ShovelsTopPriority,
            ItemHelper.HoesTopPriority,
            ItemHelper.HelmetsTopPriority,
            ItemHelper.ChestplatesTopPriority,
            ItemHelper.LeggingsTopPriority,
            ItemHelper.BootsTopPriority,
            ItemHelper.ShootWeapons, // bows + crossbows
    };

    // Base combat value for scoring: higher = hits harder / protects better.
    // For weapons: Java Edition base attack damage.
    // For armour: armour protection points (helmet/chestplate/leggings/boots).
    // For bows / crossbows: fully-charged unenchanted damage.
    // Only items that appear in DEDUPE_CATEGORIES arrays are included.
    private static final Map<Item, Float> COMBAT_VALUES = new HashMap<>();
    static {
        // --- Swords (base attack damage) ---
        COMBAT_VALUES.put(Items.NETHERITE_SWORD, 8f);
        COMBAT_VALUES.put(Items.DIAMOND_SWORD,    7f);
        COMBAT_VALUES.put(Items.IRON_SWORD,       6f);
        COMBAT_VALUES.put(Items.STONE_SWORD,      5f);
        COMBAT_VALUES.put(Items.GOLDEN_SWORD,     4f);
        COMBAT_VALUES.put(Items.WOODEN_SWORD,     4f);

        // --- Axes (base attack damage) ---
        COMBAT_VALUES.put(Items.NETHERITE_AXE, 10f);
        COMBAT_VALUES.put(Items.DIAMOND_AXE,    6f);
        COMBAT_VALUES.put(Items.IRON_AXE,       5f);
        COMBAT_VALUES.put(Items.STONE_AXE,      4f);
        COMBAT_VALUES.put(Items.GOLDEN_AXE,     3f);
        COMBAT_VALUES.put(Items.WOODEN_AXE,     3f);

        // --- Pickaxes (base attack damage) ---
        COMBAT_VALUES.put(Items.NETHERITE_PICKAXE, 6f);
        COMBAT_VALUES.put(Items.DIAMOND_PICKAXE,   5f);
        COMBAT_VALUES.put(Items.IRON_PICKAXE,      4f);
        COMBAT_VALUES.put(Items.STONE_PICKAXE,     3f);
        COMBAT_VALUES.put(Items.GOLDEN_PICKAXE,    2f);
        COMBAT_VALUES.put(Items.WOODEN_PICKAXE,    2f);

        // --- Shovels (base attack damage) ---
        COMBAT_VALUES.put(Items.NETHERITE_SHOVEL, 6.5f);
        COMBAT_VALUES.put(Items.DIAMOND_SHOVEL,   5.5f);
        COMBAT_VALUES.put(Items.IRON_SHOVEL,      4.5f);
        COMBAT_VALUES.put(Items.STONE_SHOVEL,     3.5f);
        COMBAT_VALUES.put(Items.GOLDEN_SHOVEL,    2.5f);
        COMBAT_VALUES.put(Items.WOODEN_SHOVEL,    2.5f);

        // --- Hoes (base attack damage — all 1 in Java Ed.) ---
        COMBAT_VALUES.put(Items.NETHERITE_HOE, 1f);
        COMBAT_VALUES.put(Items.DIAMOND_HOE,   1f);
        COMBAT_VALUES.put(Items.IRON_HOE,      1f);
        COMBAT_VALUES.put(Items.STONE_HOE,     1f);
        COMBAT_VALUES.put(Items.GOLDEN_HOE,    1f);
        COMBAT_VALUES.put(Items.WOODEN_HOE,    1f);

        // --- Helmet armour protection ---
        COMBAT_VALUES.put(Items.NETHERITE_HELMET,  3f);
        COMBAT_VALUES.put(Items.DIAMOND_HELMET,    3f);
        COMBAT_VALUES.put(Items.IRON_HELMET,       2f);
        COMBAT_VALUES.put(Items.CHAINMAIL_HELMET,  2f);
        COMBAT_VALUES.put(Items.GOLDEN_HELMET,     2f);
        COMBAT_VALUES.put(Items.LEATHER_HELMET,    1f);

        // --- Chestplate armour protection ---
        COMBAT_VALUES.put(Items.NETHERITE_CHESTPLATE,  8f);
        COMBAT_VALUES.put(Items.DIAMOND_CHESTPLATE,    8f);
        COMBAT_VALUES.put(Items.IRON_CHESTPLATE,       6f);
        COMBAT_VALUES.put(Items.CHAINMAIL_CHESTPLATE,  5f);
        COMBAT_VALUES.put(Items.GOLDEN_CHESTPLATE,     5f);
        COMBAT_VALUES.put(Items.LEATHER_CHESTPLATE,    3f);

        // --- Leggings armour protection ---
        COMBAT_VALUES.put(Items.NETHERITE_LEGGINGS,  6f);
        COMBAT_VALUES.put(Items.DIAMOND_LEGGINGS,    6f);
        COMBAT_VALUES.put(Items.IRON_LEGGINGS,       5f);
        COMBAT_VALUES.put(Items.CHAINMAIL_LEGGINGS,  4f);
        COMBAT_VALUES.put(Items.GOLDEN_LEGGINGS,     3f);
        COMBAT_VALUES.put(Items.LEATHER_LEGGINGS,    2f);

        // --- Boots armour protection ---
        COMBAT_VALUES.put(Items.NETHERITE_BOOTS,  3f);
        COMBAT_VALUES.put(Items.DIAMOND_BOOTS,    3f);
        COMBAT_VALUES.put(Items.IRON_BOOTS,       2f);
        COMBAT_VALUES.put(Items.CHAINMAIL_BOOTS,  1f);
        COMBAT_VALUES.put(Items.GOLDEN_BOOTS,     1f);
        COMBAT_VALUES.put(Items.LEATHER_BOOTS,    1f);

        // --- Bows / crossbows (fully charged unenchanted) ---
        COMBAT_VALUES.put(Items.BOW,      9f);
        COMBAT_VALUES.put(Items.CROSSBOW, 9f);
    }

    // Fraction of max durability below which the item is junked regardless of damage/enchants.
    private static final float MIN_DURABILITY_FRACTION = 0.15f;

    private void startInventoryCleanup(AltoClef mod) {
        List<Item> keepItems = lootableItems(mod);
        _junkSlots.clear();

        // Phase 1: For each equipment category, keep best 1, junk the rest.
        //   Sub-15%-durability items are junked unconditionally before scoring.
        for (Item[] category : DEDUPE_CATEGORIES) {
            Set<Item> catSet = new HashSet<>(Arrays.asList(category));
            List<SlotScore> candidates = new ArrayList<>();

            for (int i = 0; i < 36; i++) {
                int windowSlot = i < 9 ? i + 36 : i;
                ItemStack stack = StorageHelper.getItemStackInSlot(new PlayerSlot(windowSlot));
                if (stack.isEmpty()) continue;
                if (!catSet.contains(stack.getItem())) continue;

                int maxDur = stack.getMaxDamage();
                if (maxDur > 0 && stack.getDamage() > maxDur * (1f - MIN_DURABILITY_FRACTION)) {
                    // Worn-out tool: junk it
                    _junkSlots.add(windowSlot);
                    continue;
                }
                candidates.add(new SlotScore(windowSlot, scoreStack(stack)));
            }

            if (candidates.size() <= 1) continue;

            // Sort descending by score, best first
            candidates.sort((a, b) -> Integer.compare(b.score, a.score));
            // Everything after the best is junk
            for (int i = 1; i < candidates.size(); i++) {
                _junkSlots.add(candidates.get(i).windowSlot);
            }
        }

        // Phase 2: Also junk items not in the whitelist at all
        for (int i = 0; i < 36; i++) {
            int windowSlot = i < 9 ? i + 36 : i;
            // Already marked as duplicate or worn-out junk
            if (_junkSlots.contains(windowSlot)) continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(new PlayerSlot(windowSlot));
            if (stack.isEmpty()) continue;
            if (keepItems.contains(stack.getItem())) continue;
            _junkSlots.add(windowSlot);
        }

        if (_junkSlots.isEmpty()) {
            _cleanIndex = -1;
        } else {
            _cleanIndex = 0;
            _cleanThrowTimer.setInterval(0.5 + Math.random() * 2.0);
            _cleanThrowTimer.reset();
        }
    }

    // Score higher = better keep.
    // Enchanted >> base damage >> durability.
    private static int scoreStack(ItemStack stack) {
        float baseDamage = COMBAT_VALUES.getOrDefault(stack.getItem(), 0f);
        int score = (int) (baseDamage * 100f);
        if (stack.hasEnchantments()) {
            score += 2000;
        }
        int durability = stack.getMaxDamage() - stack.getDamage();
        score += durability;
        return score;
    }

    // Only pick up equipment drops if they're better than what we already have.
    // Non-equipment items (blocks, food, pearls, etc.) are always picked up.
    private boolean shouldPickupDrop(AltoClef mod, ItemEntity entity) {
        ItemStack stack = entity.getStack();
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();

        // Not a weapon/tool/armor — always pick up (food, blocks, pearls, etc.)
        if (!COMBAT_VALUES.containsKey(item)) return true;

        // Find which equipment category this item belongs to
        Item[] category = null;
        for (Item[] cat : DEDUPE_CATEGORIES) {
            for (Item catItem : cat) {
                if (catItem.equals(item)) {
                    category = cat;
                    break;
                }
            }
            if (category != null) break;
        }
        if (category == null) return true;

        int dropScore = scoreStack(stack);
        Set<Item> catSet = new HashSet<>(Arrays.asList(category));

        // Find the best score we already have for this category
        int bestInventoryScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            int windowSlot = i < 9 ? i + 36 : i;
            ItemStack invStack = StorageHelper.getItemStackInSlot(new PlayerSlot(windowSlot));
            if (invStack.isEmpty()) continue;
            if (!catSet.contains(invStack.getItem())) continue;
            int invScore = scoreStack(invStack);
            if (invScore > bestInventoryScore) {
                bestInventoryScore = invScore;
            }
        }

        // Only pick up if we have nothing in this category, or it's strictly better
        return bestInventoryScore == Integer.MIN_VALUE || dropScore > bestInventoryScore;
    }

    private static class SlotScore {
        final int windowSlot;
        final int score;

        SlotScore(int windowSlot, int score) {
            this.windowSlot = windowSlot;
            this.score = score;
        }
    }

    private static boolean shouldForce(Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }

    private static void sleepSec(double seconds) {
        try {
            Thread.sleep((int) (1000 * seconds));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private class ScanChunksInRadius extends SearchChunksExploreTask {

        private final BlockPos _center;
        private final double _radius;

        public ScanChunksInRadius(BlockPos center, double radius) {
            _center = center;
            _radius = radius;
        }

        @Override
        protected boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos) {
            double cx = (pos.getStartX() + pos.getEndX()) / 2.0;
            double cz = (pos.getStartZ() + pos.getEndZ()) / 2.0;
            double dx = _center.getX() - cx;
            double dz = _center.getZ() - cz;
            return dx * dx + dz * dz < _radius * _radius;
        }

        @Override
        protected ChunkPos getBestChunkOverride(AltoClef mod, List<ChunkPos> chunks) {
            if (_closestPlayerLastPos != null) {
                double lowestScore = Double.POSITIVE_INFINITY;
                ChunkPos bestChunk = null;
                for (ChunkPos toSearch : chunks) {
                    double cx = (toSearch.getStartX() + toSearch.getEndX() + 1) / 2.0;
                    double cz = (toSearch.getStartZ() + toSearch.getEndZ() + 1) / 2.0;
                    double px = mod.getPlayer().getX();
                    double pz = mod.getPlayer().getZ();
                    double distanceSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz);
                    double pdx = _closestPlayerLastPos.getX() - cx;
                    double pdz = _closestPlayerLastPos.getZ() - cz;
                    double distanceToLastPlayerPos = pdx * pdx + pdz * pdz;
                    Vec3d direction = _closestPlayerLastPos
                            .subtract(_closestPlayerLastObservePos).multiply(1, 0, 1).normalize();
                    double dirx = direction.x, dirz = direction.z;
                    double correctDistance = pdx * dirx + pdz * dirz;
                    double tempX = dirx * correctDistance;
                    double tempZ = dirz * correctDistance;
                    double perpendicularDistance = ((pdx - tempX) * (pdx - tempX)) + ((pdz - tempZ) * (pdz - tempZ));
                    double score = distanceSq + distanceToLastPlayerPos * 0.6
                            - correctDistance * 2 + perpendicularDistance * 0.5;
                    if (score < lowestScore) {
                        lowestScore = score;
                        bestChunk = toSearch;
                    }
                }
                return bestChunk;
            }
            return super.getBestChunkOverride(mod, chunks);
        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof ScanChunksInRadius scan) {
                return scan._center.equals(_center) && Math.abs(scan._radius - _radius) <= 1;
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Сканирование территории...";
        }
    }
}

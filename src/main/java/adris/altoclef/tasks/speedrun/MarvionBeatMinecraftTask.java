package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.*;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.LootDesertTempleTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.*;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Predicate;

import static net.minecraft.client.MinecraftClient.getInstance;

@SuppressWarnings("ALL")
public class MarvionBeatMinecraftTask extends Task {
    private static final Block[] TRACK_BLOCKS = new Block[]{
            Blocks.BLAST_FURNACE,
            Blocks.FURNACE,
            Blocks.SMOKER,
            Blocks.END_PORTAL_FRAME,
            Blocks.END_PORTAL,
            Blocks.CRAFTING_TABLE, // For pearl trading + gold crafting
            Blocks.CHEST, // For ruined portals
            Blocks.SPAWNER, // For silverfish,
            Blocks.STONE_PRESSURE_PLATE // For desert temples
    };
    private static final Item[] COLLECT_EYE_ARMOR = new Item[]{
            Items.GOLDEN_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS,
            Items.DIAMOND_BOOTS
    };
    private static final ItemTarget[] COLLECT_STONE_GEAR = combine(
            toItemTargets(Items.STONE_SWORD, 1),
            toItemTargets(Items.STONE_PICKAXE, 2),
            toItemTargets(Items.STONE_HOE),
            toItemTargets(Items.COAL, 13)
    );
    private static final Item COLLECT_SHIELD = Items.SHIELD;
    private static final Item[] COLLECT_IRON_ARMOR = ItemHelper.IRON_ARMORS;
    private static final Item[] COLLECT_EYE_ARMOR_END = ItemHelper.DIAMOND_ARMORS;
    private static final ItemTarget[] COLLECT_IRON_GEAR = combine(
            toItemTargets(Items.IRON_SWORD, 2),
            toItemTargets(Items.STONE_SHOVEL),
            toItemTargets(Items.STONE_AXE),
            toItemTargets(Items.DIAMOND_PICKAXE)
    );
    private static final ItemTarget[] COLLECT_EYE_GEAR = combine(
            toItemTargets(Items.DIAMOND_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE, 3),
            toItemTargets(Items.BUCKET, 2),
            toItemTargets(Items.CRAFTING_TABLE)
    );
    private static final ItemTarget[] COLLECT_IRON_GEAR_MIN = combine(
            toItemTargets(Items.IRON_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE)
    );
    private static final ItemTarget[] COLLECT_EYE_GEAR_MIN = combine(
            toItemTargets(Items.DIAMOND_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE)
    );
    private static final ItemTarget[] IRON_GEAR = combine(
            toItemTargets(Items.IRON_SWORD, 2),
            toItemTargets(Items.IRON_PICKAXE, 2),
            toItemTargets(Items.STONE_SHOVEL),
            toItemTargets(Items.STONE_AXE),
            toItemTargets(Items.SHIELD)
    );
    private static final ItemTarget[] IRON_GEAR_MIN = combine(
            toItemTargets(Items.IRON_SWORD),
            toItemTargets(Items.SHIELD)
    );
    private static final int END_PORTAL_FRAME_COUNT = 12;
    private static final double END_PORTAL_BED_SPAWN_RANGE = 8;

    private static final int TWISTING_VINES_COUNT = 28;
    private static final int TWISTING_VINES_COUNT_MIN = 14;
    // We don't want curse of binding
    private static final Predicate<ItemStack> _noCurseOfBinding = stack -> {
        if (stack.getEnchantments().getEnchantments().contains(EnchantmentTags.CURSE)) {
            return false;
        }
        return true;
    };
    private static BeatMinecraftConfig _config;
    private static GoToStrongholdPortalTask _locateStrongholdTask;
    private static boolean openingEndPortal = false;

    static {
        ConfigHelper.loadConfig("configs/beat_minecraft.json", BeatMinecraftConfig::new, BeatMinecraftConfig.class, newConfig -> _config = newConfig);
    }

    private final HashMap<Item, Integer> _cachedEndItemDrops = new HashMap<>();
    // For some reason, after death there's a frame where the game thinks there are NO items in the end.
    private final TimerGame _cachedEndItemNothingWaitTime = new TimerGame(10);
    private final Task _buildMaterialsTask;
    private final PlaceBedAndSetSpawnTask _setBedSpawnTask = new PlaceBedAndSetSpawnTask();
    private final Task _goToNetherTask = new DefaultGoToDimensionTask(Dimension.NETHER); // To keep the portal build cache.
    private final Task _getOneBedTask = TaskCatalogue.getItemTask("bed", 1);
    private final Task _sleepThroughNightTask = new SleepThroughNightTask();
    private final Task _killDragonBedStratsTask = new KillEnderDragonWithBedsTask();
    // End specific dragon breath avoidance
    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private final TimerGame _timer1 = new TimerGame(5);
    private final TimerGame _timer2 = new TimerGame(35);
    private final TimerGame _timer3 = new TimerGame(60);
    boolean _weHaveEyes;
    private static boolean dragonIsDead;
    private BlockPos _endPortalCenterLocation;
    private boolean isEquippingDiamondArmor;
    private boolean _ranStrongholdLocator;
    private boolean _endPortalOpened;
    private BlockPos _bedSpawnLocation;
    private List<BlockPos> _notRuinedPortalChests = new ArrayList<>();
    private int _cachedFilledPortalFrames = 0;
    // Controls whether we CAN walk on the end portal.
    private static boolean enteringEndPortal;
    private Task _foodTask;
    private Task _gearTask;
    private Task _lootTask;
    private boolean _collectingEyes;
    private boolean _escapingDragonsBreath = false;
    private boolean isGettingBlazeRods = false;
    private boolean isGettingEnderPearls = false;
    private Task searchBiomeTask;
    private Task _getPorkchopTask;
    private Task _stoneGearTask;
    private Task _logsTask;
    private Task _starterGearTask;
    private Task _ironGearTask;
    private Task _shieldTask;
    private Task _smeltTask;
    private Task getBedTask;
    private Task getTwistingVines;

    public MarvionBeatMinecraftTask() {
        _locateStrongholdTask = new GoToStrongholdPortalTask(_config.targetEyes);
        _buildMaterialsTask = new GetBuildingMaterialsTask(_config.buildMaterialCount);
    }

    /**
     * Retrieves the BeatMinecraftConfig instance, creating a new one if it doesn't exist.
     * @return the BeatMinecraftConfig instance
     */
    public static BeatMinecraftConfig getConfig() {
        if (_config == null) {
            _config = new BeatMinecraftConfig();
        }
        return _config;
    }

    /**
     * Retrieves the frame blocks surrounding the end portal center.
     * @param endPortalCenter the center position of the end portal
     * @return the list of frame blocks
     */
    private static List<BlockPos> getFrameBlocks(BlockPos endPortalCenter) {
        List<BlockPos> frameBlocks = new ArrayList<>();

        if (endPortalCenter != null) {
            int[][] frameOffsets = {
                    {2, 0, 1},
                    {2, 0, 0},
                    {2, 0, -1},
                    {-2, 0, 1},
                    {-2, 0, 0},
                    {-2, 0, -1},
                    {1, 0, 2},
                    {0, 0, 2},
                    {-1, 0, 2},
                    {1, 0, -2},
                    {0, 0, -2},
                    {-1, 0, -2}
            };

            for (int[] offset : frameOffsets) {
                BlockPos frameBlock = endPortalCenter.add(offset[0], offset[1], offset[2]);
                frameBlocks.add(frameBlock);
            }
        }

        return frameBlocks;
    }

    private static ItemTarget[] toItemTargets(Item... items) {
        ItemTarget[] itemTargets = new ItemTarget[items.length];
        for (int i = 0; i < items.length; i++) {
            itemTargets[i] = new ItemTarget(items[i]);
        }
        return itemTargets;
    }

    private static ItemTarget[] toItemTargets(Item item, int count) {
        ItemTarget[] itemTargets = {new ItemTarget(item, count)};
        return itemTargets;
    }

    private static ItemTarget[] combine(ItemTarget[]... targets) {
        List<ItemTarget> combinedTargets = new ArrayList<>();

        for (ItemTarget[] targetArray : targets) {
            if (targetArray != null) {
                combinedTargets.addAll(Arrays.asList(targetArray));
            }
        }

        ItemTarget[] combinedArray = combinedTargets.toArray(new ItemTarget[0]);
        return combinedArray;
    }

    private static boolean isEndPortalFrameFilled(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return false;
        }

        BlockState blockState = mod.getWorld().getBlockState(pos);

        if (blockState.getBlock() != Blocks.END_PORTAL_FRAME) {
            return false;
        }

        return blockState.get(EndPortalFrameBlock.EYE);
    }

    private static boolean shouldForce(AltoClef mod, Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }

    @Override
    public boolean isFinished() {
        if (getInstance().currentScreen instanceof net.minecraft.client.gui.screen.CreditsScreen) {
            return true;
        }

        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && dragonIsDead) {
            return true;
        }
        return false;
    }

    private boolean needsBuildingMaterials(AltoClef mod) {
        int materialCount = StorageHelper.getBuildingMaterialCount();
        boolean shouldForce = shouldForce(mod, _buildMaterialsTask);
        return materialCount < _config.minBuildMaterialCount || shouldForce;
    }

    private void updateCachedEndItems(AltoClef mod) {
        List<ItemEntity> droppedItems = mod.getEntityTracker().getDroppedItems();

        _cachedEndItemNothingWaitTime.reset();
        _cachedEndItemDrops.clear();

        for (ItemEntity entity : droppedItems) {
            if (entity.getStack().isEmpty()) {
                continue;
            }
            Item item = entity.getStack().getItem();
            int count = entity.getStack().getCount();

            _cachedEndItemDrops.put(item, _cachedEndItemDrops.getOrDefault(item, 0) + count);
        }
    }

    private int getEndCachedCount(Item item) {
        if (_cachedEndItemDrops.containsKey(item)) {
            return _cachedEndItemDrops.get(item);
        } else {
            return 0;
        }
    }

    private boolean droppedInEnd(Item item) {
        Integer cachedCount = getEndCachedCount(item);
        return cachedCount != null && cachedCount > 0;
    }

    private boolean hasItemOrDroppedInEnd(AltoClef mod, Item item) {
        if (mod == null || item == null) {
            throw new IllegalArgumentException("mod and item must not be null");
        }

        boolean hasItem = mod.getItemStorage().hasItem(item);
        boolean droppedInEnd = droppedInEnd(item);
        return hasItem || droppedInEnd;
    }

    private List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();

        lootable.addAll(Arrays.asList(
                Items.GOLDEN_APPLE,
                Items.ENCHANTED_GOLDEN_APPLE,
                Items.GLISTERING_MELON_SLICE,
                Items.GOLDEN_CARROT,
                Items.OBSIDIAN
        ));

        boolean isGoldenHelmetEquipped = StorageHelper.isArmorEquipped(Items.GOLDEN_HELMET);
        boolean hasGoldenHelmet = mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_HELMET);
        boolean hasEnoughGoldIngots = mod.getItemStorage().getItemCountInventoryOnly(Items.GOLD_INGOT) >= 5;

        if (!isGoldenHelmetEquipped && !hasGoldenHelmet) {
            lootable.add(Items.GOLDEN_HELMET);
        }

        if ((hasEnoughGoldIngots && !isGoldenHelmetEquipped && !hasGoldenHelmet) || _config.barterPearlsInsteadOfEndermanHunt) {
            lootable.add(Items.GOLD_INGOT);
        }

        if (!mod.getItemStorage().hasItemInventoryOnly(Items.FLINT_AND_STEEL)) {
            lootable.add(Items.FLINT_AND_STEEL);
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.FIRE_CHARGE)) {
            lootable.add(Items.FIRE_CHARGE);
        }

        if (!mod.getItemStorage().hasItemInventoryOnly(Items.BUCKET) && !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            lootable.add(Items.IRON_INGOT);
        }

        if (!StorageHelper.itemTargetsMetInventory(COLLECT_EYE_GEAR_MIN)) {
            lootable.add(Items.DIAMOND);
        }

        if (!mod.getItemStorage().hasItemInventoryOnly(Items.FLINT)) {
            lootable.add(Items.FLINT);
        }

        return lootable;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        // Disable walking on end portal
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);

        // Pop the top behaviour from the stack
        mod.getBehaviour().pop();

        // BlockScanner doesn't need stopTracking - omit
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other == null || !(other instanceof MarvionBeatMinecraftTask)) {
            return false;
        }
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Beating the game (Marvion version)";
    }

    private boolean endPortalFound(AltoClef mod, BlockPos endPortalCenter) {
        if (endPortalCenter == null) {
            return false;
        }
        if (endPortalOpened(mod, endPortalCenter)) {
            return true;
        }
        List<BlockPos> frameBlocks = getFrameBlocks(endPortalCenter);
        for (BlockPos frame : frameBlocks) {
            if (mod.getChunkTracker().isChunkLoaded(frame) &&
                mod.getWorld().getBlockState(frame).getBlock() == Blocks.END_PORTAL_FRAME) {
                return true;
            }
        }
        return false;
    }

    private boolean endPortalOpened(AltoClef mod, BlockPos endPortalCenter) {
        if (_endPortalOpened && endPortalCenter != null) {
            if (!mod.getChunkTracker().isChunkLoaded(endPortalCenter)) return false;
            return mod.getWorld().getBlockState(endPortalCenter).getBlock() == Blocks.END_PORTAL;
        }
        return false;
    }

    private boolean spawnSetNearPortal(AltoClef mod, BlockPos endPortalCenter) {
        if (_bedSpawnLocation == null) {
            return false;
        }

        try {
            if (!mod.getChunkTracker().isChunkLoaded(_bedSpawnLocation)) return false;
            Block block = mod.getWorld().getBlockState(_bedSpawnLocation).getBlock();
            for (Item bedItem : ItemHelper.BED) {
                Block bedBlock = ItemHelper.itemsToBlocks(new Item[]{bedItem})[0];
                if (block == bedBlock) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<BlockPos> locateClosestUnopenedRuinedPortalChest(AltoClef mod) {
        if (!WorldHelper.getCurrentDimension().equals(Dimension.OVERWORLD)) {
            return Optional.empty();
        }

        try {
            return mod.getBlockScanner().getNearestBlock(blockPos -> {
                boolean isNotRuinedPortalChest = !_notRuinedPortalChests.contains(blockPos);
                boolean isUnopenedChest = WorldHelper.isUnopenedChest(blockPos);
                boolean isWithinDistance = mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 150);
                boolean isLootablePortalChest = canBeLootablePortalChest(mod, blockPos);

                return isNotRuinedPortalChest && isUnopenedChest && isWithinDistance && isLootablePortalChest;
            }, Blocks.CHEST);
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        try {
            dragonIsDead = false;
            enteringEndPortal = false;
            resetTimers();
            pushBehaviour(mod);
            addThrowawayItemsWarning(mod);
            // BlockScanner doesn't need trackBlock - scanning is automatic
            addProtectedItems(mod);
            allowWalkingOnEndPortal(mod);
            avoidDragonBreath(mod);
            avoidBreakingBed(mod);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetTimers() {
        try {
            _timer1.reset();
            _timer2.reset();
            _timer3.reset();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void pushBehaviour(AltoClef mod) {
        if (mod.getBehaviour() != null) {
            mod.getBehaviour().push();
        }
    }

    private void addThrowawayItemsWarning(AltoClef mod) {
        String settingsWarningTail = "in \".minecraft/altoclef_settings.json\". @gamer may break if you don't add this! (sorry!)";

        if (!ArrayUtils.contains(mod.getModSettings().getThrowawayItems(mod), Items.END_STONE)) {
            Debug.logWarning("\"end_stone\" is not part of your \"throwawayItems\" list " + settingsWarningTail);
        }

        if (!mod.getModSettings().shouldThrowawayUnusedItems()) {
            Debug.logWarning("\"throwawayUnusedItems\" is not set to true " + settingsWarningTail);
        }
    }

    private void addProtectedItems(AltoClef mod) {
        mod.getBehaviour().addProtectedItems(
                Items.ENDER_EYE,
                Items.BLAZE_ROD,
                Items.ENDER_PEARL,
                Items.CRAFTING_TABLE,
                Items.IRON_INGOT,
                Items.WATER_BUCKET,
                Items.FLINT_AND_STEEL,
                Items.SHIELD,
                Items.SHEARS,
                Items.BUCKET,
                Items.GOLDEN_HELMET,
                Items.SMOKER,
                Items.FURNACE,
                Items.BLAST_FURNACE
        );

        mod.getBehaviour().addProtectedItems(ItemHelper.BED);
        mod.getBehaviour().addProtectedItems(ItemHelper.IRON_ARMORS);
        mod.getBehaviour().addProtectedItems(ItemHelper.LOG);
    }

    private void allowWalkingOnEndPortal(AltoClef mod) {
        mod.getBehaviour().allowWalkingOn(blockPos -> {
            if (enteringEndPortal && mod.getChunkTracker().isChunkLoaded(blockPos)) {
                BlockState blockState = mod.getWorld().getBlockState(blockPos);
                return blockState.getBlock() == Blocks.END_PORTAL;
            }
            return false;
        });
    }

    private void avoidDragonBreath(AltoClef mod) {
        mod.getBehaviour().avoidWalkingThrough(blockPos -> {
            Dimension currentDimension = WorldHelper.getCurrentDimension();
            boolean isEndDimension = currentDimension == Dimension.END;
            boolean isTouchingDragonBreath = _dragonBreathTracker.isTouchingDragonBreath(blockPos);

            return isEndDimension && !_escapingDragonsBreath && isTouchingDragonBreath;
        });
    }

    private void avoidBreakingBed(AltoClef mod) {
        if (_bedSpawnLocation != null) {
            BlockPos bedHead = WorldHelper.getBedHead(_bedSpawnLocation);
            BlockPos bedFoot = WorldHelper.getBedFoot(_bedSpawnLocation);

            mod.getBehaviour().avoidBlockBreaking(blockPos -> blockPos.equals(bedHead) || blockPos.equals(bedFoot));
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getPlayer().getMainHandStack().getItem() instanceof EnderEyeItem && !openingEndPortal
                && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            List<ItemStack> itemStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
            for (ItemStack itemStack : itemStacks) {
                Item item = itemStack.getItem();
                if (item instanceof SwordItem) {
                    mod.getSlotHandler().forceEquipItem(item);
                }
            }
        }
        boolean eyeGearSatisfied = StorageHelper.isArmorEquippedAll(COLLECT_EYE_ARMOR);
        boolean ironGearSatisfied = StorageHelper.isArmorEquippedAll(COLLECT_IRON_ARMOR);
        if (mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)) {
            if (mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.value != 0) {
                mod.getBehaviour().setBlockBreakAdditionalPenalty(0);
            }
        } else {
            if (mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.value != mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.defaultValue) {
                mod.getBehaviour().setBlockBreakAdditionalPenalty(mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.defaultValue);
            }
        }
        Predicate<Task> isCraftingTableTask = task -> {
            if (task instanceof CraftInTableTask || task instanceof PickupFromContainerTask) {
                return true;
            }
            return false;
        };
        Predicate<Task> isSmokerTask = task -> {
            if (task instanceof SmeltInSmokerTask || task instanceof PickupFromContainerTask) {
                return true;
            }
            return false;
        };
        Predicate<Task> isFurnaceTask = task -> {
            if (task instanceof SmeltInFurnaceTask || task instanceof PickupFromContainerTask || task instanceof CraftInTableTask) {
                return true;
            }
            return false;
        };
        Predicate<Task> isBlastFurnaceTask = task -> {
            if (task instanceof SmeltInBlastFurnaceTask || task instanceof PickupFromContainerTask) {
                return true;
            }
            return false;
        };
        // Crafting table blacklisting (BlockScanner replaces BlockTracker)
        {
            Optional<BlockPos> craftingTables = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
            if (craftingTables.isPresent() && mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                    && !thisOrChildSatisfies(isCraftingTableTask) && !mod.getBlockScanner().isUnreachable(craftingTables.get())) {
                Debug.logMessage("Blacklisting extra crafting table.");
                mod.getBlockScanner().requestBlockUnreachable(craftingTables.get(), 0);
                BlockState craftingTablePosUp = mod.getWorld().getBlockState(craftingTables.get().up(2));
                Optional<Entity> witch = mod.getEntityTracker().getClosestEntity(WitchEntity.class);
                if (witch.isPresent() && craftingTables.get().isWithinDistance(witch.get().getPos(), 15)) {
                    Debug.logMessage("Blacklisting witch crafting table.");
                    mod.getBlockScanner().requestBlockUnreachable(craftingTables.get(), 0);
                }
                if (craftingTablePosUp.getBlock() == Blocks.WHITE_WOOL) {
                    Debug.logMessage("Blacklisting pillage crafting table.");
                    mod.getBlockScanner().requestBlockUnreachable(craftingTables.get(), 0);
                }
            }
        }
        {
            Optional<BlockPos> smokers = mod.getBlockScanner().getNearestBlock(Blocks.SMOKER);
            if (smokers.isPresent() && mod.getItemStorage().hasItem(Items.SMOKER)
                    && !thisOrChildSatisfies(isSmokerTask) && !mod.getBlockScanner().isUnreachable(smokers.get())) {
                Debug.logMessage("Blacklisting extra smoker.");
                mod.getBlockScanner().requestBlockUnreachable(smokers.get(), 0);
            }
        }
        {
            Optional<BlockPos> furnaces = mod.getBlockScanner().getNearestBlock(Blocks.FURNACE);
            if (furnaces.isPresent() && (mod.getItemStorage().hasItem(Items.FURNACE) || mod.getItemStorage().hasItem(Items.BLAST_FURNACE))
                    && !thisOrChildSatisfies(isFurnaceTask) && !mod.getBlockScanner().isUnreachable(furnaces.get())) {
                Debug.logMessage("Blacklisting extra furnace.");
                mod.getBlockScanner().requestBlockUnreachable(furnaces.get(), 0);
            }
        }
        {
            Optional<BlockPos> blastFurnaces = mod.getBlockScanner().getNearestBlock(Blocks.BLAST_FURNACE);
            if (blastFurnaces.isPresent() && mod.getItemStorage().hasItem(Items.BLAST_FURNACE)
                    && !thisOrChildSatisfies(isBlastFurnaceTask) && !mod.getBlockScanner().isUnreachable(blastFurnaces.get())) {
                Debug.logMessage("Blacklisting extra blast furnace.");
                mod.getBlockScanner().requestBlockUnreachable(blastFurnaces.get(), 0);
            }
        }
        Block[] wools = ItemHelper.itemsToBlocks(ItemHelper.WOOL);
        for (Block wool : wools) {
            Optional<BlockPos> woolsPos = mod.getBlockScanner().getNearestBlock(wool);
            if (woolsPos.isPresent() && woolsPos.get().getY() < 62 && !mod.getBlockScanner().isUnreachable(woolsPos.get())) {
                Debug.logMessage("Blacklisting dangerous wool.");
                mod.getBlockScanner().requestBlockUnreachable(woolsPos.get(), 0);
            }
        }
        Block[] logBlocks = ItemHelper.itemsToBlocks(ItemHelper.LOG);
        for (Block logBlock : logBlocks) {
            Optional<BlockPos> logs = mod.getBlockScanner().getNearestBlock(logBlock);
            if (logs.isPresent()) {
                Iterable<Entity> entities = mod.getWorld().getEntities();
                for (Entity entity : entities) {
                    if (entity instanceof PillagerEntity && !mod.getBlockScanner().isUnreachable(logs.get())
                            && logs.get().isWithinDistance(entity.getPos(), 40)) {
                        Debug.logMessage("Blacklisting pillage log.");
                        mod.getBlockScanner().requestBlockUnreachable(logs.get(), 0);
                    }
                }
                if (logs.get().getY() < 62 && !mod.getBlockScanner().isUnreachable(logs.get()) && !ironGearSatisfied
                        && !eyeGearSatisfied) {
                    Debug.logMessage("Blacklisting dangerous log.");
                    mod.getBlockScanner().requestBlockUnreachable(logs.get(), 0);
                }
            }
        }
        if (_locateStrongholdTask.isActive()) {
            if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                    if (_timer1.elapsed()) {
                        if (_config.renderDistanceManipulation) {
                            getInstance().options.getViewDistance().setValue(12);
                        }
                        _timer1.reset();
                    }
                }
            }
        }
        if ((_logsTask != null || _foodTask != null || _getOneBedTask.isActive() || _stoneGearTask != null ||
                (_sleepThroughNightTask.isActive() && !mod.getItemStorage().hasItem(ItemHelper.BED))) &&
                getBedTask == null) {
            if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                if (_timer3.getDuration() >= 30) {
                    if (_config.renderDistanceManipulation) {
                        getInstance().options.getViewDistance().setValue(12);
                        getInstance().options.getEntityDistanceScaling().setValue(1.0);
                    }
                }
                if (_timer3.elapsed()) {
                    if (_config.renderDistanceManipulation) {
                        getInstance().options.getViewDistance().setValue(32);
                        getInstance().options.getEntityDistanceScaling().setValue(5.0);
                    }
                    _timer3.reset();
                }
            }
        }
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && _foodTask == null && !_getOneBedTask.isActive()
                && !_locateStrongholdTask.isActive() && _logsTask == null && _stoneGearTask == null &&
                _getPorkchopTask == null && searchBiomeTask == null && _config.renderDistanceManipulation &&
                !_ranStrongholdLocator && getBedTask == null && !_sleepThroughNightTask.isActive()) {
            if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                if (_timer1.elapsed()) {
                    if (_config.renderDistanceManipulation) {
                        getInstance().options.getViewDistance().setValue(2);
                        getInstance().options.getEntityDistanceScaling().setValue(0.5);
                    }
                    _timer1.reset();
                }
            }
        }
        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            if (!mod.getClientBaritone().getExploreProcess().isActive() && !_locateStrongholdTask.isActive() &&
                    _config.renderDistanceManipulation) {
                if (_timer1.elapsed()) {
                    if (_config.renderDistanceManipulation) {
                        getInstance().options.getViewDistance().setValue(12);
                        getInstance().options.getEntityDistanceScaling().setValue(1.0);
                    }
                    _timer1.reset();
                }
            }
        }
        List<Slot> torches = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.TORCH);
        if (!torches.isEmpty()) {
            for (Slot torch : torches) {
                if (Slot.isCursor(torch)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(torch, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> beds = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, ItemHelper.BED);
        if (!beds.isEmpty() && mod.getItemStorage().getItemCount(ItemHelper.BED) > getTargetBeds(mod) &&
                !endPortalFound(mod, _endPortalCenterLocation) && WorldHelper.getCurrentDimension() != Dimension.END) {
            for (Slot bed : beds) {
                if (Slot.isCursor(bed)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(bed, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> excessWaterBuckets = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.WATER_BUCKET);
        if (!excessWaterBuckets.isEmpty() && mod.getItemStorage().getItemCount(Items.WATER_BUCKET) > 1) {
            for (Slot excessWaterBucket : excessWaterBuckets) {
                if (Slot.isCursor(excessWaterBucket)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(excessWaterBucket, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> excessLighters = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.FLINT_AND_STEEL);
        if (!excessLighters.isEmpty() && mod.getItemStorage().getItemCount(Items.FLINT_AND_STEEL) > 1) {
            for (Slot excessLighter : excessLighters) {
                if (Slot.isCursor(excessLighter)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(excessLighter, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> sands = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.SAND);
        if (!sands.isEmpty()) {
            for (Slot sand : sands) {
                if (Slot.isCursor(sand)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(sand, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> gravels = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.GRAVEL);
        if (!gravels.isEmpty() && (mod.getItemStorage().hasItem(Items.FLINT) || mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL))) {
            for (Slot gravel : gravels) {
                if (Slot.isCursor(gravel)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(gravel, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> furnaceSlots = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.FURNACE);
        // shouldUseBlastFurnace not in altoclef Settings - check inventory instead
        if (!furnaceSlots.isEmpty() && mod.getItemStorage().hasItem(Items.SMOKER) &&
                mod.getItemStorage().hasItem(Items.BLAST_FURNACE)) {
            for (Slot furnaceSlot : furnaceSlots) {
                if (Slot.isCursor(furnaceSlot)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(furnaceSlot, 0, SlotActionType.PICKUP);
                }
            }
        }
        List<Slot> shears = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, Items.SHEARS);
        if (!shears.isEmpty() && !StorageHelper.isBigCraftingOpen() && !StorageHelper.isFurnaceOpen() &&
                !StorageHelper.isSmokerOpen() && !StorageHelper.isBlastFurnaceOpen() && !needsBeds(mod)) {
            for (Slot shear : shears) {
                if (Slot.isCursor(shear)) {
                    if (!mod.getControllerExtras().isBreakingBlock()) {
                        LookHelper.randomOrientation(mod);
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(shear, 0, SlotActionType.PICKUP);
                }
            }
        }

        // By default, don't walk over end portals.
        enteringEndPortal = false;

        // End stuff.
        if (WorldHelper.getCurrentDimension() == Dimension.END) {
            if (!mod.getWorld().isChunkLoaded(0, 0)) {
                setDebugState("Waiting for chunks to load");
                return null;
            }
            if (_config.renderDistanceManipulation) {
                getInstance().options.getViewDistance().setValue(12);
                getInstance().options.getEntityDistanceScaling().setValue(1.0);
            }
            // If we have bed, do bed strats, otherwise punk normally.
            updateCachedEndItems(mod);
            // Grab beds
            if (mod.getEntityTracker().itemDropped(ItemHelper.BED) && (needsBeds(mod) ||
                    WorldHelper.getCurrentDimension() == Dimension.END))
                return new PickupDroppedItemTask(new ItemTarget(ItemHelper.BED), true);
            // Grab tools
            if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE)) {
                if (mod.getEntityTracker().itemDropped(Items.IRON_PICKAXE))
                    return new PickupDroppedItemTask(Items.IRON_PICKAXE, 1);
                if (mod.getEntityTracker().itemDropped(Items.DIAMOND_PICKAXE))
                    return new PickupDroppedItemTask(Items.DIAMOND_PICKAXE, 1);
            }
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET) && mod.getEntityTracker().itemDropped(Items.WATER_BUCKET))
                return new PickupDroppedItemTask(Items.WATER_BUCKET, 1);
            // Grab armor
            for (Item armorCheck : COLLECT_EYE_ARMOR_END) {
                if (!StorageHelper.isArmorEquipped(armorCheck)) {
                    if (mod.getItemStorage().hasItem(armorCheck)) {
                        setDebugState("Equipping armor.");
                        return new EquipArmorTask(armorCheck);
                    }
                    if (mod.getEntityTracker().itemDropped(armorCheck)) {
                        return new PickupDroppedItemTask(armorCheck, 1);
                    }
                }
            }
            // Dragons breath avoidance
            _dragonBreathTracker.updateBreath(mod);
            for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer()) {
                if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                    setDebugState("ESCAPE dragons breath");
                    _escapingDragonsBreath = true;
                    return _dragonBreathTracker.getRunAwayTask();
                }
            }
            _escapingDragonsBreath = false;

            // If we find an ender portal, just GO to it!!!
            if (mod.getBlockScanner().anyFound(Blocks.END_PORTAL)) {
                setDebugState("WOOHOO");
                dragonIsDead = true;
                enteringEndPortal = true;
                if (!mod.getExtraBaritoneSettings().isCanWalkOnEndPortal()) {
                    mod.getExtraBaritoneSettings().canWalkOnEndPortal(true);
                }
                return new DoToClosestBlockTask(
                        blockPos -> new GetToBlockTask(blockPos.up()),
                        Blocks.END_PORTAL
                );
            }
            if (mod.getItemStorage().hasItem(ItemHelper.BED) ||
                    mod.getBlockScanner().anyFound(ItemHelper.itemsToBlocks(ItemHelper.BED))) {
                setDebugState("Bed strats");
                return _killDragonBedStratsTask;
            }
            setDebugState("No beds, regular strats.");
            return new KillEnderDragonTask();
        } else {
            // We're not in the end so reset our "end cache" timer
            _cachedEndItemNothingWaitTime.reset();
        }

        // Check for end portals. Always.
        if (!endPortalOpened(mod, _endPortalCenterLocation) && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            Optional<BlockPos> endPortal = mod.getBlockScanner().getNearestBlock(Blocks.END_PORTAL);
            if (endPortal.isPresent()) {
                _endPortalCenterLocation = endPortal.get();
                _endPortalOpened = true;
            } else {
                // TODO: Test that this works, for some reason the bot gets stuck near the stronghold and it keeps "Searching" for the portal
                _endPortalCenterLocation = doSimpleSearchForEndPortal(mod);
            }
        }
        // Portable crafting table pickup
        Block[] copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
        {
            Optional<BlockPos> nearestCraftingTable = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
            if (nearestCraftingTable.isPresent() && WorldHelper.canBreak(nearestCraftingTable.get())) {
                for (Block CopperBlock : copperBlocks) {
                    Block blockBelow = mod.getWorld().getBlockState(nearestCraftingTable.get().down()).getBlock();
                    if (blockBelow == CopperBlock) {
                        Debug.logMessage("Blacklisting crafting table in trial chambers.");
                        mod.getBlockScanner().requestBlockUnreachable(nearestCraftingTable.get(), 0);
                    }
                }
            }
        }
        boolean noEyesPlease = (endPortalOpened(mod, _endPortalCenterLocation) || WorldHelper.getCurrentDimension() == Dimension.END);
        int filledPortalFrames = getFilledPortalFrames(mod, _endPortalCenterLocation);
        int eyesNeededMin = noEyesPlease ? 0 : _config.minimumEyes - filledPortalFrames;
        int eyesNeeded = noEyesPlease ? 0 : _config.targetEyes - filledPortalFrames;
        int eyes = mod.getItemStorage().getItemCount(Items.ENDER_EYE);
        if (eyes < eyesNeededMin || (!_ranStrongholdLocator && _collectingEyes && eyes < eyesNeeded)) {
            // Blast furnace pickup
            {
                Optional<BlockPos> blastFurnacePos = mod.getBlockScanner().getNearestBlock(Blocks.BLAST_FURNACE);
                Optional<ItemEntity> blastFurnaceEntity = mod.getEntityTracker().getClosestItemDrop(Items.BLAST_FURNACE);
                if (blastFurnacePos.isPresent() && !_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END
                        && _config.rePickupCraftingTable && !mod.getItemStorage().hasItem(Items.BLAST_FURNACE)
                        && !thisOrChildSatisfies(isBlastFurnaceTask) && WorldHelper.canBreak(blastFurnacePos.get())
                        || (blastFurnaceEntity.isPresent()
                        && mod.getEntityTracker().itemDropped(blastFurnaceEntity.get().getStack().getItem())
                        && !thisOrChildSatisfies(isBlastFurnaceTask) && !mod.getItemStorage().hasItem(Items.BLAST_FURNACE))) {
                    setDebugState("Picking up the blast furnace while we are at it.");
                    Item blastFurnaceItem = blastFurnaceEntity.isPresent() ? blastFurnaceEntity.get().getStack().getItem() : Items.BLAST_FURNACE;
                    Block blastFurnaceBlock = blastFurnacePos.isPresent() ? mod.getWorld().getBlockState(blastFurnacePos.get()).getBlock() : Blocks.BLAST_FURNACE;
                    return new MineAndCollectTask(blastFurnaceItem, 1, new Block[]{blastFurnaceBlock}, MiningRequirement.WOOD);
                }
            }
            // Furnace pickup
            {
                Optional<BlockPos> furnacePos = mod.getBlockScanner().getNearestBlock(Blocks.FURNACE);
                Optional<ItemEntity> furnaceEntity = mod.getEntityTracker().getClosestItemDrop(Items.FURNACE);
                if (furnacePos.isPresent() && !_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END
                        && _config.rePickupCraftingTable && !mod.getItemStorage().hasItem(Items.FURNACE)
                        && !mod.getItemStorage().hasItem(Items.BLAST_FURNACE) && !thisOrChildSatisfies(isFurnaceTask)
                        && WorldHelper.canBreak(furnacePos.get())
                        || (furnaceEntity.isPresent() && mod.getEntityTracker().itemDropped(furnaceEntity.get().getStack().getItem())
                        && !thisOrChildSatisfies(isFurnaceTask) && !mod.getItemStorage().hasItem(Items.FURNACE)
                        && !mod.getItemStorage().hasItem(Items.BLAST_FURNACE))) {
                    setDebugState("Picking up the furnace while we are at it.");
                    Item furnaceItem = furnaceEntity.isPresent() ? furnaceEntity.get().getStack().getItem() : Items.FURNACE;
                    Block furnaceBlock = furnacePos.isPresent() ? mod.getWorld().getBlockState(furnacePos.get()).getBlock() : Blocks.FURNACE;
                    return new MineAndCollectTask(furnaceItem, 1, new Block[]{furnaceBlock}, MiningRequirement.WOOD);
                }
            }
            // Smoker pickup
            {
                Optional<BlockPos> smokerPos = mod.getBlockScanner().getNearestBlock(Blocks.SMOKER);
                Optional<ItemEntity> smokerEntity = mod.getEntityTracker().getClosestItemDrop(Items.SMOKER);
                if (smokerPos.isPresent() && !_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END
                        && _config.rePickupCraftingTable && !mod.getItemStorage().hasItem(Items.SMOKER)
                        && !thisOrChildSatisfies(isSmokerTask) && WorldHelper.canBreak(smokerPos.get())
                        || (smokerEntity.isPresent()
                        && mod.getEntityTracker().itemDropped(smokerEntity.get().getStack().getItem())
                        && !thisOrChildSatisfies(isSmokerTask) && !mod.getItemStorage().hasItem(Items.SMOKER))) {
                    setDebugState("Picking up the smoker while we are at it.");
                    Item smokerItem = smokerEntity.isPresent() ? smokerEntity.get().getStack().getItem() : Items.SMOKER;
                    Block smokerBlock = smokerPos.isPresent() ? mod.getWorld().getBlockState(smokerPos.get()).getBlock() : Blocks.SMOKER;
                    return new MineAndCollectTask(smokerItem, 1, new Block[]{smokerBlock}, MiningRequirement.WOOD);
                }
            }
            // Crafting table pickup
            {
                Optional<BlockPos> craftingTablePos = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
                Optional<ItemEntity> craftingTableEntity = mod.getEntityTracker().getClosestItemDrop(Items.CRAFTING_TABLE);
                if (craftingTablePos.isPresent() && !_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END
                        && _config.rePickupCraftingTable && !mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                        && !thisOrChildSatisfies(isCraftingTableTask) && WorldHelper.canBreak(craftingTablePos.get())
                        || (craftingTableEntity.isPresent()
                        && mod.getEntityTracker().itemDropped(craftingTableEntity.get().getStack().getItem())
                        && !thisOrChildSatisfies(isCraftingTableTask) && !mod.getItemStorage().hasItem(Items.CRAFTING_TABLE))) {
                    setDebugState("Picking up the crafting table while we are at it.");
                    Item craftingTableItem = craftingTableEntity.isPresent() ? craftingTableEntity.get().getStack().getItem() : Items.CRAFTING_TABLE;
                    Block craftingTableBlock = craftingTablePos.isPresent() ? mod.getWorld().getBlockState(craftingTablePos.get()).getBlock() : Blocks.CRAFTING_TABLE;
                    return new MineAndCollectTask(craftingTableItem, 1, new Block[]{craftingTableBlock}, MiningRequirement.HAND);
                }
            }
            if (!mod.getItemStorage().hasItem(Items.NETHERRACK) &&
                    WorldHelper.getCurrentDimension() == Dimension.NETHER && !isGettingBlazeRods &&
                    !isGettingEnderPearls) {
                setDebugState("Getting netherrack.");
                if (mod.getEntityTracker().itemDropped(Items.NETHERRACK)) {
                    return new PickupDroppedItemTask(Items.NETHERRACK, 1, true);
                }
                return TaskCatalogue.getItemTask(Items.NETHERRACK, 1);
            }
        }
        // Sleep through night.
        if (_config.sleepThroughNight && !_endPortalOpened && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            if (WorldHelper.canSleep()) {
                if (_config.renderDistanceManipulation && mod.getItemStorage().hasItem(ItemHelper.BED)) {
                    if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                        if (_timer1.elapsed()) {
                            getInstance().options.getViewDistance().setValue(2);
                            getInstance().options.getEntityDistanceScaling().setValue(0.5);
                            _timer1.reset();
                        }
                    }
                }
                if (_timer2.elapsed()) {
                    _timer2.reset();
                }
                if (_timer2.getDuration() >= 30 &&
                        !mod.getPlayer().isSleeping()) {
                    if (mod.getEntityTracker().itemDropped(ItemHelper.BED) && needsBeds(mod)) {
                        setDebugState("Resetting sleep through night task.");
                        return new PickupDroppedItemTask(new ItemTarget(ItemHelper.BED), true);
                    }
                    if (anyBedsFound(mod)) {
                        setDebugState("Resetting sleep through night task.");
                        return new DoToClosestBlockTask(DestroyBlockTask::new, ItemHelper.itemsToBlocks(ItemHelper.BED));
                    }
                }
                setDebugState("Sleeping through night");
                return _sleepThroughNightTask;
            }
            if (shouldForce(mod, _getOneBedTask)) {
                setDebugState("Getting one bed to sleep in at night.");
                return _getOneBedTask;
            }
            if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
                Block[] bedBlocks = ItemHelper.itemsToBlocks(ItemHelper.BED);
                for (Block bedBlock : bedBlocks) {
                    Optional<BlockPos> nearestBed = mod.getBlockScanner().getNearestBlock(bedBlock);
                    if (nearestBed.isPresent() && WorldHelper.canBreak(nearestBed.get())) {
                        boolean isValid = true;
                        for (Block CopperBlock : copperBlocks) {
                            Block blockBelow = mod.getWorld().getBlockState(nearestBed.get().down()).getBlock();
                            if (blockBelow == CopperBlock) {
                                isValid = false;
                                break;
                            }
                        }
                        if (isValid) {
                            return _getOneBedTask;
                        }
                    }
                }
            }
        }
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            if (needsBeds(mod) && anyBedsFound(mod)) {
                setDebugState("A bed was found, getting it.");
                if (_config.renderDistanceManipulation) {
                    if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                        if (_timer1.elapsed()) {
                            getInstance().options.getViewDistance().setValue(2);
                            getInstance().options.getEntityDistanceScaling().setValue(0.5);
                            _timer1.reset();
                        }
                    }
                }
                getBedTask = getBedTask(mod);
                return getBedTask;
            }
            getBedTask = null;
        }

        // Do we need more eyes?
        if (eyes < eyesNeededMin || (!_ranStrongholdLocator && _collectingEyes && eyes < eyesNeeded)) {
            _collectingEyes = true;
            _weHaveEyes = false;
            return getEyesOfEnderTask(mod, eyesNeeded);
        } else {
            _weHaveEyes = true;
            _collectingEyes = false;
        }

        // We have eyes. Locate our portal + enter.
        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                if (mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE) && !StorageHelper.isBigCraftingOpen()
                        && !StorageHelper.isFurnaceOpen() && !StorageHelper.isSmokerOpen()
                        && !StorageHelper.isBlastFurnaceOpen() && (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)
                        || mod.getItemStorage().hasItem(Items.FIRE_CHARGE))) {
                    Item[] throwGearItems = {Items.STONE_SWORD, Items.STONE_PICKAXE, Items.IRON_SWORD, Items.IRON_PICKAXE};
                    List<Slot> throwGearSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, throwGearItems);
                    if (!throwGearSlot.isEmpty()) {
                        for (Slot slot : throwGearSlot) {
                            if (Slot.isCursor(slot)) {
                                if (!mod.getControllerExtras().isBreakingBlock()) {
                                    LookHelper.randomOrientation(mod);
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                            } else {
                                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                            }
                        }
                    }
                    List<Slot> ironArmorSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, COLLECT_IRON_ARMOR);
                    if (!ironArmorSlot.isEmpty()) {
                        for (Slot slot : ironArmorSlot) {
                            if (Slot.isCursor(slot)) {
                                if (!mod.getControllerExtras().isBreakingBlock()) {
                                    LookHelper.randomOrientation(mod);
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                            } else {
                                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                            }
                        }
                    }
                }
                // If we found our end portal...
                if (endPortalFound(mod, _endPortalCenterLocation)) {
                    // Destroy silverfish spawner
                    if (StorageHelper.miningRequirementMetInventory(MiningRequirement.WOOD)) {
                        Optional<BlockPos> silverfish = mod.getBlockScanner().getNearestBlock(blockPos -> {
                            return WorldHelper.getSpawnerEntity(blockPos) instanceof SilverfishEntity;
                        }, Blocks.SPAWNER);
                        if (silverfish.isPresent()) {
                            setDebugState("Breaking silverfish spawner.");
                            return new DestroyBlockTask(silverfish.get());
                        }
                    }
                    if (endPortalOpened(mod, _endPortalCenterLocation)) {
                        openingEndPortal = false;
                        if (needsBuildingMaterials(mod)) {
                            setDebugState("Collecting building materials.");
                            return _buildMaterialsTask;
                        }
                        if (_config.placeSpawnNearEndPortal && mod.getItemStorage().hasItem(ItemHelper.BED)) {
                            if (!spawnSetNearPortal(mod, _endPortalCenterLocation)) {
                                setDebugState("Setting spawn near end portal");
                                return setSpawnNearPortalTask(mod);
                            }
                        }
                        // We're as ready as we'll ever be, hop into the portal!
                        setDebugState("Entering End");
                        enteringEndPortal = true;
                        if (!mod.getExtraBaritoneSettings().isCanWalkOnEndPortal()) {
                            mod.getExtraBaritoneSettings().canWalkOnEndPortal(true);
                        }
                        return new DoToClosestBlockTask(
                                blockPos -> new GetToBlockTask(blockPos.up()),
                                Blocks.END_PORTAL
                        );
                    } else {
                        // Open the portal! (we have enough eyes, do it)
                        setDebugState("Opening End Portal");
                        openingEndPortal = true;
                        return new DoToClosestBlockTask(
                                blockPos -> new InteractWithBlockTask(Items.ENDER_EYE, blockPos),
                                blockPos -> !isEndPortalFrameFilled(mod, blockPos),
                                Blocks.END_PORTAL_FRAME
                        );
                    }
                } else {
                    _ranStrongholdLocator = true;
                    // Get beds before starting our portal location.
                    if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && needsBeds(mod)) {
                        setDebugState("Getting beds before stronghold search.");
                        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                            if (_timer1.elapsed()) {
                                if (_config.renderDistanceManipulation) {
                                    getInstance().options.getViewDistance().setValue(32);
                                    getInstance().options.getEntityDistanceScaling().setValue(5.0);
                                }
                                _timer1.reset();
                            }
                        }
                        getBedTask = getBedTask(mod);
                        return getBedTask;
                    }
                    getBedTask = null;
                    if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                        setDebugState("Getting water bucket.");
                        return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
                    }
                    if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) {
                        setDebugState("Getting flint and steel.");
                        return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
                    }
                    if (needsBuildingMaterials(mod)) {
                        setDebugState("Collecting building materials.");
                        return _buildMaterialsTask;
                    }
                    // Portal Location
                    setDebugState("Locating End Portal...");
                    return _locateStrongholdTask;
                }
            }
            case NETHER -> {
                if (!StorageHelper.isBigCraftingOpen() && !StorageHelper.isFurnaceOpen() && !StorageHelper.isSmokerOpen()
                        && !StorageHelper.isBlastFurnaceOpen() && (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)
                        || mod.getItemStorage().hasItem(Items.FIRE_CHARGE))) {
                    Item[] throwGearItems = {Items.STONE_SWORD, Items.STONE_PICKAXE, Items.IRON_SWORD, Items.IRON_PICKAXE};
                    List<Slot> throwGearSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, throwGearItems);
                    if (!throwGearSlot.isEmpty()) {
                        for (Slot slot : throwGearSlot) {
                            if (Slot.isCursor(slot)) {
                                if (!mod.getControllerExtras().isBreakingBlock()) {
                                    LookHelper.randomOrientation(mod);
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                            } else {
                                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                            }
                        }
                    }
                    List<Slot> ironArmorSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, COLLECT_IRON_ARMOR);
                    if (!ironArmorSlot.isEmpty()) {
                        for (Slot slot : ironArmorSlot) {
                            if (Slot.isCursor(slot)) {
                                if (!mod.getControllerExtras().isBreakingBlock()) {
                                    LookHelper.randomOrientation(mod);
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                            } else {
                                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                            }
                        }
                    }
                }
                // Portal Location
                setDebugState("Locating End Portal...");
                return _locateStrongholdTask;
            }
        }
        return null;
    }

    private Task setSpawnNearPortalTask(AltoClef mod) {
        if (_setBedSpawnTask.isSpawnSet()) {
            _bedSpawnLocation = _setBedSpawnTask.getBedSleptPos();
        } else {
            _bedSpawnLocation = null;
        }

        if (shouldForce(mod, _setBedSpawnTask)) {
            setDebugState("Setting spawnpoint now.");
            return _setBedSpawnTask;
        }

        if (WorldHelper.inRangeXZ(mod.getPlayer(), WorldHelper.toVec3d(_endPortalCenterLocation), END_PORTAL_BED_SPAWN_RANGE)) {
            return _setBedSpawnTask;
        } else {
            setDebugState("Approaching portal (to set spawnpoint)");
            return new GetToXZTask(_endPortalCenterLocation.getX(), _endPortalCenterLocation.getZ());
        }
    }

    private Task getBlazeRodsTask(AltoClef mod, int count) {
        if (mod.getEntityTracker().itemDropped(Items.BLAZE_ROD)) {
            return new PickupDroppedItemTask(Items.BLAZE_ROD, 1);
        } else if (mod.getEntityTracker().itemDropped(Items.BLAZE_POWDER)) {
            return new PickupDroppedItemTask(Items.BLAZE_POWDER, 1);
        } else {
            return new CollectBlazeRodsTask(count);
        }
    }

    private Task getEnderPearlTask(AltoClef mod, int count) {
        isGettingEnderPearls = true;

        if (shouldForce(mod, getTwistingVines)) {
            return getTwistingVines;
        }

        if (mod.getEntityTracker().itemDropped(Items.ENDER_PEARL)) {
            return new PickupDroppedItemTask(Items.ENDER_PEARL, 1);
        }

        if (_config.barterPearlsInsteadOfEndermanHunt) {
            if (!StorageHelper.isArmorEquipped(Items.GOLDEN_HELMET)) {
                return new EquipArmorTask(Items.GOLDEN_HELMET);
            }
            return new TradeWithPiglinsTask(32, Items.ENDER_PEARL, count);
        }

        boolean endermanFound = mod.getEntityTracker().entityFound(EndermanEntity.class);
        boolean pearlDropped = mod.getEntityTracker().itemDropped(Items.ENDER_PEARL);
        boolean hasTwistingVines = mod.getItemStorage().getItemCount(Items.TWISTING_VINES) > TWISTING_VINES_COUNT_MIN;

        if ((endermanFound || pearlDropped) && hasTwistingVines) {
            Optional<Entity> toKill = mod.getEntityTracker().getClosestEntity(EndermanEntity.class);
            if (toKill.isPresent()) {
                return new KillEndermanTask(count);
            }
        }

        boolean hasEnoughTwistingVines = mod.getItemStorage().getItemCount(Items.TWISTING_VINES) >= TWISTING_VINES_COUNT_MIN;

        if (!hasEnoughTwistingVines) {
            // BlockScanner tracks automatically - no need to call trackBlock
            boolean vinesFound = mod.getBlockScanner().anyFound(Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT);
            if (vinesFound) {
                getTwistingVines = TaskCatalogue.getItemTask(Items.TWISTING_VINES, TWISTING_VINES_COUNT);
                return getTwistingVines;
            }
            return new SearchChunkForBlockTask(Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WARPED_HYPHAE, Blocks.WARPED_NYLIUM);
        }

        return new SearchWithinBiomeTask(BiomeKeys.WARPED_FOREST);
    }

    private int getTargetBeds(AltoClef mod) {
        int bedsInEnd = 0;
        for (Item bed : ItemHelper.BED) {
            bedsInEnd += _cachedEndItemDrops.getOrDefault(bed, 0);
        }
        int targetBeds = _config.requiredBeds;
        if (_config.placeSpawnNearEndPortal) {
            if (!spawnSetNearPortal(mod, _endPortalCenterLocation) && !shouldForce(mod, _setBedSpawnTask)) {
                targetBeds += 1;
            }
        }
        targetBeds -= bedsInEnd;
        return targetBeds;
    }

    private boolean needsBeds(AltoClef mod) {
        int totalEndItems = 0;
        for (Item bed : ItemHelper.BED) {
            totalEndItems += _cachedEndItemDrops.getOrDefault(bed, 0);
        }

        int itemCount = mod.getItemStorage().getItemCount(ItemHelper.BED);
        int targetBeds = getTargetBeds(mod);
        return (itemCount + totalEndItems) < targetBeds;
    }

    private Task getBedTask(AltoClef mod) {
        int targetBeds = getTargetBeds(mod);

        if (!mod.getItemStorage().hasItem(Items.SHEARS) && !anyBedsFound(mod)) {
            return TaskCatalogue.getItemTask(Items.SHEARS, 1);
        }
        Block[] copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
        Block[] beds = ItemHelper.itemsToBlocks(ItemHelper.BED);
        for (Block bed : beds) {
            Optional<BlockPos> nearestBed = mod.getBlockScanner().getNearestBlock(bed);
            if (nearestBed.isPresent() && WorldHelper.canBreak(nearestBed.get())) {
                for (Block CopperBlock : copperBlocks) {
                    Block blockBelow = mod.getWorld().getBlockState(nearestBed.get().down()).getBlock();
                    if (blockBelow == CopperBlock) {
                        Debug.logMessage("Blacklisting bed in trial chambers.");
                        mod.getBlockScanner().requestBlockUnreachable(nearestBed.get(), 0);
                    }
                }
            }
        }
        return TaskCatalogue.getItemTask("bed", targetBeds);
    }

    private boolean anyBedsFound(AltoClef mod) {
        Block[] copperBlocksLocal = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
        boolean validBedsFoundInBlocks = false;
        Block[] beds = ItemHelper.itemsToBlocks(ItemHelper.BED);
        for (Block bed : beds) {
            Optional<BlockPos> nearestBed = mod.getBlockScanner().getNearestBlock(bed);
            if (nearestBed.isPresent() && WorldHelper.canBreak(nearestBed.get())) {
                validBedsFoundInBlocks = true;
                for (Block CopperBlock : copperBlocksLocal) {
                    Block blockBelow = mod.getWorld().getBlockState(nearestBed.get().down()).getBlock();
                    if (blockBelow == CopperBlock) {
                        validBedsFoundInBlocks = false;
                        break;
                    }
                }
            }
        }

        boolean bedsFoundInEntities = mod.getEntityTracker().itemDropped(ItemHelper.BED);
        return validBedsFoundInBlocks || bedsFoundInEntities;
    }

    private BlockPos doSimpleSearchForEndPortal(AltoClef mod) {
        List<BlockPos> frames = mod.getBlockScanner().getKnownLocations(Blocks.END_PORTAL_FRAME);
        if (frames.size() < END_PORTAL_FRAME_COUNT) {
            return null;
        }
        int sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos frame : frames) {
            sumX += frame.getX();
            sumY += frame.getY();
            sumZ += frame.getZ();
        }
        return new BlockPos(sumX / frames.size(), sumY / frames.size(), sumZ / frames.size());
    }

    private int getFilledPortalFrames(AltoClef mod, BlockPos endPortalCenter) {
        if (!endPortalFound(mod, endPortalCenter)) {
            return 0;
        }
        int filledFramesCount = 0;
        for (BlockPos frame : getFrameBlocks(endPortalCenter)) {
            if (mod.getChunkTracker().isChunkLoaded(frame) && isEndPortalFrameFilled(mod, frame)) {
                filledFramesCount++;
            }
        }
        return filledFramesCount;
    }

    private boolean canBeLootablePortalChest(AltoClef mod, BlockPos blockPos) {
        if (mod.getWorld().getBlockState(blockPos.up()).getBlock() == Blocks.WATER ||
                blockPos.getY() < 50) {
            return false;
        }

        BlockPos minPos = blockPos.add(-4, -2, -4);
        BlockPos maxPos = blockPos.add(4, 2, 4);

        try {
            for (BlockPos checkPos : WorldHelper.scanRegion(minPos, maxPos)) {
                if (mod.getWorld().getBlockState(checkPos).getBlock() == Blocks.NETHERRACK) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        _notRuinedPortalChests.add(blockPos);

        return false;
    }

    private Task getEyesOfEnderTask(AltoClef mod, int targetEyes) {
        if (mod.getEntityTracker().itemDropped(Items.ENDER_EYE)) {
            setDebugState("Picking up Dropped Eyes");
            return new PickupDroppedItemTask(Items.ENDER_EYE, targetEyes);
        }

        int eyeCount = mod.getItemStorage().getItemCount(Items.ENDER_EYE);

        int blazePowderCount = mod.getItemStorage().getItemCount(Items.BLAZE_POWDER);
        int blazeRodCount = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        int blazeRodTarget = (int) Math.ceil(((double) targetEyes - eyeCount - blazePowderCount) / 2.0);
        int enderPearlTarget = targetEyes - eyeCount;
        boolean needsBlazeRods = blazeRodCount < blazeRodTarget;
        boolean needsBlazePowder = eyeCount + blazePowderCount < targetEyes;
        boolean needsEnderPearls = mod.getItemStorage().getItemCount(Items.ENDER_PEARL) < enderPearlTarget;

        if (needsBlazePowder && !needsBlazeRods) {
            setDebugState("Crafting blaze powder");
            return TaskCatalogue.getItemTask(Items.BLAZE_POWDER, targetEyes - eyeCount);
        }

        if (!needsBlazePowder && !needsEnderPearls) {
            setDebugState("Crafting Ender Eyes");
            return TaskCatalogue.getItemTask(Items.ENDER_EYE, targetEyes);
        }

        // Get blaze rods + pearls...
        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                // If we happen to find beds...
                if (needsBeds(mod) && anyBedsFound(mod)) {
                    setDebugState("A bed was found, getting it.");
                    if (_config.renderDistanceManipulation) {
                        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                            if (_timer1.elapsed()) {
                                getInstance().options.getViewDistance().setValue(2);
                                getInstance().options.getEntityDistanceScaling().setValue(0.5);
                                _timer1.reset();
                            }
                        }
                    }
                    getBedTask = getBedTask(mod);
                    return getBedTask;
                }
                getBedTask = null;
                if (shouldForce(mod, _logsTask)) {
                    setDebugState("Getting logs for later.");
                    return _logsTask;
                }
                _logsTask = null;
                if (shouldForce(mod, _stoneGearTask)) {
                    setDebugState("Getting stone gear for later.");
                    return _stoneGearTask;
                }
                _stoneGearTask = null;
                if (shouldForce(mod, _getPorkchopTask)) {
                    setDebugState("Getting pork chop just for fun.");
                    if (_config.renderDistanceManipulation) {
                        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                            getInstance().options.getViewDistance().setValue(32);
                            getInstance().options.getEntityDistanceScaling().setValue(5.0);
                        }
                    }
                    return _getPorkchopTask;
                }
                _getPorkchopTask = null;
                if (shouldForce(mod, _starterGearTask)) {
                    setDebugState("Getting starter gear.");
                    return _starterGearTask;
                }
                _starterGearTask = null;
                if (shouldForce(mod, _shieldTask) && !StorageHelper.isArmorEquipped(COLLECT_SHIELD)) {
                    setDebugState("Getting shield for defense purposes only.");
                    return _shieldTask;
                }
                _shieldTask = null;
                if (shouldForce(mod, _foodTask)) {
                    setDebugState("Getting food for ender eye journey.");
                    return _foodTask;
                }
                _foodTask = null;
                if (shouldForce(mod, _smeltTask)) {
                    if (_config.renderDistanceManipulation) {
                        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                            if (_timer1.elapsed()) {
                                getInstance().options.getViewDistance().setValue(2);
                                getInstance().options.getEntityDistanceScaling().setValue(0.5);
                                _timer1.reset();
                            }
                        }
                    }
                    return _smeltTask;
                }
                _smeltTask = null;
                // Smelt remaining raw food
                if (_config.alwaysCookRawFood) {
                    for (Item raw : ItemHelper.RAW_FOODS) {
                        if (mod.getItemStorage().hasItem(raw)) {
                            Optional<Item> cooked = ItemHelper.getCookedFood(raw);
                            if (cooked.isPresent()) {
                                int targetCount = mod.getItemStorage().getItemCount(cooked.get()) + mod.getItemStorage().getItemCount(raw);
                                setDebugState("Smelting raw food: " + ItemHelper.stripItemName(raw));
                                _smeltTask = new SmeltInSmokerTask(new SmeltTarget(new ItemTarget(cooked.get(), targetCount), new ItemTarget(raw, targetCount)));
                                return _smeltTask;
                            }
                        }
                    }
                }
                _smeltTask = null;
                // Make sure we have gear, then food.
                if (shouldForce(mod, _lootTask)) {
                    setDebugState("Looting chest for goodies");
                    return _lootTask;
                }
                _lootTask = null;
                if (shouldForce(mod, _ironGearTask) && !StorageHelper.isArmorEquipped(COLLECT_IRON_ARMOR)) {
                    setDebugState("Getting iron gear before diamond gear for defense purposes only.");
                    return _ironGearTask;
                }
                _ironGearTask = null;
                if (shouldForce(mod, _gearTask) && !StorageHelper.isArmorEquipped(COLLECT_EYE_ARMOR)) {
                    setDebugState("Getting diamond gear for ender eye journey.");
                    return _gearTask;
                }
                _gearTask = null;

                boolean eyeGearSatisfied = StorageHelper.itemTargetsMet(mod, COLLECT_EYE_GEAR_MIN) && StorageHelper.isArmorEquippedAll(COLLECT_EYE_ARMOR);
                boolean ironGearSatisfied2 = StorageHelper.itemTargetsMet(mod, COLLECT_IRON_GEAR_MIN) && StorageHelper.isArmorEquippedAll(COLLECT_IRON_ARMOR);
                boolean shieldSatisfied = StorageHelper.isArmorEquipped(COLLECT_SHIELD);
                if (!isEquippingDiamondArmor) {
                    if (!mod.getItemStorage().hasItem(Items.PORKCHOP) &&
                            !mod.getItemStorage().hasItem(Items.COOKED_PORKCHOP) &&
                            !StorageHelper.itemTargetsMet(mod, IRON_GEAR_MIN) && !ironGearSatisfied2 && !eyeGearSatisfied) {
                        if (mod.getItemStorage().getItemCount(ItemHelper.LOG) < 12
                                && mod.getItemStorage().getItemCount(ItemHelper.PLANKS) < 12 * 4
                                && !StorageHelper.itemTargetsMet(mod, COLLECT_STONE_GEAR)
                                && !StorageHelper.itemTargetsMet(mod, IRON_GEAR_MIN) && !eyeGearSatisfied && !ironGearSatisfied2) {
                            _logsTask = TaskCatalogue.getItemTask("log", 18);
                            return _logsTask;
                        }
                        if (!StorageHelper.itemTargetsMet(mod, COLLECT_STONE_GEAR) &&
                                !StorageHelper.itemTargetsMet(mod, IRON_GEAR_MIN) && !eyeGearSatisfied &&
                                !ironGearSatisfied2) {
                            if (mod.getItemStorage().getItemCount(Items.STICK) < 7) {
                                _stoneGearTask = TaskCatalogue.getItemTask(Items.STICK, 15);
                                return _stoneGearTask;
                            }
                            _stoneGearTask = TaskCatalogue.getSquashedItemTask(COLLECT_STONE_GEAR);
                            return _stoneGearTask;
                        }
                        if (mod.getEntityTracker().entityFound(PigEntity.class) && (StorageHelper.itemTargetsMet(mod,
                                COLLECT_STONE_GEAR) || StorageHelper.itemTargetsMet(mod, IRON_GEAR_MIN) ||
                                eyeGearSatisfied || ironGearSatisfied2)) {
                            Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity livingEntity && !livingEntity.isBaby();
                            _getPorkchopTask = new KillAndLootTask(PigEntity.class, notBaby, new ItemTarget(Items.PORKCHOP, 1));
                            return _getPorkchopTask;
                        }
                        setDebugState("Searching a better place to start with.");
                        if (_config.renderDistanceManipulation) {
                            if (!mod.getClientBaritone().getExploreProcess().isActive()) {
                                if (_timer1.elapsed()) {
                                    getInstance().options.getViewDistance().setValue(32);
                                    getInstance().options.getEntityDistanceScaling().setValue(5.0);
                                    _timer1.reset();
                                }
                            }
                        }
                        searchBiomeTask = new SearchWithinBiomeTask(BiomeKeys.PLAINS);
                        return searchBiomeTask;
                    }
                    // Then get one bed
                    if (!mod.getItemStorage().hasItem(ItemHelper.BED) && _config.sleepThroughNight) {
                        return _getOneBedTask;
                    }
                    // Then starter gear
                    if (!StorageHelper.itemTargetsMet(mod, IRON_GEAR_MIN) && !eyeGearSatisfied &&
                            !ironGearSatisfied2 && !StorageHelper.isArmorEquipped(Items.SHIELD)) {
                        _starterGearTask = TaskCatalogue.getSquashedItemTask(IRON_GEAR);
                        return _starterGearTask;
                    }
                    // Then get shield
                    if (_config.getShield && !shieldSatisfied && !mod.getFoodChain().needsToEat()) {
                        ItemTarget shield = new ItemTarget(COLLECT_SHIELD);
                        if (mod.getItemStorage().hasItem(shield) && !StorageHelper.isArmorEquipped(COLLECT_SHIELD)) {
                            setDebugState("Equipping shield.");
                            return new EquipArmorTask(COLLECT_SHIELD);
                        }
                        _shieldTask = TaskCatalogue.getItemTask(shield);
                        return _shieldTask;
                    }
                    // Then get food
                    if (StorageHelper.calculateInventoryFoodScore() < _config.minFoodUnits) {
                        _foodTask = new CollectFoodTask(_config.foodUnits);
                        return _foodTask;
                    }
                    // Then loot chest if there is any
                    if (_config.searchRuinedPortals) {
                        Optional<BlockPos> chest = locateClosestUnopenedRuinedPortalChest(mod);
                        if (chest.isPresent()) {
                            _lootTask = new LootContainerTask(chest.get(), lootableItems(mod), _noCurseOfBinding);
                            return _lootTask;
                        }
                    }
                    if (_config.searchDesertTemples && StorageHelper.miningRequirementMetInventory(MiningRequirement.WOOD)) {
                        BlockPos temple = WorldHelper.getADesertTemple();
                        if (temple != null) {
                            _lootTask = new LootDesertTempleTask(temple, lootableItems(mod));
                            return _lootTask;
                        }
                    }
                    // Then get iron
                    if (_config.ironGearBeforeDiamondGear && !ironGearSatisfied2 && !eyeGearSatisfied) {
                        for (Item ironArmor : COLLECT_IRON_ARMOR) {
                            if (mod.getItemStorage().hasItem(ironArmor) && !StorageHelper.isArmorEquipped(ironArmor)) {
                                setDebugState("Equipping armor.");
                                return new EquipArmorTask(ironArmor);
                            }
                        }
                        List<ItemTarget> ironGearsAndArmors = new ArrayList<>();
                        for (ItemTarget ironGear : COLLECT_IRON_GEAR) {
                            ironGearsAndArmors.add(ironGear);
                        }
                        for (Item ironArmor : COLLECT_IRON_ARMOR) {
                            if (!mod.getItemStorage().hasItem(ironArmor) && !StorageHelper.isArmorEquipped(ironArmor)) {
                                ironGearsAndArmors.add(new ItemTarget(ironArmor, 1));
                            }
                        }
                        _ironGearTask = TaskCatalogue.getSquashedItemTask(ironGearsAndArmors.toArray(ItemTarget[]::new));
                        return _ironGearTask;
                    }
                    _ironGearTask = null;
                }
                // Then get diamond
                if (!eyeGearSatisfied) {
                    for (Item diamond : COLLECT_EYE_ARMOR) {
                        if (mod.getItemStorage().hasItem(diamond) && !StorageHelper.isArmorEquipped(diamond)) {
                            setDebugState("Equipping armor.");
                            isEquippingDiamondArmor = true;
                            return new EquipArmorTask(diamond);
                        }
                    }
                    List<ItemTarget> diamondGearsAndArmors = new ArrayList<>();
                    for (ItemTarget diamondGear : COLLECT_EYE_GEAR) {
                        diamondGearsAndArmors.add(diamondGear);
                    }
                    for (Item diamondArmor : COLLECT_EYE_ARMOR) {
                        if (!mod.getItemStorage().hasItem(diamondArmor) && !StorageHelper.isArmorEquipped(diamondArmor)) {
                            diamondGearsAndArmors.add(new ItemTarget(diamondArmor, 1));
                        }
                    }
                    _gearTask = TaskCatalogue.getSquashedItemTask(diamondGearsAndArmors.toArray(ItemTarget[]::new));
                    return _gearTask;
                } else {
                    _gearTask = null;
                    if (!StorageHelper.isBigCraftingOpen() && !StorageHelper.isFurnaceOpen() && !StorageHelper.isSmokerOpen()
                            && !StorageHelper.isBlastFurnaceOpen() && (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)
                            || mod.getItemStorage().hasItem(Items.FIRE_CHARGE))) {
                        Item[] throwGearItems = {Items.STONE_SWORD, Items.STONE_PICKAXE, Items.IRON_SWORD, Items.IRON_PICKAXE};
                        List<Slot> throwGearSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, throwGearItems);
                        if (!throwGearSlot.isEmpty()) {
                            for (Slot slot : throwGearSlot) {
                                if (Slot.isCursor(slot)) {
                                    if (!mod.getControllerExtras().isBreakingBlock()) {
                                        LookHelper.randomOrientation(mod);
                                    }
                                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                                } else {
                                    mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                                }
                            }
                        }
                        List<Slot> ironArmorSlot2 = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, COLLECT_IRON_ARMOR);
                        if (!ironArmorSlot2.isEmpty()) {
                            for (Slot slot : ironArmorSlot2) {
                                if (Slot.isCursor(slot)) {
                                    if (!mod.getControllerExtras().isBreakingBlock()) {
                                        LookHelper.randomOrientation(mod);
                                    }
                                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                                } else {
                                    mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                                }
                            }
                        }
                    }
                }
                if (needsBuildingMaterials(mod)) {
                    setDebugState("Collecting building materials.");
                    return _buildMaterialsTask;
                }
                // Then go to the nether.
                setDebugState("Going to Nether");
                return _goToNetherTask;
            }
            case NETHER -> {
                if (needsEnderPearls) {
                    setDebugState("Getting Ender Pearls");
                    return getEnderPearlTask(mod, enderPearlTarget);
                }
                setDebugState("Getting Blaze Rods");
                return getBlazeRodsTask(mod, blazeRodTarget);
            }
            case END -> throw new UnsupportedOperationException("You're in the end. Don't collect eyes here.");
        }
        return null;
    }
}

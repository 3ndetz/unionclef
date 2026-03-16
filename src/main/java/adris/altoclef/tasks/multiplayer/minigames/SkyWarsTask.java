package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.entity.KillPlayerTask;
import adris.altoclef.tasks.entity.ShiftEntityTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

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
                pos, toItemTargets(lootableItems(mod).toArray(new Item[0])));

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
            return new KillPlayerTask(player.getName().getString());
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

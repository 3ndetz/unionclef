package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.TimersHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class KitPVPTask extends Task {

    private final Task _foodTask = new CollectFoodTask(80);
    private final TimerGame _runAwayExtraTime = new TimerGame(10);
    private final TimerGame _funnyMessageTimer = new TimerGame(10);
    private final TimerGame _performExtraActionsTimer = new TimerGame(2.5);
    private Vec3d _closestPlayerLastPos;
    private Vec3d _closestPlayerLastObservePos;
    private double _closestDistance;

    private Task _runAwayTask;
    private String _currentVisibleTarget;
    private boolean _forceWait = false;
    private boolean _isEatingStrength = false;
    private boolean _isEatingGapple = false;
    private final TimerGame _eatingGappleTimer = new TimerGame(3);
    private Task _armorTask;
    private Task _shootArrowTask;
    private Task _lootTask;
    private Task _pickupTask;
    private boolean _finishOnKilled = false;

    private List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        lootable.addAll(armorAndToolsNeeded(mod));
        lootable.addAll(Arrays.stream(ItemHelper.PLANKS).toList());
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.GOLDEN_CARROT);
        lootable.add(Items.STONE);
        lootable.add(Items.BOW);
        lootable.add(Items.ARROW);
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

    public KitPVPTask(BlockPos center, double scanRadius, boolean finishOnKilled) {
        _finishOnKilled = finishOnKilled;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().setForceFieldPlayers(true);
    }

    private BlockPos _lastLootPos;

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        Optional<Entity> closest = mod.getEntityTracker()
                .getClosestEntity(mod.getPlayer().getPos(),
                        toPunk -> shouldPunk(mod, (PlayerEntity) toPunk),
                        PlayerEntity.class);
        boolean targetIsNear = false;

        if (closest.isPresent()) {
            _closestPlayerLastPos = closest.get().getPos();
            _closestPlayerLastObservePos = mod.getPlayer().getPos();
            _closestDistance = _closestPlayerLastPos.distanceTo(_closestPlayerLastObservePos);
            if (_closestDistance <= 8 && mod.getEntityTracker().isEntityReachable(closest.get())) {
                targetIsNear = true;
            }
        }

        if (_forceWait && !targetIsNear) {
            return null;
        }

        if (shouldForce(_shootArrowTask)) {
            return _shootArrowTask;
        }

        if (!targetIsNear) {
            if (shouldForce(_armorTask)) {
                return _armorTask;
            }

            boolean reachableLootCont = true;
            if (_lastLootPos != null) {
                reachableLootCont = WorldHelper.canReach(_lastLootPos);
            }
            if (reachableLootCont && shouldForce(_lootTask)) {
                return _lootTask;
            }

            if (_isEatingStrength) {
                _isEatingStrength = false;
            }

            // Use strength potion
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.STRENGTH)
                    && mod.getItemStorage().hasItem(Items.GUNPOWDER)) {
                if (LookHelper.tryAvoidingInteractable(mod)) {
                    setDebugState("Найдена смесь силы; надо понюхать");
                    mod.getSlotHandler().forceEquipItem(new Item[]{Items.GUNPOWDER});
                    mod.getInputControls().hold(Input.CLICK_RIGHT);
                    mod.getInputControls().release(Input.CLICK_RIGHT);
                    _isEatingStrength = true;
                } else {
                    setDebugState("Нюхаем смесь силы: меняем угол обзора чтобы не интерактить");
                }
                return null;
            }

            // Eat gapples
            boolean needEatGapple = !mod.getPlayer().hasStatusEffect(StatusEffects.ABSORPTION)
                    || (mod.getPlayer().getHealth() < 18 && _eatingGappleTimer.getDuration() > 6);
            if (needEatGapple && mod.getItemStorage()
                    .hasItemInventoryOnly(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE)) {
                if (LookHelper.tryAvoidingInteractable(mod) && !_isEatingGapple) {
                    setDebugState("Есть яблоко, почему бы не пожрать..");
                    mod.getSlotHandler().forceEquipItem(
                            new Item[]{Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE}, true);
                    mod.getInputControls().hold(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _eatingGappleTimer.reset();
                    _isEatingGapple = true;
                } else {
                    if (_isEatingGapple && _eatingGappleTimer.elapsed()) {
                        _isEatingGapple = false;
                        setDebugState("Яблоко не съелось! Попытка 2!");
                    } else {
                        setDebugState("Жрем геплы: меняем угол обзора");
                    }
                }
                return null;
            } else {
                if (_isEatingGapple) {
                    mod.getInputControls().release(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(false);
                    _isEatingGapple = false;
                }
            }

            // Equip armor in priority order
            int armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.HelmetsTopPriority);
            if (armorEquipNeed != -1) {
                _armorTask = new EquipArmorTask(
                        Arrays.stream(ItemHelper.HelmetsTopPriority).toList().get(armorEquipNeed));
                return _armorTask;
            }
            armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.ChestplatesTopPriority);
            if (armorEquipNeed != -1) {
                _armorTask = new EquipArmorTask(
                        Arrays.stream(ItemHelper.ChestplatesTopPriority).toList().get(armorEquipNeed));
                return _armorTask;
            }
            armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.LeggingsTopPriority);
            if (armorEquipNeed != -1) {
                _armorTask = new EquipArmorTask(
                        Arrays.stream(ItemHelper.LeggingsTopPriority).toList().get(armorEquipNeed));
                return _armorTask;
            }
            armorEquipNeed = isArmorNeededToEquip(mod, ItemHelper.BootsTopPriority);
            if (armorEquipNeed != -1) {
                _armorTask = new EquipArmorTask(
                        Arrays.stream(ItemHelper.BootsTopPriority).toList().get(armorEquipNeed));
                return _armorTask;
            }

            // Loot nearby chests
            Optional<BlockPos> closestCont = mod.getBlockScanner().getNearestBlock(
                    blockPos -> WorldHelper.isUnopenedChest(blockPos)
                            && mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 10)
                            && WorldHelper.canReach(blockPos),
                    Blocks.CHEST);
            if (closestCont.isPresent() && WorldHelper.canReach(closestCont.get())
                    && TimersHelper.CanChestInteract()) {
                setDebugState("Поиск ресурсов -> контейнеры:");
                _lastLootPos = closestCont.get();
                _lootTask = new LootContainerTask(closestCont.get(), lootableItems(mod));
                return _lootTask;
            }

            // Pick up dropped items
            for (Item check : lootableItems(mod)) {
                if (mod.getEntityTracker().itemDropped(check)) {
                    Optional<ItemEntity> closestEnt = mod.getEntityTracker().getClosestItemDrop(
                            ent -> mod.getPlayer().getPos().isInRange(ent.getEyePos(), 10), check);
                    if (closestEnt.isPresent()) {
                        _pickupTask = new PickupDroppedItemTask(new ItemTarget(check, 1), true);
                        return _pickupTask;
                    }
                }
            }

            if (shouldBow(mod) && closest.isPresent()) {
                _shootArrowTask = new ShootArrowSimpleProjectileTask(closest.get());
                return _shootArrowTask;
            }
        } else {
            if (_isEatingGapple) {
                mod.getInputControls().release(Input.CLICK_RIGHT);
                mod.getExtraBaritoneSettings().setInteractionPaused(false);
                _isEatingGapple = false;
            }
        }

        if (closest.isPresent()) {
            setDebugState("УНИЧТОЖИТЬ");
            PlayerEntity entity = (PlayerEntity) closest.get();
            if (LookHelper.cleanLineOfSight(entity.getPos(), 100)) {
                if (mod.getPlayer().distanceTo(entity) > 10) {
                    if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) > 2) {
                        return new ThrowEnderPearlSimpleProjectileTask(entity.getBlockPos().add(0, -1, 0));
                    } else if (shouldBow(mod)) {
                        _shootArrowTask = new ShootArrowSimpleProjectileTask(entity);
                        return _shootArrowTask;
                    }
                }
                return new KillEntityTask(entity);
            } else {
                return null;
            }
        }

        setDebugState("Поиск низших сущностей...");
        _currentVisibleTarget = null;
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof KitPVPTask;
    }

    @Override
    protected String toDebugString() {
        return "Режим терминатора (кпвп): уничтожить";
    }

    private boolean shouldBow(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.BOW)
                && (mod.getItemStorage().hasItem(Items.ARROW)
                || mod.getItemStorage().hasItem(Items.SPECTRAL_ARROW));
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
                if (level > idx) {
                    level = idx;
                }
            }
            idx++;
        }
        return level;
    }

    private int isArmorNeededToEquip(AltoClef mod, Item[] armorsTopPriority) {
        int idx = 0;
        int level = -1;
        int hasLevel = 7;

        for (Item armorItem : armorsTopPriority) {
            if (StorageHelper.isArmorEquipped(armorItem)) {
                level = idx;
            }
            if (mod.getItemStorage().hasItem(armorItem)) {
                if (hasLevel > idx) {
                    hasLevel = idx;
                }
            }
            idx++;
        }
        if (level == -1) {
            level = 7;
        }
        if (hasLevel < level) {
            return hasLevel;
        } else {
            return -1;
        }
    }

    private boolean shouldPunk(AltoClef mod, PlayerEntity player) {
        if (player == null || player.isDead() || !player.isAlive()) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        return !mod.getButler().isUserAuthorized(player.getName().getString());
    }

    private static boolean shouldForce(Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }

}

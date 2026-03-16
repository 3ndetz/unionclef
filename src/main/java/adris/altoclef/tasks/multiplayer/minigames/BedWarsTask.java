package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.multiplayer.RejoinEvent;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.entity.KillPlayerTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;

import java.util.*;

public class BedWarsTask extends Task {

    public BedWarsTask() {
        // fields have defaults; initializeNewGame() is called from onStart()
    }

    BlockPos bedPos = null;
    public int ourColor = -1;
    public String ourColorName = "Unknown";
    private Task _pickupTask;

    public boolean virtualResourcesType = true;
    public boolean ownBedDestroyed = false;

    private final TimerReal shopTimer = new TimerReal(7);
    private final TimerReal shopCooldown = new TimerReal(20);
    private final TimerReal preShopTimer = new TimerReal(15);
    private final TimerReal _teamDetermineCooldown = new TimerReal(10);
    private boolean inShop = false;

    public boolean teamDetermined = false;

    protected boolean determineSelfColor(AltoClef mod) {
        ourColor = getHelmetColor(mod.getPlayer());
        if (ourColor == -1) {
            ourColorName = "Unknown";
            teamDetermined = false;
            return false;
        }
        ourColorName = getClosestColorName(ourColor);
        teamDetermined = true;
        return true;
    }

    public void initializeNewGame(AltoClef mod) {
        shopTimer.forceElapse();
        shopCooldown.forceElapse();
        ourColor = -1;
        _pickupTask = null;
        virtualResourcesType = true;
        teamDetermined = false;
        bedPos = null;
        inShop = false;
        ownBedDestroyed = false;

        if (determineSelfColor(mod))
            ourBedBlock = BEDWARS_BED_COLORS.get(ourColorName);
        else
            ourBedBlock = null;

        enemyBedBlocks = new ArrayList<>(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.BED)).toList());
        if (ourBedBlock != null)
            enemyBedBlocks.remove(ourBedBlock);
    }

    private Subscription<RejoinEvent> _rejoinSubscription;

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        initializeNewGame(mod);
        mod.getBehaviour().push();

        _rejoinSubscription = EventBus.subscribe(RejoinEvent.class, evt -> {
            Debug.logMessage("Rejoined game, resetting BedWars task.");
            initializeNewGame(AltoClef.getInstance());
        });
    }

    public static final Map<String, Integer> BEDWARS_COLORS = new HashMap<>();
    static {
        BEDWARS_COLORS.put("red", 0xFF0000);
        BEDWARS_COLORS.put("yellow", 0xFFFF00);
        BEDWARS_COLORS.put("orange", 0xFF8000);
        BEDWARS_COLORS.put("green", 0x336600);
        BEDWARS_COLORS.put("gray", 0x858585);
        BEDWARS_COLORS.put("cyan", 0x30D5C8);
        BEDWARS_COLORS.put("blue", 0x004DFF);
        BEDWARS_COLORS.put("light_blue", 0x00BFFF);
        BEDWARS_COLORS.put("pink", 0xF70AA0);
    }

    public static final Map<String, Block> BEDWARS_BED_COLORS = new HashMap<>();
    static {
        BEDWARS_BED_COLORS.put("red", Blocks.RED_BED);
        BEDWARS_BED_COLORS.put("yellow", Blocks.YELLOW_BED);
        BEDWARS_BED_COLORS.put("orange", Blocks.ORANGE_BED);
        BEDWARS_BED_COLORS.put("green", Blocks.GREEN_BED);
        BEDWARS_BED_COLORS.put("gray", Blocks.GRAY_BED);
        BEDWARS_BED_COLORS.put("cyan", Blocks.CYAN_BED);
        BEDWARS_BED_COLORS.put("blue", Blocks.BLUE_BED);
        BEDWARS_BED_COLORS.put("light_blue", Blocks.LIGHT_BLUE_BED);
        BEDWARS_BED_COLORS.put("pink", Blocks.PINK_BED);
    }

    public String getClosestColorName(int rgb) {
        if (rgb == -1) return "Unknown";

        int r1 = ColorHelper.Argb.getRed(rgb);
        int g1 = ColorHelper.Argb.getGreen(rgb);
        int b1 = ColorHelper.Argb.getBlue(rgb);

        String closestColorName = "Unknown";
        double minDistance = Double.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : BEDWARS_COLORS.entrySet()) {
            int colorRgb = entry.getValue();
            int r2 = ColorHelper.Argb.getRed(colorRgb);
            int g2 = ColorHelper.Argb.getGreen(colorRgb);
            int b2 = ColorHelper.Argb.getBlue(colorRgb);

            double distance = Math.sqrt(Math.pow(r1 - r2, 2) + Math.pow(g1 - g2, 2) + Math.pow(b1 - b2, 2));
            if (distance < minDistance) {
                minDistance = distance;
                closestColorName = entry.getKey();
            }
        }
        return closestColorName;
    }

    public int getHelmetColor(PlayerEntity player) {
        for (ItemStack itemStack : player.getArmorItems()) {
            if (itemStack.isOf(Items.LEATHER_HELMET)) {
                return DyedColorComponent.getColor(itemStack, DyedColorComponent.DEFAULT_COLOR);
            }
        }
        return -1;
    }

    public int getBalance(AltoClef mod) {
        if (!virtualResourcesType) {
            int ironCount = mod.getItemStorage().getItemCountInventoryOnly(Items.IRON_INGOT);
            int goldCount = mod.getItemStorage().getItemCountInventoryOnly(Items.GOLD_INGOT);
            if (ironCount < 64 || goldCount < 45) {
                return 0;
            } else {
                return ironCount * 4 + goldCount * 16;
            }
        }
        return mod.getPlayer().experienceLevel;
    }

    public boolean inOurTeam(PlayerEntity player) {
        int color = getHelmetColor(player);
        return ourColor == color && color != -1;
    }

    public List<Item> itemsToBuy(AltoClef mod) {
        List<Item> shoplist = new ArrayList<>();
        if (mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.WOOL) < 64) {
            shoplist.addAll(List.of(ItemHelper.WOOL));
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.IRON_SWORD)) {
            shoplist.add(Items.IRON_SWORD);
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.BOW)) {
            shoplist.add(Items.BOW);
        }
        if (mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.ARROWS) < 64) {
            shoplist.addAll(List.of(ItemHelper.ARROWS));
        }
        if (!StorageHelper.isArmorEquipped(Items.IRON_BOOTS)) {
            shoplist.add(Items.IRON_BOOTS);
        }
        return shoplist;
    }

    public List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        lootable.addAll(itemsToBuy(mod));
        lootable.add(Items.GOLD_INGOT);
        lootable.add(Items.DIAMOND);
        lootable.add(Items.EMERALD);
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.BOW);
        lootable.add(Items.ARROW);
        lootable.add(Items.IRON_INGOT);
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            lootable.add(Items.WATER_BUCKET);
        }
        return lootable;
    }

    List<Block> enemyBedBlocks;
    Block ourBedBlock;

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getPlayer() == null) {
            return null;
        }

        boolean inChest = ContainerType.screenHandlerMatches(ContainerType.CHEST);

        if (inShop && !inChest) {
            inShop = false;
        }

        if (!teamDetermined) {
            if (_teamDetermineCooldown.elapsed()) {
                determineSelfColor(mod);
                _teamDetermineCooldown.reset();
            }
        }

        if (virtualResourcesType) {
            if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLD_INGOT, Items.IRON_INGOT)) {
                virtualResourcesType = false;
                Debug.logMessage("Virtual resources type disabled, using real resources");
            }
        }

        if (ourBedBlock != null) {
            if (bedPos == null) {
                Optional<BlockPos> bedPosOpt = mod.getBlockScanner().getNearestBlock(ourBedBlock);
                if (bedPosOpt.isPresent()) {
                    Debug.logMessage("Found our bed at " + bedPosOpt.get().toShortString());
                    bedPos = bedPosOpt.get();
                }
            } else {
                if (!ownBedDestroyed) {
                    Optional<BlockPos> bedPosOpt = mod.getBlockScanner().getNearestBlock(ourBedBlock);
                    if (bedPosOpt.isEmpty() && mod.getPlayer().getPos().distanceTo(bedPos.toCenterPos()) < 25) {
                        ownBedDestroyed = true;
                    }
                    Optional<Entity> closestEnemyNearBed = mod.getEntityTracker().getClosestEntity(
                            bedPos.toCenterPos(),
                            toPunk -> isValidEnemy(toPunk)
                                    && toPunk.getPos().isInRange(bedPos.toCenterPos(), 15),
                            PlayerEntity.class);
                    if (closestEnemyNearBed.isPresent()) {
                        Entity enemyBed = closestEnemyNearBed.get();
                        setDebugState("PROTECTING BED FROM " + enemyBed.getName().getString());
                        return new KillPlayerTask(enemyBed.getName().getString());
                    }
                }
            }
        }

        Optional<BlockPos> enemyBedPosOpt = mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(),
                to -> to.isWithinDistance(mod.getPlayer().getBlockPos(), 10),
                enemyBedBlocks != null ? enemyBedBlocks.toArray(Block[]::new) : new Block[0]);
        if (enemyBedPosOpt.isPresent()) {
            BlockPos enemyBedPos = enemyBedPosOpt.get();
            setDebugState("Destroying enemy bed at " + enemyBedPos.toShortString());
            return new DestroyBlockTask(enemyBedPos);
        }

        if (inChest) {
            setDebugState("shopping");
            if (!inShop && !preShopTimer.elapsed()) {
                inShop = true;
                shopTimer.reset();
            }
            if (shopTimer.elapsed() || preShopTimer.elapsed()) {
                inShop = false;
                shopCooldown.reset();
                StorageHelper.closeScreen();
                return null;
            }
            List<Item> toBuy = itemsToBuy(mod);
            if (!toBuy.isEmpty()) {
                Slot slot = getSlotShopBW(mod, false, toBuy.toArray(Item[]::new));
                if (slot != null) {
                    mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                    return null;
                } else {
                    shopCooldown.reset();
                }
            } else {
                shopCooldown.reset();
            }
        }

        Optional<Entity> closestEnemy = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> isValidEnemy(toPunk),
                PlayerEntity.class);
        if (closestEnemy.isPresent()) {
            Entity enemy = closestEnemy.get();
            double range = mod.getPlayer().getPos().distanceTo(enemy.getPos());
            if (range <= 20) {
                setDebugState("Attacking enemy: " + enemy.getName().getString());
                return new KillPlayerTask(enemy.getName().getString());
            } else {
                boolean preferBow = ShootArrowSimpleProjectileTask.canUseRanged(mod, enemy)
                        && mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.ARROWS) > 20;
                if (preferBow) {
                    setDebugState("Attacking ranged enemy: " + enemy.getName().getString());
                    return new ShootArrowSimpleProjectileTask(enemy);
                }
            }
        }

        if (!inChest && getBalance(mod) >= 350 && shopCooldown.elapsed()) {
            return new DoToClosestEntityTask(
                    entity -> {
                        if (entity.isInRange(mod.getPlayer(), 3)) {
                            if (LookHelper.canHitEntity(mod, entity)) {
                                setDebugState("Found villager: " + entity.getName().getString());
                                LookHelper.smoothLookAt(mod, entity);
                                mod.getController().interactEntity(mod.getPlayer(), entity, Hand.MAIN_HAND);
                                preShopTimer.reset();
                                inShop = false;
                                return null;
                            } else {
                                return new GetCloseToBlockTask(entity.getBlockPos());
                            }
                        } else {
                            return new GetToEntityTask(entity, 2);
                        }
                    },
                    entity -> isValidTrader(entity, mod),
                    VillagerEntity.class
            );
        }

        for (Item check : lootableItems(mod)) {
            if (mod.getEntityTracker().itemDropped(check)) {
                Optional<ItemEntity> closestEnt = mod.getEntityTracker().getClosestItemDrop(
                        ent -> mod.getEntityTracker().isEntityReachable(ent)
                                && mod.getPlayer().getPos().isInRange(ent.getEyePos(), 400),
                        check);
                if (closestEnt.isPresent()) {
                    setDebugState("Resource collecting");
                    _pickupTask = new PickupDroppedItemTask(new ItemTarget(check, 1), false);
                    return _pickupTask;
                }
            }
        }

        return null;
    }

    public static Slot getSlotShopBW(AltoClef mod, boolean chooseCategories, Item... checkItem) {
        Iterable<Slot> slots = Slot.getCurrentScreenSlots();
        if (AltoClef.inGame() && mod.getPlayer() != null && slots != null) {
            for (Slot slot : slots) {
                int windowSlot = slot.getWindowSlot();
                boolean check = chooseCategories
                        ? (windowSlot >= 0 && windowSlot < 9)
                        : (windowSlot >= 9);
                if (check) {
                    ItemStack itemStack = StorageHelper.getItemStackInSlot(slot);
                    if (itemStack != null && itemStack.getItem() instanceof Item item
                            && !(item.equals(Items.AIR))) {
                        if (Arrays.asList(checkItem).contains(item)) {
                            return slot;
                        }
                    }
                }
            }
        }
        return null;
    }

    protected boolean isValidEnemy(Entity entity) {
        return entity instanceof PlayerEntity player && !inOurTeam(player)
                && MurderMysteryTask.isValidPlayerMM(player);
    }

    public boolean isValidTrader(Entity villager, AltoClef mod) {
        return villager.isAlive() && villager.getName() != null
                && villager.getName().getString().toLowerCase().contains("магазин");
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().pop();
        EventBus.unsubscribe(_rejoinSubscription);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BedWarsTask;
    }

    @Override
    protected String toDebugString() {
        return "Playing Bed Wars"
                + ((ourColorName.isBlank()) ? "" : ": team " + ourColorName);
    }
}

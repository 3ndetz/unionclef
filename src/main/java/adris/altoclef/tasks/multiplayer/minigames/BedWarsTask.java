package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.multiplayer.RejoinEvent;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.entity.TungstenPunkTask;
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
import adris.altoclef.multiversion.ColorHelperVer;
import java.util.*;

public class BedWarsTask extends Task {

    // --- Config ---
    private static final int ENEMY_BED_SEARCH_RADIUS = 80;
    private static final int BED_PROTECT_RADIUS = 15;
    private static final int TEAMMATE_FOLLOW_RADIUS = 20;
    private static final double AGGRO_MODE_CHANCE = 0.20;
    private static final int AGGRO_MIN_BLOCKS = 48;
    private static final int MIN_BLOCKS_FOR_ACTIONS = 48;
    private static final int RESOURCE_SPAWN_CONFIRM_COUNT = 3;
    private static final double RESOURCE_SPAWN_MERGE_RADIUS = 2.0;
    private static final double RESOURCE_SPAWN_IDLE_RADIUS = 3.0;
    private static final int BED_COVER_TEAMMATE_TIMEOUT_TICKS = 100; // ~5 seconds

    // --- State ---
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

    // Aggro mode: 20% chance per game to be aggressive raider
    private boolean aggroMode = false;

    // Resource spawn tracking
    private final Map<BlockPos, Integer> resourceSpawnCounts = new HashMap<>();
    private final List<BlockPos> confirmedResourceSpawns = new ArrayList<>();

    // Bed covering state
    private int bedCoverTeammateTimeoutTicks = 0;

    List<Block> enemyBedBlocks;
    Block ourBedBlock;

    private Subscription<RejoinEvent> _rejoinSubscription;

    public BedWarsTask() {
    }

    // ========== Team detection ==========

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

        // Roll aggro mode
        aggroMode = Math.random() < AGGRO_MODE_CHANCE;
        if (aggroMode) {
            Debug.logMessage("BedWars: AGGRO MODE activated! Will raid enemy beds.");
        }

        // Reset resource spawn tracker
        resourceSpawnCounts.clear();
        confirmedResourceSpawns.clear();
        bedCoverTeammateTimeoutTicks = 0;

        if (determineSelfColor(mod))
            ourBedBlock = BEDWARS_BED_COLORS.get(ourColorName);
        else
            ourBedBlock = null;

        enemyBedBlocks = new ArrayList<>(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.BED)).toList());
        if (ourBedBlock != null)
            enemyBedBlocks.remove(ourBedBlock);

        // Add wool as throwaway (building) blocks for baritone bridging
        setupThrowawayBlocks(mod);
    }

    private void setupThrowawayBlocks(AltoClef mod) {
        List<Item> throwaways = mod.getClientBaritoneSettings().acceptableThrowawayItems.value;
        for (Item wool : ItemHelper.WOOL) {
            if (!throwaways.contains(wool)) {
                throwaways.add(wool);
            }
        }
        if (!throwaways.contains(Items.END_STONE)) {
            throwaways.add(Items.END_STONE);
        }
    }

    // ========== Lifecycle ==========

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

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().pop();
        EventBus.unsubscribe(_rejoinSubscription);
    }

    // ========== Main tick ==========

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

        // --- Team detection ---
        if (!teamDetermined) {
            if (_teamDetermineCooldown.elapsed()) {
                determineSelfColor(mod);
                _teamDetermineCooldown.reset();
                if (teamDetermined) {
                    ourBedBlock = BEDWARS_BED_COLORS.get(ourColorName);
                    enemyBedBlocks = new ArrayList<>(Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.BED)).toList());
                    if (ourBedBlock != null) enemyBedBlocks.remove(ourBedBlock);
                }
            }
        } else {
            int currentColor = getHelmetColor(mod.getPlayer());
            if (currentColor != ourColor) {
                Debug.logMessage("BedWars: team color changed, resetting for new game.");
                initializeNewGame(mod);
            }
        }

        // --- Resource type detection ---
        if (virtualResourcesType) {
            if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLD_INGOT, Items.IRON_INGOT)) {
                virtualResourcesType = false;
                Debug.logMessage("Virtual resources type disabled, using real resources");
            }
        }

        // --- Track resource spawns ---
        trackResourceSpawns(mod);

        // --- Own bed tracking ---
        if (ourBedBlock != null) {
            if (bedPos == null) {
                Optional<BlockPos> bedPosOpt = mod.getBlockScanner().getNearestBlock(ourBedBlock);
                if (bedPosOpt.isPresent()) {
                    Debug.logMessage("Found our bed at " + bedPosOpt.get().toShortString());
                    bedPos = bedPosOpt.get();
                }
            } else if (!ownBedDestroyed) {
                Optional<BlockPos> bedPosOpt = mod.getBlockScanner().getNearestBlock(ourBedBlock);
                if (bedPosOpt.isEmpty() && mod.getPlayer().getPos().distanceTo(bedPos.toCenterPos()) < 25) {
                    ownBedDestroyed = true;
                }
            }
        }

        // --- ALWAYS allowed: bed protection (enemy near our bed) ---
        if (bedPos != null && !ownBedDestroyed) {
            Optional<Entity> closestEnemyNearBed = mod.getEntityTracker().getClosestEntity(
                    bedPos.toCenterPos(),
                    toPunk -> isValidEnemy(toPunk)
                            && toPunk.getPos().isInRange(bedPos.toCenterPos(), BED_PROTECT_RADIUS),
                    PlayerEntity.class);
            if (closestEnemyNearBed.isPresent()) {
                Entity enemy = closestEnemyNearBed.get();
                setDebugState("PROTECTING BED FROM " + enemy.getName().getString());
                return new TungstenPunkTask(enemy.getName().getString());
            }
        }

        // --- ALWAYS allowed: self-defense (enemy within 5 blocks) ---
        Optional<Entity> closestEnemy = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> isValidEnemy(toPunk),
                PlayerEntity.class);
        if (closestEnemy.isPresent()) {
            Entity enemy = closestEnemy.get();
            double range = mod.getPlayer().getPos().distanceTo(enemy.getPos());
            if (range <= 5) {
                setDebugState("Self-defense: " + enemy.getName().getString());
                return new TungstenPunkTask(enemy.getName().getString());
            }
        }

        // --- ALWAYS allowed: shopping (if already in chest UI) ---
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

        // ============================================================
        // GEAR GATE: until we have MIN_BLOCKS_FOR_ACTIONS, only farm.
        // Allowed above: bed protection, self-defense, active shopping.
        // Below this gate: everything offensive / strategic.
        // ============================================================
        boolean hasMinGear = getBlockCount(mod) >= MIN_BLOCKS_FOR_ACTIONS;

        // --- ALWAYS allowed: go to shop to buy gear ---
        // PRIORITY: if we have currency but no blocks, shop FIRST before looting
        if (!inChest && shopCooldown.elapsed()) {
            boolean needsGear = !hasMinGear || getBalance(mod) >= 350;
            if (needsGear && getBalance(mod) > 0) {
                setDebugState("Going to shop (balance: " + getBalance(mod) + ")");
                return goToShop(mod);
            }
        }

        // --- Loot pickup ---
        // No gear yet: pick up everything within big radius (farming mode)
        // Has gear: only pick up stuff nearby (within 8 blocks), don't derail from tasks
        double lootRadius = hasMinGear ? 8 : 400;
        for (Item check : lootableItems(mod)) {
            if (mod.getEntityTracker().itemDropped(check)) {
                Optional<ItemEntity> closestEnt = mod.getEntityTracker().getClosestItemDrop(
                        ent -> mod.getEntityTracker().isEntityReachable(ent)
                                && mod.getPlayer().getPos().isInRange(ent.getEyePos(), lootRadius),
                        check);
                if (closestEnt.isPresent()) {
                    setDebugState("Resource collecting" + (hasMinGear ? " (nearby)" : ""));
                    _pickupTask = new PickupDroppedItemTask(new ItemTarget(check, 1), false);
                    return _pickupTask;
                }
            }
        }

        // --- Farming mode: idle at resource spawn ---
        if (!hasMinGear) {
            Task idleResourceTask = tryIdleAtResourceSpawn(mod);
            if (idleResourceTask != null) {
                return idleResourceTask;
            }
            setDebugState("Farming: need " + MIN_BLOCKS_FOR_ACTIONS + " blocks (have " + getBlockCount(mod) + ")");
            return null; // Hard stop — no offensive actions
        }

        // ============================================================
        // Below here: only runs when hasMinGear == true
        // ============================================================

        // --- Enemy bed destruction (expanded radius) ---
        if (teamDetermined) {
            Optional<BlockPos> enemyBedPosOpt = mod.getBlockScanner().getNearestBlock(
                    mod.getPlayer().getPos(),
                    to -> to.isWithinDistance(mod.getPlayer().getBlockPos(), ENEMY_BED_SEARCH_RADIUS),
                    enemyBedBlocks != null ? enemyBedBlocks.toArray(Block[]::new) : new Block[0]);
            if (enemyBedPosOpt.isPresent()) {
                BlockPos enemyBedPos = enemyBedPosOpt.get();
                setDebugState("Destroying enemy bed at " + enemyBedPos.toShortString());
                return new DestroyBlockTask(enemyBedPos);
            }
        }

        // --- Combat (full range) ---
        if (closestEnemy.isPresent()) {
            Entity enemy = closestEnemy.get();
            double range = mod.getPlayer().getPos().distanceTo(enemy.getPos());
            if (range <= 20) {
                setDebugState("Attacking enemy: " + enemy.getName().getString());
                return new TungstenPunkTask(enemy.getName().getString());
            } else {
                boolean preferBow = ShootArrowSimpleProjectileTask.canUseRanged(mod, enemy)
                        && mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.ARROWS) > 20;
                if (preferBow) {
                    setDebugState("Attacking ranged enemy: " + enemy.getName().getString());
                    return new ShootArrowSimpleProjectileTask(enemy);
                }
            }
        }

        // --- Aggro mode: go raid enemy beds ---
        if (aggroMode && teamDetermined) {
            int blockCount = getBlockCount(mod);
            if (blockCount >= AGGRO_MIN_BLOCKS) {
                Optional<BlockPos> anyEnemyBed = mod.getBlockScanner().getNearestBlock(
                        enemyBedBlocks != null ? enemyBedBlocks.toArray(Block[]::new) : new Block[0]);
                if (anyEnemyBed.isPresent()) {
                    setDebugState("AGGRO: raiding enemy bed at " + anyEnemyBed.get().toShortString());
                    return new GetCloseToBlockTask(anyEnemyBed.get());
                }
            }
        }

        // --- Bed covering with endstone ---
        Task bedCoverTask = tryBedCovering(mod);
        if (bedCoverTask != null) {
            return bedCoverTask;
        }

        // --- Idle: sit at resource spawn point ---
        Task idleResourceTask = tryIdleAtResourceSpawn(mod);
        if (idleResourceTask != null) {
            return idleResourceTask;
        }

        // --- Idle: follow teammates ---
        Task followTask = tryFollowTeammate(mod);
        if (followTask != null) {
            return followTask;
        }

        return null;
    }

    // ========== Resource spawn tracking ==========

    private void trackResourceSpawns(AltoClef mod) {
        // Watch for iron/gold drops on the ground and record their positions
        for (Item resource : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.EMERALD}) {
            if (mod.getEntityTracker().itemDropped(resource)) {
                Optional<ItemEntity> drop = mod.getEntityTracker().getClosestItemDrop(
                        ent -> mod.getPlayer().getPos().isInRange(ent.getPos(), 30),
                        resource);
                if (drop.isPresent()) {
                    BlockPos dropPos = drop.get().getBlockPos();
                    // Merge with nearby known spawns
                    BlockPos mergedPos = findNearbySpawn(dropPos);
                    if (mergedPos != null) {
                        int count = resourceSpawnCounts.getOrDefault(mergedPos, 0) + 1;
                        resourceSpawnCounts.put(mergedPos, count);
                        if (count >= RESOURCE_SPAWN_CONFIRM_COUNT && !confirmedResourceSpawns.contains(mergedPos)) {
                            confirmedResourceSpawns.add(mergedPos);
                            Debug.logMessage("BedWars: confirmed resource spawn at " + mergedPos.toShortString());
                        }
                    } else {
                        resourceSpawnCounts.put(dropPos, resourceSpawnCounts.getOrDefault(dropPos, 0) + 1);
                    }
                }
            }
        }
    }

    private BlockPos findNearbySpawn(BlockPos pos) {
        for (BlockPos known : resourceSpawnCounts.keySet()) {
            if (known.isWithinDistance(pos, RESOURCE_SPAWN_MERGE_RADIUS)) {
                return known;
            }
        }
        return null;
    }

    private Task tryIdleAtResourceSpawn(AltoClef mod) {
        if (confirmedResourceSpawns.isEmpty()) return null;

        // Find closest confirmed spawn
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos spawn : confirmedResourceSpawns) {
            double dist = spawn.getSquaredDistance(mod.getPlayer().getPos());
            if (dist < closestDist) {
                closestDist = dist;
                closest = spawn;
            }
        }

        if (closest != null) {
            double dist = Math.sqrt(closestDist);
            if (dist > RESOURCE_SPAWN_IDLE_RADIUS) {
                setDebugState("Idle: going to resource spawn " + closest.toShortString());
                return new GetCloseToBlockTask(closest);
            } else {
                setDebugState("Idle: sitting at resource spawn");
                return null; // Already there, just chill
            }
        }
        return null;
    }

    // ========== Teammate following ==========

    private Task tryFollowTeammate(AltoClef mod) {
        if (!teamDetermined) return null;
        if (!hasBasicNeeds(mod)) return null;

        Optional<Entity> teammate = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                entity -> entity instanceof PlayerEntity player
                        && inOurTeam(player)
                        && !player.equals(mod.getPlayer())
                        && player.getPos().isInRange(mod.getPlayer().getPos(), TEAMMATE_FOLLOW_RADIUS),
                PlayerEntity.class);

        if (teammate.isPresent()) {
            setDebugState("Following teammate: " + teammate.get().getName().getString());
            return new GetToEntityTask(teammate.get(), 4);
        }
        return null;
    }

    private boolean hasBasicNeeds(AltoClef mod) {
        // Has a weapon
        boolean hasWeapon = mod.getItemStorage().hasItemInventoryOnly(Items.IRON_SWORD)
                || mod.getItemStorage().hasItemInventoryOnly(Items.STONE_SWORD)
                || mod.getItemStorage().hasItemInventoryOnly(Items.DIAMOND_SWORD);
        // Has some blocks
        boolean hasBlocks = getBlockCount(mod) >= 16;
        return hasWeapon && hasBlocks;
    }

    // ========== Go to shop ==========

    private Task goToShop(AltoClef mod) {
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

    // ========== Bed covering ==========

    private Task tryBedCovering(AltoClef mod) {
        if (bedPos == null || ownBedDestroyed) return null;
        if (!teamDetermined) return null;

        // Only cover bed if no enemy beds nearby (nothing better to do offensively)
        boolean enemyBedNearby = false;
        if (enemyBedBlocks != null) {
            Optional<BlockPos> nearbyEnemy = mod.getBlockScanner().getNearestBlock(
                    mod.getPlayer().getPos(),
                    to -> to.isWithinDistance(mod.getPlayer().getBlockPos(), 40),
                    enemyBedBlocks.toArray(Block[]::new));
            enemyBedNearby = nearbyEnemy.isPresent();
        }
        if (enemyBedNearby) return null;

        // Check if we have endstone
        int endstoneCount = mod.getItemStorage().getItemCountInventoryOnly(Items.END_STONE);
        if (endstoneCount < 1) return null;

        // Check if teammate is too close to bed (covering already?)
        Optional<Entity> teammateAtBed = mod.getEntityTracker().getClosestEntity(
                bedPos.toCenterPos(),
                entity -> entity instanceof PlayerEntity player
                        && inOurTeam(player)
                        && !player.equals(mod.getPlayer())
                        && player.getPos().isInRange(bedPos.toCenterPos(), 2.5),
                PlayerEntity.class);
        if (teammateAtBed.isPresent()) {
            if (bedCoverTeammateTimeoutTicks < BED_COVER_TEAMMATE_TIMEOUT_TICKS) {
                bedCoverTeammateTimeoutTicks++;
                setDebugState("Bed cover: teammate nearby, waiting");
                return null; // Wait, don't cover
            }
            // Timeout passed, cover anyway
        } else {
            bedCoverTeammateTimeoutTicks = 0;
        }

        // Find a position around the bed that needs a block
        BlockPos coverPos = findBedCoverPosition(mod);
        if (coverPos != null) {
            setDebugState("Covering bed with endstone at " + coverPos.toShortString());
            return new PlaceBlockTask(coverPos, new Block[]{Blocks.END_STONE});
        }
        return null;
    }

    private BlockPos findBedCoverPosition(AltoClef mod) {
        // Cover bed from all sides + top. Bed is 2 blocks, so check around bedPos and bedPos+facing
        // We don't know facing, so check a 3x3x3 shell around bedPos
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // bed itself
                    BlockPos check = bedPos.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(check).isAir()) {
                        candidates.add(check);
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        // Prioritize: top first, then sides
        candidates.sort((a, b) -> {
            // Top (y > bedPos.y) first
            int aAbove = a.getY() > bedPos.getY() ? 0 : 1;
            int bAbove = b.getY() > bedPos.getY() ? 0 : 1;
            if (aAbove != bAbove) return aAbove - bAbove;
            // Then closest
            double aDist = a.getSquaredDistance(mod.getPlayer().getPos());
            double bDist = b.getSquaredDistance(mod.getPlayer().getPos());
            return Double.compare(aDist, bDist);
        });

        return candidates.get(0);
    }

    // ========== Block counting ==========

    private int getBlockCount(AltoClef mod) {
        int count = mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.WOOL);
        count += mod.getItemStorage().getItemCountInventoryOnly(Items.END_STONE);
        count += mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        count += mod.getItemStorage().getItemCountInventoryOnly(Items.DIRT);
        return count;
    }

    // ========== Shopping ==========

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

    public List<Item> itemsToBuy(AltoClef mod) {
        List<Item> shoplist = new ArrayList<>();
        // Priority: wool > sword > boots > bow > arrows
        if (mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.WOOL) < 64) {
            shoplist.addAll(List.of(ItemHelper.WOOL));
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.IRON_SWORD)) {
            shoplist.add(Items.IRON_SWORD);
        }
        if (!StorageHelper.isArmorEquipped(Items.IRON_BOOTS)) {
            shoplist.add(Items.IRON_BOOTS);
        }
        // Bow and arrows are lower priority now
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.BOW)) {
            shoplist.add(Items.BOW);
        }
        if (mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.ARROWS) < 64) {
            shoplist.addAll(List.of(ItemHelper.ARROWS));
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

    // ========== Helpers ==========

    public boolean inOurTeam(PlayerEntity player) {
        int color = getHelmetColor(player);
        return ourColor == color && color != -1;
    }

    protected boolean isValidEnemy(Entity entity) {
        return entity instanceof PlayerEntity player && !inOurTeam(player)
                && MurderMysteryTask.isValidPlayerMM(player);
    }

    public boolean isValidTrader(Entity villager, AltoClef mod) {
        return villager.isAlive() && villager.getName() != null
                && villager.getName().getString().toLowerCase().contains("магазин");
    }

    public static Slot getSlotShopBW(AltoClef mod, boolean chooseCategories, Item... checkItem) {
        Iterable<Slot> slots = Slot.getCurrentScreenSlots();
        if (AltoClef.inGame() && mod.getPlayer() != null && slots != null) {
            for (Slot slot : slots) {
                int windowSlot = slot.getWindowSlot();
                // Skip player inventory slots — only look at shop container slots
                if (!chooseCategories && slot.isSlotInPlayerInventory()) continue;
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

    // ========== Color maps & detection ==========

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
        BEDWARS_COLORS.put("white", 0xFFFFFF);
        BEDWARS_COLORS.put("black", 0x1D1D21);
        BEDWARS_COLORS.put("brown", 0x835432);
        BEDWARS_COLORS.put("lime", 0x80FF00);
        BEDWARS_COLORS.put("magenta", 0xC74EBD);
        BEDWARS_COLORS.put("light_gray", 0x9D9D97);
        BEDWARS_COLORS.put("purple", 0x8932B8);
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
        BEDWARS_BED_COLORS.put("white", Blocks.WHITE_BED);
        BEDWARS_BED_COLORS.put("black", Blocks.BLACK_BED);
        BEDWARS_BED_COLORS.put("brown", Blocks.BROWN_BED);
        BEDWARS_BED_COLORS.put("lime", Blocks.LIME_BED);
        BEDWARS_BED_COLORS.put("magenta", Blocks.MAGENTA_BED);
        BEDWARS_BED_COLORS.put("light_gray", Blocks.LIGHT_GRAY_BED);
        BEDWARS_BED_COLORS.put("purple", Blocks.PURPLE_BED);
    }

    public String getClosestColorName(int rgb) {
        if (rgb == -1) return "Unknown";

        int r1 = ColorHelperVer.getRed(rgb);
        int g1 = ColorHelperVer.getGreen(rgb);
        int b1 = ColorHelperVer.getBlue(rgb);

        String closestColorName = "Unknown";
        double minDistance = Double.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : BEDWARS_COLORS.entrySet()) {
            int colorRgb = entry.getValue();
            int r2 = ColorHelperVer.getRed(colorRgb);
            int g2 = ColorHelperVer.getGreen(colorRgb);
            int b2 = ColorHelperVer.getBlue(colorRgb);

            double distance = Math.sqrt(Math.pow(r1 - r2, 2) + Math.pow(g1 - g2, 2) + Math.pow(b1 - b2, 2));
            if (distance < minDistance) {
                minDistance = distance;
                closestColorName = entry.getKey();
            }
        }
        return closestColorName;
    }

    public int getHelmetColor(PlayerEntity player) {
        //#if MC >= 12111
        //$$ for (ItemStack itemStack : java.util.List.of(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS), player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET))) {
        //#else
        for (ItemStack itemStack : player.getArmorItems()) {
        //#endif
            if (itemStack.isOf(Items.LEATHER_HELMET)) {
                return DyedColorComponent.getColor(itemStack, DyedColorComponent.DEFAULT_COLOR);
            }
        }
        return -1;
    }

    // ========== Task boilerplate ==========

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BedWarsTask;
    }

    @Override
    protected String toDebugString() {
        String mode = aggroMode ? " [AGGRO]" : "";
        return "Playing Bed Wars"
                + ((ourColorName.isBlank()) ? "" : ": team " + ourColorName)
                + mode;
    }
}

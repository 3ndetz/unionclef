package adris.altoclef.trackers;

import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.PlayerCollidedWithEntityEvent;
import adris.altoclef.mixins.PersistentProjectileEntityAccessor;
import adris.altoclef.trackers.blacklisting.EntityLocateBlacklist;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Predicate;

/**
 * Keeps track of entities so we can search/grab them.
 */
@SuppressWarnings("rawtypes")
public class EntityTracker extends Tracker {

    private final HashMap<Item, List<ItemEntity>> itemDropLocations = new HashMap<>();
    /** ItemEntities the sweep saw, and how many passed the grounded test. Read as et=seen/grounded.
     *  drop=2415/0 says the tracker reports no drops while rcon sees three on the floor; these two
     *  separate "the sweep never runs" from "the grounded test rejects them". */
    public static volatile int etItemsSeen, etItemsGrounded;
    /** Traced item entities; see the note at the sweep. */
    private static volatile int etTrace = 0;
    private final HashMap<Class, List<Entity>> entityMap = new HashMap<>();

    private final List<Entity> closeEntities = new ArrayList<>();
    private final List<LivingEntity> hostiles = new ArrayList<>();

    private final List<CachedProjectile> projectiles = new ArrayList<>();

    private final HashMap<String, PlayerEntity> playerMap = new HashMap<>();
    private final HashMap<String, Vec3d> playerLastCoordinates = new HashMap<>();

    private final EntityLocateBlacklist entityBlacklist = new EntityLocateBlacklist();
    private int _blacklistCleanupCounter = 0;
    private static final int BLACKLIST_CLEANUP_INTERVAL = 200; // ~10 seconds

    private final HashMap<PlayerEntity, List<Entity>> entitiesCollidingWithPlayerAccumulator = new HashMap<>();
    private final HashMap<PlayerEntity, HashSet<Entity>> entitiesCollidingWithPlayer = new HashMap<>();

    public EntityTracker(TrackerManager manager) {
        super(manager);

        // Listen for player collisions
        EventBus.subscribe(PlayerCollidedWithEntityEvent.class, evt -> registerPlayerCollision(evt.player, evt.other));
    }

    /**
     * Squash a class that may have subclasses into one distinguishable class type.
     * For ease of use.
     *
     * @param type: An entity class that may have a 'simpler' class to squash to
     * @return what the given entity class should be read as/catalogued as.
     */
    private static Class squashType(Class type) {
        // Squash types for ease of use
        if (PlayerEntity.class.isAssignableFrom(type)) {
            return PlayerEntity.class;
        }
        return type;
    }

    private void registerPlayerCollision(PlayerEntity player, Entity entity) {
        if (!entitiesCollidingWithPlayerAccumulator.containsKey(player)) {
            entitiesCollidingWithPlayerAccumulator.put(player, new ArrayList<>());
        }
        entitiesCollidingWithPlayerAccumulator.get(player).add(entity);
    }

    public boolean isCollidingWithPlayer(PlayerEntity player, Entity entity) {
        return entitiesCollidingWithPlayer.containsKey(player) && entitiesCollidingWithPlayer.get(player).contains(entity);
    }

    public boolean isCollidingWithPlayer(Entity entity) {
        return isCollidingWithPlayer(mod.getPlayer(), entity);
    }

    public Optional<ItemEntity> getClosestItemDrop(Item... items) {
        return getClosestItemDrop(mod.getPlayer().getPos(), items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3d position, Item... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3d position, ItemTarget... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Predicate<ItemEntity> acceptPredicate, Item... items) {
        return getClosestItemDrop(mod.getPlayer().getPos(), acceptPredicate, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3d position, Predicate<ItemEntity> acceptPredicate, Item... items) {
        ensureUpdated();
        ItemTarget[] tempTargetList = new ItemTarget[items.length];
        for (int i = 0; i < items.length; ++i) {
            tempTargetList[i] = new ItemTarget(items[i], 9999999);
        }
        return getClosestItemDrop(position, acceptPredicate, tempTargetList);
    }

    /**
     * WHY THE SELECTION IS COUNTED SEPARATELY FROM THE TRACKING.
     *
     * <p>pickup_pit reads {@code et=1224/1224} -- the tracker held the drop, grounded, on every
     * tick -- while the caller read {@code drop=1224/0}: asked 1224 times, given a drop ZERO
     * times. Both cannot be innocent, and three separate readings of this file failed to say
     * which. So the junction counts itself: how many candidates were in the map, how many the
     * blacklist removed, and how many times a drop was actually handed back.
     *
     * <p>Read as {@code idrop=asked/noneTracked/blacklisted/returned}. noneTracked means
     * itemDropped() said the map holds nothing for these targets; blacklisted means it held
     * something and the unreachable set removed it. Those are different bugs.
     */
    public static volatile int idAsked, idNoneTracked, idBlacklisted, idReturned;

    /** The last drop chosen: cost, depth, how many were considered, and the runner-up. */
    public static volatile String idDropPick = "-";
    /** Drops chosen that lay more than eight blocks BELOW the bot. */
    public static volatile int idDeepPicks;
    /** Of those, the ones taken while OTHER drops were on offer -- i.e. depth actually won. */
    public static volatile int idDeepBeatOthers;

    public Optional<ItemEntity> getClosestItemDrop(Vec3d position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return Optional.empty();
        }
        idAsked++;
        if (!itemDropped(targets)) {
            idNoneTracked++;
            return Optional.empty();
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        float runnerUp = Float.POSITIVE_INFINITY;
        int considered = 0;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                for (ItemEntity entity : itemDropLocations.get(item)) {
                    if (entityBlacklist.unreachable(entity)) { idBlacklisted++; continue; }
                    if (!entity.getStack().getItem().equals(item)) continue;
                    if (!acceptPredicate.test(entity)) continue;

                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, entity.getPos());
                    considered++;
                    if (cost < minCost) {
                        runnerUp = minCost;
                        minCost = cost;
                        closestEntity = entity;
                    } else if (cost < runnerUp) {
                        runnerUp = cost;
                    }
                }
            }
        }
        if (closestEntity != null) {
            idReturned++;
            // IS THE DEEP DROP WINNING, OR IS IT THE ONLY ONE? Those want opposite fixes, and the
            // heuristic cannot tell them apart: calculateGenericHeuristic prices a DESCENT at about
            // 4.8 ticks a block against 23 for a climb, because it treats going down as a fall --
            // and the bot cannot fall through rock, it has to mine or find a way. A twenty-minute
            // run was already lost to a drop 34 blocks out and 24 DOWN.
            // Record what was chosen, how deep, how many were considered and what the runner-up
            // cost, so a price is only changed if the price turns out to be the problem.
            try {
                double dyPick = closestEntity.getY() - position.y;
                idDropPick = String.format(java.util.Locale.ROOT,
                        "%s cost=%.0f dy=%+.1f of=%d next=%s",
                        net.minecraft.registry.Registries.ITEM.getId(
                                closestEntity.getStack().getItem()).getPath(),
                        minCost, dyPick, considered,
                        runnerUp == Float.POSITIVE_INFINITY ? "-"
                                : String.format(java.util.Locale.ROOT, "%.0f", runnerUp));
                if (dyPick < -8.0) {
                    idDeepPicks++;
                    // ⛔ THE WHOLE QUESTION IN ONE COUNTER. A deep pick with alternatives means
                    // the heuristic CHOSE the depth and the price is wrong; a deep pick with
                    // of=1 means it was the only drop known and repricing would change
                    // nothing. The first sample read of=1, and one sample is not a rate.
                    if (considered > 1) idDeepBeatOthers++;
                }
            } catch (Throwable ignored) {
                // an instrument must never be the thing that breaks a run
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public Optional<Entity> getClosestEntity(Class... entityTypes) {
        return getClosestEntity(mod.getPlayer().getPos(), entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3d position, Class... entityTypes) {
        return this.getClosestEntity(position, (entity) -> true, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Predicate<Entity> acceptPredicate, Class... entityTypes) {
        return getClosestEntity(mod.getPlayer().getPos(), acceptPredicate, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3d position, Predicate<Entity> acceptPredicate, Class... entityTypes) {
        Entity closestEntity = null;
        double minCost = Float.POSITIVE_INFINITY;
        for (Class toFind : entityTypes) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                if (entityMap.containsKey(toFind)) {
                    for (Entity entity : entityMap.get(toFind)) {
                        // Don't accept entities that no longer exist
                        if (entityBlacklist.unreachable(entity)) continue;
                        if (!entity.isAlive()) continue;
                        if (!acceptPredicate.test(entity)) continue;
                        double cost = entity.squaredDistanceTo(position);
                        if (cost < minCost) {
                            minCost = cost;
                            closestEntity = entity;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public boolean itemDropped(Item... items) {
        ensureUpdated();
        for (Item item : items) {
            if (itemDropLocations.containsKey(item)) {
                // Find a non-blacklisted item
                for (ItemEntity entity : itemDropLocations.get(item)) {
                    if (!entityBlacklist.unreachable(entity)) return true;
                }
            }
        }
        return false;
    }

    public boolean itemDropped(ItemTarget... targets) {
        ensureUpdated();
        for (ItemTarget target : targets) {
            if (itemDropped(target.getMatches())) return true;
        }
        return false;
    }

    public List<ItemEntity> getDroppedItems() {
        ensureUpdated();
        return itemDropLocations.values().stream().reduce(new ArrayList<>(), (result, drops) -> {
            result.addAll(drops);
            return result;
        });
    }

    public boolean entityFound(Predicate<Entity> shouldAccept, Class... types) {
        ensureUpdated();
        for (Class type : types) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (Entity entity : entityMap.getOrDefault(type, Collections.emptyList())) {
                    if (shouldAccept.test(entity))
                        return true;
                }
            }
        }
        return false;
    }

    public boolean entityFound(Class... types) {
        return entityFound(check -> true, types);
    }

    public <T extends Entity> List<T> getTrackedEntities(Class<T> type) {
        ensureUpdated();
        if (!entityFound(type)) {
            return Collections.emptyList();
        }
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            //noinspection unchecked
            return (List<T>) entityMap.get(type);
        }
    }

    /**
     * Gets all entities that are within our interact range
     */
    public List<Entity> getCloseEntities() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return closeEntities;
        }
    }

    /**
     * Gets a list of projectiles that we've cached/stored information about.
     */
    public List<CachedProjectile> getProjectiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return projectiles;
        }
    }

    public List<LivingEntity> getHostiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return hostiles;
        }
    }

    /**
     * Is a player loaded/within render distance?
     *
     * @param name Username on a multiplayer server
     */
    public boolean isPlayerLoaded(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return playerMap.containsKey(name);
        }
    }

    /**
     * Get where we last saw a player, if we saw them at all.
     *
     * @return Username on a multiplayer server.
     */
    public Optional<Vec3d> getPlayerMostRecentPosition(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return Optional.ofNullable(playerLastCoordinates.getOrDefault(name, null));
        }
    }

    /**
     * Gets the player entity corresponding to a username, if they're loaded/within render distance.
     *
     * @param name Username on a multiplayer server.
     */
    public Optional<PlayerEntity> getPlayerEntity(String name) {
        if (isPlayerLoaded(name)) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                return Optional.of(playerMap.get(name));
            }
        }
        return Optional.empty();
    }

    /**
     * Tells the entity tracker that we were unable to reach this entity.
     */
    public void requestEntityUnreachable(Entity entity) {
        entityBlacklist.blackListItem(mod, entity, 3);
    }

    /**
     * Whether we have decided that this entity is unreachable.
     */
    public boolean isEntityReachable(Entity entity) {
        return !entityBlacklist.unreachable(entity);
    }

    @Override
    protected synchronized void updateState() {
        // Periodic blacklist cleanup to prevent memory leak from stale entries
        if (_blacklistCleanupCounter++ > BLACKLIST_CLEANUP_INTERVAL) {
            _blacklistCleanupCounter = 0;
            entityBlacklist.cleanupStale();
        }
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            itemDropLocations.clear();
            entityMap.clear();
            closeEntities.clear();
            projectiles.clear();
            hostiles.clear();
            playerMap.clear();
            if (MinecraftClient.getInstance().world == null) return;

            // Store/Register All accumulated player collisions for this frame.
            entitiesCollidingWithPlayer.clear();
            for (Map.Entry<PlayerEntity, List<Entity>> collisions : entitiesCollidingWithPlayerAccumulator.entrySet()) {
                entitiesCollidingWithPlayer.put(collisions.getKey(), new HashSet<>());
                entitiesCollidingWithPlayer.get(collisions.getKey()).addAll(collisions.getValue());
            }
            entitiesCollidingWithPlayerAccumulator.clear();

            // Loop through all entities and track 'em
            for (Entity entity : MinecraftClient.getInstance().world.getEntities()) {

                // Catalogue based on type. Some types may get "squashed" or combined into one.
                Class type = entity.getClass();
                type = squashType(type);

                //noinspection ConstantConditions
                if (entity == null || !entity.isAlive()) continue;

                // Don't catalogue our own player.
                if (type == PlayerEntity.class && entity.equals(mod.getPlayer())) continue;

                if (!entityMap.containsKey(type)) {
                    entityMap.put(type, new ArrayList<>());
                }
                entityMap.get(type).add(entity);

                if (mod.getControllerExtras().inRange(entity)) {
                    closeEntities.add(entity);
                }

                if (entity instanceof ItemEntity ientity) {
                    etItemsSeen++;
                    // ONE ITEM, FULLY DESCRIBED. All six ground checks say no for 161 entities in a
                    // run, which cannot be true of a drop lying on a stone floor -- so either
                    // isSolidBlock is lying here or the drops are not where they are assumed to be.
                    // Position and the four block states it consults, once.
                    if (etTrace < 4) {
                        etTrace++;
                        net.minecraft.util.math.BlockPos bp = ientity.getBlockPos();
                        Debug.logMessage("ETITEM " + ientity.getStack().getItem()
                                + " pos=" + String.format("%.2f,%.2f,%.2f", ientity.getX(), ientity.getY(), ientity.getZ())
                                + " block=" + bp.getX() + "," + bp.getY() + "," + bp.getZ()
                                + " onGround=" + ientity.isOnGround()
                                + " self=" + WorldHelper.isSolidBlock(bp)
                                + " d1=" + WorldHelper.isSolidBlock(bp.down())
                                + " d2=" + WorldHelper.isSolidBlock(bp.down(2))
                                + " d3=" + WorldHelper.isSolidBlock(bp.down(3)));
                    }
                    Item droppedItem = ientity.getStack().getItem();

                    // Only cared about GROUNDED item entities -- AND THE BLOCK DIRECTLY BELOW COUNTS.
                    // The fallbacks looked two and three blocks down and skipped the one cell that
                    // actually decides it: down(1), the floor the item is resting on. On the client
                    // a dropped item bobs and isOnGround() is unreliable, so on any thin floor --
                    // the bench arena is exactly one layer with air beneath -- every test failed and
                    // the drop was never tracked at all.
                    // Measured on mine_stone: drop=2426/0, the tracker asked 2426 times whether any
                    // drop existed and answering no every time, while rcon saw three item entities
                    // lying in that arena. The pickup task therefore never started, the pack stayed
                    // empty, and the whole playthrough ladder stalled one rung above it.
                    // AND THE ITEM'S OWN CELL, because a resting drop sinks INTO the floor block.
                    // et=178/0 measured it: the sweep saw 178 item entities and not one passed this
                    // test, even with down() added. A dropped item settles a few hundredths BELOW
                    // the surface, so flooring its Y lands on the floor block ITSELF -- down() is
                    // then the air beneath the floor, and down(2)/down(3) deeper air still.
                    if (ientity.isOnGround() || ientity.isTouchingWater()
                            || WorldHelper.isSolidBlock(ientity.getBlockPos())
                            || WorldHelper.isSolidBlock(ientity.getBlockPos().down())
                            || WorldHelper.isSolidBlock(ientity.getBlockPos().down(2))
                            || WorldHelper.isSolidBlock(ientity.getBlockPos().down(3))) {
                        etItemsGrounded++;
                        if (!itemDropLocations.containsKey(droppedItem)) {
                            itemDropLocations.put(droppedItem, new ArrayList<>());
                        }
                        itemDropLocations.get(droppedItem).add(ientity);
                    }
                }
                if (entity instanceof MobEntity) {
                    if (EntityHelper.isAngryAtPlayer(mod, entity)) {

                        // Check if the mob is facing us or is close enough
                        boolean closeEnough = entity.isInRange(mod.getPlayer(), 26);

                        //Debug.logInternal("TARGET: " + hostile.is);
                        if (closeEnough) {
                            hostiles.add((LivingEntity) entity);
                        }
                    }
                } else if (entity instanceof ProjectileEntity projEntity) {
                    if (!mod.getBehaviour().shouldAvoidDodgingProjectile(entity)) {
                        CachedProjectile proj = new CachedProjectile();

                        boolean inGround = false;
                        // Get projectile "inGround" variable
                        if (entity instanceof PersistentProjectileEntity) {
                            //#if MC < 12111
                            inGround = ((PersistentProjectileEntityAccessor) entity).isInGround();
                            //#endif
                        }

                        // Ignore some of the harlmess projectiles
                        if (projEntity instanceof FishingBobberEntity || projEntity instanceof EnderPearlEntity || projEntity instanceof ExperienceBottleEntity)
                            continue;

                        if (!inGround) {
                            proj.position = projEntity.getPos();
                            proj.velocity = projEntity.getVelocity();
                            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.ARROW_GRAVITY_ACCEL : 0;
                            proj.projectileType = projEntity.getClass();
                            proj.entityId = projEntity.getId();
                            projectiles.add(proj);
                        }
                    }
                } else if (entity instanceof PlayerEntity player) {
                    String name = player.getName().getString();
                    playerMap.put(name, player);
                    playerLastCoordinates.put(name, player.getPos());
                }
            }
        }
    }

    /**
     * Add a projectile from event (instant detection, before next tick poll).
     * Filters out own projectiles, ground-stuck, and harmless types.
     */
    public boolean addProjectile(ProjectileEntity projEntity) {
        CachedProjectile proj = new CachedProjectile();

        boolean inGround = false;
        if (projEntity instanceof PersistentProjectileEntity) {
            //#if MC < 12111
            inGround = ((PersistentProjectileEntityAccessor) projEntity).isInGround();
            //#endif
        }

        // Ignore harmless projectiles
        if (projEntity instanceof FishingBobberEntity || projEntity instanceof EnderPearlEntity || projEntity instanceof ExperienceBottleEntity)
            return false;

        // Ignore own projectiles
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && projEntity.getOwner() == mc.player)
            return false;

        if (!inGround) {
            proj.position = projEntity.getPos();
            proj.velocity = projEntity.getVelocity();
            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.ARROW_GRAVITY_ACCEL : 0;
            proj.projectileType = projEntity.getClass();
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                projectiles.add(proj);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void reset() {
        // Dirty clears everything else.
        entityBlacklist.clear();
    }
}

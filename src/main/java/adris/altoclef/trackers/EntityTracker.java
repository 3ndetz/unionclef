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
    private final HashMap<Class, List<Entity>> entityMap = new HashMap<>();

    private final List<Entity> closeEntities = new ArrayList<>();
    private final List<LivingEntity> hostiles = new ArrayList<>();

    private final List<CachedProjectile> projectiles = new ArrayList<>();

    private final HashMap<String, PlayerEntity> playerMap = new HashMap<>();
    private final HashMap<String, Vec3d> playerLastCoordinates = new HashMap<>();

    private final EntityLocateBlacklist entityBlacklist = new EntityLocateBlacklist();

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

    public Optional<ItemEntity> getClosestItemDrop(Vec3d position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return Optional.empty();
        }
        if (!itemDropped(targets)) {
            return Optional.empty();
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                for (ItemEntity entity : itemDropLocations.get(item)) {
                    if (entityBlacklist.unreachable(entity)) continue;
                    if (!entity.getStack().getItem().equals(item)) continue;
                    if (!acceptPredicate.test(entity)) continue;

                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, entity.getPos());
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = entity;
                    }
                }
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
                    Item droppedItem = ientity.getStack().getItem();

                    // Only cared about GROUNDED item entities
                    if (ientity.isOnGround() || ientity.isTouchingWater() || WorldHelper.isSolidBlock(ientity.getBlockPos().down(2)) || WorldHelper.isSolidBlock(ientity.getBlockPos().down(3))) {
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
                            inGround = ((PersistentProjectileEntityAccessor) entity).isInGround();
                        }

                        // Ignore some of the harlmess projectiles
                        if (projEntity instanceof FishingBobberEntity || projEntity instanceof EnderPearlEntity || projEntity instanceof ExperienceBottleEntity)
                            continue;

                        if (!inGround) {
                            proj.position = projEntity.getPos();
                            proj.velocity = projEntity.getVelocity();
                            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.ARROW_GRAVITY_ACCEL : 0;
                            proj.projectileType = projEntity.getClass();
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
            inGround = ((PersistentProjectileEntityAccessor) projEntity).isInGround();
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

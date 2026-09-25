package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import kaptainwutax.tungsten.helpers.PlayerFit;
import kaptainwutax.tungsten.path.RouteHazards;
import kaptainwutax.tungsten.task.PillarTask;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Fight endermen from the top of a three-block pillar.
 *
 * <p>WHY. An enderman hits for 7 on normal and was most of the nether deaths (7 of 10 from
 * rung-ender, 2026-09-25; the n43 run lost 12 health in 23 s pillaring after one on a ledge while
 * it hit). The answer is geometry, not reflexes. 1.21 melee lands only when the mob's attack box
 * touches the target's hitbox, and that box is the mob's own hitbox widened sideways
 * (sqrt(2.04) - 0.6 each side) but NOT upwards. An enderman is 2.9 tall, so standing on ground it
 * cannot touch a body whose feet are 3 blocks up; the player, eyes at +4.62, reaches the top of an
 * enderman at the pillar's foot from about 1.8 blocks away (reach 3).
 *
 * <p>A site is refused if anything solid within two blocks sits at feet level or up to +3: an
 * enderman standing on it would be level enough to hit (an entity may stand with its centre 0.3
 * past its block's edge, so a support two cells away still puts it within its ~1.43 reach).
 *
 * <p>Tried first and dropped: a 3x3 roof at feet + 2. Real, ray-traced placement cannot build it
 * from the ground -- a block at +2 needs a face to click, the only faces are the tops of +1 blocks,
 * and those are above the eyes (+1.62). Measured: the build queue deferred every +2 cell, site after
 * site, while the bot walked west a block per attempt.
 *
 * <p>{@link #holding()} tells MobDefense and the stuck detector to leave the body where it is.
 */
public class EndermanShelterTask extends Task {

    /** Blocks the pillar needs. */
    public static final int BLOCKS_NEEDED = 3;
    private static final int HEIGHT = 3;
    private static final int SITE_SEARCH_RADIUS = 6;
    /** How far a calm enderman may be and still be provoked by looking at it. */
    private static final double PROVOKE_RANGE = 40;

    private static volatile boolean holding;
    /**
     * The last pillar's base, kept across task instances. The hunt stops and restarts this task as
     * health and anger change; a fresh instance used to pick a new site from where it stood -- the
     * top of its own pillar -- and build another pillar on it (measured: -60, -57, -54).
     */
    private static volatile BlockPos lastBase;

    /** True while the body climbs or stands on the pillar: MobDefense leaves endermen to us. */
    public static boolean holding() {
        return holding;
    }

    private final Predicate<Entity> accept;
    private BlockPos base;
    private final Set<BlockPos> rejected = new HashSet<>();

    public EndermanShelterTask(Predicate<Entity> accept) {
        this.accept = accept;
    }

    /** Standing on top of the last pillar right now. */
    public static boolean onPillar(AltoClef mod) {
        BlockPos b = lastBase;
        return b != null && onTop(mod, b);
    }

    /** An enderman's melee damage at the world's difficulty (vanilla: 7 on normal, x1.5 hard, easy 4.5). */
    public static float endermanHit(AltoClef mod) {
        return switch (mod.getWorld().getDifficulty()) {
            case PEACEFUL -> 0f;
            case EASY -> 4.5f;
            case NORMAL -> 7f;
            case HARD -> 10.5f;
        };
    }

    /** Two of its hits must not kill before a new enderman is provoked. */
    public static boolean healthyEnoughToProvoke(AltoClef mod) {
        return mod.getPlayer().getHealth() > 2 * endermanHit(mod) + 1;
    }

    /** Can this inventory build the pillar? */
    public static boolean hasBlocks(AltoClef mod) {
        return mod.getItemStorage().getItemCount(buildItems(mod)) >= BLOCKS_NEEDED;
    }

    private static Item[] buildItems(AltoClef mod) {
        List<Item> items = new ArrayList<>(mod.getThrowawayItems());
        items.remove(Items.NETHERRACK);
        items.add(0, Items.NETHERRACK);
        return items.toArray(new Item[0]);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        BlockPos b = lastBase;
        BlockPos feet = mod.getPlayer().getBlockPos();
        base = b != null && feet.getX() == b.getX() && feet.getZ() == b.getZ()
                && feet.getY() >= b.getY() && feet.getY() <= b.getY() + HEIGHT ? b : null;
        holding = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        World world = mod.getWorld();
        holding = false;
        BlockPos feet = mod.getPlayer().getBlockPos();

        if (base != null && onTop(mod, base)) {
            holding = true;
            return fight(mod);
        }
        if (base != null && PillarTask.isActive()) {
            holding = true;
            setDebugState("Pillaring up to fight endermen");
            return null;
        }
        if (base != null && !feet.equals(base) && !siteHolds(world, base)) {
            rejected.add(base);
            base = null;
        }
        if (base == null) {
            base = pickSite(world, feet);
            if (base == null) {
                setDebugState("No site for a pillar here");
                return null;
            }
            Debug.logMessage("Enderman pillar: site " + base.toShortString());
        }
        if (!feet.equals(base)) {
            setDebugState("Going to the pillar site " + base.toShortString());
            return new GetToBlockTask(base);
        }
        if (!mod.getSlotHandler().forceEquipItem(buildItems(mod))) {
            setDebugState("No blocks for a pillar");
            return null;
        }
        PillarTask.startTo(base.getY() + HEIGHT, null, base.getX(), base.getZ());
        lastBase = base;
        holding = true;
        setDebugState("Pillaring up to fight endermen");
        return null;
    }

    private static boolean onTop(AltoClef mod, BlockPos base) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getPlayer().isOnGround() && feet.getX() == base.getX() && feet.getZ() == base.getZ()
                && feet.getY() >= base.getY() + HEIGHT;
    }

    private Task fight(AltoClef mod) {
        EndermanEntity angry = null;
        EndermanEntity calm = null;
        double angryD = Double.MAX_VALUE, calmD = Double.MAX_VALUE;
        for (EndermanEntity e : mod.getEntityTracker().getTrackedEntities(EndermanEntity.class)) {
            if (!e.isAlive() || !accept.test(e)) continue;
            double d = e.squaredDistanceTo(mod.getPlayer());
            if (e.isAngry()) {
                if (d < angryD) { angryD = d; angry = e; }
            } else if (d < calmD && d < PROVOKE_RANGE * PROVOKE_RANGE) {
                calmD = d; calm = e;
            }
        }
        if (angry != null) {
            // ⛔ NEVER STARE AT AN ANGRY ONE THAT IS NOT IN REACH. Vanilla's ChasePlayerGoal stops
            // the enderman's navigation while its target looks at its head: measured on
            // mob_endermen_shelter, a bot that stared had the enderman stand twelve blocks off for
            // the whole run. Look at its feet and it walks up; aim at the top of the body to swing.
            if (LookHelper.canHitEntity(mod, angry)) {
                LookHelper.lookAt(mod, angry.getPos().add(0, angry.getHeight() * 0.75, 0));
                if (mod.getPlayer().getAttackCooldownProgress(0) >= 1) {
                    mod.getControllerExtras().attack(angry);
                }
                setDebugState("Hitting the enderman from the pillar");
            } else {
                LookHelper.lookAt(mod, angry.getPos());
                setDebugState("Waiting on the pillar for the enderman to come in reach");
            }
            return null;
        }
        if (calm != null && healthyEnoughToProvoke(mod)) {
            LookHelper.lookAt(mod, calm.getEyePos());
            setDebugState("Provoking an enderman from the pillar");
            return null;
        }
        setDebugState(healthyEnoughToProvoke(mod) ? "Waiting on the pillar for an enderman"
                : "Healing on the pillar before provoking the next enderman");
        return null;
    }

    /** The nearest acceptable site, the feet cell first. */
    private BlockPos pickSite(World world, BlockPos from) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS; dx++) {
            for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    candidates.add(from.add(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(from)));
        for (BlockPos p : candidates) {
            if (rejected.contains(p)) continue;
            if (siteHolds(world, p)) return p;
        }
        return null;
    }

    /**
     * The body can stand at {@code b}, its column is clear for the pillar and the body on top, and
     * nothing within two blocks lets an enderman stand level with the top.
     */
    static boolean siteHolds(World world, BlockPos b) {
        BlockPos.Mutable s = new BlockPos.Mutable();
        if (!PlayerFit.standable(world, b)) return false;
        if (RouteHazards.lethalColumn(world, b.getX(), b.getY(), b.getZ(), s)) return false;
        for (int dy = 1; dy <= HEIGHT + 2; dy++) {
            if (solid(world, b.up(dy))) return false;
        }
        // Ground all round: the enderman walks to the foot, and the top of a pillar (air on every
        // side) is never taken for a site -- a restarted task once built a second pillar on its first.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !solid(world, b.add(dx, -1, dz))) return false;
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= HEIGHT; dy++) {
                    if (solid(world, b.add(dx, dy, dz))) return false;
                }
            }
        }
        return true;
    }

    private static boolean solid(World world, BlockPos p) {
        return !world.getBlockState(p).getCollisionShape(world, p).isEmpty();
    }

    @Override
    protected void onStop(Task interruptTask) {
        holding = false;
        if (PillarTask.isActive()) PillarTask.stop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EndermanShelterTask;
    }

    @Override
    protected String toDebugString() {
        return "Fighting endermen from a pillar";
    }
}

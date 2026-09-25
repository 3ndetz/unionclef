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
    private static final int SITE_SEARCH_RADIUS = 10;
    /** How far a calm enderman may be and still be provoked by looking at it (vanilla: 64). */
    private static final double PROVOKE_RANGE = 64;
    /** How long a pillar is kept with no enderman in provoking range before the bot moves on. */
    private static final long EMPTY_PILLAR_MS = 20_000;

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
    /** A failed site search is thousands of block reads; do not repeat it every tick. */
    private long noSiteUntilMs;
    private long lastInRangeMs = System.currentTimeMillis();
    /** Last time an angry enderman was in reach, or none was angry. */
    private long lastReachableMs = System.currentTimeMillis();
    /** Where the next site is searched from: the enderman that could not reach the last pillar. */
    private BlockPos searchFrom;
    /** When the pillar at {@code base} was last started, and how many times it has been. */
    private long pillarStartMs;
    private int pillarStarts;
    private static final long PILLAR_STALL_MS = 6_000;
    private static final long UNREACHED_MS = 15_000;
    /** Set when a pillar went unused: walk towards the endermen before building the next one. */
    private boolean relocate;

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
        lastInRangeMs = System.currentTimeMillis();
        lastReachableMs = System.currentTimeMillis();
        relocate = false;
        holding = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        World world = mod.getWorld();
        holding = false;
        BlockPos feet = mod.getPlayer().getBlockPos();

        if (base != null && onTop(mod, base) && !relocate) {
            holding = true;
            pillarStarts = 0;
            return fight(mod);
        }
        if (relocate) {
            // An unused pillar: walk to within 16 of the nearest enderman, then build again there.
            EndermanEntity near = null;
            for (EndermanEntity e : mod.getEntityTracker().getTrackedEntities(EndermanEntity.class)) {
                if (e.isAlive() && accept.test(e) && (near == null
                        || e.squaredDistanceTo(mod.getPlayer()) < near.squaredDistanceTo(mod.getPlayer()))) near = e;
            }
            if (near != null && near.distanceTo(mod.getPlayer()) > 16) {
                setDebugState("No enderman near the pillar: moving towards one");
                return new adris.altoclef.tasks.movement.GetToEntityTask(near, 14);
            }
            relocate = false;
            base = null;
            lastBase = null;
            lastInRangeMs = System.currentTimeMillis();
        }
        if (base != null && (PillarTask.isActive() || pillarStarts > 0)
                && feet.getY() <= base.getY() && System.currentTimeMillis() - pillarStartMs > PILLAR_STALL_MS
                || pillarStarts > 3) {
            // A pillar that does not rise: the same site twice (n47, n49) had the body bobbing on
            // the spot for ten minutes, the pillar restarted every few ticks. Give the site up.
            Debug.logMessage("Enderman pillar: " + base.toShortString() + " does not rise, giving it up");
            if (PillarTask.isActive()) PillarTask.stop();
            rejected.add(base);
            base = null;
            lastBase = null;
            pillarStarts = 0;
            return null;
        }
        if (base != null && PillarTask.isActive()) {
            holding = true;
            adris.altoclef.tasks.movement.CustomBaritoneGoalTask.claimRoute();
            setDebugState("Pillaring up to fight endermen");
            return null;
        }
        if (base != null && !feet.equals(base) && !siteHolds(world, base)) {
            rejected.add(base);
            base = null;
        }
        if (base == null && System.currentTimeMillis() >= noSiteUntilMs) {
            base = pickSite(world, searchFrom != null ? searchFrom : feet);
            searchFrom = null;
            if (base == null) {
                noSiteUntilMs = System.currentTimeMillis() + 5000;
            } else {
                Debug.logMessage("Enderman pillar: site " + base.toShortString());
            }
        }
        if (base == null) {
            // Standing still is the one wrong answer: fight as before and look again later.
            setDebugState("No site for a pillar here: fighting on the ground");
            return new KillEntitiesTask(accept, EndermanEntity.class);
        }
        if (!feet.equals(base)) {
            setDebugState("Going to the pillar site " + base.toShortString());
            return new GetToBlockTask(base);
        }
        // A plant in the feet cell (warped fungus, roots) has no collision but catches the pillar's
        // placement ray: n47 and n49 bobbed on a fungus for ten minutes each. FastPlanner breaks
        // these before its own pillar legs (RealPlacement.obstructsPillarRay); do the same.
        if (kaptainwutax.tungsten.helpers.RealPlacement.obstructsPillarRay(world, base)) {
            setDebugState("Clearing the plant off the pillar site");
            return new adris.altoclef.tasks.construction.DestroyBlockTask(base);
        }
        if (!mod.getSlotHandler().forceEquipItem(buildItems(mod))) {
            setDebugState("No blocks for a pillar");
            return null;
        }
        if (pillarStarts == 0 || !base.equals(lastBase)) {
            pillarStartMs = System.currentTimeMillis();
            pillarStarts = 0;
        }
        pillarStarts++;
        PillarTask.startTo(base.getY() + HEIGHT, null, base.getX(), base.getZ());
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.claimRoute();
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
        if (System.currentTimeMillis() - lastInRangeMs > EMPTY_PILLAR_MS) {
            relocate = true;
            return null;
        }
        EndermanEntity angry = null;
        EndermanEntity calm = null;
        double angryD = Double.MAX_VALUE, calmD = Double.MAX_VALUE;
        for (EndermanEntity e : mod.getEntityTracker().getTrackedEntities(EndermanEntity.class)) {
            if (!e.isAlive() || !accept.test(e)) continue;
            double d = e.squaredDistanceTo(mod.getPlayer());
            if (e.isAngry()) {
                if (d < angryD) { angryD = d; angry = e; }
            } else if (d < calmD && d < PROVOKE_RANGE * PROVOKE_RANGE && mod.getPlayer().canSee(e)) {
                // Vanilla angers an enderman only when the player sees it and looks within about
                // 0.025/distance of its eyes; one behind a fungus cannot be provoked (n44 spent most
                // of its samples "provoking" and got 3 pearls in 16 minutes).
                calmD = d; calm = e;
            }
        }
        if (angry != null || calm != null) lastInRangeMs = System.currentTimeMillis();
        if (angry == null || LookHelper.canHitEntity(mod, angry)) lastReachableMs = System.currentTimeMillis();
        if (System.currentTimeMillis() - lastReachableMs > UNREACHED_MS) {
            // An angry enderman that cannot path to the pillar's foot stands where it is for good
            // (n46: nine minutes "waiting for it to come in reach"). Build the next pillar where it
            // can: next to it. Fighting it on the ground instead was tried and is what put n48 in
            // the lava sea, knocked off a rim in the melee.
            searchFrom = angry.getBlockPos();
            rejected.add(base);
            lastReachableMs = System.currentTimeMillis();
            base = null;
            lastBase = null;
            return null;
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
                // ±1 only: a site two blocks up or down is usually one the route has to dig to, and
                // the dig is how n45 opened a lava pocket on itself (dead at 4:21).
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
     * The body can stand at {@code b}, its column is clear for the pillar and the body on top, it is
     * not the top of a pillar, and no surface within two blocks lets an enderman stand with its feet
     * between +1 and +4 -- the only heights from which its attack box meets a body standing on +3.
     *
     * <p>⛔ "NOTHING SOLID WITHIN TWO BLOCKS UP TO +3" WAS THE FIRST FORM, AND A WARPED FOREST HAS NO
     * SUCH PLACE: 30 minutes from rung-ender logged "No site for a pillar" and 1 pearl in 9 minutes.
     * A wall, a trunk or a canopy is harmless unless something can stand on it at those heights.
     */
    static boolean siteHolds(World world, BlockPos b) {
        BlockPos.Mutable s = new BlockPos.Mutable();
        if (!PlayerFit.standable(world, b)) return false;
        if (RouteHazards.lethalColumn(world, b.getX(), b.getY(), b.getZ(), s)) return false;
        for (int dy = 1; dy <= HEIGHT + 2; dy++) {
            if (solid(world, b.up(dy))) return false;
        }
        // Ground on every side, at most two blocks down: stepping off the pillar must be a short
        // drop, never a cliff (n48: a pillar by a drop, the body stepped off its top and fell 29
        // blocks into lava). This also keeps the top of a pillar from passing as a site.
        // Two blocks out, not one: n50 pillared on a warped fungus crown at y 82 whose first ring
        // was solid wart and whose second was a thirty-block drop, was hit on top and fell.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos n = b.add(dx, 0, dz);
                if (!solid(world, n) && !solid(world, n.down()) && !solid(world, n.down(2))
                        && !solid(world, n.down(3))) return false;
                if (RouteHazards.lethalColumn(world, n.getX(), n.getY(), n.getZ(), s) && !solid(world, n)) return false;
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int h = 1; h <= HEIGHT + 1; h++) {
                    BlockPos feet = b.add(dx, h, dz);
                    if (solid(world, feet.down()) && !solid(world, feet)
                            && !solid(world, feet.up()) && !solid(world, feet.up(2))) return false;
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

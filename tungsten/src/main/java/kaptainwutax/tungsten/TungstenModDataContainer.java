package kaptainwutax.tungsten;

import kaptainwutax.tungsten.path.PathExecutor;
import kaptainwutax.tungsten.path.PathFinder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

public class TungstenModDataContainer {
	public static PlayerEntity player;
    public static final boolean LOG_DEBUG_DATA = false;
    public static PathExecutor EXECUTOR;

    /**
     * When the MINER owns the aim and the keys, stamped by the task that is breaking a block.
     *
     * <p>The placer already has this, as EXECUTOR.placingNow, and the walker yields to it. The
     * miner never did, and a recording shows what that costs: the camera swings toward the walker's
     * waypoint while the block being broken is somewhere else, and the body shuffles because
     * DestroyBlockTask holds MOVE_BACK within two blocks of its target while the walker holds
     * MOVE_FORWARD in the same tick. Two writers, last one wins, every tick.
     *
     * <p>A TIMESTAMP RATHER THAN A BOOLEAN, deliberately. A flag that is set and then not cleared
     * -- because the task was interrupted, or threw, or simply stopped being ticked -- would freeze
     * the walker for the rest of the run. This expires on its own a few ticks after the miner stops
     * refreshing it, so the failure mode is "yielded slightly too long" rather than "never walks
     * again".
     */
    public static volatile long minerAimUntilMs = 0L;

    /** True while a block-breaking task has claimed the aim and the keys this tick. */
    public static boolean minerOwnsAim() {
        return System.currentTimeMillis() < minerAimUntilMs;
    }
	public static PathFinder PATHFINDER = new PathFinder();

    /** Safe check — EXECUTOR may be null before TungstenMod.onInitializeClient */
    public static boolean isExecutorRunning() {
        return EXECUTOR != null && EXECUTOR.isRunning();
    }
	public static World world;
    public static boolean ignoreFallDamage = true;

    /**
     * Does the SEARCH get to ignore fall damage? Ask this, never the raw field.
     *
     * <p>⛔ The field above is {@code true} by default, which switches off a fall-damage guard that
     * is otherwise complete and correct: PathFinder.checkForFallDamage walks the parent chain,
     * rejects any segment steeper than 2.75 blocks, and already exempts water, slime columns and
     * slime bounces. All of it sits behind an early return that is taken on every search.
     *
     * <p>Measured on the playthrough, 2026-08-18: the bot descends from y=134 to y=60 and takes
     * 25.3 damage, of which the damage witness attributes FOUR events out of four to no living
     * entity at all -- dw=4/25.3/27.08/30.05/4/1, and unattributedHits is documented as "falls,
     * void, fire". One run reached wood tools and spent its last 150 seconds chipping stone on
     * 1.5 hp; the next reached no rung at all.
     *
     * <p>WHY NO COURSE CAUGHT IT: nav_descend offers drops of 1, 2 and 3 blocks. Every one of them
     * is under the 2.75 threshold, so the course is green whether the guard runs or not. A course
     * that only offers safe drops cannot test the guard against unsafe ones -- the same blind spot
     * as a course that hands the bot a weapon already in its hand.
     *
     * <p>Flagged rather than flipped, so the two arms can be interleaved: pathAvoidsFallDamage=true
     * turns the guard ON. Default stays OFF until the measurement says otherwise.
     */
    public static boolean searchIgnoresFallDamage() {
        return fallGuardRelaxed || (ignoreFallDamage && !TungstenConfig.get().pathAvoidsFallDamage);
    }

    /**
     * Set for the RETRY of a search that exhausted its open set with the guard active.
     *
     * <p>SAFETY-FIRST, NOT SAFETY-ONLY. Turning the guard on and leaving it on was measured and it
     * does not work: two playthrough runs froze at exactly (71.7, 120.0, -70.7) with items=0 for
     * their whole duration, path driver entered 460 times, movement queue advanced ZERO steps. The
     * bot took no fall damage because it never moved. That is the whole reason the field above
     * shipped as true -- the guard is correct and, alone, it is fatal on real terrain.
     *
     * <p>A human does not stand on a hill for five minutes rather than take three hearts. Prefer a
     * route with no fall damage; if there is NO such route, take the damaging one. So the guard
     * runs first, and an exhausted search retries once with it relaxed.
     */
    public static volatile boolean fallGuardRelaxed = false;
    public static GameRenderer gameRenderer = null;

    /**
     * Need-fulfiller hook (TUNGSTEN_ALTOCLEF_API stage 1): registered by
     * altoclef at init. Called on the client thread while the executor mines
     * a block so the inventory side can equip the best tool for it. Tungsten
     * itself never touches the inventory.
     */
    public static java.util.function.BiConsumer<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState> equipToolHook = null;

    /**
     * Equip-a-build-block hook: altoclef equips a cheap placeable block into the main
     * hand when the tungsten executor is about to PAVE a planned bridge (mirror of
     * equipToolHook for breaking). Tungsten never touches the inventory itself.
     */
    public static Runnable equipBlockHook = null;

    /**
     * Protection hook: returns false when the inventory/brain side (altoclef)
     * forbids mining a position — bridges its break-avoiders/protected zones
     * into BreakRules. Registered at altoclef init.
     */
    public static java.util.function.Predicate<net.minecraft.util.math.BlockPos> canBreakHook = null;

    /**
     * Protection hook: returns false when altoclef forbids PLACING at a position
     * — bridges its place-avoiders/protected zones into PlaceRules. Registered
     * at altoclef init (symmetric to canBreakHook).
     */
    public static java.util.function.Predicate<net.minecraft.util.math.BlockPos> canPlaceHook = null;
}

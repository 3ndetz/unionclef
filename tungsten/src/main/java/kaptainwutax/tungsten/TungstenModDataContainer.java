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
	public static PathFinder PATHFINDER = new PathFinder();

    /** Safe check — EXECUTOR may be null before TungstenMod.onInitializeClient */
    public static boolean isExecutorRunning() {
        return EXECUTOR != null && EXECUTOR.isRunning();
    }
	public static World world;
    public static boolean ignoreFallDamage = true;
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

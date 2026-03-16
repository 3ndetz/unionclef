package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.WorldView;

/**
 * Thin wrapper over FollowEntityTask for following players by name.
 *
 * Handles:
 *   - Name-based player lookup
 *   - Re-discovery when the player entity disappears (disconnect/teleport/chunk unload)
 *   - Follow radius / push mode
 *
 * All routing logic lives in FollowEntityTask.
 */
public class FollowPlayerTask {

    private static String  targetName   = null;
    private static Entity  targetEntity = null;
    private static double  followRadius = 0.0;
    private static boolean active       = false;

    /** Start following with push mode (never stop). */
    public static void start(String name) {
        start(name, 0.0);
    }

    /** Start following, stopping when within followRadius blocks (0 = push mode). */
    public static void start(String name, double followRadius) {
        targetName   = name;
        targetEntity = null;
        FollowPlayerTask.followRadius = followRadius;
        active = true;

        double closeEnough = followRadius > 0 ? followRadius : 0;
        FollowEntityTask.startManaged(closeEnough);

        String suffix = followRadius > 0 ? " (radius=" + followRadius + ")" : " (push mode)";
        Debug.logMessage("Following player: " + name + suffix);
    }

    public static void stop() {
        active       = false;
        targetName   = null;
        targetEntity = null;
        FollowEntityTask.stop();
    }

    public static boolean isActive()        { return active; }
    public static String  getTargetName()   { return targetName; }
    public static double  getFollowRadius() { return followRadius; }

    /**
     * Called every game tick from MixinClientPlayerEntity.
     * Only handles player re-discovery; routing is done by FollowEntityTask.tick().
     */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        tryRediscover();

        // keep FollowEntityTask in sync with our current entity
        FollowEntityTask.updateTarget(targetEntity);
    }

    /** Scan nearby players each tick to (re-)find target by name. */
    private static void tryRediscover() {
        if (targetName == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        // Validate existing entity is still in the CURRENT world.
        // After reconnect, old entity reference may be stale even if !isRemoved().
        if (targetEntity != null && !targetEntity.isRemoved()
                && mc.world.getEntityById(targetEntity.getId()) == targetEntity) {
            return; // still valid in current world
        }

        // Re-scan world for target player by name
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(targetName)) {
                targetEntity = p;
                return;
            }
        }
        targetEntity = null;
    }
}

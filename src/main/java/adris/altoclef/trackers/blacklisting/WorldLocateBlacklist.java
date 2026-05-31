package adris.altoclef.trackers.blacklisting;

import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class WorldLocateBlacklist extends AbstractObjectBlacklist<BlockPos> {

    private static final double STALE_DISTANCE_SQ = 256.0 * 256.0;

    @Override
    protected Vec3d getPos(BlockPos item) {
        return WorldHelper.toVec3d(item);
    }

    @Override
    protected boolean isStale(BlockPos pos) {
        // Expire entries for blocks very far from the player.
        // Without this, the blacklist grows unbounded in long sessions.
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            double dx = player.getX() - (pos.getX() + 0.5);
            double dy = player.getY() - (pos.getY() + 0.5);
            double dz = player.getZ() - (pos.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz > STALE_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }
}

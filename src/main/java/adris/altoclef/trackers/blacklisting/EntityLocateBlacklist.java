package adris.altoclef.trackers.blacklisting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

public class EntityLocateBlacklist extends AbstractObjectBlacklist<Entity> {

    private static final double STALE_DISTANCE_SQ = 128.0 * 128.0; // ~8 chunks

    @Override
    protected Vec3d getPos(Entity item) {
        return item.getPos();
    }

    @Override
    protected boolean isStale(Entity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return true;
        }
        // Expire entries for entities that are very far from the player.
        // Over long sessions, the blacklist accumulates entries for entities
        // the player walked past but never interacted with again.
        var player = MinecraftClient.getInstance().player;
        if (player != null && entity.squaredDistanceTo(player) > STALE_DISTANCE_SQ) {
            return true;
        }
        return false;
    }
}

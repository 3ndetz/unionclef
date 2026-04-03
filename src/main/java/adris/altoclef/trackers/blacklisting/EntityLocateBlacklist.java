package adris.altoclef.trackers.blacklisting;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

public class EntityLocateBlacklist extends AbstractObjectBlacklist<Entity> {
    @Override
    protected Vec3d getPos(Entity item) {
        //#if MC >= 12111
        //$$ return item.getEntityPos();
        //#else
        return item.getPos();
        //#endif
    }
}

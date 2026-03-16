package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.fabricmc.loader.impl.lib.sat4j.core.Vec;
import net.minecraft.entity.Entity;
import adris.altoclef.mixins.EntityAccessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public class EntityVer {


    @Pattern
    public boolean isInNetherPortal(Entity entity) {
        //#if MC <= 12006
        //$$ return ((EntityAccessor)entity).isInNetherPortal();
        //#else
        return adris.altoclef.multiversion.entity.EntityHelper.isInNetherPortal(entity);
        //#endif
    }

    @Pattern
    public int getPortalCooldown(Entity entity) {
        //#if MC >= 12001
        return entity.getPortalCooldown();
        //#else
        //$$ return ((EntityAccessor) entity).getPortalCooldown();
        //#endif
    }


    @Pattern
    public BlockPos getLandingPos(Entity entity) {
        //#if MC >= 11701
        return entity.getSteppingPos();
        //#else
        //$$ return ((adris.altoclef.mixins.EntityAccessor) entity).invokeGetLandingPos();
        //#endif
    }

    @Pattern
    private static float getPitch(Entity player) {
        //#if MC >= 11701
        return player.getPitch();
        //#else
        //$$ return player.pitch;
        //#endif
    }

    @Pattern
    private static float getYaw(Entity player) {
        //#if MC >= 11701
        return player.getYaw();
        //#else
        //$$ return player.yaw;
        //#endif
    }

    @Pattern
    private static void setPitch(Entity player, float value) {
        //#if MC >= 11701
        player.setPitch(value);
        //#else
        //$$ player.pitch = value;
        //#endif
    }

    @Pattern
    private static void setYaw(Entity player, float value) {
        //#if MC >= 11701
        player.setYaw(value);
        //#else
        //$$ player.yaw = value;
        //#endif
    }

    @Pattern
    private static Vec3d getEyePos(Entity entity) {
        //#if MC >= 11701
        return entity.getEyePos();
        //#else
        //$$ return adris.altoclef.multiversion.entity.EntityHelper.getEyePos(entity);
        //#endif
    }

    @Pattern
    private static ChunkPos getChunkPos(Entity entity) {
        //#if MC >= 11701
        return entity.getChunkPos();
        //#else
        //$$ return adris.altoclef.multiversion.entity.EntityHelper.getChunkPos(entity);
        //#endif
    }

    @Pattern
    private static int getBlockX(Entity entity) {
        //#if MC >= 11701
        return entity.getBlockX();
        //#else
        //$$ return adris.altoclef.multiversion.entity.EntityHelper.getBlockX(entity);
        //#endif
    }

    @Pattern
    private static int getBlockY(Entity entity) {
        //#if MC >= 11701
        return entity.getBlockY();
        //#else
        //$$ return adris.altoclef.multiversion.entity.EntityHelper.getBlockY(entity);
        //#endif
    }

    @Pattern
    private static int getBlockZ(Entity entity) {
        //#if MC >= 11701
        return entity.getBlockZ();
        //#else
        //$$ return adris.altoclef.multiversion.entity.EntityHelper.getBlockZ(entity);
        //#endif
    }

}

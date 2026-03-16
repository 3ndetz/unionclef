package adris.altoclef.eventbus.events.multiplayer;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

public class TeleportEvent {
    public PlayerEntity entity;
    public Vec3d from;
    public Vec3d to;

    public TeleportEvent(PlayerEntity entity, Vec3d tpFrom, Vec3d tpTo) {
        this.entity = entity;
        this.to = tpTo;
        this.from = tpFrom;
    }
}

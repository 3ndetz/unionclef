package adris.altoclef.eventbus.events;

import net.minecraft.entity.player.PlayerEntity;

public class SneakEvent {
    public PlayerEntity entity;
    public boolean sneak;

    public SneakEvent(PlayerEntity entity, boolean sneaking) {
        this.entity = entity;
        this.sneak = sneaking;
    }
}

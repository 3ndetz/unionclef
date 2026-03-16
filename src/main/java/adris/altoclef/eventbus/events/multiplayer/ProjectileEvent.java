package adris.altoclef.eventbus.events.multiplayer;

import net.minecraft.entity.projectile.ProjectileEntity;

public class ProjectileEvent {
    public ProjectileEntity entity;
    public boolean sticked;

    public ProjectileEvent(ProjectileEntity entity, boolean sticked) {
        this.entity = entity;
        this.sticked = sticked;
    }
}

package adris.altoclef.eventbus.events;

import net.minecraft.entity.damage.DamageSource;

public class EntityDamageEvent {
    public String attacker;

    public EntityDamageEvent(DamageSource source, float amount) {
    }
}

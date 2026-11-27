package adris.altoclef.mixins;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read vanilla's attack timer (> 0: doAttack refuses) at the time of a guarded tap. */
@Mixin(MinecraftClient.class)
public interface AttackCooldownAccessor {
    @Accessor("attackCooldown")
    int altoclef$getAttackCooldown();
}

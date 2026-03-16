package kaptainwutax.tungsten.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface AccessorLivingEntity {

	@Accessor
	boolean getJumping();

	@Accessor
	int getJumpingCooldown();

}

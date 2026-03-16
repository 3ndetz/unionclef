package kaptainwutax.tungsten.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.Vec3d;

@Mixin(Entity.class)
public interface AccessorEntity {

	@Accessor
	Vec3d getMovementMultiplier();

	@Accessor
	boolean getFirstUpdate();

	@Accessor
	Set<TagKey<Fluid>> getSubmergedFluidTag();

}

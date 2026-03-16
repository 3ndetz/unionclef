/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package shredder.launch.mixins;

import shredder.utils.accessor.IFireworkRocketEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.OptionalInt;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.World;

@Mixin(FireworkRocketEntity.class)
public abstract class MixinFireworkRocketEntity extends Entity implements IFireworkRocketEntity {

    @Shadow
    @Final
    private static TrackedData<OptionalInt> SHOOTER_ENTITY_ID;

    @Shadow
    private LivingEntity shooter;

    @Shadow
    public abstract boolean wasShotByEntity();

    private MixinFireworkRocketEntity(World level) {
        super(EntityType.FIREWORK_ROCKET, level);
    }

    @Override
    public LivingEntity getBoostedEntity() {
        if (this.wasShotByEntity() && this.shooter == null) {
            final Entity entity = this.getWorld().getEntityById(this.dataTracker.get(SHOOTER_ENTITY_ID).getAsInt());
            if (entity instanceof LivingEntity) {
                this.shooter = (LivingEntity) entity;
            }
        }
        return this.shooter;
    }
}

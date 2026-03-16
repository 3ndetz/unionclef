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

import shredder.api.utils.BlockOptionalMeta;
import net.minecraft.loot.context.LootContext;
import net.minecraft.registry.ReloadableRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LootContext.Builder.class)
public abstract class MixinLootContextBuilder {

    @Shadow public abstract ServerWorld getWorld();

    @Redirect(method = "create", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getReloadableRegistries()Lnet/minecraft/registry/ReloadableRegistries$Lookup;"))
    private ReloadableRegistries.Lookup create(MinecraftServer instance) {
        if (instance != null) {
            return instance.getReloadableRegistries();
        }
        if (getWorld() instanceof BlockOptionalMeta.ServerLevelStub sls) {
            return sls.holder();
        }
        return null;
    }

}

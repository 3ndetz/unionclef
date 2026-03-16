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

import shredder.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class MixinPlayerController implements IPlayerControllerMP {

    @Accessor("breakingBlock")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("breakingBlock")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("currentBreakingPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Invoker("syncSelectedSlot")
    @Override
    public abstract void callSyncCurrentPlayItem();

    @Accessor("blockBreakingCooldown")
    @Override
    public abstract void setDestroyDelay(int destroyDelay);
}

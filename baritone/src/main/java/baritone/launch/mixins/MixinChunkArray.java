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

package baritone.launch.mixins;

import baritone.utils.accessor.IChunkArray;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

@Mixin(targets = "net.minecraft.client.world.ClientChunkManager$ClientChunkMap")
public abstract class MixinChunkArray implements IChunkArray {
    @Final
    @Shadow
    AtomicReferenceArray<WorldChunk> chunks;
    @Final
    @Shadow
    int radius;

    @Final
    @Shadow
    private int diameter;
    @Shadow
    volatile int centerChunkX;
    @Shadow
    volatile int centerChunkZ;
    @Shadow
    int loadedChunkCount;

    @Shadow
    abstract boolean isInRadius(int x, int z);

    @Shadow
    abstract int getIndex(int x, int z);

    @Shadow
    protected abstract void set(int index, WorldChunk chunk);

    @Override
    public int centerX() {
        return centerChunkX;
    }

    @Override
    public int centerZ() {
        return centerChunkZ;
    }

    @Override
    public int viewDistance() {
        return radius;
    }

    @Override
    public AtomicReferenceArray<WorldChunk> getChunks() {
        return chunks;
    }

    @Override
    public void copyFrom(IChunkArray other) {
        centerChunkX = other.centerX();
        centerChunkZ = other.centerZ();

        AtomicReferenceArray<WorldChunk> copyingFrom = other.getChunks();
        for (int k = 0; k < copyingFrom.length(); ++k) {
            WorldChunk chunk = copyingFrom.get(k);
            if (chunk != null) {
                ChunkPos chunkpos = chunk.getPos();
                if (isInRadius(chunkpos.x, chunkpos.z)) {
                    int index = getIndex(chunkpos.x, chunkpos.z);
                    if (chunks.get(index) != null) {
                        throw new IllegalStateException("Doing this would mutate the client's REAL loaded chunks?!");
                    }
                    set(index, chunk);
                }
            }
        }
    }
}

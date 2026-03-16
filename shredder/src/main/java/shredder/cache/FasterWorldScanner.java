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

package shredder.cache;

import shredder.api.cache.ICachedWorld;
import shredder.api.cache.IWorldScanner;
import shredder.api.utils.BetterBlockPos;
import shredder.api.utils.BlockOptionalMeta;
import shredder.api.utils.BlockOptionalMetaLookup;
import shredder.api.utils.IPlayerContext;
import shredder.utils.accessor.IPalettedContainer;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.IdListPalette;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.SingularPalette;
import net.minecraft.world.chunk.WorldChunk;

public enum FasterWorldScanner implements IWorldScanner {
    INSTANCE;

    private static final BlockState[] PALETTE_REGISTRY_SENTINEL = new BlockState[0];

    @Override
    public List<BlockPos> scanChunkRadius(IPlayerContext ctx, BlockOptionalMetaLookup filter, int max, int yLevelThreshold, int maxSearchRadius) {
        assert ctx.world() != null;
        if (maxSearchRadius < 0) {
            throw new IllegalArgumentException("chunkRange must be >= 0");
        }
        return scanChunksInternal(ctx, filter, getChunkRange(ctx.playerFeet().x >> 4, ctx.playerFeet().z >> 4, maxSearchRadius), max);
    }

    @Override
    public List<BlockPos> scanChunk(IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos, int max, int yLevelThreshold) {
        Stream<BlockPos> stream = scanChunkInternal(ctx, filter, pos);
        if (max >= 0) {
            stream = stream.limit(max);
        }
        return stream.collect(Collectors.toList());
    }

    @Override
    public int repack(IPlayerContext ctx) {
        return this.repack(ctx, 40);
    }

    @Override
    public int repack(IPlayerContext ctx, int range) {
        ChunkManager chunkProvider = ctx.world().getChunkManager();
        ICachedWorld cachedWorld = ctx.worldData().getCachedWorld();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - range;
        int minZ = playerChunkZ - range;
        int maxX = playerChunkX + range;
        int maxZ = playerChunkZ + range;

        int queued = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                WorldChunk chunk = chunkProvider.getWorldChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    queued++;
                    cachedWorld.queueForPacking(chunk);
                }
            }
        }

        return queued;
    }

    // ordered in a way that the closest blocks are generally first
    public static List<ChunkPos> getChunkRange(int centerX, int centerZ, int chunkRadius) {
        List<ChunkPos> chunks = new ArrayList<>();
        // spiral out
        chunks.add(new ChunkPos(centerX, centerZ));
        for (int i = 1; i < chunkRadius; i++) {
            for (int j = 0; j <= i; j++) {
                chunks.add(new ChunkPos(centerX - j, centerZ - i));
                if (j != 0) {
                    chunks.add(new ChunkPos(centerX + j, centerZ - i));
                    chunks.add(new ChunkPos(centerX - j, centerZ + i));
                }
                chunks.add(new ChunkPos(centerX + j, centerZ + i));
                if (j != i) {
                    chunks.add(new ChunkPos(centerX - i, centerZ - j));
                    chunks.add(new ChunkPos(centerX + i, centerZ - j));
                    if (j != 0) {
                        chunks.add(new ChunkPos(centerX - i, centerZ + j));
                        chunks.add(new ChunkPos(centerX + i, centerZ + j));
                    }
                }
            }
        }
        return chunks;
    }

    private List<BlockPos> scanChunksInternal(IPlayerContext ctx, BlockOptionalMetaLookup lookup, List<ChunkPos> chunkPositions, int maxBlocks) {
        assert ctx.world() != null;
        try {
            // p -> scanChunkInternal(ctx, lookup, p)
            Stream<BlockPos> posStream = chunkPositions.parallelStream().flatMap(p -> scanChunkInternal(ctx, lookup, p));
            if (maxBlocks >= 0) {
                // WARNING: this can be expensive if maxBlocks is large...
                // see limit's javadoc
                posStream = posStream.limit(maxBlocks);
            }
            return posStream.collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private Stream<BlockPos> scanChunkInternal(IPlayerContext ctx, BlockOptionalMetaLookup lookup, ChunkPos pos) {
        ChunkManager chunkProvider = ctx.world().getChunkManager();
        // if chunk is not loaded, return empty stream
        if (!chunkProvider.isChunkLoaded(pos.x, pos.z)) {
            return Stream.empty();
        }

        long chunkX = (long) pos.x << 4;
        long chunkZ = (long) pos.z << 4;

        int playerSectionY = (ctx.playerFeet().y - ctx.world().getBottomY()) >> 4;

        return collectChunkSections(lookup, chunkProvider.getWorldChunk(pos.x, pos.z, false), chunkX, chunkZ, playerSectionY).stream();
    }


    private List<BlockPos> collectChunkSections(BlockOptionalMetaLookup lookup, WorldChunk chunk, long chunkX, long chunkZ, int playerSection) {
        // iterate over sections relative to player
        List<BlockPos> blocks = new ArrayList<>();
        int chunkY = chunk.getBottomY();
        ChunkSection[] sections = chunk.getSectionArray();
        int l = sections.length;
        int i = playerSection - 1;
        int j = playerSection;
        for (; i >= 0 || j < l; ++j, --i) {
            if (j < l) {
                visitSection(lookup, sections[j], blocks, chunkX, chunkY + j * 16, chunkZ);
            }
            if (i >= 0) {
                visitSection(lookup, sections[i], blocks, chunkX, chunkY + i * 16, chunkZ);
            }
        }
        return blocks;
    }

    private void visitSection(BlockOptionalMetaLookup lookup, ChunkSection section, List<BlockPos> blocks, long chunkX, int sectionY, long chunkZ) {
        if (section == null || section.isEmpty()) {
            return;
        }

        PalettedContainer<BlockState> sectionContainer = section.getBlockStateContainer();
        //this won't work if the PaletteStorage is of the type EmptyPaletteStorage
        if (((IPalettedContainer<BlockState>) sectionContainer).getStorage() == null) {
            return;
        }

        Palette<BlockState> palette = ((IPalettedContainer<BlockState>) sectionContainer).getPalette();

        if (palette instanceof SingularPalette) {
            // single value palette doesn't have any data
            if (lookup.has(palette.get(0))) {
                // TODO this is 4k hits, maybe don't return all of them?
                for (int x = 0; x < 16; ++x) {
                    for (int y = 0; y < 16; ++y) {
                        for (int z = 0; z < 16; ++z) {
                            blocks.add(new BlockPos(
                                (int) chunkX + x,
                                sectionY + y,
                                (int) chunkZ + z
                            ));
                        }
                    }
                }
            }
            return;
        }

        boolean[] isInFilter = getIncludedFilterIndices(lookup, palette);
        if (isInFilter.length == 0) {
            return;
        }

        PaletteStorage array = ((IPalettedContainer<BlockState>) section.getBlockStateContainer()).getStorage();
        long[] longArray = array.getData();
        int arraySize = array.getSize();
        int bitsPerEntry = array.getElementBits();
        long maxEntryValue = (1L << bitsPerEntry) - 1L;

        for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
            long l = longArray[i];
            for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                int value = (int) ((l >> offset) & maxEntryValue);
                if (isInFilter[value]) {
                    //noinspection DuplicateExpressions
                    blocks.add(new BlockPos(
                        (int) chunkX + ((idx & 255) & 15),
                        sectionY + (idx >> 8),
                        (int) chunkZ + ((idx & 255) >> 4)
                    ));
                }
            }
        }
    }

    private boolean[] getIncludedFilterIndices(BlockOptionalMetaLookup lookup, Palette<BlockState> palette) {
        boolean commonBlockFound = false;
        BlockState[] paletteMap = getPalette(palette);

        if (paletteMap == PALETTE_REGISTRY_SENTINEL) {
            return getIncludedFilterIndicesFromRegistry(lookup);
        }

        int size = paletteMap.length;

        boolean[] isInFilter = new boolean[size];

        for (int i = 0; i < size; i++) {
            BlockState state = paletteMap[i];
            if (lookup.has(state)) {
                isInFilter[i] = true;
                commonBlockFound = true;
            } else {
                isInFilter[i] = false;
            }
        }

        if (!commonBlockFound) {
            return new boolean[0];
        }
        return isInFilter;
    }

    private boolean[] getIncludedFilterIndicesFromRegistry(BlockOptionalMetaLookup lookup) {
        boolean[] isInFilter = new boolean[Block.STATE_IDS.size()];

        for (BlockOptionalMeta bom : lookup.blocks()) {
            for (BlockState state : bom.getAllBlockStates()) {
                isInFilter[Block.STATE_IDS.getRawId(state)] = true;
            }
        }

        return isInFilter;
    }

    /**
     * cheats to get the actual map of id -> blockstate from the various palette implementations
     */
    private static BlockState[] getPalette(Palette<BlockState> palette) {
        if (palette instanceof IdListPalette) {
            // copying the entire registry is not nice so we treat it as a special case
            return PALETTE_REGISTRY_SENTINEL;
        } else {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            palette.writePacket(buf);
            int size = buf.readVarInt();
            BlockState[] states = new BlockState[size];
            for (int i = 0; i < size; i++) {
                BlockState state = Block.STATE_IDS.get(buf.readVarInt());
                assert state != null;
                states[i] = state;
            }
            return states;
        }
    }
}

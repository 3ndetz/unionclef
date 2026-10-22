package kaptainwutax.tungsten.helpers;

import kaptainwutax.tungsten.TungstenMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/** Private copied block data of one loaded chunk; contains no live world or entity. */
public final class ChunkBlockSnapshot {
    private final ChunkPos position;
    private final int bottomY, height;
    private final PalettedContainer<BlockState>[] sections;

    private ChunkBlockSnapshot(ChunkPos position, int bottomY, int height,
                               PalettedContainer<BlockState>[] sections) {
        this.position = position;
        this.bottomY = bottomY;
        this.height = height;
        this.sections = sections;
    }

    /**
     * Capture on the client thread before dispatching a reader. Port the
     * client-owned loaded chunk capture in baritone/utils/BlockStateInterface.java:
     * 62-76 and section lookup:168-174, but copy block contents as well.
     * Vanilla PalettedContainer.copy copies storage and palette; no mutable
     * container or array is exposed. Empty sections are captured as empty,
     * rather than consulting their live emptiness later.
     *
     * <p>Copy only block containers. ChunkSection.copy also carries biome and
     * mod-specific metadata; the retained Lithium diagnostic shows its section
     * setter cannot safely mutate a copied section's random-tick metadata.
     * These snapshots never execute section setters or world mutations.
     */
    @SuppressWarnings("unchecked")
    public static ChunkBlockSnapshot capture(WorldChunk chunk) {
        if (TungstenMod.mc == null || !TungstenMod.mc.isOnThread())
            throw new IllegalStateException("Chunk block capture requires the client thread");
        ChunkSection[] live = chunk.getSectionArray();
        PalettedContainer<BlockState>[] copies = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[live.length];
        for (int i = 0; i < live.length; i++) {
            ChunkSection section = live[i];
            if (section != null && !section.isEmpty())
                copies[i] = section.getBlockStateContainer().copy();
        }
        return new ChunkBlockSnapshot(chunk.getPos(), chunk.getBottomY(), chunk.getHeight(), copies);
    }

    /** Read a cell of this captured chunk; outside its captured height is air. */
    public BlockState getBlockState(int x, int y, int z) {
        if ((x >> 4) != position.x || (z >> 4) != position.z)
            throw new IllegalArgumentException("Cell belongs to a different captured chunk");
        int relativeY = y - bottomY;
        if (relativeY < 0 || relativeY >= height) return Blocks.AIR.getDefaultState();
        PalettedContainer<BlockState> section = sections[relativeY >> 4];
        return section == null ? Blocks.AIR.getDefaultState() : section.get(x & 15, y & 15, z & 15);
    }
}

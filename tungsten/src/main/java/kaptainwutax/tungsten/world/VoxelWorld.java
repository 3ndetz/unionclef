package kaptainwutax.tungsten.world;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;

public class VoxelWorld implements WorldView {

    public final WorldView parent;
    private Long2ObjectMap<VoxelChunk> chunks = new Long2ObjectOpenHashMap<>();

    public VoxelWorld(WorldView parent) {
        this.parent = parent;
        this.chunks.defaultReturnValue(VoxelChunk.EMPTY);
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY() - this.getBottomY();
        int z = pos.getZ();
        long id = 0L;
        return this.chunks.get(id).getBlockState(x, y, z);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY() - this.getBottomY();
        int z = pos.getZ();
        long id = 0L;
        return this.chunks.get(id).getFluidState(x, y, z);
    }

    public void setBlockAndFluidState(BlockPos pos, BlockState block, FluidState fluid) {
        int x = pos.getX();
        int y = pos.getY() - this.getBottomY();
        int z = pos.getZ();
        long id = 0L;
        VoxelChunk chunk = this.chunks.computeIfAbsent(id, i -> new VoxelChunk());
        chunk.setBlockState(x, y, z, block);
        chunk.setFluidState(x, y, z, fluid);
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, Box box) {
        return this.parent.getEntityCollisions(entity, box);
    }

    //========================================================================================================//

    @Nullable
    @Override
    public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        return this.parent.getChunk(chunkX, chunkZ, leastStatus, create);
    }

    @Override
    public int getTopY(Heightmap.Type heightmap, int x, int z) {
        return this.parent.getTopY(heightmap, x, z);
    }

    @Override
    public int getAmbientDarkness() {
        return this.parent.getAmbientDarkness();
    }

    @Override
    public BiomeAccess getBiomeAccess() {
        return this.parent.getBiomeAccess();
    }

    @Override
    public RegistryEntry<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ) {
        return this.parent.getGeneratorStoredBiome(biomeX, biomeY, biomeZ);
    }

    @Override
    public boolean isClient() {
        return true;
    }

    @Override
    public int getSeaLevel() {
        return this.parent.getSeaLevel();
    }

    @Override
    public DimensionType getDimension() {
        return this.parent.getDimension();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return this.parent.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return this.parent.getLightingProvider();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.parent.getWorldBorder();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.parent.getBlockEntity(pos);
    }

	@Override
	public DynamicRegistryManager getRegistryManager() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public FeatureSet getEnabledFeatures() {
		// TODO Auto-generated method stub
		return null;
	}

}

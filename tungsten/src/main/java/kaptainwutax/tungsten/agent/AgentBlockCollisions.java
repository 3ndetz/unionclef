package kaptainwutax.tungsten.agent;

import com.google.common.collect.AbstractIterator;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.render.Cube;
import kaptainwutax.tungsten.render.Cuboid;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.boss.BossBar.Color;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.CuboidBlockIterator;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import org.jetbrains.annotations.Nullable;

public class AgentBlockCollisions extends AbstractIterator<VoxelShape> {

    private final Box box;
    private final ShapeContext context;
    private final CuboidBlockIterator blockIterator;
    private final BlockPos.Mutable pos;
    private final BlockPos.Mutable neighborPos = new BlockPos.Mutable();
    private final VoxelShape boxShape;
    private final CollisionView world;
    private final boolean forEntity;
    private BlockView chunk;
    private long chunkPos;
    public int scannedBlocks;

    // Fence connection bar shapes (block-local coords, 1.5 blocks tall)
    private static final VoxelShape CONN_NORTH = VoxelShapes.cuboid(0.375, 0, 0,     0.625, 1.5, 0.5);
    private static final VoxelShape CONN_SOUTH = VoxelShapes.cuboid(0.375, 0, 0.5,   0.625, 1.5, 1.0);
    private static final VoxelShape CONN_EAST  = VoxelShapes.cuboid(0.5,   0, 0.375, 1.0,   1.5, 0.625);
    private static final VoxelShape CONN_WEST  = VoxelShapes.cuboid(0,     0, 0.375, 0.5,   1.5, 0.625);

    public AgentBlockCollisions(CollisionView world, Agent agent, Box box) {
        this(world, agent, box, false);
    }

    public AgentBlockCollisions(CollisionView world, Agent agent, Box box, boolean forEntity) {
        this.context = new AgentShapeContext(agent);
        this.pos = new BlockPos.Mutable();
        this.boxShape = VoxelShapes.cuboid(box);
        this.world = world;
        this.box = box;
        this.forEntity = forEntity;
        int i = MathHelper.floor(box.minX - 1.0E-7D) - 1;
        int j = MathHelper.floor(box.maxX + 1.0E-7D) + 1;
        int k = MathHelper.floor(box.minY - 1.0E-7D) - 1;
        int l = MathHelper.floor(box.maxY + 1.0E-7D) + 1;
        int m = MathHelper.floor(box.minZ - 1.0E-7D) - 1;
        int n = MathHelper.floor(box.maxZ + 1.0E-7D) + 1;
        this.blockIterator = new CuboidBlockIterator(i, k, m, j, l, n);
    }

    @Nullable
    private BlockView getChunk(int x, int z) {
        int i = ChunkSectionPos.getSectionCoord(x);
        int j = ChunkSectionPos.getSectionCoord(z);
        long l = ChunkPos.toLong(i, j);

        if(this.chunk != null && this.chunkPos == l) {
            return this.chunk;
        } else {
            BlockView blockView = this.world.getChunkAsView(i, j);
            this.chunk = blockView;
            this.chunkPos = l;
            return blockView;
        }
    }

    private boolean isFenceLike(BlockState state) {
        return state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)
                || state.getBlock() instanceof PaneBlock;
    }

    /**
     * For fence/wall/pane blocks, add connection bars to all adjacent
     * fence-like neighbors regardless of client-side connection state.
     * On ViaVersion servers the real connections may differ.
     */
    private VoxelShape withFenceConnections(VoxelShape original, int x, int y, int z) {
        VoxelShape result = original;

        neighborPos.set(x, y, z - 1);
        if (isFenceLike(world.getBlockState(neighborPos)))
            result = VoxelShapes.union(result, CONN_NORTH);

        neighborPos.set(x, y, z + 1);
        if (isFenceLike(world.getBlockState(neighborPos)))
            result = VoxelShapes.union(result, CONN_SOUTH);

        neighborPos.set(x + 1, y, z);
        if (isFenceLike(world.getBlockState(neighborPos)))
            result = VoxelShapes.union(result, CONN_EAST);

        neighborPos.set(x - 1, y, z);
        if (isFenceLike(world.getBlockState(neighborPos)))
            result = VoxelShapes.union(result, CONN_WEST);

        return result;
    }

    protected VoxelShape computeNext() {
    	while (this.blockIterator.step()) {
			int i = this.blockIterator.getX();
			int j = this.blockIterator.getY();
			int k = this.blockIterator.getZ();
			int l = this.blockIterator.getEdgeCoordinatesCount();
			if (l != 3) {
				BlockView blockView = this.getChunk(i, k);
				if (blockView != null) {
					this.pos.set(i, j, k);
					BlockState blockState = blockView.getBlockState(this.pos);
					if ((!this.forEntity || blockState.shouldSuffocate(blockView, this.pos))
						&& (l != 1 || blockState.exceedsCube())
						&& (l != 2 || blockState.isOf(Blocks.MOVING_PISTON))) {
						VoxelShape voxelShape = blockState.getCollisionShape(this.world, this.pos, this.context);

						// ViaVersion: anvil → treat as full block
						if (TungstenConfig.get().avoidStuckAnvil
								&& blockState.getBlock() instanceof AnvilBlock) {
							voxelShape = VoxelShapes.fullCube();
						}

						// ViaVersion: fence/wall/pane → add connection bars to all adjacent fence-like blocks
						if (TungstenConfig.get().avoidStuckFence && isFenceLike(blockState)) {
							voxelShape = withFenceConnections(voxelShape, i, j, k);
						}

						if (voxelShape == VoxelShapes.fullCube()) {
							if (this.box.intersects((double)i, (double)j, (double)k, (double)i + 1.0, (double)j + 1.0, (double)k + 1.0)) {
								return voxelShape.offset((double)i, (double)j, (double)k);
							}
						} else {
							VoxelShape voxelShape2 = voxelShape.offset((double)i, (double)j, (double)k);
							if (!voxelShape2.isEmpty() && VoxelShapes.matchesAnywhere(voxelShape2, this.boxShape, BooleanBiFunction.AND)) {
								return voxelShape2;
							}
						}
					}
				}
			}
		}

		return this.endOfData();
    }

}

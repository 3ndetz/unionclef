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

import java.util.ArrayDeque;

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

    /** Buffered virtual connection shapes to return before resuming iteration. */
    private final ArrayDeque<VoxelShape> pendingShapes = new ArrayDeque<>(4);

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
     * Queue virtual connection bar shapes for a fence-like block.
     * No VoxelShapes.union — just cheap cuboid creation + box intersection.
     */
    private void enqueueFenceConnections(int bx, int by, int bz) {
        // North (Z-): bar from center to Z=0 edge
        enqueueIfNeighborFence(bx, by, bz, 0.375, 0, 0.625, 0.5, 0, -1);
        // South (Z+): bar from center to Z=1 edge
        enqueueIfNeighborFence(bx, by, bz, 0.375, 0.5, 0.625, 1.0, 0, 1);
        // East (X+): bar from center to X=1 edge
        enqueueIfNeighborFence(bx, by, bz, 0.5, 0.375, 1.0, 0.625, 1, 0);
        // West (X-): bar from center to X=0 edge
        enqueueIfNeighborFence(bx, by, bz, 0, 0.375, 0.5, 0.625, -1, 0);
    }

    private void enqueueIfNeighborFence(int bx, int by, int bz,
            double lMinX, double lMinZ, double lMaxX, double lMaxZ,
            int dx, int dz) {
        neighborPos.set(bx + dx, by, bz + dz);
        if (!isFenceLike(world.getBlockState(neighborPos))) return;

        double minX = bx + lMinX, maxX = bx + lMaxX;
        double minY = by, maxY = by + 1.5;
        double minZ = bz + lMinZ, maxZ = bz + lMaxZ;

        if (!this.box.intersects(minX, minY, minZ, maxX, maxY, maxZ)) return;

        pendingShapes.add(VoxelShapes.cuboid(minX, minY, minZ, maxX, maxY, maxZ));
    }

    protected VoxelShape computeNext() {
        // drain buffered virtual connection shapes first
        if (!pendingShapes.isEmpty()) {
            return pendingShapes.poll();
        }

//        while(true) {
//            if (this.blockIterator.step()) {
//                int i = this.blockIterator.getX();
//                int j = this.blockIterator.getY();
//                int k = this.blockIterator.getZ();
//                int l = this.blockIterator.getEdgeCoordinatesCount();
//
//                if(l == 3) {
//                    continue;
//                }
//
//                /*
//                BlockView blockView = this.getChunk(i, k);
//
//                if(blockView == null) {
//                    continue;
//                }*/
//
//                BlockView blockView = this.world;
//
//                this.pos.set(i, j, k);
//                BlockState blockState = blockView.getBlockState(this.pos);
//                this.scannedBlocks++;
//
//                if(this.forEntity && !blockState.shouldSuffocate(blockView, this.pos) || l == 1 && !blockState.exceedsCube()
//                    || l == 2 && !blockState.isOf(Blocks.MOVING_PISTON)) {
//                    continue;
//                }
//
//                VoxelShape voxelShape = blockState.getCollisionShape(this.world, this.pos, this.context);
//
//                if(voxelShape == VoxelShapes.fullCube()) {
//                    if(!this.box.intersects((double)i, (double)j, (double)k, (double)i + 1.0D, (double)j + 1.0D, (double)k + 1.0D)) {
//                        continue;
//                    }
//
//                    return voxelShape.offset((double)i, (double)j, (double)k);
//                }
//
//                VoxelShape voxelShape2 = voxelShape.offset((double)i, (double)j, (double)k);
//
//                if(!VoxelShapes.matchesAnywhere(voxelShape2, this.boxShape, BooleanBiFunction.AND)) {
//                    continue;
//                }
//
//                return voxelShape2;
//            }
//
//            return this.endOfData();
//        }
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

					// ViaVersion: queue virtual fence connections BEFORE the
					// edge/suffocate filter — fences at scan edges (l==1,
					// !exceedsCube) would otherwise be skipped entirely.
					if (TungstenConfig.get().avoidStuckFence && isFenceLike(blockState)) {
						enqueueFenceConnections(i, j, k);
					}

					if ((!this.forEntity || blockState.shouldSuffocate(blockView, this.pos))
						&& (l != 1 || blockState.exceedsCube())
						&& (l != 2 || blockState.isOf(Blocks.MOVING_PISTON))) {
						VoxelShape voxelShape = blockState.getCollisionShape(this.world, this.pos, this.context);

						// ViaVersion: anvil → treat as full block
						if (TungstenConfig.get().avoidStuckAnvil
								&& blockState.getBlock() instanceof AnvilBlock) {
							voxelShape = VoxelShapes.fullCube();
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

			// drain pending virtual shapes after each block
			if (!pendingShapes.isEmpty()) {
				return pendingShapes.poll();
			}
		}

		return this.endOfData();
    }

}


package kaptainwutax.tungsten.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;

import java.util.Arrays;

public class VoxelChunk {

	public static final VoxelChunk EMPTY = new VoxelChunk() {
		{
			this.blocks = new BlockState[0];
			this.fluids = new FluidState[0];
		}

		@Override
		public BlockState getBlockState(int x, int y, int z) {
			return Blocks.AIR.getDefaultState();
		}

		@Override
		public FluidState getFluidState(int x, int y, int z) {
			return Fluids.EMPTY.getDefaultState();
		}
	};

	public BlockState[] blocks = new BlockState[32 * 32 * 32];
	public FluidState[] fluids = new FluidState[32 * 32 * 32];

	public VoxelChunk() {
		Arrays.fill(this.blocks, Blocks.AIR.getDefaultState());
		Arrays.fill(this.fluids, Fluids.EMPTY.getDefaultState());
	}

	public BlockState getBlockState(int x, int y, int z) {
		return this.blocks[(y & 31) << 10 | (x & 31) << 5 | z & 31];
	}

	public FluidState getFluidState(int x, int y, int z) {
		return this.fluids[(y & 31) << 10 | (x & 31) << 5 | z & 31];
	}

	public void setBlockState(int x, int y, int z, BlockState state) {
		this.blocks[(y & 31) << 10 | (x & 31) << 5 | z & 31] = state;
	}

	public void setFluidState(int x, int y, int z, FluidState state) {
		this.fluids[(y & 31) << 10 | (x & 31) << 5 | z & 31] = state;
	}

}

package adris.altoclef.tasks.construction;

import java.util.Optional;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class ProjectileProtectionWallTask extends Task implements ITaskRequiresGrounded {


	private final AltoClef mod;
    private final TimerGame waitForBlockPlacement = new TimerGame(2);

    private BlockPos targetPlacePos;
	
	public ProjectileProtectionWallTask(AltoClef mod) {
		this.mod = mod;
	}
	
	@Override
	protected void onStart() {
		waitForBlockPlacement.forceElapse();
	}

	@Override
	protected Task onTick() {
		if (targetPlacePos != null && !WorldHelper.isSolidBlock(targetPlacePos)) {
			Optional<adris.altoclef.util.slots.Slot> slot = StorageHelper.getSlotWithThrowawayBlock(this.mod, true);
			if(slot.isPresent()) {
				place(targetPlacePos, Hand.MAIN_HAND, slot.get().getInventorySlot());
				targetPlacePos = null;
				setDebugState(null);
			}
			return null;
		}

		Optional<Entity> sentity = mod.getEntityTracker().getClosestEntity((e) -> {
        	if(e instanceof SkeletonEntity 
        			&& EntityHelper.isAngryAtPlayer(mod, e)
        			&& 
        			(((SkeletonEntity) e).getItemUseTime() > 8)
        			) return true;
        	return false;
        }, SkeletonEntity.class);
        if(sentity.isPresent()) {
    		Vec3d playerPos = mod.getPlayer().getPos();
            Vec3d targetPos = sentity.get().getPos();
    		// Calculate the direction vector towards the target entity
            Vec3d direction = playerPos.subtract(targetPos).normalize();

            // Calculate the new position two blocks away in the direction of the entity
            double x = playerPos.x - 2 * direction.x;
            double y = playerPos.y + direction.y;
            double z = playerPos.z - 2 * direction.z;
            
            targetPlacePos = new BlockPos((int) x, (int) y+1, (int) z);
			setDebugState("Placing at " + targetPlacePos.toString());
			waitForBlockPlacement.reset();
        }
		return null;
	}

	@Override
	protected void onStop(Task interruptTask) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
    public boolean isFinished() {
        assert MinecraftClient.getInstance().world != null;
        
        Optional<Entity> entity = mod.getEntityTracker().getClosestEntity((e) -> {
        	if(e instanceof SkeletonEntity 
        			&& EntityHelper.isAngryAtPlayer(mod, e)
        			&& 
        			(((SkeletonEntity) e).getItemUseTime() > 3)
        			) return true;
        	return false;
        }, SkeletonEntity.class);
        
        return targetPlacePos != null && WorldHelper.isSolidBlock(targetPlacePos) || entity.isEmpty();
    }

	@Override
	protected boolean isEqual(Task other) {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	protected String toDebugString() {
		// TODO Auto-generated method stub
		return "Placing blocks to block projectiles";
	}
	
	public Direction getPlaceSide(BlockPos blockPos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = blockPos.offset(side);
            BlockState state = mod.getWorld().getBlockState(neighbor);

            // Check if neighbour isn't empty
            if (state.isAir() || isClickable(state.getBlock())) continue;

            // Check if neighbour is a fluid
            if (!state.getFluidState().isEmpty()) continue;

            return side;
        }

        return null;
    }
	
	public boolean place(BlockPos blockPos, Hand hand, int slot) {
        if (slot < 0 || slot > 8) return false;
        if (!canPlace(blockPos)) return false;

        Vec3d hitPos = Vec3d.ofCenter(blockPos);

        BlockPos neighbour;
        Direction side = getPlaceSide(blockPos);

        if (side == null) {
        	place(blockPos.down(), hand, slot);
        	return false;
        } else {
            neighbour = blockPos.offset(side);
            hitPos = hitPos.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        }

        BlockHitResult bhr = new BlockHitResult(hitPos, side.getOpposite(), neighbour, false);

        mod.getPlayer().setYaw((float) getYaw(hitPos));
        mod.getPlayer().setPitch((float) getPitch(hitPos));
		swap(slot);

        interact(bhr, hand);


        return true;
    }
    
	
	public static boolean isClickable(Block block) {
        return block instanceof CraftingTableBlock
            || block instanceof AnvilBlock
            || block instanceof ButtonBlock
            || block instanceof AbstractPressurePlateBlock
            || block instanceof BlockWithEntity
            || block instanceof BedBlock
            || block instanceof FenceGateBlock
            || block instanceof DoorBlock
            || block instanceof NoteBlock
            || block instanceof TrapdoorBlock;
    }
	
	public void interact(BlockHitResult blockHitResult, Hand hand) {
        boolean wasSneaking = mod.getPlayer().input.sneaking;
        mod.getPlayer().input.sneaking = false;

        ActionResult result = mod.getController().interactBlock(mod.getPlayer(),hand, blockHitResult);

        if (result.shouldSwingHand()) {
            mod.getPlayer().swingHand(hand);
        }

        mod.getPlayer().input.sneaking = wasSneaking;
    }

	public boolean canPlace(BlockPos blockPos, boolean checkEntities) {
        if (blockPos == null) return false;

        // Check y level
        if (!World.isValid(blockPos) || !AltoClef.getInstance().getWorld().isInBuildLimit(blockPos)) return false;

        // Check if current block is replaceable
        if (!mod.getWorld().getBlockState(blockPos).isReplaceable()) return false;

        // Check if intersects entities
        return !checkEntities || mod.getWorld().canPlace(Blocks.OBSIDIAN.getDefaultState(), blockPos, ShapeContext.absent());
    }

    public boolean canPlace(BlockPos blockPos) {
        return canPlace(blockPos, true);
    }
	
    public boolean swap(int slot) {
        if (slot == PlayerSlot.OFFHAND_SLOT.getInventorySlot()) return true;
        if (slot < 0 || slot > 8) return false;

        mod.getPlayer().getInventory().selectedSlot = slot;
        return true;
    }
    
    public double getYaw(Vec3d pos) {
        return mod.getPlayer().getYaw() + MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(pos.getZ() - mod.getPlayer().getZ(), pos.getX() - mod.getPlayer().getX())) - 90f - mod.getPlayer().getYaw());
    }

    public double getPitch(Vec3d pos) {
        double diffX = pos.getX() - mod.getPlayer().getX();
        double diffY = pos.getY() - (mod.getPlayer().getY() + mod.getPlayer().getEyeHeight(mod.getPlayer().getPose()));
        double diffZ = pos.getZ() - mod.getPlayer().getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mod.getPlayer().getPitch() + MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mod.getPlayer().getPitch());
    }
}
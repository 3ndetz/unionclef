package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.control.Nav;
import adris.altoclef.tasksystem.Task;
import kaptainwutax.tungsten.helpers.PlayerFit;
import kaptainwutax.tungsten.task.FastNavigator;
import net.minecraft.entity.EntityPose;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/** Reach breathable air through the ordinary movement graph, then replenish oxygen. */
public final class GetToAirTask extends Task {
    private long nextSearchTick;

    @Override
    protected void onStart() {
        Nav.cancelAll();
        nextSearchTick = 0;
    }

    /** A standing body must fit and its eyes must be above any water in their cell. */
    private static boolean canBreatheAt(WorldView world, BlockPos pos, double eyeHeight) {
        if (!PlayerFit.bodyFits(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)) return false;
        if (!world.getFluidState(pos).isIn(FluidTags.WATER) && !PlayerFit.standable(world, pos)) return false;
        Vec3d eye = new Vec3d(pos.getX() + 0.5, pos.getY() + eyeHeight, pos.getZ() + 0.5);
        BlockPos eyeCell = BlockPos.ofFloored(eye);
        var fluid = world.getFluidState(eyeCell);
        return !fluid.isIn(FluidTags.WATER) || eye.y >= eyeCell.getY() + fluid.getHeight(world, eyeCell);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (!mod.getPlayer().isSubmergedInWater()) {
            // Keep the recovered air pocket until oxygen is full. The survival
            // chain's existing float input holds the head above the surface.
            if (FastNavigator.isActive()) FastNavigator.stop();
            setDebugState("Replenishing air");
            return null;
        }
        long now = mod.getWorld().getTime();
        if (!FastNavigator.isActive() && now >= nextSearchTick) {
            var world = mod.getWorld();
            double eyes = mod.getPlayer().getEyeHeight(EntityPose.STANDING);
            FastNavigator.startNearest(pos -> canBreatheAt(world, pos, eyes));
            // Rate-limit unsuccessful searches in a sealed or changing cavity.
            nextSearchTick = now + 20;
        }
        setDebugState("Finding a reachable air pocket");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Nav.cancelAll();
    }

    @Override
    public boolean isFinished() {
        var player = AltoClef.getInstance().getPlayer();
        return player == null || (!player.isSubmergedInWater() && player.getAir() >= player.getMaxAir());
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetToAirTask;
    }

    @Override
    protected String toDebugString() {
        return "Reaching breathable air";
    }
}

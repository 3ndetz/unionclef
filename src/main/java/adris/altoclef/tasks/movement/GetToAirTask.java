package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.control.Nav;
import adris.altoclef.tasks.construction.DestroyBlockTask;
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
    /** G100: how far up the column this dig-to-surface scan looks for the cap over the water. */
    private static final int DIG_UP_SCAN = 12;
    private DestroyBlockTask digUp;
    private BlockPos digTarget;

    @Override
    protected void onStart() {
        Nav.cancelAll();
        nextSearchTick = 0;
        digUp = null;
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
        var world = mod.getWorld();
        // ⛔ WHEN THERE IS NO AIR TO SWIM TO, MAKE SOME (G100, 2026-09-18). The second 60-minute
        // run drowned at y=0 mining diamonds: submerged in a flooded pocket capped by stone, this
        // task searched for the nearest EXISTING breathable cell every 20 ticks, found none it
        // could reach, did nothing, and the bot drowned (a zombie finished it). A player breaks
        // straight up and surfaces. So: if the body's own column is capped by a breakable solid
        // before air, dig that cap -- the same move as the reach-a-block miner, aimed up -- and
        // the water then rises into the opened cell for the next swim-up. Only when the column is
        // NOT diggable to air (bedrock, or air is only sideways) does it fall back to the lateral
        // nearest-air search below.
        BlockPos cap = cappedColumnDig(mod, world);
        if (cap != null) {
            if (digUp == null || !cap.equals(digTarget)) {
                digUp = new DestroyBlockTask(cap);
                digTarget = cap;
                // Logged (not just a debug-state string) so the dig-to-surface can actually be
                // measured -- a setDebugState line never reaches latest.log (G100).
                adris.altoclef.Debug.logMessage("GetToAir: digging up to air, cap at " + cap.toShortString());
            }
            if (FastNavigator.isActive()) FastNavigator.stop();
            setDebugState("Digging up to air at " + cap.toShortString());
            return digUp;
        }
        digUp = null; digTarget = null;
        long now = mod.getWorld().getTime();
        if (!FastNavigator.isActive() && now >= nextSearchTick) {
            double eyes = mod.getPlayer().getEyeHeight(EntityPose.STANDING);
            FastNavigator.startNearest(pos -> canBreatheAt(world, pos, eyes));
            // Rate-limit unsuccessful searches in a sealed or changing cavity.
            nextSearchTick = now + 20;
        }
        setDebugState("Finding a reachable air pocket");
        return null;
    }

    /**
     * The block to break to open the body's own column upward to air, or null if the column
     * already reaches air through water (swim up) or cannot be dug to air (break something else).
     *
     * <p>Scans up from the head: water cells are the way up and are passed through; the FIRST
     * non-water cell decides. If it is air/breathable the column is already open (null -- swim). If
     * it is a breakable solid it is the cap over the water and gets returned to dig. If it is
     * unbreakable (bedrock) the column is a dead end and this returns null so the lateral search
     * runs instead.
     */
    private static BlockPos cappedColumnDig(AltoClef mod, WorldView world) {
        BlockPos head = BlockPos.ofFloored(mod.getPlayer().getEyePos());
        for (int i = 1; i <= DIG_UP_SCAN; i++) {
            BlockPos c = head.up(i);
            if (world.getFluidState(c).isIn(FluidTags.WATER)) continue;   // still water: keep rising
            var state = world.getBlockState(c);
            if (state.isAir()) return null;                              // open to air: just swim up
            // A solid cell over the water: dig it if it is breakable (finite, non-negative
            // hardness rules out bedrock), else the column is a dead end -- let the lateral
            // search run instead.
            float hardness = state.getHardness(world, c);
            return hardness >= 0 ? c : null;
        }
        return null;                                                      // all water for 12: swim up
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

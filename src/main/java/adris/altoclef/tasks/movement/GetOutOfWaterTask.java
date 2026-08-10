package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

public class GetOutOfWaterTask extends CustomBaritoneGoalTask{

    private boolean startedShimmying = false;
    private final TimerGame shimmyTaskTimer = new TimerGame(5);

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // get on the surface first
        if (mod.getPlayer().getAir() < mod.getPlayer().getMaxAir() || mod.getPlayer().isSubmergedInWater()) {
            return super.onTick();
        }

        boolean hasBlockBelow = false;
        for (int i = 0; i < 3; i++) {
            if (mod.getWorld().getBlockState(mod.getPlayer().getSteppingPos().down(i)).getBlock() != Blocks.WATER) {
                hasBlockBelow = true;
            }
        }
        boolean hasAirAbove = mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().up(2)).getBlock().equals(Blocks.AIR);

        if (hasAirAbove && hasBlockBelow && StorageHelper.getNumberOfThrowawayBlocks(mod) > 0) {
            mod.getInputControls().tryPress(Input.JUMP);
            if (mod.getPlayer().isOnGround()) {

                if (!startedShimmying) {
                    startedShimmying = true;
                    shimmyTaskTimer.reset();
                }
                return new SafeRandomShimmyTask();
            }

            mod.getSlotHandler().forceEquipItem(mod.getThrowawayItems().toArray(new Item[0]));
            LookHelper.lookAt(mod, mod.getPlayer().getSteppingPos().down());
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
        }

        return super.onTick();
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        // G-0: this goal was a PREDICATE ("not water, and not next to water") with no point in it,
        // which baritone could search but the tungsten drive cannot steer at. AltoGoal
        // .NearestSatisfying carries the point search for exactly this shape, so the goal becomes
        // the test itself and nothing here open-codes a scan.
        //
        // The radius is 16: far enough to leave any pool the bot can fall into, near enough that a
        // failed search costs a bounded scan rather than a frame. The old heuristic ranked
        // water (1) above water-adjacent (0.5) above dry (0); a nearest-first search expresses the
        // same preference by ARRIVING at dry ground first, without a cost table.
        return new AltoGoal.NearestSatisfying(
                pos -> !EscapeFromWaterGoal.isWater(pos.getX(), pos.getY(), pos.getZ())
                        && !EscapeFromWaterGoal.isWaterAdjacent(pos.getX(), pos.getY(), pos.getZ()),
                mod.getPlayer().getBlockPos(), 16);
    }

    @Override
    protected boolean isEqual(Task other) {
        return false;
    }

    @Override
    protected String toDebugString() {
        return "";
    }

    @Override
    public boolean isFinished() {
        return !AltoClef.getInstance().getPlayer().isTouchingWater() && AltoClef.getInstance().getPlayer().isOnGround();
    }

    /** The water test itself, kept as the predicate the goal now asks. */
    private static final class EscapeFromWaterGoal {



        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return adris.altoclef.util.helpers.WorldHelper.isWaterState(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isWaterAdjacent(int x, int y, int z) {
            return isWater(x + 1, y, z) || isWater(x - 1, y, z) || isWater(x, y, z + 1) || isWater(x, y, z - 1)
                    || isWater(x + 1, y, z - 1) || isWater(x + 1, y, z + 1) || isWater(x - 1, y, z - 1)
                    || isWater(x - 1, y, z + 1);
        }
    }
}

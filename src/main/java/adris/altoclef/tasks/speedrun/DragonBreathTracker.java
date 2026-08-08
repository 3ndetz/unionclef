package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.BotBehaviour;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;

public class DragonBreathTracker {
    private final HashSet<BlockPos> breathBlocks = new HashSet<>();

    public void updateBreath(AltoClef mod) {
        breathBlocks.clear();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            for (BlockPos bad : WorldHelper.getBlocksTouchingBox(cloud.getBoundingBox())) {
                breathBlocks.add(bad);
            }
        }
    }

    public boolean isTouchingDragonBreath(BlockPos pos) {
        return breathBlocks.contains(pos);
    }

    public Task getRunAwayTask() {
        return new RunAwayFromDragonsBreathTask();
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {

        @Override
        protected void onStart() {
            super.onStart();
            BotBehaviour botBehaviour = AltoClef.getInstance().getBehaviour();

            botBehaviour.push();
            botBehaviour.setBlockPlacePenalty(Double.POSITIVE_INFINITY);
            // do NOT ever wander
            checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);
        }

        @Override
        protected void onStop(Task interruptTask) {
            super.onStop(interruptTask);
            AltoClef.getInstance().getBehaviour().pop();
        }

        /**
         * OFF BARITONE'S GOAL TYPE, AND THIS ONE WAS DEAD ON THE DRAGON PATH.
         *
         * <p>{@code GoalRunAway} is not one of the six types {@code goalToVec} can translate
         * (GoalBlock, GoalGetToBlock, GoalNear, GoalTwoBlocks, GoalXZ, GoalComposite). So the drive
         * asked this task where to go, got NULL, and the bot stood in the dragon's breath — which
         * is one of the two things that kill it in the End.
         *
         * <p>{@link AltoGoal.FleeLive} recomputes from the CURRENT breath blocks every tick, which
         * this needs more than the mob flees do: breath is laid down and expires continuously, so
         * a snapshot would run the bot out of a cloud that had already moved.
         */
        @Override
        protected AltoGoal newAltoGoal(AltoClef mod) {
            return new AltoGoal.FleeLive(
                    () -> breathBlocks.stream()
                            .map(b -> new net.minecraft.util.math.Vec3d(
                                    b.getX() + 0.5, b.getY(), b.getZ() + 0.5))
                            .collect(java.util.stream.Collectors.toList()),
                    () -> mod.getPlayer() == null ? null : mod.getPlayer().getPos(),
                    10);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromDragonsBreathTask;
        }

        @Override
        protected String toDebugString() {
            return "ESCAPE Dragons Breath";
        }
    }
}

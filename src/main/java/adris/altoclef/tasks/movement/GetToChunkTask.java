package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.util.math.ChunkPos;

public class GetToChunkTask extends CustomBaritoneGoalTask {

    private final ChunkPos _pos;

    public GetToChunkTask(ChunkPos pos) {
        // Override checker to be more lenient, as we are traversing entire chunks here.
        checker = new MovementProgressChecker();
        _pos = pos;
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        // G-0: GoalChunk existed only to implement baritone's Goal interface around two lines of
        // arithmetic -- head for the middle, count anywhere in the sixteen-by-sixteen as arrived.
        // AltoGoal.Chunk answers both without a pathfinder in the type.
        return AltoGoal.chunk(_pos.getStartX(), _pos.getStartZ());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToChunkTask task) {
            return task._pos.equals(_pos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Get to chunk: " + _pos.toString();
    }
}

package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Walk -- or BUILD, or DIG -- into the cell a settled drop lies in. The approach a pickup needs.
 *
 * <p>A dropped item that has come to rest is a place, not a moving target, and the recorded
 * 2026-09-11 playthrough (docs/BARITONE-GAPS.md G39) shows what it costs to treat it as one: raw
 * iron lay two blocks up a ledge, {@link GetToEntityTask} asked the physics engine for a route
 * ({@code TungstenHelper.tryPathToEntity}), the physics engine cannot place or break, so it
 * refused; the task then held forward against the ledge ("Walking straight at it (navigation
 * would not)"), wandered ("Failed exploring" x12), and abandoned the drop after 200 s. The one
 * engine that can pillar or carve a stair up two blocks -- FastPlanner, through FastNavigator --
 * was never asked, because nothing on the entity path leads to it.
 *
 * <p>This task puts the drop on the SAME road the blocks travel: an {@link AltoGoal#block}
 * goal on the drop's cell through {@code CustomBaritoneGoalTask.driveTungstenPrimary}, whose
 * escalation ladder hands a goal the grid walker cannot reach to FastNavigator. Arrival is the
 * pickup itself -- the drop is gone, or the body is touching it -- since that is the only event
 * that ends a pickup; standing in the cell is merely how it comes about.
 *
 * <p>Only for a drop that has SETTLED (on the ground, not in water, not moving). A drop still
 * falling or floating downstream is a moving target and keeps the entity chase.
 */
public class GetToDropTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    /**
     * A drop counts as settled when it is on the ground, out of water and this slow HORIZONTALLY.
     *
     * <p>⛔ NOT THE FULL VELOCITY. A resting item's vertical velocity is not zero on the client:
     * ItemEntity.tick adds gravity every tick (-0.04) and only calls move() -- which zeroes it on
     * collision -- every fourth tick while the item lies still, so the y component cycles
     * -0.04, -0.08, -0.12, 0. A test on the whole vector read "moving" on three ticks of four,
     * and the pickup flipped between this task and the entity chase at that cadence: measured on
     * drop_ledge phase B as the chain alternating "Getting to drop ... (dig/build allowed)" /
     * "Approach entity ... Tungsten locked" every few seconds with the body never moving, 2.4
     * blocks from the drop on a flat ledge top. TungstenHelper's own settled-drop test already
     * used the horizontal component only; this is the same test.
     */
    private static final double SETTLED_SPEED_SQ = 0.0025;

    private final ItemEntity drop;
    /** The cell the drop rested in when the task was built; settled drops do not move. */
    private final BlockPos cell;

    public GetToDropTask(ItemEntity drop) {
        this.drop = drop;
        this.cell = drop.getBlockPos();
    }

    /** True when {@code drop} lies still on solid ground: a place the navigator can be sent to. */
    public static boolean settled(ItemEntity drop) {
        return drop != null && drop.isAlive() && drop.isOnGround() && !drop.isTouchingWater()
                && drop.getVelocity().horizontalLengthSquared() < SETTLED_SPEED_SQ;
    }

    @Override
    protected Task onTick() {
        if (driveTungstenPrimary(AltoClef.getInstance())) return null;
        return super.onTick();
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return AltoGoal.block(cell);
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        if (!drop.isAlive()) return true;
        if (mod != null && mod.getEntityTracker().isCollidingWithPlayer(drop)) return true;
        return super.isFinished();
    }

    @Override
    protected void onWander(AltoClef mod) {
        // The pickup task owns the verdict on this drop (its budget, its blacklist); a wander
        // here says nothing about whether the drop can be had.
        super.onWander(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetToDropTask task && task.drop.equals(drop);
    }

    @Override
    protected String toDebugString() {
        return "Getting to drop " + drop.getStack().getItem().getTranslationKey() + " at "
                + cell.toShortString() + " (dig/build allowed)";
    }
}

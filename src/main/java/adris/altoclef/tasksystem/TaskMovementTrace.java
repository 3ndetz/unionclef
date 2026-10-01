package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import com.google.gson.Gson;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.task.BlockPathWalker;
import kaptainwutax.tungsten.task.FastNavigator;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in, bounded client-thread observations of movement and task selection.
 * A py4j poll cannot see a one-tick grounded handoff followed by a jump: separate
 * reads also cross frames. Record the decision and the end-of-tick state together
 * with keys, precise body velocity and route ownership, without changing control.
 */
public final class TaskMovementTrace {
    private static final int CAPACITY = 2048;
    private record Row(long sequence, String json) {}
    private static final ArrayDeque<Row> ROWS = new ArrayDeque<>();
    private static final Gson JSON = new Gson();
    private static boolean enabled;
    private static long sequence;

    private TaskMovementTrace() {}

    /** Client thread only. Enabling starts a new trace; disabling retains its last rows. */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            ROWS.clear();
            sequence = 0;
        }
        Task.diagnosticEvents = value ? TaskMovementTrace::record : null;
    }

    /** Client thread only; the caller receives a copy rather than the mutable ring. */
    public static List<String> snapshot(long afterSequence) {
        List<String> result = new ArrayList<>();
        for (Row row : ROWS) {
            if (row.sequence() > afterSequence) result.add(row.json());
        }
        return result;
    }

    public static void tick() {
        if (enabled) record("end-client-tick");
    }

    private static void record(String event) {
        if (!enabled) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null) return;
            var player = mc.player;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ++sequence);
            row.put("epochMs", System.currentTimeMillis());
            row.put("age", player.age);
            row.put("event", event);
            row.put("pos", List.of(player.getX(), player.getY(), player.getZ()));
            row.put("vel", List.of(player.getVelocity().x, player.getVelocity().y, player.getVelocity().z));
            row.put("ground", player.isOnGround());
            row.put("water", player.isTouchingWater());
            row.put("lava", player.isInLava());
            row.put("hp", player.getHealth());
            row.put("yaw", player.getYaw());
            row.put("pitch", player.getPitch());
            row.put("keys", "" + (mc.options.forwardKey.isPressed() ? "F" : ".")
                    + (mc.options.backKey.isPressed() ? "B" : ".")
                    + (mc.options.leftKey.isPressed() ? "L" : ".")
                    + (mc.options.rightKey.isPressed() ? "R" : ".")
                    + (mc.options.sprintKey.isPressed() ? "S" : ".")
                    + (mc.options.jumpKey.isPressed() ? "J" : ".")
                    + (mc.options.sneakKey.isPressed() ? "C" : "."));
            row.put("walker", BlockPathWalker.isRunning());
            row.put("navigatorLeg", BlockPathWalker.isNavigatorLeg());
            row.put("navigator", FastNavigator.isActive());
            row.put("executor", TungstenModDataContainer.isExecutorRunning());
            row.put("queue", kaptainwutax.tungsten.path.movements.MovementQueue.isRunning());
            row.put("queueIndex", kaptainwutax.tungsten.path.movements.MovementQueue.getIndex());
            row.put("queueSize", kaptainwutax.tungsten.path.movements.MovementQueue.size());
            row.put("queueSafeToCancel", kaptainwutax.tungsten.path.movements.MovementQueue.safeToCancel());
            var queued = kaptainwutax.tungsten.path.movements.MovementQueue.remainingForOverlay();
            if (!queued.isEmpty()) {
                var movement = queued.get(0);
                row.put("queueMovement", movement.getClass().getSimpleName());
                row.put("queueFrom", movement.getSrc().toShortString());
                row.put("queueTo", movement.getDest().toShortString());
            }
            row.put("walkerLive", BlockPathWalker.isLive());
            row.put("navigatorGoal", String.valueOf(FastNavigator.currentGoal()));
            row.put("navigatorExact", FastNavigator.hasExactCell());
            row.put("pillar", kaptainwutax.tungsten.task.PillarTask.isActive());
            row.put("bridge", kaptainwutax.tungsten.task.BridgeTask.isActive());
            row.put("swimOut", kaptainwutax.tungsten.task.SwimOutTask.isActive());
            row.put("slime", kaptainwutax.tungsten.task.SlimeBounceTask.isActive());
            row.put("follow", kaptainwutax.tungsten.task.FollowEntityTask.isActive()
                    || kaptainwutax.tungsten.task.FollowPlayerTask.isActive());
            row.put("flee", kaptainwutax.tungsten.task.RunAwayTask.isActive());
            row.put("punk", kaptainwutax.tungsten.task.PunkPlayerTask.isActive());
            row.put("route", BlockPathWalker.describeAgainstRoute(player.getBlockPos()));
            row.put("driveAgeMs", System.currentTimeMillis() - CustomBaritoneGoalTask.lastDriveTickMs);
            var chain = AltoClef.getInstance().getTaskRunner().getCurrentTaskChain();
            row.put("tasks", chain == null ? List.of() : chain.getTasks().stream()
                    .map(task -> task.getClass().getSimpleName()).toList());
            append(JSON.toJson(row));
        } catch (RuntimeException error) {
            append(JSON.toJson(Map.of("seq", ++sequence, "epochMs", System.currentTimeMillis(),
                    "error", error.toString(), "event", event)));
        }
    }

    private static void append(String row) {
        if (ROWS.size() == CAPACITY) ROWS.removeFirst();
        ROWS.addLast(new Row(sequence, row));
    }
}

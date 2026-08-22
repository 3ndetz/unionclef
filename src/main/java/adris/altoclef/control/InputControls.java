package adris.altoclef.control;

import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> toUnpress = new ArrayDeque<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.

    private static KeyBinding inputToKeyBinding(Input input) {
        GameOptions o = MinecraftClient.getInstance().options;
        return switch (input) {
            case MOVE_FORWARD -> o.forwardKey;
            case MOVE_BACK -> o.backKey;
            case MOVE_LEFT -> o.leftKey;
            case MOVE_RIGHT -> o.rightKey;
            case CLICK_LEFT -> o.attackKey;
            case CLICK_RIGHT -> o.useKey;
            case JUMP -> o.jumpKey;
            case SNEAK -> o.sneakKey;
            case SPRINT -> o.sprintKey;
            default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
        };
    }

    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        inputToKeyBinding(input).setPressed(true);
        // Also necessary to ensure the game registers the input as "pressed"
        KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        toUnpress.add(input);
        _waitForRelease.add(input);
    }

    public void hold(Input input) {
        if (!inputToKeyBinding(input).isPressed()) {
            KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        }
        inputToKeyBinding(input).setPressed(true);
    }

    /**
     * WHO TAKES THE FORWARD KEY OFF THE CLOSE WALK? Ten call sites release MOVE_FORWARD and
     * guessing among them has already cost one wrong fix: the release inside GetToEntityTask
     * looked obvious, was gated behind a flag, and the counter then showed it firing zero times
     * while the press was still lost 118 times in the same run.
     *
     * <p>This is the one choke point every release passes through, so it can name the caller
     * instead. Only sampled on the ticks that matter -- MOVE_FORWARD, while the close walk is the
     * thing driving -- because a stack walk per release would not be free otherwise.
     */
    public static final java.util.Map<String, Integer> forwardStealers =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** Who released MOVE_FORWARD under the close walk, commonest first. */
    public static String forwardStealers() {
        synchronized (forwardStealers) {
            return forwardStealers.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(5)
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .reduce((a, b) -> a + " " + b).orElse("(none)");
        }
    }

    public void release(Input input) {
        // ⛔ THIS ONLY EVER WATCHED ONE OF THE TWO PLACES THE KEY IS STOLEN.
        // It recorded a thief only while the close walk to an ITEM was driving, and the shuffling
        // a viewer actually sees happens while MINING: BlockPathWalker holds MOVE_FORWARD toward
        // its waypoint and something else releases it in the same tick. That case was invisible
        // here, so a whole session of reading counters never surfaced it and the user found it by
        // watching a recording instead.
        // Widened to cover the walker as well; the stack frame it records is the answer to "which
        // two writers are fighting", which is the question that matters and the one I was about to
        // guess at.
        if (input == Input.MOVE_FORWARD
                && (adris.altoclef.tasks.movement.GetToEntityTask.closeWalkDrivingNow()
                    || kaptainwutax.tungsten.task.BlockPathWalker.isRunning())) {
            try {
                for (StackTraceElement el : new Throwable().getStackTrace()) {
                    String cn = el.getClassName();
                    if (cn.endsWith("InputControls")) continue;
                    String key = cn.substring(cn.lastIndexOf('.') + 1) + ":" + el.getLineNumber();
                    synchronized (forwardStealers) {
                        forwardStealers.merge(key, 1, Integer::sum);
                    }
                    break;
                }
            } catch (Exception ignored) {
                // naming the caller must never break the control it rides on
            }
        }
        inputToKeyBinding(input).setPressed(false);
    }

    /**
     * Let go of everything.
     *
     * <p>The last eight callers of the pathfinder's own input handler all said exactly this --
     * clearAllKeys() -- from places that have nothing to do with pathing: a task chain handing over,
     * a gesture finishing, the nether-portal task steadying itself before a step. Altoclef has owned
     * the input path since the InputControls migration; these were the stragglers, and they were
     * reaching across to a second key system to do something this one already knows how to do,
     * which is also how two systems end up disagreeing about whether a key is down.
     */
    public void releaseAll() {
        // ⛔ THE HOLE IN THE FIRST VERSION OF THIS INSTRUMENT. release(MOVE_FORWARD) was named
        // and releaseAll() was not, so every caller that drops all keys at once -- and there are
        // many -- would have shown up as "(none)" and been read as "nobody takes the key".
        if (adris.altoclef.tasks.movement.GetToEntityTask.closeWalkDrivingNow()) {
            try {
                for (StackTraceElement el : new Throwable().getStackTrace()) {
                    String cn = el.getClassName();
                    if (cn.endsWith("InputControls")) continue;
                    String key = "ALL:" + cn.substring(cn.lastIndexOf('.') + 1)
                            + ":" + el.getLineNumber();
                    synchronized (forwardStealers) {
                        forwardStealers.merge(key, 1, Integer::sum);
                    }
                    break;
                }
            } catch (Exception ignored) {
                // naming the caller must never break the control it rides on
            }
        }
        for (Input input : Input.values()) {
            release(input);
        }
        toUnpress.clear();
    }

    public boolean isHeldDown(Input input) {
        return inputToKeyBinding(input).isPressed();
    }

    public void forceLook(float yaw, float pitch) {
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.setYaw(yaw);
            MinecraftClient.getInstance().player.setPitch(pitch);
        }
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!toUnpress.isEmpty()) {
            inputToKeyBinding(toUnpress.remove()).setPressed(false);
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}

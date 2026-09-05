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
    /** Zeroed with the run counters. Without this the tally is a LIFETIME total, and this repo
     *  has already read one of those as a per-run figure once ("inRange=2222 clicked=0"). */
    public static void clearForwardStealers() {
        synchronized (forwardStealers) {
            forwardStealers.clear();
        }
    }

    /** Who released CLICK_LEFT while the miner was holding it, commonest first. */
    public static final java.util.Map<String, Integer> attackStealers =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** Is tungsten's miner actually running this tick? Never throws; an instrument must not. */
    private static boolean minerRunningNow() {
        try {
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            return ex != null && ex.isMiningNow();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Zeroed with the run counters, for the same reason forwardStealers is. */
    public static void clearAttackStealers() {
        synchronized (attackStealers) {
            attackStealers.clear();
        }
    }

    public static String attackStealers() {
        synchronized (attackStealers) {
            return attackStealers.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(5)
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .reduce((a, b) -> a + " " + b).orElse("(none)");
        }
    }

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
        // ⛔ AND IT COUNTED THE CALL, NOT THE THEFT. Releasing a key nobody is holding is a no-op,
        // and this tally charged it as a steal all the same -- which is how TimeoutWanderTask:256
        // came to read ten thousand while wanderKeysKept read zero. Ask whether the key was
        // actually DOWN, so the number means "a press was taken away" rather than "release() was
        // called". Same lesson as bsStub and wallSkipRefused: a counter must measure the event,
        // not the code path near it.
        // ⛔ AND THE SAME QUESTION FOR THE ATTACK KEY, WHICH IS WHERE MINING DIES.
        // Measured: with the key pressed and the aim on the planned cell, vanilla breaks on 2-5%
        // of ticks in the playthrough (mine=14/784, 88/1805) while the miner is genuinely running
        // (stallMiner=1143/6, 2260/6) -- and the SAME code mines fine in isolation on nav_break
        // (mine=30/11). Something in the playthrough takes the key that the miner holds, exactly
        // as something took MOVE_FORWARD from the walker below.
        //
        // Same two rules this tally already learned: only count a real theft (isHeldDown), and
        // only while the miner is actually running, so the denominator is a tick that mattered.
        if (input == Input.CLICK_LEFT && isHeldDown(input) && minerRunningNow()) {
            try {
                for (StackTraceElement el : new Throwable().getStackTrace()) {
                    String cn = el.getClassName();
                    if (cn.endsWith("InputControls")) continue;
                    String key = cn.substring(cn.lastIndexOf('.') + 1) + ":" + el.getLineNumber();
                    synchronized (attackStealers) {
                        attackStealers.merge(key, 1, Integer::sum);
                    }
                    break;
                }
            } catch (Exception ignored) {
                // naming the caller must never break the control it rides on
            }
        }
        if (input == Input.MOVE_FORWARD
                && isHeldDown(input)
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
        // ⛔ FIXED 2026-09-05: this used to carry its own dedicated "ALL:..." steal-detection
        // block here, on the theory that release(MOVE_FORWARD)'s own instrumentation would never
        // see a releaseAll()-driven release and so would misreport "(none)" for it. That premise
        // was wrong: the loop below calls release(input) for EVERY Input, MOVE_FORWARD included,
        // and release()'s own stack walk already skips InputControls frames to find the real
        // external caller -- exactly the same caller this block was independently recomputing.
        // So a single releaseAll() steal was being recorded TWICE, once here under an "ALL:"-
        // prefixed key and once more inside release(MOVE_FORWARD)'s own check under the bare
        // caller key -- and this block's own check didn't even gate on isHeldDown(MOVE_FORWARD)
        // first, unlike release()'s, so it also over-counted releases of a key that was never
        // actually down. Both are exactly the disease this file's own comments call out
        // elsewhere: "a counter must measure the event, not the code path near it." Removed;
        // the loop's own release() calls already count this correctly.
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

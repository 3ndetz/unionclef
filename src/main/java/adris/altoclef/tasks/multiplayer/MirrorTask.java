package adris.altoclef.tasks.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MIRROR — record a target player's movement trajectory and replay it so the bot reproduces the same
 * path + jumps (operator: "agent stands at the start point and exactly repeats the parkour jumps").
 *
 * We can only OBSERVE a player's position each tick (the client never receives another player's raw
 * key inputs), so we record their position-over-time (relative to where they started) + infer a JUMP
 * whenever their Y rose while grounded, then REPLAY by driving the bot's real inputs: face the next
 * waypoint, hold MOVE_FORWARD, and press JUMP at the recorded jump points. The bot really walks/jumps —
 * it's not a teleport. This is an inference-based mirror (position-faithful), the best achievable from
 * observation alone.
 */
public class MirrorTask extends Task {
    public enum Mode { RECORD, REPLAY, RECORD_THEN_REPLAY }

    private static final class Frame {
        final Vec3d rel;     // position relative to the recording's start
        final boolean jump;  // target jumped on this frame
        Frame(Vec3d rel, boolean jump) { this.rel = rel; this.jump = jump; }
    }

    // last recording, shared so a separate "@mirror play" can replay a prior "@mirror <p> rec".
    private static final List<Frame> SAVED = new ArrayList<>();

    private final String _targetName;
    private final Mode _mode;
    private final int _recordTicksMax;

    private final List<Frame> _rec = new ArrayList<>();
    private Vec3d _targetStart;
    private Vec3d _lastTargetPos;
    private Vec3d _botStart;
    private int _recordTicks;
    private int _replayIndex;
    private boolean _replaying;
    private boolean _done;

    public MirrorTask(String targetName, Mode mode, double recordSeconds) {
        _targetName = targetName;
        _mode = mode;
        _recordTicksMax = Math.max(20, (int) (recordSeconds * 20));
    }

    @Override
    protected void onStart() {
        _rec.clear();
        _targetStart = null;
        _lastTargetPos = null;
        _recordTicks = 0;
        _replayIndex = 0;
        _done = false;
        _replaying = (_mode == Mode.REPLAY);
        AltoClef mod = AltoClef.getInstance();
        if (_replaying && mod.getPlayer() != null) {
            _botStart = mod.getPlayer().getPos();
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null) return null;

        if (!_replaying) {
            // ---- RECORD ----
            Optional<PlayerEntity> opt = mod.getEntityTracker().getPlayerEntity(_targetName);
            if (opt.isEmpty()) {
                // target left render: if we have something, move on; else wait briefly then give up
                if (!_rec.isEmpty()) { finishRecording(mod); }
                else if (_recordTicks++ > 60) { _done = true; }
                return null;
            }
            PlayerEntity target = opt.get();
            Vec3d pos = target.getPos();
            if (_targetStart == null) { _targetStart = pos; _lastTargetPos = pos; }
            double dy = pos.y - _lastTargetPos.y;
            boolean jump = dy > 0.20; // rose noticeably this tick => a jump/step-up
            _rec.add(new Frame(pos.subtract(_targetStart), jump));
            _lastTargetPos = pos;
            if (++_recordTicks >= _recordTicksMax) { finishRecording(mod); }
            return null;
        }

        // ---- REPLAY ----
        List<Frame> frames = (_mode == Mode.REPLAY) ? SAVED : _rec;
        if (frames.isEmpty()) { _done = true; return null; }
        if (_botStart == null) _botStart = mod.getPlayer().getPos();
        if (_replayIndex >= frames.size()) { releaseAll(mod); _done = true; return null; }

        Frame f = frames.get(_replayIndex);
        Vec3d desired = _botStart.add(f.rel);
        Vec3d botPos = mod.getPlayer().getPos();
        double dx = desired.x - botPos.x;
        double dz = desired.z - botPos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // face the waypoint (~eye level) and walk toward it
        LookHelper.smoothLookAt(mod, new Vec3d(desired.x, botPos.y + 1.5, desired.z), 0.6f);
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        // jump where the target jumped, or when the next waypoint is clearly higher (a ledge)
        if (f.jump || (desired.y - botPos.y) > 0.45) {
            mod.getInputControls().tryPress(Input.JUMP);
        }
        // advance to the next waypoint once we're close enough horizontally
        if (horiz < 0.65) _replayIndex++;
        return null;
    }

    private void finishRecording(AltoClef mod) {
        SAVED.clear();
        SAVED.addAll(_rec);
        if (_mode == Mode.RECORD_THEN_REPLAY) {
            _replaying = true;
            _botStart = mod.getPlayer().getPos();
        } else {
            _done = true; // RECORD-only
        }
    }

    private void releaseAll(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected void onStop(Task interruptTask) {
        releaseAll(AltoClef.getInstance());
    }

    @Override
    public boolean isFinished() {
        return _done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MirrorTask m && m._targetName.equals(_targetName) && m._mode == _mode;
    }

    @Override
    protected String toDebugString() {
        return "Mirror " + _targetName + " (" + _mode + ", " + (_replaying ? "replay " + _replayIndex : "record " + _recordTicks) + ")";
    }
}

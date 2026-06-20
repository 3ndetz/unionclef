package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.Random;

/**
 * ALIVE / idle-life — make the bot fidget like a real idle player so it never stands frozen
 * (operator: "standing in a lobby is cringe; while it chats it should move, walk to people, look at
 * them"). PURELY input-driven: look around, glance at nearby players, tiny out-and-back shuffles,
 * occasional jumps. NO pathfinding — so it works EVERYWHERE, including protected lobbies where
 * baritone can't path (can't break the glass floor). Every horizontal shuffle is paired with an
 * equal step back, so the bot stays on its spot and never walks off a platform edge.
 *
 * It's a TOOL: the AGENT decides when to start it (e.g. while chatting) and when to stop it
 * (@idle/@stop). It is NOT a side daemon and does NOT think for the agent.
 */
public class AliveTask extends Task {
    private final int _durationTicks;
    private final Random _rng = new Random();

    private int _ticks;
    private int _phaseTicksLeft;
    private Vec3d _lookTarget;
    private Input _heldMove;      // movement input currently held (released between phases)
    private Input _pendingReturn; // forced opposite step next phase, to cancel net drift

    public AliveTask(double seconds) {
        _durationTicks = Math.max(20, (int) (seconds * 20));
    }

    @Override
    protected void onStart() {
        _ticks = 0;
        _phaseTicksLeft = 0;
        _lookTarget = null;
        _heldMove = null;
        _pendingReturn = null;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null) return null;
        _ticks++;

        // keep the head moving smoothly toward whatever we're looking at this phase
        if (_lookTarget != null) {
            LookHelper.smoothLookAt(mod, _lookTarget, 2.5f);
        }

        if (_phaseTicksLeft-- > 0) {
            return null; // hold the current micro-action
        }

        // phase ended: release any held movement, then pick the next micro-action
        releaseMoves(mod);
        chooseAction(mod);
        return null;
    }

    private void chooseAction(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();

        // 1) always cancel a previous shuffle with the opposite step first (net displacement ~0)
        if (_pendingReturn != null) {
            holdMove(mod, _pendingReturn);
            _phaseTicksLeft = 4 + _rng.nextInt(4);
            _pendingReturn = null;
            return;
        }

        int r = _rng.nextInt(100);

        // 2) ~40%: glance at a nearby OTHER player ("смотрел на них")
        if (r < 40) {
            Optional<Entity> p = nearestOtherPlayer(mod);
            if (p.isPresent()) {
                _lookTarget = p.get().getPos().add(0, 1.3, 0);
                _phaseTicksLeft = 20 + _rng.nextInt(30); // hold the gaze ~1-2.5s
                return;
            }
            r = 60 + _rng.nextInt(40); // nobody here -> fall through to look-around/move
        }

        if (r < 65) {
            // 3) look around: a random point at roughly eye level, glancing up/down a touch
            double ang = _rng.nextDouble() * Math.PI * 2;
            double y = me.y + 1.3 + (_rng.nextDouble() * 2 - 1.0);
            _lookTarget = new Vec3d(me.x + Math.cos(ang) * 6, y, me.z + Math.sin(ang) * 6);
            _phaseTicksLeft = 15 + _rng.nextInt(25);
        } else if (r < 82) {
            // 4) tiny shuffle out (paired with a return next phase so we never drift off an edge)
            Input dir;
            int d = _rng.nextInt(4);
            switch (d) {
                case 0: dir = Input.MOVE_FORWARD; break;
                case 1: dir = Input.MOVE_BACK; break;
                case 2: dir = Input.MOVE_LEFT; break;
                default: dir = Input.MOVE_RIGHT; break;
            }
            holdMove(mod, dir);
            _phaseTicksLeft = 4 + _rng.nextInt(4); // ~0.2-0.4s, well under a block
            _pendingReturn = opposite(dir);
        } else if (r < 93) {
            // 5) jump in place
            mod.getInputControls().tryPress(Input.JUMP);
            _phaseTicksLeft = 8 + _rng.nextInt(10);
        } else {
            // 6) brief sneak bob
            mod.getInputControls().tryPress(Input.SNEAK);
            _phaseTicksLeft = 6 + _rng.nextInt(8);
        }
    }

    private Optional<Entity> nearestOtherPlayer(AltoClef mod) {
        Entity self = mod.getPlayer();
        return mod.getEntityTracker().getClosestEntity(
                e -> e != self && e.isAlive() && e.squaredDistanceTo(self) < 24 * 24,
                PlayerEntity.class);
    }

    private void holdMove(AltoClef mod, Input dir) {
        mod.getInputControls().hold(dir);
        _heldMove = dir;
    }

    private void releaseMoves(AltoClef mod) {
        if (_heldMove != null) {
            mod.getInputControls().release(_heldMove);
            _heldMove = null;
        }
    }

    private static Input opposite(Input dir) {
        switch (dir) {
            case MOVE_FORWARD: return Input.MOVE_BACK;
            case MOVE_BACK: return Input.MOVE_FORWARD;
            case MOVE_LEFT: return Input.MOVE_RIGHT;
            case MOVE_RIGHT: return Input.MOVE_LEFT;
            default: return Input.MOVE_BACK;
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        releaseMoves(AltoClef.getInstance());
    }

    @Override
    public boolean isFinished() {
        return _ticks >= _durationTicks;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof AliveTask;
    }

    @Override
    protected String toDebugString() {
        return "Idle-life (alive " + _ticks + "/" + _durationTicks + ")";
    }
}

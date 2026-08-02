package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * ALIVE — make the bot act like a real player instead of standing frozen, with GOALS the AGENT sets
 * live via {@link AliveConfig} (the @alive command). Modes:
 *   IDLE  — fidget on the spot: look around, glance at nearby players, tiny shuffles, jumps.
 *   WATCH — keep LOOKING at the configured nicks (turn to whoever's present), fidget in place.
 *   NEAR  — hang AROUND the configured nick(s): drift toward the nearest one until within radius,
 *           then fidget + look at them. (input-driven, no block-breaking -> works in lobbies/privates.)
 *   ROAM  — wander to random nearby points within a bounded area, looking around as it goes.
 *
 * It EMITS events back to the agent (stdout -> latest.log -> the `alive` source in events.sh): a
 * watched/near target that LEFT render -> target_lost; no movement progress while trying to move ->
 * stuck; reached a near-target -> arrived. So the agent configures it, plays/talks, and reacts to
 * (or preempts with a real task) these events. Pure mechanical executor — the THINKING stays in the LLM.
 */
public class AliveTask extends Task {
    private final int _durationTicks;
    private final Random _rng = new Random();

    private int _ticks;
    private int _phaseTicksLeft;
    private Vec3d _lookTarget;
    private Input _heldMove;
    private Input _pendingReturn;
    private Vec3d _start;
    private Vec3d _roamWaypoint;

    // event/state tracking
    private final Set<String> _seenTargets = new HashSet<>();   // targets we've observed present
    private final Set<String> _lostEmitted = new HashSet<>();   // target_lost already emitted (debounce)
    private Vec3d _lastPos;
    private int _noProgressTicks;
    private boolean _stuckEmitted;
    private final Set<String> _arrivedEmitted = new HashSet<>();

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
        _roamWaypoint = null;
        _seenTargets.clear();
        _lostEmitted.clear();
        _arrivedEmitted.clear();
        _stuckEmitted = false;
        _noProgressTicks = 0;
        AltoClef mod = AltoClef.getInstance();
        _start = (mod.getPlayer() != null) ? mod.getPlayer().getPos() : null;
        _lastPos = _start;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null) return null;
        _ticks++;
        if (_start == null) _start = mod.getPlayer().getPos();

        // keep the head moving smoothly toward whatever we're looking at this phase
        if (_lookTarget != null) LookHelper.smoothLookAt(mod, _lookTarget, 2.5f);

        switch (AliveConfig.mode()) {
            case WATCH: tickWatch(mod); break;
            case NEAR:  tickNear(mod); break;
            case ROAM:  tickRoam(mod); break;
            default:    tickIdle(mod); break;
        }
        return null;
    }

    // ───────────────────────────── IDLE ─────────────────────────────
    private void tickIdle(AltoClef mod) {
        if (_phaseTicksLeft-- > 0) return;
        releaseMoves(mod);
        Vec3d me = mod.getPlayer().getPos();
        if (_pendingReturn != null) { holdMove(mod, _pendingReturn); _phaseTicksLeft = 4 + _rng.nextInt(4); _pendingReturn = null; return; }
        int r = _rng.nextInt(100);
        if (r < 40) {
            Optional<Entity> p = nearestOtherPlayer(mod, 24);
            if (p.isPresent()) { _lookTarget = p.get().getPos().add(0, 1.3, 0); _phaseTicksLeft = 20 + _rng.nextInt(30); return; }
            r = 60 + _rng.nextInt(40);
        }
        if (r < 65) {
            double ang = _rng.nextDouble() * Math.PI * 2;
            _lookTarget = new Vec3d(me.x + Math.cos(ang) * 6, me.y + 1.3 + (_rng.nextDouble() * 2 - 1), me.z + Math.sin(ang) * 6);
            _phaseTicksLeft = 15 + _rng.nextInt(25);
        } else if (r < 82) {
            Input dir; int d = _rng.nextInt(4);
            dir = d == 0 ? Input.MOVE_FORWARD : d == 1 ? Input.MOVE_BACK : d == 2 ? Input.MOVE_LEFT : Input.MOVE_RIGHT;
            holdMove(mod, dir); _phaseTicksLeft = 4 + _rng.nextInt(4); _pendingReturn = opposite(dir);
        } else if (r < 93) {
            mod.getInputControls().tryPress(Input.JUMP); _phaseTicksLeft = 8 + _rng.nextInt(10);
        } else {
            mod.getInputControls().tryPress(Input.SNEAK); _phaseTicksLeft = 6 + _rng.nextInt(8);
        }
    }

    // ───────────────────────────── WATCH ────────────────────────────
    private void tickWatch(AltoClef mod) {
        trackLost(mod);
        Optional<PlayerEntity> t = nearestConfiguredTarget(mod);
        if (t.isPresent()) {
            _lookTarget = t.get().getPos().add(0, 1.3, 0);     // stare at them
            // light fidget so we're not a statue while watching
            if (_phaseTicksLeft-- <= 0) {
                releaseMoves(mod);
                if (_rng.nextInt(100) < 15) mod.getInputControls().tryPress(Input.JUMP);
                _phaseTicksLeft = 14 + _rng.nextInt(16);
            }
        } else {
            tickIdle(mod);   // nobody configured present -> just be alive
        }
    }

    // ───────────────────────────── NEAR ─────────────────────────────
    private void tickNear(AltoClef mod) {
        trackLost(mod);
        Optional<PlayerEntity> t = nearestConfiguredTarget(mod);
        if (t.isEmpty()) { releaseMoves(mod); tickIdle(mod); return; }
        PlayerEntity target = t.get();
        Vec3d tp = target.getPos();
        Vec3d me = mod.getPlayer().getPos();
        double horiz = Math.hypot(tp.x - me.x, tp.z - me.z);
        _lookTarget = tp.add(0, 1.3, 0);
        if (horiz > AliveConfig.radius()) {
            holdMove(mod, Input.MOVE_FORWARD);            // drift toward them (input-driven)
            if ((tp.y - me.y) > 0.45) mod.getInputControls().tryPress(Input.JUMP);
            detectStuck(mod);
        } else {
            releaseMoves(mod);
            String nm = target.getName().getString();
            if (_arrivedEmitted.add(nm)) emit("arrived", nm);
            _noProgressTicks = 0; _stuckEmitted = false;
            if (_phaseTicksLeft-- <= 0) {                 // fidget next to them
                if (_rng.nextInt(100) < 20) mod.getInputControls().tryPress(Input.JUMP);
                _phaseTicksLeft = 14 + _rng.nextInt(16);
            }
        }
    }

    // ───────────────────────────── ROAM ─────────────────────────────
    private void tickRoam(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();
        if (_roamWaypoint == null || Math.hypot(_roamWaypoint.x - me.x, _roamWaypoint.z - me.z) < 1.5 || _ticks % 120 == 0) {
            double ang = _rng.nextDouble() * Math.PI * 2, dist = 4 + _rng.nextDouble() * 6;
            _roamWaypoint = new Vec3d(_start.x + Math.cos(ang) * dist, me.y, _start.z + Math.sin(ang) * dist);
            _noProgressTicks = 0; _stuckEmitted = false;
        }
        _lookTarget = new Vec3d(_roamWaypoint.x, me.y + 1.4, _roamWaypoint.z);
        holdMove(mod, Input.MOVE_FORWARD);
        // occasional glance around / at a player so it doesn't look robotic
        if (_ticks % 60 == 0) {
            Optional<Entity> p = nearestOtherPlayer(mod, 18);
            if (p.isPresent()) _lookTarget = p.get().getPos().add(0, 1.3, 0);
        }
        detectStuck(mod);
    }

    // ───────────────────────────── helpers ──────────────────────────
    private void trackLost(AltoClef mod) {
        for (String nick : AliveConfig.targets()) {
            boolean present = mod.getEntityTracker().getPlayerEntity(nick).isPresent();
            if (present) { _seenTargets.add(nick); _lostEmitted.remove(nick); }
            else if (_seenTargets.contains(nick) && _lostEmitted.add(nick)) emit("target_lost", nick);
        }
    }

    private Optional<PlayerEntity> nearestConfiguredTarget(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();
        PlayerEntity best = null; double bd = Double.MAX_VALUE;
        for (String nick : AliveConfig.targets()) {
            Optional<PlayerEntity> p = mod.getEntityTracker().getPlayerEntity(nick);
            if (p.isPresent()) {
                double d = p.get().squaredDistanceTo(me);
                if (d < bd) { bd = d; best = p.get(); }
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Entity> nearestOtherPlayer(AltoClef mod, double range) {
        Entity self = mod.getPlayer();
        return mod.getEntityTracker().getClosestEntity(
                e -> e != self && e.isAlive() && e.squaredDistanceTo(self) < range * range, PlayerEntity.class);
    }

    private void detectStuck(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();
        if (_lastPos != null && Math.hypot(me.x - _lastPos.x, me.z - _lastPos.z) < 0.05) {
            if (++_noProgressTicks > 50 && !_stuckEmitted) {   // ~2.5s of trying to move, no progress
                _stuckEmitted = true;
                emit("stuck", AliveConfig.mode() == AliveConfig.Mode.NEAR
                        ? String.join(",", AliveConfig.targets()) : "");
            }
        } else {
            _noProgressTicks = 0; _stuckEmitted = false;
        }
        _lastPos = me;
    }

    /** Emit a structured event the agent picks up via events.sh (stdout -> latest.log -> `alive`). */
    private void emit(String ev, String arg) {
        String a = arg == null ? "" : arg.replace("\"", "");
        System.out.println("ALIVE_EVENT {\"ev\":\"" + ev + "\",\"arg\":\"" + a + "\",\"mode\":\""
                + AliveConfig.mode() + "\"}");
    }

    private void holdMove(AltoClef mod, Input dir) { mod.getInputControls().hold(dir); _heldMove = dir; }
    private void releaseMoves(AltoClef mod) { if (_heldMove != null) { mod.getInputControls().release(_heldMove); _heldMove = null; } }

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
    protected void onStop(Task interruptTask) { releaseMoves(AltoClef.getInstance()); }

    @Override
    public boolean isFinished() { return _ticks >= _durationTicks; }

    @Override
    protected boolean isEqual(Task other) { return other instanceof AliveTask; }

    @Override
    protected String toDebugString() { return "Alive [" + AliveConfig.describe() + "] " + _ticks + "/" + _durationTicks; }
}

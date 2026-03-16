package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;

public abstract class KillAuraHelper {
    static long _timeExpires = 0;
    static double _rvalue = -1;
    public static double initialYaw = -1f;

    public static double GetNextRandomY() {
        if (System.currentTimeMillis() >= _timeExpires) {
            _timeExpires = System.currentTimeMillis() + 55;
            _rvalue = -1.0 + Math.random() * 1.0;
        }
        return _rvalue;
    }

    public static long TimerStartTime = -1;
    public static long TimerStartExpires = 0;
    public static long TimerGoing = 0;
    static long _lastRequestTime = -1;
    public static float YawSpeed = 1;
    public static float PitchSpeed = 1;
    private static final TimerGame _inPvpAction = new TimerGame(1);
    private static final TimerGame _CooldownFor18 = new TimerGame(0.07);
    private static final TimerGame _rotatedMoveTimer = new TimerGame(1);
    private static Input _rotatedMove = Input.MOVE_RIGHT;

    // Track whether combat movement is active (for cleanup)
    private static boolean _combatMovementActive = false;

    public static boolean ElapsedPvpCD() {
        return _CooldownFor18.elapsed();
    }

    public static void ResetPvpCD() {
        _CooldownFor18.setInterval(0.02 + (Math.random() / 30));
        _CooldownFor18.reset();
    }

    public static void TimerStop() {
        if (TimerStartTime != -1) {
            TimerStartTime = -1;
            initialYaw = -1;
            _lastRequestTime = -1;
            _inPvpAction.reset();
        }
    }

    public static boolean IsInBattle() {
        return _inPvpAction.elapsed();
    }

    public static boolean TimerStart(float initialYaww) {
        IsInBattle();
        if (TimerStartTime == -1) {
            YawSpeed = 0;
            PitchSpeed = 0;
            initialYaw = initialYaww;
            TimerStartTime = System.currentTimeMillis();
            TimerStartExpires = TimerStartTime + 100;
            _lastRequestTime = TimerStartTime;
            TimerGoing = 0;
            return true;
        } else {
            TimerGoing = (System.currentTimeMillis() - TimerStartTime);
            if (System.currentTimeMillis() > _lastRequestTime + 100) {
                TimerStop();
            }
            _lastRequestTime = System.currentTimeMillis();
            return false;
        }
    }

    public static Input getRotatedMove() {
        if (_rotatedMoveTimer.elapsed()) {
            _rotatedMoveTimer.reset();
            _rotatedMove = Math.random() > 0.5 ? Input.MOVE_LEFT : Input.MOVE_RIGHT;
        }
        return _rotatedMove;
    }

    /**
     * Tick-based PvP combat movement: sprint + forward + jump for crits.
     * Call this every tick during combat. No threads, no delays.
     * Keys stay held as long as this is called; call stopCombatMovement() when done.
     */
    public static void GoJump(AltoClef mod, boolean rotated, boolean jump) {
        _combatMovementActive = true;

        // Always sprint forward
        mod.getInputControls().hold(Input.SPRINT);
        mod.getInputControls().hold(Input.MOVE_FORWARD);

        // Jump for crits when on ground
        if (jump && mod.getPlayer().isOnGround()) {
            mod.getInputControls().hold(Input.JUMP);
        }

        // Occasional strafing
        if (rotated) {
            mod.getInputControls().hold(getRotatedMove());
        }
    }

    public static void GoJump(AltoClef mod, boolean rotated) {
        GoJump(mod, rotated, false);
    }

    /**
     * Release all combat movement keys. Call when leaving PvP combat.
     */
    public static void stopCombatMovement(AltoClef mod) {
        if (!_combatMovementActive) return;
        _combatMovementActive = false;
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.MOVE_LEFT);
        mod.getInputControls().release(Input.MOVE_RIGHT);
    }

    public static boolean isCombatMovementActive() {
        return _combatMovementActive;
    }
}

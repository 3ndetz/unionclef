package adris.altoclef.tasks.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static adris.altoclef.util.helpers.LookHelper.toVec3d;

/**
 * A task for performing various gestures and emotes in-game.
 * Handles different types of gestures by controlling player movement, rotation, and actions.
 *
 * Gestures include:
 * - Fight: Combat stance with side strafing
 * - Disrespect: Turning back to target
 * - BrawlStars: Spinning animation
 * - Hey: Simple greeting wave
 * - Cheer: Celebratory jump and wave
 * - Sad: Looking down with slow movement
 * - Crazy: Random head movements
 * - Agree: Nodding head up and down
 * - Disagree: Shaking head left and right
 *
 * Can target either:
 * - A specific entity (like a player)
 * - A position in the world
 * - Random gesture if no target specified
 *
 * Gesture timing and animations are controlled by phase system and timers
 */
public class GestureTask extends Task {

    private Entity _target;
    private Vec3d _targetPos;
    private int _phase = 0;
    private double _interactDistance = 2.5d;
    private double _stopDistance = 0.2d;
    private boolean _started = false;
    private final boolean SIMPLE_GO_TO_TARGET = false;

    public enum Gesture {
        Fight,
        Disrespect,
        BrawlStars,
        Hey,
        Cheer,
        Sad,
        Crazy,
        Agree,
        Disagree
    }

    public float _rotationIter = -179;
    public Gesture _gesture;
    private final TimerGame _shiftTimer = new TimerGame(0.15);
    public final TimerGame _gestureTimer = new TimerGame(1.7);

    public GestureTask(Entity target, Gesture type) {
        _target = target;
        _targetPos = null;
        _gesture = type;
    }

    public GestureTask(Vec3d position, Gesture type) {
        _target = null;
        _targetPos = position;
        _gesture = type;
    }

    public Map<String, Gesture> getGestureMap() {
        Map<String, Gesture> map = new HashMap<>();
        map.put("Angry", Gesture.Fight);
        map.put("Gloat", Gesture.Disrespect);
        map.put("Smirk", Gesture.BrawlStars);
        return map;
    }

    public Gesture gestureFromString(String gestureString) {
        Gesture gesture;
        try {
            gesture = Gesture.valueOf(gestureString);
        } catch (IllegalArgumentException e) {
            gesture = getGestureMap().getOrDefault(gestureString, Gesture.Hey);
        }
        return gesture;
    }

    public GestureTask(String playerName, String gestureString) {
        AltoClef mod = AltoClef.getInstance();
        Optional<PlayerEntity> player = mod.getEntityTracker().getPlayerEntity(playerName);
        if (player.isEmpty()) {
            Debug.logMessage("Player " + playerName + " not found.");
            _target = null;
            _targetPos = null;
        } else {
            _target = player.get();
            _targetPos = null;
        }
        _gesture = gestureFromString(gestureString);
    }

    public GestureTask(Entity target) {
        this(target, Gesture.values()[new Random().nextInt(Gesture.values().length - 1)]); // random
    }

    protected Vec3d getTargetPos(AltoClef mod) {
        if (_target != null) {
            return _target.getEyePos();
        }
        return _targetPos;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        Vec3d lookTarget = getTargetPos(mod);

        if (_gestureTimer.elapsed()) {
            _gestureTimer.reset();
        }
        if (!_started) _started = true;

        if (lookTarget == null || mod.getPlayer() == null || mod.getPlayer().getPos() == null) {
            setDebugState("NULL TARGET / SELF");
            return null;
        }
        Rotation lookAtCamera = LookHelper.getLookRotation(mod, lookTarget);
        Vec3d targetVector = lookTarget.subtract(mod.getPlayer().getPos()).normalize();
        double targetDist = mod.getPlayer().getPos().distanceTo(lookTarget);
        // Clamp camera distance to prevent teleporting ~100000 blocks away
        double maxCameraDist = 20.0;
        if (LookHelper.cleanLineOfSight(lookTarget, 100d) && targetDist <= maxCameraDist) {
            // set camera from target if we see target, to show us from target
            mod.getBehaviour().setCameraPositionModifer(
                    lookTarget.add(targetVector.multiply(1)));
            lookAtCamera = LookHelper.getLookRotation(mod, lookTarget);

            mod.getBehaviour().setCameraRotationModifer(new Rotation(
                    LookHelper.normalizeAngle(lookAtCamera.getYaw() + 180),
                    0)
            );
        } else {
            mod.getBehaviour().setCameraPositionModifer(
                    mod.getPlayer().getEyePos()
                            .add(targetVector.multiply(-1)));
            mod.getBehaviour().setCameraRotationModifer(lookAtCamera);
        }
        double yDiff = lookTarget.getY() - mod.getPlayer().getPos().getY();
        double dist = mod.getPlayer().getPos().distanceTo(lookTarget);
        boolean tooClose = dist < _stopDistance;
        boolean shifting = !(_gesture.equals(Gesture.Fight) || _gesture.equals(Gesture.BrawlStars) || _gesture.equals(Gesture.Disagree) || _gesture.equals(Gesture.Agree));
        boolean moveLeftRight = _gesture.equals(Gesture.Fight);
        boolean swingHand = !(_gesture.equals(Gesture.BrawlStars) || _gesture.equals(Gesture.Disrespect)
                || (_gesture.equals(Gesture.Agree) || _gesture.equals(Gesture.Disagree)));

        // Handle different gesture types and looking
        if (_gesture.equals(Gesture.Hey)) {
            LookHelper.smoothLookAt(mod, lookTarget, 0.3f);
        } else if (_gesture.equals(Gesture.Agree)) {
            if (_phase == 0) {
                Rotation newRot = new Rotation(mod.getPlayer().getYaw(), 20);
                LookHelper.smoothLook(mod, newRot, 0.3f);
            } else {
                LookHelper.smoothLookAt(mod, lookTarget, 0.3f);
            }
        } else if (_gesture.equals(Gesture.Disagree)) {
            if (_phase == 0) {
                // Shake head left: offset +50 from current yaw (relative, not absolute)
                float targetYaw = LookHelper.normalizeAngle(mod.getPlayer().getYaw() + 50);
                Rotation newRot = new Rotation(targetYaw, mod.getPlayer().getPitch());
                LookHelper.smoothLook(mod, newRot, 0.2f);
            } else {
                LookHelper.smoothLookAt(mod, lookTarget, 0.4f);
            }
        } else if (_gesture.equals(Gesture.Fight)) {
            if (_shiftTimer.getDuration() < 0.1d) {
                Rotation newRot = new Rotation(mod.getPlayer().getYaw(), 40);
                LookHelper.smoothLook(mod, newRot, (float) _shiftTimer.getDuration() * 4);
            } else {
                LookHelper.smoothLookAt(mod, lookTarget, 0.4f);
            }
        } else if (_gesture.equals(Gesture.BrawlStars)) {
            // TODO UNTESTED
            mod.getInputControls().hold(Input.SPRINT);
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            Rotation currentLookRot = LookHelper.getLookRotation();
            LookHelper.smoothLookAt(mod, mod.getPlayer().getEyePos().add(
                    toVec3d(new Rotation(currentLookRot.getYaw() + 90, 0))
                            .normalize().multiply(5)
                    ).multiply(1, 0, 1).add(
                    new Vec3d(0, lookTarget.getY(), 0)
                    ),
                    0.25f);
        } else if (_gesture.equals(Gesture.Disrespect)) {
            Rotation lookAt = LookHelper.getLookRotation(mod, lookTarget);
            float newRotYaw = lookAt.getYaw() + 180;
            newRotYaw = LookHelper.normalizeAngle(newRotYaw);
            LookHelper.smoothLook(mod, new Rotation(newRotYaw, lookAt.getPitch()), 0.3f);
        } else if (_gesture.equals(Gesture.Cheer)) {
            Rotation newRot = new Rotation(mod.getPlayer().getYaw(), -40);
            LookHelper.smoothLook(mod, newRot, 0.3f);
        } else if (_gesture.equals(Gesture.Sad)) {
            LookHelper.smoothLookAt(mod,
                    lookTarget.add(0, -yDiff - 10, 0), 0.05f);
        } else if (_gesture.equals(Gesture.Crazy)) {
            LookHelper.randomOrientation(mod);
        }

        // Handle jumping and actions
        if (_gesture.equals(Gesture.Cheer)) {
            mod.getInputControls().tryPress(Input.JUMP);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }

        if (swingHand) {
            if (_phase == 0) {
                // CHECK NOT TO HIT THE ENTITY
                // CHECK NOT TO HIT THE BLOCK IF IN CREATIVE
                if (dist > 6 && !mod.getPlayer().isInCreativeMode())
                    mod.getInputControls().hold(Input.CLICK_LEFT);
            }
            mod.getInputControls().release(Input.CLICK_LEFT);
        }

        if (shifting) {
            if (_phase == 0) {
                mod.getInputControls().hold(Input.SNEAK);
            } else {
                mod.getInputControls().release(Input.SNEAK);
            }
        } else {
            mod.getInputControls().release(Input.SNEAK);
        }

        if (moveLeftRight) {
            if (_phase == 0) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            } else {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
            }
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        }

        // we can show gesture to target that is line of sight no matter of distance
        // if not in line of sight it needs to be a baritone get to pos task, TODO
        if (SIMPLE_GO_TO_TARGET) {
            if (tooClose) {
                if (mod.getPlayer().getVelocity().horizontalLengthSquared() > 0.0025) {
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                } else {
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            } else if (!mod.getPlayer().getPos().isInRange(lookTarget, _interactDistance)) {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            } else {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            }
        }

        if (_shiftTimer.elapsed()) {
            if (_phase > 0) {
                _phase = 0;
            } else {
                _phase += 1;
            }
            _shiftTimer.reset();
        }

        if (_gesture.equals(Gesture.Sad)) {
            return new SafeRandomShimmyTask();
        }

        return null;
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        Vec3d target = getTargetPos(mod);
        return target == null || (_started && _gestureTimer.elapsed());
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        //EpicCamera.getInstance().freezeCam(5);  // Phase 4 stub
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SPRINT);
        // Reset camera modifiers so EpicCamera returns to normal orbital mode
        mod.getBehaviour().resetCameraPositionModifer();
        mod.getBehaviour().resetCameraRotationModifer();
        mod.getBehaviour().pop();
        //EpicCamera.getInstance().forceStopFreezing();  // Phase 4 stub
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof GestureTask task)) return false;
        if (_target != null) return task._target != null && task._target.equals(_target);
        return task._targetPos != null && task._targetPos.equals(_targetPos);
    }

    @Override
    protected String toDebugString() {
        String target_str;
        if (_target != null && _target.getName() != null) {
            target_str = _target.getName().getString();
        } else if (_targetPos != null) {
            target_str = String.format("(%.1f, %.1f, %.1f)", _targetPos.x, _targetPos.y, _targetPos.z);
        } else {
            target_str = "(unreachable)";
        }

        return "Showing gesture " + _gesture.toString()
                + " to target " + target_str
                + " for time " + String.format("%.1f", _gestureTimer.getDuration());
    }
}

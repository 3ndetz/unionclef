package adris.altoclef.tasks.stupid;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
//#if MC >= 12100
import net.minecraft.item.Items;
//#endif
import net.minecraft.util.math.Vec3d;


public class MacePunchTask extends Task {

    Entity _target;
    boolean _jumped = false;
    boolean _finished = false;
    boolean _saveFall = false;
    boolean _readyForJump = false;
    private int _jumpStage = 0;

    public MacePunchTask(Entity target) {
        _target = target;
    }

    public MacePunchTask(Entity target, double minHeight) {
        _target = target;
        MACE_Y = minHeight;
    }

    // horizontal range when can perform mace punch
    public static double MACE_XZ_RANGE = 6d;
    // minimum vertical difference when can perform mace punch
    public static double MACE_Y_MIN_DIFF = 3d;
    public double MACE_Y;
    public static double MACE_HIT_REACH = 2.9d;
    public final TimerGame _accelerateTimer = new TimerGame(0.5);
    public static final TimerGame _saveFallTimeout = new TimerGame(0.5);
    public static final TimerGame _maceTimeout = new TimerGame(10);
    public final TimerGame _stageTimer = new TimerGame(0.3);

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
    }

    public static boolean canMacePunch(AltoClef mod, Vec3d pos) {
        //#if MC >= 12100
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.MACE))
            return false;
        if (!LookHelper.cleanLineOfSight(pos, 400))
            return false;
        double yDiff = mod.getPlayer().getY() - pos.getY();
        if (yDiff < MACE_Y_MIN_DIFF)
            return false;
        double xzDist = pos.multiply(1, 0, 1)
                .distanceTo(mod.getPlayer().getPos().multiply(1, 0, 1));
        double HEIGHT_BLOCKS_PER_HORIZONTAL = 6;
        double ratio = MACE_HIT_REACH + HEIGHT_BLOCKS_PER_HORIZONTAL - (MACE_Y_MIN_DIFF - yDiff) / HEIGHT_BLOCKS_PER_HORIZONTAL;
        return ratio >= xzDist;
        //#else
        //$$ return false;
        //#endif
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (!_saveFallTimeout.elapsed()) {
            return new MLGBucketTask();
        }
        if (_target == null || !_target.isAlive()) {
            setDebugState("TARGET IS NULL");
            _finished = true;
            return null;
        }
        //#if MC >= 12100
        // Preparing. We should have MACE in inventory
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.MACE)) {
            setDebugState("NO MACE FOUND");
            _finished = true;
            return null;
        }
        //#endif
        if (mod.getPlayer().isTouchingWater()) {
            setDebugState("IN WATER");
            _finished = true;
            return null;
        }
        double yDiff = mod.getPlayer().getY() - _target.getY();
        if (_jumped && _maceTimeout.elapsed()) {
            setDebugState("MACE TIMEOUT");
            _finished = true;
            return null;
        }
        // Step 3.3. Perform ATTACK
        if (_jumped && !_maceTimeout.elapsed() && !mod.getPlayer().isOnGround()) {
            //#if MC >= 12100
            mod.getSlotHandler().forceEquipItem(Items.MACE);
            //#endif
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            mod.getInputControls().hold(Input.SPRINT);
            mod.getInputControls().hold(Input.JUMP);
            setDebugState("HIS NAME IS JOHN CENA");
            mod.getBehaviour().setUserTaskChainPriority(Float.POSITIVE_INFINITY);

            // if target is in attack range, perform attack
            if (LookHelper.canHitEntity(mod, _target)) {
                LookHelper.smoothLookAt(mod, LookHelper.getOptimalAimPoint(mod, _target));
                setDebugState("PUNCH! *john cena jingle, be pee pe peee*");
                if (LookHelper.isLookingAtEntity(mod, _target)) {
                    mod.getInputControls().release(Input.CLICK_RIGHT);
                    mod.getInputControls().release(Input.CLICK_LEFT);
                    mod.getInputControls().hold(Input.CLICK_LEFT);
                    mod.getInputControls().tryPress(Input.CLICK_LEFT);
                    mod.getBehaviour().setDefaultUserTaskChainPriority();
                    mod.getControllerExtras().attack(_target);
                    String targetString;
                    if (_target != null && _target.getName() != null)
                        targetString = " (" + _target.getName().getString() + ")";
                    else
                        targetString = "";
                    Debug.logMessage("HIS NAME IS JOHN CENA!!! *punch*" + targetString);
                    _finished = true;
                    _jumped = false;
                    _saveFallTimeout.reset();
                }
            } else {
                LookHelper.smoothLook(mod, _target);
            }
            return null;
        }

        if (_readyForJump) {
            if (mod.getPlayer().isOnGround()) {
                //#if MC >= 12100
                mod.getSlotHandler().forceEquipItem(Items.MACE);
                //#endif

                // Use stages with fixed timings instead of accelerateTimer.getDuration()
                switch (_jumpStage) {
                    case 0:
                        setDebugState("Preparing for accelerate... (back+sneak)");
                        mod.getInputControls().release(Input.JUMP);
                        mod.getInputControls().hold(Input.MOVE_BACK);
                        mod.getInputControls().hold(Input.SNEAK);
                        if (_stageTimer.elapsed()) {
                            _jumpStage = 1;
                            _stageTimer.reset();
                        }
                        break;
                    case 1:
                        setDebugState("Accelerating (forward+sprint)");
                        mod.getInputControls().release(Input.MOVE_BACK);
                        mod.getInputControls().release(Input.SNEAK);
                        mod.getInputControls().hold(Input.MOVE_FORWARD);
                        mod.getInputControls().hold(Input.SPRINT);
                        if (_stageTimer.elapsed()) {
                            _jumpStage = 2;
                        }
                        break;
                    case 2:
                        setDebugState("JUMPING!");
                        mod.getInputControls().hold(Input.MOVE_FORWARD);
                        mod.getInputControls().hold(Input.SPRINT);
                        mod.getInputControls().hold(Input.JUMP);
                        _jumped = true;
                        _maceTimeout.reset();
                        break;
                }
            } else {
                _maceTimeout.reset();
                _jumped = true;
            }
            return null;
        }

        if (MACE_Y != 0) {
            // Step 2. Get to Y range (need height difference)
            if (yDiff < MACE_Y) {
                setDebugState("Getting needed height");
                return new GetToYTask(_target.getBlockY() + (int) MACE_Y);
            }
        }
        setDebugState("JUMP!");
        mod.getBehaviour().setUserTaskChainPriority(Float.POSITIVE_INFINITY);
        // Step 3. PERFORM JOHN CENA
        LookHelper.smoothLook(mod, _target);
        //#if MC >= 12100
        // 3.1 Equip mace
        mod.getSlotHandler().forceEquipItem(Items.MACE);
        //#endif
        // 3.2 Perform JUMP

        _stageTimer.reset();
        _jumpStage = 0;
        _readyForJump = true;
        return null;
    }

    @Override
    public boolean isFinished() {
        return _finished;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.CLICK_LEFT);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getBehaviour().setDefaultUserTaskChainPriority();
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MacePunchTask;
    }

    @Override
    protected String toDebugString() {
        return "Mace punching from height";
    }
}

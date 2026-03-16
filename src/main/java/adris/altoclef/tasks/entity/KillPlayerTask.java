package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.agent.Pipeline;
import adris.altoclef.util.progresscheck.IProgressChecker;
import adris.altoclef.util.progresscheck.LinearProgressChecker;
import adris.altoclef.util.progresscheck.ProgressCheckerRetry;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;

//#if MC >= 12100
import adris.altoclef.tasks.stupid.MacePunchTask;
//#endif

import java.util.Optional;

import static adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask.canUseRanged;
import static adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask.shouldEnderpearl;

/**
 * Kill a player given their username
 */
public class KillPlayerTask extends AbstractKillEntityTask {

    public final String _playerName;
    private final double AUTO_RANGED_DISTANCE = 100;
    private final double AUTO_PEARL_DISTANCE = 100;
    private TimerGame _pearlTimer = new TimerGame(10);
    private TimerGame _bowTimer = new TimerGame(4);
    private final TimerGame _rangedTimer = new TimerGame(6);
    private Task specialKillTask;

    private final IProgressChecker<Double> _distancePlayerCheck = new ProgressCheckerRetry<>(new LinearProgressChecker(5, -2), 3);

    public KillPlayerTask(String name) {
        super(7, 1);
        _playerName = name;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // If we're closer to the player, our task isn't bad.
        Optional<Entity> player = getEntityTarget(mod);
        if (player.isEmpty()) {
            _distancePlayerCheck.reset();
        } else {
            if (specialKillTask != null && specialKillTask.isActive()
                    && !specialKillTask.isFinished()
                    && (
                        //#if MC >= 12100
                        specialKillTask instanceof MacePunchTask ||
                        //#endif
                        !_rangedTimer.elapsed())
            ) {
                return specialKillTask;
            } else {
                specialKillTask = null;
            }
            //#if MC >= 12100
            if (MacePunchTask.canMacePunch(mod, player.get().getPos())) {
                specialKillTask = new MacePunchTask(player.get());
                return specialKillTask;
            }
            //#endif
            double distSq = player.get().squaredDistanceTo(mod.getPlayer());
            if (distSq < 10 * 10) {
                _distancePlayerCheck.reset();
            } else {
                // TODO NOW UNTESTED!!!

                if (distSq < AUTO_RANGED_DISTANCE * AUTO_RANGED_DISTANCE) {
                    // shoot bow!
                    boolean canBow = canUseRanged(mod, player.get());

                    boolean canEnderpearl = (shouldEnderpearl(mod, player.get())
                            && distSq < AUTO_PEARL_DISTANCE * AUTO_PEARL_DISTANCE);
                    boolean canPerformAnyRangedTactics = (canBow || canEnderpearl);
                    if (canPerformAnyRangedTactics) {
                        boolean BOW_OR_ENDERPEARL = true; // switcher
                        boolean bothRangedTacticsAvailable = canBow && canEnderpearl;
                        if (!bothRangedTacticsAvailable) {
                            _bowTimer.setInterval(10);
                            _pearlTimer.setInterval(10);
                            if (canBow) {
                                BOW_OR_ENDERPEARL = true;
                            }
                            if (canEnderpearl)
                                BOW_OR_ENDERPEARL = false;
                        } else {
                            BOW_OR_ENDERPEARL = Math.random() < 0.7; // switcher
                        }
                        if (BOW_OR_ENDERPEARL) {
                            if (_bowTimer.elapsed()) {
                                if (bothRangedTacticsAvailable) {
                                    _bowTimer.setInterval(10);
                                    _pearlTimer.setInterval(4);
                                }
                                _bowTimer.reset();
                                _rangedTimer.reset();
                                specialKillTask = new ShootArrowSimpleProjectileTask(player.get());
                                return specialKillTask;
                            }

                        } else {
                            if (_pearlTimer.elapsed()) {
                                if (bothRangedTacticsAvailable) {
                                    _bowTimer.setInterval(5);
                                    _pearlTimer.setInterval(10);
                                }
                                _pearlTimer.reset();
                                _rangedTimer.reset();
                                specialKillTask = new ThrowEnderPearlSimpleProjectileTask(player.get().getBlockPos());
                                return specialKillTask;
                            }
                        }
                    }
                }
            }
            _distancePlayerCheck.setProgress(-1 * distSq);
            if (!_distancePlayerCheck.failed()) {
                progress.reset();
            }
        }
        // TODO untested
        if (Pipeline.MurderMystery.equals(AltoClef.getPipeline()) && player.isPresent() && canUseRanged(mod, player.get())) {
            _bowTimer.forceElapse();
            specialKillTask = new ShootArrowSimpleProjectileTask(player.get());
            return specialKillTask;
        }
        return super.onTick();
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        if (other instanceof KillPlayerTask task) {
            return task._playerName.equals(_playerName);
        }
        return false;
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        if (mod.getEntityTracker().isPlayerLoaded(_playerName)) {
            return mod.getEntityTracker().getPlayerEntity(_playerName).map(Entity.class::cast);
        }
        return Optional.empty();
    }

    @Override
    protected String toDebugString() {
        return "Striking player " + _playerName;
    }
}

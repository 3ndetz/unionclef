package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 *
 * A task that chooses the best strategy based on navigation distances using Baritone's pathfinding
 * Current approach: BAD. Choosing only closest, but MAY STUCK FOR UNREACHABLE / UNOPTIMIZED
 *
 * TODO: Choose strategy based on baritone heuristic (getCurrentBaritoneHeuristic from AbstractDoToClosestObjectTask)
 *
 * If current heuristic is too big within a timer of 2 secs, choose another strategy AND set getclosest to to it.
 * So, getclosesetTo should only return current strategy is being calculated
 *
 * More proper explanations how it should work: (TODO implement)
 *
 * We should have several timers.

if heuristic for strategy no more than 100, then we can give it 30 seconds (or more, maybe forever) without switching tries.

If heuristic is 100-1000, give it 15 seconds.

if heuristic is more than 1000, switch every 10 seconds.

SO THE COORECT APPROACH NEEDS TO:
1. on spawn (first start) choose the strategy with closest position
2. then ALWAYS switch the strategies using the proper timeouts for each heuristic value. To know good the strategy or not first we should TRY IT and we need ALWAYS try them.
3. The strategies for wrapper isnt valid should be excluded from choosing since their getpos is null and its heuristics cant be calculated. BUT if they are not null, the should again may be choosed.
4. ALL the valid strategies should be tried, so switching is not OPTIONAL, it's doing ALWAYS but just with modifying interval.
 *
 *
 * TODO NEXT AFTER THIS: planning intervals.
 *
 * Planning interval event:
 * We giving 3 seconds for EVERY strategy, and do next:
 * try closest strategy for 3 seconds timeout, save its heuristic before finish
 * try next for 3 seconds, do the same for all.
 * ORDER of planning heuristics: from closest to farthest just by distance.
 * Before (and during) planning event heuristics, non-valid strategies should be filtered, BUT NOT DELETED.
 * IF checking heuristic is too low (<100), we SHOULD NOT not check others.
 * IT IS CRUCIAL to check heuristic ONLY at the end of timeout, not starting.
 * TODO experiment: temporally blacklist strategies with with INFINITY heuristics for 10 seconds.
 *
 * The interval of planning events should be as described above, depending on current chosen final heuristic.
 * For big heuristic, choose time is lower, for very lower heuristic, time can be forever.
 *  !!! DO NOT ERASE THIS COMMENTS / TODOS !!! You may only make it prettier view WITHOUT LOSE ANY INFORMATION!!!
 */
public class ChooseStrategyTask<T extends Enum<T>> extends AbstractDoToClosestObjectTask<T> {
    private static final double LOW_HEURISTIC_THRESHOLD = 100.0;
    private static final double MED_HEURISTIC_THRESHOLD = 1000.0;
    private static final double LOW_HEURISTIC_TIMEOUT = 30.0;  // For h <= 100
    private static final double MED_HEURISTIC_TIMEOUT = 15.0;  // For 100 < h <= 1000
    private static final double HIGH_HEURISTIC_TIMEOUT = 10.0; // For h > 1000

    private final Map<T, PositionWrapper> _strategyMap;
    private final Function<T, Task> _strategyTaskProvider;
    private final TimerGame _switchTimer;
    private TimerGame _minimalTimeout = new TimerGame(10);
    private final Map<T, Double> _strategyHeuristics;
    private T _currentStrategy;
    private int _strategyIndex;

    public ChooseStrategyTask(Function<T, Task> strategyTaskProvider, Map<T, PositionWrapper> strategyMap) {
        this._strategyTaskProvider = strategyTaskProvider;
        this._strategyMap = new HashMap<>(strategyMap);
        this._switchTimer = new TimerGame(HIGH_HEURISTIC_TIMEOUT);
        this._strategyHeuristics = new HashMap<>();
        this._currentStrategy = null;
        this._strategyIndex = 0;

    }

    private static double getCurrentBaritoneHeuristic(AltoClef mod) {
        Optional<Double> ticksRemainingOp = mod.getClientBaritone().getPathingBehavior().ticksRemainingInSegment();
        return ticksRemainingOp.orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    protected Vec3d getPos(AltoClef mod, T strategy) {
        if (strategy == null) return null;
        PositionWrapper wrapper = _strategyMap.get(strategy);
        return wrapper != null ? wrapper.getPos() : null;
    }

    @Override
    protected Optional<T> getClosestTo(AltoClef mod, Vec3d pos) {
        if (_currentStrategy == null) {
            _currentStrategy = findClosestValidStrategy(mod, pos);
            _switchTimer.reset();
            return Optional.ofNullable(_currentStrategy);
        }

        double currentHeuristic = getCurrentBaritoneHeuristic(mod);
        _strategyHeuristics.put(_currentStrategy, currentHeuristic);

        if (currentHeuristic <= LOW_HEURISTIC_THRESHOLD) {
            _switchTimer.setInterval(LOW_HEURISTIC_TIMEOUT);
        } else if (currentHeuristic <= MED_HEURISTIC_THRESHOLD) {
            _switchTimer.setInterval(MED_HEURISTIC_TIMEOUT);
        } else {
            _switchTimer.setInterval(HIGH_HEURISTIC_TIMEOUT);
        }

        if (_switchTimer.elapsed()) {
            _currentStrategy = getNextValidStrategy(mod);
            _switchTimer.reset();
        }

        return Optional.ofNullable(_currentStrategy);
    }

    private T getNextValidStrategy(AltoClef mod) {
        ArrayList<T> validStrategies = new ArrayList<>();
        for (Map.Entry<T, PositionWrapper> entry : _strategyMap.entrySet()) {
            Debug.logMessage(entry.getKey().name()+ " STRATEGY " + entry.getValue().toString() + " is valid " + isValid(mod, entry.getKey()));
            if (entry.getValue() != null && entry.getValue().hasPosition() &&
                isValid(mod, entry.getKey())) {
                validStrategies.add(entry.getKey());
            }
        }
        //Debug.logMessage(" DEBUG VALID STRATS" + validStrategies.toString());
        // this uses VERY rare =(((
        if (validStrategies.isEmpty()) return _currentStrategy;

        _strategyIndex = (_strategyIndex + 1) % validStrategies.size();
        return validStrategies.get(_strategyIndex);
    }

    private T findClosestValidStrategy(AltoClef mod, Vec3d pos) {
        T closest = null;
        double minDist = Double.POSITIVE_INFINITY;

        for (Map.Entry<T, PositionWrapper> entry : _strategyMap.entrySet()) {
            PositionWrapper wrapper = entry.getValue();
            if (wrapper != null && wrapper.hasPosition() &&
                wrapper.getPos() != null && isValid(mod, entry.getKey())) {
                double dist = wrapper.getPos().squaredDistanceTo(pos);
                if (dist < minDist) {
                    minDist = dist;
                    closest = entry.getKey();
                }
            }
        }
        return closest;
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(T strategy) {
        return _strategyTaskProvider.apply(strategy);
    }

    @Override
    protected boolean isValid(AltoClef mod, T strategy) {
        PositionWrapper wrapper = _strategyMap.get(strategy);
        return wrapper != null &&
        wrapper.isValid(mod); // HERE'S THE SHIT!!!
    }

    @Override
    protected void onStart() {
        //_minimalTimeout.reset();
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        //if (!_minimalTimeout.elapsed()) return true;
        //Debug.logMessage("isequal !" + this._switchTimer.elapsed());
        //return !this._switchTimer.elapsed();
        if (other instanceof ChooseStrategyTask<?> task) {
            //Debug.logMessage("Strat Equals " + task._strategyMap.values().stream().anyMatch(
            //        (positionWrapper) -> true
            //);
            // need to check every keypair key <-> value.equals()
            if (task._strategyMap.keySet().equals(this._strategyMap.keySet())){
                // if there's pending equal task, let's redefine the position suppliers
                for (Map.Entry<?, PositionWrapper> entry : task._strategyMap.entrySet()) {
                    PositionWrapper wrapper = entry.getValue();
                    if (wrapper != null && wrapper.hasPosition()) {
                        this._strategyMap.put((T) entry.getKey(), wrapper);
                        //Debug.logMessage("Wrapper exchanged " + wrapper);
                    }
                }
                return true;
            };
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        double h = _strategyHeuristics.getOrDefault(_currentStrategy, Double.POSITIVE_INFINITY);
        return String.format("Choose Strategy: current=%s, h=%.0f, timeout=%.1fs",
            _currentStrategy, h, _switchTimer.getDuration());
    }
}

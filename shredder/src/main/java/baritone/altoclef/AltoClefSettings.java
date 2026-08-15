/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.altoclef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

public class AltoClefSettings {

    // woo singletons
    private static AltoClefSettings _instance = new AltoClefSettings();
    private final Object breakMutex = new Object();
    private final Object placeMutex = new Object();
    private final Object propertiesMutex = new Object();
    private final Object globalHeuristicMutex = new Object();
    private final HashSet<BlockPos> _blocksToAvoidBreaking = new HashSet<>();
    private final List<Predicate<BlockPos>> _breakAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> _placeAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> _forceCanWalkOn = new ArrayList<>();
    private final List<Predicate<BlockPos>> _forceAvoidWalkThrough = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> _forceSaveTool = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> _forceUseTool = new ArrayList<>();
    private final List<BiFunction<Double, BlockPos, Double>> _globalHeuristics = new ArrayList<>();
    private final HashSet<Item> _protectedItems = new HashSet<>();
    private boolean _allowFlowingWaterPass;
    private boolean _pauseInteractions;
    private boolean _dontPlaceBucketButStillFall;
    private boolean _allowSwimThroughLava = false;
    private boolean _treatSoulSandAsOrdinaryBlock = false;
    private boolean canWalkOnEndPortal = false;

    public static AltoClefSettings getInstance() {
        return _instance;
    }

    public void canWalkOnEndPortal(boolean canWalk) {
        canWalkOnEndPortal = canWalk;
    }

    public void avoidBlockBreak(BlockPos pos) {
        synchronized (breakMutex) {
            _blocksToAvoidBreaking.add(pos);
        }
    }

    /**
     * ⛔ THIS APPENDS, AND NOTHING IN THIS FILE EVER REMOVES -- WHICH DOES NOT MEAN IT LEAKS.
     *
     * <p>Grepping this file for {@code _breakAvoiders.clear} or {@code .remove} finds nothing, and
     * that reads exactly like an append-only leak: {@code avoidPredCount} would only ever grow, and
     * this class's own note says "a predicate COUNT that grows is a push/pop leak". I concluded
     * precisely that, and it is WRONG.
     *
     * <p>The list is rebuilt wholesale by {@code BotBehaviour.applyState}, through the accessor:
     * {@code getBreakAvoiders().clear()} then {@code addAll(toAvoidBreaking)}, plus the persistent
     * extra predicate when one is set. So the mutation that bounds this list lives in ANOTHER FILE
     * and is invisible to any search of this one.
     *
     * <p>The lesson is about searching, not about avoiders: absence of a mutation in the file that
     * OWNS the collection is not absence of the mutation. Ask who holds the accessor.
     */
    public void avoidBlockBreak(Predicate<BlockPos> avoider) {
        synchronized (breakMutex) {
            _breakAvoiders.add(avoider);
        }
    }

    public void configurePlaceBucketButDontFall(boolean allow) {
        synchronized (propertiesMutex) {
            _dontPlaceBucketButStillFall = allow;
        }
    }

    public void treatSoulSandAsOrdinaryBlock(boolean enable) {
        synchronized (propertiesMutex) {
            _treatSoulSandAsOrdinaryBlock = enable;
        }
    }

    public void avoidBlockPlace(Predicate<BlockPos> avoider) {
        synchronized (placeMutex) {
            _placeAvoiders.add(avoider);
        }
    }

    public boolean shouldForceSaveTool(BlockState state, ItemStack tool) {
        synchronized (propertiesMutex) {
            return _forceSaveTool.stream().anyMatch(pred -> pred.test(state, tool));
        }
    }

    public boolean shouldAvoidBreaking(int x, int y, int z) {
        return shouldAvoidBreaking(new BlockPos(x, y, z));
    }

    /**
     * WHICH SOURCE refused, counted separately. Read as {@code avoidSrc=set/pred/preds}.
     *
     * <p>mine_coal produced {@code cb=0/818/0/0} with {@code breakFail=0/0/0/0/0}: 818 candidates
     * refused as unbreakable while no break had failed and no ban had been installed. "Something is
     * in the avoid state" is not a diagnosis -- this says whether it is the explicit POSITION set or
     * a PREDICATE, and how many predicates are registered at all. Those have different causes: a
     * position set is somebody protecting a block, a predicate is somebody banning a region, and a
     * predicate COUNT that grows is a push/pop leak.
     */
    public static volatile int avoidHitSet, avoidHitPred, avoidPredCount;

    public boolean shouldAvoidBreaking(BlockPos pos) {
        synchronized (breakMutex) {
            avoidPredCount = _breakAvoiders.size();
            if (_blocksToAvoidBreaking.contains(pos)) {
                avoidHitSet++;
                return true;
            }
            boolean pred = _breakAvoiders.stream().anyMatch(p -> p.test(pos));
            if (pred) {
                avoidHitPred++;
            }
            return pred;
        }
    }

    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        synchronized (placeMutex) {
            return _placeAvoiders.stream().anyMatch(pred -> pred.test(pos));
        }
    }

    public boolean shouldAvoidPlacingAt(int x, int y, int z) {
        return shouldAvoidPlacingAt(new BlockPos(x, y, z));
    }

    public boolean canWalkOnForce(int x, int y, int z) {
        synchronized (propertiesMutex) {
            return _forceCanWalkOn.stream().anyMatch(pred -> pred.test(new BlockPos(x, y, z)));
        }
    }

    public boolean shouldAvoidWalkThroughForce(BlockPos pos) {
        synchronized (propertiesMutex) {
            return _forceAvoidWalkThrough.stream().anyMatch(pred -> pred.test(pos));
        }
    }

    public boolean shouldAvoidWalkThroughForce(int x, int y, int z) {
        return shouldAvoidWalkThroughForce(new BlockPos(x, y, z));
    }

    public boolean shouldForceUseTool(BlockState state, ItemStack tool) {
        synchronized (propertiesMutex) {
            return _forceUseTool.stream().anyMatch(pred -> pred.test(state, tool));
        }
    }

    public boolean shouldNotPlaceBucketButStillFall() {
        synchronized (propertiesMutex) {
            return _dontPlaceBucketButStillFall;
        }
    }

    public boolean shouldTreatSoulSandAsOrdinaryBlock() {
        synchronized (propertiesMutex) {
            return _treatSoulSandAsOrdinaryBlock;
        }
    }

    public boolean isInteractionPaused() {
        synchronized (propertiesMutex) {
            return _pauseInteractions;
        }
    }

    public void setInteractionPaused(boolean paused) {
        synchronized (propertiesMutex) {
            _pauseInteractions = paused;
        }
    }

    public boolean isFlowingWaterPassAllowed() {
        synchronized (propertiesMutex) {
            return _allowFlowingWaterPass;
        }
    }

    public boolean canSwimThroughLava() {
        synchronized (propertiesMutex) {
            return _allowSwimThroughLava;
        }
    }

    public void setFlowingWaterPass(boolean pass) {
        synchronized (propertiesMutex) {
            _allowFlowingWaterPass = pass;
        }
    }

    public void allowSwimThroughLava(boolean allow) {
        synchronized (propertiesMutex) {
            _allowSwimThroughLava = allow;
        }
    }

    public double applyGlobalHeuristic(double prev, int x, int y, int z) {
        return prev;
        /*
        synchronized (globalHeuristicMutex) {
            BlockPos p = new BlockPos(x, y, z);
            for (BiFunction<Double, BlockPos, Double> toApply : _globalHeuristics) {
                prev = toApply.apply(prev, p);
            }
        }
        return prev;
         */
    }

    public HashSet<BlockPos> getBlocksToAvoidBreaking() {
        return _blocksToAvoidBreaking;
    }

    public List<Predicate<BlockPos>> getBreakAvoiders() {
        return _breakAvoiders;
    }

    public List<Predicate<BlockPos>> getPlaceAvoiders() {
        return _placeAvoiders;
    }

    public List<Predicate<BlockPos>> getForceWalkOnPredicates() {
        return _forceCanWalkOn;
    }

    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() {
        return _forceAvoidWalkThrough;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceSaveToolPredicates() {
        return _forceSaveTool;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() {
        return _forceUseTool;
    }

    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() {
        return _globalHeuristics;
    }

    public boolean isItemProtected(Item item) {
        return _protectedItems.contains(item);
    }

    public HashSet<Item> getProtectedItems() {
        return _protectedItems;
    }

    public void protectItem(Item item) {
        _protectedItems.add(item);
    }

    public void stopProtectingItem(Item item) {
        _protectedItems.remove(item);
    }

    public Object getBreakMutex() {
        return breakMutex;
    }

    public Object getPlaceMutex() {
        return placeMutex;
    }

    public Object getPropertiesMutex() {
        return propertiesMutex;
    }

    public Object getGlobalHeuristicMutex() {
        return globalHeuristicMutex;
    }

    public boolean isCanWalkOnEndPortal() {
        return canWalkOnEndPortal;
    }
}
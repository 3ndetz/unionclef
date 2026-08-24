package adris.altoclef;

import adris.altoclef.trackers.threats.DamageTrackerStrategy;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.settings.AltoClefSettings;
import kaptainwutax.tungsten.path.movements.Rotation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Represents the current behaviour/"on the fly settings" of the bot.
 * <p>
 * Use this to change how the bot works for the duration of a task.
 * <p>
 * (for example, "Build this bridge and avoid mining any blocks nearby")
 */
public class BotBehaviour {
    /**
     * Fluid handling for ray traces -- rehomed from the deleted engine (G-0, 2026-08-24).
     *
     * <p>It was a static on baritone's RayTraceUtils that this class saved and restored around
     * actions needing a different fluid rule. The push/pop state still carries the value, so
     * the field has to survive the module removal; only its home changes, to the one class
     * that ever touched it.
     */
    public static net.minecraft.world.RaycastContext.FluidHandling LIVE_RAY_FLUID_HANDLING =
            net.minecraft.world.RaycastContext.FluidHandling.NONE;

    private final AltoClef mod;
    private Predicate<BlockPos> _extraAvoidBlockPlacing = null;
    private Predicate<BlockPos> _extraAvoidBlockBreaking = null;
    Deque<State> states = new ArrayDeque<>();

    private DamageTrackerStrategy _damageTrackerStrategy = DamageTrackerStrategy.Smart;

    public DamageTrackerStrategy getDamageTrackerStrategy() {
        return _damageTrackerStrategy;
    }

    public void setDamageTrackerStrategy(DamageTrackerStrategy strategy) {
        _damageTrackerStrategy = strategy;
    }

    public BotBehaviour(AltoClef mod) {
        this.mod = mod;

        // Start with one state.
        push();
    }

    // Getter(s)

    /**
     * Returns the current state of Behaviour for escapeLava
     *
     * @return The current state of Behaviour for escapeLava
     */
    public boolean shouldEscapeLava() {
        return current().escapeLava;
    }

    /// Parameters

    /**
     * If the bot should escape lava or not, part of WorldSurvivalChain
     *
     * @param allow True if the bot should escape lava
     */
    public void setEscapeLava(boolean allow) {
        current().escapeLava = allow;
        current().applyState();
    }

    public void setFollowDistance(double distance) {
        current().followOffsetDistance = distance;
        current().applyState();
    }

    public void setMineScanDroppedItems(boolean value) {
        current().mineScanDroppedItems = value;
        current().applyState();
    }


    public boolean exclusivelyMineLogs() {
        return current().exclusivelyMineLogs;
    }

    public void setExclusivelyMineLogs(boolean value) {
        current().exclusivelyMineLogs = value;
        current().applyState();
    }

    public boolean shouldExcludeFromForcefield(Entity entity) {
        if (!current().excludeFromForceField.isEmpty()) {
            for (Predicate<Entity> pred : current().excludeFromForceField) {
                if (pred.test(entity)) return true;
            }
        }
        return false;
    }

    public void addForceFieldExclusion(Predicate<Entity> pred) {
        current().excludeFromForceField.add(pred);
        // Not needed, as excludeFromForceField isn't applied anywhere else.
        // current.applyState();
    }

    public List<Pair<Slot, Predicate<ItemStack>>> getConversionSlots() {
        return current().conversionSlots;
    }

    public void markSlotAsConversionSlot(Slot slot, Predicate<ItemStack> itemBelongsHere) {
        current().conversionSlots.add(new Pair<>(slot, itemBelongsHere));
        // apply not needed
    }

    /**
     * WHO last registered a break-avoider, and how many have been registered at all.
     *
     * <p>mine_coal produced {@code cb=0/818/0/0} with {@code breakFail=0/0/0/0/0}: 818 candidates
     * refused as unbreakable while no break had failed and no ban had ever been installed.
     * {@code avoidSrc} then narrowed it to "a predicate registered DURING the run" -- a clean run
     * reads zero predicates, so it is not inherited state and not a push/pop leak.
     *
     * <p>That still leaves "which caller", and this repo has answered that question the same way
     * three times today and got it right every time: stamp the caller at the registration site
     * instead of reasoning about who it might be. Registration is RARE -- a handful of calls per
     * run -- so walking a stack trace here costs nothing, unlike the predicate TEST, which runs a
     * million times a run and must stay arithmetic.
     */
    /**
     * ⛔ THIS STAMP CANNOT NAME AN INHERITED BAN, AND THAT IS WORTH KNOWING BEFORE WAITING FOR ONE.
     *
     * <p>It is cleared by {@code resetRunCounters} at the start of every course, so it can only ever
     * name a caller that registered DURING the run being measured. A predicate carried over from an
     * earlier job was registered before the reset, so this reads "-" for it, permanently.
     *
     * <p>Measured on four mine_coal runs: three read {@code avoidSrc=0/0/0/0@-} (clean), and one
     * read {@code avoidSrc=0/0/1/0@-} -- a predicate PRESENT, zero registered this run, and no
     * caller. The "-" there is not a missing stamp, it IS the finding: nothing registered it here.
     *
     * <p>So the two cases are told apart by the COUNTERS, not by this string: predCount>0 with
     * registered=0 is inherited; registered>0 names its caller here. Anyone chasing the separate
     * 818-refusal case wants the second, and will wait for ever if they expect the first to speak.
     */
    public static volatile String lastBreakAvoiderBy = "-";
    public static volatile int breakAvoidersRegistered;

    /**
     * The same stamp, but NEVER cleared by {@code resetRunCounters} -- so an INHERITED ban can be
     * named at last.
     *
     * <p>{@link #lastBreakAvoiderBy} answers "who registered during THIS run", which is the right
     * question for a ban installed here and the wrong one for a ban carried over: that predicate was
     * registered before the reset, so the per-run stamp reads "-" for it permanently, however many
     * runs are spent waiting. Measured: {@code avoidSrc=0/0/1/0@-}, a predicate present with zero
     * registrations and no caller.
     *
     * <p>This one persists for the life of the client, so the pair reads:
     * <ul>
     *   <li>{@code registered>0} -- installed this run, and lastBreakAvoiderBy names it;</li>
     *   <li>{@code predCount>0, registered=0} -- INHERITED, and THIS field names the caller and run
     *       that installed it.</li>
     * </ul>
     *
     * <p>An instrument that cannot answer the question being asked of it is the defect this repo has
     * paid for most often. This is the one line that lets the separate 818-refusal case be traced
     * rather than guessed at.
     */
    public static volatile String breakAvoiderInstalledBy = "-";

    /** First frame outside this class, so the tag names the CALLER rather than this method. */
    private static String callerTag() {
        try {
            for (StackTraceElement f : new Throwable().getStackTrace()) {
                if (!f.getClassName().endsWith("BotBehaviour")) {
                    String cls = f.getClassName();
                    return cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName()
                            + ":" + f.getLineNumber();
                }
            }
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a registration
        }
        return "?";
    }

    /**
     * ⛔ RE-BANNING AN ALREADY-BANNED POSITION IS NOT FREE, AND IT WAS BEING DONE THOUSANDS OF
     * TIMES A RUN -- ON THE LOCK THE PATHFINDER NEEDS.
     *
     * <p>Measured on a playthrough run: {@code avoidSrc=57/0/2/5212}, i.e. 5212 registrations
     * against 2 predicates actually present. The caller is not doing anything unreasonable --
     * {@code DoCraftInTableTask.onTick} re-asserts "do not break my crafting tables" every tick
     * over every table the scanner knows, which is the correct intent. What made it expensive is
     * that every one of those calls, for a position already in the set, paid:
     *
     * <ul>
     *   <li>{@code new Throwable().getStackTrace()} in {@link #callerTag()} -- a FULL stack
     *       capture, per call, purely to stamp an instrument;</li>
     *   <li>{@link State#applyState} -- three nested mutexes, eight collections cleared and
     *       refilled wholesale, to reproduce a state that was already correct.</li>
     * </ul>
     *
     * <p>The third item is the one that hurts: {@code applyState} takes {@code breakMutex}, and
     * {@code AltoClefSettings.shouldAvoidBreaking} takes the SAME mutex on the pathfinder's
     * hottest path -- roughly a million calls a run. So this was not merely wasted work, it was
     * thousands of lock acquisitions a run contending directly with pathfinding, on a client that
     * this bench has just established is fps-bound.
     *
     * <p>{@code HashSet.add} already answers "did anything change": it returns false when the
     * position was present. If nothing changed there is nothing to apply, so return before doing
     * any of the above. The set ends up identical either way, and the invariant that makes this
     * safe is that the live settings always reflect {@code current()} -- {@code push()} copies the
     * state it inherits and {@code pop()} re-applies, so a no-op add cannot leave settings stale.
     *
     * <p>NOTE, because it changes what an instrument MEANS: the counters below now count DISTINCT
     * bans rather than calls, and {@code lastBreakAvoiderBy} names whoever first installed a
     * position rather than whoever last re-asserted it. That is the more useful reading of both --
     * 5212 was never "how many bans exist", it was "how many times we asked".
     */
    public void avoidBlockBreaking(BlockPos pos) {
        if (!current().blocksToAvoidBreaking.add(pos)) return;   // already banned: nothing changed
        lastBreakAvoiderBy = "pos@" + callerTag();
        breakAvoiderInstalledBy = lastBreakAvoiderBy;  // survives resetRunCounters
        breakAvoidersRegistered++;
        current().applyState();
    }

    public void avoidBlockBreaking(Predicate<BlockPos> pred) {
        lastBreakAvoiderBy = "pred@" + callerTag();
        breakAvoiderInstalledBy = lastBreakAvoiderBy;  // survives resetRunCounters
        breakAvoidersRegistered++;
        current().toAvoidBreaking.add(pred);
        current().applyState();
    }

    public void avoidBlockPlacing(Predicate<BlockPos> pred) {
        current().toAvoidPlacing.add(pred);
        current().applyState();
    }

    public void avoidBlockPlacingExtra(Predicate<BlockPos> pred) {
        _extraAvoidBlockPlacing = pred;
        current().applyState();
    }

    public void resetAvoidBlockPlacingExtra() {
        _extraAvoidBlockPlacing = null;
        current().applyState();
    }

    public void avoidBlockBreakingExtra(Predicate<BlockPos> pred) {
        lastBreakAvoiderBy = "extra@" + callerTag();
        breakAvoiderInstalledBy = lastBreakAvoiderBy;  // survives resetRunCounters
        breakAvoidersRegistered++;
        _extraAvoidBlockBreaking = pred;
        current().applyState();
    }

    public void resetAvoidBlockBreakingExtra() {
        _extraAvoidBlockBreaking = null;
        current().applyState();
    }

    public void allowWalkingOn(Predicate<BlockPos> pred) {
        current().allowWalking.add(pred);
        current().applyState();
    }

    public void avoidWalkingThrough(Predicate<BlockPos> pred) {
        current().avoidWalkingThrough.add(pred);
        current().applyState();
    }


    public void forceUseTool(BiPredicate<BlockState, ItemStack> pred) {
        current().forceUseTools.add(pred);
        current().applyState();
    }

    public void setRayTracingFluidHandling(RaycastContext.FluidHandling fluidHandling) {
        current().rayFluidHandling = fluidHandling;
        //Debug.logMessage("OOF: " + fluidHandling);
        current().applyState();
    }

    public void setAllowWalkThroughFlowingWater(boolean value) {
        current()._allowWalkThroughFlowingWater = value;
        current().applyState();
    }

    public void setPauseOnLostFocus(boolean pauseOnLostFocus) {
        current().pauseOnLostFocus = pauseOnLostFocus;
        current().applyState();
    }

    public void addProtectedItems(Item... items) {
        Collections.addAll(current().protectedItems, items);
        current().applyState();
    }

    public void removeProtectedItems(Item... items) {
        current().protectedItems.removeAll(Arrays.asList(items));
        current().applyState();
    }

    public boolean isProtected(Item item) {
        // For now nothing is protected.
        return current().protectedItems.contains(item);
    }

    public boolean shouldForceFieldPlayers() {
        return current().forceFieldPlayers;
    }

    public void setForceFieldPlayers(boolean forceFieldPlayers) {
        current().forceFieldPlayers = forceFieldPlayers;
        // Not needed, nothing changes.
        // current.applyState()
    }

    /**
     * Adds a predicate that prevents attacking/targeting an entity.
     * If any predicate returns true for an entity, it will NOT be attacked
     * by MobDefenseChain or force field. Useful for no-PvP zones.
     */
    public void addAttackExclusion(Predicate<Entity> pred) {
        current().attackExclusions.add(pred);
    }

    /**
     * Returns true if the entity should NOT be attacked (is in a no-PvP zone, etc.)
     */
    public boolean shouldExcludeFromAttack(Entity entity) {
        for (Predicate<Entity> pred : current().attackExclusions) {
            if (pred.test(entity)) return true;
        }
        return false;
    }

    // --- Camera modifiers (EpicCamera integration) ---
    public void setCameraRotationModifer(Rotation rotation) { AltoClef._cameraRotationModifer = rotation; }
    public void setCameraRotationModifer(float pitch) {
        Rotation current = AltoClef._cameraRotationModifer;
        float yaw = current != null ? current.getYaw() : -500;
        AltoClef._cameraRotationModifer = new Rotation(yaw, pitch);
    }
    public void resetCameraRotationModifer() { AltoClef._cameraRotationModifer = null; }
    public void setCameraPositionModifer(Vec3d pos) { AltoClef._cameraPositionModifer = pos; }
    public void resetCameraPositionModifer() { AltoClef._cameraPositionModifer = null; }

    // --- User task chain priority ---
    private float _userTaskChainPriority = 50;
    public float getUserTaskChainPriority() { return _userTaskChainPriority; }
    public void setUserTaskChainPriority(float priority) { _userTaskChainPriority = priority; }
    public void setDefaultUserTaskChainPriority() { _userTaskChainPriority = 50; }

    public void allowSwimThroughLava(boolean allow) {
        current().swimThroughLava = allow;
        current().applyState();
    }

    public void setPreferredStairs(boolean allow) {
        //current().preferredStairs = allow;
        current().applyState();
    }

    public void setAllowDiagonalAscend(boolean allow) {
        current().allowDiagonalAscend = allow;
        current().applyState();
    }

    public void setBlockPlacePenalty(double penalty) {
        current().blockPlacePenalty = penalty;
        current().applyState();
    }

    public void setBlockBreakAdditionalPenalty(double penalty) {
        current().blockBreakAdditionalPenalty = penalty;
        current().applyState();
    }

    public void avoidDodgingProjectile(Predicate<Entity> whenToDodge) {
        current().avoidDodgingProjectile.add(whenToDodge);
        // Not needed, nothing changes.
        // current().applyState();
    }

    public void addGlobalHeuristic(BiFunction<Double, BlockPos, Double> heuristic) {
        current().globalHeuristics.add(heuristic);
        current().applyState();
    }

    public boolean shouldAvoidDodgingProjectile(Entity entity) {
        if (!current().avoidDodgingProjectile.isEmpty()) {
            for (Predicate<Entity> test : current().avoidDodgingProjectile) {
                if (test.test(entity)) return true;
            }
        }
        return false;
    }

    /// Stack management
    public void push() {
        if (states.isEmpty()) {
            states.push(new State());
        } else {
            // Make copy and push that
            states.push(new State(current()));
        }
    }

    public void push(State customState) {
        states.push(customState);
    }

    public State pop() {
        if (states.isEmpty()) {
            Debug.logError("State stack is empty. This shouldn't be happening.");
            return null;
        }
        State popped = states.pop();
        if (states.isEmpty()) {
            Debug.logError("State stack is empty after pop. This shouldn't be happening.");
            return null;
        }
        states.peek().applyState();
        return popped;
    }

    private State current() {
        if (states.isEmpty()) {
            Debug.logError("STATE EMPTY, UNEMPTIED!");
            push();
        }
        return states.peek();
    }

    private class State {
        /// Baritone Params
        public double followOffsetDistance;
        public HashSet<Item> protectedItems = new HashSet<>();
        public boolean mineScanDroppedItems;
        public boolean swimThroughLava;
        public boolean allowDiagonalAscend;
        //public boolean preferredStairs;
        public double blockPlacePenalty;
        public double blockBreakAdditionalPenalty;

        // Alto Clef params
        public boolean exclusivelyMineLogs;
        public boolean forceFieldPlayers;
        public List<Predicate<Entity>> avoidDodgingProjectile = new ArrayList<>();
        public List<Predicate<Entity>> excludeFromForceField = new ArrayList<>();
        public List<Predicate<Entity>> attackExclusions = new ArrayList<>();
        public List<Pair<Slot, Predicate<ItemStack>>> conversionSlots = new ArrayList<>();

        // Extra Baritone Settings
        public HashSet<BlockPos> blocksToAvoidBreaking = new HashSet<>();
        public List<Predicate<BlockPos>> toAvoidBreaking = new ArrayList<>();
        public List<Predicate<BlockPos>> toAvoidPlacing = new ArrayList<>();
        public List<Predicate<BlockPos>> allowWalking = new ArrayList<>();
        public List<Predicate<BlockPos>> avoidWalkingThrough = new ArrayList<>();
        public List<BiPredicate<BlockState, ItemStack>> forceUseTools = new ArrayList<>();
        public List<BiFunction<Double, BlockPos, Double>> globalHeuristics = new ArrayList<>();
        public boolean _allowWalkThroughFlowingWater = false;

        // Minecraft config
        public boolean pauseOnLostFocus = true;

        // Hard coded stuff
        public RaycastContext.FluidHandling rayFluidHandling;

        // Other necessary stuff
        public boolean escapeLava = true;

        public State() {
            this(null);
        }

        public State(State toCopy) {
            // Read in current state
            readState();

            readExtraState(mod.getExtraBaritoneSettings());

            readMinecraftState();

            if (toCopy != null) {
                // Copy over stuff from old one
                exclusivelyMineLogs = toCopy.exclusivelyMineLogs;
                avoidDodgingProjectile.addAll(toCopy.avoidDodgingProjectile);
                excludeFromForceField.addAll(toCopy.excludeFromForceField);
                attackExclusions.addAll(toCopy.attackExclusions);
                conversionSlots.addAll(toCopy.conversionSlots);
                forceFieldPlayers = toCopy.forceFieldPlayers;
                escapeLava = toCopy.escapeLava;
            }
        }

        /**
         * Make the current state match our copy
         */
        public void applyState() {
            applyState(mod.getExtraBaritoneSettings());
        }

        /**
         * Read in a copy of the current state
         */
        private void readState() {
            // G-0: these six were tuning knobs on the LEGACY pathfinder's cost model -- follow
            // offset, dropped-item scanning, walking on lava, diagonal ascents, and the two
            // place/break penalties. That pathfinder is gone, so there is nothing left to read them
            // from and nothing left that consumes them. The fields stay because the push/pop state
            // is public API inside altoclef and callers still set them; they simply no longer
            // travel through another module's settings object to do it.
        }

        private void readExtraState(AltoClefSettings settings) {
            synchronized (settings.getBreakMutex()) {
                synchronized (settings.getPlaceMutex()) {
                    blocksToAvoidBreaking = new HashSet<>(settings.getBlocksToAvoidBreaking());
                    // ⛔ DO NOT COPY THE PERSISTENT EXTRA INTO THE STACK IT LIVES OUTSIDE OF.
                    //
                    // applyState() appends _extraAvoidBlockBreaking to the live list every time,
                    // and this reads that live list back when a state is pushed. So a push taken
                    // while a ban is active BAKES the ban into that state's own toAvoidBreaking --
                    // and from there resetAvoidBlockBreakingExtra() can never remove it, because
                    // that method nulls the extra slot and rebuilds from the state list which now
                    // contains a copy.
                    //
                    // MineAndCollectTask.onResourceStart pushes on every resource task, so this is
                    // the ordinary path, not a corner. It is also why clearing the extra at task
                    // end -- the fix committed an hour ago -- is necessary but NOT sufficient on
                    // its own: the copy survives the clear.
                    //
                    // The extra is documented as living "outside push/pop stack". Excluding it here
                    // is what makes that comment true.
                    toAvoidBreaking = new ArrayList<>(settings.getBreakAvoiders());
                    if (_extraAvoidBlockBreaking != null) {
                        toAvoidBreaking.remove(_extraAvoidBlockBreaking);
                    }
                    // Same for the placing twin, for the same reason.
                    toAvoidPlacing = new ArrayList<>(settings.getPlaceAvoiders());
                    if (_extraAvoidBlockPlacing != null) {
                        toAvoidPlacing.remove(_extraAvoidBlockPlacing);
                    }
                    protectedItems = new HashSet<>(settings.getProtectedItems());
                    synchronized (settings.getPropertiesMutex()) {
                        allowWalking = new ArrayList<>(settings.getForceWalkOnPredicates());
                        avoidWalkingThrough = new ArrayList<>(settings.getForceAvoidWalkThroughPredicates());
                        forceUseTools = new ArrayList<>(settings.getForceUseToolPredicates());
                    }
                }
            }
            synchronized (settings.getGlobalHeuristicMutex()) {
                globalHeuristics = new ArrayList<>(settings.getGlobalHeuristics());
            }
            _allowWalkThroughFlowingWater = settings.isFlowingWaterPassAllowed();

            rayFluidHandling = LIVE_RAY_FLUID_HANDLING;
        }

        private void readMinecraftState() {
            pauseOnLostFocus = MinecraftClient.getInstance().options.pauseOnLostFocus;
        }

        /**
         * Make the current state match our copy
         */
        private void applyState(AltoClefSettings sa) {
            // (the five legacy cost-model writes that stood here went with the engine)

            // We need an alternrative method to handle this, this method makes navigation much less reliable.
            //s.allowDownward.value = preferredStairs;

            // Kinda jank but it works.
            synchronized (sa.getBreakMutex()) {
                synchronized (sa.getPlaceMutex()) {
                    sa.getBreakAvoiders().clear();
                    sa.getBreakAvoiders().addAll(toAvoidBreaking);
                    sa.getBlocksToAvoidBreaking().clear();
                    sa.getBlocksToAvoidBreaking().addAll(blocksToAvoidBreaking);
                    sa.getPlaceAvoiders().clear();
                    sa.getPlaceAvoiders().addAll(toAvoidPlacing);
                    // Persistent extra predicates (outside push/pop stack)
                    if (_extraAvoidBlockPlacing != null) {
                        sa.getPlaceAvoiders().add(_extraAvoidBlockPlacing);
                    }
                    if (_extraAvoidBlockBreaking != null) {
                        sa.getBreakAvoiders().add(_extraAvoidBlockBreaking);
                    }
                    sa.getProtectedItems().clear();
                    sa.getProtectedItems().addAll(protectedItems);
                    synchronized (sa.getPropertiesMutex()) {
                        sa.getForceWalkOnPredicates().clear();
                        sa.getForceWalkOnPredicates().addAll(allowWalking);
                        sa.getForceAvoidWalkThroughPredicates().clear();
                        sa.getForceAvoidWalkThroughPredicates().addAll(avoidWalkingThrough);
                        sa.getForceUseToolPredicates().clear();
                        sa.getForceUseToolPredicates().addAll(forceUseTools);
                    }
                }
            }
            synchronized (sa.getGlobalHeuristicMutex()) {
                sa.getGlobalHeuristics().clear();
                sa.getGlobalHeuristics().addAll(globalHeuristics);
            }


            sa.setFlowingWaterPass(_allowWalkThroughFlowingWater);
            sa.allowSwimThroughLava(swimThroughLava);

            // Extra / hard coded.
            // Mirrored into tungsten: LookHelper's reach tests and isLookingAt() now raytrace
            // through RotationHelper, which keeps its own copy of this flag. Without the mirror,
            // "let me aim at a fluid" (bucket pickup, MLG water recovery, ClearLiquidTask) would
            // set the baritone flag and then raytrace with the tungsten one still on NONE — water
            // would stay invisible to the aim and the click would never be armed.
            LIVE_RAY_FLUID_HANDLING = rayFluidHandling;
            kaptainwutax.tungsten.path.movements.RotationHelper.fluidHandling = rayFluidHandling;

            // Minecraft
            MinecraftClient.getInstance().options.pauseOnLostFocus = pauseOnLostFocus;
        }
    }
}

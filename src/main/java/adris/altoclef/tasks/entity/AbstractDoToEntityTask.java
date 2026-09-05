package adris.altoclef.tasks.entity;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.KillAuraHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

/**
 * Interacts with an entity while maintaining distance.
 * <p>
 * The interaction is abstract.
 */
public abstract class AbstractDoToEntityTask extends Task implements ITaskRequiresGrounded {

    /** Which entity the current pursuit is about, and when it began -- see the budget below. */
    private Entity pursuitEntity = null;
    private long pursuitStartMs = 0L;
    /** Ninety seconds: generous for a chase, far short of a run. */
    private static final long ENTITY_BUDGET_MS = 90_000L;
    /** Entities blacklisted because the pursuit ran past its budget. */
    public static volatile int entityBudgetSpent;
    protected final MovementProgressChecker progress = new MovementProgressChecker();
    /** Why the interact gate refuses, counted per condition. Read over py4j as dte=... */
    public static volatile int dteGate, dteInRange, dteHungry, dteFalling, dteMlg, dteUnsafe;

    private final double maintainDistance;
    private final double combatGuardLowerRange;
    private final double combatGuardLowerFieldRadius;
    private TimeoutWanderTask wanderTask;

    protected AbstractDoToEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        this.maintainDistance = maintainDistance;
        this.combatGuardLowerRange = combatGuardLowerRange;
        this.combatGuardLowerFieldRadius = combatGuardLowerFieldRadius;
    }

    protected AbstractDoToEntityTask(double maintainDistance) {
        this(maintainDistance, 0, Double.POSITIVE_INFINITY);
    }

    protected AbstractDoToEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        this(-1, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        progress.reset();
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        } // Kinda duct tape but it should be future proof ish
    }

    /** Walking over a drop collects it from about a block away; attack reach is irrelevant. */
    private static final double PICKUP_RANGE_SQ = 2.0D * 2.0D;
    /** Ticks a drop was near enough to walk onto but not 'hittable'. */
    public static volatile int dteItemNearNotHittable;

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // TODOS.md, the stall-detector-wipe pattern already fixed in TimeoutWanderTask/
        // DestroyBlockTask: a mere search makes Nav.isPathing() true too, so resetting
        // unconditionally could hide a genuine stall for as long as a search never resolves.
        progress.resetIfPathingWithGrace(mod, Nav.isPathing());

        Optional<Entity> checkEntity = getEntityTarget(mod);


        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetTargetEntity();
            mod.getMobDefenseChain().resetForceField();
        } else {
            mod.getMobDefenseChain().setTargetEntity(checkEntity.get());
        }
        if (checkEntity.isPresent()) {
            Entity entity = checkEntity.get();

            double playerReach = mod.getModSettings().getEntityReachRange();

            double sqDist = entity.squaredDistanceTo(mod.getPlayer());

            if (sqDist < combatGuardLowerRange * combatGuardLowerRange) {
                mod.getMobDefenseChain().setForceFieldRange(combatGuardLowerFieldRadius);
            } else {
                mod.getMobDefenseChain().resetForceField();
            }

            // maintainDistance: how close the pathfinder should bring us.
            // For combat entities (maintainDistance < 0), approach to within 2 blocks — NOT playerReach-1.
            // playerReach-1 was too far (3.0) and left the bot staring.
            double maintainDistance = this.maintainDistance >= 0 ? this.maintainDistance : 2.0;

            boolean tooClose = sqDist < maintainDistance * maintainDistance;

            // G-0: THE "STEP AWAY" HERE WENT TO AN ENGINE THAT DOES NOT DRIVE, SO IT IS GONE.
            // It handed a GoalRunAway to getCustomGoalProcess().setGoalAndPath -- the legacy
            // engine, which has not moved the body since tungsten became the default -- so the
            // backing-off it describes has not happened for a long time regardless of this line.
            //
            // NOT replaced with a real step-away on the live drive, deliberately. That would be a
            // BEHAVIOUR change on the combat path, which is deprioritised, and it belongs in a pass
            // that can measure whether backing off actually helps rather than one clearing imports.
            // The `tooClose` computation above stays: it still feeds the counters below.
            //
            // Gate: mob_melee, baselined before the change at PASS (zombie dead, mdTung 51,
            // min_hp 14.0).

            boolean inRange = mod.getControllerExtras().inRange(entity);
            // A DROPPED ITEM IS NOT HIT, IT IS WALKED OVER.
            // inRange() is canHitEntity(): line of sight plus ATTACK reach. That is the right
            // question for a mob and the wrong one for a drop -- an item has a tiny hitbox,
            // sits just off the floor (dy=+0.4 in the captured case) and the aim ray misses
            // it, so the approach never reports arrival. Measured: dte=4720/0 -- four thousand
            // seven hundred gate ticks and NOT ONCE in range, while the target sat two steps
            // away. The task then wanders, returns, and tries again: the operator watched this
            // from the outside and called it worse than a freeze, and pacing.py put four of
            // twelve trajectory returns on exactly this Approach-entity <-> Wander pair.
            if (kaptainwutax.tungsten.TungstenConfig.get().itemPickupIsDistanceNotReach
                    && entity instanceof net.minecraft.entity.ItemEntity) {
                boolean near = sqDist <= PICKUP_RANGE_SQ;
                if (near && !inRange) dteItemNearNotHittable++;
                inRange = near;
            }
            // ⭐ CLOSE UNTIL THE SWING GATE IS SATISFIED, NOT UNTIL inRange IS. inRange is 4.5 and
            // the swing gate's REACH is 3.0, so the branch below used to stop the walk a block and a
            // half short and leave the bot standing where it cannot hit -- 34.8 ticks a fight,
            // measured. Combat entities only (maintainDistance < 0); shearing and milking keep the
            // old arrival test, because their interaction range is not the sword's.
            if (kaptainwutax.tungsten.TungstenConfig.get().combatCloseToReach
                    && this.maintainDistance < 0
                    && sqDist > kaptainwutax.tungsten.combat.TriggerBot.REACH
                             * kaptainwutax.tungsten.combat.TriggerBot.REACH) {
                inRange = false;
            }

            // WHICH OF THE FIVE CONDITIONS IS SAYING NO?
            // The bot sat in "Approaching target" for ever with a zombie four blocks away, and the
            // child task's counters could not say why: they live past this gate. Five conditions
            // guard it and the debug state names none of them, so count each separately rather
            // than guess which one is false.
            dteGate++;
            if (inRange) dteInRange++;
            if (mod.getFoodChain().needsToEat()) dteHungry++;
            if (mod.getMLGBucketChain().isFalling(mod)) dteFalling++;
            if (!mod.getMLGBucketChain().doneMLG()) dteMlg++;
            if (!Nav.isSafeToCancel()) dteUnsafe++;

            // Interact when in range. Only gate on inRange (canHitEntity) — the old raycast check
            // was "basically useless" and blocked interaction most of the time.
            // ⛔⭐ THE 34.8 TICKS OF "IN THE BAND, OUT OF REACH" ARE BORN ON THIS LINE (2026-08-13).
            // Approaching and striking are EXCLUSIVE branches of one if. While inRange is false the
            // task returns GetToEntityTask and the body walks; the moment inRange turns true it
            // returns the interaction instead, and CLOSING STOPS.
            //
            // But inRange is distance < 4.5 (ControllerExtras) while the swing gate's REACH is 3.0.
            // Between them lies a block and a half in which the bot counts as "arrived" and stops
            // walking, yet cannot hit anything. Measured on mob_skeleton over 25 runs: 34.8 ticks
            // per fight of "controller running, target not reachable" -- the single largest swing
            // refusal, ahead of cooldown at 19.3.
            //
            // It is not a constant that wants nudging: "arrived" and "can hit" are two different
            // distances and this code treats them as one. Whoever fixes it should keep closing
            // until the SWING gate is satisfied, not until inRange is.
            //
            // This also finishes off combatEngageBand (+0.88 arrows, 1.90 sigma, off): it started
            // the fight earlier across the band, but closing still stopped at 4.5, so the bot simply
            // waited inside a different counter.
            if (inRange && !mod.getFoodChain().needsToEat() &&
                    !mod.getMLGBucketChain().isFalling(mod) && mod.getMLGBucketChain().doneMLG() &&
                    !mod.getMLGBucketChain().isChorusFruiting() &&
                    Nav.isSafeToCancel()) {
                progress.reset();
                return onEntityInteract(mod, entity);
            } else if (!tooClose) {
                setDebugState("Approaching target");
                // ⛔ A STEADY WALK NEVER TRIPS A PROGRESS CHECK, AND THAT IS THE WHOLE BUG.
                //
                // The give-up below fires when `progress` says the body has stopped. A bot walking
                // toward a sheep sixty-seven blocks away is progressing the entire time, so it
                // never fires -- and after stone tools the playthrough wants a BED, which wants
                // WOOL, which wants SHEEP. Measured on a twenty-minute run:
                //
                //     lock=13/4/18@sheep:67.6>67.6, m0.0, h67.5 @bot[372.71,96.00,72.50]
                //                  sheep:41.2>41.2, m0.0, h41.2 @bot[422.35,95.00,113.30]
                //     avoidSrc=...@PlaceBedAndSetSpawnTask.onStart:147
                //
                // Thirteen barren locks against four productive, the ladder frozen at stone tools
                // for the last fifteen minutes, and the run ending with an empty pack.
                //
                // This is the same defect the drop pursuit had, and the same medicine: a ceiling on
                // what ONE target may cost, independent of whether the walk is progressing. The
                // drop version fired and shipped today (dropBudget=1).
                if (kaptainwutax.tungsten.TungstenConfig.get().entityPursuitHasBudget) {
                    if (entity != pursuitEntity) {
                        pursuitEntity = entity;
                        pursuitStartMs = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - pursuitStartMs > ENTITY_BUDGET_MS) {
                        Debug.logMessage("Entity has cost more than its budget — blacklisting it.");
                        entityBudgetSpent++;
                        pursuitEntity = null;
                        progress.reset();
                        mod.getEntityTracker().requestEntityUnreachable(entity);
                        return null;
                    }
                }
                if (!progress.check(mod)) {
                    progress.reset();
                    Debug.logMessage("Failed to get to target, blacklisting.");
                    mod.getEntityTracker().requestEntityUnreachable(entity);
                }
                // Approach tightly — 1 block for close range, maintainDistance for far
                double approachDist = (sqDist < playerReach * playerReach * 2.0) ? 1.0 : maintainDistance;
                return new GetToEntityTask(entity, approachDist);
            }
        }
        if (BeatMinecraftTask.isTaskRunning(mod,wanderTask)) {
            return wanderTask;
        }

        if (!Nav.isSafeToCancel()) {
            return null;
        }
        wanderTask = new TimeoutWanderTask();
        return wanderTask;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof AbstractDoToEntityTask task) {
            if (!doubleCheck(task.maintainDistance, maintainDistance)) return false;
            if (!doubleCheck(task.combatGuardLowerFieldRadius, combatGuardLowerFieldRadius)) return false;
            if (!doubleCheck(task.combatGuardLowerRange, combatGuardLowerRange)) return false;
            return isSubEqual(task);
        }
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean doubleCheck(double a, double b) {
        // ⛔ FIXED 2026-09-05: `Double.isInfinite(a) == Double.isInfinite(b)` is true for EVERY
        // pair of finite values (false == false), not just for the intended "both infinite"
        // shortcut -- so this returned true (equal) for any two finite doubles whatsoever,
        // e.g. doubleCheck(2.0, 50.0) == true, and the abs()-closeness line below was dead code
        // (only reachable when exactly one side is infinite, where abs(a-b) is itself infinite
        // and so always fails the < 0.1 test anyway). Net effect: isEqual() above never actually
        // distinguished two AbstractDoToEntityTask instances by maintainDistance,
        // combatGuardLowerFieldRadius or combatGuardLowerRange -- only isSubEqual() mattered.
        // Intent was clearly "treat infinite as its own equality class, otherwise compare
        // numerically" -- gate the shortcut on EITHER side being infinite, not on their
        // infinite-ness matching.
        if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.isInfinite(a) == Double.isInfinite(b);
        return Math.abs(a - b) < 0.1;
    }

    protected abstract boolean isSubEqual(AbstractDoToEntityTask other);

    protected abstract Task onEntityInteract(AltoClef mod, Entity entity);

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        mod.getMobDefenseChain().setTargetEntity(null);
        mod.getMobDefenseChain().resetForceField();
        KillAuraHelper.stopCombatMovement(mod);
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}

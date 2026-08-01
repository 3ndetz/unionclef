package kaptainwutax.tungsten.path.movements;

import java.util.List;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary;
import kaptainwutax.tungsten.util.WindMouseRotation;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.AirBlock;
import net.minecraft.block.AzaleaBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.WorldView;

/**
 * The CROSS-MOVEMENT sprint policy — a port of {@code PathExecutor.shouldSprintNextTick}
 * (baritone/src/main/java/baritone/pathing/path/PathExecutor.java:345-474) together with its three
 * helpers {@code sprintableAscend} (:530-568), {@code canSprintFromDescendInto} (:570-581) and
 * {@code skipNow} (:515-528), plus {@code overrideFall} (:476-513) which only the fall branch uses.
 *
 * <h2>Why this file exists, and why it must land WITH the teardown</h2>
 *
 * {@link MovementQueue} is a port of {@code PathExecutor.onTick}, but its sprint half was never
 * carried over. Landing upstream's sprint TEARDOWN alone —
 * <pre>
 *   if (!movement.sprintRequested()) player.setSprinting(false);   // PathExecutor.java:239-241
 * </pre>
 * was measured on the stand and made {@code nav_gaps} go 12/12 -&gt; 11/12, i.e. it broke the one
 * course that is nothing but sprint distance. The reason is structural, not a tuning accident:
 * upstream's teardown is one half of a mechanism whose other half is THIS file. Only
 * {@link MovementTraverse} and {@link MovementDiagonal} ever set {@code Input.SPRINT} in tungsten,
 * so with no policy the teardown fires on very nearly every tick; upstream never has that problem
 * because a movement that does not request sprint can still be sprinted THROUGH when the policy
 * looks one to three movements ahead and says so. Half a mechanism is the defect class this package
 * keeps paying for. Ship the two together or ship neither.
 *
 * <h2>Shape of the API, and why it is not a bare boolean</h2>
 *
 * Upstream's method returns a boolean but is not a predicate: on five of its paths it also
 * <em>advances the path position and re-enters {@code onTick()}</em> (:363-367, :411-413, :427-431,
 * :461-465), forces {@code Input.JUMP} on (:366) or off (:446), and on one path clears the keys,
 * retargets the camera and presses {@code MOVE_FORWARD} (:467-470). Returning only the boolean
 * would drop exactly the half of the method that makes sprint-ascends and fall-overshoots work.
 * So {@link #shouldSprintNextTick} returns a {@link Decision}: the sprint answer PLUS the effects,
 * which the queue applies because the queue owns the index and the tick's key writes. The three
 * {@code apply*} statics below do the applying, so the call site stays three lines.
 *
 * <p>One upstream side effect is NOT deferred: {@code MovementDescend.forceSafeMode()} (:397). That
 * mutates the movement object itself, at that exact point in upstream's order, and a caller that
 * forgot to apply it would silently sprint a descend upstream had decided was unsafe. It is done
 * inline and flagged at the site.
 *
 * <h2>Substitutions (each named at its use site as well)</h2>
 * <ul>
 *   <li>{@code IPlayerContext} -&gt; {@code (WorldView, ClientPlayerEntity)}, the package's standard
 *       substitution. {@code ctx.playerFeet()} is {@link RotationHelper#playerFeet}.</li>
 *   <li>{@code path.movements()} / {@code pathPosition} -&gt; the queue's {@code List<Movement>} and
 *       its {@code index}. Upstream's {@code path.length()} counts POSITIONS, so
 *       {@code path.length() - 1} is {@code movements.size()} — the same identity
 *       {@code MovementQueue.tick} already documents. Every bound below is converted with that and
 *       the conversion is written out.</li>
 *   <li>{@code InputOverrideHandler.isInputForcedDown(SPRINT)} -&gt;
 *       {@link Movement#sprintRequested()}, which is the same question one layer down (the movement
 *       declared SPRINT for the tick that just ran).</li>
 *   <li>{@code setInputForceState(SPRINT, false)} + {@code PathingBehavior.onPlayerSprintState}
 *       (which feeds the {@code KeyBinding.isPressed()} redirect in
 *       MixinClientPlayerEntity.java:110-132) -&gt; {@link #applySprint}, which overwrites the sprint
 *       KEY after the movement applied its own inputs. Same net effect: the policy, not the
 *       movement, is the last word on sprint for the tick.</li>
 *   <li>Baritone settings -&gt; upstream DEFAULTS hardcoded as the constants below, each with its
 *       {@code Settings.java} line, as every other port in this package does.</li>
 *   <li>{@code MovementParkour} — no tungsten class. See the ADAPTER note in the descend branch: the
 *       disjunct is written out as a named constant {@code false}, never deleted.</li>
 *   <li>{@code AltoClefSettings.shouldAvoidWalkThroughForce} — no tungsten equivalent, DROPPED, and
 *       marked DROPPED at the two places it appears (the same convention as
 *       {@link MovementHelperB}).</li>
 * </ul>
 */
public final class SprintPolicy {

    private SprintPolicy() {}

    // ---------------------------------------------------------------------------------------
    // Settings, hardcoded at baritone's verified defaults (no tungsten equivalent exists).
    // ---------------------------------------------------------------------------------------

    /** {@code Baritone.settings().allowSprint} (Settings.java:69) — default true. Half of
     *  {@code CalculationContext.canSprint} (CalculationContext.java:108). */
    private static final boolean ALLOW_SPRINT = true;

    /** {@code Baritone.settings().sprintAscends} (Settings.java:358) — default true. */
    private static final boolean SPRINT_ASCENDS = true;

    /** {@code Baritone.settings().allowOvershootDiagonalDescend} (Settings.java:551) — default true. */
    private static final boolean ALLOW_OVERSHOOT_DIAGONAL_DESCEND = true;

    /** {@code Baritone.settings().allowPlace} (Settings.java:74) — default true. Read only by the
     *  {@code couldPlaceInstead} clause in the descend/frost-walker branch. */
    private static final boolean ALLOW_PLACE = true;

    /** The food level {@code CalculationContext.canSprint} demands (CalculationContext.java:108).
     *  Vanilla stops sprinting at 6 or below, hence the strict {@code >}. */
    private static final int SPRINT_HUNGER_FLOOR = 6;

    // ---------------------------------------------------------------------------------------
    // The result
    // ---------------------------------------------------------------------------------------

    /**
     * What one call of the policy decided: the sprint answer, and the effects upstream applies
     * itself but which belong to the queue here (the index) or to the tick's key writes.
     *
     * <p>Immutable and allocation-cheap; the two common answers are the shared {@link #NO} and
     * {@link #YES} instances.
     */
    public static final class Decision {

        /** {@code shouldSprintNextTick}'s return value — upstream's {@code sprintNextTick}, which
         *  {@code PathingBehavior.onPlayerSprintState} feeds to the {@code KeyBinding.isPressed()}
         *  redirect (PathingBehavior.java:110-115). */
        public final boolean sprint;

        /** The index the queue must jump to, or -1 for "stay". Upstream writes this as
         *  {@code pathPosition++} (:364, :411, :428) or {@code pathPosition = indexOf(fallDest)}
         *  (:462) and then RECURSES into {@code onTick()}, so the queue must re-enter its advance
         *  loop after applying it — a plain index write without the re-entry loses the tick. A value
         *  equal to {@code movements.size()} means "the chain is finished", which the queue's own
         *  {@code index >= movements.size()} branch already handles. */
        public final int nextIndex;

        /** {@code TRUE} = force JUMP on (:366), {@code FALSE} = force JUMP off (:446),
         *  {@code null} = upstream touched the jump input on neither path, so leave it alone. */
        public final Boolean jump;

        /** The fall-override steer (:467-470): the unforced camera target. {@code null} on every
         *  other path. */
        public final Rotation aim;

        /** The fall-override steer: {@code setInputForceState(MOVE_FORWARD, true)} (:469). */
        public final boolean moveForward;

        /** The fall-override steer: {@code clearKeys()} (:467), which must run BEFORE the aim and
         *  the forward press or it erases them. */
        public final boolean clearKeys;

        private Decision(boolean sprint, int nextIndex, Boolean jump, Rotation aim,
                         boolean moveForward, boolean clearKeys) {
            this.sprint = sprint;
            this.nextIndex = nextIndex;
            this.jump = jump;
            this.aim = aim;
            this.moveForward = moveForward;
            this.clearKeys = clearKeys;
        }

        /** Does this decision ask the queue to move its index and re-enter? */
        public boolean advances() {
            return nextIndex >= 0;
        }

        /** Does this decision write anything other than the sprint key? */
        public boolean hasSteer() {
            return clearKeys || moveForward || aim != null;
        }
    }

    /** "return false" — every plain refusal in upstream's method. */
    public static final Decision NO = new Decision(false, -1, null, null, false, false);

    /** "return true" with no side effect — upstream's {@code if (requested) return true;} (:375-377)
     *  and the sprintable-ascend mirror at :450-452. */
    public static final Decision YES = new Decision(true, -1, null, null, false, false);

    // ---------------------------------------------------------------------------------------
    // The policy
    // ---------------------------------------------------------------------------------------

    /**
     * {@code PathExecutor.shouldSprintNextTick} (:345-474), in upstream's order, branch for branch.
     *
     * <p>Call it ONCE per tick, from the point in {@code MovementQueue.tick} that corresponds to
     * PathExecutor.java:238 — that is, after {@code movement.update()} has returned a status that is
     * neither SUCCESS nor UNREACHABLE/FAILED, and before {@code ticksOnCurrent++}. Calling it
     * anywhere else changes what {@link Movement#sprintRequested()} means, because that flag is
     * written by {@code update()} and describes the tick that just ran.
     *
     * @param movements the queue's chain, upstream's {@code path.movements()}
     * @param index     the queue's current step, upstream's {@code pathPosition}
     * @param player    the ticking player; the world is read off it
     * @return never null; see {@link Decision}
     */
    public static Decision shouldSprintNextTick(List<Movement> movements, int index,
                                                ClientPlayerEntity player) {
        // Not upstream: PathExecutor cannot be called with an out-of-range position or a null
        // player, MovementQueue can (a chain that emptied, a disconnect between ticks). Refusing to
        // sprint is the no-op answer; nothing below is meaningful without a current movement.
        if (movements == null || player == null || index < 0 || index >= movements.size()) {
            return NO;
        }
        WorldView world = player.getEntityWorld();
        if (world == null) {
            return NO;
        }

        // :346 — "did the movement that just ran ask for sprint?". Read BEFORE anything else, as
        // upstream, because upstream's very next line takes the request away.
        Movement current = movements.get(index);
        boolean requested = current.sprintRequested();

        // :348-349 "we'll take it from here, no need for minecraft to see we're holding down control
        // and sprint for us". ADAPTER: there is no force map to clear here — the movement already
        // pressed the real sprint key in Movement.applyInputs. The equivalent is that applySprint()
        // OVERWRITES that key with this decision, which is what makes the policy the last word.

        // :351-354. First and foremost: allowSprint off, or not enough hunger, means no sprint.
        if (!canSprint(player)) {
            return NO;
        }

        // :357-372. Traverse requests sprinting, so this check comes BEFORE the `requested`
        // short-circuit: a traverse that is about to become a sprint-ascend must be skipped now,
        // not merely sprinted.
        // `pathPosition < path.length() - 3` is `index < movements.size() - 2`, which is exactly the
        // bound that makes movements.get(index + 2) legal.
        if (current instanceof MovementTraverse && index < movements.size() - 2) {
            Movement next = movements.get(index + 1);
            if (next instanceof MovementAscend
                    && sprintableAscend(world, (MovementTraverse) current,
                            (MovementAscend) next, movements.get(index + 2))) {
                if (skipNow(player, current)) {
                    Debug.logMessage("SprintPolicy: skipping traverse to straight ascend");
                    // :364-367 — advance, re-enter onTick, and THEN force JUMP on top of whatever
                    // the newly-current movement pressed. The jump must be applied after the
                    // re-entry, which is why it travels in the Decision instead of being pressed
                    // here.
                    return new Decision(true, index + 1, Boolean.TRUE, null, false, false);
                }
                // :369. Per-tick line, so verbose-gated — upstream's logDebug is behind chatDebug
                // for the same reason.
                if (TungstenConfig.get().verboseDebugLogging) {
                    Debug.logMessage("SprintPolicy: too far to the side to safely sprint ascend");
                }
            }
        }

        // :375-377. If the movement itself asked, we're done.
        if (requested) {
            return YES;
        }

        // :379-437. Descend and ascend do NOT request sprinting, because a movement cannot see what
        // comes after it. This is where that context is supplied.
        if (current instanceof MovementDescend) {

            if (index < movements.size() - 1) {   // :382, `pathPosition < path.length() - 2`
                // :383-384 "keep this out of onTick, even if that means a tick of delay before it
                // has an effect"
                Movement next = movements.get(index + 1);
                if (MovementHelperB.canUseFrostWalker(player, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some
                    // cases we may not overshoot. Since MovementDescend can't know the next movement
                    // we have to tell it.
                    // ADAPTER: upstream's condition is
                    // `next instanceof MovementTraverse || next instanceof MovementParkour`.
                    // Tungsten has no MovementParkour port, so that disjunct is a constant false; it
                    // is written out as a named local rather than deleted, so wiring a parkour class
                    // later is one line and not an archaeology exercise. MovementFallback is NOT a
                    // substitute for it — it is a dumb steer for edges no class owns, and treating
                    // it as a parkour would flip couldPlaceInstead on for shapes upstream never
                    // meant.
                    final boolean nextIsParkour = false;   // ADAPTER: no MovementParkour in tungsten
                    if (next instanceof MovementTraverse || nextIsParkour) {
                        // :389 — traverse doesn't react fast enough, so only a parkour could place
                        // instead of taking the frost-walker route. With nextIsParkour constant
                        // false this is always false; the throwaway test is kept live and evaluated
                        // so the clause stays honest about what it costs.
                        boolean couldPlaceInstead = ALLOW_PLACE && hasThrowaway(player) && nextIsParkour;
                        // :390-395, upstream's comment kept because the maths is the explanation:
                        // this is true if the next movement does not ascend or descend and goes into
                        // the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend. In that
                        // case current.getDirection() is e.g. (0,-1,1) and next.getDirection() is
                        // e.g. (0,0,3), so the cross product of (0,0,1) and (0,0,3) is taken, which
                        // is (0,0,0) because the vectors are colinear (don't form a plane). Since
                        // movements in exactly the opposite direction (e.g. descend (0,-1,1) and
                        // traverse (0,0,-1)) would also pass this check we also have to rule out
                        // that case; we can do that by adding the directions, because traverse is
                        // always 1 long like descend and parkour can't jump through
                        // current.getSrc().down().
                        boolean sameFlatDirection =
                                !current.getDirection().up().add(next.getDirection()).equals(BlockPos.ORIGIN)
                                        && current.getDirection().up().crossProduct(next.getDirection())
                                                .equals(BlockPos.ORIGIN);
                        if (sameFlatDirection && !couldPlaceInstead) {
                            // :397. NOT deferred to the caller: this mutates the movement object at
                            // exactly this point in upstream's order, and the very next branch reads
                            // safeMode() back. A caller that forgot to apply it would sprint a
                            // descend upstream had just decided was unsafe.
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            // :402-405
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                if (TungstenConfig.get().verboseDebugLogging) {
                    Debug.logMessage("SprintPolicy: sprinting would be unsafe");
                }
                return NO;
            }

            if (index < movements.size() - 1) {   // :407, `pathPosition < path.length() - 2`
                Movement next = movements.get(index + 1);
                if (next instanceof MovementAscend
                        && current.getDirection().up().equals(next.getDirection().down())) {
                    // :409-417 — a descend then an ascend in the same direction. "okay to skip
                    // clearKeys and / or onChangeInPathPosition here since this isn't possible to
                    // repeat, since it's asymmetric" — that comment is about upstream's own
                    // bookkeeping; the queue's onChangeInPathPosition on an index move is harmless
                    // and is what its snap loop already does.
                    Debug.logMessage("SprintPolicy: skipping descend to straight ascend");
                    return new Decision(true, index + 1, null, null, false, false);
                }
                if (canSprintFromDescendInto(world, current, next)) {
                    // :420-426. Two descends in a row are only sprintable if the SECOND one is
                    // sprintable out of as well — otherwise the body arrives at the third step
                    // carrying speed it cannot lose.
                    if (next instanceof MovementDescend && index < movements.size() - 2) {
                        Movement nextNext = movements.get(index + 2);
                        if (nextNext instanceof MovementDescend
                                && !canSprintFromDescendInto(world, next, nextNext)) {
                            return NO;
                        }
                    }
                    // :427-431 — only advance once the feet have actually arrived; otherwise sprint
                    // and stay on this movement.
                    if (RotationHelper.playerFeet(player).equals(current.getDest())) {
                        return new Decision(true, index + 1, null, null, false, false);
                    }
                    return YES;
                }
                // upstream's commented-out "Turning off sprinting" trace lives here (:435); nothing
                // to port.
            }
        }

        // :438-453
        if (current instanceof MovementAscend && index != 0) {
            Movement prev = movements.get(index - 1);
            if (prev instanceof MovementDescend
                    && prev.getDirection().up().equals(current.getDirection().down())) {
                BetterBlockPos center = current.getSrc().above();
                // :443-445, upstream's comment: playerFeet adds 0.1251 to account for soul sand,
                // farmland is 0.9375, and the 0.07 is to account for farmland.
                if (player.getEntityPos().y >= center.getY() - 0.07) {
                    // :446 — force JUMP OFF. This is the descend-to-ascend pair upstream skipped
                    // into above: the body is already high enough, and a jump here would throw it
                    // over the step.
                    return new Decision(true, -1, Boolean.FALSE, null, false, false);
                }
            }
            // :450-452 — the mirror of the traverse branch at the top, evaluated from the ascend's
            // side. It is what keeps a skipped sprint-ascend sprinting on the tick AFTER the skip.
            if (index < movements.size() - 1 && prev instanceof MovementTraverse
                    && sprintableAscend(world, (MovementTraverse) prev,
                            (MovementAscend) current, movements.get(index + 1))) {
                return YES;
            }
        }

        // :454-472
        if (current instanceof MovementFall) {
            FallOverride data = overrideFall(movements, index, world, (MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = data.dest;
                int destIndex = positionIndexOf(movements, fallDest);
                if (destIndex < 0) {
                    // :458-460 upstream throws IllegalStateException here, because the cell is
                    // derived from its own path and therefore must be on it. ADAPTER: this queue's
                    // chain can be TRUNCATED after construction (MovementQueue.start cuts it at the
                    // first unpreparable step), so the invariant is not ours to assert, and a throw
                    // from here would escape MovementQueue.tick — its try/catch only wraps
                    // movement.update() — and take the client tick with it. Treat an off-chain
                    // overshoot as "no override" and fall through to the plain answer.
                    if (TungstenConfig.get().verboseDebugLogging) {
                        Debug.logMessage("SprintPolicy: fall overshoot target " + fallDest
                                + " is not on the chain — ignoring the override");
                    }
                } else if (RotationHelper.playerFeet(player).equals(fallDest)) {
                    // :461-466 — we have landed where the overshoot aimed; jump the index straight
                    // there and re-enter.
                    return new Decision(true, destIndex, null, null, false, false);
                } else {
                    // :467-470 — clear the keys, aim at the overshoot point (UNFORCED), walk.
                    Rotation aim = RotationHelper.calcRotationFromVec3d(
                            RotationHelper.playerHead(player), data.aimAt,
                            RotationHelper.playerRotations(player));
                    return new Decision(true, -1, null, aim, true, true);
                }
            }
        }

        return NO;   // :473
    }

    // ---------------------------------------------------------------------------------------
    // Appliers. The queue owns the index; these own the keys and the camera for the effects the
    // decision carries. Kept separate so the call site can respect upstream's ORDER (see the
    // wiring note in the class header): the sprint answer is latched by the OUTERMOST decision of
    // the tick, while a jump forced by a skip must be pressed AFTER the movement it skipped into
    // has written its own inputs.
    // ---------------------------------------------------------------------------------------

    /**
     * The sprint half of {@code PathingBehavior.onPlayerSprintState} (PathingBehavior.java:110-115)
     * plus {@code PathExecutor.java:239-241}.
     *
     * <p>Upstream feeds {@code sprintNextTick} into a {@code KeyBinding.isPressed()} redirect inside
     * {@code ClientPlayerEntity.tickMovement} (MixinClientPlayerEntity.java:110-132), i.e. the
     * decision IS the sprint key for that tick. Tungsten has no such redirect, so the key is written
     * directly — which works because {@code MovementQueue.tick} runs at the HEAD of
     * {@code ClientPlayerEntity.tick}, before {@code tickMovement} reads it, and nothing else in
     * that mixin touches the sprint key while the queue owns the tick.
     *
     * <p>The {@code setSprinting(false)} is upstream's and is not redundant: "letting go of control
     * doesn't make you stop sprinting actually" (PathExecutor.java:240). Releasing the key alone
     * leaves the entity's sprint flag latched until something else clears it.
     */
    public static void applySprint(ClientPlayerEntity player, boolean sprint) {
        GameOptions options = MinecraftClient.getInstance().options;
        if (options != null) {
            options.sprintKey.setPressed(sprint);
        }
        if (!sprint && player != null) {
            player.setSprinting(false);
        }
    }

    /**
     * {@code setInputForceState(Input.JUMP, …)} (PathExecutor.java:366 and :446). {@code null} means
     * upstream touched the jump input on neither path — leave whatever the movement declared.
     */
    public static void applyJump(Boolean jump) {
        if (jump == null) {
            return;
        }
        GameOptions options = MinecraftClient.getInstance().options;
        if (options != null) {
            options.jumpKey.setPressed(jump);
        }
    }

    /**
     * The fall-override steer (PathExecutor.java:467-469): clear the keys, retarget the camera,
     * press forward. Order is upstream's — the clear must precede the press or it erases it.
     *
     * <p>ADAPTER, and it is load-bearing: {@link Movement#motionYaw} is re-armed with the steer's
     * yaw. Upstream does not need to, because its {@code MixinEntity} swaps the player's yaw around
     * {@code updateVelocity} using the LookBehavior target it just set, so MOVE_FORWARD is resolved
     * in the requested facing on this very tick. Here the camera is stepped per RENDER FRAME by
     * {@link WindMouseRotation}, and the motion frame the movement armed in {@code Movement.update()}
     * still holds the FALL's own target — so without this the forward press would be resolved in a
     * facing this steer just overrode. See {@code Movement.motionYaw} for the trace that mechanism
     * was built from.
     *
     * <p>The camera target itself goes through the UNFORCED path ({@code setTargetFast} with the
     * player's own pitch), because upstream passes {@code force = false} at :468 — the same mapping
     * {@code Movement.updateAimTarget} uses.
     */
    public static void applySteer(ClientPlayerEntity player, Decision decision) {
        if (decision == null || !decision.hasSteer()) {
            return;
        }
        if (decision.clearKeys) {
            Movement.clearAllKeys();
        }
        if (decision.aim != null && player != null) {
            WindMouseRotation.INSTANCE.setTargetFast(decision.aim.getYaw(), player.getPitch());
            Movement.motionYaw = decision.aim.getYaw();
            Movement.motionPitch = player.getPitch();
        }
        if (decision.moveForward) {
            GameOptions options = MinecraftClient.getInstance().options;
            if (options != null) {
                options.forwardKey.setPressed(true);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers, ported one for one
    // ---------------------------------------------------------------------------------------

    /**
     * {@code CalculationContext.canSprint} (CalculationContext.java:108) —
     * {@code allowSprint && player.getHungerManager().getFoodLevel() > 6}. Upstream builds a whole
     * {@code CalculationContext} at PathExecutor.java:352 just to read this one boolean.
     */
    private static boolean canSprint(ClientPlayerEntity player) {
        return ALLOW_SPRINT && player.getHungerManager().getFoodLevel() > SPRINT_HUNGER_FLOOR;
    }

    /**
     * {@code CalculationContext.hasThrowaway} (CalculationContext.java:106), reduced the same way
     * {@code MovementTraverse.hasThrowaway} reduces it: tungsten's equip hook takes no location and
     * no filter, so "have we got a block" can only be asked of the main hand. KNOWN divergence, not
     * faked. It feeds only {@code couldPlaceInstead}, which is dead while there is no parkour class.
     */
    private static boolean hasThrowaway(ClientPlayerEntity player) {
        return TungstenConfig.get().allowPlace
                && player.getMainHandStack().getItem() instanceof BlockItem;
    }

    /**
     * {@code PathExecutor.skipNow} (:515-528): are we centred enough on the current movement, and
     * far enough past the block behind us, to cut it short without bonking our head?
     */
    private static boolean skipNow(ClientPlayerEntity player, Movement current) {
        BlockPos direction = current.getDirection();
        BetterBlockPos src = current.getSrc();
        Vec3d pos = player.getEntityPos();
        double offTarget = Math.abs(direction.getX() * (src.z + 0.5D - pos.z))
                + Math.abs(direction.getZ() * (src.x + 0.5D - pos.x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = src.subtract(direction).up(2);
        WorldView world = player.getEntityWorld();
        if (fullyPassable(world, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(direction.getX() * (headBonk.getX() + 0.5D - pos.x))
                + Math.abs(direction.getZ() * (headBonk.getZ() + 0.5D - pos.z));
        return flatDist > 0.8;
    }

    /**
     * {@code PathExecutor.sprintableAscend} (:530-568): can this traverse-then-ascend pair be taken
     * as one sprint jump? Every refusal is upstream's, in upstream's order.
     *
     * <p>DROPPED (:563-566): the two {@code AltoClefSettings.shouldAvoidWalkThroughForce} checks on
     * {@code current.getSrc().up(3)} and {@code up(2)}. No tungsten equivalent exists — the same
     * omission {@link MovementHelperB} records for {@code canWalkThrough}. Their effect is to refuse
     * a sprint-ascend under a cell altoclef has force-marked as un-walkable; without the marker
     * there is nothing to read, so this is a drop of an input, not of a condition.
     */
    private static boolean sprintableAscend(WorldView world, MovementTraverse current,
                                            MovementAscend next, Movement nextnext) {
        if (!SPRINT_ASCENDS) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().down())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX()
                || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelperB.canWalkOn(world, current.getDest().below())) {
            return false;
        }
        if (!MovementHelperB.canWalkOn(world, next.getDest().below())) {
            return false;
        }
        // :546-548 `if (!next.toBreakCached.isEmpty()) return false;  // it's breaking`.
        // ADAPTER: upstream's cache is populated every tick by PathExecutor's break/place
        // recalculation (:148-181), which MovementQueue does not port — it only RESETS the cache at
        // the top of each tick. So the field is null here, and toBreak(world) is called to compute
        // exactly what upstream would have been reading: the same predicate, over the same world,
        // on the same tick.
        if (!next.toBreak(world).isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().up(y);
                if (x == 1) {
                    chk = chk.add(current.getDirection());
                }
                if (!fullyPassable(world, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelperB.avoidWalkingInto(world.getBlockState(current.getSrc().up(3)))) {
            return false;
        }
        // DROPPED (:563-566): AltoClefSettings.shouldAvoidWalkThroughForce(src.up(3) / src.up(2)).
        return !MovementHelperB.avoidWalkingInto(
                world.getBlockState(next.getDest().up(2))); // codacy smh my head
    }

    /**
     * {@code PathExecutor.canSprintFromDescendInto} (:570-581): having descended, is the NEXT
     * movement one we can carry the speed into?
     */
    private static boolean canSprintFromDescendInto(WorldView world, Movement current, Movement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelperB.canWalkOn(world, current.getDest().add(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && ALLOW_OVERSHOOT_DIAGONAL_DESCEND;
    }

    /** {@code PathExecutor.overrideFall}'s return, upstream a {@code Pair<Vec3d, BlockPos>} (:510).
     *  Named fields instead of {@code getLeft}/{@code getRight} so the two cannot be swapped. */
    private static final class FallOverride {
        /** The point to aim at — a Vec3d some way BEYOND the fall's destination. */
        final Vec3d aimAt;
        /** The cell the overshoot actually ends in; must be a position on the chain. */
        final BetterBlockPos dest;

        FallOverride(Vec3d aimAt, BetterBlockPos dest) {
            this.aimAt = aimAt;
            this.dest = dest;
        }
    }

    /**
     * {@code PathExecutor.overrideFall} (:476-513): a shallow fall followed by up to two traverses
     * in the same flat direction is not two manoeuvres, it is one jump with a run-out. Returns the
     * point to aim past and the cell the run-out ends in, or null if the shape does not qualify.
     */
    private static FallOverride overrideFall(List<Movement> movements, int index, WorldView world,
                                             MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        // :481-483. Same cache ADAPTER as sprintableAscend above — the field is null here because
        // MovementQueue resets it every tick and never repopulates it, so compute it.
        if (!movement.toBreak(world).isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        // :487 `i < path.length() - 1` is `i < movements.size()`, which is the bound that makes
        // movements.get(i) legal.
        for (i = index + 1; i < movements.size() && i < index + 3; i++) {
            Movement next = movements.get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!fullyPassable(world, chk)) {
                    break outer;
                }
            }
            if (!MovementHelperB.canWalkOn(world, next.getDest().below())) {
                break;
            }
        }
        i--;
        if (i == index) {
            return null; // no valid extension exists
        }
        double len = i - index - 0.4;
        return new FallOverride(
                new Vec3d(flatDir.getX() * len + movement.getDest().x + 0.5,
                        movement.getDest().y,
                        flatDir.getZ() * len + movement.getDest().z + 0.5),
                new BetterBlockPos(movement.getDest().add(
                        flatDir.getX() * (i - index), 0, flatDir.getZ() * (i - index))));
    }

    /**
     * {@code path.positions().indexOf(pos)} (PathExecutor.java:462). A path's positions are the
     * movements' sources followed by the last movement's destination, so this scans the same list in
     * the same order and returns the FIRST match, as {@code List.indexOf} does.
     *
     * <p>A result equal to {@code movements.size()} means the last destination — upstream's
     * {@code pathPosition = path.length() - 1}, after which its {@code onTick} bumps once more and
     * reports the path finished. The queue's {@code index >= movements.size()} branch is that same
     * ending.
     *
     * @return the index, or -1 if the cell is not a position of this chain
     */
    private static int positionIndexOf(List<Movement> movements, BetterBlockPos pos) {
        for (int i = 0; i < movements.size(); i++) {
            if (movements.get(i).getSrc().equals(pos)) {
                return i;
            }
        }
        if (!movements.isEmpty() && movements.get(movements.size() - 1).getDest().equals(pos)) {
            return movements.size();
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // fullyPassable — MovementHelper.java:274-331, ported here because no tungsten file has it
    // ---------------------------------------------------------------------------------------

    /**
     * {@code MovementHelper.fullyPassable(IPlayerContext, BlockPos)} (MovementHelper.java:318-331):
     * "canWalkThrough but also won't impede movement at all — so not including doors or fence gates
     * (we'd have to right click), not including water, and not including ladders or vines or cobwebs
     * (they slow us down)".
     *
     * <p>⛔ NOT {@code kaptainwutax.tungsten.helpers.BlockStateChecker.fullyPassableBlockState}, and
     * this is not a style preference. That copy is missing both the {@code SnowBlock} case and
     * upstream's closing {@code canPathfindThrough(LAND)} test — it falls out of its exception list
     * with a bare {@code return YES}, so it answers "fully passable" for STONE. Feeding this policy
     * that answer would sprint the bot head-first into walls. Ported from upstream instead.
     *
     * <p>DROPPED (:321-323): {@code AltoClefSettings.shouldAvoidWalkThroughForce(pos)} — no tungsten
     * equivalent, same drop {@link MovementHelperB} records for {@code canWalkThrough}.
     */
    private static boolean fullyPassable(WorldView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        // DROPPED: if (AltoClefSettings.shouldAvoidWalkThroughForce(pos)) return false;
        if (fullyPassable == Ternary.YES) {
            return true;
        }
        if (fullyPassable == Ternary.NO) {
            return false;
        }
        // Upstream's tail. Unreachable today — fullyPassableBlockState never answers MAYBE — and
        // kept because it is upstream's, and because a future block case that does answer MAYBE
        // would otherwise silently fall out of a switch with no default.
        return state.canPathfindThrough(NavigationType.LAND);
    }

    /** {@code MovementHelper.fullyPassableBlockState} (MovementHelper.java:274-304), verbatim. */
    private static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return Ternary.YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof AbstractFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapdoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return Ternary.NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or
        // pos parameters at least in 1.12.2 vanilla, that is.....
        if (state.canPathfindThrough(NavigationType.LAND)) {
            return Ternary.YES;
        } else {
            return Ternary.NO;
        }
    }
}

package kaptainwutax.tungsten.path.movements;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Verbatim copy of baritone/src/main/java/baritone/pathing/movement/MovementState.java (whole
 * file), package changed and the two enum imports dropped because {@link MovementStatus} and
 * {@link Input} now live in this same package. Part of the movement substrate
 * (BARITONE-PORT-SPEC.md unit 1, `movements/MovementState.java`).
 *
 * <p>What this object is for. It is the *declaration* a movement writes during its own
 * updateState() call, and the only thing the per-tick applier reads. That indirection is what makes
 * "exactly one writer of keys and camera per tick" true rather than aspirational
 * (Movement.java:122-151): a movement never touches KeyBinding or the aim actuator itself, it only
 * fills in this object, and MovementExecutor then does, in order — release every key, press exactly
 * what this tick declared, clear the map (Movement.java:154-158). A key not set this tick is
 * therefore released this tick; nothing latches. Two consequences that must not drift:
 * <ul>
 *   <li>{@link #getInputStates()} returns the live map, not a copy, because the applier clears it
 *       through this reference after applying it. Do not defensively copy it — that would leave the
 *       state holding last tick's keys and reintroduce latching.
 *   <li>{@link #setInput} overwrites, so a later branch in the same updateState() can revoke an
 *       earlier one (e.g. MovementTraverse.java:862 clears the SNEAK that PREPPING set while mining
 *       an adjacent block, and :935-936 replaces MOVE_FORWARD with MOVE_BACK for the soul-sand
 *       back-off). Branch order in the movements is the arbiter; this class must stay dumb.
 * </ul>
 *
 * <p>{@code status} is deliberately left null by the constructor: upstream relies on the status
 * ladder (Movement.java:225-237) writing PREPPING/WAITING/RUNNING on the first update, and a
 * pre-seeded value would make the "was this ever prepped" branches lie.
 *
 * <p>HashMap, not EnumMap. Upstream uses HashMap (MovementState.java:32) and this is a copy; the
 * only difference is iteration order, and the applier releases every key before iterating, so order
 * carries no meaning. (The spec table says EnumMap — a suggestion, not upstream, so it is not
 * followed. The CLICK_LEFT-cancels-CLICK_RIGHT interlock is an explicit rule in the executor,
 * InputOverrideHandler.java:90-92, never an artefact of map ordering.)
 *
 * <p>Adapters: none. Upstream's class needs nothing from baritone except Rotation, Input and
 * MovementStatus, all three of which are copied into this package.
 */
public class MovementState {

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputState = new HashMap<>();

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return this.target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        this.inputState.put(input, forced);
        return this;
    }

    public Map<Input, Boolean> getInputStates() {
        return this.inputState;
    }

    public static class MovementTarget {

        /**
         * Yaw and pitch angles that must be matched
         *
         * <p>Public field, as upstream (MovementState.java:66), because the movements read it
         * directly and not through the Optional accessor when they already know a target is set —
         * e.g. MovementTraverse.java:955 compares {@code state.getTarget().rotation.getYaw()}
         * against a freshly computed yaw, and :959/:989 test
         * {@code ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)}. Keeping it
         * public keeps those copied lines compiling unchanged.
         */
        public Rotation rotation;

        /**
         * Whether or not this target must force rotations.
         * <p>
         * {@code true} if we're trying to place or break blocks, {@code false} if we're trying to look at the movement location
         */
        private boolean forceRotations;

        public MovementTarget() {
            this(null, false);
        }

        public MovementTarget(Rotation rotation, boolean forceRotations) {
            this.rotation = rotation;
            this.forceRotations = forceRotations;
        }

        public final Optional<Rotation> getRotation() {
            return Optional.ofNullable(this.rotation);
        }

        /**
         * Read by the applier to pick the aim path: forced targets snap the camera, unforced ones
         * are the soft "walk that way" look that preserves the current pitch
         * (Movement.java:149-153, MovementHelper.java:715-722). The two are not interchangeable —
         * an unforced target must never be used to aim a click.
         */
        public boolean hasToForceRotations() {
            return this.forceRotations;
        }
    }
}

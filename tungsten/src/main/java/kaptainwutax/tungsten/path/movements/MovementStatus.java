package kaptainwutax.tungsten.path.movements;

/**
 * Verbatim copy of baritone/src/main/java/baritone/api/pathing/movement/MovementStatus.java (whole
 * file), package changed only. Part of the movement substrate (BARITONE-PORT-SPEC.md unit 1).
 *
 * <p>Kept as its own top-level type in this package — as upstream has it — rather than nested in
 * MovementState, so that every ported movement can write bare `MovementStatus.RUNNING` exactly as
 * upstream does, with no import and no rewriting of the copied bodies. (The spec table lists the
 * enum under `movements/MovementState.java`; that is a file-layout note, not a semantic one. Same
 * package either way.)
 *
 * <p>The `complete` flag is load-bearing, not decoration: it is the single predicate the per-tick
 * applier uses to decide that the movement is done and every key must be released
 * (`if (currentState.getStatus().isComplete()) clearAllKeys();` — Movement.java:161-163). CANCELED
 * and FAILED are unused upstream and are copied anyway so the ladder stays identical.
 *
 * <p>No adapters needed: upstream's enum has no baritone dependencies.
 */
public enum MovementStatus {

    /**
     * We are preparing the movement to be executed. This is when any blocks obstructing the destination are broken.
     */
    PREPPING(false),

    /**
     * We are waiting for the movement to begin, after {@link MovementStatus#PREPPING}.
     */
    WAITING(false),

    /**
     * The movement is currently in progress, after {@link MovementStatus#WAITING}
     */
    RUNNING(false),

    /**
     * The movement has been completed and we are at our destination
     */
    SUCCESS(true),

    /**
     * There was a change in state between calculation and actual
     * movement execution, and the movement has now become impossible.
     */
    UNREACHABLE(true),

    /**
     * Unused
     */
    FAILED(true),

    /**
     * "Unused"
     */
    CANCELED(true);

    /**
     * Whether or not this status indicates a complete movement.
     */
    private final boolean complete;

    MovementStatus(boolean complete) {
        this.complete = complete;
    }

    public final boolean isComplete() {
        return this.complete;
    }
}

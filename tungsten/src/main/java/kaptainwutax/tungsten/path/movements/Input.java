package kaptainwutax.tungsten.path.movements;

/**
 * Verbatim copy of baritone/src/main/java/baritone/api/utils/input/Input.java (whole file),
 * package changed only. Part of the movement substrate (BARITONE-PORT-SPEC.md unit 1).
 *
 * <p>`baritone/` is not compiled and shredder owns the `baritone.*` package, so this enum may
 * never be imported from there — it is re-declared here and referenced only within
 * `kaptainwutax.tungsten.path.movements`.
 *
 * <p>Why an enum of abstract inputs instead of KeyBinding references: a movement declares what it
 * wants pressed this tick without touching Minecraft's key state, and exactly one place
 * (MovementExecutor, ported from Movement.java:122-151) turns that declaration into
 * KeyBinding.setPressed. That indirection is what makes "one writer of keys per tick" enforceable
 * — a movement physically cannot latch a key.
 *
 * <p>No adapters needed: upstream's enum has no baritone dependencies. Constant order is upstream's
 * and is preserved so the declaration order matches everything downstream that iterates inputs.
 */
public enum Input {

    /**
     * The move forward input
     */
    MOVE_FORWARD,

    /**
     * The move back input
     */
    MOVE_BACK,

    /**
     * The move left input
     */
    MOVE_LEFT,

    /**
     * The move right input
     */
    MOVE_RIGHT,

    /**
     * The attack input
     */
    CLICK_LEFT,

    /**
     * The use item input
     */
    CLICK_RIGHT,

    /**
     * The jump input
     */
    JUMP,

    /**
     * The sneak input
     */
    SNEAK,

    /**
     * The sprint input
     */
    SPRINT
}

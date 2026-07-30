package kaptainwutax.tungsten.path.movements;

/**
 * Verbatim copy of baritone/src/main/java/baritone/api/utils/Rotation.java (whole file, every
 * method, nothing added), package changed only. Part of the movement substrate
 * (BARITONE-PORT-SPEC.md unit 1).
 *
 * <p>Written here because MovementState.MovementTarget cannot exist without it and tungsten has no
 * yaw/pitch value type (WindMouseRotation takes two floats). The rotation *maths* — the port of
 * RotationUtils / RayTraceUtils listed in the spec's `movements/RotationHelper.java` row — is a
 * separate file and belongs to that unit; this is only the immutable pair it operates on. If that
 * unit also emits a Rotation, keep exactly one: this copy is complete upstream, so deleting the
 * other one loses nothing.
 *
 * <p>Load-bearing details that must not be "cleaned up":
 * <ul>
 *   <li>Immutable, and every operation returns a new instance. The movements hand rotations to the
 *       aim actuator and compare against the player's current rotations in the same tick; a mutable
 *       pair would alias.
 *   <li>The constructor throws on NaN/Inf (Rotation.java:39-41). This is upstream's tripwire for
 *       broken aim maths — a NaN yaw would otherwise silently freeze the camera, which is exactly
 *       the failure mode the port exists to remove. Keep it loud.
 *   <li>{@link #yawIsReallyClose} normalizes *both* yaws before comparing and accepts the
 *       &gt; 359.99 wrap case (Rotation.java:130-133). Without that, a target of 180.0 versus a
 *       current of -180.0 reads as 360 degrees apart and a "have we arrived" gate never fires.
 *   <li>{@link #isReallyCloseTo} is that yaw test AND a pitch test with a 0.01 tolerance. It is the
 *       gate for "the aim has actually arrived, so it is safe to click"
 *       (MovementTraverse.java:959, :989), so the tolerance is a constant, not a tunable.
 * </ul>
 *
 * <p>No adapters needed: upstream's class has no baritone or Minecraft dependencies.
 *
 * @author Brady (upstream)
 * @since 9/25/2018
 */
public class Rotation {

    /**
     * The yaw angle of this Rotation
     */
    private final float yaw;

    /**
     * The pitch angle of this Rotation
     */
    private final float pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        if (Float.isInfinite(yaw) || Float.isNaN(yaw) || Float.isInfinite(pitch) || Float.isNaN(pitch)) {
            throw new IllegalStateException(yaw + " " + pitch);
        }
    }

    /**
     * @return The yaw of this rotation
     */
    public float getYaw() {
        return this.yaw;
    }

    /**
     * @return The pitch of this rotation
     */
    public float getPitch() {
        return this.pitch;
    }

    /**
     * Adds the yaw/pitch of the specified rotations to this
     * rotation's yaw/pitch, and returns the result.
     *
     * @param other Another rotation
     * @return The result from adding the other rotation to this rotation
     */
    public Rotation add(Rotation other) {
        return new Rotation(
                this.yaw + other.yaw,
                this.pitch + other.pitch
        );
    }

    /**
     * Subtracts the yaw/pitch of the specified rotations from this
     * rotation's yaw/pitch, and returns the result.
     *
     * @param other Another rotation
     * @return The result from subtracting the other rotation from this rotation
     */
    public Rotation subtract(Rotation other) {
        return new Rotation(
                this.yaw - other.yaw,
                this.pitch - other.pitch
        );
    }

    /**
     * @return A copy of this rotation with the pitch clamped
     */
    public Rotation clamp() {
        return new Rotation(
                this.yaw,
                clampPitch(this.pitch)
        );
    }

    /**
     * @return A copy of this rotation with the yaw normalized
     */
    public Rotation normalize() {
        return new Rotation(
                normalizeYaw(this.yaw),
                this.pitch
        );
    }

    /**
     * @return A copy of this rotation with the pitch clamped and the yaw normalized
     */
    public Rotation normalizeAndClamp() {
        return new Rotation(
                normalizeYaw(this.yaw),
                clampPitch(this.pitch)
        );
    }

    public Rotation withPitch(float pitch) {
        return new Rotation(this.yaw, pitch);
    }

    /**
     * Is really close to
     *
     * @param other another rotation
     * @return are they really close
     */
    public boolean isReallyCloseTo(Rotation other) {
        return yawIsReallyClose(other) && Math.abs(this.pitch - other.pitch) < 0.01;
    }

    public boolean yawIsReallyClose(Rotation other) {
        float yawDiff = Math.abs(normalizeYaw(yaw) - normalizeYaw(other.yaw)); // you cant fool me
        return (yawDiff < 0.01 || yawDiff > 359.99);
    }

    /**
     * Clamps the specified pitch value between -90 and 90.
     *
     * @param pitch The input pitch
     * @return The clamped pitch
     */
    public static float clampPitch(float pitch) {
        return Math.max(-90, Math.min(90, pitch));
    }

    /**
     * Normalizes the specified yaw value between -180 and 180.
     *
     * @param yaw The input yaw
     * @return The normalized yaw
     */
    public static float normalizeYaw(float yaw) {
        float newYaw = yaw % 360F;
        if (newYaw < -180F) {
            newYaw += 360F;
        }
        if (newYaw > 180F) {
            newYaw -= 360F;
        }
        return newYaw;
    }

    @Override
    public String toString() {
        return "Yaw: " + yaw + ", Pitch: " + pitch;
    }
}

package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;

/**
 * A movement request for one combat tick — what a subsystem WANTS the legs to do,
 * separated from actually pressing the keys.
 *
 * <p>WHY THIS EXISTS (2026-07-27). Combat movement used to be written straight to
 * {@code mc.options.*Key.setPressed(...)} by two independent subsystems at two different
 * frequencies: {@link SafetySystem#renderUpdate} (the stage machine + BFS attack-path
 * follower) ran once per RENDER FRAME, while {@code CombatController.combatMove} ran once
 * per CLIENT TICK. Whichever happened to run last before vanilla sampled the keyboard won,
 * so the bot's movement depended on the framerate — which is exactly why behaviour on the
 * low-FPS test stand never matched behaviour on the user's machine. On top of that, the
 * per-tick writer bypassed the {@link VoidGuard} clamp entirely, and the correct
 * eye-to-hitbox approach distance implemented in the stage machine was being overwritten
 * by a cruder centre-to-centre rule in the per-tick writer.
 *
 * <p>Now every producer fills an intent, {@code CombatController} resolves them by priority
 * once per tick, {@link VoidGuard} vetoes the result, and the keys are written EXACTLY ONCE.
 */
public final class CombatMoveIntent {

    /** True when the producer wants to own the legs this tick. */
    public boolean active;

    public boolean forward;
    public boolean back;
    public boolean left;
    public boolean right;
    public boolean sprint;
    public boolean jump;
    public boolean sneak;

    /** Drop the request entirely (producer does not want the legs). */
    public void clear() {
        active = false;
        forward = back = left = right = sprint = jump = sneak = false;
    }

    /** Claim the legs with an explicit key set. */
    public void set(boolean forward, boolean back, boolean left, boolean right,
                    boolean sprint, boolean jump, boolean sneak) {
        this.active = true;
        this.forward = forward;
        this.back = back;
        this.left = left;
        this.right = right;
        this.sprint = sprint;
        this.jump = jump;
        this.sneak = sneak;
    }

    public void copyFrom(CombatMoveIntent other) {
        this.active = other.active;
        this.forward = other.forward;
        this.back = other.back;
        this.left = other.left;
        this.right = other.right;
        this.sprint = other.sprint;
        this.jump = other.jump;
        this.sneak = other.sneak;
    }

    /**
     * The ONE place combat is allowed to touch the movement keys. Everything not
     * requested is explicitly released, so a stale press can never survive a tick.
     */
    public void writeKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(forward);
        mc.options.backKey.setPressed(back);
        mc.options.leftKey.setPressed(left);
        mc.options.rightKey.setPressed(right);
        mc.options.sprintKey.setPressed(sprint);
        mc.options.jumpKey.setPressed(jump);
        mc.options.sneakKey.setPressed(sneak);
    }

    /** World-space horizontal heading (dx,dz) implied by this intent's keys, or null
     *  if it requests no horizontal movement. MC convention: sideways +1 = LEFT. */
    public double[] heading(float yawDegrees) {
        double fwd = (forward ? 1 : 0) - (back ? 1 : 0);
        double strafe = (left ? 1 : 0) - (right ? 1 : 0);
        if (fwd == 0 && strafe == 0) return null;
        double yawRad = Math.toRadians(yawDegrees);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        return new double[]{ strafe * cos - fwd * sin, fwd * cos + strafe * sin };
    }
}

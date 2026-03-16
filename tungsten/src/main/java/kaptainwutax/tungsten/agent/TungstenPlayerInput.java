package kaptainwutax.tungsten.agent;

/**
 * Replaces net.minecraft.util.PlayerInput (record added in MC 1.21.4).
 * In MC 1.21, player input is represented via the Input class (movementForward/Sideways floats).
 * This class holds the same logical boolean data as PlayerInput for internal Tungsten use.
 */
public class TungstenPlayerInput {

    public static final TungstenPlayerInput DEFAULT = new TungstenPlayerInput(false, false, false, false, false, false, false);

    public final boolean forward;
    public final boolean backward;
    public final boolean left;
    public final boolean right;
    public final boolean jump;
    public final boolean sneak;
    public final boolean sprint;

    public TungstenPlayerInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }

    public boolean forward()  { return forward; }
    public boolean backward() { return backward; }
    public boolean left()     { return left; }
    public boolean right()    { return right; }
    public boolean jump()     { return jump; }
    public boolean sneak()    { return sneak; }
    public boolean sprint()   { return sprint; }

}

package kaptainwutax.tungsten.elytra;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

/**
 * Vanilla elytra flight, one tick at a time: the glide (LivingEntity#travel while gliding) and the
 * pull of a firework rocket on its shooter (FireworkRocketEntity#tick). Pure math over a motion
 * vector, so a planner can try many pitches ahead without touching the player.
 *
 * <p>The first piece of moving the fork's elytra pilot onto tungsten: the pilot, the predictions
 * drawn in flight and the planned trajectory search all step through this one model.
 */
public final class ElytraPhysics {

    /** A rocket pulls for 10 * (1 + flight) + rand(6) + rand(7) ticks: at least 20 for an
     *  ordinary one (flight 1), up to 31. */
    public static final int ROCKET_MIN_TICKS = 20, ROCKET_MAX_TICKS = 31;

    private ElytraPhysics() {
    }

    /** Unit look vector for a yaw and pitch in degrees (Entity#getRotationVector). */
    public static Vec3d look(float yaw, float pitch) {
        float p = pitch * MathHelper.RADIANS_PER_DEGREE, y = -yaw * MathHelper.RADIANS_PER_DEGREE;
        float cy = MathHelper.cos(y), sy = MathHelper.sin(y), cp = MathHelper.cos(p), sp = MathHelper.sin(p);
        return new Vec3d(sy * cp, -sp, cy * cp);
    }

    /** Motion after one gliding tick with this look; the rocket's pull added after, as the rocket
     *  entity ticks after its shooter. */
    public static Vec3d step(Vec3d motion, float yaw, float pitch, boolean rocket) {
        return tick(motion, yaw, pitch, rocket).motion();
    }

    /** One gliding tick: {@code move} is how far the player goes in it, the glide alone, and
     *  {@code motion} the velocity it ends with, a live rocket's pull added. The rocket entity ticks
     *  after its shooter has moved, so its pull moves the player from the next tick on. Moving by the
     *  pulled velocity instead put every tick under a rocket 0.17 blocks off (median, max 0.84) on a
     *  logged 300-block flight, against 0.0000 this way, and a planned path ran ahead of the real one
     *  by the sum of the pulls (08.11). */
    public static Tick tick(Vec3d motion, float yaw, float pitch, boolean rocket) {
        Vec3d look = look(yaw, pitch);
        double mx = motion.x, my = motion.y, mz = motion.z;
        float pr = pitch * MathHelper.RADIANS_PER_DEGREE;
        double lookH = Math.sqrt(look.x * look.x + look.z * look.z);
        double flat = Math.sqrt(mx * mx + mz * mz);
        double cp2 = MathHelper.cos(pr);
        cp2 = cp2 * cp2 * Math.min(1.0, look.length() / 0.4);
        my += -0.08 + cp2 * 0.06;
        if (my < 0 && lookH > 0) {
            double lift = my * -0.1 * cp2;
            my += lift;
            mx += look.x * lift / lookH;
            mz += look.z * lift / lookH;
        }
        if (pr < 0 && lookH > 0) { // nose up: trade speed for height
            double climb = flat * -MathHelper.sin(pr) * 0.04;
            my += climb * 3.2;
            mx -= look.x * climb / lookH;
            mz -= look.z * climb / lookH;
        }
        if (lookH > 0) {
            mx += (look.x / lookH * flat - mx) * 0.1;
            mz += (look.z / lookH * flat - mz) * 0.1;
        }
        mx *= 0.99f;
        my *= 0.98f;
        mz *= 0.99f;
        Vec3d move = new Vec3d(mx, my, mz);
        if (rocket) {
            mx += look.x * 0.1 + (look.x * 1.5 - mx) * 0.5;
            my += look.y * 0.1 + (look.y * 1.5 - my) * 0.5;
            mz += look.z * 0.1 + (look.z * 1.5 - mz) * 0.5;
        }
        return new Tick(move, rocket ? new Vec3d(mx, my, mz) : move);
    }

    public record Tick(Vec3d move, Vec3d motion) {
    }

    /** Where a glide goes over the next ticks holding yaw and pitch, a rocket pulling for the
     *  first {@code rocketTicks}. Positions are the player's feet. Stops at the first block the
     *  0.6-wide gliding box would touch; {@link Path#hit} says whether it did. */
    public static Path predict(BlockView world, Vec3d pos, Vec3d motion, float yaw, float pitch,
                               int ticks, int rocketTicks) {
        Vec3d[] pts = new Vec3d[ticks + 1];
        pts[0] = pos;
        int n = 1;
        boolean hit = false;
        for (int i = 0; i < ticks; i++) {
            Tick t = tick(motion, yaw, pitch, i < rocketTicks);
            motion = t.motion();
            Vec3d next = pos.add(t.move());
            if (world != null && blocked(world, pos, next)) {
                hit = true;
                break;
            }
            pos = next;
            pts[n++] = pos;
        }
        return new Path(java.util.Arrays.copyOf(pts, n), hit);
    }

    /** The gliding box (0.6 x 0.6) at these feet touches a block with collision. */
    public static boolean blocked(BlockView world, Vec3d feet) {
        return blocked(world, feet, feet);
    }

    /** The gliding box anywhere on its way from one tick's feet to the next touches a block: the box
     *  around both. Checking the ends alone let a glide at 1.7 blocks a tick pass through a wall one
     *  block thick -- a plan once flew outside a glass hall that way and dropped in through its side
     *  (01.11). A little stricter than the true sweep past a corner, never more lenient. */
    public static boolean blocked(BlockView world, Vec3d from, Vec3d to) {
        Box b = new Box(Math.min(from.x, to.x) - 0.3, Math.min(from.y, to.y), Math.min(from.z, to.z) - 0.3,
                Math.max(from.x, to.x) + 0.3, Math.max(from.y, to.y) + 0.6, Math.max(from.z, to.z) + 0.3);
        for (int x = MathHelper.floor(b.minX); x <= MathHelper.floor(b.maxX); x++) {
            for (int y = MathHelper.floor(b.minY); y <= MathHelper.floor(b.maxY); y++) {
                for (int z = MathHelper.floor(b.minZ); z <= MathHelper.floor(b.maxZ); z++) {
                    var at = new net.minecraft.util.math.BlockPos(x, y, z);
                    var shape = world.getBlockState(at).getCollisionShape(world, at);
                    if (!shape.isEmpty() && shape.getBoundingBox().offset(at).intersects(b)) return true;
                }
            }
        }
        return false;
    }

    public record Path(Vec3d[] points, boolean hit) {
    }
}

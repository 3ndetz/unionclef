package kaptainwutax.tungsten.util;

/**
 * Per-tick ring buffer of the player's yaw — lets a stand test QUANTIFY aim jitter
 * (the "прицел трясёт" shake) instead of eyeballing it: a smooth aim tracking a target
 * turns near-monotonically (few Δyaw sign-reversals); a shaky one flips direction many
 * times per second. Recorded every client tick from MixinClientPlayerEntity; read over
 * py4j via getAimSamples(n). Cheap (one float write per tick), always on.
 */
public final class AimSampler {

    private static final int CAP = 400;               // ~20s at 20 tps
    private static final float[] yaws = new float[CAP];
    private static int idx = 0;
    private static int count = 0;

    private AimSampler() {}

    public static synchronized void record(float yaw) {
        yaws[idx] = yaw;
        idx = (idx + 1) % CAP;
        if (count < CAP) count++;
    }

    /** The last n recorded yaws, oldest-first. */
    public static synchronized float[] last(int n) {
        n = Math.min(n, count);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = yaws[((idx - n + i) % CAP + CAP) % CAP];
        }
        return out;
    }
}

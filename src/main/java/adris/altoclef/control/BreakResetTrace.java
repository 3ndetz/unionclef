package adris.altoclef.control;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WHO THREW AWAY THE BREAK? A diagnostic for block breaks that never finish.
 *
 * <p>Found on nav_cliff (2026-09-24): the executor mined one stone by hand, aim steady (breakAim
 * 2469/0/0/0, zero misses), on the ground, the estimate right (152 ticks) -- and at 405 ticks the
 * break progress read 0.1..0.8, a different value every time. Something restarted the break over and
 * over, and nothing said what. Vanilla restarts a break on {@code cancelBlockBreaking} and when the
 * target block changes; this records the first frame of OUR code on the stack each time a break with
 * real progress is thrown away, so the answer is a counter instead of a guess.
 *
 * <p>Only a cancel that discards progress is traced, which is rare, so the stack walk costs nothing
 * on the normal path.
 */
public final class BreakResetTrace {

    private BreakResetTrace() {}

    private static final Map<String, Integer> SITES = new LinkedHashMap<>();
    public static volatile int resets = 0;
    /** Client-side break completions (ClientPlayerInteractionManager.breakBlock). */
    public static volatile int completions = 0;
    public static volatile int starts = 0;
    /** Breaks restarted with a fresh START because vanilla would have continued one the server never had. */
    public static volatile int freshStarts = 0;
    public static volatile String lastCompleted = "-", lastStarted = "-";

    public static synchronized void noteDiscard(String kind, float progress) {
        if (progress <= 0.02f) return;
        resets++;
        String site = kind + "@" + firstOwnFrame();
        SITES.merge(site, 1, Integer::sum);
    }

    private static String firstOwnFrame() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if ((c.startsWith("kaptainwutax.") || c.startsWith("adris.altoclef."))
                    && !c.startsWith("adris.altoclef.mixins.")
                    && !c.equals(BreakResetTrace.class.getName())) {
                return c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
            }
        }
        return "vanilla";
    }

    /** Sites that discarded break progress, most frequent first, as "site=count" joined by spaces. */
    public static synchronized String summary() {
        StringBuilder sb = new StringBuilder("resets=" + resets + " completions=" + completions + " starts=" + starts + " freshStarts=" + freshStarts
                + " lastStart=" + lastStarted + " lastDone=" + lastCompleted);
        SITES.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue()));
        return sb.toString();
    }

    private static final String[] RING = new String[400];
    private static int ringPos = 0;

    /** One raw break event: what vanilla's break state looked like when it happened. */
    public static synchronized void event(String what, Object pos, boolean breaking, float progress, int cooldown) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        long t = mc.world != null ? mc.world.getTime() : -1;
        String body = "";
        if (mc.player != null) {
            var e = mc.player.getEyePos();
            body = String.format(" eye=(%.2f,%.2f,%.2f) ground=%b vy=%.2f", e.x, e.y, e.z,
                    mc.player.isOnGround(), mc.player.getVelocity().y);
        }
        RING[ringPos++ % RING.length] = t + " " + what + " " + pos + " brk=" + breaking
                + String.format(" p=%.2f cd=%d", progress, cooldown) + body;
    }

    public static synchronized String events() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RING.length; i++) {
            String e = RING[(ringPos + i) % RING.length];
            if (e != null) sb.append(e).append(" | ");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        java.util.Arrays.fill(RING, null);
        SITES.clear();
        resets = 0;
        completions = 0;
        starts = 0;
    }
}

package kaptainwutax.tungsten.render;

import java.util.List;

import kaptainwutax.tungsten.path.movements.Movement;
import kaptainwutax.tungsten.path.movements.MovementQueue;
import kaptainwutax.tungsten.task.BlockPathWalker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * What the bot is about to do, drawn in the world every frame -- baritone's PathRenderer, for the
 * drivers a playthrough actually runs.
 *
 * <p>⛔ WHY THIS EXISTS (user, 2026-09-24): "визуализации действий fastplanner где ломает или ставит
 * блоки и baritone-like маршруты -- никаких нет". Checked against the code: the render containers were
 * only ever fed by FastNavigator and the physics replay (PathExecutor). The drive @gamer runs almost
 * all the time -- CombatPathfinder's route walked by {@link BlockPathWalker}, the ported baritone
 * {@link MovementQueue}, and altoclef's own digging -- put nothing in them. So the one thing a person
 * watching could not see was the route the bot was on.
 *
 * <p>Pulled, not pushed: each frame reads the live state of every driver, so there is no container
 * for a driver to forget to fill or forget to clear, and a stale route cannot linger after a stop.
 *
 * <p>Colours follow baritone's defaults: route line cyan, next waypoint white, blocks to break red,
 * blocks to place green, the block being mined right now orange (with a box that grows with
 * progress), altoclef's mining target yellow.
 */
public final class RouteOverlay {

    private RouteOverlay() {}

    private static final Color ROUTE = new Color(0, 220, 255);
    private static final Color QUEUE = new Color(80, 140, 255);
    private static final Color NEXT = Color.WHITE;
    private static final Color BREAK = new Color(255, 40, 40);
    private static final Color PLACE = new Color(40, 255, 40);
    private static final Color MINING = new Color(255, 150, 0);
    private static final Color TARGET = new Color(255, 230, 0);

    /** Longest stretch of route drawn ahead of the body; beyond it the line only costs frames. */
    private static final int MAX_WAYPOINTS = 64;
    private static final int MAX_MOVEMENTS = 24;
    /** A mining note older than this is a break that ended without a cancel event. */
    private static final long MINING_STALE_MS = 600;

    private static volatile BlockPos mining;
    private static volatile double miningProgress;
    private static volatile long miningMs;
    private static volatile BlockPos target;
    private static volatile long targetMs;

    /** The block whose break just made progress (0..1). Called by whoever drives the break. */
    public static void noteMining(BlockPos pos, double progress) {
        mining = pos == null ? null : pos.toImmutable();
        miningProgress = progress;
        miningMs = System.currentTimeMillis();
    }

    /** The block a task means to break next, before any swing -- where the bot is headed to dig. */
    public static void noteTarget(BlockPos pos) {
        target = pos == null ? null : pos.toImmutable();
        targetMs = System.currentTimeMillis();
    }

    public static void draw() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        drawWalkerRoute();
        drawMovementQueue(mc);
        drawMining();
    }

    private static void drawWalkerRoute() {
        List<BlockPos> p = BlockPathWalker.routeForOverlay();
        if (p == null || p.isEmpty()) return;
        int from = Math.max(0, Math.min(BlockPathWalker.waypointForOverlay(), p.size() - 1));
        int to = Math.min(p.size(), from + MAX_WAYPOINTS);
        Vec3d prev = null;
        for (int i = from; i < to; i++) {
            BlockPos b = p.get(i);
            Vec3d c = new Vec3d(b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5);
            if (prev != null) line(prev, c, ROUTE, 5.0f);
            prev = c;
        }
        BlockPos next = p.get(from);
        cell(new net.minecraft.util.math.Box(next.getX() + 0.3, next.getY(), next.getZ() + 0.3,
                next.getX() + 0.7, next.getY() + 0.2, next.getZ() + 0.7), NEXT, 120);
        BlockPos end = p.get(p.size() - 1);
        cell(new net.minecraft.util.math.Box(end.getX(), end.getY(), end.getZ(),
                end.getX() + 1, end.getY() + 2, end.getZ() + 1), ROUTE, 40);
    }

    private static void drawMovementQueue(MinecraftClient mc) {
        List<Movement> ms = MovementQueue.remainingForOverlay();
        if (ms.isEmpty()) return;
        int n = Math.min(ms.size(), MAX_MOVEMENTS);
        for (int i = 0; i < n; i++) {
            Movement m = ms.get(i);
            BlockPos s = m.getSrc(), d = m.getDest();
            line(new Vec3d(s.getX() + 0.5, s.getY() + 0.15, s.getZ() + 0.5),
                    new Vec3d(d.getX() + 0.5, d.getY() + 0.15, d.getZ() + 0.5), QUEUE, 5.0f);
            try {
                List<BlockPos> br = m.toBreakCached != null ? m.toBreakCached : m.toBreak(mc.world);
                for (BlockPos b : br) box(b, BREAK, 0.02, 70);
                List<BlockPos> pl = m.toPlaceCached != null ? m.toPlaceCached : m.toPlace(mc.world);
                for (BlockPos b : pl) box(b, PLACE, 0.02, 70);
            } catch (Exception ignored) {
                // a movement whose cells cannot be computed this frame simply draws no boxes
            }
        }
    }

    private static void drawMining() {
        long now = System.currentTimeMillis();
        BlockPos t = target;
        if (t != null && now - targetMs < 1500) box(t, TARGET, 0.01, 0);
        BlockPos m = mining;
        if (m != null && now - miningMs < MINING_STALE_MS) {
            box(m, MINING, 0.03, 0);
            double f = Math.max(0.05, Math.min(1.0, miningProgress));
            double inset = 0.5 * (1.0 - f);
            cell(new net.minecraft.util.math.Box(m.getX() + inset, m.getY() + inset, m.getZ() + inset,
                    m.getX() + 1 - inset, m.getY() + 1 - inset, m.getZ() + 1 - inset), MINING, 90);
        }
    }

    private static void box(BlockPos b, Color c, double grow, int fillAlpha) {
        cell(new net.minecraft.util.math.Box(b.getX() - grow, b.getY() - grow, b.getZ() - grow,
                b.getX() + 1 + grow, b.getY() + 1 + grow, b.getZ() + 1 + grow), c, fillAlpha);
    }

    /** A box with a thick outline and, when fillAlpha > 0, a see-through fill -- readable on video. */
    private static void cell(net.minecraft.util.math.Box bx, Color c, int fillAlpha) {
        //#if MC >= 12111
        net.minecraft.client.render.DrawStyle style = fillAlpha > 0
                ? net.minecraft.client.render.DrawStyle.filledAndStroked(c.toARGB(255), 3.0f, c.toARGB(fillAlpha))
                : net.minecraft.client.render.DrawStyle.stroked(c.toARGB(255), 3.0f);
        net.minecraft.world.debug.gizmo.GizmoDrawing.box(bx, style);
        net.minecraft.world.debug.gizmo.GizmoDrawing.box(bx, net.minecraft.client.render.DrawStyle.stroked(c.toARGB(110), 2.0f))
                .ignoreOcclusion();
        //#else
        //$$ new Cuboid(new Vec3d(bx.minX, bx.minY, bx.minZ), new Vec3d(bx.getLengthX(), bx.getLengthY(), bx.getLengthZ()), c).render(null);
        //#endif
    }

    private static void line(Vec3d a, Vec3d b, Color c, float width) {
        //#if MC >= 12111
        net.minecraft.world.debug.gizmo.GizmoDrawing.line(a, b, c.toARGB(255), width);
        net.minecraft.world.debug.gizmo.GizmoDrawing.line(a, b, c.toARGB(110), width * 0.6f).ignoreOcclusion();
        //#else
        //$$ new Line(a, b, c).render(null);
        //#endif
    }
}

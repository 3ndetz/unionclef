package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Pillar up: reach a raised goal (a ledge / tree top / tower) by placing blocks
 * under yourself — the vertical counterpart to {@link BridgeTask}. Classic
 * Minecraft pillaring, driven tick-by-tick: stay centred, jump, and while airborne
 * place a block into the air cell under your feet (against the block below it), so
 * you land one higher. Repeat to the target Y.
 *
 * A directed execution primitive (like BridgeTask). The pathfinder integration
 * (place-as-a-move) drives it; it's also exposed via py4j (pillarTo) and is the
 * reach mechanism for goals #27's give-up currently abandons.
 */
public class PillarTask {

    private static boolean active = false;
    private static int targetY;
    private static int climbGoalY;   // the FINAL height this climb is heading to (for the visual);
                                     // the tower is built in chunks, but the whole column is drawn.
    private static int placed;
    private static int stuckTicks;
    private static double lastY;
    /** Ticks spent walking the body to the middle of its cell before the first jump (G42). */
    private static int centerTicks;
    private static final double CENTER_TOL = 0.2;
    private static final int CENTER_TICKS_MAX = 60;
    /** Towers that had to start off-centre because the walk to the middle timed out. */
    public static volatile int pillarCenterTimeout;
    /** Per-tower anatomy for the "stuck" verdict: airborne-and-rising ticks, ticks with a cell to
     *  place into, ticks the live ray was not on the support's top face, clicks refused, placed. */
    private static int dAir, dPlaceAt, dReadyNull, dTryFalse, dPlaced, dInsideCell;
    private static BlockPos dLastPlaceAt;
    /** How far above the cell's top the feet must be before a click is attempted (baritone: 0.1). */
    private static final double PLACE_CLEARANCE = 0.05;

    public static synchronized boolean startTo(int ty) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        targetY = ty;
        climbGoalY = ty;
        placed = 0;
        stuckTicks = 0;
        centerTicks = 0;
        dAir = dPlaceAt = dReadyNull = dTryFalse = dPlaced = dInsideCell = 0;
        dLastPlaceAt = null;
        lastY = p.getY();
        active = true;
        Debug.logMessage("Pillaring up to y=" + ty);
        return true;
    }

    /** Tell the visual how high the WHOLE climb is going (the final goal), so the green plan shows
     *  the entire tower even though it is built to intermediate waypoints one chunk at a time. */
    public static void setClimbGoal(int y) { if (y > climbGoalY) climbGoalY = y; }

    public static boolean isActive() { return active; }
    public static int getPlaced() { return placed; }

    public static void stop() {
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.useKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
        }
        WindMouseRotation.INSTANCE.clearTarget();
        kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.clear();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        WorldView world = player.getEntityWorld();
        var opts = mc.options;

        // reached target height (standing on / at the target level)
        if (player.getY() >= targetY - 0.05 && player.isOnGround()) {
            Debug.logMessage("Pillar done at y=" + String.format("%.1f", player.getY()) + " (placed " + placed + ")");
            stop();
            return;
        }

        // Same re-equip as BridgeTask: a tower that stops halfway because one stack ended is
        // not a tower. One policy, one place — helpers/BlockPlaceHelper.equipThrowaway.
        if (!kaptainwutax.tungsten.helpers.BlockPlaceHelper.equipThrowaway(player)) {
            Debug.logMessage("Pillar: out of blocks — nothing placeable in the hotbar");
            stop();
            return;
        }

        // Visualize the WHOLE tower still to be built (green), not just the next block. PillarTask
        // places one cell per hop, so the executor's single-cell overlay only ever showed the first
        // block of a tall pillar (user 2026-09-10). Draw every cell of this column from the bot's
        // current level up to the target, so the full plan is visible at once.
        if (kaptainwutax.tungsten.TungstenConfig.get().renderPlacePlan
                && !kaptainwutax.tungsten.task.FastNavigator.isActive()) {  // FastNavigator owns the overlay while it drives
            int cx = net.minecraft.util.math.MathHelper.floor(player.getX());
            int cz = net.minecraft.util.math.MathHelper.floor(player.getZ());
            int fy = net.minecraft.util.math.MathHelper.floor(player.getY());
            int top = Math.min(Math.max(targetY, climbGoalY), fy + 30);   // cap so a freak goal cannot draw an endless tower
            kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.clear();
            for (int y = fy - 1; y < top; y++) {
                kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.add(
                        new kaptainwutax.tungsten.render.Cuboid(
                                new Vec3d(cx + 0.1, y + 0.1, cz + 0.1),
                                new Vec3d(0.8, 0.8, 0.8),
                                new kaptainwutax.tungsten.render.Color(60, 220, 120)));
            }
        }

        // ⛔ A TOWER IS BUILT FROM THE MIDDLE OF ITS CELL (G42, 2026-09-11). "Stay centred" below
        // only released the keys; it never MOVED the body to the centre. On canopy_drop the wall
        // hand-off started this task with the body at x=764.0 -- exactly on the boundary between
        // two cells, where the previous manoeuvre had left it -- and the placement never fired:
        // the crosshair straight down lands on the neighbouring column, RealPlacement predicts a
        // different cell, no click, "Pillar stuck at y=-59.0" every twelve seconds for the whole
        // window. Baritone's MovementPillar centres before it jumps (the 0.17 test); so does the
        // navigator before a dig (G34). Walk to the centre first, sneaking so the body cannot
        // overshoot; jump only from there.
        {
            // ⛔ CENTRE ON THE CELL THAT HOLDS YOU UP, NOT ON floor(x). A body straddling the lip
            // of a gap has floor(x) in the AIR cell; walking to that cell's centre is walking off
            // the edge -- nav_bridge in round 7 read "the bot LEFT THE ARENA: min Y -120.4" for
            // exactly that, one deploy after this centring was added. Among the cells the hitbox
            // overlaps, take the nearest one with a solid block under it; with none, do not
            // centre at all.
            BlockPos column = supportedColumnUnder(player, world);
            double ccx = column == null ? player.getX() : column.getX() + 0.5;
            double ccz = column == null ? player.getZ() : column.getZ() + 0.5;
            double ox = ccx - player.getX(), oz = ccz - player.getZ();
            if (column != null && player.isOnGround() && ox * ox + oz * oz > CENTER_TOL * CENTER_TOL
                    && centerTicks < CENTER_TICKS_MAX) {
                centerTicks++;
                float yaw = (float) Math.toDegrees(-Math.atan2(ox, oz));
                WindMouseRotation.INSTANCE.setTarget(yaw, 15f);
                float err = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - player.getYaw()));
                opts.forwardKey.setPressed(err < 25f);
                opts.sneakKey.setPressed(true);
                opts.sprintKey.setPressed(false);
                opts.jumpKey.setPressed(false);
                lastY = player.getY();   // centring is not a stuck tower
                return;
            }
            if (centerTicks >= CENTER_TICKS_MAX && centerTicks < CENTER_TICKS_MAX + 1) {
                centerTicks++;
                pillarCenterTimeout++;   // could not centre in the time; build from here rather than never
            }
        }

        // Stay centred over the column (no horizontal drift) and aim straight down.
        opts.forwardKey.setPressed(false);
        opts.sprintKey.setPressed(false);
        opts.sneakKey.setPressed(false);
        WindMouseRotation.INSTANCE.setTarget(player.getYaw(), 89f); // pitch +89 = down

        // Jump off the ground; release jump while airborne (single hop per block).
        opts.jumpKey.setPressed(player.isOnGround());

        // Find the air cell directly under the player that has a solid block below it
        // (within 2 down) — that's where the pillar block goes. Only place while
        // airborne and rising, so we don't fight our own footing.
        if (!player.isOnGround() && player.getVelocity().y > -0.15) {
            double px = player.getX(), pz = player.getZ();
            BlockPos placeAt = null, against = null;
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos c = BlockPos.ofFloored(px, player.getY() - dy, pz);
                BlockPos b = c.down();
                if (isAir(world, c) && !isAir(world, b)) { placeAt = c; against = b; break; }
            }
            dAir++;
            // ⛔ THE BODY MUST HAVE LEFT THE CELL BEFORE THE CELL IS FILLED (G42, 2026-09-11).
            // "Airborne and rising" starts at the first tick off the ground, feet at +0.42, still
            // inside the cell the block is going into -- vanilla refuses a cube that intersects
            // an entity, so that click fails, and BlockPlaceHelper's rate gate is armed by the
            // attempt regardless: the next click is allowed four ticks later, after the apex, and
            // the tower never rises. Diagnosed on pit_escape: "air=81 placeAt=81 readyNull=0
            // tryFalse=81 placed=0" -- the ray on the right face every time, every click refused.
            // Baritone's MovementPillar clicks only at player.y > dest.y + 0.1 (feet above the
            // cell's top); this is that test, so the first click is the one inside the window.
            if (placeAt != null && player.getY() < placeAt.getY() + 1.0 + PLACE_CLEARANCE) {
                dInsideCell++;
                placeAt = null;
            }
            if (placeAt != null) {
                dPlaceAt++;
                if (!PlaceRules.canPlace(world, placeAt)) {
                    Debug.logMessage("Pillar stopped: protected/denied at " + placeAt.toShortString());
                    stop();
                    return;
                }
                // THIS DID NOT EVEN AIM. It forged a hit on the top face of the block below
                // and clicked, so a tower went up with the camera pointing anywhere at all —
                // a placement through geometry, not a placement. Now: look DOWN at that face
                // through the mouse pipeline, and click only when the player's own crosshair
                // agrees it would fill the cell (RealPlacement, ported from baritone's
                // MovementHelper.attemptToPlaceABlock).
                Vec3d faceCenter = Vec3d.ofCenter(against).add(0, 0.5, 0); // top face of the block below
                Vec3d dv = faceCenter.subtract(player.getEyePos());
                float wantYaw = (float) Math.toDegrees(-Math.atan2(dv.x, dv.z));
                float wantPitch = (float) Math.toDegrees(
                        -Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
                kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
                BlockHitResult hit =
                        kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(mc, placeAt);
                // Same shared rate gate as every other placement (helpers/BlockPlaceHelper).
                if (hit == null) {
                    dReadyNull++;
                } else if (kaptainwutax.tungsten.helpers.BlockPlaceHelper.tryPlace(hit)) {
                    // remember this pillar block as scaffolding so a cleanup can mine it back out
                    kaptainwutax.tungsten.util.ScaffoldRegistry.record(placeAt);
                    dPlaced++;
                } else {
                    dTryFalse++;
                }
                dLastPlaceAt = placeAt;
            }
        }

        // Progress / stuck detection on Y.
        if (player.getY() - lastY > 0.5) {
            placed++;
            lastY = player.getY();
            stuckTicks = 0;
        } else if (Math.abs(player.getY() - lastY) < 0.02) {
            if (++stuckTicks > 80) { // ~4s no vertical progress
                // SAY WHY, NOT JUST THAT. A tower that places nothing has one of four reasons --
                // never airborne, no cell to place into, the crosshair not on the support's top
                // face, or the click refused -- and "stuck" alone named none of them (canopy_drop
                // and pit_escape, 2026-09-11: 0 placed, 16 restarts, no idea which).
                String hitS = "-";
                try {
                    var lh = kaptainwutax.tungsten.path.movements.RotationHelper.liveHit(player);
                    if (lh instanceof BlockHitResult bh) {
                        hitS = bh.getBlockPos().toShortString() + "/" + String.valueOf(bh.getSide());
                    } else if (lh != null) {
                        hitS = lh.getType().name();
                    }
                } catch (Throwable ignored) {
                    // a diagnostic never breaks the tick it rides on
                }
                Debug.logMessage(String.format(
                        "Pillar stuck at y=%.1f  air=%d insideCell=%d placeAt=%d readyNull=%d tryFalse=%d placed=%d"
                        + " pitch=%.0f onGround=%b hit=%s lastPlaceAt=%s hand=%s",
                        player.getY(), dAir, dInsideCell, dPlaceAt, dReadyNull, dTryFalse, dPlaced,
                        player.getPitch(), player.isOnGround(), hitS,
                        dLastPlaceAt == null ? "-" : dLastPlaceAt.toShortString(),
                        player.getMainHandStack().getItem().toString()));
                stop();
            }
        }
    }

    private static boolean isAir(WorldView w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }

    /** The feet-level cell, among those the hitbox overlaps, that has a solid block under it and
     *  lies nearest the body -- the column a tower can be built in. Null when the body hangs over
     *  nothing (mid-bridge over a void), so the caller does not walk it anywhere. */
    private static BlockPos supportedColumnUnder(ClientPlayerEntity player, WorldView world) {
        net.minecraft.util.math.Box box = player.getBoundingBox();
        int fy = net.minecraft.util.math.MathHelper.floor(player.getY());
        double[][] corners = {
            {player.getX(), player.getZ()},
            {box.minX + 0.01, box.minZ + 0.01}, {box.minX + 0.01, box.maxZ - 0.01},
            {box.maxX - 0.01, box.minZ + 0.01}, {box.maxX - 0.01, box.maxZ - 0.01},
        };
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] c : corners) {
            BlockPos cell = BlockPos.ofFloored(c[0], fy, c[1]);
            if (isAir(world, cell.down())) continue;
            double dx = cell.getX() + 0.5 - player.getX(), dz = cell.getZ() + 0.5 - player.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = cell; }
        }
        return best;
    }
}

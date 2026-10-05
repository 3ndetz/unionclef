package adris.altoclef.util.agent;

import adris.altoclef.multiversion.entity.PlayerVer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Elytra flight to a point: equip the elytra, jump, open the wings, climb on fireworks to a
 * cruise height above the terrain, hold course, glide down and land near the target.
 *
 * <p>The pathfinder (tungsten) has no flight at all, and baritone's ElytraBehavior is not
 * compiled and is built around a native nether pathfinder. This is a small open-air controller
 * for the overworld. Flying itself is ElytraPilot: flight-path angle held like a plane, rate-
 * limited controls, rockets as throttle, a glide slope and a flare. It does not route around
 * mountains; it looks 120 blocks ahead and climbs over them.
 */
public final class ElytraFlight {

    public enum Phase { IDLE, EQUIP, TAKEOFF, FLYING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static double tx, ty, tz;
    private static int ticks, takeoffTicks, boostCooldown, rocketsUsed;
    private static int glideWait;
    private static final ElytraPilot pilot = new ElytraPilot();
    private static boolean pilotReady;
    private static volatile ElytraPilot.Out lastOut;
    // Fall watch: the wings open in a long fall, and a glide nobody steers is landed. "armed"
    // marks a glide that is ours (we opened the wings, or our flight was stopped) -- a player
    // flying this account by hand is never taken over.
    private static volatile boolean fallGlide = true;
    private static boolean armed, rescue;
    private static int deployCooldown;
    private static boolean glideSent;
    private static boolean registered;

    private static final int TIMEOUT_TICKS = 20 * 180;

    private ElytraFlight() {}

    /** Hook the client tick once: flights, and the fall watch between flights. */
    public static synchronized void register() {
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(ElytraFlight::tick);
            registered = true;
        }
    }

    /** Fall watch on/off (on by default): open the wings in a long fall and land. */
    public static void setFallGlide(boolean on) { fallGlide = on; }

    /** Start a flight; call on the client thread. */
    public static synchronized Map<String, Object> start(double x, double y, double z) {
        register();
        rescue = false;
        tx = x;
        ty = y;
        tz = z;
        ticks = takeoffTicks = boostCooldown = rocketsUsed = glideWait = 0;
        glideSent = false;
        pilotReady = false;
        lastOut = null;
        reason = "";
        phase = Phase.EQUIP;
        return status();
    }

    public static void stop() {
        if (phase != Phase.DONE && phase != Phase.FAILED) {
            phase = Phase.IDLE;
            reason = "stopped";
            armed = true; // still gliding: the fall watch lands it close by
            MinecraftClient.getInstance().execute(ElytraFlight::putRocketsAway);
        }
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("reason", reason);
        m.put("rocketsUsed", rocketsUsed);
        m.put("target", String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f", tx, ty, tz));
        var p = MinecraftClient.getInstance().player;
        if (p != null) {
            m.put("pos", String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", p.getX(), p.getY(), p.getZ()));
            m.put("distance", Math.round(Math.hypot(tx - p.getX(), tz - p.getZ())));
            m.put("gliding", adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p));
        }
        ElytraPilot.Out o = lastOut;
        if (o != null) { // flight telemetry, for tuning from the agent's log
            m.put("mode", o.mode.name().toLowerCase(java.util.Locale.ROOT));
            m.put("speed", Math.round(o.speed * 100) / 100.0);
            m.put("pitch", Math.round(o.pitch));
            m.put("climbAngle", Math.round(o.gamma));
        }
        return m;
    }

    private static void fail(String why) {
        phase = Phase.FAILED;
        reason = why;
        putRocketsAway();
    }

    private static void finish(String why) {
        phase = Phase.DONE;
        reason = why;
        putRocketsAway();
    }

    /** Leave a harmless stack in hand. With rockets selected, the next right click the pathfinder
     *  makes (placing a block, a fall clutch) launches a firework instead -- and with a full
     *  inventory the mod cannot de-equip it on its own. */
    private static void putRocketsAway() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        var inv = p.getInventory();
        if (!inv.getStack(PlayerVer.getSelectedSlot(inv)).isOf(Items.FIREWORK_ROCKET)) return;
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            var st = inv.getStack(i);
            if (st.isEmpty()) {
                PlayerVer.setSelectedSlot(inv, i);
                return;
            }
            if (fallback < 0 && st.getItem() instanceof net.minecraft.item.BlockItem) fallback = i;
        }
        if (fallback >= 0) PlayerVer.setSelectedSlot(inv, fallback);
    }

    private static void tick(MinecraftClient client) {
        Phase ph = phase;
        if (ph == Phase.IDLE || ph == Phase.DONE || ph == Phase.FAILED) {
            watchFall(client);
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.interactionManager == null) {
            fail("not in game");
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            fail("timeout");
            return;
        }
        if (boostCooldown > 0) boostCooldown--;

        switch (ph) {
            case EQUIP -> {
                if (p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
                    if (findItem(p, Items.FIREWORK_ROCKET) < 0) {
                        fail("no fireworks");
                        return;
                    }
                    phase = Phase.TAKEOFF;
                    return;
                }
                if (ticks > 40) {
                    fail("could not equip elytra");
                    return;
                }
                // Right-clicking an elytra in hand puts it on (and swaps out a chestplate).
                if (holdInHotbar(client, p, Items.ELYTRA)) {
                    client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                } else {
                    fail("no elytra");
                }
            }
            case TAKEOFF -> {
                // Creative flight (double jump) blocks gliding: make sure it is off.
                if (p.getAbilities().flying) {
                    p.getAbilities().flying = false;
                    p.sendAbilitiesUpdate();
                }
                // Wings do not open in water: say so at once instead of jumping for 12 seconds.
                if (p.isTouchingWater() || p.isInLava()) {
                    fail("in water");
                    return;
                }
                if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
                    phase = Phase.FLYING; // the pilot fires the first rocket on its first tick
                    return;
                }
                if (++takeoffTicks > 20 * 12) {
                    fail("could not take off");
                    return;
                }
                aim(p, tx - p.getX(), tz - p.getZ(), -10);
                if (p.isOnGround()) {
                    p.jump();
                    glideSent = false;
                    glideWait = 0;
                } else if (!glideSent && p.getVelocity().y < 0) {
                    // Past the top of the jump: open the wings ONCE. The server answers a repeat
                    // while already gliding with stopGliding(), and the client sees the gliding
                    // flag a tick or two late -- sending every tick closed the wings again.
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    glideSent = true;
                } else if (glideSent && ++glideWait > 10) {
                    glideSent = false; // no confirmation this jump: try again on the next one
                    glideWait = 0;
                }
            }
            case FLYING -> fly(client, p);
            default -> { }
        }
    }

    private static void fly(MinecraftClient client, ClientPlayerEntity p) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (!adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
            // Down within 30 blocks: good enough, the agent walks the rest. Further: fly again.
            if (p.isOnGround() && dist < 30) {
                finish("landed");
            } else if (p.isOnGround()) {
                takeoffTicks = 0;
                phase = Phase.TAKEOFF;
            }
            return;
        }
        if (!pilotReady) {
            pilot.reset(p.getYaw(), p.getPitch());
            pilotReady = true;
        }
        var v = p.getVelocity();
        ElytraPilot.Out o = pilot.step(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
                tx, landingY(client), tz, terrainAhead(client, p), top(client, p.getX(), p.getZ()),
                runwayClear(client, p));
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        if (o.boost && !rescue && !boost(client, p)) pilot.boostFailed(); // a rescue glides, no rockets
        if (o.arrived) finish(rescue ? "glided down" : "arrived");
    }

    /** Between flights: open the wings in a long fall; land a glide that is ours. */
    private static void watchFall(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (!fallGlide || p == null || client.world == null) return;
        if (deployCooldown > 0) deployCooldown--;
        boolean gliding = adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p);
        if (p.isOnGround() || p.isTouchingWater() || p.isInLava() || p.hasVehicle() || p.isClimbing()
                || p.getAbilities().flying || !p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            armed = false;
            return;
        }
        double height = p.getY() - top(client, p.getX(), p.getZ());
        if (gliding) {
            if (armed) startRescue(client, p, height);
            return;
        }
        // About 6 blocks of free fall so far and more to go: a bucket clutch at this speed is a
        // gamble, wings are not.
        if (p.getVelocity().y < -0.6 && height > 4 && deployCooldown == 0) {
            p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            deployCooldown = 5;
            armed = true;
        }
    }

    /** Land the glide at a spot ahead along the way it is going. */
    private static void startRescue(MinecraftClient client, ClientPlayerEntity p, double height) {
        var v = p.getVelocity();
        double hs = Math.hypot(v.x, v.z);
        double ahead = Math.max(14, Math.min(60, height * 2.5));
        double dirX, dirZ;
        if (hs > 0.1) {
            dirX = v.x / hs;
            dirZ = v.z / hs;
        } else { // straight down: wherever the bot faces
            double yawRad = Math.toRadians(p.getYaw());
            dirX = -Math.sin(yawRad);
            dirZ = Math.cos(yawRad);
        }
        tx = p.getX() + dirX * ahead;
        tz = p.getZ() + dirZ * ahead;
        ty = top(client, tx, tz);
        ticks = takeoffTicks = boostCooldown = rocketsUsed = glideWait = 0;
        pilot.resetForLanding(p.getYaw(), p.getPitch());
        pilotReady = true;
        lastOut = null;
        rescue = true;
        armed = false;
        reason = "rescue";
        phase = Phase.FLYING;
    }

    /** Ground height at x,z from the heightmap; the world bottom where the chunk is not loaded. */
    private static double top(MinecraftClient client, double x, double z) {
        return client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Land on the ground at the target when its chunk is loaded, else at the height asked for. */
    private static double landingY(MinecraftClient client) {
        int cx = (int) Math.floor(tx) >> 4, cz = (int) Math.floor(tz) >> 4;
        if (!client.world.getChunkManager().isChunkLoaded(cx, cz)) return ty;
        return top(client, tx, tz);
    }

    /** Nothing standing into a 7-degree approach over the last 50 blocks: land like a plane. */
    private static boolean runwayClear(MinecraftClient client, ClientPlayerEntity p) {
        double ly = landingY(client);
        double dx = p.getX() - tx, dz = p.getZ() - tz, d = Math.max(Math.hypot(dx, dz), 1e-6);
        double tan = Math.tan(Math.toRadians(7));
        for (double k = 2; k <= Math.min(50, d); k += 2) {
            double h = top(client, tx + dx / d * k, tz + dz / d * k);
            // Level ground is the runway itself; anything a block up, or into the slope, is not.
            if (h > ly + 0.9 && h > ly + k * tan - 1.5) return false;
        }
        return true;
    }

    /** Highest ground every 8 blocks along the course, up to 120 blocks ahead. */
    private static double terrainAhead(MinecraftClient client, ClientPlayerEntity p) {
        double dx = tx - p.getX(), dz = tz - p.getZ(), dist = Math.max(Math.hypot(dx, dz), 1e-6);
        double m = landingY(client);
        for (double k = 0; k <= Math.min(120, dist); k += 8) {
            m = Math.max(m, top(client, p.getX() + dx / dist * k, p.getZ() + dz / dist * k));
        }
        return m;
    }

    private static void aim(ClientPlayerEntity p, double dx, double dz, float pitch) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    /** Fire a rocket; false when it could not be used this tick (the pilot asks again). */
    private static boolean boost(MinecraftClient client, ClientPlayerEntity p) {
        // A rocket used on the ground just flies off on its own: only while gliding.
        if (boostCooldown > 0 || !adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) return false;
        if (!holdInHotbar(client, p, Items.FIREWORK_ROCKET)) {
            reason = "out of fireworks";
            return false;
        }
        // The swap into the hotbar lands next tick: use the rocket only once it is in hand.
        if (!p.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return false;
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketsUsed++;
        boostCooldown = 10; // the pilot paces rockets; this only stops double clicks
        return true;
    }

    /** Inventory index (0..35) of the item, or -1. */
    private static int findItem(ClientPlayerEntity p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** Make the item the selected hotbar stack, moving it from the main inventory if needed. */
    private static boolean holdInHotbar(MinecraftClient client, ClientPlayerEntity p, Item item) {
        int i = findItem(p, item);
        if (i < 0) return false;
        if (i >= 9) {
            // Swap the stack into hotbar slot 8: player screen slot i, hotbar button 8.
            client.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, 8, SlotActionType.SWAP, p);
            i = 8;
        }
        PlayerVer.setSelectedSlot(p.getInventory(), i);
        return true;
    }
}

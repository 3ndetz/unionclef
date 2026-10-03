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
 * for the overworld: it steers by yaw/pitch every client tick and spends a rocket when the
 * speed drops. It does not route around mountains; it flies high enough to clear them.
 */
public final class ElytraFlight {

    public enum Phase { IDLE, EQUIP, TAKEOFF, FLYING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static double tx, ty, tz;
    private static int ticks, takeoffTicks, boostCooldown, rocketsUsed;
    private static boolean registered;

    private static final int TIMEOUT_TICKS = 20 * 180;
    private static final int CRUISE_ABOVE_TERRAIN = 24;
    private static final double ARRIVE_DIST = 4.0;

    private ElytraFlight() {}

    /** Start a flight; call on the client thread. */
    public static synchronized Map<String, Object> start(double x, double y, double z) {
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(ElytraFlight::tick);
            registered = true;
        }
        tx = x;
        ty = y;
        tz = z;
        ticks = takeoffTicks = boostCooldown = rocketsUsed = 0;
        reason = "";
        phase = Phase.EQUIP;
        return status();
    }

    public static void stop() {
        if (phase != Phase.DONE && phase != Phase.FAILED) {
            phase = Phase.IDLE;
            reason = "stopped";
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
        return m;
    }

    private static void fail(String why) {
        phase = Phase.FAILED;
        reason = why;
    }

    private static void tick(MinecraftClient client) {
        Phase ph = phase;
        if (ph == Phase.IDLE || ph == Phase.DONE || ph == Phase.FAILED) return;
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
                if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
                    phase = Phase.FLYING;
                    boost(client, p);
                    return;
                }
                if (++takeoffTicks > 60) {
                    fail("could not take off");
                    return;
                }
                aim(p, tx - p.getX(), tz - p.getZ(), -10);
                if (p.isOnGround()) {
                    p.jump();
                } else if (p.getVelocity().y < 0) {
                    // Falling after the jump: open the wings, as pressing jump mid-air does.
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
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
            if (p.isOnGround() && dist < ARRIVE_DIST * 3) {
                phase = Phase.DONE;
                reason = "landed";
            } else if (p.isOnGround()) {
                takeoffTicks = 0;
                phase = Phase.TAKEOFF; // touched down early: take off again
            }
            return;
        }
        int terrain = Math.max(
                client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) p.getX(), (int) p.getZ()),
                client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) tx, (int) tz));
        double cruise = Math.max(ty, terrain) + CRUISE_ABOVE_TERRAIN;
        double above = p.getY() - ty;
        double speed = p.getVelocity().length();

        float pitch;
        if (dist < Math.max(12, above * 1.4)) {
            // Final approach: aim straight at the landing spot, nose down at most 45 degrees.
            pitch = (float) Math.min(45, Math.max(5, Math.toDegrees(Math.atan2(above, Math.max(dist, 1)))));
            if (dist < ARRIVE_DIST && above < 3) {
                phase = Phase.DONE;
                reason = "arrived";
            }
        } else if (p.getY() < cruise) {
            pitch = -30; // climb
            if (speed < 1.1) boost(client, p);
        } else {
            pitch = 3; // cruise: shallow glide keeps speed
            if (speed < 0.7) boost(client, p);
        }
        aim(p, dx, dz, pitch);
    }

    private static void aim(ClientPlayerEntity p, double dx, double dz, float pitch) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    private static void boost(MinecraftClient client, ClientPlayerEntity p) {
        if (boostCooldown > 0) return;
        if (!holdInHotbar(client, p, Items.FIREWORK_ROCKET)) {
            reason = "out of fireworks";
            return;
        }
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketsUsed++;
        boostCooldown = 30;
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

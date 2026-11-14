package adris.altoclef.util.agent;

import adris.altoclef.multiversion.entity.PlayerVer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A fireworks show from the ground: colored rockets launched one after another in a fan in
 * front of the bot. Creative only -- each rocket is made in the creative inventory with its own
 * stars (shape, one to three dye colors, a fade, now and then a trail or a twinkle).
 *
 * <p>A rocket starts where the player clicks a block, as a player does it: the bot looks at the
 * ground a few blocks ahead and uses the rocket on the block the game's own crosshair ray hits
 * -- no hit result made up from coordinates.
 */
public final class Salute {

    public enum Phase { IDLE, FIRING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static int total, fired, wait, slot, prevSlot, misses;
    private static float baseYaw, prevPitch;
    private static ItemStack prevStack = ItemStack.EMPTY; // what the borrowed slot held
    private static boolean registered;
    private static final Random RND = new Random();

    private static final int SPACING_TICKS = 12;   // a rocket every 0.6 s
    private static final float FAN_DEGREES = 50;   // spread of the launch points left to right
    private static final DyeColor[] PALETTE = {
        DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE,
        DyeColor.BLUE, DyeColor.PURPLE, DyeColor.MAGENTA, DyeColor.PINK, DyeColor.WHITE, DyeColor.CYAN,
    };
    private static final FireworkExplosionComponent.Type[] SHAPES = {
        FireworkExplosionComponent.Type.LARGE_BALL, FireworkExplosionComponent.Type.SMALL_BALL,
        FireworkExplosionComponent.Type.STAR, FireworkExplosionComponent.Type.BURST,
        FireworkExplosionComponent.Type.LARGE_BALL,
    };

    private Salute() {}

    /** Launch count colored rockets (1..12); call on the client thread. */
    public static synchronized Map<String, Object> start(int count) {
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(Salute::tick);
            registered = true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.interactionManager == null) return fail("not in game");
        if (!p.getAbilities().creativeMode) return fail("not creative");
        if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p) || !p.isOnGround()) return fail("not on the ground");
        var inv = p.getInventory();
        prevSlot = PlayerVer.getSelectedSlot(inv);
        slot = 8; // an empty hotbar slot if there is one, else the last: it is restored after
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                slot = i;
                break;
            }
        }
        prevStack = inv.getStack(slot).copy();
        total = Math.max(1, Math.min(count, 12));
        fired = misses = 0;
        wait = 0;
        baseYaw = p.getYaw();
        prevPitch = p.getPitch();
        reason = "";
        phase = Phase.FIRING;
        return status();
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("reason", reason);
        m.put("fired", fired);
        m.put("total", total);
        return m;
    }

    public static void stop() {
        if (phase == Phase.FIRING) finish(Phase.DONE, "stopped");
    }

    private static Map<String, Object> fail(String why) {
        phase = Phase.FAILED;
        reason = why;
        return status();
    }

    private static void tick(MinecraftClient client) {
        if (phase != Phase.FIRING) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.interactionManager == null) {
            phase = Phase.FAILED;
            reason = "not in game";
            return;
        }
        if (fired >= total) {
            finish(Phase.DONE, "done");
            return;
        }
        // Aim: fan the launch points across the front, a few blocks ahead on the ground.
        float spread = total == 1 ? 0 : -FAN_DEGREES / 2 + FAN_DEGREES * fired / (total - 1);
        float yaw = baseYaw + spread, pitch = 55;
        p.setYaw(p.getYaw() + clamp(wrap(yaw - p.getYaw()), 10));
        p.setPitch(p.getPitch() + clamp(pitch - p.getPitch(), 10));
        if (wait > 0) {
            wait--;
            return;
        }
        boolean aimed = Math.abs(wrap(yaw - p.getYaw())) < 2 && Math.abs(pitch - p.getPitch()) < 2;
        HitResult hit = client.crosshairTarget; // the game's own ray, a frame after the turn
        if (!aimed || hit == null || hit.getType() != HitResult.Type.BLOCK) {
            if (aimed && ++misses > 40) finish(Phase.FAILED, "no ground in front");
            return;
        }
        var inv = p.getInventory();
        ItemStack rocket = rocket();
        inv.setStack(slot, rocket.copy());
        // Player screen handler: hotbar 0..8 is slot 36..44.
        client.interactionManager.clickCreativeStack(rocket, 36 + slot);
        PlayerVer.setSelectedSlot(inv, slot);
        client.interactionManager.interactBlock(p, Hand.MAIN_HAND, (BlockHitResult) hit);
        p.swingHand(Hand.MAIN_HAND);
        fired++;
        misses = 0;
        wait = SPACING_TICKS;
    }

    /** One rocket with random stars: 1..2 explosions, flight 1..3. */
    private static ItemStack rocket() {
        int n = RND.nextInt(4) == 0 ? 2 : 1;
        List<FireworkExplosionComponent> stars = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            IntList colors = new IntArrayList();
            int k = 1 + RND.nextInt(3);
            for (int c = 0; c < k; c++) colors.add(PALETTE[RND.nextInt(PALETTE.length)].getFireworkColor());
            IntList fade = new IntArrayList();
            if (RND.nextBoolean()) fade.add(PALETTE[RND.nextInt(PALETTE.length)].getFireworkColor());
            stars.add(new FireworkExplosionComponent(SHAPES[RND.nextInt(SHAPES.length)], colors, fade,
                    RND.nextInt(3) == 0, RND.nextInt(3) == 0));
        }
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET, 1);
        stack.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1 + RND.nextInt(3), stars));
        return stack;
    }

    /** Give the borrowed slot back as it was, select the old slot, look ahead again. */
    private static void finish(Phase end, String why) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity p = client.player;
        if (p != null && client.interactionManager != null) {
            var inv = p.getInventory();
            if (fired > 0) { // the slot holds a show rocket (or nothing): put back what was there
                inv.setStack(slot, prevStack.copy());
                client.interactionManager.clickCreativeStack(prevStack.copy(), 36 + slot);
            }
            PlayerVer.setSelectedSlot(inv, prevSlot);
            p.setPitch(Math.min(prevPitch, 20));
        }
        phase = end;
        reason = why;
    }

    private static float clamp(float v, float lim) { return Math.max(-lim, Math.min(lim, v)); }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}

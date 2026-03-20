/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.utils.IPlayerContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Render-frame right-click helper for god bridge mode.
 * Called every render frame from MixinMinecraft to achieve jitter-click speeds (30-40+ CPS).
 * Uses the same processRightClickBlock path as BlockPlaceHelper.
 */
public class GodBridgeClickHelper {

    private static volatile boolean active = false;
    private static IPlayerContext ctx = null;

    // Jitter state: burst of clicks with micro-pauses
    private static int burstRemaining = 0;
    private static long nextClickNanos = 0;

    // Jitter timing constants (nanoseconds)
    private static final long CLICK_INTERVAL_MIN = 8_000_000L;   // 8ms between clicks in burst
    private static final long CLICK_INTERVAL_MAX = 25_000_000L;  // 25ms between clicks in burst
    private static final long PAUSE_MIN = 15_000_000L;           // 15ms pause between bursts
    private static final long PAUSE_MAX = 40_000_000L;           // 40ms pause between bursts
    private static final int BURST_MIN = 20;
    private static final int BURST_MAX = 40;

    public static void activate(IPlayerContext playerContext) {
        ctx = playerContext;
        active = true;
    }

    public static void deactivate() {
        active = false;
        ctx = null;
        burstRemaining = 0;
        nextClickNanos = 0;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Called every render frame from MixinMinecraft.render().
     * Same right-click path as BlockPlaceHelper: objectMouseOver → processRightClickBlock.
     */
    public static void onRenderFrame() {
        if (!active) return;
        IPlayerContext localCtx = ctx;
        if (localCtx == null) return;
        try {
            if (localCtx.player() == null || localCtx.world() == null || localCtx.player().isRiding()) return;
            if (localCtx.playerController() == null) return;
        } catch (Exception e) {
            return;
        }

        long now = System.nanoTime();
        if (now < nextClickNanos) return;

        HitResult mouseOver;
        try {
            mouseOver = localCtx.objectMouseOver();
        } catch (Exception e) {
            return;
        }
        if (mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Refill burst if depleted — no wasted frames, click immediately
        if (burstRemaining <= 0) {
            burstRemaining = rng.nextInt(BURST_MIN, BURST_MAX + 1);
        }

        // Right-click through same path as BlockPlaceHelper
        BlockHitResult blockHit = (BlockHitResult) mouseOver;
        try {
            for (Hand hand : Hand.values()) {
                if (localCtx.playerController().processRightClickBlock(localCtx.player(), localCtx.world(), hand, blockHit) == ActionResult.SUCCESS) {
                    localCtx.player().swingHand(hand);
                    break;
                }
                if (!localCtx.player().getStackInHand(hand).isEmpty()
                        && localCtx.playerController().processRightClick(localCtx.player(), localCtx.world(), hand) == ActionResult.SUCCESS) {
                    break;
                }
            }
        } catch (Exception e) {
            deactivate();
            return;
        }

        burstRemaining--;
        if (burstRemaining <= 0) {
            // End of burst — short inter-burst pause
            nextClickNanos = now + rng.nextLong(PAUSE_MIN, PAUSE_MAX);
        } else {
            // Mid-burst — micro-pause between clicks
            nextClickNanos = now + rng.nextLong(CLICK_INTERVAL_MIN, CLICK_INTERVAL_MAX);
        }
    }
}

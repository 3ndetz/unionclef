package kaptainwutax.tungsten.helpers;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * PLACE THROUGH THE GAME, NOT AROUND IT.
 *
 * <p>Every placement in tungsten used to build its own {@link BlockHitResult} out of a face
 * centre and hand that to {@code interactionManager.interactBlock}. That is not a placement,
 * it is a forged interaction: the packet claims the player clicked a face the player was
 * never looking at, so blocks appeared through block edges with the camera pointing
 * elsewhere. Three sites did it — the executor's place queue, {@code BridgeTask} and
 * {@code PillarTask} — and one of them even carried a comment calling the camera "cosmetic".
 *
 * <p>Baritone does the opposite, and this is a port of its gate.
 * {@code MovementHelper.attemptToPlaceABlock} (baritone/.../MovementHelper.java:806-856)
 * aims at the face, RAY TRACES from the player's own eyes, and accepts the placement only
 * when the trace lands on the intended block with the intended side — the test being
 * {@code hit.getBlockPos().offset(hit.getSide()).equals(placeAt)}. Then it presses use, and
 * vanilla's own ray trace produces the packet.
 *
 * <p>So: aim, wait for the real crosshair to agree, and place with the REAL hit result. If
 * the aim never converges, the placement does not happen and that is a bug to fix in the aim,
 * not to paper over with a forged packet.
 */
public final class RealPlacement {

    private RealPlacement() {}

    /**
     * The player's actual crosshair hit, but only if using it would fill {@code target}.
     *
     * @return the real {@link BlockHitResult} to pass to {@code interactBlock}, or null when
     *         the camera is not yet looking somewhere that would produce the wanted block.
     */
    public static BlockHitResult readyToPlace(MinecraftClient mc, BlockPos target) {
        // A LIVE RAY TRACE, NOT mc.crosshairTarget. The cached crosshair is computed once per
        // RENDER frame, so at this stand's ~10 fps it is 1-2 ticks — 100-200 ms — out of date,
        // and the gate then judges a placement against where the camera USED to point. Upstream
        // never reads a cache here: MovementHelper.attemptToPlaceABlock ray-traces from the
        // player each time (RayTraceUtils.rayTraceTowards). RotationHelper.liveHit is that trace,
        // ported with it. Named as trap 4 in docs/BARITONE-PORT-SPEC.md.
        HitResult ct = mc.player == null ? null
                : kaptainwutax.tungsten.path.movements.RotationHelper.liveHit(mc.player);
        if (ct == null || ct.getType() != HitResult.Type.BLOCK
                || !(ct instanceof BlockHitResult hit)) {
            return null;
        }
        BlockPos hitPos = hit.getBlockPos();
        // The normal case: we are looking at a neighbour's face, and the block will appear in
        // the cell on that side of it. BOTH halves of upstream's condition
        // (MovementHelper.attemptToPlaceABlock:845-847): the geometry has to work AND the block
        // being clicked has to be one you can actually place against. Only the geometry was
        // ported, so any hit whose side happened to point at the target was promoted to a click
        // — including hits on the very blocks canPlaceAgainst exists to refuse.
        if (hitPos.offset(hit.getSide()).equals(target)
                && mc.world != null && canPlaceAgainst(mc.world, hitPos)) {
            return hit;
        }
        // Baritone also allows looking straight AT the target when the block already there is
        // replaceable (tall grass, snow layer, water) — the new block takes its place.
        if (hitPos.equals(target) && mc.world != null
                && mc.world.getBlockState(hitPos).isReplaceable()) {
            return hit;
        }
        return null;
    }

    /**
     * Can a face of this block be clicked against — {@code MovementHelper.canPlaceAgainst}
     * (baritone/.../MovementHelper.java:637-647).
     *
     * <p>THIS USED TO BE A SECOND, WEAKER COPY OF A PORT THAT ALREADY EXISTED. It kept only the
     * shape test — {@code isShapeFullCube} plus glass — and dropped the three guards upstream
     * puts in front of it, all of which {@code MovementHelperB.canPlaceAgainst}
     * (MovementHelperB.java:729-741) had already ported correctly:
     *
     * <ul>
     *   <li>{@code shouldAvoidPlacingAt} -&gt; {@code PlaceRules.allowedByPolicy}, so the builder
     *       stopped honouring claims and protected areas for the block it CLICKS (the target cell
     *       was checked; the support was not).</li>
     *   <li>the world border.</li>
     *   <li>{@code isBlockNormalCube}'s explicit blacklist (MovementHelper.java:790-797):
     *       bamboo, moving pistons, scaffolding, SHULKER BOXES, pointed dripstone, amethyst.
     *       Their collision shape says "full cube" and lies about it. A shulker box next to the
     *       target passed the shape test, the click OPENED it, {@code interactBlock} returned
     *       SUCCESS, and the queue counted a block it never placed while the bot sat in a
     *       container GUI.</li>
     * </ul>
     *
     * <p>So it now forwards to the real port. Two spellings of one upstream function is exactly
     * the duplication this project keeps paying for.
     */
    public static boolean canPlaceAgainst(WorldView world, BlockPos pos) {
        return kaptainwutax.tungsten.path.movements.MovementHelperB.canPlaceAgainst(
                world, pos.getX(), pos.getY(), pos.getZ(), world.getBlockState(pos));
    }
}

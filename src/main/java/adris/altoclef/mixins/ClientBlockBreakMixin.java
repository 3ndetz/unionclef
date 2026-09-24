package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public final class ClientBlockBreakMixin {

    // for SOME REASON baritone triggers a block cancel breaking every other frame, so we have a 2 frame requirement for that?
    @Unique
    private static int _breakCancelFrames;

    @Shadow private float currentBreakingProgress;
    @Shadow private BlockPos currentBreakingPos;
    @Shadow private boolean breakingBlock;
    @Shadow private int blockBreakingCooldown;

    @Inject(
            method = "updateBlockBreakingProgress",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onBreakUpdate(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        // ⛔ NEVER CONTINUE A BREAK THE SERVER NEVER STARTED -- baritone BlockBreakHelper.tick: when the
        // controller is not hitting a block (hasBrokenBlock), it calls clickBlock (a fresh
        // START_DESTROY_BLOCK) instead of onPlayerDamageBlock.
        //
        // Vanilla 1.21.11 continues a break whenever currentBreakingPos equals the target, WITHOUT
        // checking breakingBlock. After a break the server rejected, breakingBlock is false but
        // currentBreakingPos still names the block the server put back; holding attack then mines it
        // again with no START, every STOP reaches a server whose miningPos is unset, and the server
        // restores the block every time. Measured on nav_cliff (2026-09-24): one START, sixteen
        // client-side completions in a row, the stone never gone on the server -- the bot stood
        // mining the same block for the rest of the course. So: not hitting -> start over properly.
        if (!breakingBlock && blockBreakingCooldown <= 0 && currentBreakingPos != null
                && currentBreakingPos.equals(pos)
                && MinecraftClient.getInstance().player != null
                && !MinecraftClient.getInstance().player.getAbilities().creativeMode) {
            adris.altoclef.control.BreakResetTrace.freshStarts++;
            ci.setReturnValue(((ClientPlayerInteractionManager) (Object) this).attackBlock(pos, direction));
            return;
        }
        // A new target while a break has progress throws that progress away (vanilla restarts).
        if (breakingBlock && currentBreakingPos != null && !currentBreakingPos.equals(pos)) {
            adris.altoclef.control.BreakResetTrace.noteDiscard("retarget", currentBreakingProgress);
        }
        ClientBlockBreakAccessor breakAccessor = (ClientBlockBreakAccessor) (MinecraftClient.getInstance().interactionManager);
        if (breakAccessor != null) {
            _breakCancelFrames = 2;
            EventBus.publish(new BlockBreakingEvent(pos, breakAccessor.getCurrentBreakingProgress()));
        }
    }

    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void onAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        adris.altoclef.control.BreakResetTrace.event("ATTACK", pos == null ? "-" : pos.toShortString(),
                breakingBlock, currentBreakingProgress, blockBreakingCooldown);
        adris.altoclef.control.BreakResetTrace.starts++;
        adris.altoclef.control.BreakResetTrace.lastStarted = pos == null ? "-" : pos.toShortString()
                + "/" + direction + (breakingBlock ? " (was " + currentBreakingPos.toShortString() + ")" : "");
    }

    @Inject(method = "breakBlock", at = @At("HEAD"))
    private void onBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        adris.altoclef.control.BreakResetTrace.event("BREAK", pos == null ? "-" : pos.toShortString(),
                breakingBlock, currentBreakingProgress, blockBreakingCooldown);
        adris.altoclef.control.BreakResetTrace.completions++;
        adris.altoclef.control.BreakResetTrace.lastCompleted = pos == null ? "-" : pos.toShortString();
    }

    @Inject(
            method = "cancelBlockBreaking",
            at = @At("HEAD")
    )
    private void cancelBlockBreaking(CallbackInfo ci) {
        if (breakingBlock) {
            adris.altoclef.control.BreakResetTrace.event("CANCEL", currentBreakingPos == null ? "-" : currentBreakingPos.toShortString(),
                    breakingBlock, currentBreakingProgress, blockBreakingCooldown);
            adris.altoclef.control.BreakResetTrace.noteDiscard("cancel", currentBreakingProgress);
        }
        if (_breakCancelFrames-- == 0) {
            EventBus.publish(new BlockBreakingCancelEvent());
        }
    }
}

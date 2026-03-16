package kaptainwutax.tungsten.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.gui.hud.ChatHud;

/**
 * Fixes a vanilla MC 1.21 off-by-one bug in ChatHud.addVisibleMessage:
 *   list.remove(list.size())   ← IndexOutOfBoundsException when list is full
 * should be:
 *   list.remove(list.size()-1)
 *
 * Triggered by any sendMessage call when the visible message list is at max capacity (100).
 */
@Mixin(ChatHud.class)
public class MixinChatHud {

    @ModifyArg(
        method = "addVisibleMessage",
        at = @At(value = "INVOKE", target = "Ljava/util/List;remove(I)Ljava/lang/Object;")
    )
    private int fixRemoveOutOfBounds(int index) {
        // Vanilla passes list.size() (out of bounds); correct to list.size()-1
        return index - 1;
    }
}

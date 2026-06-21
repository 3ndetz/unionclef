package adris.altoclef.mixins;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screen.ConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the yes/no callback of a ConfirmScreen so the bot can AUTO-ACCEPT the
 * "this server requires a resource pack" prompt (operator 2026-06-21). Declining that
 * prompt DISCONNECTS the bot (e.g. fdmc.pw), which kept it bouncing off the server.
 */
@Mixin(ConfirmScreen.class)
public interface ConfirmScreenAccessor {
    @Accessor("callback")
    BooleanConsumer getCallback();
}

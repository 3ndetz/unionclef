package adris.altoclef.mixins;

import java.io.File;
import net.minecraft.client.util.ScreenshotRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ScreenshotRecorder.class)
public interface ScreenshotRecorderInvoker {
    @Invoker("getScreenshotFilename")
    static File invokeGetScreenshotFileName(File dir) {
        throw new AssertionError();
    }
}

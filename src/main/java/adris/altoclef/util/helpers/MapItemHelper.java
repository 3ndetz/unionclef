package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.ImageComparer;
import adris.altoclef.util.slots.PlayerSlot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;


public class MapItemHelper {
    public static String saveNonExistMapToDataset(AltoClef mod) {
        return saveNonExistMapToDataset(mod, false);
    }

    public static String saveNonExistMapToDataset(AltoClef mod, boolean neural_solve) {
        ItemStack item = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        if (item != null) {
            return saveNonExistMapToDataset(item, mod, neural_solve);
        }
        return "";
    }

    public static String saveNonExistMapToDataset(ItemStack stack, AltoClef mod) {
        return saveNonExistMapToDataset(stack, mod, false);
    }

    public static String saveNonExistMapToDataset(ItemStack stack, AltoClef mod, boolean neural_solve) {
        // Map rendering disabled — requires MapRendererInvoker/MapTextureAccessor to be fully implemented.
        // See autoclef source for full commented-out implementation.
        return "";
    }

    public static String saveMapFile(AltoClef mod, Object mapId, Object mapState, boolean neural_solve) {
        // Map file saving disabled — requires MapRendererInvoker/MapTextureAccessor to be fully implemented.
        return "";
    }

    public static void saveImageFile(byte[] bytes_img, File screenshot) {
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                BufferedImage buffered_img = ImageComparer.byte2BufferedImage(bytes_img);
                if (buffered_img != null) {
                    ImageIO.write(buffered_img, "png", screenshot);
                    Debug.logMessage("[CAPTCHA] IMAGE SAVED! Name=" + screenshot.getName());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}

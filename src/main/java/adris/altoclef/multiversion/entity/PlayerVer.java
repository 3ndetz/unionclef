package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

public class PlayerVer {


    public static void sendChatMessage(ClientPlayerEntity player,String content) {
        //#if MC >= 11904
        player.networkHandler.sendChatMessage(content);
        //#else
        //$$ player.sendChatMessage(content);
        //#endif
    }

    public static void sendChatCommand(ClientPlayerEntity player,String content) {
        //#if MC >= 11904
        player.networkHandler.sendChatCommand(content);
        //#else
        //$$ player.sendChatMessage("/"+content);
        //#endif
    }

    @Pattern
    private static ItemStack getCursorStack(PlayerEntity player) {
        //#if MC >= 11701
        return player.currentScreenHandler.getCursorStack();
        //#else
        //$$ return player.inventory.getCursorStack();
        //#endif
    }

    @Pattern
    private static Inventory getInventory(PlayerEntity player) {
        //#if MC >= 11701
        return player.getInventory();
        //#else
        //$$ return player.inventory;
        //#endif
    }

    public static boolean inPowderedSnow(PlayerEntity player) {
        //#if MC >= 11701
        return player.inPowderSnow;
        //#else
        //$$ return false;
        //#endif
    }

    @Pattern
    public static int getSelectedSlot(net.minecraft.entity.player.PlayerInventory inv) {
        //#if MC >= 12111
        //$$ return inv.getSelectedSlot();
        //#else
        return inv.selectedSlot;
        //#endif
    }

    /**
     * ⛔ SYNCS THE SLOT TO THE SERVER TOO, because every caller here is one swing away from the
     * race this shim used to hide: the field is written locally and vanilla posts the packet on
     * its own tick, so anything acting in the same tick acts with the PREVIOUS item. Measured on
     * allround as ~12 hits of exactly 1.0 hp a run -- a bow's attack damage -- with an iron_sword
     * drawn on the client. Behind syncSlotToServer while it is being measured.
     */
    public static void setSelectedSlot(net.minecraft.entity.player.PlayerInventory inv, int slot) {
        //#if MC >= 12111
        //$$ inv.setSelectedSlot(slot);
        //#else
        inv.selectedSlot = slot;
        //#endif
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getInventory() == inv) {
            kaptainwutax.tungsten.combat.WeaponSelector.syncSlot(mc.player, slot);
        }
    }

}

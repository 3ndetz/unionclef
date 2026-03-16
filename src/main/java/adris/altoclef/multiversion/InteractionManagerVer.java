package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

public class InteractionManagerVer {


    @Pattern
    public ActionResult interactItem(ClientPlayerInteractionManager interactionManager, PlayerEntity player, Hand hand) {
        //#if MC >= 11904
        return interactionManager.interactItem(player,hand);
        //#else
        //$$ return interactionManager.interactItem(player,MinecraftClient.getInstance().world,hand);
        //#endif
    }

    @Pattern
    public ActionResult interactBlock(ClientPlayerInteractionManager interactionManager, ClientPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        //#if MC >= 11904
        return interactionManager.interactBlock(player,hand, hitResult);
        //#else
        //$$ return interactionManager.interactBlock(player,MinecraftClient.getInstance().world, hand ,hitResult);
        //#endif
    }

}

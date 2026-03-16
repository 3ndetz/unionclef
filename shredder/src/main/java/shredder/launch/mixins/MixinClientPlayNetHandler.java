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

package shredder.launch.mixins;

import shredder.Baritone;
import shredder.api.BaritoneAPI;
import shredder.api.IBaritone;
import shredder.api.event.events.BlockChangeEvent;
import shredder.api.event.events.ChatEvent;
import shredder.api.event.events.ChunkEvent;
import shredder.api.event.events.type.EventState;
import shredder.api.utils.Pair;
import shredder.cache.CachedChunk;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Brady
 * @since 8/3/2018
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetHandler extends ClientCommonNetworkHandler {

    protected MixinClientPlayNetHandler(final MinecraftClient arg, final ClientConnection arg2, final ClientConnectionState arg3) {
        super(arg, arg2, arg3);
    }

    @Inject(
            method = "sendChatMessage(Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sendChatMessage(String string, CallbackInfo ci) {
        ChatEvent event = new ChatEvent(string);
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(this.client.player);
        if (baritone == null) {
            return;
        }
        baritone.getGameEventHandler().onSendChatMessage(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "onChunkData",
            at = @At("RETURN")
    )
    private void postHandleChunkData(ChunkDataS2CPacket packetIn, CallbackInfo ci) {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ClientPlayerEntity player = ibaritone.getPlayerContext().player();
            if (player != null && player.networkHandler == (ClientPlayNetworkHandler) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(
                                EventState.POST,
                                !packetIn.isWritingErrorSkippable() ? ChunkEvent.Type.POPULATE_FULL : ChunkEvent.Type.POPULATE_PARTIAL,
                                packetIn.getChunkX(),
                                packetIn.getChunkZ()
                        )
                );
            }
        }
    }

    @Inject(
            method = "onUnloadChunk",
            at = @At("HEAD")
    )
    private void preChunkUnload(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ClientPlayerEntity player = ibaritone.getPlayerContext().player();
            if (player != null && player.networkHandler == (ClientPlayNetworkHandler) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(EventState.PRE, ChunkEvent.Type.UNLOAD, packet.pos().x, packet.pos().z)
                );
            }
        }
    }

    @Inject(
            method = "onUnloadChunk",
            at = @At("RETURN")
    )
    private void postChunkUnload(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ClientPlayerEntity player = ibaritone.getPlayerContext().player();
            if (player != null && player.networkHandler == (ClientPlayNetworkHandler) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(EventState.POST, ChunkEvent.Type.UNLOAD, packet.pos().x, packet.pos().z)
                );
            }
        }
    }

    @Inject(
            method = "onBlockUpdate",
            at = @At("RETURN")
    )
    private void postHandleBlockChange(BlockUpdateS2CPacket packetIn, CallbackInfo ci) {
        if (!Baritone.settings().repackOnAnyBlockChange.value) {
            return;
        }
        if (!CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(packetIn.getState().getBlock())) {
            return;
        }
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ClientPlayerEntity player = ibaritone.getPlayerContext().player();
            if (player != null && player.networkHandler == (ClientPlayNetworkHandler) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(
                                EventState.POST,
                                ChunkEvent.Type.POPULATE_FULL,
                                packetIn.getPos().getX() >> 4,
                                packetIn.getPos().getZ() >> 4
                        )
                );
            }
        }
    }

    @Inject(
            method = "onChunkDeltaUpdate",
            at = @At("RETURN")
    )
    private void postHandleMultiBlockChange(ChunkDeltaUpdateS2CPacket packetIn, CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForConnection((ClientPlayNetworkHandler) (Object) this);
        if (baritone == null) {
            return;
        }

        List<Pair<BlockPos, BlockState>> changes = new ArrayList<>();
        packetIn.visitUpdates((mutPos, state) -> {
            changes.add(new Pair<>(mutPos.toImmutable(), state));
        });
        if (changes.isEmpty()) {
            return;
        }
        baritone.getGameEventHandler().onBlockChange(new BlockChangeEvent(
                new ChunkPos(changes.get(0).first()),
                changes
        ));
    }

    @Inject(
            method = "onDeathMessage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;showsDeathScreen()Z"
            )
    )
    private void onPlayerDeath(DeathMessageS2CPacket packetIn, CallbackInfo ci) {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            ClientPlayerEntity player = ibaritone.getPlayerContext().player();
            if (player != null && player.networkHandler == (ClientPlayNetworkHandler) (Object) this) {
                ibaritone.getGameEventHandler().onPlayerDeath();
            }
        }
    }
}

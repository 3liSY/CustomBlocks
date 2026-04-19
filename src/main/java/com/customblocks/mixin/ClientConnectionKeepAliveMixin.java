package com.customblocks.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts incoming KeepAlive packets on the Netty IO thread and
 * responds IMMEDIATELY — before the packet is queued for the main thread.
 * <p>
 * During a heavy resource pack reload (400+ textures, 100+ mods), the
 * client's main thread can be blocked for 1-3 minutes. Without this fix,
 * keepalive responses are delayed until the main thread processes the
 * packet queue, causing a server-side timeout disconnect.
 * <p>
 * This runs on the Netty IO thread, so it fires regardless of main
 * thread state. Combined with the server-side grace period Mixin,
 * this guarantees players from any country can join without being kicked.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionKeepAliveMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"))
    private void customblocks$earlyKeepAliveResponse(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof KeepAliveS2CPacket keepAlive) {
            // Send response on the IO thread — bypasses main thread queue entirely.
            ((ClientConnection) (Object) this).send(new KeepAliveC2SPacket(keepAlive.getId()));
        }
    }
}

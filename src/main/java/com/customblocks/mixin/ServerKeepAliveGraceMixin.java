package com.customblocks.mixin;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the "Alternative Keepalive" system from Purpur, adapted for
 * Fabric 1.21.1. This is the same battle-tested algorithm used by thousands
 * of Purpur/Paper servers worldwide.
 * <p>
 * <b>Vanilla behavior (broken for high-latency players):</b>
 * <ul>
 *   <li>Server sends 1 keepalive every 15 seconds</li>
 *   <li>If that single packet is lost or delayed → instant disconnect</li>
 * </ul>
 * <p>
 * <b>Alternative behavior (this Mixin):</b>
 * <ul>
 *   <li>Server sends 1 keepalive every second</li>
 *   <li>Only disconnects if NONE of the last 30 are answered (~30s total silence)</li>
 *   <li>If ANY single response arrives (in any order) → player stays connected</li>
 * </ul>
 * <p>
 * This does NOT affect normal players — they respond instantly and never
 * accumulate pending keepalives. It only helps players with packet loss
 * or temporary freezes (resource reload, GC pauses, etc.).
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerKeepAliveGraceMixin {

    @Shadow private boolean waitingForKeepAlive;
    @Shadow private int latency;

    @Shadow public abstract void sendPacket(Packet<?> packet);

    @Unique private final List<Long> customblocks$pendingKeepAlives = new ArrayList<>();
    @Unique private long customblocks$altLastTime;

    @Inject(method = "baseTick", at = @At("HEAD"))
    private void customblocks$alternativeKeepalive(CallbackInfo ci) {
        // Only run during PLAY phase — during configuration→play transition the
        // Netty pipeline briefly has no outbound encoder. Sending any packet in
        // that window causes EncoderException and kicks the player.
        if (!((Object) this instanceof net.minecraft.server.network.ServerPlayNetworkHandler)) return;

        // Always clear vanilla's flag so it never triggers disconnect.
        // Vanilla keepalive still sends every 15s (harmless), but our
        // alternative system handles the actual timeout logic.
        this.waitingForKeepAlive = false;

        long now = Util.getMeasuringTimeMs();
        if (now - customblocks$altLastTime >= 1000L) {
            if (customblocks$pendingKeepAlives.size() > 30) {
                // 30+ unanswered keepalives = 30+ seconds of total silence.
                // This is a genuine lost connection, not a temporary freeze.
                ((ServerCommonNetworkHandler) (Object) this)
                        .disconnect(Text.translatable("disconnect.timeout"));
            } else {
                customblocks$altLastTime = now;
                customblocks$pendingKeepAlives.add(now);
                sendPacket(new KeepAliveS2CPacket(now));
            }
        }
    }

    @Inject(method = "onKeepAlive", at = @At("HEAD"), cancellable = true)
    private void customblocks$handleKeepAliveResponse(KeepAliveC2SPacket packet, CallbackInfo ci) {
        long id = packet.getId();
        if (customblocks$pendingKeepAlives.contains(id)) {
            int ping = (int) (Util.getMeasuringTimeMs() - id);
            this.latency = (this.latency * 3 + ping) / 4;
            customblocks$pendingKeepAlives.clear(); // got a response — all good
        }
        // Cancel vanilla handler to prevent "unexpected query response" disconnect.
        // Without this, vanilla sees our keepalive responses as unexpected
        // (because waitingForKeepAlive is always false) and kicks the player.
        ci.cancel();
    }
}

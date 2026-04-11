package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * §1 — Sent server→client when the initial texture-sync queue for a joining player
 * hits zero. Replaces the arbitrary 4 000 ms debounce guessing logic that caused
 * KeepAlive packets to be missed on the Netty thread → SocketException: Connection reset.
 */
public record SyncCompletePayload() implements CustomPayload {

    public static final Id<SyncCompletePayload> ID =
            new Id<>(Identifier.of("customblocks", "sync_complete"));

    public static final PacketCodec<PacketByteBuf, SyncCompletePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> { /* nothing to write */ },
                    buf -> new SyncCompletePayload()
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: "I'm ready for the full sync."
 * Sent by the client once its play connection is fully established.
 * This guarantees the Netty pipeline is configured for S2C responses.
 * Zero data fields — this is a marker packet only.
 */
public record SyncRequestPayload() implements CustomPayload {

    public static final Id<SyncRequestPayload> ID =
            new Id<>(Identifier.of("customblocks", "sync_request"));

    public static final PacketCodec<PacketByteBuf, SyncRequestPayload> CODEC = PacketCodec.of(
            (value, buf) -> { /* no fields */ },
            buf -> new SyncRequestPayload()
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

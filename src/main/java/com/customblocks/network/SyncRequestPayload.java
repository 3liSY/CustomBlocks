package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: "I'm ready for the full sync."
 * Sent by the client once its play connection is fully established.
 * This guarantees the Netty pipeline is configured for S2C responses.
 *
 * Phase 3: includes the client's cached texture hash so the server can skip
 * the drip-feed if the client already has all textures. Empty string = no cache.
 */
public record SyncRequestPayload(String textureHash) implements CustomPayload {

    /** Backward-compat: no hash. */
    public SyncRequestPayload() { this(""); }

    public static final Id<SyncRequestPayload> ID =
            new Id<>(Identifier.of("customblocks", "sync_request"));

    public static final PacketCodec<PacketByteBuf, SyncRequestPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeString(value.textureHash() != null ? value.textureHash() : ""),
            buf -> new SyncRequestPayload(buf.readableBytes() > 0 ? buf.readString() : "")
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

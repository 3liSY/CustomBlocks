package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: "I'm ready for the full sync."
 * Sent by the client once its play connection is fully established.
 *
 * Phase 3: includes the client's cached global texture hash.
 * N3:      also includes per-slot CRC32 hashes as a compact JSON string
 *          {@code {"0":"a1b2c3d4","5":"deadbeef",...}} so the server can
 *          diff and send only the slots the client is missing or stale on.
 *          Empty string = no per-slot cache (force full drip-feed).
 */
public record SyncRequestPayload(String textureHash, String slotHashesJson) implements CustomPayload {

    /** Backward-compat constructor for code that only has the global hash. */
    public SyncRequestPayload(String textureHash) {
        this(textureHash, "");
    }

    public static final Id<SyncRequestPayload> ID =
            new Id<>(Identifier.of("customblocks", "sync_request"));

    public static final PacketCodec<PacketByteBuf, SyncRequestPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.textureHash() != null ? value.textureHash() : "");
                buf.writeByteArray((value.slotHashesJson() != null ? value.slotHashesJson() : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            },
            buf -> {
                String hash  = buf.readableBytes() > 0 ? buf.readString() : "";
                String slots = buf.readableBytes() > 0 ? new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8) : "";
                return new SyncRequestPayload(hash, slots);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

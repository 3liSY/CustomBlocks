package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client: All drip-feed texture payloads have been enqueued.
 * Carries the exact count of texture payloads sent and a SHA-256 manifest
 * (slotId → SHA-256 hex) so the client can verify completeness and
 * cache integrity on subsequent joins.
 *
 * Replaces the "sync_done" action piggy-backed onto {@link SlotUpdatePayload}.
 */
public record SyncCompletePayload(int payloadCount, String manifestJson, String serverHash)
        implements CustomPayload {

    public static final Id<SyncCompletePayload> ID =
            new Id<>(Identifier.of("customblocks", "sync_complete"));

    public static final PacketCodec<PacketByteBuf, SyncCompletePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.payloadCount());
                buf.writeByteArray((value.manifestJson() != null ? value.manifestJson() : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                buf.writeByteArray((value.serverHash()   != null ? value.serverHash()   : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            },
            buf -> {
                int count   = buf.readVarInt();
                String mani = new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8);
                String hash = buf.readableBytes() > 0 ? new String(buf.readByteArray(65_536), java.nio.charset.StandardCharsets.UTF_8) : "";
                return new SyncCompletePayload(count, mani, hash);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

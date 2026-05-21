package com.customblocks.network;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client chunked texture transfer.
 * <p>
 * When a texture exceeds the chunking threshold (500 KB), the server splits it
 * into multiple {@code ChunkedTexturePayload} packets. The client buffers them
 * by {@code transferId} and reassembles the full byte array once all chunks
 * have arrived, then processes the result through the normal
 * {@link SlotUpdatePayload} handler.
 * <p>
 * Metadata fields (action, customId, etc.) are only meaningful on chunk 0;
 * subsequent chunks carry empty/default metadata to save bandwidth.
 */
@SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
public record ChunkedTexturePayload(
        String transferId,    // unique UUID per transfer
        int    chunkIndex,    // 0-based piece index
        int    totalChunks,   // total number of pieces
        byte[] chunkData,     // raw bytes for this piece
        // ── Metadata (only populated on chunk 0) ────────────────────────────
        String action,
        int    slotIndex,
        String customId,
        String displayName,
        int    lightLevel,
        float  hardness,
        String soundType,
        String face,
        String animMeta
) implements CustomPayload {

    public static final Id<ChunkedTexturePayload> ID =
            new Id<>(Identifier.of("customblocks", "chunked_texture"));

    public static final PacketCodec<PacketByteBuf, ChunkedTexturePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.transferId());
                buf.writeVarInt(value.chunkIndex());
                buf.writeVarInt(value.totalChunks());
                buf.writeByteArray(value.chunkData());
                // Metadata
                buf.writeString(value.action()      != null ? value.action()      : "");
                buf.writeVarInt(value.slotIndex());
                buf.writeString(value.customId()     != null ? value.customId()     : "");
                buf.writeString(value.displayName()  != null ? value.displayName()  : "");
                buf.writeVarInt(value.lightLevel());
                buf.writeFloat(value.hardness());
                buf.writeString(value.soundType()    != null ? value.soundType()    : "stone");
                buf.writeString(value.face()         != null ? value.face()         : "");
                buf.writeString(value.animMeta()     != null ? value.animMeta()     : "");
            },
            buf -> {
                String transferId  = buf.readString();
                int    chunkIndex  = buf.readVarInt();
                int    totalChunks = buf.readVarInt();
                byte[] chunkData   = buf.readByteArray(1_048_576); // max 1 MB per chunk read
                // Metadata
                String action      = buf.readString();
                int    slotIndex   = buf.readVarInt();
                String customId    = buf.readString();
                String displayName = buf.readString();
                int    lightLevel  = buf.readVarInt();
                float  hardness    = buf.readFloat();
                String soundType   = buf.readString();
                String face        = buf.readString();
                String animMeta    = buf.readString();
                return new ChunkedTexturePayload(
                        transferId, chunkIndex, totalChunks, chunkData,
                        action.isEmpty()      ? null : action,
                        slotIndex,
                        customId.isEmpty()    ? null : customId,
                        displayName.isEmpty() ? null : displayName,
                        lightLevel, hardness,
                        soundType.isEmpty()   ? "stone" : soundType,
                        face.isEmpty()        ? null : face,
                        animMeta.isEmpty()    ? null : animMeta
                );
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

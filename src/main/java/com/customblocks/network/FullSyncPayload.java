package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client on join. Metadata + animMeta per slot.
 * <p>
 * Textures are NOT included — they are drip-fed via {@link SlotUpdatePayload} after the sync.
 * This keeps the join payload small (< 100KB even with 2048 slots).
 */
public record FullSyncPayload(List<SlotEntry> entries, byte[] tabIconTexture, int maxSlots) implements CustomPayload {

    /** Legacy constructor without maxSlots (backward compat). */
    public FullSyncPayload(List<SlotEntry> entries, byte[] tabIconTexture) {
        this(entries, tabIconTexture, 0);
    }

    public static final Id<FullSyncPayload> ID =
            new Id<>(Identifier.of("customblocks", "full_sync"));

    public record SlotEntry(
            int    index,
            String customId,
            String displayName,
            byte[] texture,
            int    lightLevel,
            float  hardness,
            String soundType,
            String animMeta
    ) {
        /** Legacy constructor without animMeta. */
        public SlotEntry(int index, String customId, String displayName, byte[] texture,
                         int lightLevel, float hardness, String soundType) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, null);
        }
    }

    public static final PacketCodec<PacketByteBuf, FullSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.entries().size());
                for (SlotEntry e : value.entries()) {
                    buf.writeVarInt(e.index());
                    buf.writeString(e.customId());
                    buf.writeString(e.displayName());
                    buf.writeByteArray(e.texture() != null ? e.texture() : new byte[0]);
                    buf.writeVarInt(e.lightLevel());
                    buf.writeFloat(e.hardness());
                    buf.writeString(e.soundType() != null ? e.soundType() : "stone");
                    buf.writeString(e.animMeta()  != null ? e.animMeta()  : "");
                }
                buf.writeByteArray(value.tabIconTexture() != null ? value.tabIconTexture() : new byte[0]);
                buf.writeVarInt(value.maxSlots());
            },
            buf -> {
                int size = buf.readVarInt();
                List<SlotEntry> entries = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    int    index       = buf.readVarInt();
                    String id          = buf.readString();
                    String name        = buf.readString();
                    byte[] tex         = buf.readByteArray(10_485_760);
                    int    lightLevel  = buf.readVarInt();
                    float  hardness    = buf.readFloat();
                    String soundType   = buf.readString();
                    String animMeta    = buf.readableBytes() > 0 ? buf.readString() : "";
                    entries.add(new SlotEntry(index, id, name,
                            tex.length > 0 ? tex : null, lightLevel, hardness, soundType,
                            animMeta.isEmpty() ? null : animMeta));
                }
                byte[] tabIcon = buf.readByteArray(10_485_760);
                int maxSlots = buf.readableBytes() > 0 ? buf.readVarInt() : 0;
                if (buf.readableBytes() > 0) buf.skipBytes(buf.readableBytes());
                return new FullSyncPayload(entries, tabIcon.length > 0 ? tabIcon : null, maxSlots);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server → Client on join. Metadata only (textures come via SlotUpdatePayload). */
public record FullSyncPayload(List<SlotEntry> entries, byte[] tabIconTexture) implements CustomPayload {

    public static final Id<FullSyncPayload> ID =
            new Id<>(Identifier.of("customblocks", "full_sync"));

    public record SlotEntry(
            int    index,
            String customId,
            String displayName,
            byte[] texture,
            int    lightLevel,
            float  hardness,
            String soundType
    ) {}

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
                }
                buf.writeByteArray(value.tabIconTexture() != null ? value.tabIconTexture() : new byte[0]);
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
                    entries.add(new SlotEntry(index, id, name,
                            tex.length > 0 ? tex : null, lightLevel, hardness, soundType));
                }
                byte[] tabIcon = buf.readByteArray(10_485_760);
                if (buf.readableBytes() > 0) buf.skipBytes(buf.readableBytes());
                return new FullSyncPayload(entries, tabIcon.length > 0 ? tabIcon : null);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

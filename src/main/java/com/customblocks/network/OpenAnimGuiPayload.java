package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server → Client: tells the client to open the animation settings screen for a block. */
public record OpenAnimGuiPayload(
        String customId,
        String displayName,
        String animMeta,
        int    frameCount
) implements CustomPayload {

    public static final Id<OpenAnimGuiPayload> ID =
            new Id<>(Identifier.of("customblocks", "open_anim_gui"));

    public static final PacketCodec<PacketByteBuf, OpenAnimGuiPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.customId()    != null ? value.customId()    : "");
                buf.writeString(value.displayName() != null ? value.displayName() : "");
                buf.writeByteArray((value.animMeta() != null ? value.animMeta() : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                buf.writeVarInt(value.frameCount());
            },
            buf -> {
                String customId    = buf.readString();
                String displayName = buf.readString();
                String animMeta    = new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8);
                int    frameCount  = buf.readVarInt();
                return new OpenAnimGuiPayload(customId, displayName, animMeta, frameCount);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

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
                buf.writeString(value.animMeta()    != null ? value.animMeta()    : "");
                buf.writeVarInt(value.frameCount());
            },
            buf -> new OpenAnimGuiPayload(buf.readString(), buf.readString(), buf.readString(), buf.readVarInt())
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

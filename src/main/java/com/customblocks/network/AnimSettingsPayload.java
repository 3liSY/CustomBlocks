package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client → Server: player changed animation settings for a custom block. */
public record AnimSettingsPayload(
        String customId,
        String animMeta   // full rebuilt mcmeta JSON
) implements CustomPayload {

    public static final Id<AnimSettingsPayload> ID =
            new Id<>(Identifier.of("customblocks", "anim_settings"));

    public static final PacketCodec<PacketByteBuf, AnimSettingsPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.customId() != null ? value.customId() : "");
                buf.writeString(value.animMeta()  != null ? value.animMeta()  : "");
            },
            buf -> new AnimSettingsPayload(buf.readString(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

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
                buf.writeByteArray((value.animMeta() != null ? value.animMeta() : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            },
            buf -> {
                String customId = buf.readString();
                String animMeta = new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8);
                return new AnimSettingsPayload(customId, animMeta);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

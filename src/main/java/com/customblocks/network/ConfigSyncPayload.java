package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client: broadcasts updated triangle/square hex colour values so the
 * client can regenerate its local resource pack and update item textures + names
 * without reconnecting.
 */
public record ConfigSyncPayload(
        String blackHex,
        String yellowHex,
        String greenHex,
        String redHex
) implements CustomPayload {

    public static final Id<ConfigSyncPayload> ID =
            new Id<>(Identifier.of("customblocks", "config_sync"));

    public static final PacketCodec<PacketByteBuf, ConfigSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.blackHex()  != null ? value.blackHex()  : "");
                buf.writeString(value.yellowHex() != null ? value.yellowHex() : "");
                buf.writeString(value.greenHex()  != null ? value.greenHex()  : "");
                buf.writeString(value.redHex()    != null ? value.redHex()    : "");
            },
            buf -> new ConfigSyncPayload(
                    buf.readString(),
                    buf.readString(),
                    buf.readString(),
                    buf.readString()
            )
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

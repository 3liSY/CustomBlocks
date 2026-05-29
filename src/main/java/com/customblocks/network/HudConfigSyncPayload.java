package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent C2S by server owner to broadcast HUD config to all players.
 * Also sent S2C to apply the server-wide config to a joining or online player.
 */
public record HudConfigSyncPayload(String configJson) implements CustomPayload {

    public static final Id<HudConfigSyncPayload> ID =
            new Id<>(Identifier.of("customblocks", "hud_config_sync"));

    public static final PacketCodec<PacketByteBuf, HudConfigSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeString(value.configJson(), 65536),
            buf -> new HudConfigSyncPayload(buf.readString(65536))
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server → Client: tells the client to pause or resume resource pack reloads. */
public record RpPausePayload(boolean paused) implements CustomPayload {

    public static final Id<RpPausePayload> ID =
            new Id<>(Identifier.of("customblocks", "rp_pause"));

    public static final PacketCodec<PacketByteBuf, RpPausePayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeBoolean(value.paused()),
            buf -> new RpPausePayload(buf.readBoolean())
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

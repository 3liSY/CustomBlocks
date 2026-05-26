package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record OpenHudEditorPayload() implements CustomPayload {

    public static final Id<OpenHudEditorPayload> ID =
            new Id<>(Identifier.of("customblocks", "open_hud_editor"));

    public static final PacketCodec<PacketByteBuf, OpenHudEditorPayload> CODEC = PacketCodec.of(
            (value, buf) -> {},
            buf -> new OpenHudEditorPayload()
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

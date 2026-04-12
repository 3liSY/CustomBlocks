package com.customblocks.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Payload sent by the server to inform the client that texture synchronization is complete.
 * This explicitly replaces the arbitrary 4000ms debounce loop.
 */
public record SyncCompletePayload() implements CustomPayload {

    public static final CustomPayload.Id<SyncCompletePayload> ID = new CustomPayload.Id<>(Identifier.of("customblocks", "sync_complete"));

    public static final PacketCodec<RegistryByteBuf, SyncCompletePayload> CODEC = PacketCodec.unit(new SyncCompletePayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

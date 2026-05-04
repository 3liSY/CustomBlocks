package com.customblocks.network;

import com.customblocks.CustomBlocksMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: requests cycling the Golden Hexagon indicator mode.
 * Sent when the player presses Ctrl+Shift while holding the Hexagon.
 */
public record CycleHexagonModePayload() implements CustomPayload {

    public static final CustomPayload.Id<CycleHexagonModePayload> ID =
            new CustomPayload.Id<>(Identifier.of(CustomBlocksMod.MOD_ID, "cycle_hex_mode"));

    public static final PacketCodec<RegistryByteBuf, CycleHexagonModePayload> CODEC =
            PacketCodec.unit(new CycleHexagonModePayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

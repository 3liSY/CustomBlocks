package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client update for a single slot.
 * <p>
 * Actions: add, retexture, remove, rename, setprop, setface, clearface,
 * clearfaces, setshape, setcollision, tabicon, animsettings
 */
public record SlotUpdatePayload(
        String action,
        int    slotIndex,
        String customId,
        String displayName,
        byte[] texture,
        int    lightLevel,
        float  hardness,
        String soundType,
        String face,
        String shapeData,
        String animMeta,
        String facesJson,
        /** H4 — JSON array of base64-encoded variant textures, or null/empty if none. */
        String variantsJson
) implements CustomPayload {

    /** No face, no shapeData, no animMeta, no facesJson, no variantsJson. */
    public SlotUpdatePayload(String action, int slotIndex, String customId,
                              String displayName, byte[] texture,
                              int lightLevel, float hardness, String soundType) {
        this(action, slotIndex, customId, displayName, texture,
             lightLevel, hardness, soundType, null, null, null, null, null);
    }

    /** With face, no shapeData, no animMeta, no facesJson, no variantsJson. */
    public SlotUpdatePayload(String action, int slotIndex, String customId,
                              String displayName, byte[] texture,
                              int lightLevel, float hardness, String soundType, String face) {
        this(action, slotIndex, customId, displayName, texture,
             lightLevel, hardness, soundType, face, null, null, null, null);
    }

    /** With face and shapeData, no animMeta, no facesJson, no variantsJson. */
    public SlotUpdatePayload(String action, int slotIndex, String customId,
                              String displayName, byte[] texture,
                              int lightLevel, float hardness, String soundType,
                              String face, String shapeData) {
        this(action, slotIndex, customId, displayName, texture,
             lightLevel, hardness, soundType, face, shapeData, null, null, null);
    }

    /** With face, shapeData, and animMeta; no facesJson, no variantsJson. */
    public SlotUpdatePayload(String action, int slotIndex, String customId,
                              String displayName, byte[] texture,
                              int lightLevel, float hardness, String soundType,
                              String face, String shapeData, String animMeta) {
        this(action, slotIndex, customId, displayName, texture,
             lightLevel, hardness, soundType, face, shapeData, animMeta, null, null);
    }

    /** With face, shapeData, animMeta and facesJson; no variantsJson. */
    public SlotUpdatePayload(String action, int slotIndex, String customId,
                              String displayName, byte[] texture,
                              int lightLevel, float hardness, String soundType,
                              String face, String shapeData, String animMeta, String facesJson) {
        this(action, slotIndex, customId, displayName, texture,
             lightLevel, hardness, soundType, face, shapeData, animMeta, facesJson, null);
    }

    public static final Id<SlotUpdatePayload> ID =
            new Id<>(Identifier.of("customblocks", "slot_update"));

    public static final PacketCodec<PacketByteBuf, SlotUpdatePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.action());
                buf.writeVarInt(value.slotIndex());
                buf.writeString(value.customId()      != null ? value.customId()      : "");
                buf.writeString(value.displayName()   != null ? value.displayName()   : "");
                buf.writeByteArray(value.texture()    != null ? value.texture()       : new byte[0]);
                buf.writeVarInt(value.lightLevel());
                buf.writeFloat(value.hardness());
                buf.writeString(value.soundType()     != null ? value.soundType()     : "stone");
                buf.writeString(value.face()          != null ? value.face()          : "");
                buf.writeString(value.shapeData()     != null ? value.shapeData()     : "");
                buf.writeString(value.animMeta()      != null ? value.animMeta()      : "");
                buf.writeString(value.facesJson()     != null ? value.facesJson()     : "");
                buf.writeString(value.variantsJson()  != null ? value.variantsJson()  : "");
            },
            buf -> {
                String action       = buf.readString();
                int    index        = buf.readVarInt();
                String id           = buf.readString();
                String name         = buf.readString();
                byte[] tex          = buf.readByteArray(10_485_760);
                int    lightLevel   = buf.readVarInt();
                float  hardness     = buf.readFloat();
                String soundType    = buf.readString();
                String face         = buf.readableBytes() > 0 ? buf.readString() : "";
                String shapeData    = buf.readableBytes() > 0 ? buf.readString() : "";
                String animMeta     = buf.readableBytes() > 0 ? buf.readString() : "";
                String facesJson    = buf.readableBytes() > 0 ? buf.readString() : "";
                String variantsJson = buf.readableBytes() > 0 ? buf.readString() : "";
                return new SlotUpdatePayload(
                        action, index,
                        id.isEmpty()           ? null : id,
                        name.isEmpty()         ? null : name,
                        tex.length > 0         ? tex  : null,
                        lightLevel, hardness,
                        soundType.isEmpty()    ? "stone" : soundType,
                        face.isEmpty()         ? null : face,
                        shapeData.isEmpty()    ? null : shapeData,
                        animMeta.isEmpty()     ? null : animMeta,
                        facesJson.isEmpty()    ? null : facesJson,
                        variantsJson.isEmpty() ? null : variantsJson
                );
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

package com.customblocks.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SlotUpdatePayload(String action, int slotIndex, String customId, String displayName, byte[] texture, int lightLevel, float hardness, String soundType) implements CustomPayload {
   public static final CustomPayload.Id<SlotUpdatePayload> ID = new CustomPayload.Id(Identifier.of("customblocks", "slot_update"));
   public static final PacketCodec<PacketByteBuf, SlotUpdatePayload> CODEC = PacketCodec.of((value, buf) -> {
      buf.writeString(value.action());
      buf.writeInt(value.slotIndex());
      buf.writeString(value.customId() != null ? value.customId() : "");
      buf.writeString(value.displayName() != null ? value.displayName() : "");
      buf.writeByteArray(value.texture() != null ? value.texture() : new byte[0]);
      buf.writeInt(value.lightLevel());
      buf.writeFloat(value.hardness());
      buf.writeString(value.soundType() != null ? value.soundType() : "stone");
   }, (buf) -> {
      String action = buf.readString();
      int index = buf.readInt();
      String id = buf.readString();
      String name = buf.readString();
      byte[] tex = buf.readByteArray(10485760);
      int lightLevel = buf.readInt();
      float hardness = buf.readFloat();
      String soundType = buf.readString();
      if (buf.readableBytes() > 0) {
         buf.skipBytes(buf.readableBytes());
      }

      return new SlotUpdatePayload(action, index, id.isEmpty() ? null : id, name.isEmpty() ? null : name, tex.length > 0 ? tex : null, lightLevel, hardness, soundType.isEmpty() ? "stone" : soundType);
   });

   public SlotUpdatePayload(String action, int slotIndex, String customId, String displayName, byte[] texture, int lightLevel, float hardness, String soundType) {
      this.action = action;
      this.slotIndex = slotIndex;
      this.customId = customId;
      this.displayName = displayName;
      this.texture = texture;
      this.lightLevel = lightLevel;
      this.hardness = hardness;
      this.soundType = soundType;
   }

   public CustomPayload.Id<? extends CustomPayload> getId() {
      return ID;
   }

   public String action() {
      return this.action;
   }

   public int slotIndex() {
      return this.slotIndex;
   }

   public String customId() {
      return this.customId;
   }

   public String displayName() {
      return this.displayName;
   }

   public byte[] texture() {
      return this.texture;
   }

   public int lightLevel() {
      return this.lightLevel;
   }

   public float hardness() {
      return this.hardness;
   }

   public String soundType() {
      return this.soundType;
   }
}

package com.customblocks.block;

import com.customblocks.SlotManager;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockView;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class SlotBlock extends Block {
   private final int slotIndex;

   public SlotBlock(int slotIndex, AbstractBlock.Settings settings) {
      super(settings);
      this.slotIndex = slotIndex;
   }

   public int getSlotIndex() {
      return this.slotIndex;
   }

   public String getSlotKey() {
      return "slot_" + this.slotIndex;
   }

   public MutableText getName() {
      String name = SlotManager.getDisplayName(this.getSlotKey());
      return Text.literal(name != null ? name : "Custom Block " + this.slotIndex);
   }

   public BlockSoundGroup getSoundGroup(BlockState state) {
      SlotManager.SlotData d = SlotManager.getBySlot(this.getSlotKey());
      if (d == null) {
         return BlockSoundGroup.STONE;
      } else {
         String var3 = d.soundType;
         byte var4 = -1;
         switch(var3.hashCode()) {
         case 3522692:
            if (var3.equals("sand")) {
               var4 = 4;
            }
            break;
         case 3655341:
            if (var3.equals("wood")) {
               var4 = 0;
            }
            break;
         case 3655349:
            if (var3.equals("wool")) {
               var4 = 5;
            }
            break;
         case 98436988:
            if (var3.equals("glass")) {
               var4 = 3;
            }
            break;
         case 98615734:
            if (var3.equals("grass")) {
               var4 = 1;
            }
            break;
         case 103787271:
            if (var3.equals("metal")) {
               var4 = 2;
            }
         }

         BlockSoundGroup var10000;
         switch(var4) {
         case 0:
            var10000 = BlockSoundGroup.WOOD;
            break;
         case 1:
            var10000 = BlockSoundGroup.GRASS;
            break;
         case 2:
            var10000 = BlockSoundGroup.METAL;
            break;
         case 3:
            var10000 = BlockSoundGroup.GLASS;
            break;
         case 4:
            var10000 = BlockSoundGroup.SAND;
            break;
         case 5:
            var10000 = BlockSoundGroup.WOOL;
            break;
         default:
            var10000 = BlockSoundGroup.STONE;
         }

         return var10000;
      }
   }

   public float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
      SlotManager.SlotData d = SlotManager.getBySlot(this.getSlotKey());
      float hardness = d != null ? d.hardness : 1.5F;
      if (hardness < 0.0F) {
         return 0.0F;
      } else if (hardness == 0.0F) {
         return 1.0F;
      } else {
         float speed = player.getBlockBreakingSpeed(state);
         boolean correctTool = speed > 1.0F;
         return correctTool ? speed / hardness / 30.0F : 1.0F / hardness / 100.0F;
      }
   }

   public static class SlotItem extends BlockItem {
      private final int slotIndex;

      public SlotItem(SlotBlock block, Item.Settings settings) {
         super(block, settings);
         this.slotIndex = block.getSlotIndex();
      }

      private String getSlotKey() {
         return "slot_" + this.slotIndex;
      }

      public Text getName() {
         String name = SlotManager.getDisplayName(this.getSlotKey());
         return Text.literal(name != null ? name : "Custom Block");
      }

      public Text getName(ItemStack stack) {
         return this.getName();
      }
   }
}

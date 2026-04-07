package com.customblocks;

import com.customblocks.block.SlotBlock;
import com.customblocks.command.CustomBlockCommand;
import com.customblocks.item.ColorSquareItem;
import com.customblocks.network.FullSyncPayload;
import com.customblocks.network.SlotUpdatePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomBlocksMod implements ModInitializer {
   public static final String MOD_ID = "customblocks";
   public static final Logger LOGGER = LoggerFactory.getLogger("customblocks");
   public static final SlotBlock[] SLOT_BLOCKS = new SlotBlock[512];
   public static final SlotBlock.SlotItem[] SLOT_ITEMS = new SlotBlock.SlotItem[512];
   private static final Map<UUID, ConcurrentLinkedQueue<SlotUpdatePayload>> PENDING_TEXTURES = new ConcurrentHashMap();
   private static final Map<UUID, Integer> SEND_DELAY = new ConcurrentHashMap();
   private static final int DELAY_TICKS = 60;
   private static final int BATCH_SIZE = 4;
   public static final RegistryKey<ItemGroup> CUSTOM_BLOCKS_TAB;

   public void onInitialize() {
      for(int i = 0; i < 512; ++i) {
         AbstractBlock.Settings settings = AbstractBlock.Settings.create().strength(1.5F, 6.0F).luminance((state) -> {
            SlotManager.SlotData d = SlotManager.getBySlot("slot_" + i);
            return d != null ? d.lightLevel : 0;
         });
         SlotBlock block = new SlotBlock(i, settings);
         Identifier id = Identifier.of("customblocks", "slot_" + i);
         SlotBlock.SlotItem item = new SlotBlock.SlotItem(block, new Item.Settings());
         Registry.register(Registries.BLOCK, id, block);
         Registry.register(Registries.ITEM, id, item);
         SLOT_BLOCKS[i] = block;
         SLOT_ITEMS[i] = item;
      }

      String[][] squares = new String[][]{{"black", "Black"}, {"yellow", "Yellow"}, {"green", "Green"}};
      String[][] var2 = squares;
      int var9 = squares.length;

      for(int var10 = 0; var10 < var9; ++var10) {
         String[] sq = var2[var10];
         Identifier sqId = Identifier.of("customblocks", sq[0] + "_square");
         ColorSquareItem sqItem = new ColorSquareItem(sq[0], sq[1], (new Item.Settings()).maxCount(1));
         Registry.register(Registries.ITEM, sqId, sqItem);
      }

      PayloadTypeRegistry.playS2C().register(FullSyncPayload.ID, FullSyncPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(SlotUpdatePayload.ID, SlotUpdatePayload.CODEC);
      Registry.register(Registries.ITEM_GROUP, CUSTOM_BLOCKS_TAB, FabricItemGroup.builder().displayName(Text.literal("Custom Blocks")).icon(() -> {
         SlotManager.SlotData icon = SlotManager.getById("tab_icon");
         if (icon != null) {
            return new ItemStack(SLOT_ITEMS[icon.index]);
         } else {
            Iterator var1 = SlotManager.allSlots().iterator();

            SlotManager.SlotData d;
            do {
               if (!var1.hasNext()) {
                  return new ItemStack(Items.STONE);
               }

               d = (SlotManager.SlotData)var1.next();
            } while(d.customId.equals("tab_icon"));

            return new ItemStack(SLOT_ITEMS[d.index]);
         }

package com.customblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;

public class CustomBlocksMod implements ModInitializer {
    public static final ItemGroup CUSTOM_BLOCKS_TAB;

    static {
        CUSTOM_BLOCKS_TAB = FabricItemGroupBuilder.build(
            new Identifier("customblocks", "custom_blocks_tab"),
            () -> new ItemStack(Blocks.DIAMOND_BLOCK)
        );
    }

    @Override
    public void onInitialize() {
        // Initialization logic
    }
}
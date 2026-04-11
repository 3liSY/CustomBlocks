package com.customblocks.block;

import com.customblocks.SlotManager;
import com.customblocks.network.OpenAnimGuiPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class SlotBlock extends Block {

    private final int slotIndex;

    public SlotBlock(int slotIndex, Settings settings) {
        super(settings);
        this.slotIndex = slotIndex;
    }

    public int    getSlotIndex() { return slotIndex; }
    public String getSlotKey()   { return "slot_" + slotIndex; }

    @Override
    public MutableText getName() {
        String name = SlotManager.getDisplayName(getSlotKey());
        return Text.literal(name != null ? name : "Custom Block " + slotIndex);
    }

    /**
     * Right-click an animated block → open the Animation Settings GUI.
     * Only triggers on animated blocks (GIF / APNG).
     * Returns PASS for non-animated blocks so normal item use still works.
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        SlotManager.SlotData data = SlotManager.getBySlot(getSlotKey());
        if (data == null || !data.isAnimated()) return ActionResult.PASS;

        if (player instanceof ServerPlayerEntity sp) {
            // Count frames from the animMeta
            int frames = countFrames(data.animMeta);
            ServerPlayNetworking.send(sp, new OpenAnimGuiPayload(
                    data.customId,
                    data.displayName,
                    data.animMeta,
                    frames
            ));
        }
        return ActionResult.SUCCESS;
    }

    /** Parse frame count from animMeta JSON without full Gson dependency. */
    private static int countFrames(String animMeta) {
        if (animMeta == null) return 0;
        // Quick count of "index": occurrences
        int count = 0;
        int idx = 0;
        while ((idx = animMeta.indexOf("\"index\"", idx)) != -1) { count++; idx += 7; }
        return count > 0 ? count : 1;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        VoxelShape s = SlotManager.buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        SlotManager.SlotData d = SlotManager.getBySlot(getSlotKey());
        if (d != null && d.noCollision) return VoxelShapes.empty();
        VoxelShape s = SlotManager.buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        VoxelShape s = SlotManager.buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public BlockSoundGroup getSoundGroup(BlockState state) {
        SlotManager.SlotData d = SlotManager.getBySlot(getSlotKey());
        if (d == null) return BlockSoundGroup.STONE;
        return switch (d.soundType) {
            case "wood"         -> BlockSoundGroup.WOOD;
            case "grass"        -> BlockSoundGroup.GRASS;
            case "metal"        -> BlockSoundGroup.METAL;
            case "glass"        -> BlockSoundGroup.GLASS;
            case "sand"         -> BlockSoundGroup.SAND;
            case "wool"         -> BlockSoundGroup.WOOL;
            case "gravel"       -> BlockSoundGroup.GRAVEL;
            case "snow"         -> BlockSoundGroup.SNOW;
            case "dirt"         -> BlockSoundGroup.ROOTED_DIRT;
            case "coral"        -> BlockSoundGroup.WET_GRASS;
            case "bamboo"       -> BlockSoundGroup.BAMBOO;
            case "nether_brick" -> BlockSoundGroup.NETHER_BRICKS;
            case "ice"          -> BlockSoundGroup.GLASS;
            case "honey"        -> BlockSoundGroup.HONEY;
            case "bone"         -> BlockSoundGroup.BONE;
            case "slime"        -> BlockSoundGroup.SLIME;
            default             -> BlockSoundGroup.STONE;
        };
    }

    @Override
    public float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
        SlotManager.SlotData d = SlotManager.getBySlot(getSlotKey());
        float hardness = d != null ? d.hardness : 1.5f;
        if (hardness < 0) return 0f;
        if (hardness == 0) return 1f;
        float speed = player.getBlockBreakingSpeed(state);
        boolean correctTool = speed > 1.0f;
        return correctTool ? speed / hardness / 30f : 1f / hardness / 100f;
    }

    public static class SlotItem extends BlockItem {
        private final int slotIndex;

        public SlotItem(SlotBlock block, Item.Settings settings) {
            super(block, settings);
            this.slotIndex = block.getSlotIndex();
        }

        private String getSlotKey() { return "slot_" + slotIndex; }

        @Override
        public Text getName() {
            String name = SlotManager.getDisplayName(getSlotKey());
            return Text.literal(name != null ? name : "Custom Block");
        }

        @Override
        public Text getName(ItemStack stack) { return getName(); }
    }
}

package com.customblocks.block;

import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.gui.GuiManager;
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
        super(settings.nonOpaque());
        this.slotIndex = slotIndex;
    }

    public int    getSlotIndex() { return slotIndex; }
    public String getSlotKey()   { return "slot_" + slotIndex; }

    @Override
    public MutableText getName() {
        SlotData d = SlotManager.getBySlot(getSlotKey());
        String name = d != null ? d.displayName : null;
        return Text.literal(name != null ? name : "Custom Block " + slotIndex);
    }

    /**
     * Right-click an animated block → open the Animation Settings GUI.
     * Non-animated blocks pass through to vanilla behaviour (no arm-swing).
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        SlotData data = SlotManager.getBySlot(getSlotKey());
        boolean animated = data != null && data.isAnimated();

        // Non-animated: behave like a normal block (no special click handling, no arm-swing).
        if (!animated) return ActionResult.PASS;

        if (world.isClient) return ActionResult.SUCCESS;

        if (player instanceof ServerPlayerEntity sp) {
            GuiManager.openAnimGui(sp, data.customId);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        VoxelShape s = buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        SlotData d = SlotManager.getBySlot(getSlotKey());
        if (d != null && d.noCollision) return VoxelShapes.empty();
        VoxelShape s = buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        VoxelShape s = buildVoxelShape(getSlotKey());
        return s != null ? s : VoxelShapes.fullCube();
    }

    @Override
    public BlockSoundGroup getSoundGroup(BlockState state) {
        SlotData d = SlotManager.getBySlot(getSlotKey());
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
        SlotData d = SlotManager.getBySlot(getSlotKey());
        float hardness = d != null ? d.hardness : 1.5f;
        if (hardness < 0) return 0f;
        if (hardness == 0) return 1f;
        float speed = player.getBlockBreakingSpeed(state);
        boolean correctTool = speed > 1.0f;
        return correctTool ? speed / hardness / 30f : 1f / hardness / 100f;
    }

    /** Build VoxelShape from slot shape boxes. */
    private static VoxelShape buildVoxelShape(String slotKey) {
        SlotData d = SlotManager.getBySlot(slotKey);
        if (d == null || !d.isShaped()) return null;
        VoxelShape shape = VoxelShapes.empty();
        for (SlotData.ShapeBox box : d.shapeBoxes) {
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                    box.x1() / 16.0, box.y1() / 16.0, box.z1() / 16.0,
                    box.x2() / 16.0, box.y2() / 16.0, box.z2() / 16.0));
        }
        return shape;
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
            SlotData d = SlotManager.getBySlot(getSlotKey());
            String name = d != null ? d.displayName : null;
            return Text.literal(name != null ? name : "Custom Block");
        }

        @Override
        public Text getName(ItemStack stack) { return getName(); }
    }
}

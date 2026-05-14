package com.customblocks.block;

import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.gui.GuiManager;
import com.customblocks.core.HologramManager;
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

import java.util.concurrent.ConcurrentHashMap;

public class SlotBlock extends Block {

    private static final ConcurrentHashMap<Integer, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    private final int slotIndex;
    private final String slotKey;

    public SlotBlock(int slotIndex, Settings settings) {
        super(settings.nonOpaque());
        this.slotIndex = slotIndex;
        this.slotKey = "slot_" + slotIndex;
    }

    public int    getSlotIndex() { return slotIndex; }
    public String getSlotKey()   { return slotKey; }

    /** Invalidate the cached VoxelShape for a slot (call when shape changes). */
    public static void invalidateShape(int slotIndex) { SHAPE_CACHE.remove(slotIndex); }

    @Override
    public MutableText getName() {
        SlotData d = SlotManager.getBySlot(slotKey);
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
        SlotData data = SlotManager.getBySlot(slotKey);
        boolean animated = data != null && data.isAnimated();

        // Non-animated: behave like a normal block (no special click handling, no arm-swing).
        if (!animated) return ActionResult.PASS;

        if (world.isClient) return ActionResult.SUCCESS;

        if (player instanceof ServerPlayerEntity sp) {
            GuiManager.openAnimGui(sp, data.customId, 0);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return getCachedShape();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        SlotData d = SlotManager.getBySlot(slotKey);
        if (d != null && d.noCollision) return VoxelShapes.empty();
        return getCachedShape();
    }

    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return getCachedShape();
    }

    private VoxelShape getCachedShape() {
        return SHAPE_CACHE.computeIfAbsent(slotIndex, idx -> {
            VoxelShape s = buildVoxelShape(slotKey);
            return s != null ? s : VoxelShapes.fullCube();
        });
    }

    @Override
    public BlockSoundGroup getSoundGroup(BlockState state) {
        SlotData d = SlotManager.getBySlot(slotKey);
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
        SlotData d = SlotManager.getBySlot(slotKey);
        float hardness = d != null ? d.hardness : 1.5f;
        if (hardness < 0) return 0f;
        if (hardness == 0) return 1f;
        float speed = player.getBlockBreakingSpeed(state);
        boolean correctTool = speed > 1.0f;
        return correctTool ? speed / hardness / 30f : 1f / hardness / 100f;
    }

    /** I1 — remove hologram when this block is replaced/broken. */
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !newState.isOf(this)) {
            HologramManager.onBlockRemoved((net.minecraft.server.world.ServerWorld) world, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
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
        private final String slotKey;

        public SlotItem(SlotBlock block, Item.Settings settings) {
            super(block, settings);
            this.slotIndex = block.getSlotIndex();
            this.slotKey = "slot_" + slotIndex;
        }

        @Override
        protected boolean postPlacement(net.minecraft.util.math.BlockPos pos,
                                        net.minecraft.world.World world,
                                        net.minecraft.entity.player.PlayerEntity player,
                                        net.minecraft.item.ItemStack stack,
                                        net.minecraft.block.BlockState state) {
            boolean result = super.postPlacement(pos, world, player, stack, state);
            // K1 — record placement stat; R1 — achievement hook
            if (!world.isClient) {
                SlotData d = SlotManager.getBySlot(slotKey);
                if (d != null) {
                    com.customblocks.core.PlacementStats.recordPlacement(d.customId, player.getUuid());
                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                        com.customblocks.core.AchievementManager.onBlockPlaced(sp);
                    }
                    // I1 — spawn hologram above the placed block
                    HologramManager.onBlockPlaced(
                        (net.minecraft.server.world.ServerWorld) world, pos, d.customId);
                }
            }
            return result;
        }

        @Override
        public Text getName() {
            SlotData d = SlotManager.getBySlot(slotKey);
            String name = d != null ? d.displayName : null;
            return Text.literal(name != null ? name : "Custom Block");
        }

        @Override
        public Text getName(ItemStack stack) { return getName(); }
    }
}

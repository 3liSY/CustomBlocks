package com.customblocks.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Phase 4C — Showcase Display Block.
 * <p>
 * A placeable block that spawns an invisible ArmorStand above itself and
 * cycles through registered custom blocks, showing each one as a floating
 * item on the stand's head with a name hologram.
 */
public class ShowcaseBlock extends Block {

    public static final MapCodec<ShowcaseBlock> CODEC = createCodec(ShowcaseBlock::new);

    public ShowcaseBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    /**
     * Right-click → show current config info and usage hint.
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        com.customblocks.gui.ShowcaseManager.openConfig(sp, pos);
        return ActionResult.SUCCESS;
    }

    /**
     * Fires when the block is first placed by a player.
     * Spawns the initial ArmorStand with source="all".
     */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld sw) {
            com.customblocks.gui.ShowcaseManager.onPlace(sw, pos, "all");
        }
    }

    /**
     * Fires when the block is broken — cleans up the ArmorStand + hologram.
     */
    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            com.customblocks.gui.ShowcaseManager.onBreak(world, pos);
        }
        return super.onBreak(world, pos, state, player);
    }
}

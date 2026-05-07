package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.gui.GuiManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Lumina Brush — property painter.
 *
 * Right-click any Custom Block to jump straight into the Royal Properties GUI
 * (light level slider, hardness slider, collision toggle). No need to navigate
 * through the editor — one click and you're adjusting glow in real-time.
 */
public class LuminaBrushItem extends Item {

    public LuminaBrushItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§b§lLumina §r§fBrush"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 6 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SMALL_FLAME,
                entity.getX(), entity.getY() + 1.3, entity.getZ(),
                1, 0.12, 0.15, 0.12, 0.01);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                entity.getX(), entity.getY() + 1.1, entity.getZ(),
                1, 0.15, 0.15, 0.15, 0.01);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !player.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin)) {
            player.sendMessage(
                Text.literal("§0§l[§b§lCB§0§l]§r §cYou need OP to use the Lumina Brush."), true);
            if (world instanceof ServerWorld sw)
                sw.playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

        SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

        // Open the Royal Properties GUI directly for this block
        GuiManager.openPropertiesGui(sp, data.customId, 0);

        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                8, 0.2, 0.2, 0.2, 0.03);
            sw.playSound(null, pos,
                net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.5f);
        }

        return ActionResult.SUCCESS;
    }
}

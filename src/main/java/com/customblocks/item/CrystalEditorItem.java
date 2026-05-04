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
 * Crystal Editor — visual shape editor shortcut.
 *
 * Right-click any Custom Block to jump straight into the Shape Editor GUI.
 * Browse presets (slab, stairs, carpet, etc.), toggle collision, and manage
 * custom hitboxes — all without navigating through the block editor first.
 *
 * <p><b>Registry ID:</b> {@code amethyst_chisel} (preserved for data compatibility).
 */
public class CrystalEditorItem extends Item {

    public CrystalEditorItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§5§lCrystal §r§dEditor"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return com.customblocks.core.MagicItemsManager.getConfig("amethyst_chisel").visualGlint; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 7 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                entity.getX(), entity.getY() + 1.3, entity.getZ(),
                1, 0.12, 0.15, 0.12, 0.01);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                entity.getX(), entity.getY() + 1.1, entity.getZ(),
                1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        com.customblocks.core.MagicItemsManager.MagicItemConfig cfg = com.customblocks.core.MagicItemsManager.getConfig("amethyst_chisel");
        if (!cfg.enabled) return ActionResult.PASS;

        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        if (player instanceof ServerPlayerEntity sp) {
            if (cfg.requirePermission && !com.customblocks.command.PermissionHelper.canUseTool(sp, "crystaleditor")) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] You do not have permission to use this magic item."), true);
                if (world instanceof ServerWorld sw)
                    sw.playSound(null, player.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
                return ActionResult.FAIL;
            }
            if (cfg.worksInCreativeOnly && !sp.isCreative()) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] This item only works in Creative Mode."), true);
                return ActionResult.FAIL;
            }
        }

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) {
            if (cfg.worksOnNonCustomBlocks) {
                // If it works on non custom blocks... shape editor doesn't make sense for vanilla blocks.
                // We just return PASS.
                return ActionResult.PASS;
            }
            if (player instanceof ServerPlayerEntity sp) sp.sendMessage(Text.literal("§cThis item only works on CustomBlocks!"), true);
            return ActionResult.PASS;
        }

        SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

        // Open the Shape Editor GUI directly for this block
        GuiManager.openShapeEditor(sp, data.customId, 0);

        if (world instanceof ServerWorld sw) {
            if (cfg.particlesOnUse) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    12, 0.3, 0.3, 0.3, 0.05);
            }
            if (!cfg.soundOnUse.isEmpty()) {
                net.minecraft.sound.SoundEvent se = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.tryParse(cfg.soundOnUse));
                if (se == null) se = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                sw.playSound(null, pos, se, net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.2f);
            }
        }
        
        if (cfg.cooldownTicks > 0) player.getItemCooldownManager().set(this, cfg.cooldownTicks);
        if (cfg.consumeOnUse && !sp.isCreative()) ctx.getStack().decrement(1);

        return ActionResult.SUCCESS;
    }
}

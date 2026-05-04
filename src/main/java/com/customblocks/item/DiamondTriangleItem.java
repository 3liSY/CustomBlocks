package com.customblocks.item;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.command.PermissionHelper;
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
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * Diamond Triangle — master background-removal control.
 *
 * • Right-click in air → opens the Background Studio (tolerance slider, presets, bulk re-apply).
 * • Right-click on any custom block → also opens the Studio (with that block contextually
 *   referenced for previewing — current implementation just opens the Studio).
 *
 * The Studio replaces the legacy "bgRemovalTolerance" entry that used to live in /cb config.
 */
public class DiamondTriangleItem extends Item {

    public DiamondTriangleItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§b§lDiamond §r§fTriangle"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return com.customblocks.core.MagicItemsManager.getConfig("diamond_triangle").visualGlint; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 7 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                entity.getX(), entity.getY() + 1.3, entity.getZ(),
                1, 0.13, 0.18, 0.13, 0.005);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                entity.getX(), entity.getY() + 1.1, entity.getZ(),
                1, 0.18, 0.18, 0.18, 0.01);
        }
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        com.customblocks.core.MagicItemsManager.MagicItemConfig cfg = com.customblocks.core.MagicItemsManager.getConfig("diamond_triangle");
        if (!cfg.enabled) return TypedActionResult.pass(player.getStackInHand(hand));
        
        if (world.isClient) return TypedActionResult.pass(player.getStackInHand(hand));
        if (player instanceof ServerPlayerEntity sp) {
            if (cfg.requirePermission && !PermissionHelper.canUseTool(sp, "diamondtriangle")) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] You do not have permission to use this magic item."), true);
                if (world instanceof ServerWorld sw)
                    sw.playSound(null, sp.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
                return TypedActionResult.fail(player.getStackInHand(hand));
            }
            if (cfg.worksInCreativeOnly && !sp.isCreative()) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] This item only works in Creative Mode."), true);
                return TypedActionResult.fail(player.getStackInHand(hand));
            }
            GuiManager.openBgStudio(sp);
            if (world instanceof ServerWorld sw) {
                if (cfg.particlesOnUse) {
                    sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                        sp.getX(), sp.getY() + 1, sp.getZ(),
                        10, 0.3, 0.4, 0.3, 0.05);
                }
                if (!cfg.soundOnUse.isEmpty()) {
                    net.minecraft.sound.SoundEvent se = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.tryParse(cfg.soundOnUse));
                    if (se == null) se = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                    sw.playSound(null, sp.getBlockPos(), se, net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.6f);
                }
            }
            if (cfg.cooldownTicks > 0) player.getItemCooldownManager().set(this, cfg.cooldownTicks);
            if (cfg.consumeOnUse && !sp.isCreative()) player.getStackInHand(hand).decrement(1);
        }
        return TypedActionResult.success(player.getStackInHand(hand));
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        com.customblocks.core.MagicItemsManager.MagicItemConfig cfg = com.customblocks.core.MagicItemsManager.getConfig("diamond_triangle");
        if (!cfg.enabled) return ActionResult.PASS;

        World        world  = ctx.getWorld();
        PlayerEntity player = ctx.getPlayer();
        if (world.isClient) return ActionResult.PASS;
        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        
        if (cfg.requirePermission && !PermissionHelper.canUseTool(sp, "diamondtriangle")) {
            sp.sendMessage(Text.literal("§c[CustomBlocks] You do not have permission to use this magic item."), true);
            return ActionResult.FAIL;
        }
        if (cfg.worksInCreativeOnly && !sp.isCreative()) {
            sp.sendMessage(Text.literal("§c[CustomBlocks] This item only works in Creative Mode."), true);
            return ActionResult.FAIL;
        }

        BlockState state = world.getBlockState(ctx.getBlockPos());
        if (state.getBlock() instanceof SlotBlock sb) {
            SlotData d = SlotManager.getBySlot(sb.getSlotKey());
            if (d != null && player.isSneaking()) {
                if (!cfg.allowSneakAction) return ActionResult.PASS;
                // Sneak + right-click → Quick Actions contextual menu
                GuiManager.openQuickActions(sp, d.customId);
                if (world instanceof ServerWorld sw) {
                    if (cfg.particlesOnUse) {
                        sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                            ctx.getBlockPos().getX() + 0.5, ctx.getBlockPos().getY() + 0.5, ctx.getBlockPos().getZ() + 0.5,
                            8, 0.25, 0.25, 0.25, 0.04);
                    }
                    if (!cfg.soundOnUse.isEmpty()) {
                        net.minecraft.sound.SoundEvent se = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.tryParse(cfg.soundOnUse));
                        if (se == null) se = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                        sw.playSound(null, ctx.getBlockPos(), se, net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.8f);
                    }
                }
                if (cfg.cooldownTicks > 0) player.getItemCooldownManager().set(this, cfg.cooldownTicks);
                if (cfg.consumeOnUse && !sp.isCreative()) ctx.getStack().decrement(1);
                return ActionResult.SUCCESS;
            }
        } else if (cfg.worksOnNonCustomBlocks) {
            if (player.isSneaking() && cfg.allowSneakAction) {
                // Feature allows sneak on non-custom blocks
                GuiManager.openBgStudio(sp);
                if (world instanceof ServerWorld sw && !cfg.soundOnUse.isEmpty()) {
                    net.minecraft.sound.SoundEvent se = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.tryParse(cfg.soundOnUse));
                    if (se == null) se = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                    sw.playSound(null, ctx.getBlockPos(), se, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.4f);
                }
                if (cfg.cooldownTicks > 0) player.getItemCooldownManager().set(this, cfg.cooldownTicks);
                if (cfg.consumeOnUse && !sp.isCreative()) ctx.getStack().decrement(1);
                return ActionResult.SUCCESS;
            }
        }

        if (player.isSneaking()) {
            if (world instanceof ServerWorld sw) {
                sw.playSound(null, sp.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.5f);
            }
            sp.sendMessage(Text.literal("§cThis item only works on CustomBlocks!"), true);
            return ActionResult.FAIL;
        }

        return use(world, player, ctx.getHand()).getResult();
    }
}

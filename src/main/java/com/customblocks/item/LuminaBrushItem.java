package com.customblocks.item;

import com.customblocks.command.PermissionHelper;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.gui.GuiManager;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lumina Brush — property painter.
 *
 * Right-click a Custom Block → open Properties GUI.
 * Sneak + right-click → copy all properties to clipboard.
 * With properties on clipboard, right-click another block → paste all properties.
 * Air-click (no block) → open block picker.
 */
public class LuminaBrushItem extends Item {

    /** Per-player properties clipboard. */
    public record PropertySnapshot(int lightLevel, float hardness, String soundType, boolean noCollision) {}
    public static final Map<UUID, PropertySnapshot> PROPERTY_CLIPBOARD = new ConcurrentHashMap<>();

    public LuminaBrushItem(Settings settings) {
        super(settings.maxCount(1));
    }

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
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient || hand != Hand.MAIN_HAND) return TypedActionResult.pass(user.getStackInHand(hand));
        if (!(user instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(user.getStackInHand(hand));
        if (!PermissionHelper.canUseTool(user)) {
            user.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            return TypedActionResult.fail(user.getStackInHand(hand));
        }
        // Air-click → open block picker
        GuiManager.openEditorPicker(sp, 0);
        return TypedActionResult.success(user.getStackInHand(hand));
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !PermissionHelper.canUseTool(player)) {
            player.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            if (world instanceof ServerWorld sw)
                sw.playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

        SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

        boolean sneaking = player.isSneaking();

        if (sneaking) {
            // Sneak + right-click: copy properties
            PropertySnapshot snap = new PropertySnapshot(data.lightLevel, data.hardness, data.soundType, data.noCollision);
            PROPERTY_CLIPBOARD.put(player.getUuid(), snap);
            player.sendMessage(Text.literal(
                "§a[Brush] §fCopied properties from §e" + data.displayName + "§f:"
                + " §7Light: §f" + data.lightLevel
                + " §7Hard: §f" + data.hardness
                + " §7Sound: §f" + data.soundType
                + " §7Collision: §f" + (data.noCollision ? "OFF" : "ON")
                + "§7. Right-click another block to paste."), true);
            if (world instanceof ServerWorld sw)
                sw.playSound(null, pos,
                    net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.8f);
            return ActionResult.SUCCESS;
        }

        // Check if clipboard has properties to paste
        PropertySnapshot clipboard = PROPERTY_CLIPBOARD.get(player.getUuid());
        if (clipboard != null) {
            UndoManager.pushUndoMutation(data.customId, data, "paste_properties", player.getUuid());
            SlotManager.setLightLevel(data.customId, clipboard.lightLevel());
            SlotManager.setHardness(data.customId, clipboard.hardness());
            SlotManager.setSoundType(data.customId, clipboard.soundType());
            SlotManager.setCollision(data.customId, !clipboard.noCollision());
            SlotManager.saveAll();
            player.sendMessage(Text.literal("§a[Brush] §fPasted properties onto §e" + data.displayName + "§f."), true);
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

        // Normal right-click: open Properties GUI
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

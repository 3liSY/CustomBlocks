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
 * Amethyst Chisel — visual shape editor shortcut.
 *
 * Right-click a Custom Block → open Shape Editor GUI.
 * Sneak + right-click → copy that block's hitbox to clipboard.
 * With a hitbox on clipboard, right-click another block → paste shape.
 * Air-click (no block targeted) → open block picker.
 */
public class AmethystChiselItem extends Item {

    /** Per-player hitbox clipboard: stores shape boxes copied from a block. */
    public static final Map<UUID, List<SlotData.ShapeBox>> HITBOX_CLIPBOARD = new ConcurrentHashMap<>();

    public AmethystChiselItem(Settings settings) {
        super(settings.maxCount(1));
    }

    @Override
    public Text getName()                { return Text.literal("§5§lAmethyst §r§dChisel"); }
    @Override
    public Text getName(ItemStack stack) { return buildDisplayStack(stack).getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    private ItemStack buildDisplayStack(ItemStack stack) {
        ItemStack display = stack.copy();
        display.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Right-click block §8→ §fOpen Shape Editor"),
            Text.literal("§7Sneak + right-click §8→ §fCopy block's hitbox"),
            Text.literal("§7With hitbox copied, right-click §8→ §fPaste shape"),
            Text.literal("§7Air-click §8→ §fOpen block picker"),
            HITBOX_CLIPBOARD.isEmpty() ? Text.literal("§8Clipboard: empty") :
                Text.literal("§aClipboard: shape ready to paste")
        )));
        return display;
    }

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
            // Sneak + right-click: copy hitbox to clipboard
            List<SlotData.ShapeBox> boxes = data.shapeBoxes;
            if (boxes == null || boxes.isEmpty()) {
                player.sendMessage(Text.literal("§c[Chisel] §fThis block has no custom hitbox to copy. (Default full-block shape)"), true);
                return ActionResult.FAIL;
            }
            HITBOX_CLIPBOARD.put(player.getUuid(), List.copyOf(boxes));
            player.sendMessage(Text.literal("§a[Chisel] §fCopied hitbox from §e" + data.displayName
                + " §f(" + boxes.size() + " box" + (boxes.size() == 1 ? "" : "es") + "). Right-click another block to paste."), true);
            if (world instanceof ServerWorld sw)
                sw.playSound(null, pos,
                    net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.8f);
            return ActionResult.SUCCESS;
        }

        // Check if clipboard has a hitbox to paste
        List<SlotData.ShapeBox> clipboard = HITBOX_CLIPBOARD.get(player.getUuid());
        if (clipboard != null && !clipboard.isEmpty()) {
            // Paste hitbox onto this block
            UndoManager.pushUndoMutation(data.customId, data, "paste_shape", player.getUuid());
            final List<SlotData.ShapeBox> finalClipboard = clipboard;
            SlotManager.update(data.customId, d -> d.withShapeBoxes(finalClipboard));
            com.customblocks.block.SlotBlock.invalidateShape(data.index);
            SlotManager.saveAll();
            player.sendMessage(Text.literal("§a[Chisel] §fPasted hitbox onto §e" + data.displayName + "§f."), true);
            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    12, 0.3, 0.3, 0.3, 0.05);
                sw.playSound(null, pos,
                    net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.2f);
            }
            return ActionResult.SUCCESS;
        }

        // Normal right-click: open Shape Editor GUI
        GuiManager.openShapeEditor(sp, data.customId, 0);

        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                12, 0.3, 0.3, 0.3, 0.05);
            sw.playSound(null, pos,
                net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.2f);
        }

        return ActionResult.SUCCESS;
    }
}

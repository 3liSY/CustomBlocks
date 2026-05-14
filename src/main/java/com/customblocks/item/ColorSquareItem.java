package com.customblocks.item;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.command.PermissionHelper;
import com.customblocks.gui.ChatHelper;
import com.customblocks.CustomBlocksMod;
import com.customblocks.block.SlotBlock;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Locale;

/**
 * Right-click any CustomBlock to swap to an already-existing color variant.
 * Built-in squares target black/yellow/green variants.
 * Custom squares target hex variants created by matching custom triangles.
 */
public class ColorSquareItem extends Item {
    public static final String CUSTOM_SQUARE_REGISTRY_ID = "custom_square";

    private static final String NBT_KIND = "cb_square";
    private static final String NBT_RGB = "cb_square_rgb";
    private static final String NBT_LABEL = "cb_square_label";
    private static final String NBT_KEY = "cb_square_key";

    /** Keep in sync with built-in triangle colors and command suggestions. */
    public static final String[] KNOWN_COLORS = {"black", "yellow", "green"};

    private final String colorWord;
    private final String colorName;

    public ColorSquareItem(String colorWord, String colorName, Settings settings) {
        super(settings);
        this.colorWord = colorWord;
        this.colorName = colorName;
    }

    @Override
    public Text getName() {
        return Text.literal(colorName + " Square");
    }

    @Override
    public Text getName(ItemStack stack) {
        SquareColor color = resolveColor(stack);
        return Text.literal(color.label() + " Square");
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return isCustomSquare(stack);
    }

    public static ItemStack createCustomStack(Item item, int rgb) {
        rgb &= 0xFFFFFF;
        String label = labelForRgb(rgb);
        String key = keyForRgb(rgb);
        ItemStack stack = new ItemStack(item, 1);

        NbtCompound nbt = new NbtCompound();
        nbt.putString(NBT_KIND, "custom");
        nbt.putInt(NBT_RGB, rgb);
        nbt.putString(NBT_LABEL, label);
        nbt.putString(NBT_KEY, key);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("§b§l" + label + " §r§fSquare").styled(s -> s.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Swaps instantly to a matching color variant").styled(s -> s.withItalic(false)),
            Text.literal("§7Target color: §f#" + hexForRgb(rgb)).styled(s -> s.withItalic(false)),
            Text.literal("§8Right-click a CustomBlock to swap live").styled(s -> s.withItalic(false)))));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 12 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.CHERRY_LEAVES, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.15, 0.15, 0.15, 0.02);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !PermissionHelper.canUseTool(player)) {
            player.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            if (world instanceof ServerWorld sw) {
                sw.playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            }
            return ActionResult.FAIL;
        }
        if (!CustomBlocksConfig.isColorToolModeConfigured()) {
            if (player != null) {
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_color_not_configured")), true);
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_color_config_hint")), true);
            }
            return ActionResult.FAIL;
        }

        SlotData current = SlotManager.getBySlot(sb.getSlotKey());
        if (current == null) return ActionResult.PASS;

        SquareColor color = resolveColor(ctx.getStack());
        String targetId = resolveTargetId(current.customId, color.key());

        if (targetId.equals(current.customId)) {
            if (player != null) {
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_square_already_color", color.label())), true);
                if (world instanceof ServerWorld sw) {
                    sw.playSound(null, player.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(),
                        net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.2f);
                }
            }
            return ActionResult.SUCCESS;
        }

        SlotData target = SlotManager.getById(targetId);
        if (target == null) {
            if (player != null) {
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_square_variant_missing", targetId)), true);
            }
            return ActionResult.FAIL;
        }

        // Client notify + forced redraw without neighbor churn keeps the swap snappy for recording.
        world.setBlockState(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].getDefaultState(),
            Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD | Block.FORCE_STATE);

        if (player != null) {
            player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_square_swapped", target.displayName)), true);
            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    10, 0.2, 0.2, 0.2, 0.05);
                sw.playSound(null, pos,
                    net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.1f);
            }
        }

        return ActionResult.SUCCESS;
    }

    private static String resolveTargetId(String currentId, String targetColorKey) {
        String baseId = stripCustomColorSuffix(currentId);
        String[] segments = baseId.split("_", -1);
        for (int i = 0; i < segments.length; i++) {
            for (String known : KNOWN_COLORS) {
                if (segments[i].equalsIgnoreCase(known)) {
                    segments[i] = targetColorKey;
                    return String.join("_", segments);
                }
            }
        }
        return baseId + "_" + targetColorKey;
    }

    private static String stripCustomColorSuffix(String id) {
        return id.replaceFirst("(?i)_hex_[0-9a-f]{6}$", "");
    }

    private SquareColor resolveColor(ItemStack stack) {
        if (stack != null) {
            NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (custom != null) {
                NbtCompound nbt = custom.copyNbt();
                if ("custom".equals(nbt.getString(NBT_KIND)) && nbt.contains(NBT_RGB)) {
                    int rgb = nbt.getInt(NBT_RGB) & 0xFFFFFF;
                    String label = nbt.contains(NBT_LABEL) ? nbt.getString(NBT_LABEL) : labelForRgb(rgb);
                    String key = nbt.contains(NBT_KEY) ? nbt.getString(NBT_KEY) : keyForRgb(rgb);
                    if (label == null || label.isBlank()) label = labelForRgb(rgb);
                    if (key == null || key.isBlank()) key = keyForRgb(rgb);
                    return new SquareColor(label, key);
                }
            }
        }
        return new SquareColor(colorName, colorWord.toLowerCase(Locale.ROOT));
    }

    private static boolean isCustomSquare(ItemStack stack) {
        if (stack == null) return false;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        return custom != null && "custom".equals(custom.copyNbt().getString(NBT_KIND));
    }

    private static String labelForRgb(int rgb) {
        return "Hex #" + hexForRgb(rgb);
    }

    private static String keyForRgb(int rgb) {
        return "hex_" + hexForRgb(rgb).toLowerCase(Locale.ROOT);
    }

    private static String hexForRgb(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private record SquareColor(String label, String key) {}
}

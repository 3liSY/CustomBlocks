package com.customblocks.item;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.command.PermissionHelper;
import com.customblocks.gui.ChatHelper;
import com.customblocks.CustomBlocksMod;
import com.customblocks.block.SlotBlock;
import com.customblocks.core.ColorDetection;
import com.customblocks.core.ColorNames;
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

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * All 16 canonical color family names.
     * Kept in sync with {@link ColorNames#FAMILY_NAMES} — used for ID-segment scanning.
     */
    public static final String[] KNOWN_COLORS =
        ColorNames.FAMILY_NAMES.toArray(new String[0]);

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
        final int colorRgb = rgb;
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal(label).styled(s -> s.withColor(colorRgb).withBold(true).withItalic(false))
                .append(Text.literal(" Square").styled(s -> s.withColor(0xFFFFFF).withBold(false).withItalic(false))));
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
        String targetId = resolveTargetId(current.customId, color.key(), current.texture);

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
            // No variant found — fall back to the base block directly, never auto-create
            String baseId = findBaseId(targetId, color.key());
            SlotData baseBlock = baseId != null ? SlotManager.getById(baseId) : null;
            if (baseBlock == null) {
                if (player != null) {
                    boolean isHexBlock = current.customId.matches("(?i).*_hex_[0-9a-f]{6}$");
                    if (isHexBlock && !color.key().startsWith("hex_")) {
                        player.sendMessage(Text.literal(
                            "§c[CB] §fNo §c" + color.label() + "§f variant exists for this hex block. " +
                            "Use §e/cb customcolor square <hex>§f to get a matching color square."), true);
                    } else {
                        player.sendMessage(Text.literal(
                            "§c[CB] §fNo block found for '§c" + targetId + "§f'" +
                            (baseId != null ? " and base '§c" + baseId + "§f'" : "") + "§f. Try a different color."), true);
                    }
                    if (world instanceof ServerWorld sw) {
                        sw.playSound(null, player.getBlockPos(),
                            net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                            net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
                    }
                }
                return ActionResult.FAIL;
            }
            target = baseBlock;
        }

        // Client notify + forced redraw without neighbor churn keeps the swap snappy for recording.
        SlotBlock targetBlock = CustomBlocksMod.safeSlotBlock(target.index);
        if (targetBlock == null) return ActionResult.FAIL;
        world.setBlockState(pos, targetBlock.getDefaultState(),
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

    /**
     * Determine the ID of the target color variant.
     *
     * Layer 1 — name scan: scan ID segments against all 16 families + 40 aliases.
     * If a segment resolves to ANY color family it is replaced with targetColorKey.
     * Example: "obsidian_black" → targetColorKey="yellow" → "obsidian_yellow"
     *          "marble_crimson" (alias crimson→red) → "marble_yellow"
     *
     * Layer 2 — pixel analysis: if no color segment found and we have texture bytes,
     * run HSB bucket clustering on the texture to detect the dominant color.
     * The detected family segment is replaced, or the key is appended.
     *
     * @param currentId    the block's current customId
     * @param targetColorKey the target color key (e.g. "yellow", "hex_ff0000")
     * @param textureBytes  the block's texture bytes (may be null — skips pixel analysis)
     */
    static String resolveTargetId(String currentId, String targetColorKey, byte[] textureBytes) {
        String baseId = stripCustomColorSuffix(currentId);
        String[] segments = baseId.split("_", -1);

        // Layer 1 — name scan using full ColorNames vocabulary
        for (int i = 0; i < segments.length; i++) {
            String resolved = ColorNames.resolveFamily(segments[i]);
            if (resolved != null) {
                segments[i] = targetColorKey;
                return String.join("_", segments);
            }
        }

        // Layer 2 — pixel analysis fallback
        if (textureBytes != null && textureBytes.length > 0) {
            ColorDetection.DetectionResult result = ColorDetection.detect(textureBytes);
            if (result.confident() && result.primary != null) {
                // Found a dominant color — replace last segment that is a plain word,
                // or append the target key.
                // Try to find ANY segment that pixel-matches the detected family and replace it.
                for (int i = 0; i < segments.length; i++) {
                    if (segments[i].equalsIgnoreCase(result.primary)) {
                        segments[i] = targetColorKey;
                        return String.join("_", segments);
                    }
                }
            }
        }

        // No color segment found either way — append target key as suffix
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

    /**
     * 1.28 — find the "base" block ID to fall back on when a color variant doesn't exist.
     * Example: targetId="fortnite_black", colorKey="black" → returns "fortnite" if it exists.
     * Tries exact suffix strip first, then scans segments for any color family name.
     */
    private static String findBaseId(String targetId, String colorKey) {
        // 1. Exact suffix strip: "fortnite_black" - "_black" = "fortnite"
        String suffix = "_" + colorKey;
        if (targetId.endsWith(suffix)) {
            String base = targetId.substring(0, targetId.length() - suffix.length());
            if (!base.isEmpty() && SlotManager.hasId(base)) return base;
        }
        // 2. Scan segments right-to-left for any color family, strip it
        String[] parts = targetId.split("_", -1);
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i];
            if (seg.equalsIgnoreCase(colorKey) || ColorNames.resolveFamily(seg) != null) {
                List<String> segs = new ArrayList<>(Arrays.asList(parts));
                segs.remove(i);
                if (segs.isEmpty()) continue;
                String candidate = String.join("_", segs);
                if (SlotManager.hasId(candidate)) return candidate;
            }
        }
        return null;
    }

    private record SquareColor(String label, String key) {}
}

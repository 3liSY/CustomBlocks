package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.NetworkManager;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

/**
 * Right-click any CustomBlock to create a new colour variant of it.
 * - The original block is untouched.
 * - A new block (e.g. "mars_green") is registered automatically with the
 *   background replaced by this triangle's colour, then given to you.
 * - If the variant already exists, you simply receive it without re-processing.
 *
 * Background detection uses flood-fill from all four corners, so only true
 * background pixels are recoloured — design details are never touched.
 */
public class ColorTriangleItem extends Item {

    public static final String CUSTOM_TRIANGLE_REGISTRY_ID = "custom_triangle";

    private static final String NBT_KIND = "cb_triangle";
    private static final String NBT_RGB = "cb_triangle_rgb";
    private static final String NBT_LABEL = "cb_triangle_label";
    private static final String NBT_KEY = "cb_triangle_key";

    private final int    targetR, targetG, targetB;
    private final String colorName;

    /** Known colours used in block IDs — kept in sync with ColorSquareItem. */
    private static final String[] COLOR_NAMES = { "black", "yellow", "green" };

    /** Per-channel tolerance for background detection. */
    private static final int TOLERANCE = 35;
    /** Mode B safety: skip trapped regions larger than this fraction of texture pixels. */
    private static final double MAX_TRAPPED_HOLE_FRACTION = 0.28d;

    public ColorTriangleItem(int r, int g, int b, String colorName, Settings settings) {
        super(settings);
        this.targetR   = r;
        this.targetG   = g;
        this.targetB   = b;
        this.colorName = colorName;
    }

    @Override public Text getName()                { return Text.literal(colorName + " Triangle"); }
    @Override public Text getName(ItemStack stack) {
        TriangleColor color = resolveColor(stack);
        return Text.literal(color.label() + " Triangle");
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return isCustomTriangle(stack);
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
            Text.literal("§b§l" + label + " §r§fTriangle").styled(s -> s.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Recolours connected background pixels").styled(s -> s.withItalic(false)),
            Text.literal("§7Target colour: §f#" + hexForRgb(rgb)).styled(s -> s.withItalic(false)),
            Text.literal("§8Right-click a CustomBlock to create a variant").styled(s -> s.withItalic(false)))));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 10 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.1, 0.1, 0.1, 0.02);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.2, 0.2, 0.2, 0.01);
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
                Text.literal("§0§l[§b§lCB§0§l]§r §cYou need OP to use colour triangles."), true);
            if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }
        if (!CustomBlocksConfig.isColorToolModeConfigured()) {
            if (player != null) {
                player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §eColor tools are not configured yet."), true);
                player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §7Open §f/cb config §7and choose: §fDefault: Fill corner only §7or §fExtra: Fill corners + more§7."), true);
            }
            return ActionResult.FAIL;
        }

        SlotData source = SlotManager.getBySlot(sb.getSlotKey());
        if (source == null) return ActionResult.PASS;
        TriangleColor color = resolveColor(ctx.getStack());

        byte[] workTexture = source.texture;
        if (workTexture == null || workTexture.length == 0) {
            // Heuristic Fallback: Use north face, then any face
            workTexture = source.faceTextures.get("north");
            if (workTexture == null && !source.faceTextures.isEmpty()) {
                workTexture = source.faceTextures.values().iterator().next();
            }
        }

        if (workTexture == null || workTexture.length == 0) {
            if (player != null) {
                player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l]§r §cThis block has no texture data to recolour."), true);
                if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            }
            return ActionResult.FAIL;
        }

        byte[] finalTex = workTexture;

        // ── Build the new block ID ────────────────────────────────────────────
        String baseId  = stripColorSuffix(source.customId);
        String newId   = baseId + "_" + color.key();
        String newName = deriveDisplayName(source.displayName, color.label());

        // Already this colour?
        if (newId.equals(source.customId)) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l]§r §7This block is already §f" + color.label() + "§7."), true);
            return ActionResult.SUCCESS;
        }

        // Variant already exists — just hand it over
        SlotData existing = SlotManager.getById(newId);
        if (existing != null) {
            if (player != null) {
                player.getInventory().insertStack(
                    new ItemStack(CustomBlocksMod.SLOT_ITEMS[existing.index]));
                player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l]§r §aGiven §f" + existing.displayName
                        + "§a (variant already existed)."), true);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 5, 0.2, 0.2, 0.2, 0.05);
                    sw.playSound(null, pos, net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
                }
            }
            return ActionResult.SUCCESS;
        }

        // Need a free slot
        if (SlotManager.freeSlots() == 0) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l]§r §cNo free block slots! Delete some blocks first."), true);
            return ActionResult.FAIL;
        }

        // ── Process texture in background thread ──────────────────────────────
        MinecraftServer     server = world.getServer();
        SlotData finalSrc = source;
        PlayerEntity         fp      = player;
        int fR = color.r(), fG = color.g(), fB = color.b();

        Thread t = new Thread(() -> {
            try {
                System.setProperty("java.awt.headless", "true");
                byte[] newTexture = recolourBackground(finalTex, fR, fG, fB, CustomBlocksConfig.useTrappedHoleFill());

                server.execute(() -> {
                    if (SlotManager.freeSlots() == 0) {
                        if (fp != null)
                            fp.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §cNo free slots!"), true);
                        return;
                    }
                    SlotData newD = SlotManager.assign(newId, newName, newTexture);
                    if (newD == null) {
                        if (fp != null)
                            fp.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §cFailed to allocate slot."), true);
                        return;
                    }
                    // Copy properties from the source block
                    SlotManager.setLightLevel(newId, finalSrc.lightLevel);
                    SlotManager.setHardness(newId, finalSrc.hardness);
                    SlotManager.setSoundType(newId, finalSrc.soundType);
                    UndoManager.pushUndoCreate(newId, fp != null ? fp.getUuid() : null);
                    SlotManager.saveAll();

                    // Broadcast the new block to all players
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("add", newD.index, newId, newName, newTexture,
                            finalSrc.lightLevel, finalSrc.hardness, finalSrc.soundType));

                    // Give the new block to the player
                    if (fp != null) {
                        fp.getInventory().insertStack(
                            new ItemStack(CustomBlocksMod.SLOT_ITEMS[newD.index]));
                        fp.sendMessage(
                            Text.literal("§0§l[§b§lCB§0§l]§r §aCreated §f" + newName
                                + " §aand added it to your inventory!"), true);
                        
                        ServerWorld sw = (ServerWorld) world;
                        sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
                        sw.playSound(null, pos, net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
                    }
                });
            } catch (Exception e) {
                server.execute(() -> {
                    if (fp != null)
                        fp.sendMessage(
                            Text.literal("§0§l[§b§lCB§0§l]§r §cRecolour failed: " + e.getMessage()), true);
                });
            }
        }, "CB-Recolour");
        t.setDaemon(true);
        t.start();

        return ActionResult.SUCCESS;
    }

    // ── Texture processing ────────────────────────────────────────────────────

    /**
     * Flood-fills the background of the image (seeded from all 4 corners) and
     * replaces matching pixels with the new colour.  Only connected background
     * regions reachable from the image border are changed — interior details
     * with a similar colour are never touched.
     */
    private static byte[] recolourBackground(byte[] src, int newR, int newG, int newB, boolean fillTrapped)
            throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
        if (img == null) throw new Exception("Could not decode image");

        int w = img.getWidth(), h = img.getHeight();

        // Sample background colour from the top-left pixel
        int bgArgb = img.getRGB(0, 0);
        int bgA    = (bgArgb >> 24) & 0xFF;
        int bgR    = (bgArgb >> 16) & 0xFF;
        int bgG    = (bgArgb >> 8)  & 0xFF;
        int bgB    =  bgArgb        & 0xFF;

        int newArgb = (0xFF << 24) | (newR << 16) | (newG << 8) | newB;

        boolean[][] visited = new boolean[w][h];
        Queue<int[]> queue  = new ArrayDeque<>();

        // Seed from all 4 corners
        int[][] corners = { {0,0}, {w-1,0}, {0,h-1}, {w-1,h-1} };
        for (int[] c : corners) {
            if (!visited[c[0]][c[1]] && isBackground(img, c[0], c[1], bgA, bgR, bgG, bgB)) {
                visited[c[0]][c[1]] = true;
                queue.add(c);
            }
        }

        // BFS flood fill
        int[][] dirs = { {1,0},{-1,0},{0,1},{0,-1} };
        while (!queue.isEmpty()) {
            int[] px = queue.poll();
            int x = px[0], y = px[1];
            img.setRGB(x, y, newArgb);
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h
                        && !visited[nx][ny]
                        && isBackground(img, nx, ny, bgA, bgR, bgG, bgB)) {
                    visited[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        if (fillTrapped) {
            fillTrappedBackgroundRegions(img, visited, newArgb);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    public static byte[] recolourTexture(byte[] src, int newR, int newG, int newB, boolean fillTrapped) throws Exception {
        return recolourBackground(src, newR, newG, newB, fillTrapped);
    }

    private static void fillTrappedBackgroundRegions(BufferedImage img, boolean[][] visited, int newArgb) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] scanned = new boolean[w][h];
        int totalPixels = w * h;
        int maxPixels = (int) Math.floor(totalPixels * MAX_TRAPPED_HOLE_FRACTION);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (visited[x][y] || scanned[x][y]) continue;
                if (!isHoleCandidate(img, x, y)) {
                    scanned[x][y] = true;
                    continue;
                }

                List<int[]> component = new ArrayList<>();
                Queue<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, y});
                scanned[x][y] = true;
                boolean touchesEdge = false;
                boolean hasNonCandidate = false;

                while (!q.isEmpty()) {
                    int[] p = q.poll();
                    int cx = p[0], cy = p[1];
                    component.add(p);
                    if (cx == 0 || cy == 0 || cx == w - 1 || cy == h - 1) touchesEdge = true;

                    for (int[] d : dirs) {
                        int nx = cx + d[0], ny = cy + d[1];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        if (visited[nx][ny]) continue;
                        if (scanned[nx][ny]) continue;
                        scanned[nx][ny] = true;
                        if (isHoleCandidate(img, nx, ny)) q.add(new int[]{nx, ny});
                        else hasNonCandidate = true;
                    }
                }

                if (touchesEdge) continue;
                if (hasNonCandidate) continue;
                if (component.size() > maxPixels) continue;
                for (int[] p : component) {
                    img.setRGB(p[0], p[1], newArgb);
                    visited[p[0]][p[1]] = true;
                }
            }
        }
    }

    private static boolean isHoleCandidate(BufferedImage img, int x, int y) {
        int px = img.getRGB(x, y);
        int a = (px >> 24) & 0xFF;
        if (a < 50) return true;
        int r = (px >> 16) & 0xFF;
        int g = (px >> 8) & 0xFF;
        int b = px & 0xFF;

        // Solid/near-black pockets
        int max = Math.max(r, Math.max(g, b));
        if (max <= 36) return true;

        // Checker/flat placeholder-like greys (neutral tone, not bright white)
        int min = Math.min(r, Math.min(g, b));
        int spread = max - min;
        int avg = (r + g + b) / 3;
        return spread <= 18 && avg >= 70 && avg <= 220;
    }

    private static boolean isBackground(BufferedImage img, int x, int y,
                                        int bgA, int bgR, int bgG, int bgB) {
        int px = img.getRGB(x, y);
        int a  = (px >> 24) & 0xFF;
        if (a < 50)  return true;                     // transparent = background
        if (bgA < 50) return a < 50;                  // original was transparent bg
        int r = (px >> 16) & 0xFF;
        int g = (px >> 8)  & 0xFF;
        int b =  px        & 0xFF;
        return Math.abs(r - bgR) <= TOLERANCE
            && Math.abs(g - bgG) <= TOLERANCE
            && Math.abs(b - bgB) <= TOLERANCE;
    }

    // ── ID / name helpers ─────────────────────────────────────────────────────

    /**
     * Strips a known colour segment from a block ID by scanning underscore-
     * separated tokens and removing the FIRST one that exactly matches a
     * known colour word.
     *
     * "mars_black"      → "mars"
     * "28_lam_black"    → "28_lam"
     * "black_mars"      → "mars"
     * "letter_black_v2" → "letter_v2"
     *
     * If no colour token is found the original ID is returned unchanged
     * (the new colour will be appended as a suffix by the caller).
     */
    private static String stripColorSuffix(String id) {
        id = id.replaceFirst("(?i)_hex_[0-9a-f]{6}$", "");
        String[] segments = id.split("_", -1);
        for (int i = 0; i < segments.length; i++) {
            for (String c : COLOR_NAMES) {
                if (segments[i].equalsIgnoreCase(c)) {
                    // Remove this segment, rejoin the rest
                    String[] without = new String[segments.length - 1];
                    System.arraycopy(segments, 0, without, 0, i);
                    System.arraycopy(segments, i + 1, without, i, segments.length - i - 1);
                    return String.join("_", without);
                }
            }
        }
        return id; // no colour found — caller will append new colour
    }

    public static String variantIdFor(String sourceId, String colorKey) {
        return stripColorSuffix(sourceId) + "_" + colorKey.toLowerCase(Locale.ROOT);
    }

    /** Replaces a known colour word in the display name, or appends the new colour. */
    private static String deriveDisplayName(String original, String newColorName) {
        for (String c : COLOR_NAMES) {
            String cap = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            if (original.contains(cap)) return original.replace(cap, newColorName);
            if (original.contains(c))   return original.replace(c,   newColorName.toLowerCase());
        }
        return original + " " + newColorName;
    }

    public static String variantDisplayNameFor(String sourceName, String colorLabel) {
        return deriveDisplayName(sourceName, colorLabel);
    }

    private TriangleColor resolveColor(ItemStack stack) {
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
                    return new TriangleColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, label, key);
                }
            }
        }
        String key = colorName.toLowerCase(Locale.ROOT);
        int[] rgb = CustomBlocksConfig.builtInTriangleRgb(key, targetR, targetG, targetB);
        return new TriangleColor(rgb[0], rgb[1], rgb[2], colorName, key);
    }

    private static boolean isCustomTriangle(ItemStack stack) {
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

    private record TriangleColor(int r, int g, int b, String label, String key) {}
}

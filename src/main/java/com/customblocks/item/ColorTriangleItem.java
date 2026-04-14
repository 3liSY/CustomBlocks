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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
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

    private final int    targetR, targetG, targetB;
    private final String colorName;

    /** Known colours used in block IDs — kept in sync with ColorSquareItem. */
    private static final String[] COLOR_NAMES = { "black", "yellow", "green" };

    /** Per-channel tolerance for background detection. */
    private static final int TOLERANCE = 35;

    public ColorTriangleItem(int r, int g, int b, String colorName, Settings settings) {
        super(settings);
        this.targetR   = r;
        this.targetG   = g;
        this.targetB   = b;
        this.colorName = colorName;
    }

    @Override public Text getName()                { return Text.literal(colorName + " Triangle"); }
    @Override public Text getName(ItemStack stack) { return getName(); }

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
                Text.literal("§c[CustomBlocks] You need OP to use colour triangles."), true);
            if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

        SlotData source = SlotManager.getBySlot(sb.getSlotKey());
        if (source == null) return ActionResult.PASS;

        if (source.texture == null || source.texture.length == 0) {
            if (player != null) {
                player.sendMessage(
                    Text.literal("§c[CustomBlocks] This block has no texture."), true);
                if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            }
            return ActionResult.FAIL;
        }

        // ── Build the new block ID ────────────────────────────────────────────
        String baseId  = stripColorSuffix(source.customId);
        String newId   = baseId + "_" + colorName.toLowerCase();
        String newName = deriveDisplayName(source.displayName, colorName);

        // Already this colour?
        if (newId.equals(source.customId)) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§7[CustomBlocks] This block is already §f" + colorName + "§7."), true);
            return ActionResult.SUCCESS;
        }

        // Variant already exists — just hand it over
        SlotData existing = SlotManager.getById(newId);
        if (existing != null) {
            if (player != null) {
                player.getInventory().insertStack(
                    new ItemStack(CustomBlocksMod.SLOT_ITEMS[existing.index]));
                player.sendMessage(
                    Text.literal("§a[CustomBlocks] Given §f" + existing.displayName
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
                    Text.literal("§c[CustomBlocks] No free block slots! Delete some blocks first."), true);
            return ActionResult.FAIL;
        }

        // ── Process texture in background thread ──────────────────────────────
        MinecraftServer     server = world.getServer();
        SlotData finalSrc = source;
        PlayerEntity         fp      = player;
        int fR = targetR, fG = targetG, fB = targetB;

        Thread t = new Thread(() -> {
            try {
                System.setProperty("java.awt.headless", "true");
                byte[] newTexture = recolourBackground(finalSrc.texture, fR, fG, fB);

                server.execute(() -> {
                    if (SlotManager.freeSlots() == 0) {
                        if (fp != null)
                            fp.sendMessage(Text.literal("§c[CustomBlocks] No free slots!"), true);
                        return;
                    }
                    SlotData newD = SlotManager.assign(newId, newName, newTexture);
                    if (newD == null) {
                        if (fp != null)
                            fp.sendMessage(Text.literal("§c[CustomBlocks] Failed to allocate slot."), true);
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
                            Text.literal("§a[CustomBlocks] Created §f" + newName
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
                            Text.literal("§c[CustomBlocks] Recolour failed: " + e.getMessage()), true);
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
    private static byte[] recolourBackground(byte[] src, int newR, int newG, int newB)
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

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
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

    /** Replaces a known colour word in the display name, or appends the new colour. */
    private static String deriveDisplayName(String original, String newColorName) {
        for (String c : COLOR_NAMES) {
            String cap = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            if (original.contains(cap)) return original.replace(cap, newColorName);
            if (original.contains(c))   return original.replace(c,   newColorName.toLowerCase());
        }
        return original + " " + newColorName;
    }
}

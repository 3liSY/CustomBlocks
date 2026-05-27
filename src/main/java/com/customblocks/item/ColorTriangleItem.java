package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.command.PermissionHelper;
import com.customblocks.gui.ChatHelper;
import com.customblocks.core.ColorNames;
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
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String NBT_CUSTOM_NAME = "cb_triangle_custom_name";

    private final int    targetR, targetG, targetB;
    private final String colorName;

    /**
     * All 16 canonical color family names — kept in sync with {@link ColorNames#FAMILY_NAMES}.
     * Used for stripping existing color segments from block IDs and display names.
     */
    private static final String[] COLOR_NAMES =
        ColorNames.FAMILY_NAMES.toArray(new String[0]);

    // ── 3.6 Per-player flood-fill tolerance ──────────────────────────────────
    /** Default per-channel tolerance for background detection.
     *  Falls back to {@link CustomBlocksConfig#bgRemovalTolerance} if &gt; 0, else 35. */
    public static final int DEFAULT_TOLERANCE = 35;
    /** Per-player overrides set via /cb tolerance. Range 10–80. */
    public static final Map<UUID, Integer> PLAYER_TOLERANCE = new ConcurrentHashMap<>();

    /** Resolve effective tolerance for a player (uses config default if no override). */
    public static int effectiveTolerance(UUID playerUuid) {
        if (PLAYER_TOLERANCE.containsKey(playerUuid)) {
            return PLAYER_TOLERANCE.get(playerUuid);
        }
        int cfg = CustomBlocksConfig.bgRemovalTolerance;
        return (cfg > 0) ? cfg : DEFAULT_TOLERANCE;
    }
    /** Mode B safety: skip trapped regions larger than this fraction of texture pixels. */
    private static final double MAX_TRAPPED_HOLE_FRACTION = 0.28d;

    // ── V4-29 Per-player fill mode ────────────────────────────────────────────
    /** "edge" = smart perimeter seeding (default); "full" = replace matching pixels everywhere. */
    public static final Map<UUID, String> PLAYER_MODE = new ConcurrentHashMap<>();

    /** Returns "edge" or "full" for the given player; defaults to "edge". */
    public static String effectiveMode(UUID playerUuid) {
        return PLAYER_MODE.getOrDefault(playerUuid, "edge");
    }

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
        final int colorRgb = rgb;
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal(label).styled(s -> s.withColor(colorRgb).withBold(true).withItalic(false))
                .append(Text.literal(" Triangle").styled(s -> s.withColor(0xFFFFFF).withBold(false).withItalic(false))));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("§7Recolours connected background pixels").styled(s -> s.withItalic(false)),
            Text.literal("§7Target colour: §f#" + hexForRgb(rgb)).styled(s -> s.withItalic(false)),
            Text.literal("§8Right-click a CustomBlock to create a variant").styled(s -> s.withItalic(false)),
            Text.literal("§7Mode: §f" + formatModeForTooltip() + "  §7Tolerance: §f" + getToleranceForTooltip()).styled(s -> s.withItalic(false)),
            Text.literal("§8Use §f/cb config §8to change").styled(s -> s.withItalic(false)))));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 10 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.1, 0.1, 0.1, 0.02);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.2, 0.2, 0.2, 0.01);
        }
        if (selected && !world.isClient && entity instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
            String h = com.customblocks.core.FirstUseHints.hint(sp.getUuid(), "color_triangle");
            if (h != null) {
                sp.sendMessage(Text.literal(h), false);
                sp.sendMessage(Text.literal("§7Shift+right-click to preview first.  §8Mode: §f" + formatModeForTooltip()), false);
            }
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

        if (player != null && !PermissionHelper.canUseTool(player)) {
            player.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            if (world instanceof ServerWorld sw) sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }
        if (!CustomBlocksConfig.isColorToolModeConfigured()) {
            if (player != null) {
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_color_not_configured")), true);
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_color_config_hint")), true);
            }
            return ActionResult.FAIL;
        }

        SlotData source = SlotManager.getBySlot(sb.getSlotKey());
        if (source == null) return ActionResult.PASS;

        // ── 3.5 Shift+right-click → confirmation GUI before creating color variant ──
        if (player != null && player.isSneaking()) {
            TriangleColor color = resolveColor(ctx.getStack());
            String baseId  = stripColorSuffix(source.customId);
            String newId   = baseId + "_" + color.key();
            String newName = deriveDisplayName(source.displayName, color.label());
            var job = new com.customblocks.gui.GuiManager.RecolorJob(
                source.customId, newId, newName, color.r(), color.g(), color.b());
            com.customblocks.gui.GuiManager.openRecolorConfirmGui(
                (net.minecraft.server.network.ServerPlayerEntity) player, job);
            return ActionResult.SUCCESS;
        }

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
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_no_texture")), true);
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
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_already_color", color.label())), true);
            return ActionResult.SUCCESS;
        }

        // Variant already exists — just hand it over
        SlotData existing = SlotManager.getById(newId);
        if (existing != null) {
            if (player != null) {
                SlotBlock.SlotItem existingItem = CustomBlocksMod.safeSlotItem(existing.index);
                if (existingItem != null) player.getInventory().insertStack(new ItemStack(existingItem));
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_variant_exists", existing.displayName)), true);
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
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_no_slots_long")), true);
            return ActionResult.FAIL;
        }

        // ── Process texture in background thread ──────────────────────────────
        MinecraftServer     server = world.getServer();
        SlotData finalSrc = source;
        PlayerEntity         fp      = player;
        int fR = color.r(), fG = color.g(), fB = color.b();
        // V4-29: capture per-player tolerance and fill mode before background thread
        int fTolerance = (fp != null) ? effectiveTolerance(fp.getUuid())
                                      : (CustomBlocksConfig.bgRemovalTolerance > 0 ? CustomBlocksConfig.bgRemovalTolerance : DEFAULT_TOLERANCE);
        String fMode = (fp != null) ? effectiveMode(fp.getUuid()) : "edge";

        Thread t = new Thread(() -> {
            try {
                System.setProperty("java.awt.headless", "true");
                byte[] newTexture = recolourBackground(finalTex, fR, fG, fB, CustomBlocksConfig.useTrappedHoleFill(), fTolerance, fMode);

                server.execute(() -> {
                    if (SlotManager.freeSlots() == 0) {
                        if (fp != null)
                            fp.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.no_free_slots_short")), true);
                        return;
                    }
                    SlotData newD = SlotManager.assign(newId, newName, newTexture);
                    if (newD == null) {
                        if (fp != null)
                            fp.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_allocate_failed")), true);
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
                        SlotBlock.SlotItem newDItem = CustomBlocksMod.safeSlotItem(newD.index);
                        if (newDItem != null) fp.getInventory().insertStack(new ItemStack(newDItem));
                        fp.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_created", newName)), true);
                        
                        ServerWorld sw = (ServerWorld) world;
                        sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
                        sw.playSound(null, pos, net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
                    }
                });
            } catch (Exception e) {
                server.execute(() -> {
                    if (fp != null)
                        fp.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_triangle_recolour_failed", e.getMessage())), true);
                });
            }
        }, "CB-Recolour");
        t.setDaemon(true);
        t.start();

        return ActionResult.SUCCESS;
    }

    // ── Texture processing ────────────────────────────────────────────────────

    /**
     * V4-29: Recolours background using the player's chosen mode.
     * Mode "edge"  — seeds from entire image perimeter (better than 4 corners).
     * Mode "full"  — replaces every pixel matching the background colour anywhere.
     * Background colour sampled as 3×3 median in each corner for anti-aliasing.
     * Colour distance uses CIE-Lab delta-E (perceptual) instead of per-channel abs.
     */
    private static byte[] recolourBackground(byte[] src, int newR, int newG, int newB,
                                              boolean fillTrapped, int tolerance, String mode)
            throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
        if (img == null) throw new Exception("Could not decode image");

        int w = img.getWidth(), h = img.getHeight();

        // Sample background colour from 3×3 median in each corner
        int bgArgb = sampleCornerBackground(img, w, h);
        int bgA = (bgArgb >> 24) & 0xFF;
        int bgR = (bgArgb >> 16) & 0xFF;
        int bgG = (bgArgb >> 8)  & 0xFF;
        int bgB =  bgArgb        & 0xFF;
        double[] bgLab = rgbToLab(bgR, bgG, bgB);

        // Convert tolerance (per-channel 10–80) to a CIE-Lab delta-E threshold.
        // Old per-channel 35 ≈ delta-E 18; scale linearly.
        double labThreshold = tolerance * (18.0 / 35.0);

        int newArgb = (0xFF << 24) | (newR << 16) | (newG << 8) | newB;
        boolean[][] visited = new boolean[w][h];
        Queue<int[]> queue = new ArrayDeque<>();

        if ("full".equals(mode)) {
            // Full mode: replace every matching pixel regardless of connectivity
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    if (isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                        visited[x][y] = true;
                        img.setRGB(x, y, newArgb);
                    }
                }
            }
        } else {
            // Edge mode: BFS seeded from entire perimeter (all edge pixels)
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            // Seed top and bottom rows
            for (int x = 0; x < w; x++) {
                for (int y : new int[]{0, h-1}) {
                    if (!visited[x][y] && isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                        visited[x][y] = true;
                        queue.add(new int[]{x, y});
                    }
                }
            }
            // Seed left and right columns
            for (int y = 0; y < h; y++) {
                for (int x : new int[]{0, w-1}) {
                    if (!visited[x][y] && isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                        visited[x][y] = true;
                        queue.add(new int[]{x, y});
                    }
                }
            }
            // BFS flood fill
            while (!queue.isEmpty()) {
                int[] px = queue.poll();
                int x = px[0], y = px[1];
                img.setRGB(x, y, newArgb);
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h
                            && !visited[nx][ny]
                            && isBackgroundLab(img, nx, ny, bgA, bgLab, labThreshold)) {
                        visited[nx][ny] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
            }
            if (fillTrapped) {
                // Pass 1: recolor any unvisited pixels that match the background color.
                // Catches same-color landlocked areas (e.g. red inside a red-background "9").
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        if (!visited[x][y] && isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                            img.setRGB(x, y, newArgb);
                            visited[x][y] = true;
                        }
                    }
                }
                // Pass 2: existing behavior — recolor black/grey trapped holes
                fillTrappedBackgroundRegions(img, visited, newArgb);
            }
        }

        // Edge expansion: catch background-tinted anti-aliased pixels the BFS missed.
        // Expands the recolor zone by 1.5x tolerance. Dark shadow pixels are far from the
        // background color in Lab space and won't be touched.
        double expandThreshold = labThreshold * 1.2;
        int[][] eDirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (visited[x][y]) continue;
                boolean adj = false;
                for (int[] d : eDirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h && visited[nx][ny]) {
                        adj = true; break;
                    }
                }
                if (!adj) continue;
                if (isBackgroundLab(img, x, y, bgA, bgLab, expandThreshold)) {
                    img.setRGB(x, y, newArgb);
                    visited[x][y] = true;
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    /** Samples 3×3 regions in all 4 corners, returns median ARGB. */
    private static int sampleCornerBackground(BufferedImage img, int w, int h) {
        java.util.List<Integer> samples = new ArrayList<>();
        int[][] corners = {{0,0},{Math.max(0,w-3),0},{0,Math.max(0,h-3)},{Math.max(0,w-3),Math.max(0,h-3)}};
        for (int[] c : corners) {
            for (int dx = 0; dx < 3 && c[0]+dx < w; dx++) {
                for (int dy = 0; dy < 3 && c[1]+dy < h; dy++) {
                    samples.add(img.getRGB(c[0]+dx, c[1]+dy));
                }
            }
        }
        samples.sort(java.util.Comparator.naturalOrder());
        return samples.get(samples.size() / 2);
    }

    /** Checks if a pixel is background using CIE-Lab delta-E distance. */
    private static boolean isBackgroundLab(BufferedImage img, int x, int y,
                                            int bgA, double[] bgLab, double threshold) {
        int px = img.getRGB(x, y);
        int a = (px >> 24) & 0xFF;
        if (a < 50) return true;
        if (bgA < 50) return a < 50;
        int r = (px >> 16) & 0xFF;
        int g = (px >> 8)  & 0xFF;
        int b =  px        & 0xFF;
        double[] lab = rgbToLab(r, g, b);
        double dE = Math.sqrt(
            (lab[0]-bgLab[0])*(lab[0]-bgLab[0]) +
            (lab[1]-bgLab[1])*(lab[1]-bgLab[1]) +
            (lab[2]-bgLab[2])*(lab[2]-bgLab[2]));
        return dE <= threshold;
    }

    /** sRGB → CIE L*a*b* (D65 illuminant). */
    private static double[] rgbToLab(int r, int g, int b) {
        double rd = r / 255.0, gd = g / 255.0, bd = b / 255.0;
        rd = rd > 0.04045 ? Math.pow((rd+0.055)/1.055, 2.4) : rd/12.92;
        gd = gd > 0.04045 ? Math.pow((gd+0.055)/1.055, 2.4) : gd/12.92;
        bd = bd > 0.04045 ? Math.pow((bd+0.055)/1.055, 2.4) : bd/12.92;
        double X = (rd*0.4124564 + gd*0.3575761 + bd*0.1804375) / 0.95047;
        double Y = (rd*0.2126729 + gd*0.7151522 + bd*0.0721750);
        double Z = (rd*0.0193339 + gd*0.1191920 + bd*0.9503041) / 1.08883;
        X = X > 0.008856 ? Math.cbrt(X) : 7.787*X + 16.0/116.0;
        Y = Y > 0.008856 ? Math.cbrt(Y) : 7.787*Y + 16.0/116.0;
        Z = Z > 0.008856 ? Math.cbrt(Z) : 7.787*Z + 16.0/116.0;
        return new double[]{116.0*Y - 16.0, 500.0*(X-Y), 200.0*(Y-Z)};
    }

    /** Recolour with specific tolerance and mode (V4-29: mode = "edge" or "full"). */
    public static byte[] recolourTexture(byte[] src, int newR, int newG, int newB, boolean fillTrapped, int tolerance, String mode) throws Exception {
        return recolourBackground(src, newR, newG, newB, fillTrapped, tolerance, mode);
    }

    /** Recolour with specific tolerance; uses default "edge" mode. */
    public static byte[] recolourTexture(byte[] src, int newR, int newG, int newB, boolean fillTrapped, int tolerance) throws Exception {
        return recolourBackground(src, newR, newG, newB, fillTrapped, tolerance, "edge");
    }

    /** Recolour using the default/config tolerance (backwards-compatible overload). */
    public static byte[] recolourTexture(byte[] src, int newR, int newG, int newB, boolean fillTrapped) throws Exception {
        int tol = CustomBlocksConfig.bgRemovalTolerance > 0 ? CustomBlocksConfig.bgRemovalTolerance : DEFAULT_TOLERANCE;
        return recolourBackground(src, newR, newG, newB, fillTrapped, tol, "edge");
    }

    /** Recolour using per-player tolerance AND per-player mode (V4-29 entry point). */
    public static byte[] recolourTextureForPlayer(byte[] src, int newR, int newG, int newB, boolean fillTrapped, UUID playerUuid) throws Exception {
        int tol = effectiveTolerance(playerUuid);
        String mode = effectiveMode(playerUuid);
        return recolourBackground(src, newR, newG, newB, fillTrapped, tol, mode);
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
                                        int bgA, int bgR, int bgG, int bgB, int tolerance) {
        int px = img.getRGB(x, y);
        int a  = (px >> 24) & 0xFF;
        if (a < 50)  return true;                     // transparent = background
        if (bgA < 50) return a < 50;                  // original was transparent bg
        int r = (px >> 16) & 0xFF;
        int g = (px >> 8)  & 0xFF;
        int b =  px        & 0xFF;
        return Math.abs(r - bgR) <= tolerance
            && Math.abs(g - bgG) <= tolerance
            && Math.abs(b - bgB) <= tolerance;
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
    /**
     * Strip the FIRST color segment from a block ID, using the full ColorNames vocabulary
     * (16 families + 40+ aliases).
     *
     * Examples:
     *   "mars_black"      → "mars"
     *   "28_lam_black"    → "28_lam"
     *   "black_mars"      → "mars"
     *   "letter_black_v2" → "letter_v2"
     *   "marble_crimson"  → "marble"   (alias: crimson → red)
     */
    private static String stripColorSuffix(String id) {
        id = id.replaceFirst("(?i)_hex_[0-9a-f]{6}$", "");
        String[] segments = id.split("_", -1);
        for (int i = 0; i < segments.length; i++) {
            if (ColorNames.resolveFamily(segments[i]) != null) {
                // Remove this segment, rejoin the rest
                String[] without = new String[segments.length - 1];
                System.arraycopy(segments, 0, without, 0, i);
                System.arraycopy(segments, i + 1, without, i, segments.length - i - 1);
                return String.join("_", without);
            }
        }
        return id; // no colour found — caller will append new colour
    }

    public static String variantIdFor(String sourceId, String colorKey) {
        return stripColorSuffix(sourceId) + "_" + colorKey.toLowerCase(Locale.ROOT);
    }

    /**
     * Replace a known color word in the display name, or append the new color.
     * Checks all 16 family names + all 40+ aliases (capitalised and lower-case).
     */
    private static String deriveDisplayName(String original, String newColorName) {
        if (newColorName == null || newColorName.isBlank()) return original;
        // Check canonical family names first (most common case)
        for (String c : COLOR_NAMES) {
            String cap = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            if (original.contains(cap)) return original.replace(cap, newColorName);
            if (original.contains(c))   return original.replace(c,   newColorName.toLowerCase(Locale.ROOT));
        }
        // Check aliases (e.g. "Crimson" → replace with new color)
        for (Map.Entry<String, String> e : ColorNames.ALIAS_MAP.entrySet()) {
            String alias = e.getKey();
            String cap   = Character.toUpperCase(alias.charAt(0)) + alias.substring(1);
            if (original.contains(cap)) return original.replace(cap, newColorName);
            if (original.contains(alias)) return original.replace(alias, newColorName.toLowerCase(Locale.ROOT));
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
                    if (nbt.contains(NBT_CUSTOM_NAME) && !nbt.getString(NBT_CUSTOM_NAME).isBlank()) {
                        String customName = nbt.getString(NBT_CUSTOM_NAME);
                        String k = customName.toLowerCase(Locale.ROOT).replace(" ", "_");
                        return new TriangleColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, customName, k);
                    }
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
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b =  rgb        & 0xFF;
        String closest = null;
        double bestDist = Double.MAX_VALUE;
        for (com.customblocks.gui.ColorLibrary.LibColor c : com.customblocks.gui.ColorLibrary.ALL) {
            int[] cr = com.customblocks.gui.ColorPickerHelper.hexToRgb(c.hex());
            if (cr == null) continue;
            double[] labA = rgbToLab(r, g, b);
            double[] labB = rgbToLab(cr[0], cr[1], cr[2]);
            double dE = Math.sqrt(
                (labA[0]-labB[0])*(labA[0]-labB[0]) +
                (labA[1]-labB[1])*(labA[1]-labB[1]) +
                (labA[2]-labB[2])*(labA[2]-labB[2]));
            if (dE < bestDist) { bestDist = dE; closest = c.name(); }
        }
        return (closest != null && bestDist < 25.0) ? closest : "";
    }

    private static String keyForRgb(int rgb) {
        return "hex_" + hexForRgb(rgb).toLowerCase(Locale.ROOT);
    }

    private static String hexForRgb(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private static String formatModeForTooltip() {
        String m = CustomBlocksConfig.colorToolBackgroundMode;
        if ("corners_and_trapped".equals(m)) return "Background + Enclosed Areas";
        if ("corners_only".equals(m))        return "Background Only";
        return "Not Configured";
    }

    private static String getToleranceForTooltip() {
        int t = CustomBlocksConfig.bgRemovalTolerance;
        return t > 0 ? String.valueOf(t) : "35 (default)";
    }

    private record TriangleColor(int r, int g, int b, String label, String key) {}
}

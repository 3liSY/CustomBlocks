package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.command.PermissionHelper;
import com.customblocks.gui.ChatHelper;
import com.customblocks.ImageProcessor;
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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golden Hexagon — UV face manipulation wand.
 *
 * Right-click a face → rotate 90° CW (or CCW if sneak is held).
 * Air-click → toggle single-face / all-faces mode.
 * Holds per-player rotation state counter and shows hotbar message while selected.
 */
public class GoldenHexagonItem extends Item {

    /** Per-player cumulative rotation display (0=0°, 1=90°, 2=180°, 3=270°). */
    public static final Map<UUID, Integer> PLAYER_ROTATION = new ConcurrentHashMap<>();
    /** Per-player mode: false=single-face (default), true=all-faces. */
    public static final Map<UUID, Boolean> PLAYER_ALL_FACES = new ConcurrentHashMap<>();

    public GoldenHexagonItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§6§lGolden §r§eHexagon"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (!world.isClient && world instanceof ServerWorld sw) {
            if (selected && world.getTime() % 8 == 0) {
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                    entity.getX(), entity.getY() + 1.4, entity.getZ(),
                    2, 0.15, 0.2, 0.15, 0.01);
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                    entity.getX(), entity.getY() + 1.2, entity.getZ(),
                    1, 0.2, 0.2, 0.2, 0.01);
            }
            // Show hotbar status message every 20 ticks while selected
            if (selected && world.getTime() % 20 == 0 && entity instanceof ServerPlayerEntity sp) {
                int rot = PLAYER_ROTATION.getOrDefault(sp.getUuid(), 0) * 90;
                boolean allFaces = PLAYER_ALL_FACES.getOrDefault(sp.getUuid(), false);
                String modeStr = allFaces ? "§aAll Faces" : "§7Single Face";
                sp.sendMessage(Text.literal("§6Hexagon §8| §7Rotation: §e" + rot + "° §8| Mode: " + modeStr
                    + " §8| §7Sneak+click=CCW, Air=toggle mode"), true);
            }
        }
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient || hand != Hand.MAIN_HAND) return TypedActionResult.pass(user.getStackInHand(hand));
        if (!PermissionHelper.canUseTool(user)) {
            user.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            return TypedActionResult.fail(user.getStackInHand(hand));
        }
        // Air-click → toggle single-face / all-faces mode
        boolean allFaces = !PLAYER_ALL_FACES.getOrDefault(user.getUuid(), false);
        PLAYER_ALL_FACES.put(user.getUuid(), allFaces);
        user.sendMessage(Text.literal("§6[Hexagon] §fMode: " + (allFaces ? "§aAll Faces" : "§7Single Face")), true);
        return TypedActionResult.success(user.getStackInHand(hand));
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !PermissionHelper.canUseTool(player)) {
            if (!world.isClient) player.sendMessage(PermissionHelper.toolPermissionDeniedMessage(), true);
            if (world instanceof ServerWorld sw)
                sw.playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

        if (world.isClient) return ActionResult.PASS;

        SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        String face = switch (ctx.getSide()) {
            case UP    -> "top";
            case DOWN  -> "bottom";
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST  -> "east";
            case WEST  -> "west";
        };

        boolean isSneaking = player != null && player.isSneaking();
        boolean isAllFaces = PLAYER_ALL_FACES.getOrDefault(player != null ? player.getUuid() : UUID.randomUUID(), false);

        // Determine which faces to apply transformation to
        java.util.List<String> targetFaces;
        if (isAllFaces) {
            targetFaces = java.util.List.of("top", "bottom", "north", "south", "east", "west");
        } else {
            targetFaces = java.util.List.of(face);
        }

        // Get source bytes for the clicked face
        byte[] faceBytes = data.faceTextures.containsKey(face)
            ? data.faceTextures.get(face)
            : data.texture;

        if (faceBytes == null || faceBytes.length == 0) {
            if (player != null) {
                player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_hex_no_face_texture")), true);
                if (world instanceof ServerWorld sw)
                    sw.playSound(null, player.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            }
            return ActionResult.FAIL;
        }

        MinecraftServer server = player instanceof ServerPlayerEntity sp ? sp.getServer() : null;
        if (server == null) return ActionResult.FAIL;

        String blockId = data.customId;
        UUID uuid = player.getUuid();

        // Update rotation state counter
        int currentRot = PLAYER_ROTATION.getOrDefault(uuid, 0);
        int newRot;
        if (isSneaking) {
            newRot = (currentRot + 3) % 4; // CCW = +3 mod 4
        } else {
            newRot = (currentRot + 1) % 4; // CW = +1 mod 4
        }
        PLAYER_ROTATION.put(uuid, newRot);

        // Push undo before transformation
        UndoManager.pushUndoMutation(blockId, data, "face_uv", uuid);

        final byte[] sourceBytes = faceBytes;
        final boolean ccw = isSneaking;
        final java.util.List<String> faces = targetFaces;
        final String faceLabel = isAllFaces ? "ALL FACES" : face.toUpperCase(Locale.ROOT);
        final int rotDeg = newRot * 90;

        Thread t = new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(sourceBytes));
                if (img == null) {
                    server.execute(() -> player.sendMessage(
                        Text.literal(ChatHelper.formattedKey("cmd.tool_hex_decode_failed")), true));
                    return;
                }

                // Apply rotation to the texture (CW or CCW)
                BufferedImage result;
                int w = img.getWidth(), h = img.getHeight();
                result = new BufferedImage(h, w, img.getType());
                if (ccw) {
                    // CCW 90°: (x,y) → (y, w-1-x)
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            result.setRGB(y, w - 1 - x, img.getRGB(x, y));
                } else {
                    // CW 90°: (x,y) → (h-1-y, x)
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            result.setRGB(h - 1 - y, x, img.getRGB(x, y));
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(result, "png", bos);
                byte[] newBytes = bos.toByteArray();

                server.execute(() -> {
                    for (String f : faces) {
                        SlotManager.setFaceTexture(blockId, f, newBytes);
                    }
                    SlotManager.saveAll();

                    SlotData updated = SlotManager.getById(blockId);
                    if (updated == null) return;

                    for (String f : faces) {
                        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                            "setface", updated.index, blockId, updated.displayName,
                            null, updated.lightLevel, updated.hardness, updated.soundType,
                            f, null, null));
                    }

                    String dir = ccw ? "CCW" : "CW";
                    player.sendMessage(Text.literal("§6[Hexagon] §f" + faceLabel + " rotated " + dir + ". Display angle: §e" + rotDeg + "°"), true);

                    if (world instanceof ServerWorld sw) {
                        sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            15, 0.3, 0.3, 0.3, 0.05);
                        sw.playSound(null, pos,
                            net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                            net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.4f);
                    }
                });
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Golden Hexagon transform error", e);
                server.execute(() -> player.sendMessage(
                    Text.literal(ChatHelper.formattedKey("cmd.tool_hex_transform_failed", e.getMessage())), true));
            }
        }, "CB-GoldenHexagon");
        t.setDaemon(true);
        t.start();

        return ActionResult.SUCCESS;
    }
}

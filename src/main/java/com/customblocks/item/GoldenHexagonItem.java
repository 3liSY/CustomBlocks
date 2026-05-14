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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Golden Hexagon — UV face manipulation wand.
 *
 * Right-click a Custom Block face to cycle rotation (0 → 90 → 180 → 270).
 * Sneak + right-click to flip the face texture horizontally.
 *
 * Works on per-face textures AND the main block texture (applied to the
 * clicked face). Creates a face override automatically if one doesn't exist.
 */
public class GoldenHexagonItem extends Item {

    public GoldenHexagonItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§6§lGolden §r§eHexagon"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 8 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                entity.getX(), entity.getY() + 1.4, entity.getZ(),
                2, 0.15, 0.2, 0.15, 0.01);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                entity.getX(), entity.getY() + 1.2, entity.getZ(),
                1, 0.2, 0.2, 0.2, 0.01);
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
            if (world instanceof ServerWorld sw)
                sw.playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return ActionResult.FAIL;
        }

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

        // Get the texture bytes for this face
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

        // Push undo before transformation
        UndoManager.pushUndoMutation(blockId, data, "face_uv", uuid);

        // Process the image transformation in a thread to avoid blocking
        final byte[] sourceBytes = faceBytes;
        final boolean flip = isSneaking;
        final String faceU = face.toUpperCase(Locale.ROOT);

        Thread t = new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(sourceBytes));
                if (img == null) {
                    server.execute(() -> player.sendMessage(
                        Text.literal(ChatHelper.formattedKey("cmd.tool_hex_decode_failed")), true));
                    return;
                }

                BufferedImage result;

                if (flip) {
                    // Horizontal flip
                    AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
                    tx.translate(-img.getWidth(), 0);
                    AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
                    result = op.filter(img, null);
                } else {
                    // Rotate 90° clockwise
                    int w = img.getWidth();
                    int h = img.getHeight();
                    BufferedImage rotated = new BufferedImage(h, w, img.getType());
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            rotated.setRGB(h - 1 - y, x, img.getRGB(x, y));
                        }
                    }
                    result = rotated;
                }

                // Encode back to PNG
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(result, "png", bos);
                byte[] newBytes = bos.toByteArray();

                server.execute(() -> {
                    // Apply as face override
                    SlotManager.setFaceTexture(blockId, face, newBytes);
                    SlotManager.saveAll();

                    SlotData updated = SlotManager.getById(blockId);
                    if (updated == null) return;

                    // Broadcast update
                    SlotUpdatePayload pkt = new SlotUpdatePayload(
                        "setface", updated.index, blockId, updated.displayName,
                        null, updated.lightLevel, updated.hardness, updated.soundType,
                        face, null, null);
                    NetworkManager.broadcastUpdate(server, pkt);

                    player.sendMessage(Text.literal(flip
                        ? ChatHelper.formattedKey("cmd.tool_hex_done_flip", faceU)
                        : ChatHelper.formattedKey("cmd.tool_hex_done_rotate", faceU)), true);

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

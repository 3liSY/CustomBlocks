package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // Track rotation state per player per block-face
    private static final Map<String, Integer> ROTATION_STATE = new ConcurrentHashMap<>();

    public GoldenHexagonItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                { return Text.literal("§6§lGolden §r§eHexagon"); }
    @Override
    public Text getName(ItemStack stack) { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack) { return com.customblocks.core.MagicItemsManager.getConfig("golden_hexagon").visualGlint; }

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
        com.customblocks.core.MagicItemsManager.MagicItemConfig cfg = com.customblocks.core.MagicItemsManager.getConfig("golden_hexagon");
        if (!cfg.enabled) return ActionResult.PASS;

        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        if (player instanceof ServerPlayerEntity sp) {
            if (cfg.requirePermission && !com.customblocks.command.PermissionHelper.canUseTool(sp, "goldenhexagon")) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] You do not have permission to use this magic item."), true);
                if (world instanceof ServerWorld sw)
                    sw.playSound(null, player.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
                return ActionResult.FAIL;
            }
            if (cfg.worksInCreativeOnly && !sp.isCreative()) {
                sp.sendMessage(Text.literal("§c[CustomBlocks] This item only works in Creative Mode."), true);
                return ActionResult.FAIL;
            }
        }

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) {
            if (cfg.worksOnNonCustomBlocks) {
                return ActionResult.PASS; // Vanilla block logic not supported yet.
            }
            if (player instanceof ServerPlayerEntity sp) sp.sendMessage(Text.literal("§cThis item only works on CustomBlocks!"), true);
            return ActionResult.PASS;
        }

        if (player.isSneaking() && !cfg.allowSneakAction) {
            return ActionResult.PASS;
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
                player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l] §cThis face has no texture data to manipulate."), true);
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

        Thread t = new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(sourceBytes));
                if (img == null) {
                    server.execute(() -> player.sendMessage(
                        Text.literal("§c[CustomBlocks] Failed to decode face texture."), true));
                    return;
                }

                BufferedImage result;
                String actionLabel;

                if (flip) {
                    // Horizontal flip
                    AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
                    tx.translate(-img.getWidth(), 0);
                    AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
                    result = op.filter(img, null);
                    actionLabel = "§dFlipped §f" + face.toUpperCase() + " §dhorizontally";
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
                    actionLabel = "§eRotated §f" + face.toUpperCase() + " §e90° clockwise";
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

                    player.sendMessage(Text.literal(
                        "§0§l[§b§lCB§0§l] §a" + actionLabel + " §a✔"), true);

                    if (world instanceof ServerWorld sw) {
                        com.customblocks.core.MagicItemsManager.MagicItemConfig currentCfg = com.customblocks.core.MagicItemsManager.getConfig("golden_hexagon");
                        if (currentCfg.particlesOnUse) {
                            sw.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                15, 0.3, 0.3, 0.3, 0.05);
                        }
                        if (!currentCfg.soundOnUse.isEmpty()) {
                            net.minecraft.sound.SoundEvent se = net.minecraft.registry.Registries.SOUND_EVENT.get(net.minecraft.util.Identifier.tryParse(currentCfg.soundOnUse));
                            if (se == null) se = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                            sw.playSound(null, pos, se, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.4f);
                        }
                    }
                    if (cfg.cooldownTicks > 0) player.getItemCooldownManager().set(GoldenHexagonItem.this, cfg.cooldownTicks);
                    if (cfg.consumeOnUse && !player.isCreative()) ctx.getStack().decrement(1);
                });
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.error("[CustomBlocks] Golden Hexagon transform error", e);
                server.execute(() -> player.sendMessage(
                    Text.literal("§c[CustomBlocks] Image transform failed: " + e.getMessage()), true));
            }
        }, "CB-GoldenHexagon");
        t.setDaemon(true);
        t.start();

        return ActionResult.SUCCESS;
    }
}

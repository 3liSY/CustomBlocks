package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.ImageProcessor;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
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
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rainbow Rectangle — face-paint wand.
 *
 * Right-click any Custom Block with this item to enter "face-paint" mode.
 * After providing a URL, it CREATES A NEW VARIANT BLOCK (dupe + face override)
 * so the original block definition stays unchanged. The clicked block in the
 * world is swapped to the new variant and one item is given to you.
 *
 * Typing "cancel" in chat aborts.
 */
public class RectangleToolItem extends Item {

    // ── Pending face-edit sessions ────────────────────────────────────────────

    public static final Map<UUID, PendingSession> PENDING = new ConcurrentHashMap<>();

    public record PendingSession(
            BlockPos pos,
            String   face,
            String   slotId,
            int      size       // pixel size for the face texture (from shift-click = 256, normal = DEFAULT)
    ) {}

    public RectangleToolItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                   { return Text.literal("§6§lRainbow §r§fRectangle"); }
    @Override
    public Text getName(ItemStack stack)    { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack){ return true; }

    // ── Right-click logic ─────────────────────────────────────────────────────

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§c[CustomBlocks] You need OP (level 2) to use the Rainbow Rectangle."), true);
            return ActionResult.FAIL;
        }

        SlotManager.SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        String face = switch (ctx.getSide()) {
            case UP    -> "top";
            case DOWN  -> "bottom";
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST  -> "east";
            case WEST  -> "west";
        };

        // Shift-click = high quality (256px), normal click = default (128px)
        int size = (player != null && player.isSneaking())
                ? ImageProcessor.MAX_SIZE
                : ImageProcessor.DEFAULT_SIZE;

        UUID uuid = player.getUuid();
        PENDING.put(uuid, new PendingSession(pos, face, data.customId, size));

        player.sendMessage(Text.literal(
            "§6[CustomBlocks] §eClicked §f" + face.toUpperCase() + " §eof §f" + data.displayName + "§e."), false);
        player.sendMessage(Text.literal(
            "§ePaste image URL (PNG/JPG/GIF/WebP…) — will create a new variant:"), false);
        player.sendMessage(Text.literal(
            "§7  §aShift+click §7= high quality (256px)   §8current: §f" + size + "px"), false);
        player.sendMessage(Text.literal("§7Type §ccancel §7to abort."), false);

        return ActionResult.SUCCESS;
    }

    // ── Chat input handler ────────────────────────────────────────────────────

    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        PendingSession session = PENDING.remove(uuid);
        if (session == null) return false;

        String trimmed = message.trim();

        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Text.literal("§7[CustomBlocks] Face-paint cancelled."), false);
            return true;
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            player.sendMessage(Text.literal(
                "§c[CustomBlocks] Needs a URL starting with http:// or https://"), false);
            return true;
        }

        String url    = trimmed;
        String face   = session.face();
        String baseId = session.slotId();
        int    size   = session.size();
        MinecraftServer server = player.getServer();
        if (server == null) return true;

        player.sendMessage(Text.literal(
            "§e[CustomBlocks] Downloading " + face.toUpperCase() + " face at §f" + size + "px§e…"), false);

        Thread t = new Thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);

                // Detect animated GIF
                ImageProcessor.GifResult gifResult =
                    ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw, size) : null;

                byte[] faceBytes;
                String faceAnim = null;
                if (gifResult != null) {
                    faceBytes = gifResult.stripPng();
                    faceAnim  = gifResult.mcmeta();
                    server.execute(() -> player.sendMessage(Text.literal(
                        "§b[CustomBlocks] Animated GIF — " + gifResult.frameCount() + " frames!"), false));
                } else {
                    faceBytes = ImageProcessor.toPng(raw);
                    faceBytes = ImageProcessor.padToSquare(faceBytes);
                    faceBytes = ImageProcessor.replaceBackground(faceBytes);
                    faceBytes = ImageProcessor.resizeTo(faceBytes, size);
                }

                final byte[] finalBytes = faceBytes;
                final String finalAnim  = faceAnim;

                server.execute(() -> {
                    SlotManager.SlotData original = SlotManager.getById(baseId);
                    if (original == null) {
                        player.sendMessage(Text.literal(
                            "§c[CustomBlocks] Block '" + baseId + "' was deleted."), false);
                        return;
                    }
                    if (SlotManager.freeSlots() == 0) {
                        player.sendMessage(Text.literal(
                            "§c[CustomBlocks] No free slots! Delete a block first."), false);
                        return;
                    }

                    // Generate new variant ID
                    String variantId = generateVariantId(baseId, face);
                    String variantName = original.displayName + " (" + cap(face) + ")";

                    // Dupe original → new slot
                    byte[] texCopy = original.texture != null ? original.texture.clone() : null;
                    SlotManager.SlotData newBlock = SlotManager.assign(variantId, variantName, texCopy);
                    if (newBlock == null) {
                        player.sendMessage(Text.literal("§c[CustomBlocks] Could not create variant — no free slots."), false);
                        return;
                    }

                    // Copy all properties from original
                    SlotManager.setLightLevel(variantId, original.lightLevel);
                    SlotManager.setHardness(variantId, original.hardness);
                    SlotManager.setSoundType(variantId, original.soundType);
                    if (original.animMeta != null) SlotManager.setAnimMeta(variantId, original.animMeta);
                    for (var e : original.faceTextures.entrySet())
                        SlotManager.setFaceTexture(variantId, e.getKey(), e.getValue().clone());

                    // Apply the new face override
                    SlotManager.setFaceTexture(variantId, face, finalBytes);
                    if (finalAnim != null) SlotManager.setAnimMeta(variantId, finalAnim);

                    SlotManager.pushUndoCreate(variantId);
                    SlotManager.saveAll();

                    // Swap the clicked block in the world to the new variant
                    ServerWorld world = player.getServerWorld();
                    BlockState current = world.getBlockState(session.pos());
                    if (current.getBlock() instanceof SlotBlock) {
                        world.setBlockState(session.pos(),
                            CustomBlocksMod.SLOT_BLOCKS[newBlock.index].getDefaultState());
                    }

                    // Give player 1 of the new block
                    player.getInventory().insertStack(
                        new ItemStack(CustomBlocksMod.SLOT_ITEMS[newBlock.index], 1));

                    // Broadcast add + face to all clients
                    SlotManager.SlotData fresh = SlotManager.getById(variantId);
                    if (fresh != null) {
                        CustomBlocksMod.broadcastUpdate(server,
                            new SlotUpdatePayload("add", fresh.index, variantId, variantName,
                                texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType));
                        // Re-send all faces including the new one
                        for (var fe : fresh.faceTextures.entrySet())
                            CustomBlocksMod.broadcastUpdate(server,
                                new SlotUpdatePayload("setface", fresh.index, variantId, null,
                                    fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType,
                                    fe.getKey()));
                    }

                    player.sendMessage(Text.literal(
                        "§a[CustomBlocks] Created variant §f'" + variantId + "'§a! " +
                        "§7(slot #" + newBlock.index + ") — original untouched."), false);
                });

            } catch (Exception e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("§c[CustomBlocks] Failed: " + e.getMessage()), false));
            }
        }, "PB-RectDownload");
        t.setDaemon(true);
        t.start();

        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Auto-generate a free variant ID like baseId_face, baseId_face_2, … */
    private static String generateVariantId(String baseId, String face) {
        String candidate = baseId + "_" + face;
        if (!SlotManager.hasId(candidate)) return candidate;
        for (int i = 2; i <= 99; i++) {
            String c = candidate + "_" + i;
            if (!SlotManager.hasId(c)) return c;
        }
        return candidate + "_" + (System.currentTimeMillis() % 10000);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

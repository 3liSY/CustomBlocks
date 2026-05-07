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
import net.minecraft.util.math.BlockPos;
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
    private static final Map<UUID, Long> SESSION_TIMESTAMPS = new ConcurrentHashMap<>();

    public record PendingSession(
            BlockPos pos,
            String   face,
            String   slotId,
            int      size
    ) {}

    public RectangleToolItem(Settings settings) { super(settings); }

    @Override
    public Text getName()                   { return Text.literal("§6§lRainbow §r§fRectangle"); }
    @Override
    public Text getName(ItemStack stack)    { return getName(); }
    @Override
    public boolean hasGlint(ItemStack stack){ return true; }

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (selected && !world.isClient && world.getTime() % 10 == 0 && world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.1, 0.1, 0.1, 0.02);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    // ── Right-click logic ─────────────────────────────────────────────────────

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !player.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin)) {
            player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §cYou need OP to use the Rainbow Rectangle."), true);
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

        int size = (player != null && player.isSneaking())
                ? ImageProcessor.MAX_SIZE
                : ImageProcessor.DEFAULT_SIZE;

        UUID uuid = player.getUuid();
        PENDING.put(uuid, new PendingSession(pos, face, data.customId, size));
        SESSION_TIMESTAMPS.put(uuid, System.currentTimeMillis());

        player.sendMessage(Text.literal(
            "§0§l[§b§lCB§0§l]§r §eClicked §f" + face.toUpperCase() + " §eof §f" + data.displayName + "§e."), false);
        player.sendMessage(Text.literal(
            "§ePaste image URL (PNG/JPG/GIF/WebP…) — will create a new variant:"), false);
        player.sendMessage(Text.literal(
            "§7  §aShift+click §7= high quality (256px)   §8current: §f" + size + "px"), false);
        player.sendMessage(Text.literal("§7Type §ccancel §7to abort."), false);

        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.05);
            sw.playSound(null, pos, net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1.2f);
        }

        return ActionResult.SUCCESS;
    }

    // ── Chat input handler ────────────────────────────────────────────────────

    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        PendingSession session = PENDING.remove(uuid);
        SESSION_TIMESTAMPS.remove(uuid);
        if (session == null) return false;

        String trimmed = message.trim();

        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §7Face-paint cancelled."), false);
            player.getServerWorld().playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.8f);
            return true;
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            player.sendMessage(Text.literal(
                "§0§l[§b§lCB§0§l]§r §cNeeds a URL starting with http:// or https://"), false);
            return true;
        }

        String url    = trimmed;
        String face   = session.face();
        String baseId = session.slotId();
        int    size   = session.size();
        MinecraftServer server = player.getServer();
        if (server == null) return true;

        player.sendMessage(Text.literal(
            "§0§l[§b§lCB§0§l]§r §eDownloading " + face.toUpperCase() + " face at §f" + size + "px§e..."), false);

        Thread t = new Thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);

                ImageProcessor.ProcessResult anim =
                    ImageProcessor.isAnimatedImage(raw) ? ImageProcessor.processAnimation(raw, size) : null;

                byte[] faceBytes;
                String faceAnim = null;
                if (anim != null && anim.isAnimated()) {
                    faceBytes = anim.bytes();
                    faceAnim  = anim.mcmeta();
                    server.execute(() -> player.sendMessage(Text.literal(
                        "§0§l[§b§lCB§0§l]§r §bAnimated Image — " + anim.frameCount() + " frames!"), false));
                } else {
                    faceBytes = ImageProcessor.toPng(raw);
                    faceBytes = ImageProcessor.padToSquare(faceBytes);
                    faceBytes = ImageProcessor.replaceBackground(faceBytes);
                    faceBytes = ImageProcessor.resizeTo(faceBytes, size);
                }

                final byte[] finalBytes = faceBytes;
                final String finalAnim  = faceAnim;

                server.execute(() -> {
                    SlotData original = SlotManager.getById(baseId);
                    if (original == null) {
                        player.sendMessage(Text.literal(
                            "§0§l[§b§lCB§0§l]§r §cBlock '" + baseId + "' was deleted."), false);
                        return;
                    }
                    if (SlotManager.freeSlots() == 0) {
                        player.sendMessage(Text.literal(
                            "§0§l[§b§lCB§0§l]§r §cNo free slots! Delete a block first."), false);
                        return;
                    }

                    String variantId = generateVariantId(baseId, face);
                    String variantName = original.displayName + " (" + cap(face) + ")";

                    byte[] texCopy = original.texture != null ? original.texture.clone() : null;
                    SlotData newBlock = SlotManager.assign(variantId, variantName, texCopy);
                    if (newBlock == null) {
                        player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §cCould not create variant — no free slots."), false);
                        return;
                    }

                    SlotManager.setLightLevel(variantId, original.lightLevel);
                    SlotManager.setHardness(variantId, original.hardness);
                    SlotManager.setSoundType(variantId, original.soundType);
                    if (original.animMeta != null) SlotManager.setAnimMeta(variantId, original.animMeta);
                    for (var e : original.faceTextures.entrySet())
                        SlotManager.setFaceTexture(variantId, e.getKey(), e.getValue().clone());

                    SlotManager.setFaceTexture(variantId, face, finalBytes);
                    if (finalAnim != null) SlotManager.setAnimMeta(variantId, finalAnim);

                    UndoManager.pushUndoCreate(variantId, player.getUuid());
                    SlotManager.saveAll();

                    ServerWorld world = player.getServerWorld();
                    BlockState current = world.getBlockState(session.pos());
                    if (current.getBlock() instanceof SlotBlock) {
                        world.setBlockState(session.pos(),
                            CustomBlocksMod.SLOT_BLOCKS[newBlock.index].getDefaultState());
                    }

                    player.getInventory().insertStack(
                        (CustomBlocksMod.safeSlotItem(newBlock.index) != null ? new ItemStack(CustomBlocksMod.safeSlotItem(newBlock.index), 1) : ItemStack.EMPTY));

                    SlotData fresh = SlotManager.getById(variantId);
                    if (fresh != null) {
                        NetworkManager.broadcastUpdate(server,
                            new SlotUpdatePayload("add", fresh.index, variantId, variantName,
                                texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType,
                                null, null, fresh.animMeta));
                        for (var fe : fresh.faceTextures.entrySet())
                            NetworkManager.broadcastUpdate(server,
                                new SlotUpdatePayload("setface", fresh.index, variantId, null,
                                    fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType,
                                    fe.getKey()));
                    }

                    player.sendMessage(Text.literal(
                        "§0§l[§b§lCB§0§l]§r §aCreated variant §f'" + variantId + "'§a! " +
                        "§7(slot #" + newBlock.index + ") — original untouched."), false);
                    
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, session.pos().getX() + 0.5, session.pos().getY() + 0.5, session.pos().getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.1);
                    world.playSound(null, session.pos(), net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
                });

            } catch (Exception e) {
                server.execute(() -> player.sendMessage(
                    Text.literal("§0§l[§b§lCB§0§l]§r §cFailed: " + e.getMessage()), false));
            }
        }, "PB-RectDownload");
        t.setDaemon(true);
        t.start();

        return true;
    }

    // ── Session cleanup (called from server tick) ─────────────────────────────

    public static void tickSessionCleanup() {
        long timeout = CustomBlocksConfig.sessionTimeoutSeconds * 1000L;
        long now = System.currentTimeMillis();
        SESSION_TIMESTAMPS.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > timeout) {
                PENDING.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public static void onPlayerDisconnect(UUID uuid) {
        PENDING.remove(uuid);
        SESSION_TIMESTAMPS.remove(uuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

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
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Rainbow Rectangle — one unique item, no colour variants.
 *
 * Right-click any Custom Block with this item to enter "face-paint" mode:
 *   1. The item detects which face of the block was clicked (top/bottom/north/south/east/west).
 *   2. A chat prompt asks the player to type (or paste) any image URL.
 *   3. The URL is downloaded, converted to PNG, white background auto-removed, and
 *      applied as an override for that specific face.
 *   4. Typing "cancel" (case-insensitive) aborts without changes.
 *
 * Accepts ALL image formats supported by ImageIO + TwelveMonkeys (PNG, JPG, WebP, GIF, BMP…).
 *
 * Visual identity:
 *   - Has enchantment glint so it shimmers with a purple rainbow sheen in-hand.
 *   - Name rendered in cycling rainbow colours via the client-side colour provider
 *     registered in CustomBlocksClient.
 *   - Texture: rainbow_rectangle.png  (place in assets/customblocks/textures/item/)
 */
public class RectangleToolItem extends Item {

    // ── Pending face-edit sessions ────────────────────────────────────────────

    /** Keyed by player UUID. Populated on right-click, consumed on next chat message. */
    public static final Map<UUID, PendingSession> PENDING = new ConcurrentHashMap<>();

    public record PendingSession(
            BlockPos pos,    // block that was right-clicked (for context only)
            String   face,   // "top" / "bottom" / "north" / "south" / "east" / "west"
            String   slotId  // customId of the block
    ) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    public RectangleToolItem(Settings settings) {
        super(settings);
    }

    // ── Item meta ─────────────────────────────────────────────────────────────

    @Override
    public Text getName() {
        // §6§l gives gold bold; the actual rainbow cycling is done client-side
        return Text.literal("§6§lRainbow §r§fRectangle");
    }

    @Override
    public Text getName(ItemStack stack) { return getName(); }

    /** Always shimmer — reinforces the "rainbow" identity without needing animated textures. */
    @Override
    public boolean hasGlint(ItemStack stack) { return true; }

    // ── Right-click logic ─────────────────────────────────────────────────────

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        // All logic is server-side; client just returns PASS
        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        // OP check — same level as all other CB tools
        if (player != null && !player.hasPermissionLevel(2)) {
            player.sendMessage(
                Text.literal("§c[CustomBlocks] You need OP (level 2) to use the Rainbow Rectangle."), true);
            return ActionResult.FAIL;
        }

        SlotManager.SlotData data = SlotManager.getBySlot(sb.getSlotKey());
        if (data == null) return ActionResult.PASS;

        // Detect which face was clicked
        Direction side = ctx.getSide();
        String face = switch (side) {
            case UP    -> "top";
            case DOWN  -> "bottom";
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST  -> "east";
            case WEST  -> "west";
        };

        // Register pending session for this player
        UUID uuid = player.getUuid();
        PENDING.put(uuid, new PendingSession(pos, face, data.customId));

        // Prompt player in chat
        player.sendMessage(Text.literal(
            "§6[CustomBlocks] §eYou clicked the §f" + face.toUpperCase()
            + " §eface of §f" + data.displayName + "§e."), false);
        player.sendMessage(Text.literal(
            "§ePaste the image URL in chat §7(PNG, JPG, WebP, GIF… any format):"), false);
        player.sendMessage(Text.literal(
            "§7Type §ccancel §7to abort."), false);

        return ActionResult.SUCCESS;
    }

    // ── Chat input handler ────────────────────────────────────────────────────

    /**
     * Called by the chat event registered in {@link com.customblocks.CustomBlocksMod}.
     *
     * @param player  the player who sent the message
     * @param message raw chat text
     * @return {@code true} if the message was consumed (don't show in chat),
     *         {@code false} if no pending session (pass through normally)
     */
    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        PendingSession session = PENDING.remove(uuid); // consume immediately
        if (session == null) return false;             // no active session — pass through

        String trimmed = message.trim();

        // Cancellation
        if (trimmed.equalsIgnoreCase("cancel")) {
            player.sendMessage(Text.literal("§7[CustomBlocks] Face-paint cancelled."), false);
            return true;
        }

        // Validate looks like a URL
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            player.sendMessage(Text.literal(
                "§c[CustomBlocks] That doesn't look like a URL. Must start with http:// or https://"), false);
            return true;
        }

        String url    = trimmed;
        String face   = session.face();
        String slotId = session.slotId();
        MinecraftServer server = player.getServer();
        if (server == null) return true;

        player.sendMessage(Text.literal(
            "§e[CustomBlocks] Downloading image for §f" + face + " §eface…"), false);

        Thread t = new Thread(() -> {
            try {
                // Download → convert to PNG → auto-remove white background
                byte[] processed = ImageProcessor.downloadAndProcess(url);

                server.execute(() -> {
                    SlotManager.SlotData d = SlotManager.getById(slotId);
                    if (d == null) {
                        player.sendMessage(
                            Text.literal("§c[CustomBlocks] Block '§f" + slotId
                                + "§c' no longer exists."), false);
                        return;
                    }
                    SlotManager.pushUndo(slotId, "setface " + face);
                    SlotManager.setFaceTexture(slotId, face, processed);
                    SlotManager.saveAll();

                    // Broadcast setface — ONLY this face updates on clients; other faces unchanged
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId,
                            null, processed, d.lightLevel, d.hardness, d.soundType, face));

                    player.sendMessage(Text.literal(
                        "§a[CustomBlocks] §f" + face.toUpperCase()
                        + " §aface updated on §f" + d.displayName + "§a!"), false);
                });

            } catch (Exception e) {
                server.execute(() ->
                    player.sendMessage(Text.literal(
                        "§c[CustomBlocks] Failed: " + e.getMessage()), false));
            }
        }, "CB-RectDownload");
        t.setDaemon(true);
        t.start();

        return true; // consumed — don't echo to chat
    }
}

package com.customblocks;

import com.customblocks.block.SlotBlock;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4.4 — Scans loaded chunks for placed instances of a custom block.
 */
public class BlockFinder {

    /**
     * Scans loaded chunks within radius for blocks matching customId.
     * Runs on a background thread; results sent to player via chat.
     * Spawns end-rod particles at each found location.
     *
     * @param player    the requesting player
     * @param customId  the custom block ID to find
     * @param radius    scan radius in blocks
     * @param countOnly if true, only report count (no clickable positions or particles)
     */
    public static void findBlocks(ServerPlayerEntity player, String customId, int radius, boolean countOnly) {
        SlotData target = SlotManager.getById(customId);
        if (target == null) {
            player.sendMessage(
                Text.literal("§cNo custom block with ID '§f" + customId + "§c' found."), false);
            return;
        }

        final int slotIndex = target.index;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Notify the player immediately (action bar)
        player.sendMessage(
            Text.literal("§7Scanning for §b" + customId + "§7 within §f" + radius + "§7 blocks…"), true);

        new Thread(() -> {
            try {
                List<BlockPos> found = scanChunks(player, slotIndex, radius);

                server.execute(() -> {
                    if (found.isEmpty()) {
                        player.sendMessage(
                            Text.literal("§7No placed §b" + customId
                                + "§7 blocks found within §f" + radius + "§7 blocks."), false);
                        return;
                    }

                    player.sendMessage(
                        Text.literal("§aFound §f" + found.size()
                            + " §ainstance(s) of §b" + customId
                            + "§a within §f" + radius + "§a blocks:"), false);

                    if (!countOnly) {
                        int shown = Math.min(found.size(), 10);
                        for (int i = 0; i < shown; i++) {
                            BlockPos pos = found.get(i);
                            String tpCmd = "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
                            MutableText posText = Text.literal(
                                "  §7[" + (i + 1) + "] §b"
                                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + " §8(click to teleport)")
                                .setStyle(Style.EMPTY.withClickEvent(
                                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, tpCmd)));
                            player.sendMessage(posText, false);
                        }
                        if (found.size() > 10) {
                            player.sendMessage(
                                Text.literal("§8… and §7" + (found.size() - 10) + "§8 more."), false);
                        }
                        spawnParticles(player, found);
                    }
                });
            } catch (Exception e) {
                server.execute(() ->
                    player.sendMessage(
                        Text.literal("§cScan failed: " + e.getMessage()), false));
            }
        }, "CustomBlocks-BlockFinder").start();
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private static List<BlockPos> scanChunks(ServerPlayerEntity player, int slotIndex, int radius) {
        List<BlockPos> results = new ArrayList<>();
        ServerWorld world = player.getServerWorld();
        BlockPos origin = player.getBlockPos();

        int chunkRadius = (radius >> 4) + 1;
        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;

        int minY = world.getBottomY();
        int maxY = world.getTopY();

        for (int cx = originChunkX - chunkRadius; cx <= originChunkX + chunkRadius; cx++) {
            for (int cz = originChunkZ - chunkRadius; cz <= originChunkZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;

                WorldChunk chunk = world.getChunk(cx, cz);

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (int bx = baseX; bx < baseX + 16; bx++) {
                    for (int bz = baseZ; bz < baseZ + 16; bz++) {
                        // Quick horizontal distance check to skip obviously out-of-range columns
                        int dx = bx - origin.getX();
                        int dz = bz - origin.getZ();
                        if (dx * dx + dz * dz > (long) radius * radius) continue;

                        for (int by = minY; by < maxY; by++) {
                            BlockPos pos = new BlockPos(bx, by, bz);
                            BlockState state = chunk.getBlockState(pos);

                            if (!(state.getBlock() instanceof SlotBlock sb)) continue;
                            if (sb.getSlotIndex() != slotIndex) continue;

                            // 3-D distance check
                            double dist = Math.sqrt(pos.getSquaredDistance(origin));
                            if (dist > radius) continue;

                            results.add(pos.toImmutable());
                        }
                    }
                }
            }
        }
        return results;
    }

    private static void spawnParticles(ServerPlayerEntity player, List<BlockPos> positions) {
        ServerWorld world = player.getServerWorld();
        for (BlockPos pos : positions) {
            world.spawnParticles(
                ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5,
                20, 0.3, 0.5, 0.3, 0.05);
        }
    }
}

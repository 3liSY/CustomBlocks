package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Phase 9.4 — Named backup management for {@code /cb backup}.
 * <p>
 * Backups are ZIP files stored in {@code config/customblocks/backups/}.
 * Each ZIP contains the current {@code slots.json.gz} and all {@code .dat} texture files.
 * Expiry timers are tracked in memory and checked on server tick; they reset on server restart
 * (intentional — expiry is advisory, not guaranteed across restarts).
 */
public final class BackupManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Backup");
    private static final String BACKUPS_DIR = "config/customblocks/backups";
    private static final String DATA_DIR    = "config/customblocks";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    /** Per-backup expiry timestamps (millis). Populated by {@link #setExpiry}. */
    private static final Map<String, Long> EXPIRY = new java.util.concurrent.ConcurrentHashMap<>();

    private BackupManager() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Create a named backup ZIP. Returns the path to the created file.
     */
    public static Path create(String name) throws IOException {
        Path dir = Path.of(BACKUPS_DIR);
        Files.createDirectories(dir);

        String timestamp = LocalDateTime.now().format(FMT);
        String filename = timestamp + "_" + name + ".zip";
        Path dest = dir.resolve(filename);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dest))) {
            // slots.json.gz
            Path slotsFile = Path.of(DATA_DIR, "slots.json.gz");
            if (Files.exists(slotsFile)) {
                zos.putNextEntry(new ZipEntry("slots.json.gz"));
                Files.copy(slotsFile, zos);
                zos.closeEntry();
            }
            // All texture .dat files
            Path texDir = Path.of(DATA_DIR, "textures");
            if (Files.isDirectory(texDir)) {
                try (var stream = Files.list(texDir)) {
                    for (Path f : stream.filter(p -> p.toString().endsWith(".dat")).toList()) {
                        zos.putNextEntry(new ZipEntry("textures/" + f.getFileName()));
                        Files.copy(f, zos);
                        zos.closeEntry();
                    }
                }
            }
        }

        LOGGER.info("[CustomBlocks] Backup created: {}", dest.getFileName());
        return dest;
    }

    public record BackupEntry(String name, String created, long sizeKb, long expiresAt) {}

    /**
     * List all backups, newest first.
     */
    public static List<BackupEntry> list() {
        try {
            Path dir = Path.of(BACKUPS_DIR);
            if (!Files.isDirectory(dir)) return List.of();
            List<BackupEntry> result = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path f : stream.filter(p -> p.toString().endsWith(".zip")).toList()) {
                    java.nio.file.Path fnPath = f.getFileName();
                    if (fnPath == null) continue;
                    String fname = fnPath.toString();
                    long kb = Files.size(f) / 1024;
                    // Extract timestamp from filename prefix (yyyyMMdd_HHmm_name.zip)
                    String created = fname.length() >= 13 ? fname.substring(0, 13).replace("_", " ") : "unknown";
                    long exp = EXPIRY.getOrDefault(fname, 0L);
                    result.add(new BackupEntry(fname, created, kb, exp));
                }
            }
            result.sort(Comparator.comparing(BackupEntry::name).reversed());
            return result;
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to list backups: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Restore a backup by filename. Returns true on success.
     */
    public static boolean restore(String filename) {
        try {
            Path dir = Path.of(BACKUPS_DIR);
            Path zip = dir.resolve(filename);
            if (!zip.startsWith(dir.toAbsolutePath().normalize())) return false; // path traversal guard
            if (!Files.exists(zip)) return false;

            Path dataDir = Path.of(DATA_DIR);
            Files.createDirectories(dataDir);

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    Path target;
                    if (name.equals("slots.json.gz")) {
                        target = dataDir.resolve("slots.json.gz");
                    } else if (name.startsWith("textures/")) {
                        Path texDir = dataDir.resolve("textures");
                        Files.createDirectories(texDir);
                        target = texDir.resolve(name.substring("textures/".length()));
                    } else {
                        zis.closeEntry();
                        continue;
                    }
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    zis.closeEntry();
                }
            }

            // Reload from the restored files
            SlotManager.loadAll();
            LOGGER.info("[CustomBlocks] Restored backup: {}", filename);
            return true;
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Restore failed for '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    /**
     * Delete a backup by filename. Returns true if the file existed and was deleted.
     */
    public static boolean delete(String filename) {
        try {
            Path dir = Path.of(BACKUPS_DIR).toAbsolutePath().normalize();
            Path target = dir.resolve(filename).normalize();
            if (!target.startsWith(dir)) return false; // path traversal guard
            EXPIRY.remove(filename);
            return Files.deleteIfExists(target);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Delete failed for '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    /**
     * Set an expiry timer (hours from now) for a backup.
     */
    public static boolean setExpiry(String filename, int hours) {
        Path dir = Path.of(BACKUPS_DIR);
        if (!Files.exists(dir.resolve(filename))) return false;
        long expiresAt = System.currentTimeMillis() + hours * 3_600_000L;
        EXPIRY.put(filename, expiresAt);
        return true;
    }

    /**
     * Check and prune expired backups. Call this periodically (e.g., every 5 min from server tick).
     */
    public static void pruneExpired(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : new ArrayList<>(EXPIRY.entrySet())) {
            if (now >= e.getValue()) {
                boolean deleted = delete(e.getKey());
                if (deleted) {
                    LOGGER.info("[CustomBlocks] Expired backup deleted: {}", e.getKey());
                    // Notify online admins
                    final String fname = e.getKey();
                    server.getPlayerManager().getPlayerList().forEach(p -> {
                        if (com.customblocks.command.PermissionHelper.canAdmin(p.getCommandSource())) {
                            com.customblocks.gui.ChatHelper.warn(p, "Expired backup deleted: §b" + fname);
                        }
                    });
                }
            }
        }
    }
}

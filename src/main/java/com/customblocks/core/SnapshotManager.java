package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Phase Q — Safety nets.
 * Manages periodic snapshots stored in {@code config/customblocks/snapshots/}.
 * Supports /cb panic (two-step rollback) and /cb recover (GUI restore picker).
 */
public final class SnapshotManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Snapshots");
    public static final String SNAPSHOTS_DIR = "config/customblocks/snapshots";
    private static final int MAX_SNAPSHOTS = 20;
    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // Per-player panic arm: UUID -> armed-at ms
    private static final ConcurrentHashMap<UUID, Long> PANIC_ARMED = new ConcurrentHashMap<>();
    private static final long PANIC_TIMEOUT_MS = 30_000L;

    private static volatile ScheduledExecutorService scheduler;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public static void start(int intervalMinutes) {
        if (intervalMinutes <= 0) {
            LOGGER.info("[CustomBlocks] Auto-snapshots disabled (autoSnapshotMinutes=0).");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CustomBlocks-Snapshots");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
            () -> takeSnapshot("auto"),
            intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOGGER.info("[CustomBlocks] Auto-snapshot scheduler started — every {} min.", intervalMinutes);
    }

    public static void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
    }

    // ── Metadata record ──────────────────────────────────────────────────────

    public record SnapshotMeta(String filename, String timestamp, String reason, long sizeBytes) {}

    // ── Take ─────────────────────────────────────────────────────────────────

    /** Takes a snapshot tagged with a reason string (e.g. "auto", "pre_op_bulkrecolor"). */
    public static void takeSnapshot(String reason) {
        try {
            Path dir = Path.of(SNAPSHOTS_DIR);
            Files.createDirectories(dir);

            String safeReason = reason.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename   = TS_FMT.format(LocalDateTime.now()) + "_" + safeReason + ".json.gz";
            Path tmp  = dir.resolve(filename + ".tmp");
            Path dest = dir.resolve(filename);

            JsonObject root = SlotManager.serializeSnapshotToJson();
            try (GZIPOutputStream gz  = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)));
                 OutputStreamWriter ow = new OutputStreamWriter(gz, java.nio.charset.StandardCharsets.UTF_8)) {
                new GsonBuilder().create().toJson(root, ow);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            pruneOldSnapshots();
            LOGGER.info("[CustomBlocks] Snapshot saved: {}", filename);
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to take snapshot (reason={}): {}", reason, e.getMessage());
        }
    }

    // ── List ─────────────────────────────────────────────────────────────────

    /** Returns up to MAX_SNAPSHOTS entries, newest first. */
    public static List<SnapshotMeta> list() {
        try {
            Path dir = Path.of(SNAPSHOTS_DIR);
            if (!Files.exists(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_SNAPSHOTS)
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String stem = name.substring(0, name.length() - ".json.gz".length());
                        // stem = "2025-01-01_12-00-00_auto"
                        String ts = stem.length() >= 19
                            ? stem.substring(0, 19).replace('_', ' ')
                            : stem;
                        String rsn = stem.length() > 20 ? stem.substring(20) : "unknown";
                        long sz = 0;
                        try { sz = Files.size(p); } catch (Exception ignored) {}
                        return new SnapshotMeta(name, ts, rsn, sz);
                    })
                    .toList();
            }
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to list snapshots: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /** Restores from the named snapshot file. Returns true on success. */
    public static boolean restore(String filename) {
        if (filename == null || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            LOGGER.warn("[CustomBlocks] Rejected unsafe snapshot filename: {}", filename);
            return false;
        }
        Path file = Path.of(SNAPSHOTS_DIR, filename);
        if (!Files.exists(file)) {
            LOGGER.warn("[CustomBlocks] Snapshot not found: {}", filename);
            return false;
        }
        try {
            takeSnapshot("pre_restore"); // safety snapshot before restoring
            JsonObject root;
            try (GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)));
                 InputStreamReader ir = new InputStreamReader(gz, java.nio.charset.StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(ir).getAsJsonObject();
            }
            SlotManager.restoreFromSnapshotJson(root);
            LOGGER.info("[CustomBlocks] Restored from snapshot: {}", filename);
            return true;
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Failed to restore snapshot '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    // ── Panic (two-step /cb panic → /cb panic confirm) ───────────────────────

    public static void armPanic(UUID playerId) {
        PANIC_ARMED.put(playerId, System.currentTimeMillis());
    }

    public static boolean isPanicArmed(UUID playerId) {
        Long t = PANIC_ARMED.get(playerId);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > PANIC_TIMEOUT_MS) {
            PANIC_ARMED.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * Confirms a pending panic roll-back.
     * @return true if successfully restored; false if expired, no snapshots, or restore failed.
     */
    public static boolean confirmPanic(UUID playerId) {
        Long t = PANIC_ARMED.remove(playerId);
        if (t == null || System.currentTimeMillis() - t > PANIC_TIMEOUT_MS) return false;
        List<SnapshotMeta> snaps = list();
        SnapshotMeta target = snaps.stream()
            .filter(s -> !s.reason().startsWith("pre_restore") && !s.reason().startsWith("pre_op_panic"))
            .findFirst()
            .orElse(null);
        if (target == null) return false;
        return restore(target.filename());
    }

    public static void clearPanic(UUID playerId) {
        PANIC_ARMED.remove(playerId);
    }

    // ── Housekeeping ─────────────────────────────────────────────────────────

    private static void pruneOldSnapshots() {
        try {
            Path dir = Path.of(SNAPSHOTS_DIR);
            if (!Files.exists(dir)) return;
            List<Path> all;
            try (var stream = Files.list(dir)) {
                all = stream.filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            }
            for (int i = MAX_SNAPSHOTS; i < all.size(); i++) {
                try { Files.deleteIfExists(all.get(i)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomBlocks] Failed to prune old snapshots: {}", e.getMessage());
        }
    }

    private SnapshotManager() {}
}

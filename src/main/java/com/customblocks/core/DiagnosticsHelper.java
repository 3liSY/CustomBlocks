package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase T₁ — /cb diagnostics. Bundles incident reports, config, and a log tail
 * into a single ZIP for easy support sharing.
 */
public final class DiagnosticsHelper {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private DiagnosticsHelper() {}

    /**
     * Create a diagnostics ZIP in {@code config/customblocks/diagnostics/} and return its path.
     * Returns null on failure.
     */
    public static Path createDiagnosticsZip() {
        try {
            Path outDir = Path.of("config/customblocks/diagnostics");
            Files.createDirectories(outDir);
            String name = "cb-diagnostics-" + TS.format(Instant.now()) + ".zip";
            Path zipPath = outDir.resolve(name);

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
                // 1. Config
                addFileIfExists(zos, Path.of("config/customblocks/config.json"), "config.json");

                // 2. All incident files (last 10)
                Path incidentsDir = Path.of("config/customblocks/incidents");
                if (Files.isDirectory(incidentsDir)) {
                    try (var stream = Files.list(incidentsDir)) {
                        stream.filter(Files::isRegularFile)
                              .sorted(java.util.Comparator.reverseOrder())
                              .limit(10)
                              .forEach(p -> addFileIfExists(zos, p, "incidents/" + p.getFileName()));
                    }
                }

                // 3. Lock state
                addFileIfExists(zos, Path.of("config/customblocks/locks.json"), "locks.json");

                // 4. Category data
                addFileIfExists(zos, Path.of("config/customblocks/categories.json"), "categories.json");

                // 5. Latest log tail (last 200 lines)
                Path latestLog = Path.of("logs/latest.log");
                if (Files.isRegularFile(latestLog)) {
                    java.util.List<String> lines = Files.readAllLines(latestLog, StandardCharsets.UTF_8);
                    int start = Math.max(0, lines.size() - 200);
                    StringBuilder sb = new StringBuilder();
                    for (int i = start; i < lines.size(); i++) {
                        sb.append(lines.get(i)).append('\n');
                    }
                    addString(zos, "latest_log_tail.txt", sb.toString());
                }

                // 6. Summary
                StringBuilder summary = new StringBuilder();
                summary.append("CustomBlocks Diagnostics Report\n");
                summary.append("Generated: ").append(Instant.now()).append("\n");
                summary.append("Mod version: ").append(detectVersion(CustomBlocksMod.MOD_ID)).append("\n");
                summary.append("Minecraft: ").append(detectVersion("minecraft")).append("\n");
                summary.append("Fabric Loader: ").append(detectVersion("fabricloader")).append("\n");
                summary.append("Java: ").append(System.getProperty("java.version")).append("\n");
                summary.append("OS: ").append(System.getProperty("os.name")).append(" ")
                       .append(System.getProperty("os.arch")).append("\n");
                summary.append("Max memory: ").append(Runtime.getRuntime().maxMemory() / (1024*1024)).append(" MB\n");
                summary.append("Blocks registered: ").append(SlotManager.allSlots().size()).append("\n");
                summary.append("Categories: ").append(CategoryManager.getAllCategories().size()).append("\n");
                summary.append("Locked blocks: ").append(LockManager.lockedIds().size()).append("\n");
                summary.append("Snapshots: ").append(SnapshotManager.list().size()).append("\n");
                summary.append("Config:\n");
                summary.append("  maxSlots=").append(CustomBlocksConfig.maxSlots).append("\n");
                summary.append("  voiceMode=").append(CustomBlocksConfig.voiceMode).append("\n");
                summary.append("  undoMode=").append(CustomBlocksConfig.undoMode).append("\n");
                summary.append("  maxUndoDepth=").append(CustomBlocksConfig.maxUndoDepth).append("\n");
                summary.append("  texturePayloadsPerTick=").append(CustomBlocksConfig.texturePayloadsPerTick).append("\n");
                addString(zos, "summary.txt", summary.toString());
            }

            return zipPath;
        } catch (IOException | RuntimeException e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to create diagnostics ZIP", e);
            return null;
        }
    }

    private static void addFileIfExists(ZipOutputStream zos, Path file, String entryName) {
        try {
            if (Files.isRegularFile(file)) {
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not add {} to diagnostics ZIP: {}", entryName, e.getMessage());
        }
    }

    private static void addString(ZipOutputStream zos, String entryName, String content) {
        try {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not add {} to diagnostics ZIP: {}", entryName, e.getMessage());
        }
    }

    private static String detectVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // ── Phase 10.3 — Error Log Export ─────────────────────────────────────────

    /**
     * Writes WARN/ERROR lines (last 200 matches) from logs/latest.log to a
     * timestamped text file under config/customblocks/exports/. Returns the path.
     */
    public static Path exportErrorLog() throws IOException {
        Path outDir = Path.of("config/customblocks/exports");
        Files.createDirectories(outDir);
        String ts = TS.format(Instant.now());
        Path out = outDir.resolve("error_log_" + ts + ".txt");

        Path serverLog = Path.of("logs/latest.log");
        java.util.List<String> filtered = new java.util.ArrayList<>();
        if (Files.isRegularFile(serverLog)) {
            for (String line : Files.readAllLines(serverLog, StandardCharsets.UTF_8)) {
                if (line.contains("WARN") || line.contains("ERROR") || line.contains("CustomBlocks")) {
                    filtered.add(line);
                }
            }
        }
        int from = Math.max(0, filtered.size() - 200);
        String content = filtered.isEmpty()
            ? "(No WARN/ERROR lines found in logs/latest.log)"
            : String.join("\n", filtered.subList(from, filtered.size()));
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out;
    }

    // ── Phase 10.4 — GUI Audit ────────────────────────────────────────────────

    /**
     * Scans all GuiMode values and reports Royal Directive compliance.
     * Returns strings prefixed with "PASS:" or "FAIL:".
     */
    public static java.util.List<String> runGuiAudit() {
        java.util.List<String> results = new java.util.ArrayList<>();
        // Source of truth lives in GuiManager — no local hardcoded lists needed.
        java.util.Set<String> implemented = com.customblocks.gui.GuiManager.implementedModeNames();
        java.util.Set<String> knownStubs  = com.customblocks.gui.GuiManager.stubModeNames();

        for (com.customblocks.gui.GuiMode mode : com.customblocks.gui.GuiMode.values()) {
            String name = mode.name();
            if (knownStubs.contains(name)) {
                results.add("FAIL:" + name + ": stub — reserved for future phase");
            } else if (implemented.contains(name)) {
                results.add("PASS:" + name + ": handler and reopen case present");
            } else {
                results.add("FAIL:" + name + ": unknown — add to GuiManager.IMPLEMENTED_MODES or STUB_MODES");
            }
        }
        results.sort(java.util.Comparator.comparing(s -> s.startsWith("FAIL") ? "0" : "1"));
        return results;
    }
}

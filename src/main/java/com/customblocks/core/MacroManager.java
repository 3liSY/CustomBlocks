package com.customblocks.core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1/P2 — Macro system.
 * <p>
 * A macro is a named, ordered list of {@code /cb} command strings that can be
 * recorded via {@link #startRecording} and replayed via
 * {@link #runMacro}.
 * <p>
 * <b>Recording lifecycle:</b><br>
 * {@code /cb macro record <name>} → startRecording → player is "live-recording"<br>
 * Key command handlers call {@link #record(UUID, String)} when recording is active<br>
 * {@code /cb macro stop} → stopRecording → saves to disk<br>
 * <p>
 * <b>Playback:</b><br>
 * {@code /cb macro run <name>} → loads macro from disk → dispatches each stored command
 * string via {@link net.minecraft.server.command.ServerCommandSource#getServer()}.
 */
public final class MacroManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/Macro");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MACRO_DIR = Path.of("config/customblocks/macros");

    /** Per-player recording flag: UUID → macro name being recorded. */
    private static final Map<UUID, String> RECORDING = new ConcurrentHashMap<>();
    /** Per-player capture buffer while recording. */
    private static final Map<UUID, List<String>> BUFFER = new ConcurrentHashMap<>();

    private MacroManager() {}

    // ── Recording ─────────────────────────────────────────────────────────────

    /** Returns true if this player is currently in recording mode. */
    public static boolean isRecording(UUID uuid) {
        return RECORDING.containsKey(uuid);
    }

    /** Returns the macro name currently being recorded, or null. */
    public static String recordingName(UUID uuid) {
        return RECORDING.get(uuid);
    }

    /**
     * Start recording a new macro. If a macro with this name already exists on disk
     * its contents are replaced when recording stops.
     */
    public static void startRecording(UUID uuid, String macroName) {
        RECORDING.put(uuid, macroName);
        BUFFER.put(uuid, new ArrayList<>());
    }

    /**
     * Append a {@code /cb} command line to the active recording buffer.
     * The {@code command} string should NOT include the leading "/cb " prefix —
     * store just the subcommand and args (e.g. {@code "rename my_block New Name"}).
     * No-op if the player is not recording.
     */
    public static void record(UUID uuid, String command) {
        List<String> buf = BUFFER.get(uuid);
        if (buf != null && command != null && !command.isBlank()) {
            buf.add(command.trim());
        }
    }

    /**
     * Stop recording and persist the macro to disk.
     *
     * @return number of commands saved, or -1 on error.
     */
    public static int stopRecording(UUID uuid) {
        String name = RECORDING.remove(uuid);
        List<String> commands = BUFFER.remove(uuid);
        if (name == null || commands == null) return -1;
        try {
            saveMacro(name, commands);
            return commands.size();
        } catch (IOException e) {
            LOGGER.error("[Macro] Failed to save macro '{}': {}", name, e.getMessage());
            return -1;
        }
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private static Path macroPath(String name) {
        return MACRO_DIR.resolve(sanitizeName(name) + ".json");
    }

    private static String sanitizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private static void saveMacro(String name, List<String> commands) throws IOException {
        Files.createDirectories(MACRO_DIR);
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        JsonArray arr = new JsonArray();
        for (String cmd : commands) arr.add(cmd);
        obj.add("commands", arr);
        Path tmp = macroPath(name).resolveSibling(sanitizeName(name) + ".tmp");
        Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
        Files.move(tmp, macroPath(name),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Load a macro from disk. Returns null if not found or invalid.
     */
    public static List<String> loadMacro(String name) {
        Path p = macroPath(name);
        if (!Files.exists(p)) return null;
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<String> cmds = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("commands")) {
                cmds.add(el.getAsString());
            }
            return cmds;
        } catch (Exception e) {
            LOGGER.warn("[Macro] Could not load macro '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Add or replace a single command at the end of an existing (or new) macro.
     */
    public static boolean addCommand(String macroName, String command) {
        List<String> existing = loadMacro(macroName);
        if (existing == null) existing = new ArrayList<>();
        existing.add(command.trim());
        try {
            saveMacro(macroName, existing);
            return true;
        } catch (IOException e) {
            LOGGER.error("[Macro] addCommand failed for '{}': {}", macroName, e.getMessage());
            return false;
        }
    }

    /** Delete a macro file. Returns true if it existed and was deleted. */
    public static boolean deleteMacro(String name) {
        try { return Files.deleteIfExists(macroPath(name)); }
        catch (IOException e) { return false; }
    }

    /** List all macro names available on disk. */
    public static List<String> listMacros() {
        List<String> result = new ArrayList<>();
        if (!Files.exists(MACRO_DIR)) return result;
        try (var stream = Files.list(MACRO_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .map(p -> p.getFileName().toString().replace(".json", ""))
                  .sorted()
                  .forEach(result::add);
        } catch (IOException e) {
            LOGGER.warn("[Macro] Could not list macros: {}", e.getMessage());
        }
        return result;
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    /**
     * Execute all commands in a macro on behalf of {@code player}.
     * Each stored string is dispatched as {@code /cb <command>} via the server's
     * command dispatcher.
     *
     * @return number of commands dispatched, or -1 if macro not found.
     */
    public static int runMacro(net.minecraft.server.network.ServerPlayerEntity player, String name) {
        List<String> commands = loadMacro(name);
        if (commands == null) return -1;
        net.minecraft.server.command.ServerCommandSource src = player.getCommandSource();
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) return -1;
        int count = 0;
        for (String cmd : commands) {
            try {
                server.getCommandManager().getDispatcher().execute("cb " + cmd, src);
                count++;
            } catch (Exception e) {
                LOGGER.warn("[Macro] Command '{}' failed during replay: {}", cmd, e.getMessage());
            }
        }
        return count;
    }
}

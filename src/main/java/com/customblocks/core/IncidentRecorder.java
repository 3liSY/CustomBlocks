package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Writes redacted incident reports to {@code config/customblocks/incidents}.
 */
public final class IncidentRecorder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path INCIDENTS_DIR = Path.of("config", "customblocks", "incidents");
    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private IncidentRecorder() {}

    public static void record(String phase, String lastAction, Throwable error) {
        if (error == null) return;
        try {
            Files.createDirectories(INCIDENTS_DIR);

            JsonObject root = new JsonObject();
            root.addProperty("timestampUtc", Instant.now().toString());
            root.addProperty("phase", safe(phase));
            root.addProperty("lastAction", safe(lastAction));
            root.addProperty("exceptionClass", error.getClass().getName());
            root.addProperty("message", safe(error.getMessage()));
            root.addProperty("modVersion", detectVersion(CustomBlocksMod.MOD_ID));
            root.addProperty("minecraftVersion", detectVersion("minecraft"));
            root.addProperty("fabricLoaderVersion", detectVersion("fabricloader"));

            JsonArray frames = new JsonArray();
            StackTraceElement[] stack = error.getStackTrace();
            for (int i = 0; i < Math.min(5, stack.length); i++) {
                StackTraceElement frame = stack[i];
                JsonObject f = new JsonObject();
                f.addProperty("class", frame.getClassName());
                f.addProperty("method", frame.getMethodName());
                f.addProperty("file", frame.getFileName());
                f.addProperty("line", frame.getLineNumber());
                frames.add(f);
            }
            root.add("stackTop", frames);

            String fileName = FILE_STAMP.format(Instant.now()) + ".json";
            Path tmp = INCIDENTS_DIR.resolve(fileName + ".tmp");
            Path dst = INCIDENTS_DIR.resolve(fileName);
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            rotateToMostRecent(50);
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Failed to write incident report: {}", ex.getMessage());
        }
    }

    private static void rotateToMostRecent(int keep) throws Exception {
        try (Stream<Path> stream = Files.list(INCIDENTS_DIR)) {
            java.util.List<Path> files = stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::getFileName).reversed())
                .toList();
            for (int i = keep; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    private static String detectVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}

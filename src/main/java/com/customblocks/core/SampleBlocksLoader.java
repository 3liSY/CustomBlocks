package com.customblocks.core;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;

/**
 * X7/D1 — Pre-populated sample block library for first install.
 * <p>
 * On the first time the server starts with no blocks registered, this loader
 * creates a small set of coloured sample blocks so new users immediately see
 * something in the creative tab instead of an empty mod.
 *
 * Guarded by {@code config/customblocks/.samples_loaded} marker file — runs once.
 */
public final class SampleBlocksLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
    private static final String MARKER = "config/customblocks/.samples_loaded";

    /** Sample definitions: {customId, displayName, RGB hex color, lightLevel}. */
    private static final Object[][] SAMPLES = {
        {"cb_sample_crimson",  "Crimson Slab",    0xC0392B, 0},
        {"cb_sample_sapphire", "Sapphire Block",  0x1A5276, 0},
        {"cb_sample_amber",    "Amber Stone",     0xD68910, 0},
        {"cb_sample_emerald",  "Emerald Tile",    0x1E8449, 0},
        {"cb_sample_glowstone","Glow Crystal",    0xF4D03F, 12},
    };

    private SampleBlocksLoader() {}

    /**
     * Call after {@link SlotManager#loadAll()} on server start.
     * No-op if the marker file already exists or if blocks are already registered.
     */
    public static void maybeLoadSamples(MinecraftServer server) {
        Path marker = Path.of(MARKER);
        if (Files.exists(marker)) return;
        if (!SlotManager.allSlots().isEmpty()) {
            // Server already has blocks — write marker and skip
            writeMarker(marker, "skipped (blocks already existed)");
            return;
        }
        LOGGER.info("[CustomBlocks] First install detected — loading sample blocks...");
        int created = 0;
        for (Object[] s : SAMPLES) {
            String id   = (String) s[0];
            String name = (String) s[1];
            int    rgb  = (int)    s[2];
            int    lum  = (int)    s[3];
            try {
                byte[] png = makeSolidPng(rgb, 128);
                SlotData d = SlotManager.assign(id, name, png);
                if (d != null) {
                    if (lum > 0) SlotManager.setLightLevel(id, lum);
                    created++;
                    LOGGER.info("[CustomBlocks] Sample block created: '{}' -> slot #{}", id, d.index);
                }
            } catch (Exception ex) {
                LOGGER.warn("[CustomBlocks] Could not create sample block '{}': {}", id, ex.getMessage());
            }
        }
        if (created > 0) {
            SlotManager.saveAll();
            // Broadcast to all online players (in case someone is already on)
            if (server != null) {
                com.customblocks.network.NetworkManager.broadcastFullSync(server);
            }
            LOGGER.info("[CustomBlocks] {} sample block(s) installed. See /cb gui to explore them.", created);
        }
        writeMarker(marker, "loaded " + created + " samples");
    }

    /** Generate a solid-colour 128×128 PNG with a subtle border to look less flat. */
    private static byte[] makeSolidPng(int rgb, int size) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Fill main body
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, size, size);
        // Subtle lighter top/left highlight
        Color lighter = new Color(
            Math.min(255, ((rgb >> 16) & 0xFF) + 40),
            Math.min(255, ((rgb >>  8) & 0xFF) + 40),
            Math.min(255,  (rgb        & 0xFF) + 40));
        g.setColor(lighter);
        g.setStroke(new BasicStroke(3));
        g.drawLine(2, 2, size - 3, 2);
        g.drawLine(2, 2, 2, size - 3);
        // Subtle darker bottom/right shadow
        Color darker = new Color(
            Math.max(0, ((rgb >> 16) & 0xFF) - 50),
            Math.max(0, ((rgb >>  8) & 0xFF) - 50),
            Math.max(0,  (rgb        & 0xFF) - 50));
        g.setColor(darker);
        g.drawLine(2, size - 3, size - 3, size - 3);
        g.drawLine(size - 3, 2, size - 3, size - 3);
        // Inner border texture lines (crosshatch at 25%)
        g.setColor(new Color(darker.getRed(), darker.getGreen(), darker.getBlue(), 60));
        g.setStroke(new BasicStroke(1));
        int q = size / 4;
        for (int i = 1; i <= 3; i++) {
            g.drawLine(i * q, 0, i * q, size);
            g.drawLine(0, i * q, size, i * q);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    private static void writeMarker(Path marker, String note) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "status=" + note + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            LOGGER.debug("[CustomBlocks] Could not write samples marker: {}", ex.getMessage());
        }
    }
}

package com.customblocks.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksMod;
import com.customblocks.gui.ColorPickerHelper;
import com.customblocks.network.NetworkManager;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ColorVariantService {
    private static final Set<String> DRESS_OVERLAYS = Set.of("cracked", "mossy", "weathered", "glowing", "frosted");
    // Phase D11: reuse per-thread ARGB buffers across variant generation.
    private static final ThreadLocal<BufferedImage> VARIANT_BUFFER = new ThreadLocal<>();

    private ColorVariantService() {}

    public static Set<String> dressOverlays() {
        return DRESS_OVERLAYS;
    }

    public static String normalizeDressOverlay(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    public static boolean isValidDressOverlay(String raw) {
        return DRESS_OVERLAYS.contains(normalizeDressOverlay(raw));
    }

    public static String suggestDressedId(String sourceId, String overlay) {
        String normalizedOverlay = normalizeDressOverlay(overlay);
        String base = sourceId + "_dressed_" + normalizedOverlay;
        if (!SlotManager.hasId(base)) return base;
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "_" + i;
            if (!SlotManager.hasId(candidate)) return candidate;
        }
        return base + "_" + (System.currentTimeMillis() % 10000);
    }

    public static String dressedDisplayName(String sourceDisplayName, String overlay) {
        return sourceDisplayName + " (" + overlayLabel(overlay) + ")";
    }

    public static PreparedVariant prepareDressedVariant(SlotData source, String overlay) throws IOException {
        String normalizedOverlay = normalizeDressOverlay(overlay);
        if (!DRESS_OVERLAYS.contains(normalizedOverlay)) {
            throw new IOException("Unknown overlay '" + overlay + "'.");
        }
        byte[] primary = resolvePrimaryTexture(source);
        if (primary == null || primary.length == 0) {
            throw new IOException("Block '" + source.customId + "' has no texture data to dress.");
        }
        byte[] baseTexture = applyDress(primary, normalizedOverlay);
        Map<String, byte[]> faces = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : source.faceTextures.entrySet()) {
            byte[] value = entry.getValue();
            if (value != null && value.length > 0) {
                faces.put(entry.getKey(), applyDress(value, normalizedOverlay));
            }
        }
        return new PreparedVariant(baseTexture, Collections.unmodifiableMap(faces));
    }

    public static CreationResult createPreparedVariant(
            MinecraftServer server,
            SlotData source,
            String targetId,
            String targetName,
            PreparedVariant prepared,
            UUID actorId,
            String actorName,
            ServerPlayerEntity recipient,
            String historyAction,
            String historyDetail) {
        SlotData existing = SlotManager.getById(targetId);
        if (existing != null) {
            giveToPlayer(existing, recipient);
            return new CreationResult(existing, false, targetId, targetName);
        }

        SlotData created = SlotManager.assign(targetId, targetName, prepared.baseTexture());
        if (created == null) return null;

        SlotManager.setLightLevel(targetId, source.lightLevel);
        SlotManager.setHardness(targetId, source.hardness);
        SlotManager.setSoundType(targetId, source.soundType);
        if (source.animMeta != null && !source.animMeta.isBlank()) {
            SlotManager.setAnimMeta(targetId, source.animMeta);
        }
        for (Map.Entry<String, byte[]> entry : prepared.faceTextures().entrySet()) {
            SlotManager.setFaceTexture(targetId, entry.getKey(), entry.getValue());
        }
        if (source.isShaped()) {
            SlotManager.setShape(targetId, new ArrayList<>(source.shapeBoxes));
        }
        if (source.noCollision) {
            SlotManager.setCollision(targetId, false);
        }

        try {
            Set<String> srcCats = new LinkedHashSet<>(CategoryManager.getCategoriesForBlock(source.customId));
            for (String cat : srcCats) {
                CategoryManager.assignBlock(targetId, cat);
            }
        } catch (Throwable ignored) {
        }

        UndoManager.pushUndoCreate(targetId, actorId);
        SlotManager.saveAll();

        SlotData finalData = SlotManager.getById(targetId);
        broadcastFullVariant(server, finalData);
        giveToPlayer(finalData, recipient);
        HistoryTracker.record(actorId, actorName, historyAction, targetId, historyDetail);
        return new CreationResult(finalData, true, targetId, targetName);
    }

    public static void broadcastFullVariant(MinecraftServer server, SlotData data) {
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                "add", data.index, data.customId, data.displayName, data.texture,
                data.lightLevel, data.hardness, data.soundType, null, null, data.animMeta));
        for (Map.Entry<String, byte[]> faceEntry : data.faceTextures.entrySet()) {
            NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                    "setface", data.index, data.customId, null, faceEntry.getValue(),
                    data.lightLevel, data.hardness, data.soundType, faceEntry.getKey(), null, data.animMeta));
        }
        if (data.isShaped()) {
            NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                    "setshape", data.index, data.customId, null, null,
                    0, 0, "stone", null, serializeShape(data.shapeBoxes)));
        }
        if (data.noCollision) {
            NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                    "setcollision", data.index, data.customId, null, null,
                    0, 0, "stone", null, "false"));
        }
    }

    private static void giveToPlayer(SlotData data, ServerPlayerEntity player) {
        if (player == null || data == null) return;
        Item item = CustomBlocksMod.safeSlotItem(data.index);
        if (item == null) return;
        player.getInventory().insertStack(new ItemStack(item));
    }

    private static byte[] resolvePrimaryTexture(SlotData source) {
        if (source.texture != null && source.texture.length > 0) return source.texture;
        byte[] north = source.faceTextures.get("north");
        if (north != null && north.length > 0) return north;
        for (byte[] face : source.faceTextures.values()) {
            if (face != null && face.length > 0) return face;
        }
        
        // COL12 — fallback to disk if texture was dropped from RAM
        java.io.File tFile = new java.io.File("config/customblocks/textures", "slot_" + source.index + ".dat");
        if (tFile.exists()) {
            try {
                return java.nio.file.Files.readAllBytes(tFile.toPath());
            } catch (Exception ignored) {}
        }
        
        return null;
    }

    private static String serializeShape(List<SlotData.ShapeBox> boxes) {
        if (boxes == null || boxes.isEmpty()) return "full";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < boxes.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(boxes.get(i).toSerialString());
        }
        return sb.toString();
    }

    public static String extractRepresentativeColorHex(SlotData source) throws IOException {
        byte[] primary = resolvePrimaryTexture(source);
        if (primary == null || primary.length == 0) {
            throw new IOException("Block '" + source.customId + "' has no texture data.");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(primary));
        if (image == null) throw new IOException("Could not decode source texture.");

        long totalR = 0;
        long totalG = 0;
        long totalB = 0;
        int count = 0;
        long fallbackR = 0;
        long fallbackG = 0;
        long fallbackB = 0;
        int fallbackCount = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 24) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                fallbackR += r;
                fallbackG += g;
                fallbackB += b;
                fallbackCount++;
                if (r + g + b < 48) continue;
                totalR += r;
                totalG += g;
                totalB += b;
                count++;
            }
        }
        if (count == 0) {
            if (fallbackCount == 0) return "#808080";
            return ColorPickerHelper.rgbToHex((int) (fallbackR / fallbackCount), (int) (fallbackG / fallbackCount), (int) (fallbackB / fallbackCount));
        }
        return ColorPickerHelper.rgbToHex((int) (totalR / count), (int) (totalG / count), (int) (totalB / count));
    }

    public static List<String> gradientInteriorColors(String fromHex, String toHex, int steps) {
        int clamped = Math.max(1, Math.min(32, steps));
        String[] all = ColorPickerHelper.generateGradient(fromHex, toHex, clamped + 2);
        List<String> colors = new ArrayList<>();
        for (int i = 1; i < all.length - 1; i++) {
            colors.add(all[i].toUpperCase(Locale.ROOT));
        }
        return colors;
    }

    public static PreparedVariant prepareRecoloredVariant(SlotData source, String targetHex) throws IOException {
        int[] rgb = ColorPickerHelper.hexToRgb(targetHex);
        if (rgb == null) throw new IOException("Invalid gradient color '" + targetHex + "'.");
        byte[] primary = resolvePrimaryTexture(source);
        if (primary == null || primary.length == 0) {
            throw new IOException("Block '" + source.customId + "' has no texture data to recolor.");
        }
        byte[] baseTexture = recolorTexture(primary, rgb[0], rgb[1], rgb[2]);
        Map<String, byte[]> faces = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : source.faceTextures.entrySet()) {
            byte[] value = entry.getValue();
            if (value != null && value.length > 0) {
                faces.put(entry.getKey(), recolorTexture(value, rgb[0], rgb[1], rgb[2]));
            }
        }
        return new PreparedVariant(baseTexture, Collections.unmodifiableMap(faces));
    }

    public static String gradientVariantId(String sourceId, int stepIndex, String targetHex) {
        String hex = targetHex == null ? "808080" : targetHex.replace("#", "").toLowerCase(Locale.ROOT);
        return sourceId + "_gradient_" + stepIndex + "_" + hex;
    }

    public static String gradientDisplayName(String sourceDisplayName, int stepIndex, int totalSteps, String targetHex) {
        return sourceDisplayName + " Gradient " + stepIndex + "/" + totalSteps + " " + targetHex.toUpperCase(Locale.ROOT);
    }

    private static byte[] applyDress(byte[] source, String overlay) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("Could not decode source texture.");
        BufferedImage out = borrowVariantBuffer(image.getWidth(), image.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        switch (overlay) {
            case "mossy" -> {
                ColorPickerHelper.applyOverlay(out, 76, 133, 74, 0.24f);
                stipple(out, new Color(55, 96, 50, 145), 0.11f, 5, 7);
            }
            case "weathered" -> {
                ColorPickerHelper.applyOverlay(out, 122, 111, 91, 0.20f);
                stipple(out, new Color(90, 79, 64, 110), 0.09f, 4, 11);
            }
            case "glowing" -> {
                ColorPickerHelper.applyOverlay(out, 110, 220, 196, 0.18f);
                brighten(out, 1.12f);
                addEdgeGlow(out, new Color(190, 255, 242, 90));
            }
            case "frosted" -> {
                ColorPickerHelper.applyOverlay(out, 189, 223, 255, 0.28f);
                brighten(out, 1.05f);
                stipple(out, new Color(255, 255, 255, 100), 0.08f, 3, 5);
            }
            case "cracked" -> {
                ColorPickerHelper.applyOverlay(out, 86, 86, 86, 0.16f);
                addCracks(out, new Color(28, 28, 28, 170));
            }
            default -> throw new IOException("Unknown overlay '" + overlay + "'.");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(out, "PNG", baos);
        return baos.toByteArray();
    }

    private static byte[] recolorTexture(byte[] source, int targetR, int targetG, int targetB) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("Could not decode source texture.");
        BufferedImage out = borrowVariantBuffer(image.getWidth(), image.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ColorPickerHelper.recolorImage(out, targetR, targetG, targetB, 1.0f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(out, "PNG", baos);
        return baos.toByteArray();
    }

    private static BufferedImage borrowVariantBuffer(int width, int height) {
        BufferedImage cached = VARIANT_BUFFER.get();
        if (cached == null || cached.getWidth() != width || cached.getHeight() != height) {
            cached = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            VARIANT_BUFFER.set(cached);
        } else {
            Graphics2D clear = cached.createGraphics();
            clear.setComposite(AlphaComposite.Clear);
            clear.fillRect(0, 0, width, height);
            clear.dispose();
        }
        return cached;
    }

    private static void brighten(BufferedImage image, float factor) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) continue;
                int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * factor));
                int g = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * factor));
                int b = Math.min(255, Math.round((argb & 0xFF) * factor));
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    private static void stipple(BufferedImage image, Color color, float coverage, int stepX, int stepY) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y += Math.max(1, stepY)) {
            for (int x = 0; x < w; x += Math.max(1, stepX)) {
                int mix = Math.abs((x * 31) ^ (y * 17) ^ (w * 13) ^ (h * 7)) % 100;
                if (mix >= Math.round(coverage * 100f)) continue;
                blendPixel(image, x, y, color);
                if (x + 1 < w && mix % 3 == 0) blendPixel(image, x + 1, y, color);
                if (y + 1 < h && mix % 5 == 0) blendPixel(image, x, y + 1, color);
            }
        }
    }

    private static void addEdgeGlow(BufferedImage image, Color glow) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) continue;
                if (isEdgePixel(image, x, y)) {
                    blendPixel(image, x, y, glow);
                }
            }
        }
    }

    private static boolean isEdgePixel(BufferedImage image, int x, int y) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) continue;
                int nx = x + ox;
                int ny = y + oy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) return true;
                int na = (image.getRGB(nx, ny) >>> 24) & 0xFF;
                if (na == 0) return true;
            }
        }
        return false;
    }

    private static void addCracks(BufferedImage image, Color crack) {
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(crack);
        int w = image.getWidth();
        int h = image.getHeight();
        int lines = Math.max(2, Math.min(6, (w + h) / 20));
        for (int i = 0; i < lines; i++) {
            int startX = (i * 7 + w / 5) % Math.max(1, w);
            int startY = 0;
            int midX = Math.min(w - 1, Math.max(0, startX + ((i % 2 == 0) ? -w / 5 : w / 6)));
            int midY = Math.max(1, h / 2 + ((i % 3) - 1) * Math.max(1, h / 8));
            int endX = Math.min(w - 1, Math.max(0, midX + ((i % 2 == 0) ? w / 7 : -w / 6)));
            int endY = h - 1;
            g.drawLine(startX, startY, midX, midY);
            g.drawLine(midX, midY, endX, endY);
            if (midX + 1 < w) g.drawLine(midX, midY, Math.min(w - 1, midX + 1), Math.max(0, midY - Math.max(1, h / 6)));
        }
        g.dispose();
    }

    private static void blendPixel(BufferedImage image, int x, int y, Color overlay) {
        int argb = image.getRGB(x, y);
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) return;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        float alpha = overlay.getAlpha() / 255f;
        int nr = clamp(Math.round(r * (1f - alpha) + overlay.getRed() * alpha));
        int ng = clamp(Math.round(g * (1f - alpha) + overlay.getGreen() * alpha));
        int nb = clamp(Math.round(b * (1f - alpha) + overlay.getBlue() * alpha));
        image.setRGB(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String overlayLabel(String overlay) {
        String normalized = normalizeDressOverlay(overlay);
        if (normalized.isEmpty()) return "Dressed";
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1).replace('_', ' ');
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record PreparedVariant(byte[] baseTexture, Map<String, byte[]> faceTextures) {}

    public record CreationResult(SlotData slotData, boolean created, String targetId, String targetName) {}
}

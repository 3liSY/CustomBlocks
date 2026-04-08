package com.customblocks;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

/**
 * Central image utility for CustomBlocks.
 *
 * Supports PNG, JPG, GIF (including animated), BMP, and WebP (via TwelveMonkeys).
 * All images are:
 *  1. Downloaded with format rewriting for common CDNs.
 *  2. Converted to PNG.
 *  3. Padded to a square canvas (black letterbox) — ensures crisp block textures.
 *  4. Background-removed: white/transparent edges flood-filled to black.
 */
public final class ImageProcessor {

    private ImageProcessor() {}

    // TwelveMonkeys auto-registers WebP and other providers at class-load time.
    // We ensure it's triggered once during mod init:
    static {
        System.setProperty("java.awt.headless", "true");
        ImageIO.scanForPlugins();
    }

    private static final int WHITE_TOLERANCE  = 30;
    private static final int OPAQUE_THRESHOLD = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full pipeline: download → detect GIF → convert → pad to square → remove bg. */
    public static byte[] downloadAndProcess(String url) throws IOException, InterruptedException {
        byte[] raw = download(url);
        if (isAnimatedGif(raw)) {
            GifResult gif = processGif(raw);
            if (gif != null) return gif.stripPng; // caller sets animMeta separately
        }
        byte[] png = toPng(raw);
        png = padToSquare(png);
        return replaceBackground(png);
    }

    /** Same but skips background removal — used when caller handles it. */
    public static byte[] downloadAndConvert(String url) throws IOException, InterruptedException {
        byte[] raw = download(url);
        byte[] png = toPng(raw);
        return padToSquare(png);
    }

    /**
     * Download with CDN URL rewriting.
     * Supports Discord, Imgur, and handles redirects.
     * Max 10 MB.
     */
    public static byte[] download(String url) throws IOException, InterruptedException {
        String fetchUrl = url;

        // Discord CDN — append ?format=png so Discord auto-converts WebP to PNG
        if ((url.contains("cdn.discordapp.com") || url.contains("media.discordapp.net"))
                && url.toLowerCase().contains(".webp")) {
            fetchUrl = url.replaceAll("[?&]format=[^&]*", "");
            fetchUrl += (fetchUrl.contains("?") ? "&" : "?") + "format=png&quality=lossless";
        }
        // Imgur .webp → .png
        if (url.contains("i.imgur.com") && url.toLowerCase().endsWith(".webp"))
            fetchUrl = url.substring(0, url.length() - 5) + ".png";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fetchUrl))
                .header("User-Agent", "CustomBlocksMod/2.0")
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200)
            throw new IOException("HTTP " + res.statusCode() + " — check the URL is publicly accessible.");
        byte[] body = res.body();
        if (body.length > 10_485_760)
            throw new IOException("Image too large (max 10 MB, got " + (body.length / 1024) + " KB)");
        return body;
    }

    /**
     * Convert any supported format to PNG.
     * TwelveMonkeys on the classpath adds WebP, TIFF, PSD, and more.
     */
    public static byte[] toPng(byte[] raw) throws IOException {
        // Try ImageIO (handles PNG, JPG, GIF, BMP, WebP via TwelveMonkeys)
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) {
            // Fallback detection for common magic bytes
            String detected = detectFormat(raw);
            throw new IOException(
                "Could not read image" + (detected != null ? " (detected: " + detected + ")" : "") +
                ". Supported formats: PNG, JPG, GIF, BMP, WebP. " +
                "Try re-uploading as PNG or JPG if the issue persists.");
        }
        BufferedImage argb = toArgb(img);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Pad an image to a square canvas (black letterbox) so it renders cleanly on a cube face.
     * 600×450 → 600×600, centred, with 75 px black bars top and bottom.
     */
    public static byte[] padToSquare(byte[] pngBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        if (w == h) return pngBytes; // already square

        int size = Math.max(w, h);
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        int x = (size - w) / 2, y = (size - h) / 2;
        g.drawImage(img, x, y, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(canvas, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Smart background removal.
     * BFS flood-fill from all four corners, replacing white/transparent pixels with black.
     * Also converts any remaining fully-transparent pixels to black.
     */
    public static byte[] replaceBackground(byte[] pngBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage argb = toArgb(img);

        int[][] corners = {{0,0},{w-1,0},{0,h-1},{w-1,h-1}};
        boolean hasBgCorner = false;
        for (int[] c : corners)
            if (isBackground(argb.getRGB(c[0], c[1]))) { hasBgCorner = true; break; }

        if (hasBgCorner) {
            boolean[][] visited = new boolean[w][h];
            Queue<int[]> queue = new ArrayDeque<>();
            for (int[] c : corners) {
                if (!visited[c[0]][c[1]] && isBackground(argb.getRGB(c[0], c[1]))) {
                    visited[c[0]][c[1]] = true;
                    queue.add(c);
                }
            }
            int BLACK = 0xFF000000;
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            while (!queue.isEmpty()) {
                int[] px = queue.poll();
                int x = px[0], y = px[1];
                argb.setRGB(x, y, BLACK);
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx>=0 && nx<w && ny>=0 && ny<h && !visited[nx][ny]
                            && isBackground(argb.getRGB(nx, ny))) {
                        visited[nx][ny] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
            }
        }
        // Transparent → black
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (((argb.getRGB(x, y) >> 24) & 0xFF) < 10)
                    argb.setRGB(x, y, 0xFF000000);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /** Backwards-compat alias. */
    public static byte[] replaceWhiteBackground(byte[] pngBytes) throws IOException {
        return replaceBackground(pngBytes);
    }

    // ── Animated GIF ──────────────────────────────────────────────────────────

    public record GifResult(
            byte[] stripPng,    // all frames in a vertical strip
            String mcmeta,      // Minecraft animation JSON (write as <tex>.png.mcmeta)
            int    frameCount
    ) {}

    public static boolean isAnimatedGif(byte[] raw) {
        if (raw.length < 6) return false;
        // GIF magic
        if (!(raw[0]=='G' && raw[1]=='I' && raw[2]=='F')) return false;
        // Quick scan for multiple image descriptor blocks (0x2C)
        int count = 0;
        for (int i = 6; i < raw.length - 1 && count < 2; i++)
            if ((raw[i] & 0xFF) == 0x2C) count++;
        return count >= 2;
    }

    /**
     * Extract GIF frames into a vertical PNG strip + Minecraft animation .mcmeta JSON.
     * Returns null if the GIF has ≤ 1 frame (use regular processing instead).
     */
    public static GifResult processGif(byte[] gifBytes) {
        try {
            System.setProperty("java.awt.headless", "true");
            ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes));
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
            if (!it.hasNext()) return null;
            ImageReader reader = it.next();
            reader.setInput(iis, false);

            int numFrames = reader.getNumImages(true);
            if (numFrames <= 1) { reader.dispose(); return null; }

            // First frame to get dimensions
            BufferedImage frame0 = reader.read(0);
            int fw = frame0.getWidth(), fh = frame0.getHeight();
            int size = Math.max(fw, fh);

            // Accumulate frame images and delays
            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            java.util.List<Integer> delays = new java.util.ArrayList<>();
            BufferedImage composite = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);

                // Read frame delay from metadata (centiseconds → ticks; 5cs ≈ 1 tick)
                int delayCsecs = 10;
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    String fmt = meta.getNativeMetadataFormatName();
                    org.w3c.dom.Node root = meta.getAsTree(fmt);
                    org.w3c.dom.NodeList children = root.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        org.w3c.dom.Node child = children.item(j);
                        if ("GraphicControlExtension".equals(child.getNodeName())) {
                            org.w3c.dom.NamedNodeMap attrs = child.getAttributes();
                            org.w3c.dom.Node d = attrs.getNamedItem("delayTime");
                            if (d != null) delayCsecs = Integer.parseInt(d.getNodeValue());
                        }
                    }
                } catch (Exception ignored) {}

                int ticks = Math.max(1, delayCsecs / 5);
                delays.add(ticks);

                // Composite onto accumulation buffer
                Graphics2D cg = composite.createGraphics();
                cg.drawImage(frame, 0, 0, null);
                cg.dispose();

                frames.add(copyArgb(composite));
            }
            reader.dispose();

            // Build vertical strip (square frames)
            BufferedImage strip = new BufferedImage(size, size * numFrames, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = strip.createGraphics();
            sg.setColor(Color.BLACK);
            sg.fillRect(0, 0, size, size * numFrames);
            for (int i = 0; i < frames.size(); i++) {
                int dx = (size - fw) / 2;
                int dy = i * size + (size - fh) / 2;
                sg.drawImage(frames.get(i), dx, dy, null);
            }
            sg.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(strip, "PNG", baos);

            // Build mcmeta JSON
            StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"frames\":[");
            for (int i = 0; i < numFrames; i++) {
                if (i > 0) mcmeta.append(",");
                mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(delays.get(i)).append("}");
            }
            mcmeta.append("]}}");

            return new GifResult(baos.toByteArray(), mcmeta.toString(), numFrames);
        } catch (Exception e) {
            return null; // fall back to static treatment
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isBackground(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r >= (255 - WHITE_TOLERANCE) && g >= (255 - WHITE_TOLERANCE) && b >= (255 - WHITE_TOLERANCE);
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.createGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    private static BufferedImage copyArgb(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static String detectFormat(byte[] raw) {
        if (raw.length < 4) return null;
        if (raw[0]==(byte)0xFF && raw[1]==(byte)0xD8) return "JPEG";
        if (raw[0]==(byte)0x89 && raw[1]==0x50 && raw[2]==0x4E && raw[3]==0x47) return "PNG";
        if (raw[0]=='G' && raw[1]=='I' && raw[2]=='F') return "GIF";
        if (raw[0]=='R' && raw[1]=='I' && raw[2]=='F' && raw[3]=='F') return "RIFF/WebP";
        if (raw[0]==0x42 && raw[1]==0x4D) return "BMP";
        return null;
    }
}

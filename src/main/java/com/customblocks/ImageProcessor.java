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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

/**
 * Central image utility for CustomBlocks.
 *
 * Supports PNG, JPG, GIF (including animated), BMP, and WebP (via TwelveMonkeys).
 * All images are:
 *  1. Downloaded with format rewriting for common CDNs.
 *  2. Converted to PNG (alpha preserved).
 *  3. Padded to a square canvas (black letterbox).
 *  4. Background-removed: white/transparent edges flood-filled to black,
 *     anti-aliased fringe pixels expanded-to-black, then every remaining
 *     semi-transparent pixel composited against black → fully opaque result.
 */
public final class ImageProcessor {

    /**
     * Container for processed image data and its corresponding Minecraft animation metadata.
     */
    public record ProcessResult(byte[] bytes, String mcmeta, int frameCount) {
        public boolean isAnimated() { return frameCount > 1; }
    }

    // TwelveMonkeys auto-registers WebP and other providers at class-load time.
    static {
        System.setProperty("java.awt.headless", "true");
        ImageIO.scanForPlugins();
    }

    /** Flood-fill considers a pixel "background" if it is transparent or near-white. */
    private static final int WHITE_TOLERANCE  = 50;   // raised from 30 — catches cream/off-white bg
    private static final int OPAQUE_THRESHOLD = 200;  // alpha below this = treat as transparent
    /** Extra tolerance for the 1-pixel anti-fringe expand pass after flood-fill. */
    private static final int FRINGE_TOLERANCE = 80;   // catches anti-aliased edge pixels (R,G,B >= 175)

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Default texture size in pixels. Keeps packets safe. Override with commands. */
    public static final int DEFAULT_SIZE = 128;
    /** Hard cap — prevents packet kicks. */
    public static final int MAX_SIZE     = 256;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full pipeline with custom target size: download → convert → pad → remove bg → resize. */
    public static ProcessResult downloadAndProcess(String url, int targetSize) throws IOException, InterruptedException {
        byte[] raw = download(url);
        if (raw == null || raw.length == 0) throw new IOException("Downloaded empty data");

        try {
            // Detect animated format (GIF, APNG, animated WebP)
            if (isAnimatedImage(raw)) {
                ProcessResult anim = processAnimation(raw, targetSize);
                if (anim != null && anim.isAnimated()) {
                    if (isBrokenTexture(anim.bytes)) throw new IOException("Broken texture detected in animation.");
                    return anim;
                }
            }
            
            byte[] png = toPng(raw);
            png = padToSquare(png);
            png = replaceBackground(png);
            byte[] processed = resizeTo(png, targetSize);
            if (isBrokenTexture(processed)) throw new IOException("Broken texture detected.");
            return new ProcessResult(processed, null, 1);
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Error processing image from " + url, e);
            throw new IOException("Processing failed: " + e.getMessage());
        }
    }

    /** Full pipeline: download → detect GIF → convert → pad to square → remove bg → resize to DEFAULT_SIZE. */
    public static ProcessResult downloadAndProcess(String url) throws IOException, InterruptedException {
        return downloadAndProcess(url, DEFAULT_SIZE);
    }

    /** Same but skips background removal — used when caller handles it. */
    public static byte[] downloadAndConvert(String url, int targetSize) throws IOException, InterruptedException {
        byte[] raw = download(url);
        byte[] png = toPng(raw);
        png = padToSquare(png);
        return resizeTo(png, targetSize);
    }

    /** Same but skips background removal — used when caller handles it. */
    public static byte[] downloadAndConvert(String url) throws IOException, InterruptedException {
        return downloadAndConvert(url, DEFAULT_SIZE);
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
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IOException("HTTP " + res.statusCode() + " from " + url);
        byte[] body = res.body();
        if (body == null || body.length == 0) throw new IOException("Empty response from " + url);
        if (body.length > 20_971_520) throw new IOException("Image too large (max 20MB): " + body.length + " bytes");
        return body;
    }

    /**
     * Convert any supported format to PNG, preserving the alpha channel exactly.
     * TwelveMonkeys on the classpath adds WebP, TIFF, PSD, and more.
     */
    public static byte[] toPng(byte[] raw) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) {
            String detected = detectFormat(raw);
            throw new IOException(
                "Could not read image" + (detected != null ? " (detected: " + detected + ")" : "") +
                ". Supported formats: PNG, JPG, GIF, BMP, WebP. " +
                "Try re-uploading as PNG or JPG if the issue persists.");
        }
        // Use AlphaComposite.Src so transparent/semi-transparent pixels from
        // WebP/PNG are preserved exactly — replaceBackground handles the flatten.
        BufferedImage argb = toArgb(img);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Pad an image to a square canvas (black letterbox) so it renders cleanly on a cube face.
     * The letterbox area is fully opaque black.
     */
    public static byte[] padToSquare(byte[] pngBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        if (w == h) return pngBytes;

        int size = Math.max(w, h);
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Fill letterbox with opaque black
        g.setComposite(AlphaComposite.Src);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        // Draw the image preserving its alpha channel
        g.setComposite(AlphaComposite.SrcOver);
        int x = (size - w) / 2, y = (size - h) / 2;
        g.drawImage(img, x, y, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(canvas, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Smart background removal with three stages:
     *
     * 1. BFS flood-fill from all 4 corners — replaces white/transparent connected bg with black.
     * 2. Anti-fringe expand pass — 1-pixel dilation that catches anti-aliased edge pixels
     *    that the flood-fill leaves behind (near-white pixels adjacent to filled bg).
     * 3. Full alpha flatten — every remaining semi-transparent pixel is composited
     *    against black and made fully opaque. No transparency survives in the output.
     *
     * The result is always a 100% opaque image with a clean black background.
     */
    public static byte[] replaceBackground(byte[] pngBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage argb = toArgb(img);

        final int BLACK    = 0xFF000000;
        final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
        boolean[][] isBg   = new boolean[w][h];

        // ── Stage 1: BFS flood-fill from ALL border pixels ───────────────────
        // Seed from every pixel on the 4 edges (not just corners) so that white
        // outlines touching any edge are caught, not just the 4 corner pixels.
        Queue<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            if (!isBg[x][0]   && isBackground(argb.getRGB(x, 0)))   { isBg[x][0]   = true; queue.add(new int[]{x, 0});   }
            if (!isBg[x][h-1] && isBackground(argb.getRGB(x, h-1))) { isBg[x][h-1] = true; queue.add(new int[]{x, h-1}); }
        }
        for (int y = 1; y < h - 1; y++) {
            if (!isBg[0][y]   && isBackground(argb.getRGB(0, y)))   { isBg[0][y]   = true; queue.add(new int[]{0, y});   }
            if (!isBg[w-1][y] && isBackground(argb.getRGB(w-1, y))) { isBg[w-1][y] = true; queue.add(new int[]{w-1, y}); }
        }

        if (!queue.isEmpty()) {
            while (!queue.isEmpty()) {
                int[] px = queue.poll();
                int x = px[0], y = px[1];
                for (int[] d : DIRS) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h
                            && !isBg[nx][ny] && isBackground(argb.getRGB(nx, ny))) {
                        isBg[nx][ny] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
            }

            // ── Stage 2: Anti-fringe expand — 1-pixel dilation ───────────────
            // Any pixel directly adjacent to a filled background pixel that is
            // semi-transparent OR near-white is also marked background.
            // This eliminates the bright halo that anti-aliasing leaves at edges.
            boolean[][] fringe = new boolean[w][h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (isBg[x][y]) continue;
                    boolean adjacentToBg = false;
                    for (int[] d : DIRS) {
                        int nx = x + d[0], ny = y + d[1];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && isBg[nx][ny]) {
                            adjacentToBg = true;
                            break;
                        }
                    }
                    if (adjacentToBg && isFringe(argb.getRGB(x, y)))
                        fringe[x][y] = true;
                }
            }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (fringe[x][y]) isBg[x][y] = true;
        }

        // ── Stage 3: Paint bg black; composite every semi-transparent pixel ──
        // against black and make fully opaque. No transparency left in output.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isBg[x][y]) {
                    argb.setRGB(x, y, BLACK);
                    continue;
                }
                int pixel = argb.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a == 255) continue; // already fully opaque — skip
                // Premultiply alpha against black: out = src * (a/255), fully opaque
                int r = (int)(((pixel >> 16) & 0xFF) * a / 255.0 + 0.5);
                int g = (int)(((pixel >>  8) & 0xFF) * a / 255.0 + 0.5);
                int b = (int)( (pixel        & 0xFF) * a / 255.0 + 0.5);
                argb.setRGB(x, y, BLACK | (r << 16) | (g << 8) | b);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Returns true if this pixel should be treated as background during flood-fill.
     * Uses configurable CIE-Lab Delta E distance from pure white.
     */
    public static boolean isBackground(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (CustomBlocksConfig.bgRemovalTolerance <= 0) return false;
        double distance = deltaE(rgbToLab(argb), LAB_WHITE);
        return distance <= CustomBlocksConfig.bgRemovalTolerance;
    }

    /** Detects if the image is likely animated (GIF, APNG, animated WebP). */
    public static boolean isAnimatedImage(byte[] raw) {
        if (raw.length < 4) return false;
        // GIF87a/GIF89a
        if (raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F') return true;
        // WebP (RIFF + WEBPVP8X) - heuristic
        if (raw.length > 30 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F' 
            && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P') {
            // Check for ANIM chunk in WebP
            String head = new String(raw, 0, Math.min(200, raw.length));
            if (head.contains("ANIM")) return true;
        }
        // APNG (PNG signature + acTL chunk)
        if (raw[0] == (byte)0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47) {
            String head = new String(raw, 0, Math.min(200, raw.length), StandardCharsets.US_ASCII);
            if (head.contains("acTL")) return true;
        }
        return false;
    }

    /**
     * Extracts vertical frame count instantly from PNG headers without decoding the whole image.
     * Returns 1 if not a valid PNG or not animated.
     */
    public static int getVerticalFrames(byte[] raw) {
        if (raw == null || raw.length < 24) return 1;
        // Check PNG signature: 89 50 4E 47
        if (raw[0] != (byte)0x89 || raw[1] != 0x50 || raw[2] != 0x4E || raw[3] != 0x47) return 1;
        
        // IHDR chunk should follow immediately (12 bytes in, 4 byte data length, 4 byte type 'IHDR', then width/height)
        // Standard width offset: 16, height offset: 20
        int w = ((raw[16] & 0xFF) << 24) | ((raw[17] & 0xFF) << 16) | ((raw[18] & 0xFF) << 8) | (raw[19] & 0xFF);
        int h = ((raw[20] & 0xFF) << 24) | ((raw[21] & 0xFF) << 16) | ((raw[22] & 0xFF) << 8) | (raw[23] & 0xFF);
        
        if (w > 0 && h > w) {
            return h / w;
        }
        return 1;
    }

    // ── Animated Image Processing ────────────────────────────────────────────

    /**
     * Universal animation processor. Detects format (GIF, WebP, APNG), extracts frames,
     * applies disposal methods, and builds a vertical PNG strip with .mcmeta.
     */
    public static ProcessResult processAnimation(byte[] raw, int frameSize) {
        frameSize = Math.max(16, Math.min(MAX_SIZE, frameSize));
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            reader.setInput(iis);

            int numFrames = 0;
            try {
                numFrames = reader.getNumImages(true);
            } catch (IOException e) {
                reader.dispose();
                return null;
            }

            if (numFrames <= 1) {
                reader.dispose();
                return null;
            }

            // Cap frames to 64 to prevent server OOM
            numFrames = Math.min(numFrames, 64);

            BufferedImage firstFrame = reader.read(0);
            int fw = firstFrame.getWidth();
            int fh = firstFrame.getHeight();

            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            java.util.List<Integer> ticks = new java.util.ArrayList<>();
            BufferedImage composite = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gComp = composite.createGraphics();

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);
                int delayCsecs = 10; // default 100ms
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    // Seek delay in common metadata formats (GIF/WebP)
                    String[] names = meta.getMetadataFormatNames();
                    for (String name : names) {
                        org.w3c.dom.Node root = meta.getAsTree(name);
                        // Search for delayTime in nodes (simplified heuristic)
                        org.w3c.dom.NodeList nodes = root.getChildNodes();
                        for (int k = 0; k < nodes.getLength(); k++) {
                            if (nodes.item(k).getNodeName().contains("Control")) {
                                org.w3c.dom.NamedNodeMap attrs = nodes.item(k).getAttributes();
                                org.w3c.dom.Node delayNode = attrs.getNamedItem("delayTime");
                                if (delayNode != null) delayCsecs = Integer.parseInt(delayNode.getNodeValue());
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Convert csecs to Game Ticks (1 tick = 50ms = 5 csecs)
                ticks.add(Math.max(1, delayCsecs / 5));

                // Minimal disposal handling: overdraw
                gComp.setComposite(AlphaComposite.SrcOver);
                gComp.drawImage(frame, 0, 0, null);
                frames.add(copyArgb(composite));
            }
            gComp.dispose();
            reader.dispose();

            if (frames.isEmpty()) return null;

            // Build deterministic vertical strip
            BufferedImage strip = new BufferedImage(frameSize, frameSize * frames.size(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D gStrip = strip.createGraphics();
            gStrip.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            gStrip.setComposite(java.awt.AlphaComposite.Clear);
            gStrip.fillRect(0, 0, frameSize, frameSize * frames.size());
            gStrip.setComposite(java.awt.AlphaComposite.SrcOver);

            for (int i = 0; i < frames.size(); i++) {
                gStrip.drawImage(frames.get(i), 0, i * frameSize, frameSize, frameSize, null);
            }
            gStrip.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(strip, "PNG", baos);

            // Generate mcmetas with "interpolate: true" for Fluid Motion (Royalty Standard)
            StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"interpolate\":true,\"frames\":[");
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) mcmeta.append(",");
                mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(ticks.get(i)).append("}");
            }
            mcmeta.append("]}}");

            return new ProcessResult(baos.toByteArray(), mcmeta.toString(), frames.size());
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Animation processing failed", e);
            return null;
        }
    }

    /**
     * Scale image to exactly targetSize × targetSize pixels using bicubic interpolation.
     * targetSize is clamped to [16, MAX_SIZE].
     * If the image is already the right size, returns the input unchanged.
     */
    public static byte[] resizeTo(byte[] pngBytes, int targetSize) throws IOException {
        targetSize = Math.max(16, Math.min(MAX_SIZE, targetSize));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        if (img.getWidth() == targetSize && img.getHeight() == targetSize) return pngBytes;
        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(img, 0, 0, targetSize, targetSize, null);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(out, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Checks if a texture is 'Broken' (corrupted, missing, or failed download).
     * Detects:
     *  1. Classic MC checkerboard (Pure Magenta + Pure Black)
     *  2. High concentration of Pure Magenta (Broken download/export)
     *  3. Pure/Near-Pure Black (Failed processing or empty buffer)
     */
    public static boolean isBrokenTexture(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return true;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return true;
            int w = img.getWidth(), h = img.getHeight();
            int magentaPixels = 0;   // pure magenta (255,0,255)
            int blackPixels   = 0;   // pure black (0,0,0)
            int totalPixels   = w * h;
            if (totalPixels == 0) return true;
            double[] LAB_MAGENTA = rgbToLab(0xFFFF00FF);
            double[] LAB_BLACK = rgbToLab(0xFF000000);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    if (a < 5) continue; // ignore transparency
                    double[] lab = rgbToLab(argb);
                    if (deltaE(lab, LAB_MAGENTA) <= 12.0) {
                        magentaPixels++;
                    } else if (deltaE(lab, LAB_BLACK) <= 12.0) {
                        blackPixels++;
                    }
                }
            }
            // Corruption signals:
            float magentaRatio = (float) magentaPixels / totalPixels;
            float blackRatio   = (float) blackPixels / totalPixels;
            
            // Standard MC missing-texture check (magenta+black checkerboard)
            if (magentaRatio > 0.20 && (magentaRatio + blackRatio) > 0.75) return true;
            // High-concentration magenta (export failure)
            if (magentaRatio > 0.5) return true;
            // Near-total black (download failure — only flag near-100% black)
            if (blackRatio > 0.98) return true;
            
            return false;
        } catch (Exception e) {
            com.customblocks.gui.GuiManager.logError();
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double[] rgbToLab(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        double rF = r / 255.0;
        double gF = g / 255.0;
        double bF = b / 255.0;

        rF = (rF > 0.04045) ? Math.pow((rF + 0.055) / 1.055, 2.4) : (rF / 12.92);
        gF = (gF > 0.04045) ? Math.pow((gF + 0.055) / 1.055, 2.4) : (gF / 12.92);
        bF = (bF > 0.04045) ? Math.pow((bF + 0.055) / 1.055, 2.4) : (bF / 12.92);

        rF *= 100.0; gF *= 100.0; bF *= 100.0;

        double x = rF * 0.4124 + gF * 0.3576 + bF * 0.1805;
        double y = rF * 0.2126 + gF * 0.7152 + bF * 0.0722;
        double z = rF * 0.0193 + gF * 0.1192 + bF * 0.9505;

        // D65 reference
        x /= 95.047; y /= 100.000; z /= 108.883;

        x = (x > 0.008856) ? Math.cbrt(x) : (7.787 * x) + (16.0 / 116.0);
        y = (y > 0.008856) ? Math.cbrt(y) : (7.787 * y) + (16.0 / 116.0);
        z = (z > 0.008856) ? Math.cbrt(z) : (7.787 * z) + (16.0 / 116.0);

        double L = (116.0 * y) - 16.0;
        double a = 500.0 * (x - y);
        double b_star = 200.0 * (y - z);

        return new double[]{L, a, b_star};
    }

    private static double deltaE(double[] lab1, double[] lab2) {
        return Math.sqrt(Math.pow(lab1[0] - lab2[0], 2) + Math.pow(lab1[1] - lab2[1], 2) + Math.pow(lab1[2] - lab2[2], 2));
    }

    private static final double[] LAB_WHITE = rgbToLab(0xFFFFFFFF);

    /**
     * Returns true if this pixel is a fringe candidate (anti-aliased edge pixel).
     * Uses a higher tolerance than isBackground to catch subtly-bright edge pixels.
     */
    private static boolean isFringe(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (CustomBlocksConfig.bgRemovalTolerance <= 0) return false;
        double distance = deltaE(rgbToLab(argb), LAB_WHITE);
        // Fringe uses a slighter wider tolerance, e.g. + 15
        return distance <= (CustomBlocksConfig.bgRemovalTolerance + 15);
    }

    /**
     * Convert any BufferedImage to TYPE_INT_ARGB, preserving the alpha channel exactly.
     * Uses AlphaComposite.Src so semi-transparent pixels are copied as-is.
     */
    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // AlphaComposite.Src copies pixels verbatim, preserving source alpha
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
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
        return "Unknown (" + String.format("%02X %02X %02X %02X", raw[0], raw[1], raw[2], raw[3]) + ")";
    }
}

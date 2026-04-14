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
 *  2. Converted to PNG (alpha preserved).
 *  3. Padded to a square canvas (black letterbox).
 *  4. Background-removed: white/transparent edges flood-filled to black,
 *     anti-aliased fringe pixels expanded-to-black, then every remaining
 *     semi-transparent pixel composited against black → fully opaque result.
 */
public final class ImageProcessor {

    private ImageProcessor() {}

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
    public static byte[] downloadAndProcess(String url, int targetSize) throws IOException, InterruptedException {
        byte[] raw = download(url);
        // Handle any animated format (GIF, APNG, animated WebP)
        if (isAnimatedImage(raw)) {
            GifResult gif = processAnimatedImage(raw, targetSize);
            if (gif != null) return gif.stripPng;
        }
        // AVIF detection — not yet supported, give a clear error
        if (isAvif(raw)) {
            throw new IOException(
                "AVIF format is not yet supported. Please convert your AVIF to GIF or APNG first. " +
                "You can use ezgif.com or ffmpeg: ffmpeg -i input.avif output.gif");
        }
        byte[] png = toPng(raw);
        png = padToSquare(png);
        png = replaceBackground(png);
        return resizeTo(png, targetSize);
    }

    /** Full pipeline: download → detect GIF → convert → pad to square → remove bg → resize to DEFAULT_SIZE. */
    public static byte[] downloadAndProcess(String url) throws IOException, InterruptedException {
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
        if (!(raw[0]=='G' && raw[1]=='I' && raw[2]=='F')) return false;
        int count = 0;
        for (int i = 6; i < raw.length - 1 && count < 2; i++)
            if ((raw[i] & 0xFF) == 0x2C) count++;
        return count >= 2;
    }

    /**
     * Returns true for any animated/multi-frame image format:
     * GIF, APNG, animated WebP (WEBPVP8X with ANIM chunk), AVIF sequences.
     */
    public static boolean isAnimatedImage(byte[] raw) {
        if (raw.length < 12) return false;
        return isAnimatedGif(raw) || isAnimatedPng(raw) || isAnimatedWebP(raw);
    }

    /**
     * Detect APNG — a PNG that contains the 'acTL' animation control chunk.
     * Normal PNGs only have IHDR/IDAT/etc; APNG also has acTL + fcTL + fdAT.
     */
    public static boolean isAnimatedPng(byte[] raw) {
        if (raw.length < 8) return false;
        // PNG magic bytes
        if (!( raw[0]==(byte)0x89 && raw[1]==0x50 && raw[2]==0x4E && raw[3]==0x47
            && raw[4]==0x0D && raw[5]==0x0A && raw[6]==0x1A && raw[7]==0x0A)) return false;
        // Scan for 'acTL' chunk type (bytes: 0x61 0x63 0x54 0x4C)
        for (int i = 8; i < raw.length - 7; i++) {
            if (raw[i]=='a' && raw[i+1]=='c' && raw[i+2]=='T' && raw[i+3]=='L') return true;
        }
        return false;
    }

    /**
     * Detect animated WebP — has RIFF header, WEBP marker, and an ANIM chunk.
     */
    public static boolean isAnimatedWebP(byte[] raw) {
        if (raw.length < 16) return false;
        if (!(raw[0]=='R' && raw[1]=='I' && raw[2]=='F' && raw[3]=='F')) return false;
        if (!(raw[8]=='W' && raw[9]=='E' && raw[10]=='B' && raw[11]=='P')) return false;
        // Look for 'ANIM' chunk
        for (int i = 12; i < Math.min(raw.length - 4, 512); i++) {
            if (raw[i]=='A' && raw[i+1]=='N' && raw[i+2]=='I' && raw[i+3]=='M') return true;
        }
        return false;
    }

    /**
     * Process any animated image (GIF, APNG, animated WebP) into a vertical PNG strip + mcmeta.
     * For APNG and animated WebP, attempts to read via ImageIO (TwelveMonkeys adds WebP support).
     * Returns null if the image has ≤1 frame or cannot be processed.
     */
    public static GifResult processAnimatedImage(byte[] raw, int frameSize) {
        if (isAnimatedGif(raw))   return processGif(raw, frameSize);
        if (isAnimatedPng(raw))   return processApng(raw, frameSize);
        if (isAnimatedWebP(raw))  return processGif(raw, frameSize); // ImageIO handles multi-frame WebP via TwelveMonkeys
        return null;
    }

    /**
     * Process an APNG into a vertical strip + mcmeta, same pipeline as processGif.
     * Uses ImageIO's GIF reader path — APNG requires TwelveMonkeys or similar plugin.
     * Falls back gracefully: if only 1 frame is readable, returns null (use static pipeline).
     */
    public static GifResult processApng(byte[] pngBytes, int frameSize) {
        frameSize = Math.max(16, Math.min(MAX_SIZE, frameSize));
        try {
            ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(pngBytes));
            // Try PNG reader that supports APNG (TwelveMonkeys or JDK)
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("png");
            ImageReader reader = null;
            while (it.hasNext()) {
                ImageReader r = it.next();
                r.setInput(iis, false);
                try {
                    if (r.getNumImages(true) > 1) { reader = r; break; }
                } catch (Exception ignored) { r.dispose(); }
            }
            if (reader == null) return null;

            int numFrames = Math.min(reader.getNumImages(true), 64);
            if (numFrames <= 1) { reader.dispose(); return null; }

            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            int defaultDelay = 2; // ticks
            for (int i = 0; i < numFrames; i++) {
                try { frames.add(toArgb(reader.read(i))); } catch (Exception e) { break; }
            }
            reader.dispose();
            if (frames.size() <= 1) return null;

            // Build strip
            BufferedImage strip = new BufferedImage(frameSize, frameSize * frames.size(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = strip.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg.setColor(java.awt.Color.BLACK);
            sg.fillRect(0, 0, frameSize, frameSize * frames.size());
            for (int i = 0; i < frames.size(); i++)
                sg.drawImage(frames.get(i), 0, i * frameSize, frameSize, frameSize, null);
            sg.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(strip, "PNG", baos);

            StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"frames\":[");
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) mcmeta.append(",");
                mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(defaultDelay).append("}");
            }
            mcmeta.append("]}}");
            return new GifResult(baos.toByteArray(), mcmeta.toString(), frames.size());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract GIF frames into a vertical PNG strip + Minecraft animation .mcmeta JSON.
     * Returns null if the GIF has <= 1 frame (use regular processing instead).
     */
    public static GifResult processGif(byte[] gifBytes) {
        return processGif(gifBytes, DEFAULT_SIZE);
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

    /** processGif with explicit frame size. */
    public static GifResult processGif(byte[] gifBytes, int frameSize) {
        frameSize = Math.max(16, Math.min(MAX_SIZE, frameSize));
        try {
            System.setProperty("java.awt.headless", "true");
            ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes));
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
            if (!it.hasNext()) return null;
            ImageReader reader = it.next();
            reader.setInput(iis, false);

            int numFrames = reader.getNumImages(true);
            if (numFrames <= 1) { reader.dispose(); return null; }
            // Cap frames to prevent OOM with huge GIFs
            numFrames = Math.min(numFrames, 64);

            BufferedImage frame0 = reader.read(0);
            int fw = frame0.getWidth(), fh = frame0.getHeight();

            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            java.util.List<Integer> delays = new java.util.ArrayList<>();
            BufferedImage composite = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);

            // Track previous frame geometry for disposal handling
            int prevFX = 0, prevFY = 0, prevFW = fw, prevFH = fh;
            String prevDisposal = "doNotDispose";

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);
                int delayCsecs = 10;
                int frameX = 0, frameY = 0;
                String disposal = "doNotDispose";
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    String fmt = meta.getNativeMetadataFormatName();
                    org.w3c.dom.Node root = meta.getAsTree(fmt);
                    org.w3c.dom.NodeList children = root.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        org.w3c.dom.Node child = children.item(j);
                        String nodeName = child.getNodeName();
                        if ("GraphicControlExtension".equals(nodeName)) {
                            org.w3c.dom.NamedNodeMap attrs = child.getAttributes();
                            org.w3c.dom.Node d = attrs.getNamedItem("delayTime");
                            if (d != null) delayCsecs = Integer.parseInt(d.getNodeValue());
                            org.w3c.dom.Node dm = attrs.getNamedItem("disposalMethod");
                            if (dm != null) disposal = dm.getNodeValue();
                        } else if ("ImageDescriptor".equals(nodeName)) {
                            org.w3c.dom.NamedNodeMap attrs = child.getAttributes();
                            org.w3c.dom.Node lp = attrs.getNamedItem("imageLeftPosition");
                            org.w3c.dom.Node tp = attrs.getNamedItem("imageTopPosition");
                            if (lp != null) frameX = Integer.parseInt(lp.getNodeValue());
                            if (tp != null) frameY = Integer.parseInt(tp.getNodeValue());
                        }
                    }
                } catch (Exception ignored) {}
                int ticks = Math.max(1, delayCsecs / 5);
                delays.add(ticks);

                // Apply previous frame's disposal method before drawing this frame
                if ("restoreToBackgroundColor".equals(prevDisposal)) {
                    // Clear the region the previous frame occupied
                    Graphics2D cg = composite.createGraphics();
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(prevFX, prevFY, prevFW, prevFH);
                    cg.dispose();
                } else if ("restoreToPrevious".equals(prevDisposal)) {
                    // Full clear — safest fallback
                    Graphics2D cg = composite.createGraphics();
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(0, 0, fw, fh);
                    cg.dispose();
                }
                // "doNotDispose" — keep composite as-is (accumulate)

                // Draw this frame at its correct offset position
                Graphics2D cg = composite.createGraphics();
                cg.setComposite(AlphaComposite.SrcOver);
                cg.drawImage(frame, frameX, frameY, null);
                cg.dispose();
                frames.add(copyArgb(composite));

                prevFX = frameX; prevFY = frameY;
                prevFW = frame.getWidth(); prevFH = frame.getHeight();
                prevDisposal = disposal;
            }
            reader.dispose();

            // Build strip at target frameSize
            BufferedImage strip = new BufferedImage(frameSize, frameSize * numFrames, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = strip.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg.setColor(Color.BLACK);
            sg.fillRect(0, 0, frameSize, frameSize * numFrames);
            for (int i = 0; i < frames.size(); i++) {
                sg.drawImage(frames.get(i), 0, i * frameSize, frameSize, frameSize, null);
            }
            sg.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(strip, "PNG", baos);

            StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"frames\":[");
            for (int i = 0; i < numFrames; i++) {
                if (i > 0) mcmeta.append(",");
                mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(delays.get(i)).append("}");
            }
            mcmeta.append("]}}");

            return new GifResult(baos.toByteArray(), mcmeta.toString(), numFrames);
        } catch (Exception e) {
            return null;
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
     * Returns true if this pixel should be treated as background during flood-fill.
     * Uses configurable CIE-Lab Delta E distance from pure white.
     */
    private static boolean isBackground(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (CustomBlocksConfig.bgRemovalTolerance <= 0) return false;
        double distance = deltaE(rgbToLab(argb), LAB_WHITE);
        return distance <= CustomBlocksConfig.bgRemovalTolerance;
    }

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

    /**
     * Checks if a texture is mostly alternating magenta (#FF00FF) and black (#000000).
     */
    public static boolean isBrokenTexture(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return true;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return true;
            int w = img.getWidth(), h = img.getHeight();
            int magentaPixels = 0;   // pure magenta (255,0,255) — the MC missing texture color
            int blackPixels   = 0;   // pure black (0,0,0)
            int totalPixels   = w * h;
            if (totalPixels == 0) return true;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    if (a < 10) continue; // skip fully transparent pixels
                    if (r >= 240 && g <= 15 && b >= 240) {
                        magentaPixels++;
                    } else if (r <= 15 && g <= 15 && b <= 15) {
                        blackPixels++;
                    }
                }
            }
            // Classic MC missing-texture checkerboard: magenta + black squares
            int checkerboard = magentaPixels + blackPixels;
            if (magentaPixels > 0 && checkerboard > (totalPixels * 0.4)) return true;
            // Fully black texture (failed download or cleared texture)
            if (blackPixels > (totalPixels * 0.95)) return true;
            return false;
        } catch (Exception e) {
            com.customblocks.gui.GuiManager.logError();
            return true; // if we can't even read it, it's broken
        }
    }

    /**
     * Detect AVIF format (ISO Base Media File Format with 'avif' or 'avis' brand).
     * AVIF starts with a ftyp box containing 'avif' or 'avis' as the major brand.
     */
    public static boolean isAvif(byte[] raw) {
        if (raw.length < 12) return false;
        // Check for ftyp box: bytes 4-7 = "ftyp", bytes 8-11 = brand
        if (!(raw[4]=='f' && raw[5]=='t' && raw[6]=='y' && raw[7]=='p')) return false;
        String brand = new String(raw, 8, Math.min(4, raw.length - 8));
        return brand.startsWith("avif") || brand.startsWith("avis");
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

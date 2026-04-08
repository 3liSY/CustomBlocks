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

        // ── Stage 1: BFS flood-fill from corners ─────────────────────────────
        int[][] corners = {{0,0},{w-1,0},{0,h-1},{w-1,h-1}};
        boolean hasBgCorner = false;
        for (int[] c : corners)
            if (isBackground(argb.getRGB(c[0], c[1]))) { hasBgCorner = true; break; }

        if (hasBgCorner) {
            Queue<int[]> queue = new ArrayDeque<>();
            for (int[] c : corners) {
                if (!isBg[c[0]][c[1]] && isBackground(argb.getRGB(c[0], c[1]))) {
                    isBg[c[0]][c[1]] = true;
                    queue.add(c);
                }
            }
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
     * Extract GIF frames into a vertical PNG strip + Minecraft animation .mcmeta JSON.
     * Returns null if the GIF has <= 1 frame (use regular processing instead).
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

            BufferedImage frame0 = reader.read(0);
            int fw = frame0.getWidth(), fh = frame0.getHeight();
            int size = Math.max(fw, fh);

            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            java.util.List<Integer> delays = new java.util.ArrayList<>();
            BufferedImage composite = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);

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

                Graphics2D cg = composite.createGraphics();
                cg.drawImage(frame, 0, 0, null);
                cg.dispose();
                frames.add(copyArgb(composite));
            }
            reader.dispose();

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

    /**
     * Returns true if this pixel should be treated as background during flood-fill.
     * A pixel is background if it is semi-transparent OR near-white (with WHITE_TOLERANCE).
     */
    private static boolean isBackground(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r >= (255 - WHITE_TOLERANCE)
            && g >= (255 - WHITE_TOLERANCE)
            && b >= (255 - WHITE_TOLERANCE);
    }

    /**
     * Returns true if this pixel is a fringe candidate (anti-aliased edge pixel).
     * Uses a higher tolerance than isBackground to catch subtly-bright edge pixels.
     */
    private static boolean isFringe(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;  // semi-transparent is always fringe
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r >= (255 - FRINGE_TOLERANCE)
            && g >= (255 - FRINGE_TOLERANCE)
            && b >= (255 - FRINGE_TOLERANCE);
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
        return null;
    }
}

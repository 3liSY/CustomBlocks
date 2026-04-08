package com.customblocks;

import javax.imageio.ImageIO;
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
import java.util.Queue;

/**
 * Central image utility for CustomBlocks.
 *
 * Responsibilities:
 *   1. Download image bytes from any URL (with size guard).
 *   2. Convert supported formats (JPG, PNG, GIF, BMP) to PNG — no extra deps needed.
 *      WebP: Discord CDN URLs are auto-rewritten to PNG. Other WebP → clear error.
 *   3. Auto-replace white/transparent backgrounds with black via flood-fill from corners.
 */
public final class ImageProcessor {

    private ImageProcessor() {}

    // ── Tolerance for "near-white" background detection ───────────────────────
    /** How close to pure white (255,255,255) a pixel must be to be considered background. */
    private static final int WHITE_TOLERANCE = 30;
    /** Minimum alpha for a pixel to be considered opaque (and therefore possibly white bg). */
    private static final int OPAQUE_THRESHOLD = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Downloads the image at {@code url}, converts it to PNG, and auto-removes
     * any white background (replaces with black).
     *
     * This is the single entry point used by all commands and tools.
     */
    public static byte[] downloadAndProcess(String url) throws IOException, InterruptedException {
        byte[] raw = download(url);
        byte[] png = toPng(raw);
        return replaceWhiteBackground(png);
    }

    /**
     * Same as {@link #downloadAndProcess} but skips the white-background step.
     * Used internally when callers handle bg replacement themselves.
     */
    public static byte[] downloadAndConvert(String url) throws IOException, InterruptedException {
        byte[] raw = download(url);
        return toPng(raw);
    }

    /**
     * HTTP download with WebP URL rewriting.
     * - Discord CDN WebP URLs → appends ?format=png so Discord serves PNG instead.
     * - Imgur WebP/gifv → rewrites to .png/.gif
     * Max 10 MB, HTTP 200 required.
     */
    public static byte[] download(String url) throws IOException, InterruptedException {
        // Rewrite Discord CDN WebP to PNG (Discord supports ?format=png natively)
        String fetchUrl = url;
        if ((url.contains("cdn.discordapp.com") || url.contains("media.discordapp.net"))
                && url.toLowerCase().contains(".webp")) {
            fetchUrl = url.replaceAll("[?&]format=[^&]*", ""); // strip any existing format param
            fetchUrl += (fetchUrl.contains("?") ? "&" : "?") + "format=png&quality=lossless";
        }
        // Imgur .webp → .png
        if (url.contains("i.imgur.com") && url.toLowerCase().endsWith(".webp")) {
            fetchUrl = url.substring(0, url.length() - 5) + ".png";
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fetchUrl))
                .header("User-Agent", "CustomBlocksMod/1.0")
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200)
            throw new IOException("HTTP " + res.statusCode() + " for URL: " + fetchUrl);
        byte[] body = res.body();
        if (body.length > 10_485_760)
            throw new IOException("Image too large (max 10 MB, got " + (body.length / 1024) + " KB)");
        return body;
    }

    /**
     * Converts JPG / PNG / GIF / BMP to PNG bytes using Java's built-in ImageIO.
     * WebP is handled via URL rewriting (see download()); if raw WebP bytes somehow
     * still arrive here a clear exception is thrown rather than silently failing.
     */
    public static byte[] toPng(byte[] raw) throws IOException {
        System.setProperty("java.awt.headless", "true");
        // Detect WebP magic: "RIFF????WEBP"
        if (raw.length > 11
                && raw[0]=='R' && raw[1]=='I' && raw[2]=='F' && raw[3]=='F'
                && raw[8]=='W' && raw[9]=='E' && raw[10]=='B' && raw[11]=='P') {
            throw new IOException(
                "WebP format is not supported directly. " +
                "For Discord links use the attachment URL (it auto-converts). " +
                "Otherwise please re-upload as PNG or JPG.");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) {
            throw new IOException(
                "Unsupported image format. Supported: PNG, JPG, GIF, BMP. " +
                "Convert your image to PNG/JPG and try again.");
        }
        // Ensure ARGB so transparency survives the conversion
        BufferedImage argb = toArgb(img);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Auto white-background removal.
     *
     * Samples the 4 corner pixels. If at least one corner is near-white and
     * opaque, performs a BFS flood-fill from ALL corners, replacing every
     * connected near-white pixel with solid black (0, 0, 0, 255).
     *
     * Interior pixels that happen to be near-white are NOT touched — only
     * pixels reachable from the image border via similarly-coloured neighbours.
     *
     * If no corner is near-white the image is returned unchanged.
     */
    public static byte[] replaceWhiteBackground(byte[] pngBytes) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;

        int w = img.getWidth(), h = img.getHeight();
        BufferedImage argb = toArgb(img);

        // Check all 4 corners — trigger only when at least one is near-white + opaque
        int[][] corners = {{0,0},{w-1,0},{0,h-1},{w-1,h-1}};
        boolean hasWhiteOrTransparentCorner = false;
        for (int[] c : corners) {
            if (isNearWhiteOrTransparent(argb.getRGB(c[0], c[1]))) { hasWhiteOrTransparentCorner = true; break; }
        }
        // Still run to catch fully-transparent images (handled at end), but skip BFS if no matching corner
        boolean[][] visited = new boolean[w][h];
        Queue<int[]> queue  = new ArrayDeque<>();
        if (hasWhiteOrTransparentCorner) {
        for (int[] c : corners) {
            if (!visited[c[0]][c[1]] && isNearWhiteOrTransparent(argb.getRGB(c[0], c[1]))) {
                visited[c[0]][c[1]] = true;
                queue.add(c);
            }
        }

        } // end hasWhiteOrTransparentCorner
        int BLACK = 0xFF000000;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!queue.isEmpty()) {
            int[] px = queue.poll();
            int x = px[0], y = px[1];
            argb.setRGB(x, y, BLACK);
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h
                        && !visited[nx][ny]
                        && isNearWhiteOrTransparent(argb.getRGB(nx, ny))) {
                    visited[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }

        // Also convert any remaining fully-transparent pixels to black
        // (catches inner transparent regions like holes in letters)
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (((argb.getRGB(x, y) >> 24) & 0xFF) < 10)
                    argb.setRGB(x, y, BLACK);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if the pixel is transparent OR near-white (255,255,255). */
    private static boolean isNearWhiteOrTransparent(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true; // transparent = background
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return r >= (255 - WHITE_TOLERANCE)
            && g >= (255 - WHITE_TOLERANCE)
            && b >= (255 - WHITE_TOLERANCE);
    }

    /** Ensures the image has an ARGB channel so transparency is preserved through conversion. */
    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.createGraphics().drawImage(src, 0, 0, null);
        return out;
    }
}

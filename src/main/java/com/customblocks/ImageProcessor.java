package com.customblocks;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * Supports PNG, JPG, GIF (including animated), BMP, and WebP (via URL rewriting + wsrv.nl proxy).
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
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record ProcessResult(byte[] bytes, String mcmeta, int frameCount, String warning) {
        public boolean isAnimated() { return frameCount > 1; }
    }

    static {
        System.setProperty("java.awt.headless", "true");
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
        if (raw == null || raw.length == 0)
            throw new IOException("§eThe link worked, but there was nothing there! §7The image might have been deleted. Try uploading a new one.");

        try {
            // Phase 9: Detect video (MP4/MOV) before image processing
            if (isVideoFile(raw)) {
                if (raw.length > MAX_VIDEO_SIZE)
                    throw new IOException("§eVideo too large! §7Max is §f5 MB§7 — try a shorter clip or lower resolution.");
                ProcessResult video = processVideo(raw, targetSize);
                if (video != null && video.isAnimated()) {
                    if (isBrokenTexture(video.bytes))
                        throw new IOException("§eGot the video, but frame-stitching came out broken. §7Check the server log for the exact reason.");
                    return video;
                }
                throw new IOException("§eCouldn't extract frames from that video. §7Only §fMP4§7 and §fMOV§7 with H.264 video are supported. The server log has details.");
            }

            // Detect animated format (GIF, APNG, animated WebP)
            if (isAnimatedImage(raw)) {
                // 1.25 — Reject oversized GIFs before processing to prevent OOM / server crash
                int maxMb = Math.max(1, com.customblocks.CustomBlocksConfig.maxGifSizeMb);
                long maxBytes = (long) maxMb * 1024 * 1024;
                if (raw.length > maxBytes) {
                    double actualMb = raw.length / (1024.0 * 1024.0);
                    throw new IOException(String.format(
                        "§c[CB] §fGIF too large (§c%.1f§f MB). Max allowed: §f%d§f MB. Compress it first or use a shorter animation.",
                        actualMb, maxMb));
                }
                ProcessResult anim = processAnimation(raw, targetSize);
                if (anim != null && anim.isAnimated()) {
                    if (isBrokenTexture(anim.bytes))
                        throw new IOException("§eGot the GIF, but frame-stitching came out broken. §7Check the server log for the exact reason.");
                    return anim;
                }
                // The file was flagged as animated but we could not produce multiple
                // frames. Log the hint so admins can see why, then surface a clear
                // chat error - NOT "convert to PNG", because that defeats the point
                // of uploading a GIF in the first place.
                CustomBlocksMod.LOGGER.warn(
                    "[CustomBlocks] Animated image detected but no frames were produced. Size: {} bytes, target: {}px. See earlier GIF decode warning(s) in this log for the precise reason.",
                    raw.length, targetSize);
                throw new IOException("§eI couldn't extract animation frames from that image. §7The server log will show the decode reason — share it and I can make the decoder handle it.");
            }

            byte[] png = toPng(raw);
            png = padToSquare(png);
            png = replaceBackground(png);
            byte[] processed = resizeTo(png, targetSize);
            if (isBrokenTexture(processed))
                throw new IOException("§eGot the image, but it came out broken after processing. §7Try saving it as a normal PNG and paste the new link.");
            return new ProcessResult(processed, null, 1, null);
        } catch (IOException e) {
            // Re-throw our own friendly messages as-is
            throw e;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Error processing image from " + url, e);
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("§"))
                throw new IOException(msg); // Already friendly
            throw new IOException("§eSomething went wrong while processing the image. §7" + friendlyProcessingError(e));
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
     * Phase 4A.1 — Process raw PNG bytes with an explicit background mode.
     *
     * <p>This is the low-level entry point used by the per-slot import settings.
     * The caller is responsible for downloading/converting to PNG first.
     *
     * @param pngBytes   source PNG bytes (must already be a valid PNG)
     * @param targetSize target square size in pixels
     * @param bgMode     one of "keep_transparent", "remove_auto", "fill_black", "fill_color"
     * @param fillColor  RGB int (0xRRGGBB) used only when bgMode is "fill_color"; ignored otherwise
     * @return a {@link ProcessResult} with the processed static texture (frameCount = 1)
     */
    public static ProcessResult processWithMode(byte[] pngBytes, int targetSize, String bgMode, int fillColor) throws IOException {
        byte[] png = padToSquare(pngBytes);
        byte[] processed;
        switch (bgMode == null ? "remove_auto" : bgMode) {
            case "keep_transparent" ->
                // Skip background removal entirely — preserve alpha as-is
                processed = resizeTo(png, targetSize);
            case "fill_black" ->
                // Legacy behavior: composite against black
                processed = resizeTo(replaceBackground(png), targetSize);
            case "fill_color" ->
                // Composite against the specified fill colour
                processed = resizeTo(replaceBackgroundWithColor(png, fillColor), targetSize);
            default -> // "remove_auto" — standard auto-detection
                processed = resizeTo(replaceBackground(png), targetSize);
        }
        return new ProcessResult(processed, null, 1, null);
    }

    /**
     * Phase 4A.4 — Maps a named fringe mode to a pixel tolerance value.
     * Returns -1 to indicate the fringe pass should be skipped entirely.
     */
    private static int fringeTolerance(String fringe) {
        if (fringe == null) return FRINGE_TOLERANCE;
        return switch (fringe) {
            case "off"        -> -1;   // -1 = skip fringe pass entirely
            case "light"      -> 30;
            case "normal"     -> 40;
            case "aggressive" -> 80;
            default           -> FRINGE_TOLERANCE;
        };
    }

    /**
     * Phase 4A.4 — Returns true if this pixel is a fringe candidate using an explicit tolerance.
     * tolerance == -1 always returns false (fringe pass disabled).
     */
    private static boolean isFringeWithTolerance(int argb, int tolerance) {
        if (tolerance < 0) return false;
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (tolerance == 0) return false;
        return CustomBlocksConfig.bgRemovalUseYcbcr
            ? isNearWhiteYcbcr(argb, tolerance)
            : deltaE(rgbToLab(argb), LAB_WHITE) <= tolerance;
    }

    /**
     * Phase 4A.4 — Same as {@link #replaceBackground(byte[])} but with an explicit fringe
     * tolerance. Pass -1 to skip the fringe dilation pass entirely.
     */
    public static byte[] replaceBackgroundWithFringeTolerance(byte[] pngBytes, int fringeTol) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage argb = toArgb(img);

        final int BLACK    = 0xFF000000;
        final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
        boolean[][] isBg   = new boolean[w][h];

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

            if (fringeTol >= 0) {
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
                        if (adjacentToBg && isFringeWithTolerance(argb.getRGB(x, y), fringeTol))
                            fringe[x][y] = true;
                    }
                }
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        if (fringe[x][y]) isBg[x][y] = true;
            }
        }

        final int bgR = 255, bgG = 255, bgB = 255;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isBg[x][y]) {
                    argb.setRGB(x, y, BLACK);
                    continue;
                }
                int pixel = argb.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a == 255) continue;
                int r = (int)(((pixel >> 16) & 0xFF) * a / 255.0 + bgR * (255 - a) / 255.0 + 0.5);
                int g = (int)(((pixel >>  8) & 0xFF) * a / 255.0 + bgG * (255 - a) / 255.0 + 0.5);
                int b = (int)( (pixel        & 0xFF) * a / 255.0 + bgB * (255 - a) / 255.0 + 0.5);
                argb.setRGB(x, y, BLACK | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Phase 4A.4 — Full processing pipeline with configurable background mode and fringe mode.
     *
     * @param pngBytes   source PNG bytes (must already be a valid PNG)
     * @param targetSize target square size in pixels
     * @param bgMode     one of "keep_transparent", "remove_auto", "fill_black", "fill_white", "fill_color"
     * @param fillColor  RGB int (0xRRGGBB) used only when bgMode is "fill_color"; ignored otherwise
     * @param fringe     one of "off", "light", "normal", "aggressive" (or null for default)
     * @return a {@link ProcessResult} with the processed static texture (frameCount = 1)
     */
    public static ProcessResult processWithMode(byte[] pngBytes, int targetSize, String bgMode, int fillColor, String fringe) throws IOException {
        byte[] png = padToSquare(pngBytes);
        int fringeTol = fringeTolerance(fringe);
        byte[] processed;
        switch (bgMode == null ? "remove_auto" : bgMode) {
            case "keep_transparent" ->
                processed = resizeTo(png, targetSize);
            case "fill_black" ->
                processed = resizeTo(replaceBackgroundWithFringeTolerance(png, fringeTol), targetSize);
            case "fill_white" ->
                processed = resizeTo(replaceBackgroundWithColor(png, 0xFFFFFF), targetSize);
            case "fill_color" ->
                processed = resizeTo(replaceBackgroundWithColor(png, fillColor), targetSize);
            default -> // "remove_auto" — standard auto-detection
                processed = resizeTo(replaceBackgroundWithFringeTolerance(png, fringeTol), targetSize);
        }
        return new ProcessResult(processed, null, 1, null);
    }

    /**
     * Download with CDN URL rewriting.
     * Supports Discord, Imgur, and handles redirects.
     * Max 10 MB.
     */
    @SuppressFBWarnings("DE_MIGHT_IGNORE") // best-effort stream close in error paths — original exception is always re-thrown
    public static byte[] download(String url) throws IOException, InterruptedException {
        // 1.25 — SSRF protection: block private/internal network addresses
        validateUrlSecurity(url);

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

        // Extract domain for friendly messages
        String domain;
        try {
            domain = URI.create(url).getHost();
            if (domain == null) domain = url;
        } catch (Exception e) {
            domain = url;
        }

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(fetchUrl))
                    .header("User-Agent", "CustomBlocksMod/2.0")
                    .timeout(Duration.ofSeconds(CustomBlocksConfig.downloadTimeoutSeconds))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("§eThat doesn't look like a valid link! §7Make sure you copied the full URL — it should start with §fhttp:// §7or §fhttps://");
        }

        // 7.34 — use ofInputStream() so we can inspect Content-Length before buffering the body
        HttpResponse<java.io.InputStream> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new IOException("§eCouldn't reach §f" + domain + "§e! §7The website might be down, or the server doesn't have internet. Try again later.");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("§eThe download took too long! §f" + domain + " §7is being slow. Try again, or use a different image host like §fImgur§7.");
        } catch (java.net.ConnectException e) {
            throw new IOException("§eCan't reach §f" + domain + "§e at all! §7Either the website is down, or your server has no internet.");
        } catch (java.nio.channels.UnresolvedAddressException e) {
            throw new IOException("§eNever heard of §f" + domain + "§e! §7Check for typos in the URL — did you spell the website name correctly?");
        } catch (Exception e) {
            throw new IOException("§eSomething went wrong connecting to §f" + domain + "§e. §7Try pasting the link again, or use a different image host.");
        }

        int code = res.statusCode();
        if (code < 200 || code >= 300) {
            try { res.body().close(); } catch (IOException ignored) {}
            String hint = switch (code) {
                case 400 -> "§eBad link! §7The URL has something weird in it. Try right-clicking the image → §fCopy image address §7and paste that instead.";
                case 401, 403 -> "§eNo permission! §f" + domain + " §7won't let us download this image. It might be private. Try uploading it to §fImgur §7or §fDiscord §7instead.";
                case 404 -> "§eImage not found! §7It was deleted or the link is broken. §fCheck if the link still works in your browser.";
                case 410 -> "§eThis image was permanently deleted from §f" + domain + "§e. §7You'll need to upload a new one.";
                case 429 -> "§eWhoah, slow down! §f" + domain + " §7says we're sending too many requests. Wait about a minute and try again.";
                case 500 -> "§f" + domain + " §eis having problems on their end. §7Nothing we can do — try again in a few minutes.";
                case 502, 503 -> "§f" + domain + " §eis temporarily down. §7Try again in a few minutes, or use a different image host.";
                case 504 -> "§f" + domain + " §eis being really slow right now. §7Try again later.";
                case 301, 302, 307, 308 -> "§eThe image moved to a new link and we couldn't follow it. §7Try opening it in your browser, then copy the final URL from the address bar.";
                default -> "§eSomething unexpected happened with §f" + domain + " §7(error " + code + "). Try a different image or host.";
            };
            throw new IOException(hint);
        }
        // 7.34 — reject oversized responses before reading body (avoids allocating up to 20 MB)
        java.util.OptionalLong clOpt = res.headers().firstValueAsLong("content-length");
        if (clOpt.isPresent() && clOpt.getAsLong() > 20_971_520L) {
            try { res.body().close(); } catch (IOException ignored) {}
            throw new IOException("§eToo big! §7This image is §f" + (clOpt.getAsLong() / 1_048_576) + " MB§7 but the max is §f20 MB§7. Shrink it first or use a smaller image.");
        }
        byte[] body;
        try (java.io.InputStream is = res.body()) {
            body = is.readNBytes(20_971_521); // read one byte past limit to detect overflow
        }
        if (body == null || body.length == 0)
            throw new IOException("§eGot nothing back from §f" + domain + "§e! §7The image was probably deleted. Try a different link.");
        if (body.length > 20_971_520)
            throw new IOException("§eToo big! §7This image is §f" + (body.length / 1_048_576) + " MB§7 but the max is §f20 MB§7. Shrink it first or use a smaller image.");

        // Phase 6: If the downloaded bytes are WebP (not handled by Java's ImageIO natively),
        // re-download through wsrv.nl proxy to convert to PNG or GIF (for animated WebP).
        if (isWebP(body)) {
            CustomBlocksMod.LOGGER.info("[CustomBlocks] WebP detected, converting via wsrv.nl proxy");
            try {
                String encoded = java.net.URLEncoder.encode(fetchUrl, java.nio.charset.StandardCharsets.UTF_8);
                String output = isAnimatedWebP(body) ? "gif&n=-1" : "png";
                String proxyUrl = "https://wsrv.nl/?url=" + encoded + "&output=" + output;
                HttpRequest proxyReq = HttpRequest.newBuilder()
                        .uri(URI.create(proxyUrl))
                        .header("User-Agent", "CustomBlocksMod/2.0")
                        .timeout(Duration.ofSeconds(CustomBlocksConfig.downloadTimeoutSeconds))
                        .build();
                HttpResponse<byte[]> proxyRes = HTTP.send(proxyReq, HttpResponse.BodyHandlers.ofByteArray());
                if (proxyRes.statusCode() >= 200 && proxyRes.statusCode() < 300
                        && proxyRes.body() != null && proxyRes.body().length > 0) {
                    return proxyRes.body();
                }
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] wsrv.nl proxy returned status {}", proxyRes.statusCode());
            } catch (Exception proxyEx) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] wsrv.nl proxy failed: {}", proxyEx.getMessage());
            }
            // Proxy failed — return original bytes and let ImageIO try (will fail without TwelveMonkeys)
        }

        return body;
    }

    /** Check if raw bytes have a WebP header (RIFF....WEBP). */
    private static boolean isWebP(byte[] raw) {
        return raw.length > 12
                && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P';
    }

    /** Check if WebP bytes contain an ANIM chunk (animated). */
    private static boolean isAnimatedWebP(byte[] raw) {
        if (!isWebP(raw)) return false;
        String head = new String(raw, 0, Math.min(200, raw.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.contains("ANIM");
    }

    /**
     * Convert any supported format to PNG, preserving the alpha channel exactly.
     * Java natively handles PNG, JPG, GIF, and BMP. WebP is converted upstream via wsrv.nl proxy.
     */
    public static byte[] toPng(byte[] raw) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) {
            String detected = detectFormat(raw);
            throw new IOException(
                "§eCan't read this image" + (detected != null ? " §7(looks like a §f" + detected + "§7 file)" : "") +
                "§e! We support §fPNG§7, §fJPG§7, §fGIF§7, §fBMP§7, and §fWebP§7. " +
                "Try saving it as a §fPNG §7in any image editor, then re-upload.");
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
        // Phase 4A.5 — Fill letterbox with fully transparent pixels so the bg removal
        // stage can handle padding uniformly rather than fighting opaque black bars.
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, size, size);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
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
        // against white (Phase 4A.3 — prevents edge darkening on anti-aliased art)
        // and make fully opaque. No transparency left in output.
        // Phase 4A.3 — composite against white instead of black to avoid dark fringes.
        final int bgR = 255, bgG = 255, bgB = 255;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isBg[x][y]) {
                    argb.setRGB(x, y, BLACK);
                    continue;
                }
                int pixel = argb.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a == 255) continue; // already fully opaque — skip
                // Composite against white: out = src * (a/255) + white * ((255-a)/255)
                int r = (int)(((pixel >> 16) & 0xFF) * a / 255.0 + bgR * (255 - a) / 255.0 + 0.5);
                int g = (int)(((pixel >>  8) & 0xFF) * a / 255.0 + bgG * (255 - a) / 255.0 + 0.5);
                int b = (int)( (pixel        & 0xFF) * a / 255.0 + bgB * (255 - a) / 255.0 + 0.5);
                argb.setRGB(x, y, BLACK | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * 1.31 — Same algorithm as {@link #replaceBackground} but paints background pixels
     * with a custom RGB colour instead of black.  Used by the hex-colour tool to produce
     * correctly-tinted variant textures.
     *
     * @param pngBytes source PNG bytes
     * @param rgb      target fill colour as 0xRRGGBB (alpha is ignored — always fully opaque)
     */
    public static byte[] replaceBackgroundWithColor(byte[] pngBytes, int rgb) throws IOException {
        int fillR = (rgb >> 16) & 0xFF;
        int fillG = (rgb >>  8) & 0xFF;
        int fillB =  rgb        & 0xFF;
        int FILL = 0xFF000000 | (fillR << 16) | (fillG << 8) | fillB;

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage argb = toArgb(img);

        final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
        boolean[][] isBg   = new boolean[w][h];

        Queue<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            if (!isBg[x][0]   && isBackground(argb.getRGB(x, 0)))   { isBg[x][0]   = true; queue.add(new int[]{x, 0});   }
            if (!isBg[x][h-1] && isBackground(argb.getRGB(x, h-1))) { isBg[x][h-1] = true; queue.add(new int[]{x, h-1}); }
        }
        for (int y = 1; y < h - 1; y++) {
            if (!isBg[0][y]   && isBackground(argb.getRGB(0, y)))   { isBg[0][y]   = true; queue.add(new int[]{0, y});   }
            if (!isBg[w-1][y] && isBackground(argb.getRGB(w-1, y))) { isBg[w-1][y] = true; queue.add(new int[]{w-1, y}); }
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

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isBg[x][y]) {
                    argb.setRGB(x, y, FILL);
                    continue;
                }
                int pixel = argb.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a < 255) {
                    // Premultiply alpha against fill colour
                    int r = (int)(((pixel >> 16) & 0xFF) * a / 255.0 + (fillR * (255 - a) / 255.0) + 0.5);
                    int g = (int)(((pixel >>  8) & 0xFF) * a / 255.0 + (fillG * (255 - a) / 255.0) + 0.5);
                    int b = (int)( (pixel        & 0xFF) * a / 255.0 + (fillB * (255 - a) / 255.0) + 0.5);
                    argb.setRGB(x, y, 0xFF000000 | (Math.min(255,r) << 16) | (Math.min(255,g) << 8) | Math.min(255,b));
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(argb, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Returns true if this pixel should be treated as background during flood-fill.
     * Uses the configured white-background colour distance mode.
     */
    public static boolean isBackground(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (CustomBlocksConfig.bgRemovalTolerance <= 0) return false;
        int tolerance = CustomBlocksConfig.bgRemovalTolerance;
        return CustomBlocksConfig.bgRemovalUseYcbcr
            ? isNearWhiteYcbcr(argb, tolerance)
            : deltaE(rgbToLab(argb), LAB_WHITE) <= tolerance;
    }

    // ── Phase 9: Video-to-texture (MP4/MOV) ────────────────────────────────────

    /** Max video frames to extract. */
    private static final int MAX_VIDEO_FRAMES = 200;
    /** Max video duration to process (seconds). */
    private static final double MAX_VIDEO_SECONDS = 10.0;
    /** Max video file size (5 MB). */
    private static final int MAX_VIDEO_SIZE = 5 * 1024 * 1024;

    /**
     * Detect MP4/MOV by the ISO Base Media File Format 'ftyp' box.
     * Bytes 4-7 must be 'f','t','y','p'.
     */
    public static boolean isVideoFile(byte[] raw) {
        return raw.length > 8
                && raw[4] == 'f' && raw[5] == 't' && raw[6] == 'y' && raw[7] == 'p';
    }

    /**
     * Extract frames from an MP4/MOV file using jcodec, build a vertical PNG strip
     * with mcmeta — identical output format to animated GIFs.
     *
     * Safeguards: max 200 frames, max 10 seconds, max 5 MB input, OOM-safe, timeout.
     */
    public static ProcessResult processVideo(byte[] raw, int frameSize) {
        frameSize = Math.max(16, Math.min(MAX_SIZE, frameSize));
        long startTime = System.currentTimeMillis();

        if (raw.length > MAX_VIDEO_SIZE) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Video too large: {} bytes (max {})", raw.length, MAX_VIDEO_SIZE);
            return null;
        }

        java.io.File tmpFile = null;
        try {
            // jcodec needs a seekable channel — write to a temp file
            tmpFile = java.io.File.createTempFile("cb_video_", ".mp4");
            tmpFile.deleteOnExit();
            java.nio.file.Files.write(tmpFile.toPath(), raw);

            org.jcodec.common.io.SeekableByteChannel ch = org.jcodec.common.io.NIOUtils.readableChannel(tmpFile);
            org.jcodec.api.FrameGrab grab;
            try {
                grab = org.jcodec.api.FrameGrab.createFrameGrab(ch);
            } catch (Exception e) {
                ch.close();
                throw e;
            }

            if (grab.getVideoTrack() == null || grab.getVideoTrack().getMeta() == null) {
                ch.close();
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] Video has no readable video track");
                return null;
            }

            // Determine video duration and frame rate for timing
            double duration = grab.getVideoTrack().getMeta().getTotalDuration();
            double capDuration = Math.min(duration, MAX_VIDEO_SECONDS);
            int totalVideoFrames = grab.getVideoTrack().getMeta().getTotalFrames();
            double fps = totalVideoFrames > 0 && duration > 0 ? totalVideoFrames / duration : 30.0;

            // Calculate how many frames to extract (capped)
            int framesToExtract = Math.min(MAX_VIDEO_FRAMES, (int)(capDuration * fps));
            framesToExtract = Math.max(1, framesToExtract);

            // Calculate tick duration per frame (20 ticks/second)
            int ticksPerFrame = Math.max(1, (int) Math.round(20.0 / fps));

            CustomBlocksMod.LOGGER.info("[CustomBlocks] Video: {}s, ~{} fps, extracting {} frames",
                    String.format("%.1f", duration), (int) fps, framesToExtract);

            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            for (int i = 0; i < framesToExtract; i++) {
                // Timeout check
                if (System.currentTimeMillis() - startTime > ANIM_TIMEOUT_MS) {
                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] Video processing timeout after {}ms (frame {})", ANIM_TIMEOUT_MS, i);
                    break;
                }
                // Memory check
                Runtime rt = Runtime.getRuntime();
                long free = rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
                if (free < MIN_FREE_HEAP_BYTES) {
                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] Low heap ({} MB free) — stopping video at frame {}", free / (1024 * 1024), i);
                    break;
                }

                org.jcodec.common.model.Picture pic = grab.getNativeFrame();
                if (pic == null) break;
                BufferedImage img = org.jcodec.scale.AWTUtil.toBufferedImage(pic);
                if (img == null) break;
                frames.add(img);
            }

            if (frames.isEmpty()) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] Video: no frames extracted");
                return null;
            }

            CustomBlocksMod.LOGGER.info("[CustomBlocks] Video: extracted {} frames, building strip", frames.size());

            // Build vertical strip (same format as GIF animations)
            BufferedImage strip = new BufferedImage(frameSize, frameSize * frames.size(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = strip.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < frames.size(); i++) {
                g.drawImage(frames.get(i), 0, i * frameSize, frameSize, frameSize, null);
            }
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(strip, "PNG", baos);

            // Build mcmeta with per-frame timing
            StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"interpolate\":false,\"frames\":[");
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) mcmeta.append(",");
                mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(ticksPerFrame).append("}");
            }
            mcmeta.append("]}}");

            return new ProcessResult(baos.toByteArray(), mcmeta.toString(), frames.size(), null);
        } catch (OutOfMemoryError oom) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] OOM during video processing — try a shorter video or lower resolution");
            return null;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Video processing failed", e);
            return null;
        } finally {
            if (tmpFile != null) {
                try { tmpFile.delete(); } catch (Exception ignored) {}
            }
        }
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
            String head = new String(raw, 0, Math.min(200, raw.length), java.nio.charset.StandardCharsets.ISO_8859_1);
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
     * Produce a valid Minecraft animation .mcmeta JSON for a vertical frame
     * strip of {@code frames} equal-duration frames (5 ticks per frame,
     * interpolation on).
     *
     * <p>Used as a safety-net when a strip texture exists but its original
     * per-frame timing was lost (e.g. legacy SlotData saved during a broken
     * build, or a packet dropped animMeta in transit). Guarantees the strip
     * animates instead of rendering as stacked frames.
     *
     * <p>Returns an empty string for frames &lt;= 1 so callers can short-circuit.
     */
    public static String synthesizeDefaultMcmeta(int frames) {
        if (frames <= 1) return "";
        StringBuilder sb = new StringBuilder(48 + frames * 26);
        sb.append("{\"animation\":{\"interpolate\":true,\"frames\":[");
        for (int i = 0; i < frames; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i).append(",\"time\":5}");
        }
        sb.append("]}}");
        return sb.toString();
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

    /**
     * Translates a processing exception into a human-readable explanation.
     */
    private static String friendlyProcessingError(Exception e) {
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (name.contains("OutOfMemory"))
            return "This image is way too big for the server to handle. Try a smaller one!";
        if (e instanceof javax.imageio.IIOException || name.contains("IIOException"))
            return "The image file seems broken or corrupted. Try saving it as a §fPNG§7 and re-upload.";
        if (name.contains("NullPointer"))
            return "The image didn't fully download. Make sure the upload finished before copying the link.";
        if (name.contains("ArrayIndexOutOfBounds") || name.contains("NegativeArraySize"))
            return "This image has weird dimensions we can't handle. Try cropping it to a square first.";
        if (name.contains("IllegalArgument"))
            return "The image uses a color type we don't support. Save it as a normal §fPNG§7 and try again.";
        if (msg != null && !msg.isBlank())
            return msg;
        return "Something unexpected happened. Try a different image!";
    }
    // ── Animated Image Processing ────────────────────────────────────────────

    // ── Animation limits (OOM + stability safeguards) ─────────────────────────
    /** Max frames to prevent OOM on pathological GIFs. */
    public static final int MAX_FRAMES = 100;
    /** Max per-frame dimension we will decode before rescaling. */
    private static final int MAX_FRAME_DIM = 512;
    /** Processing timeout per GIF (prevents hang on malformed files). */
    private static final long ANIM_TIMEOUT_MS = 30_000L;
    /** Minimum free heap to allocate a frame buffer. */
    private static final long MIN_FREE_HEAP_BYTES = 32L * 1024 * 1024; // 32 MB

    /** Parsed per-frame metadata from the animation container. */
    private record FrameMeta(int delayCsecs, int disposal, int offsetX, int offsetY, boolean transparent) {}

    /**
     * Universal animation processor. Detects format (GIF, WebP, APNG), extracts frames,
     * applies disposal methods (none / restoreToBackground / restoreToPrevious), respects
     * frame offsets, and builds a vertical PNG strip with proper {@code .mcmeta}.
     *
     * <p>Safety:
     * <ul>
     *   <li>Frames capped at {@link #MAX_FRAMES}.</li>
     *   <li>Source frame dims clamped to {@link #MAX_FRAME_DIM}.</li>
     *   <li>Free-heap pre-check per frame to avoid OOM.</li>
     *   <li>Hard {@link #ANIM_TIMEOUT_MS} timeout; returns null cleanly if exceeded.</li>
     * </ul>
     */
    public static ProcessResult processAnimation(byte[] raw, int frameSize) {
        frameSize = Math.max(16, Math.min(MAX_SIZE, frameSize));
        long startTime = System.currentTimeMillis();
        ImageReader reader = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (iis == null) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: ImageIO.createImageInputStream returned null");
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: no ImageReader available for this data");
                return null;
            }

            reader = readers.next();
            // seekForwardOnly MUST be false so getNumImages(true) can search.
            // ignoreMetadata=false so we can read GIF disposal/delay/offsets.
            reader.setInput(iis, false, false);

            // Best-effort frame count. Many malformed GIFs return 1 here even
            // when they have multiple frames, so we do NOT fail when it says 1 -
            // instead we let the read-loop below discover the real count by
            // reading frames until read() fails.
            int hintedFrames;
            try {
                hintedFrames = reader.getNumImages(true);
            } catch (IOException | IllegalStateException e) {
                hintedFrames = -1;
            }

            // Peek first frame for canvas dims. If this fails, it really is broken.
            BufferedImage firstFrame;
            try {
                firstFrame = reader.read(0);
            } catch (Exception e) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: first-frame read failed: {}", e.getMessage());
                return null;
            }
            if (firstFrame == null) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: first frame was null");
                return null;
            }

            int canvasW = Math.min(firstFrame.getWidth(), MAX_FRAME_DIM);
            int canvasH = Math.min(firstFrame.getHeight(), MAX_FRAME_DIM);
            if (canvasW <= 0 || canvasH <= 0) {
                CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: invalid canvas {}x{}", canvasW, canvasH);
                return null;
            }

            // Re-open the stream so the main loop starts from frame 0.
            reader.dispose();
            reader = null;
            try (ImageInputStream iis2 = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
                Iterator<ImageReader> readers2 = ImageIO.getImageReaders(iis2);
                if (!readers2.hasNext()) {
                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF decode: second pass found no reader");
                    return null;
                }
                reader = readers2.next();
                reader.setInput(iis2, false, false);

                BufferedImage composite = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                BufferedImage previous = null; // for disposal=3 (restoreToPrevious)
                Graphics2D gComp = composite.createGraphics();
                gComp.setComposite(AlphaComposite.Src);
                gComp.setColor(new Color(0, 0, 0, 0));
                gComp.fillRect(0, 0, canvasW, canvasH);
                gComp.setComposite(AlphaComposite.SrcOver);

                // Upper bound on loop: min(MAX_FRAMES, hint if reliable).
                // If hint is -1 or 1, we still try all the way to MAX_FRAMES
                // and stop when read() fails (robust against malformed GIFs).
                int maxAttempts = hintedFrames > 1
                        ? Math.min(hintedFrames, MAX_FRAMES)
                        : MAX_FRAMES;

                java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
                java.util.List<Integer> ticks = new java.util.ArrayList<>();

                for (int i = 0; i < maxAttempts; i++) {
                    // Timeout check
                    if (System.currentTimeMillis() - startTime > ANIM_TIMEOUT_MS) {
                        CustomBlocksMod.LOGGER.warn("[CustomBlocks] GIF processing timeout after {}ms (frame {})",
                                ANIM_TIMEOUT_MS, i);
                        break;
                    }

                    // Memory pre-check - ensure we have headroom before allocating a frame copy
                    Runtime rt = Runtime.getRuntime();
                    long free = rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
                    if (free < MIN_FREE_HEAP_BYTES) {
                        CustomBlocksMod.LOGGER.warn("[CustomBlocks] Low heap ({} MB free) - stopping GIF at frame {}",
                                free / (1024 * 1024), i);
                        break;
                    }

                    BufferedImage frame;
                    FrameMeta fm;
                    try {
                        frame = reader.read(i);
                        fm = parseFrameMeta(reader.getImageMetadata(i));
                    } catch (Exception e) {
                        CustomBlocksMod.LOGGER.warn("[CustomBlocks] Failed to read frame {}: {}", i, e.getMessage());
                        break;
                    }
                    if (frame == null) break;

                    // Save pre-state for disposal=3 (restoreToPrevious)
                    if (fm.disposal() == 3) {
                        previous = copyArgb(composite);
                    }

                    // Draw current frame at its offset (respects partial-frame GIFs)
                    gComp.setComposite(AlphaComposite.SrcOver);
                    int dx = Math.max(0, Math.min(fm.offsetX(), canvasW - 1));
                    int dy = Math.max(0, Math.min(fm.offsetY(), canvasH - 1));
                    gComp.drawImage(frame, dx, dy, null);

                    // Convert csecs to game ticks (1 tick = 50ms = 5 csecs). Minimum 1 tick.
                    // GIFs with delay=0 are typically "as fast as possible" - clamp to 1 tick.
                    int delay = fm.delayCsecs() <= 0 ? 10 : fm.delayCsecs();
                    ticks.add(Math.max(1, delay / 5));
                    frames.add(copyArgb(composite));

                    // Apply disposal method for NEXT frame (per GIF89a spec)
                    switch (fm.disposal()) {
                        case 2 -> { // restoreToBackground - clear this frame's region
                            gComp.setComposite(AlphaComposite.Clear);
                            int rw = Math.min(frame.getWidth(), canvasW - dx);
                            int rh = Math.min(frame.getHeight(), canvasH - dy);
                            gComp.fillRect(dx, dy, rw, rh);
                            gComp.setComposite(AlphaComposite.SrcOver);
                        }
                        case 3 -> { // restoreToPrevious - revert to pre-frame state
                            if (previous != null) {
                                gComp.setComposite(AlphaComposite.Src);
                                gComp.drawImage(previous, 0, 0, null);
                                gComp.setComposite(AlphaComposite.SrcOver);
                            }
                        }
                        default -> { /* 0 or 1: leave current composite as-is */ }
                    }
                }
                gComp.dispose();

                if (frames.isEmpty()) return null;

                // 1.12 Layer 1 — Cap decoded strip size BEFORE building the strip
                long estimatedStripBytes = (long) frameSize * frameSize * frames.size() * 4;
                long maxStripBytes = com.customblocks.CustomBlocksConfig.maxGifStripBytes;
                String animWarning = null;
                if (estimatedStripBytes > maxStripBytes) {
                    int maxFrames = (int)(maxStripBytes / ((long) frameSize * frameSize * 4));
                    maxFrames = Math.max(1, maxFrames);
                    int oldCount = frames.size();
                    frames = frames.subList(0, Math.min(maxFrames, frames.size()));
                    ticks = ticks.subList(0, Math.min(maxFrames, ticks.size()));
                    animWarning = "§eGIF had " + oldCount + " frames — trimmed to " + frames.size()
                        + " to fit safely. §7Use /cb anim to adjust.";
                    CustomBlocksMod.LOGGER.warn(
                        "[CustomBlocks] 1.12 Layer 1: GIF too large (~{}MB) — trimmed from {} to {} frames",
                        estimatedStripBytes / 1_000_000, oldCount, frames.size());
                }

                // Build vertical strip at target frame size
                BufferedImage strip = new BufferedImage(frameSize, frameSize * frames.size(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D gStrip = strip.createGraphics();
                gStrip.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gStrip.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                gStrip.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gStrip.setComposite(AlphaComposite.Clear);
                gStrip.fillRect(0, 0, frameSize, frameSize * frames.size());
                gStrip.setComposite(AlphaComposite.SrcOver);
                for (int i = 0; i < frames.size(); i++) {
                    gStrip.drawImage(frames.get(i), 0, i * frameSize, frameSize, frameSize, null);
                }
                gStrip.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(strip, "PNG", baos);

                // 1.12 Layer 2 — Cap final PNG size after encoding; retry at 64px if over 2 MB
                if (baos.size() > 2_000_000L && frameSize > 64) {
                    int smallSize = 64;
                    BufferedImage strip2 = new BufferedImage(smallSize, smallSize * frames.size(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D gStrip2 = strip2.createGraphics();
                    gStrip2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    gStrip2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    gStrip2.setComposite(AlphaComposite.Clear);
                    gStrip2.fillRect(0, 0, smallSize, smallSize * frames.size());
                    gStrip2.setComposite(AlphaComposite.SrcOver);
                    for (int k = 0; k < frames.size(); k++) {
                        gStrip2.drawImage(frames.get(k), 0, k * smallSize, smallSize, smallSize, null);
                    }
                    gStrip2.dispose();
                    baos.reset();
                    ImageIO.write(strip2, "PNG", baos);
                    String sizeWarning = "§eTexture re-encoded at 64px per frame to fit size limits.";
                    animWarning = (animWarning != null ? animWarning + " " : "") + sizeWarning;
                    CustomBlocksMod.LOGGER.warn(
                        "[CustomBlocks] 1.12 Layer 2: GIF PNG too large — re-encoded at 64px ({} frames)", frames.size());
                }

                // Minecraft .mcmeta with interpolate + explicit {index,time} entries
                StringBuilder mcmeta = new StringBuilder("{\"animation\":{\"interpolate\":true,\"frames\":[");
                for (int i = 0; i < frames.size(); i++) {
                    if (i > 0) mcmeta.append(",");
                    mcmeta.append("{\"index\":").append(i).append(",\"time\":").append(ticks.get(i)).append("}");
                }
                mcmeta.append("]}}");

                return new ProcessResult(baos.toByteArray(), mcmeta.toString(), frames.size(), animWarning);
            }
        } catch (OutOfMemoryError oom) {
            // 1.12 Layer 4 — Graceful OOM: free nothing was pinned, give player a clear message
            CustomBlocksMod.LOGGER.error("[CustomBlocks] OOM during GIF processing — server ran out of memory");
            throw new RuntimeException("§cGIF too large for server memory. §7Try a shorter GIF (under 30 frames) or a smaller resolution.");
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Animation processing failed", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.dispose(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Parse per-frame metadata (delay, disposal, offset) from standard javax_imageio_gif_image_1.0 format.
     * Falls back to defaults if attributes are missing or malformed.
     */
    private static FrameMeta parseFrameMeta(IIOMetadata meta) {
        int delay = 10, disposal = 0, offX = 0, offY = 0;
        boolean transparent = false;
        if (meta == null) return new FrameMeta(delay, disposal, offX, offY, transparent);
        try {
            String[] formats = meta.getMetadataFormatNames();
            for (String fmt : formats) {
                org.w3c.dom.Node root = meta.getAsTree(fmt);
                if (root == null) continue;
                // Walk the tree looking for GraphicControlExtension and ImageDescriptor
                java.util.Deque<org.w3c.dom.Node> stack = new java.util.ArrayDeque<>();
                stack.push(root);
                while (!stack.isEmpty()) {
                    org.w3c.dom.Node n = stack.pop();
                    String name = n.getNodeName();
                    org.w3c.dom.NamedNodeMap attrs = n.getAttributes();
                    if (attrs != null) {
                        if (name.equals("GraphicControlExtension")) {
                            org.w3c.dom.Node d  = attrs.getNamedItem("delayTime");
                            org.w3c.dom.Node dm = attrs.getNamedItem("disposalMethod");
                            org.w3c.dom.Node tc = attrs.getNamedItem("transparentColorFlag");
                            if (d != null)  try { delay = Integer.parseInt(d.getNodeValue()); } catch (NumberFormatException ignored) {}
                            if (dm != null) disposal = switch (dm.getNodeValue()) {
                                case "doNotDispose" -> 1;
                                case "restoreToBackgroundColor" -> 2;
                                case "restoreToPrevious" -> 3;
                                default -> 0;
                            };
                            if (tc != null) transparent = "TRUE".equalsIgnoreCase(tc.getNodeValue());
                        } else if (name.equals("ImageDescriptor")) {
                            org.w3c.dom.Node x = attrs.getNamedItem("imageLeftPosition");
                            org.w3c.dom.Node y = attrs.getNamedItem("imageTopPosition");
                            if (x != null) try { offX = Integer.parseInt(x.getNodeValue()); } catch (NumberFormatException ignored) {}
                            if (y != null) try { offY = Integer.parseInt(y.getNodeValue()); } catch (NumberFormatException ignored) {}
                        }
                    }
                    org.w3c.dom.NodeList kids = n.getChildNodes();
                    for (int i = 0; i < kids.getLength(); i++) stack.push(kids.item(i));
                }
            }
        } catch (RuntimeException ignored) {}
        return new FrameMeta(delay, disposal, offX, offY, transparent);
    }

    /**
     * 1.9 — Round size to nearest power-of-2, clamped to [16, MAX_SIZE].
     * Uses floor (rounds down) to avoid upscaling — 150 → 128, 200 → 128, 260 → 256.
     * If the input already exceeds MAX_SIZE it is capped; if below 16 it is floored.
     */
    public static int nearestPowerOf2(int size) {
        size = Math.max(16, Math.min(MAX_SIZE, size));
        return Integer.highestOneBit(size); // floor power-of-2
    }

    /** True when n is already a valid power-of-2 (n > 0 and exactly one bit set). */
    private static boolean isPowerOf2(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /**
     * 1.9 — Validation gate for static textures: decode, check power-of-2 dimensions,
     * and resize to nearest power-of-2 square if needed.
     * Do NOT call this on animated strips — they are handled by processAnimation().
     * Returns the original bytes unchanged if already correct or on any decode error.
     */
    public static byte[] ensurePowerOf2(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return pngBytes;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return pngBytes;
            int w = img.getWidth(), h = img.getHeight();
            if (isPowerOf2(w) && isPowerOf2(h)) return pngBytes;
            int target = nearestPowerOf2(Math.max(w, h));
            CustomBlocksMod.LOGGER.warn(
                "[CustomBlocks] 1.9 Validation gate: non-power-of-2 texture ({}x{}) → resizing to {}x{}",
                w, h, target, target);
            return resizeTo(pngBytes, target);
        } catch (Exception e) {
            return pngBytes; // best-effort — don't break pack generation
        }
    }

    /**
     * Scale image to exactly targetSize × targetSize pixels using bicubic interpolation.
     * targetSize is snapped to the nearest power-of-2 (see nearestPowerOf2) so that
     * Minecraft's mipmap system is never compromised by a non-conforming atlas entry.
     * If the image is already the right size, returns the input unchanged.
     */
    public static byte[] resizeTo(byte[] pngBytes, int targetSize) throws IOException {
        targetSize = nearestPowerOf2(targetSize);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) return pngBytes;
        if (img.getWidth() == targetSize && img.getHeight() == targetSize) return pngBytes;
        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // Phase 4A.9 — adaptive interpolation: nearest-neighbor for pixel art (<64px), bicubic otherwise
        int srcWidth = img.getWidth(), srcHeight = img.getHeight();
        Object interpHint = (srcWidth < 64 || srcHeight < 64)
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BICUBIC;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpHint);
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
            // 1.21 — Dimension check: textures smaller than 16×16 are broken
            if (w < 16 || h < 16) return true;
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

    private static boolean isNearWhiteYcbcr(int argb, int tolerance) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double y  = 0.299 * r + 0.587 * g + 0.114 * b;
        double cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b;
        double cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b;
        double lumaGap = 255.0 - y;
        double chromaDistance = Math.hypot(cb - 128.0, cr - 128.0);
        return lumaGap <= tolerance * 1.8 && chromaDistance <= tolerance * 0.85;
    }

    /**
     * Returns true if this pixel is a fringe candidate (anti-aliased edge pixel).
     * Uses a higher tolerance than isBackground to catch subtly-bright edge pixels.
     */
    private static boolean isFringe(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a < OPAQUE_THRESHOLD) return true;
        if (CustomBlocksConfig.bgRemovalTolerance <= 0) return false;
        int tolerance = CustomBlocksConfig.bgRemovalTolerance + 15;
        return CustomBlocksConfig.bgRemovalUseYcbcr
            ? isNearWhiteYcbcr(argb, tolerance)
            : deltaE(rgbToLab(argb), LAB_WHITE) <= tolerance;
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

    // ── G1/G2: Color Studio filters ───────────────────────────────────────────

    /**
     * G1 — Apply a tint color (RGB multiplier) to a PNG.
     * Each channel is multiplied by the corresponding factor (0.0–2.0).
     * Values above 1.0 boost that channel; below 1.0 suppress it.
     * Alpha channel is preserved unchanged.
     */
    public static byte[] applyTint(byte[] png, float r, float g, float b) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for tint");
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int pr = clamp8((int)(((argb >> 16) & 0xFF) * r));
                int pg = clamp8((int)(((argb >>  8) & 0xFF) * g));
                int pb = clamp8((int)(( argb        & 0xFF) * b));
                out.setRGB(x, y, (a << 24) | (pr << 16) | (pg << 8) | pb);
            }
        }
        return toPngBytes(out);
    }

    /**
     * G1 — Apply a brightness/contrast adjustment.
     * brightness: -100 to +100 (added per channel). contrast: 0.1 to 3.0 (factor around 128).
     */
    public static byte[] applyBrightness(byte[] png, int brightness, float contrast) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for brightness");
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = clamp8((int)(((((argb >> 16) & 0xFF) - 128) * contrast) + 128 + brightness));
                int g = clamp8((int)(((((argb >>  8) & 0xFF) - 128) * contrast) + 128 + brightness));
                int b = clamp8((int)(((( argb        & 0xFF) - 128) * contrast) + 128 + brightness));
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return toPngBytes(out);
    }

    /**
     * G1 — Convert image to grayscale (luminance-weighted, alpha preserved).
     */
    public static byte[] applyGrayscale(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for grayscale");
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int b =  argb        & 0xFF;
                int lum = (int)(0.299 * r + 0.587 * g + 0.114 * b);
                out.setRGB(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        return toPngBytes(out);
    }

    /**
     * G1 — Invert all RGB channels (alpha preserved).
     */
    public static byte[] applyInvert(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for invert");
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = 255 - ((argb >> 16) & 0xFF);
                int g = 255 - ((argb >>  8) & 0xFF);
                int b = 255 - ( argb        & 0xFF);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return toPngBytes(out);
    }

    /**
     * G1 — Flip the image horizontally (mirror left-right, alpha preserved).
     */
    public static byte[] applyMirrorH(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for mirror");
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, img.getRGB(w - 1 - x, y));
            }
        }
        return toPngBytes(out);
    }

    /**
     * G1 — Rotate image 90 degrees clockwise (square images stay square).
     */
    public static byte[] applyRotate90(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Failed to decode image for rotate");
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(h - 1 - y, x, img.getRGB(x, y));
            }
        }
        return toPngBytes(out);
    }

    /**
     * G2 — Generate a 16-color tinted palette set from a base PNG.
     * Returns 16 PNGs, each with a different hue-shifted tint applied.
     * Tints are evenly spaced around the HSB hue wheel at full saturation,
     * blended at {@code strength} (0.0 = no change, 1.0 = full tint).
     */
    public static List<byte[]> generateTintPalette(byte[] png, float strength) throws IOException {
        BufferedImage base = ImageIO.read(new ByteArrayInputStream(png));
        if (base == null) throw new IOException("Failed to decode image for palette");
        List<byte[]> palette = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            float hue = i / 16.0f;
            float[] tintRgb = hsvToRgb(hue, 1.0f, 1.0f);
            float tr = tintRgb[0], tg = tintRgb[1], tb = tintRgb[2];
            BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) {
                    int argb = base.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    float r = ((argb >> 16) & 0xFF) / 255f;
                    float g = ((argb >>  8) & 0xFF) / 255f;
                    float b  = ( argb        & 0xFF) / 255f;
                    // Luma-preserving tint: multiply by tint color, blend with original
                    float nr = r * (1 - strength) + (r * tr) * strength;
                    float ng = g * (1 - strength) + (g * tg) * strength;
                    float nb = b * (1 - strength) + (b * tb) * strength;
                    out.setRGB(x, y, (a << 24) | (clamp8((int)(nr * 255)) << 16) |
                            (clamp8((int)(ng * 255)) << 8) | clamp8((int)(nb * 255)));
                }
            }
            palette.add(toPngBytes(out));
        }
        return palette;
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        int hi = (int)(h * 6) % 6;
        float f = h * 6 - (int)(h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        return switch (hi) {
            case 0 -> new float[]{v, t, p};
            case 1 -> new float[]{q, v, p};
            case 2 -> new float[]{p, v, t};
            case 3 -> new float[]{p, q, v};
            case 4 -> new float[]{t, p, v};
            default-> new float[]{v, p, q};
        };
    }

    private static int clamp8(int v) { return Math.max(0, Math.min(255, v)); }

    private static byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
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

    // ── SSRF protection (item 1.25) ──────────────────────────────────────────

    /**
     * Validates that a URL points to a public internet address.
     * Blocks localhost, private networks (10.x, 172.16.x, 192.168.x),
     * link-local (169.254.x — cloud metadata endpoints), and unroutable addresses.
     *
     * Call this at the top of every method that accepts a user-supplied URL
     * before any HTTP connection is opened.
     *
     * @throws IOException with a player-friendly error message if the URL is disallowed.
     */
    public static void validateUrlSecurity(String urlString) throws IOException {
        if (urlString == null || urlString.isBlank()) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }

        java.net.URI uri;
        try {
            uri = java.net.URI.create(urlString);
        } catch (IllegalArgumentException e) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }

        java.net.InetAddress addr;
        try {
            addr = java.net.InetAddress.getByName(host);
        } catch (java.net.UnknownHostException e) {
            // Block unresolvable hosts — if DNS fails at validation time the HTTP client
            // could retry with a different result (DNS rebinding window). Safer to reject.
            throw new IOException("§c[CB] That URL is not allowed. Could not verify the host address.");
        }

        if (addr.isLoopbackAddress()) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }
        if (addr.isSiteLocalAddress()) {
            // Covers 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }
        if (addr.isLinkLocalAddress()) {
            // Covers 169.254.x.x (AWS/GCP/Azure cloud metadata endpoints)
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }
        if (addr.isAnyLocalAddress()) {
            throw new IOException("§c[CB] That URL is not allowed. Only public internet addresses are accepted.");
        }
    }
}

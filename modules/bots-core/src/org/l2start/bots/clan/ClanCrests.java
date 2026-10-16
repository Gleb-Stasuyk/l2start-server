package org.l2start.bots.clan;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Loads 16×12 BMP clan emblems from {@code data/clans/crests/} and converts them to the DDS DXT1
 * payload the Interlude client expects ({@code CrestTable.createCrest}, max 256 bytes).
 * <p>
 * Design size is 16×12 (visible crest). The client rejects non-power-of-two textures, so we pad to
 * 16×16 (edge-replicate) before DXT1 — same as Living Worlds {@code crest_png_to_dds.py} and the
 * stock client upload. The bottom 4 rows are padding only; ally crest is a separate texture.
 */
public final class ClanCrests {
    /** Visible pledge crest area (Interlude design). */
    public static final int DESIGN_W = 16;
    public static final int DESIGN_H = 12;
    /** DDS texture after power-of-two pad (client requirement). */
    public static final int TEX_W = 16;
    public static final int TEX_H = 16;
    /** Exact wire size: 128-byte DDS header + 16×16 DXT1 (4×4 blocks × 8). */
    public static final int DDS_SIZE = 256;

    private static volatile Path dir;
    private static volatile List<byte[]> cache = List.of();
    private static volatile String lastError;

    private ClanCrests() {
    }

    /** Call once when the module loads (folder next to BotClans.xml). */
    public static synchronized void init(Path crestsDir) {
        dir = crestsDir;
        cache = List.of();
        lastError = null;
        if ((crestsDir == null) || !Files.isDirectory(crestsDir)) {
            lastError = "no crests dir: " + crestsDir;
            return;
        }
        try {
            final List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(crestsDir, "*.bmp")) {
                for (Path p : ds) {
                    files.add(p);
                }
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            final List<byte[]> out = new ArrayList<>();
            for (Path p : files) {
                try {
                    out.add(bmpToDds(p));
                } catch (Exception e) {
                    lastError = p.getFileName() + ": " + e.getMessage();
                }
            }
            cache = List.copyOf(out);
        } catch (IOException e) {
            lastError = e.getMessage();
            cache = List.of();
        }
    }

    public static int size() {
        return cache.size();
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * DDS bytes for {@code CrestTable.createCrest}. {@code seed} is 1-based index into the sorted crest list
     * (from BotClans.xml {@code crestSeed}); wraps if out of range. Falls back to a deterministic fake DDS if no files.
     */
    public static byte[] ddsForSeed(long seed) {
        final List<byte[]> c = cache;
        if (c.isEmpty()) {
            return ClanTemplates.syntheticCrestBytes(seed);
        }
        final int n = c.size();
        int idx;
        if (seed <= 0) {
            idx = 0;
        } else {
            idx = (int) ((seed - 1) % n);
            if (idx < 0) {
                idx += n;
            }
        }
        return c.get(idx);
    }

    /** DDSD_CAPS|HEIGHT|WIDTH|PIXELFORMAT|LINEARSIZE — required by Interlude / Living Worlds DDS. */
    public static final int DDS_FLAGS = 0x00081007;

    /**
     * Interlude accepts pledge DDS only when length is 256, texture is 16×16 (power-of-two), and
     * header flags match the client upload (incl. DDSD_LINEARSIZE). Wrong size/flags = blank crest.
     */
    public static boolean isClientSafeCrest(byte[] data) {
        if ((data == null) || (data.length != DDS_SIZE)) {
            return false;
        }
        if ((data[0] != 'D') || (data[1] != 'D') || (data[2] != 'S') || (data[3] != ' ')) {
            return false;
        }
        final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(8);
        final int flags = bb.getInt();
        final int height = bb.getInt();
        final int width = bb.getInt();
        return (flags == DDS_FLAGS) && (width == TEX_W) && (height == TEX_H);
    }

    static byte[] bmpToDds(Path bmp) throws IOException {
        final BufferedImage src = ImageIO.read(bmp.toFile());
        if (src == null) {
            throw new IOException("unreadable BMP");
        }
        final BufferedImage design = new BufferedImage(DESIGN_W, DESIGN_H, BufferedImage.TYPE_INT_ARGB);
        design.getGraphics().drawImage(src, 0, 0, DESIGN_W, DESIGN_H, null);
        final int[] rgba = padToTexture(design);
        return encodeDxt1Dds(rgba, TEX_W, TEX_H);
    }

    /** Edge-replicate 16×12 design into 16×16 (same idea as Living Worlds crest_png_to_dds.py). */
    static int[] padToTexture(BufferedImage design) {
        final int[] rgba = new int[TEX_W * TEX_H];
        for (int y = 0; y < TEX_H; y++) {
            final int sy = Math.min(y, DESIGN_H - 1);
            for (int x = 0; x < TEX_W; x++) {
                final int sx = Math.min(x, DESIGN_W - 1);
                rgba[y * TEX_W + x] = design.getRGB(sx, sy);
            }
        }
        return rgba;
    }

    /** Minimal DDS (DXT1) for power-of-two texture — same layout the Interlude client uploads. */
    static byte[] encodeDxt1Dds(int[] rgba, int w, int h) {
        if ((w % 4) != 0 || (h % 4) != 0) {
            throw new IllegalArgumentException("DXT1 needs multiple of 4: " + w + "x" + h);
        }
        final int blocksX = w / 4;
        final int blocksY = h / 4;
        final byte[] blocks = new byte[blocksX * blocksY * 8];
        int o = 0;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                o = writeDxt1Block(rgba, w, bx * 4, by * 4, blocks, o);
            }
        }
        final ByteBuffer bb = ByteBuffer.allocate(128 + blocks.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'D').put((byte) 'D').put((byte) 'S').put((byte) ' ');
        bb.putInt(124); // header size
        // DDSD_CAPS|HEIGHT|WIDTH|PIXELFORMAT|LINEARSIZE — same as Living Worlds / client upload
        bb.putInt(0x00081007);
        bb.putInt(h);
        bb.putInt(w);
        bb.putInt(blocks.length); // pitch / linear size
        bb.putInt(0); // depth
        bb.putInt(0); // mipmaps
        for (int i = 0; i < 11; i++) {
            bb.putInt(0);
        }
        // pixel format
        bb.putInt(32);
        bb.putInt(0x4); // DDPF_FOURCC
        bb.put((byte) 'D').put((byte) 'X').put((byte) 'T').put((byte) '1');
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0x1000); // DDSCAPS_TEXTURE
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        bb.put(blocks);
        final byte[] out = bb.array();
        if (out.length != DDS_SIZE) {
            throw new IllegalStateException("pledge DDS must be " + DDS_SIZE + ", got " + out.length);
        }
        return out;
    }

    private static int writeDxt1Block(int[] rgba, int w, int x0, int y0, byte[] out, int o) {
        final int[] px = new int[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                px[y * 4 + x] = rgba[(y0 + y) * w + (x0 + x)];
            }
        }
        int minI = 0;
        int maxI = 0;
        int minL = Integer.MAX_VALUE;
        int maxL = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            final int c = px[i];
            final int l = ((c >> 16) & 0xff) * 2 + ((c >> 8) & 0xff) * 3 + (c & 0xff);
            if (l < minL) {
                minL = l;
                minI = i;
            }
            if (l > maxL) {
                maxL = l;
                maxI = i;
            }
        }
        int c0 = to565(px[maxI]);
        int c1 = to565(px[minI]);
        if (c0 == c1) {
            c1 ^= 0x0020; // ensure c0 > c1 for 4-color mode
        }
        if (c0 < c1) {
            final int t = c0;
            c0 = c1;
            c1 = t;
        }
        final int[] palette = new int[4];
        palette[0] = from565(c0);
        palette[1] = from565(c1);
        palette[2] = lerp(palette[0], palette[1], 2, 3);
        palette[3] = lerp(palette[0], palette[1], 1, 3);
        int bits = 0;
        for (int i = 0; i < 16; i++) {
            bits |= nearest(px[i], palette) << (i * 2);
        }
        out[o++] = (byte) (c0 & 0xff);
        out[o++] = (byte) ((c0 >> 8) & 0xff);
        out[o++] = (byte) (c1 & 0xff);
        out[o++] = (byte) ((c1 >> 8) & 0xff);
        out[o++] = (byte) (bits & 0xff);
        out[o++] = (byte) ((bits >> 8) & 0xff);
        out[o++] = (byte) ((bits >> 16) & 0xff);
        out[o++] = (byte) ((bits >> 24) & 0xff);
        return o;
    }

    private static int to565(int argb) {
        final int r = (argb >> 16) & 0xff;
        final int g = (argb >> 8) & 0xff;
        final int b = argb & 0xff;
        return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
    }

    private static int from565(int c) {
        final int r = ((c >> 11) & 0x1f) * 255 / 31;
        final int g = ((c >> 5) & 0x3f) * 255 / 63;
        final int b = (c & 0x1f) * 255 / 31;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, int num, int den) {
        final int ar = (a >> 16) & 0xff;
        final int ag = (a >> 8) & 0xff;
        final int ab = a & 0xff;
        final int br = (b >> 16) & 0xff;
        final int bg = (b >> 8) & 0xff;
        final int bb = b & 0xff;
        final int r = (ar * num + br * (den - num)) / den;
        final int g = (ag * num + bg * (den - num)) / den;
        final int bl = (ab * num + bb * (den - num)) / den;
        return 0xff000000 | (r << 16) | (g << 8) | bl;
    }

    private static int nearest(int argb, int[] palette) {
        int best = 0;
        int bestD = Integer.MAX_VALUE;
        final int r = (argb >> 16) & 0xff;
        final int g = (argb >> 8) & 0xff;
        final int b = argb & 0xff;
        for (int i = 0; i < 4; i++) {
            final int p = palette[i];
            final int dr = r - ((p >> 16) & 0xff);
            final int dg = g - ((p >> 8) & 0xff);
            final int db = b - (p & 0xff);
            final int d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}

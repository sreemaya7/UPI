import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Dependency-free QR Code generator (byte mode, error-correction level M, versions 1-10).
 * Version 10-M holds 213 bytes, plenty for a UPI payment link.
 */
final class Qr {
    private Qr() {}

    // Level M tables, index = version (1..10)
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS_G1 = {0, 1, 1, 1, 2, 2, 4, 4, 2, 3, 4};
    private static final int[] DATA_G1 = {0, 16, 28, 44, 32, 43, 27, 31, 38, 36, 43};
    private static final int[] BLOCKS_G2 = {0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 1};
    private static final int[] DATA_G2 = {0, 0, 0, 0, 0, 0, 0, 0, 39, 37, 44};
    private static final int[][] ALIGN = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

    private static int dataCodewords(int v) { return BLOCKS_G1[v] * DATA_G1[v] + BLOCKS_G2[v] * DATA_G2[v]; }

    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= 10; v++) {
            int countBits = v <= 9 ? 8 : 16;
            if (4 + countBits + 8 * data.length <= dataCodewords(v) * 8) { version = v; break; }
        }
        if (version == 0) throw new IllegalArgumentException("Text too long for QR code");

        // ---- bit stream
        BitBuf bb = new BitBuf();
        bb.append(0b0100, 4);
        bb.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        int capBits = dataCodewords(version) * 8;
        bb.append(0, Math.min(4, capBits - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < capBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] dataCw = bb.toBytes();

        byte[] all = addEccAndInterleave(dataCw, version);
        int size = version * 4 + 17;
        boolean[][] mod = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(mod, fn, version);
        drawCodewords(mod, fn, all);

        int bestMask = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(mod, fn, m);
            drawFormatBits(mod, fn, m);
            long p = penalty(mod);
            if (p < bestPenalty) { bestPenalty = p; bestMask = m; }
            applyMask(mod, fn, m); // undo
        }
        applyMask(mod, fn, bestMask);
        drawFormatBits(mod, fn, bestMask);
        return mod;
    }

    static String pngDataUri(String text, int scale) throws Exception {
        System.setProperty("java.awt.headless", "true");
        boolean[][] m = encode(text);
        int quiet = 4, n = m.length, px = (n + quiet * 2) * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < px; y++)
            for (int x = 0; x < px; x++) {
                int mx = x / scale - quiet, my = y / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx];
                img.getRaster().setSample(x, y, 0, dark ? 0 : 255);
            }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ------------------------------------------------------------------ internals

    private static final class BitBuf {
        final java.util.ArrayList<Boolean> bits = new java.util.ArrayList<>();
        int len = 0;
        void append(int val, int n) { for (int i = n - 1; i >= 0; i--) { bits.add(((val >>> i) & 1) != 0); len++; } }
        byte[] toBytes() {
            byte[] r = new byte[len / 8];
            for (int i = 0; i < len; i++) if (bits.get(i)) r[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            return r;
        }
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int[] rsDivisor(int degree) {
        int[] r = new int[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = gfMul(r[j], root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static int[] rsRemainder(byte[] data, int[] divisor) {
        int[] r = new int[divisor.length];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ r[0];
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(divisor[i], factor);
        }
        return r;
    }

    private static byte[] addEccAndInterleave(byte[] data, int v) {
        int nBlocks = BLOCKS_G1[v] + BLOCKS_G2[v], ecc = ECC_PER_BLOCK[v];
        int[] divisor = rsDivisor(ecc);
        byte[][] blocks = new byte[nBlocks][];
        int[][] eccs = new int[nBlocks][];
        int k = 0;
        for (int i = 0; i < nBlocks; i++) {
            int len = i < BLOCKS_G1[v] ? DATA_G1[v] : DATA_G2[v];
            blocks[i] = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            eccs[i] = rsRemainder(blocks[i], divisor);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int maxData = Math.max(DATA_G1[v], DATA_G2[v]);
        for (int i = 0; i < maxData; i++)
            for (int b = 0; b < nBlocks; b++) if (i < blocks[b].length) out.write(blocks[b][i]);
        for (int i = 0; i < ecc; i++)
            for (int b = 0; b < nBlocks; b++) out.write(eccs[b][i]);
        return out.toByteArray();
    }

    private static void setFn(boolean[][] mod, boolean[][] fn, int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFunctionPatterns(boolean[][] mod, boolean[][] fn, int version) {
        int size = mod.length;
        for (int i = 0; i < size; i++) {
            setFn(mod, fn, 6, i, i % 2 == 0);
            setFn(mod, fn, i, 6, i % 2 == 0);
        }
        drawFinder(mod, fn, 3, 3);
        drawFinder(mod, fn, size - 4, 3);
        drawFinder(mod, fn, 3, size - 4);
        int[] pos = ALIGN[version];
        for (int i = 0; i < pos.length; i++)
            for (int j = 0; j < pos.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (corner) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(mod, fn, pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        drawFormatBits(mod, fn, 0); // reserve the area
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = ((long) version << 12) | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                setFn(mod, fn, a, b, bit);
                setFn(mod, fn, b, a, bit);
            }
        }
    }

    private static void drawFinder(boolean[][] mod, boolean[][] fn, int cx, int cy) {
        int size = mod.length;
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(mod, fn, x, y, dist != 2 && dist != 4);
            }
    }

    private static void drawFormatBits(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        int data = (0 << 3) | mask; // error-correction level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(mod, fn, 8, i, bit(bits, i));
        setFn(mod, fn, 8, 7, bit(bits, 6));
        setFn(mod, fn, 8, 8, bit(bits, 7));
        setFn(mod, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(mod, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(mod, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(mod, fn, 8, size - 15 + i, bit(bits, i));
        setFn(mod, fn, 8, size - 8, true); // always-dark module
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private static void drawCodewords(boolean[][] mod, boolean[][] fn, byte[] data) {
        int size = mod.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    private static void applyMask(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !fn[y][x]) mod[y][x] = !mod[y][x];
            }
    }

    /** Standard QR mask penalty (runs, 2x2 blocks, finder-like patterns, dark/light balance). */
    private static long penalty(boolean[][] m) {
        int n = m.length;
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < n; a++) {
                int run = 1;
                for (int b = 1; b < n; b++) {
                    boolean cur = pass == 0 ? m[a][b] : m[b][a], prev = pass == 0 ? m[a][b - 1] : m[b - 1][a];
                    if (cur == prev) { run++; if (run == 5) p += 3; else if (run > 5) p++; } else run = 1;
                }
                for (int b = 0; b + 11 <= n; b++) {
                    if (matches(m, pass, a, b, new int[]{1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0})
                            || matches(m, pass, a, b, new int[]{0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1})) p += 40;
                }
            }
        }
        for (int y = 0; y + 1 < n; y++)
            for (int x = 0; x + 1 < n; x++)
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) p += 3;
        long dark = 0;
        for (boolean[] row : m) for (boolean c : row) if (c) dark++;
        long total = (long) n * n;
        long k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10;
    }

    private static boolean matches(boolean[][] m, int pass, int a, int b, int[] pat) {
        for (int i = 0; i < 11; i++) {
            boolean v = pass == 0 ? m[a][b + i] : m[b + i][a];
            if (v != (pat[i] == 1)) return false;
        }
        return true;
    }
}

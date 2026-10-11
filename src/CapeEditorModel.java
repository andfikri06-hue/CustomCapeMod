package org.levimc.launcher.customcape;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Pixel model behind the in-game cape editor (see CapePauseOverlay).
 *
 * Pure Java on purpose - no android.* imports - so the logic (flood fill,
 * undo, cape-face layout, name sanitising) can be unit-tested on a PC
 * without an Android device.
 *
 * The editable area is the part of the 64x32 cape texture that Minecraft
 * actually uses: x 0..21, y 0..16. Layout of that area (from the vanilla
 * cape model, a 10x16x1 box with its UV origin at 0,0):
 *
 *   top edge     (1,0)  10x1      bottom edge (11,0) 10x1
 *   right edge   (0,1)  1x16      left edge   (11,1) 1x16
 *   OUTER face   (1,1)  10x16     <- the side people see from behind
 *   INNER face   (12,1) 10x16     <- the side facing the player's back
 */
final class CapeEditorModel {

    static final int TEX_W = 64;
    static final int TEX_H = 32;

    /** Editable region (the used part of the texture). */
    static final int W = 22;
    static final int H = 17;

    static final int[] OUTER  = {1, 1, 10, 16};
    static final int[] INNER  = {12, 1, 10, 16};
    static final int[] TOP    = {1, 0, 10, 1};
    static final int[] BOTTOM = {11, 0, 10, 1};
    static final int[] RIGHT  = {0, 1, 1, 16};
    static final int[] LEFT   = {11, 1, 1, 16};

    private static final int MAX_HISTORY = 40;

    final int[] px = new int[W * H]; // ARGB, 0 = transparent
    private final ArrayList<int[]> history = new ArrayList<int[]>();

    boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H;
    }

    int get(int x, int y) {
        return inside(x, y) ? px[y * W + x] : 0;
    }

    void set(int x, int y, int argb) {
        if (inside(x, y)) px[y * W + x] = argb;
    }

    /** Call once at the start of every stroke / action so undo() can revert it. */
    void snapshot() {
        history.add(px.clone());
        if (history.size() > MAX_HISTORY) history.remove(0);
    }

    boolean undo() {
        if (history.isEmpty()) return false;
        int[] prev = history.remove(history.size() - 1);
        System.arraycopy(prev, 0, px, 0, px.length);
        return true;
    }

    void clear() {
        snapshot();
        java.util.Arrays.fill(px, 0);
    }

    boolean isEmpty() {
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) != 0) return false;
        }
        return true;
    }

    /** Bresenham line, so fast finger drags don't leave gaps. */
    void line(int x0, int y0, int x1, int y1, int argb) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = (W + H) * 4;
        while (guard-- > 0) {
            set(x0, y0, argb);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** 4-neighbour flood fill (iterative, bounded by the grid size). */
    void fill(int x, int y, int argb) {
        if (!inside(x, y)) return;
        int target = get(x, y);
        if (target == argb) return;
        int[] stack = new int[W * H];
        int sp = 0;
        stack[sp++] = y * W + x;
        while (sp > 0) {
            int idx = stack[--sp];
            int cx = idx % W;
            int cy = idx / W;
            if (px[idx] != target) continue;
            px[idx] = argb;
            if (cx > 0     && px[idx - 1] == target) stack[sp++] = idx - 1;
            if (cx < W - 1 && px[idx + 1] == target) stack[sp++] = idx + 1;
            if (cy > 0     && px[idx - W] == target) stack[sp++] = idx - W;
            if (cy < H - 1 && px[idx + W] == target) stack[sp++] = idx + W;
        }
    }

    private static boolean regionEmpty(int[] tex, int[] r) {
        for (int y = r[1]; y < r[1] + r[3]; y++) {
            for (int x = r[0]; x < r[0] + r[2]; x++) {
                if ((tex[y * TEX_W + x] >>> 24) != 0) return false;
            }
        }
        return true;
    }

    private static int darken(int argb, float f) {
        int a = argb >>> 24;
        int r = Math.round(((argb >> 16) & 0xFF) * f);
        int g = Math.round(((argb >> 8) & 0xFF) * f);
        int b = Math.round((argb & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Builds the full 64x32 RGBA8 texture (4 bytes per pixel, R,G,B,A order)
     * ready for the native side. Faces the user left empty are filled in so
     * the cape never has see-through gaps: the INNER face is copied from the
     * OUTER face (slightly darker) and the four 1px edges from the nearest
     * OUTER border.
     */
    byte[] toRgba() {
        int[] tex = new int[TEX_W * TEX_H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                tex[y * TEX_W + x] = px[y * W + x];
            }
        }

        if (!regionEmpty(tex, OUTER)) {
            if (regionEmpty(tex, INNER)) {
                for (int dy = 0; dy < OUTER[3]; dy++) {
                    for (int dx = 0; dx < OUTER[2]; dx++) {
                        int c = tex[(OUTER[1] + dy) * TEX_W + OUTER[0] + dx];
                        tex[(INNER[1] + dy) * TEX_W + INNER[0] + dx] = darken(c, 0.82f);
                    }
                }
            }
            if (regionEmpty(tex, TOP)) {
                for (int dx = 0; dx < TOP[2]; dx++) {
                    tex[TOP[1] * TEX_W + TOP[0] + dx] = darken(tex[OUTER[1] * TEX_W + OUTER[0] + dx], 0.9f);
                }
            }
            if (regionEmpty(tex, BOTTOM)) {
                int srcY = OUTER[1] + OUTER[3] - 1;
                for (int dx = 0; dx < BOTTOM[2]; dx++) {
                    tex[BOTTOM[1] * TEX_W + BOTTOM[0] + dx] = darken(tex[srcY * TEX_W + OUTER[0] + dx], 0.9f);
                }
            }
            if (regionEmpty(tex, RIGHT)) {
                for (int dy = 0; dy < RIGHT[3]; dy++) {
                    tex[(RIGHT[1] + dy) * TEX_W + RIGHT[0]] = darken(tex[(OUTER[1] + dy) * TEX_W + OUTER[0]], 0.9f);
                }
            }
            if (regionEmpty(tex, LEFT)) {
                int srcX = OUTER[0] + OUTER[2] - 1;
                for (int dy = 0; dy < LEFT[3]; dy++) {
                    tex[(LEFT[1] + dy) * TEX_W + LEFT[0]] = darken(tex[(OUTER[1] + dy) * TEX_W + srcX], 0.9f);
                }
            }
        }

        byte[] out = new byte[TEX_W * TEX_H * 4];
        for (int i = 0; i < tex.length; i++) {
            int c = tex[i];
            out[i * 4]     = (byte) ((c >> 16) & 0xFF); // R
            out[i * 4 + 1] = (byte) ((c >> 8) & 0xFF);  // G
            out[i * 4 + 2] = (byte) (c & 0xFF);         // B
            out[i * 4 + 3] = (byte) ((c >>> 24) & 0xFF); // A
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** Keeps [A-Za-z0-9._-], max 40 chars, never empty, never starts with a dot. */
    static String sanitizeName(String raw, String fallback) {
        StringBuilder sb = new StringBuilder();
        if (raw != null) {
            for (int i = 0; i < raw.length() && sb.length() < 40; i++) {
                char c = raw.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
                sb.append(ok ? c : '_');
            }
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = s.substring(1);
        return s.length() == 0 ? fallback : s;
    }

    /** ".../zips/slimecape.zip?x=1" -> "slimecape" */
    static String nameFromUrl(String url) {
        String tail = url == null ? "" : url.trim();
        int q = tail.indexOf('?');
        if (q >= 0) tail = tail.substring(0, q);
        int h = tail.indexOf('#');
        if (h >= 0) tail = tail.substring(0, h);
        while (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) tail = tail.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        if (dot > 0) tail = tail.substring(0, dot);
        return sanitizeName(tail, "CosmosCape");
    }

    /** "/a/b/cape.png" -> "cape" */
    static String nameFromPath(String path) {
        String tail = path == null ? "" : path.trim();
        int slash = Math.max(tail.lastIndexOf('/'), tail.lastIndexOf('\\'));
        if (slash >= 0) tail = tail.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        if (dot > 0) tail = tail.substring(0, dot);
        return sanitizeName(tail, "ImportedCape");
    }

    static boolean looksLikeHttp(String s) {
        if (s == null) return false;
        String l = s.trim().toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://");
    }
}

package org.levimc.launcher.customcape;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pixel model behind the in-game cape editor (see CapePauseOverlay).
 *
 * Pure Java on purpose - no android.* imports - so the logic (flood fill,
 * undo, cape-face layout, resolution changes) can be unit-tested on a PC
 * without an Android device.
 *
 * The editable area is the part of the cape texture that Minecraft actually
 * uses. At the standard 64x32 resolution that is x 0..21, y 0..16. Layout of
 * that area (from the vanilla cape model, a 10x16x1 box with its UV origin at
 * 0,0):
 *
 *   top edge     (1,0)  10x1      bottom edge (11,0) 10x1
 *   right edge   (0,1)  1x16      left edge   (11,1) 1x16
 *   OUTER face   (1,1)  10x16     <- the side people see from behind
 *   INNER face   (12,1) 10x16     <- the side facing the player's back
 *
 * Higher resolutions are whole multiples of that: at 2x (128x64) every number
 * above is doubled, at 4x (256x128) quadrupled, and so on. A model has exactly
 * one scale; resampled() converts a drawing to another one.
 *
 * Only the two faces can be painted. Pen, eraser and fill are clipped to them,
 * so colour can never spill onto the edge strips or the gap between the faces;
 * the edge strips are generated from the OUTER face by toRgba().
 */
final class CapeEditorModel {

    /** The vanilla cape slot. Every other resolution is a whole multiple of it. */
    static final int BASE_TEX_W = 64;
    static final int BASE_TEX_H = 32;

    /** Editable region at 1x. */
    static final int BASE_W = 22;
    static final int BASE_H = 17;

    /**
     * Resolutions offered in the panel, as multiples of 64x32:
     * 64x32, 128x64, 256x128 and 640x320 (the biggest cape the native side saves).
     */
    static final int[] SCALES = {1, 2, 4, 10};

    private static final int MAX_HISTORY = 40;

    final int scale;
    /** Editable region, in cells. */
    final int w;
    final int h;
    /** Size of the texture toRgba() produces, in px. */
    final int texW;
    final int texH;

    /** {x, y, width, height} of each part of the cape, in cells. */
    final int[] outer;
    final int[] inner;
    final int[] top;
    final int[] bottom;
    final int[] right;
    final int[] left;

    final int[] px; // ARGB, 0 = transparent
    private final ArrayList<int[]> history = new ArrayList<int[]>();
    private CapeEditorModel previous;

    CapeEditorModel() {
        this(1);
    }

    CapeEditorModel(int scale) {
        this.scale = normalizeScale(scale);
        int s = this.scale;
        w = BASE_W * s;
        h = BASE_H * s;
        texW = BASE_TEX_W * s;
        texH = BASE_TEX_H * s;
        outer = rect(1, 1, 10, 16, s);
        inner = rect(12, 1, 10, 16, s);
        top = rect(1, 0, 10, 1, s);
        bottom = rect(11, 0, 10, 1, s);
        right = rect(0, 1, 1, 16, s);
        left = rect(11, 1, 1, 16, s);
        px = new int[w * h];
    }

    private static int[] rect(int x, int y, int rw, int rh, int s) {
        return new int[]{x * s, y * s, rw * s, rh * s};
    }

    // ------------------------------------------------------------------
    // Resolutions
    // ------------------------------------------------------------------

    static boolean isSupportedScale(int s) {
        for (int i = 0; i < SCALES.length; i++) {
            if (SCALES[i] == s) return true;
        }
        return false;
    }

    /** The scale itself when it is one of SCALES, 1 for anything else. */
    static int normalizeScale(int s) {
        return isSupportedScale(s) ? s : 1;
    }

    /** "128x64" */
    static String label(int scale) {
        int s = normalizeScale(scale);
        return (BASE_TEX_W * s) + "x" + (BASE_TEX_H * s);
    }

    /** Pen sizes (in cells) offered at a resolution. The biggest one covers one standard 64x32 pixel. */
    static int[] brushSizes(int scale) {
        switch (normalizeScale(scale)) {
            case 2:
                return new int[]{1, 2};
            case 4:
                return new int[]{1, 2, 4};
            case 10:
                return new int[]{1, 2, 5, 10};
            default:
                return new int[]{1};
        }
    }

    /** One standard pixel: the pen paints the same area as it does at 64x32. */
    static int defaultBrush(int scale) {
        return normalizeScale(scale);
    }

    /** The biggest offered pen size that is not bigger than {@code brush} (the smallest one if none is). */
    static int clampBrush(int scale, int brush) {
        int[] sizes = brushSizes(scale);
        int best = sizes[0];
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] <= brush) best = sizes[i];
        }
        return best;
    }

    /** The next pen size, wrapping around to the smallest. */
    static int nextBrush(int scale, int brush) {
        int[] sizes = brushSizes(scale);
        int cur = clampBrush(scale, brush);
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] == cur) return sizes[(i + 1) % sizes.length];
        }
        return sizes[0];
    }

    /**
     * Converts the drawing to another resolution and returns the new model; this
     * model stays untouched and is remembered as previous(), so the UI can undo
     * the change. Going up is lossless (every cell becomes a block of cells);
     * going down keeps, for every new cell, the colour that covers most of the
     * old block (on a tie a visible colour beats transparent).
     */
    CapeEditorModel resampled(int newScale) {
        int target = normalizeScale(newScale);
        if (target == scale) return this;
        CapeEditorModel out = new CapeEditorModel(target);
        final int s0 = scale;
        final int s1 = out.scale;
        int[] xr = ranges(out.w, s0, s1);
        int[] yr = ranges(out.h, s0, s1);
        for (int ny = 0; ny < out.h; ny++) {
            for (int nx = 0; nx < out.w; nx++) {
                if (!out.isPaintable(nx, ny)) continue;
                int c;
                if (xr[nx + 1] > xr[nx] && yr[ny + 1] > yr[ny]) {
                    c = dominant(px, w, xr[nx], xr[nx + 1], yr[ny], yr[ny + 1]);
                } else { // going up: the old cell under the middle of the new one
                    int ox = ((2 * nx + 1) * s0) / (2 * s1);
                    int oy = ((2 * ny + 1) * s0) / (2 * s1);
                    c = px[oy * w + ox];
                }
                out.px[ny * out.w + nx] = c;
            }
        }
        out.previous = this;
        this.previous = null; // one level back is enough, and it keeps old models from piling up
        return out;
    }

    /** The model this one was converted from by resampled(), or null. */
    CapeEditorModel previous() {
        return previous;
    }

    /**
     * Old-cell boundaries for every new column (or row): new cell i takes the old
     * cells [r[i], r[i+1]). A cell edge of the cape always lands on a cell edge
     * of the other resolution, so the faces keep their exact outline.
     */
    private static int[] ranges(int count, int s0, int s1) {
        int[] r = new int[count + 1];
        for (int i = 0; i <= count; i++) r[i] = (2 * s0 * i + s1 - 1) / (2 * s1);
        return r;
    }

    private static int dominant(int[] src, int stride, int x0, int x1, int y0, int y1) {
        int n = (x1 - x0) * (y1 - y0);
        int[] vals = new int[n];
        int[] cnt = new int[n];
        int used = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = src[y * stride + x];
                int k = 0;
                while (k < used && vals[k] != c) k++;
                if (k == used) {
                    vals[used] = c;
                    cnt[used] = 1;
                    used++;
                } else {
                    cnt[k]++;
                }
            }
        }
        int best = 0;
        for (int k = 1; k < used; k++) {
            boolean more = cnt[k] > cnt[best];
            boolean tieButVisible = cnt[k] == cnt[best] && (vals[best] >>> 24) == 0 && (vals[k] >>> 24) != 0;
            if (more || tieButVisible) best = k;
        }
        return vals[best];
    }

    // ------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------

    boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    private static boolean inRect(int x, int y, int[] r) {
        return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
    }

    /** The face (outer or inner) that contains the cell, or null on an edge strip, in the gap or outside the grid. */
    int[] faceAt(int x, int y) {
        if (inRect(x, y, outer)) return outer;
        if (inRect(x, y, inner)) return inner;
        return null;
    }

    /** True only for cells inside the outer or the inner face. */
    boolean isPaintable(int x, int y) {
        return faceAt(x, y) != null;
    }

    int get(int x, int y) {
        return inside(x, y) ? px[y * w + x] : 0;
    }

    /** Paints one cell. Cells outside the two faces are ignored. */
    void set(int x, int y, int argb) {
        if (inside(x, y) && isPaintable(x, y)) px[y * w + x] = argb;
    }

    /** Call once at the start of every stroke / action so undo() can revert it. */
    void snapshot() {
        history.add(px.clone());
        if (history.size() > MAX_HISTORY) history.remove(0);
    }

    /** Reverts the last stroke / action. False when there is nothing left to revert in this model. */
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

    /** Bresenham line on any paintable cells (never on the edge strips / gap). */
    void line(int x0, int y0, int x1, int y1, int argb) {
        line(x0, y0, x1, y1, argb, null, 1);
    }

    void line(int x0, int y0, int x1, int y1, int argb, int[] clip) {
        line(x0, y0, x1, y1, argb, clip, 1);
    }

    /**
     * Bresenham line, so fast finger drags don't leave gaps. Cells outside
     * {@code clip} are skipped: pass the face the stroke started in and the
     * stroke can never cross a border. {@code clip == null} allows both faces.
     * {@code brush} is the pen size in cells (a square centred on every point).
     */
    void line(int x0, int y0, int x1, int y1, int argb, int[] clip, int brush) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = dx - dy + 2; // a line has at most max(dx, |dy|) + 1 points
        while (guard-- > 0) {
            stamp(x0, y0, argb, clip, brush);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** A brush x brush square around (cx, cy); only paintable cells inside {@code clip} change. */
    private void stamp(int cx, int cy, int argb, int[] clip, int brush) {
        int b = Math.max(1, brush);
        int ox = cx - (b - 1) / 2;
        int oy = cy - (b - 1) / 2;
        for (int y = oy; y < oy + b; y++) {
            if (y < 0 || y >= h) continue;
            for (int x = ox; x < ox + b; x++) {
                if (x < 0 || x >= w) continue;
                if (clip != null && !inRect(x, y, clip)) continue;
                if (isPaintable(x, y)) px[y * w + x] = argb;
            }
        }
    }

    /**
     * 4-neighbour flood fill (iterative). It stays inside the face that
     * contains (x, y); a click on an edge strip or in the gap does nothing.
     */
    void fill(int x, int y, int argb) {
        int[] face = faceAt(x, y);
        if (face == null) return;
        int target = get(x, y);
        if (target == argb) return;
        // every cell is filled at most once and pushes at most 4 neighbours,
        // so 4 * cells + 1 entries can never overflow
        int[] stack = new int[4 * face[2] * face[3] + 4];
        int sp = 0;
        stack[sp++] = y * w + x;
        while (sp > 0) {
            int idx = stack[--sp];
            int cx = idx % w;
            int cy = idx / w;
            if (px[idx] != target) continue;
            px[idx] = argb;
            if (inRect(cx - 1, cy, face) && px[idx - 1] == target) stack[sp++] = idx - 1;
            if (inRect(cx + 1, cy, face) && px[idx + 1] == target) stack[sp++] = idx + 1;
            if (inRect(cx, cy - 1, face) && px[idx - w] == target) stack[sp++] = idx - w;
            if (inRect(cx, cy + 1, face) && px[idx + w] == target) stack[sp++] = idx + w;
        }
    }

    // ------------------------------------------------------------------
    // Texture export
    // ------------------------------------------------------------------

    private boolean regionEmpty(int[] tex, int[] r) {
        for (int y = r[1]; y < r[1] + r[3]; y++) {
            for (int x = r[0]; x < r[0] + r[2]; x++) {
                if ((tex[y * texW + x] >>> 24) != 0) return false;
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

    /** Fills {@code dst} with a darkened copy of the same-sized area whose top-left corner is (sx, sy). */
    private void fillFrom(int[] tex, int[] dst, int sx, int sy, float f) {
        for (int dy = 0; dy < dst[3]; dy++) {
            for (int dx = 0; dx < dst[2]; dx++) {
                tex[(dst[1] + dy) * texW + dst[0] + dx] = darken(tex[(sy + dy) * texW + sx + dx], f);
            }
        }
    }

    /**
     * Builds the full RGBA8 texture (texW x texH, 4 bytes per pixel, R,G,B,A
     * order) ready for the native side. Faces the user left empty are filled in
     * so the cape never has see-through gaps: the INNER face is copied from the
     * OUTER face (slightly darker) and the four edge strips from the nearest
     * OUTER border.
     */
    byte[] toRgba() {
        int[] tex = new int[texW * texH];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                tex[y * texW + x] = px[y * w + x];
            }
        }

        if (!regionEmpty(tex, outer)) {
            if (regionEmpty(tex, inner)) fillFrom(tex, inner, outer[0], outer[1], 0.82f);
            if (regionEmpty(tex, top)) fillFrom(tex, top, outer[0], outer[1], 0.9f);
            if (regionEmpty(tex, bottom)) fillFrom(tex, bottom, outer[0], outer[1] + outer[3] - bottom[3], 0.9f);
            if (regionEmpty(tex, right)) fillFrom(tex, right, outer[0], outer[1], 0.9f);
            if (regionEmpty(tex, left)) fillFrom(tex, left, outer[0] + outer[2] - left[2], outer[1], 0.9f);
        }

        byte[] out = new byte[texW * texH * 4];
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
    // Canvas layout planning (pure maths here so it can be unit-tested)
    // ------------------------------------------------------------------

    /** How the canvas screen is laid out on a given display. */
    static final class CanvasPlan {
        /** Size of one pixel cell on screen, in px. */
        final int cellPx;
        /** True: palette + tools sit next to the canvas. False: they sit below it (narrow screens). */
        final boolean sideBySide;
        /** False when this resolution would make the canvas wider than the screen. */
        final boolean fits;

        CanvasPlan(int cellPx, boolean sideBySide, boolean fits) {
            this.cellPx = cellPx;
            this.sideBySide = sideBySide;
            this.fits = fits;
        }
    }

    static final float MIN_CELL_DP = 8f;
    /** Smallest cell drawn at the higher resolutions. */
    static final int MIN_CELL_PX = 2;
    private static final float SIDE_BY_SIDE_MIN_CELL_DP = 10f;
    /** Everything on the canvas screen except the 17 pixel rows: screen margins, card padding, header, footer, hint. */
    private static final float CHROME_H_DP = 142f;
    /** Palette column (208) + gap (10) + card padding (20) + screen margins (16). */
    private static final float SIDE_EXTRA_W_DP = 10f + 208f + 20f + 16f;
    /** Card padding (20) + screen margins (16). */
    private static final float STACK_EXTRA_W_DP = 20f + 16f;

    static CanvasPlan planCanvas(int screenWpx, int screenHpx, float density) {
        return planCanvas(screenWpx, screenHpx, density, 1);
    }

    /**
     * Picks the biggest cell size that lets the whole canvas screen fit on the
     * display (the card scrolls if it still does not). Landscape phones get the
     * palette next to the canvas, narrow screens get it underneath. A higher
     * resolution gets proportionally smaller cells, so the canvas keeps about
     * the same size on screen.
     */
    static CanvasPlan planCanvas(int screenWpx, int screenHpx, float density, int scale) {
        int s = normalizeScale(scale);
        float d = density > 0f ? density : 1f;
        float wDp = screenWpx / d;
        float hDp = screenHpx / d;
        float maxCell = hDp >= 480f ? 16f : 11f;

        boolean side;
        int basePx;
        float bySideWidth = (wDp - SIDE_EXTRA_W_DP) / BASE_W;
        if (bySideWidth >= SIDE_BY_SIDE_MIN_CELL_DP) {
            float byHeight = (hDp - CHROME_H_DP) / BASE_H;
            float cell = Math.min(maxCell, Math.min(byHeight, bySideWidth));
            side = true;
            basePx = cellToPx(Math.max(MIN_CELL_DP, cell), d);
        } else {
            float byStackWidth = (wDp - STACK_EXTRA_W_DP) / BASE_W;
            side = false;
            basePx = cellToPx(Math.max(MIN_CELL_DP, Math.min(14f, byStackWidth)), d);
        }
        if (s == 1) return new CanvasPlan(basePx, side, true);

        int cell = Math.max(MIN_CELL_PX, basePx / s);
        float extraPx = (side ? SIDE_EXTRA_W_DP : STACK_EXTRA_W_DP) * d;
        boolean fits = (double) cell * BASE_W * s + extraPx <= screenWpx;
        return new CanvasPlan(cell, side, fits);
    }

    /** Whole dp, rounded DOWN to whole px, so the canvas never ends up taller than planned. */
    private static int cellToPx(float dp, float density) {
        return Math.max(1, (int) Math.floor((double) ((float) Math.floor(dp) * density)));
    }
}

/**
 * Cape names and cape files, as pure Java (no android.*), so it is unit-tested on a PC.
 * It sits in this file on purpose: the CI compile list only names the files, so a second
 * top-level class here needs no change to build.yml.
 *
 * The panel has its own on-screen keyboard (a game window that grabs the system keyboard
 * could take the focus away from Minecraft), so a name can only contain what that
 * keyboard can type: letters, digits, space, '-' and '_'. Those are all kept as they are
 * by the native file-name clean-up, so the name shown in the list is the file name.
 */
final class CapeNames {

    private CapeNames() {}

    /** Longest name the panel accepts (the native side cuts at 48 bytes). */
    static final int MAX_LEN = 28;

    /** Keyboard rows, top to bottom. Space has its own key; Shift makes the letters capitals. */
    static final String ROW_DIGITS = "1234567890";
    static final String ROW_TOP = "qwertyuiop";
    static final String ROW_MID = "asdfghjkl";
    static final String ROW_LOW = "zxcvbnm";
    static final String ROW_EXTRA = "-_";

    /** The name used when the player does not type one: "Drawing" + month day hour minute second. */
    static String autoName(Date when) {
        return "Drawing" + new SimpleDateFormat("MMddHHmmss", Locale.US).format(when);
    }

    /** True for every character the name keyboard can type. */
    static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == ' ' || c == '-' || c == '_';
    }

    /**
     * What the player typed -> a clean name. "" means "no name given": the caller then uses
     * autoName(). Anything the keyboard cannot type is dropped, runs of spaces become one,
     * the ends are trimmed and the result is cut to MAX_LEN.
     */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastSpace = true; // also drops leading spaces
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isKeyChar(c)) continue;
            if (c == ' ') {
                if (lastSpace) continue;
                lastSpace = true;
            } else {
                lastSpace = false;
            }
            sb.append(c);
        }
        if (sb.length() > MAX_LEN) sb.setLength(MAX_LEN);
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static boolean containsIgnoreCase(Collection<String> names, String name) {
        if (names == null) return false;
        for (String n : names) {
            if (n != null && n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * `base` if no cape has that name yet, else "base (2)", "base (3)", ... A save never
     * silently replaces another cape. Compared without regard to case, because the shared
     * storage the capes live in ignores case.
     */
    static String unique(String base, Collection<String> existing) {
        if (!containsIgnoreCase(existing, base)) return base;
        for (int n = 2; n < 1000; n++) {
            String candidate = base + " (" + n + ")";
            if (!containsIgnoreCase(existing, candidate)) return candidate;
        }
        return base + " (" + (System.currentTimeMillis() % 100000L) + ")";
    }

    /** The native list ("name\nname\n...") as a list. */
    static List<String> split(String names) {
        List<String> out = new ArrayList<String>();
        if (names == null || names.length() == 0) return out;
        String[] parts = names.split("\n");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().length() > 0) out.add(parts[i]);
        }
        return out;
    }

    // ---- the name being typed ------------------------------------------------

    /** `cur` plus one typed character (capital letters when shift is on); ignored when full or not typeable. */
    static String type(String cur, char c, boolean shift) {
        String s = cur == null ? "" : cur;
        if (s.length() >= MAX_LEN || !isKeyChar(c)) return s;
        if (c == ' ' && (s.length() == 0 || s.charAt(s.length() - 1) == ' ')) return s; // no leading / double spaces
        if (shift && c >= 'a' && c <= 'z') c = (char) (c - 'a' + 'A');
        return s + c;
    }

    static String backspace(String cur) {
        if (cur == null || cur.length() == 0) return "";
        return cur.substring(0, cur.length() - 1);
    }

    /** Shift switches on by itself at the start of the name and after a space, '-' or '_'. */
    static boolean wantsShift(String cur) {
        if (cur == null || cur.length() == 0) return true;
        char last = cur.charAt(cur.length() - 1);
        return last == ' ' || last == '-' || last == '_';
    }

    // ---- files ------------------------------------------------------------------

    /**
     * Deletes the cape called `name`: <dir>/<name>.json and <dir>/<name>.png (the list shows
     * one entry per name, so both go). Touches nothing else. Returns how many files were
     * deleted; 0 for an unknown or unusable name.
     */
    static int deleteCapeFiles(File dir, String name) {
        if (dir == null || name == null || name.length() == 0) return 0;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\n') >= 0
                || name.indexOf('\r') >= 0 || name.equals(".") || name.equals("..")) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int deleted = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile()) continue;
            String fn = f.getName();
            int dot = fn.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = fn.substring(dot).toLowerCase(Locale.ROOT);
            if (!ext.equals(".json") && !ext.equals(".png")) continue;
            if (!fn.substring(0, dot).equals(name)) continue;
            if (f.delete()) deleted++;
        }
        return deleted;
    }
}

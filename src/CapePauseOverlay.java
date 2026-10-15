package org.levimc.launcher.customcape;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Custom Cape UI that only exists while Minecraft's vanilla PAUSE MENU is open.
 * Select / remove / delete a cape, or draw one on a canvas (64x32 up to 640x320) and name it.
 *
 * Why this is Java instead of pl::modmenu buttons: LeviLaunchroid hides every
 * mod button / HUD draw command whenever the pause menu is open (it treats
 * them as "game overlays"), so the Mod Menu API can never show anything in
 * the pause menu. This class draws its own small floating card (a child
 * window of the game activity, same technique the launcher uses for its own
 * buttons) and shows/hides it from the launcher's own pause-menu flag
 * (PreloaderInput.nativeIsPauseMenuOpen, fed by the preloader's hooks).
 *
 * It is compiled to a .dex by CI, embedded into the native .so and loaded
 * with InMemoryDexClassLoader (see CapeCanvasBridge.cpp). It therefore only
 * uses android.* / java.* APIs directly; everything launcher-specific is
 * reached through reflection and fails soft.
 *
 * Everything that touches views runs on the main thread. File / network /
 * native work runs on a single background thread.
 */
public final class CapePauseOverlay {

    private CapePauseOverlay() {}

    static final String TAG = "CustomCape";
    private static final String PREFS = "customcape_ui";
    private static final long TICK_MS = 120L;

    // ------------------------------------------------------------------
    // Implemented in C++ (CapeCanvasBridge.cpp, bound with RegisterNatives).
    // Every one returns a human readable status line, "OK: ..." or "ERR: ...".
    // ------------------------------------------------------------------
    private static native String nativeListCapes();                       // names joined by '\n'
    private static native String nativeActiveName();                      // "" if none
    private static native String nativeEquip(String name);
    private static native String nativeRemoveCape();
    // Not used by the panel any more (the link / file import was removed). Still declared
    // because the native side registers it: a registered method the class lacks would
    // make RegisterNatives fail.
    private static native String nativeImportPng(String pngPath, String name);
    private static native String nativeSaveDrawn(byte[] rgba, int width, int height, String name);

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private static final Object LOCK = new Object();
    // The drawing. Replaced (not edited) when the resolution changes; main thread only.
    private static CapeEditorModel sModel = new CapeEditorModel();

    private static Handler sMain;
    private static ExecutorService sIo;
    private static boolean sRunning;
    private static String sCapeDir = "";
    private static Ui sUi;
    private static int sPauseStreak;
    private static boolean sLastPaused;
    private static boolean sToastShown;
    private static boolean sNoActivityLogged;

    private static Method sPausedMethod;
    private static boolean sPausedLookupFailed;
    private static Method sHudMethod;
    private static Method sMenuMethod;
    private static String sLastFlags = "";
    private static Method sActivityMethod;
    private static boolean sActivityLookupFailed;

    // Colours: black and blue. Red is only used for things that remove something.
    static final String VERSION_LABEL = "v1.4";
    static final int C_CARD = 0xF206080D;          // near black, a little see-through
    static final int C_CARD_EDGE = 0xFF1B2B52;     // thin blue edge
    static final int C_BTN = 0xFF10182A;           // buttons, chips, fields
    static final int C_BTN_DOWN = 0xFF1A2641;
    static final int C_BTN_EDGE = 0xFF1E2C4A;
    static final int C_ACCENT = 0xFF2A66F5;        // blue (fills; white text on it passes 4.5:1)
    static final int C_ACCENT_DOWN = 0xFF2458D8;
    static final int C_ACCENT_SOFT = 0xFF11204A;   // blue tint behind the selected row
    static final int C_ACCENT_LIGHT = 0xFF74A3FF;  // blue for text / outlines on black
    static final int C_ON_ACCENT = 0xFFFFFFFF;
    static final int C_TEXT = 0xFFEAF0FF;
    static final int C_MUTED = 0xFF8A97B4;
    static final int C_DANGER = 0xFFFF5A6E;        // red text / icons on black
    static final int C_DANGER_BG = 0xFF2A1019;
    static final int C_DANGER_BG_DOWN = 0xFF3C1722;
    static final int C_DANGER_EDGE = 0xFF5A1F2E;
    static final int C_DANGER_SOLID = 0xFFE11D48;  // filled red: the final "Delete"
    static final int C_DANGER_SOLID_DOWN = 0xFFBE123C;
    static final int C_FIELD = 0xFF0A1020;
    static final int C_LOCKED = 0xFF04060A;        // canvas cells that can not be painted (outside the two faces)

    // ------------------------------------------------------------------
    // Public API - called from C++ through JNI
    // ------------------------------------------------------------------

    /** Starts the pause-menu watcher. Safe to call more than once. */
    public static void start(String capeDir) {
        synchronized (LOCK) {
            sCapeDir = capeDir == null ? "" : capeDir;
            if (sMain == null) sMain = new Handler(Looper.getMainLooper());
            if (sRunning) return;
            sRunning = true;
        }
        logFile("overlay start()");
        sMain.post(sTick);
    }

    /** Stops watching and removes the floating card. */
    public static void stop() {
        final Handler h;
        synchronized (LOCK) {
            sRunning = false;
            h = sMain;
        }
        if (h == null) return;
        h.removeCallbacks(sTick);
        h.post(new Runnable() {
            @Override public void run() {
                if (sUi != null) {
                    sUi.detach();
                    sUi = null;
                }
                logFile("overlay stop()");
            }
        });
    }

    // ------------------------------------------------------------------
    // Main loop: look at the pause flag ~8x per second
    // ------------------------------------------------------------------

    private static final Runnable sTick = new Runnable() {
        @Override public void run() {
            long next = TICK_MS;
            try {
                next = tick();
            } catch (Throwable t) {
                logFile("tick failed: " + t);
            }
            boolean again;
            synchronized (LOCK) {
                again = sRunning;
            }
            if (again) sMain.postDelayed(this, next);
        }
    };

    private static long tick() {
        Activity a = currentActivity();
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            if (sUi != null) {
                sUi.detach();
                sUi = null;
            }
            sPauseStreak = 0;
            if (!sNoActivityLogged) {
                sNoActivityLogged = true;
                logFile("waiting for the game activity...");
            }
            return 500L;
        }
        sNoActivityLogged = false;

        if (sUi != null && sUi.activity != a) { // activity was recreated
            sUi.detach();
            sUi = null;
        }
        if (!sToastShown) {
            sToastShown = true;
            logFile("game activity found: " + a.getClass().getName());
            try {
                Toast.makeText(a, "Custom Cape ready - open the pause menu", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }

        boolean paused = isPaused();
        if (paused != sLastPaused) {
            sLastPaused = paused;
            logFile("pause menu " + (paused ? "OPEN" : "closed"));
        }
        // One line whenever any of the launcher's screen flags changes. If these
        // never change while playing, the launcher's hooks did not resolve.
        String flags = "pause=" + paused + " hud=" + callBool(sHudMethod) + " menu=" + callBool(sMenuMethod)
                + " focus=" + a.hasWindowFocus() + " card=" + (sUi != null && sUi.isAttached());
        if (!flags.equals(sLastFlags)) {
            sLastFlags = flags;
            logFile("flags: " + flags);
        }
        if (paused) {
            if (sPauseStreak < 1000) sPauseStreak++;
        } else {
            sPauseStreak = 0;
        }

        if (paused && sPauseStreak >= 2) { // ~240ms debounce so it never flashes
            if (sUi == null) {
                try {
                    sUi = new Ui(a);
                } catch (Throwable t) {
                    // a broken panel must never retry 8x per second
                    logFile("panel build failed: " + t);
                    return 3000L;
                }
            }
            sUi.setShown(true);
        } else if (!paused && sUi != null) {
            sUi.setShown(false);
        }
        return TICK_MS;
    }

    // ------------------------------------------------------------------
    // Launcher integration (reflection, fails soft)
    // ------------------------------------------------------------------

    private static Class<?> loadAppClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = {
                CapePauseOverlay.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };
        ClassNotFoundException last = null;
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) continue;
            try {
                return Class.forName(name, true, loaders[i]);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException(name);
    }

    private static Method findBool(Class<?> c, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Method m = c.getMethod(names[i]);
                if (m.getReturnType() == boolean.class) return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static boolean callBool(Method m) {
        if (m == null) return false;
        try {
            Object r = m.invoke(null);
            return (r instanceof Boolean) && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while the vanilla pause menu is open (flag maintained by the preloader's hooks). */
    private static boolean isPaused() {
        if (sPausedMethod == null && !sPausedLookupFailed) {
            try {
                Class<?> c = loadAppClass("org.levimc.launcher.preloader.PreloaderInput");
                // the wrappers catch UnsatisfiedLinkError, the raw natives are the fallback
                sPausedMethod = findBool(c, "isPauseMenuOpen", "nativeIsPauseMenuOpen");
                sHudMethod = findBool(c, "isHudScreenOpen", "nativeIsHudScreenOpen");
                sMenuMethod = findBool(c, "isShowingMenu", "nativeIsShowingMenu");
            } catch (Throwable t) {
                logFile("PreloaderInput lookup failed: " + t);
            }
            if (sPausedMethod == null) {
                sPausedLookupFailed = true;
                logFile("pause detection unavailable (PreloaderInput not found)");
            }
        }
        return callBool(sPausedMethod);
    }

    private static Activity currentActivity() {
        Activity a = null;
        if (sActivityMethod == null && !sActivityLookupFailed) {
            try {
                Class<?> c = loadAppClass("org.levimc.launcher.core.minecraft.MinecraftActivityState");
                sActivityMethod = c.getMethod("getCurrentActivity");
            } catch (Throwable t) {
                sActivityLookupFailed = true;
                logFile("MinecraftActivityState lookup failed, using fallback: " + t);
            }
        }
        if (sActivityMethod != null) {
            try {
                Object o = sActivityMethod.invoke(null);
                if (o instanceof Activity) a = (Activity) o;
            } catch (Throwable ignored) {
            }
        }
        if (a == null && sActivityLookupFailed) a = activityFromThread();
        return a;
    }

    /** Fallback: ask the framework for the foreground activity. */
    private static Activity activityFromThread() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) return null;
            for (Object rec : ((Map<?, ?>) map).values()) {
                Class<?> rc = rec.getClass();
                Field pf = rc.getDeclaredField("paused");
                pf.setAccessible(true);
                if (pf.getBoolean(rec)) continue;
                Field af = rc.getDeclaredField("activity");
                af.setAccessible(true);
                Object act = af.get(rec);
                if (act instanceof Activity) return (Activity) act;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    static void io(Runnable r) {
        synchronized (LOCK) {
            if (sIo == null) sIo = Executors.newSingleThreadExecutor();
        }
        sIo.execute(r);
    }

    static void ui(Runnable r) {
        Handler h = sMain;
        if (h != null) h.post(r);
    }

    /** Appends a line to <capes folder>/customcape_log.txt so problems can be read without a PC. */
    static void logFile(String msg) {
        Log.i(TAG, msg);
        try {
            if (sCapeDir.length() == 0) return;
            File dir = new File(sCapeDir);
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, "customcape_log.txt");
            if (f.length() > 100 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " [java] " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    static GradientDrawable round(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    static StateListDrawable pressable(int normal, int pressed, float radiusPx) {
        return pressable(normal, pressed, radiusPx, 0, 0);
    }

    static StateListDrawable pressable(int normal, int pressed, float radiusPx, int stroke, int strokePx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusPx, stroke, strokePx));
        s.addState(new int[]{}, round(normal, radiusPx, stroke, strokePx));
        return s;
    }

    // ------------------------------------------------------------------
    // Tiny custom views
    // ------------------------------------------------------------------

    /** Small icons drawn with lines and circles, so nothing depends on font glyphs. */
    static final class Glyph extends View {
        static final int GRIP = 0;
        static final int CHEV_DOWN = 1;
        static final int CHEV_RIGHT = 2;
        static final int CHEV_LEFT = 3;
        static final int TRASH = 4;
        static final int CHECK = 5;
        static final int DOT = 6;      // filled circle
        static final int RING = 7;     // circle outline
        static final int CURSOR = 8;   // thin rounded bar (the text cursor)

        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setGlyphColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            if (kind == GRIP) {
                p.setStyle(Paint.Style.FILL);
                float r = s * 0.07f;
                for (int i = -1; i <= 1; i++) {
                    cv.drawCircle(cx - s * 0.16f, cy + i * s * 0.24f, r, p);
                    cv.drawCircle(cx + s * 0.16f, cy + i * s * 0.24f, r, p);
                }
            } else if (kind == DOT) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy, s / 2f, p);
            } else if (kind == RING) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.14f);
                cv.drawCircle(cx, cy, s / 2f - s * 0.07f, p);
            } else if (kind == CURSOR) {
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(0f, 0f, w, h, w / 2f, w / 2f, p);
            } else if (kind == TRASH) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, s * 0.08f));
                float u = s * 0.34f;
                // lid and handle
                cv.drawLine(cx - u, cy - 0.62f * u, cx + u, cy - 0.62f * u, p);
                cv.drawLine(cx - 0.38f * u, cy - 0.62f * u, cx - 0.38f * u, cy - 0.98f * u, p);
                cv.drawLine(cx - 0.38f * u, cy - 0.98f * u, cx + 0.38f * u, cy - 0.98f * u, p);
                cv.drawLine(cx + 0.38f * u, cy - 0.98f * u, cx + 0.38f * u, cy - 0.62f * u, p);
                // body
                cv.drawLine(cx - 0.72f * u, cy - 0.38f * u, cx - 0.62f * u, cy + 0.96f * u, p);
                cv.drawLine(cx - 0.62f * u, cy + 0.96f * u, cx + 0.62f * u, cy + 0.96f * u, p);
                cv.drawLine(cx + 0.62f * u, cy + 0.96f * u, cx + 0.72f * u, cy - 0.38f * u, p);
                // ribs
                cv.drawLine(cx - 0.25f * u, cy - 0.08f * u, cx - 0.25f * u, cy + 0.66f * u, p);
                cv.drawLine(cx + 0.25f * u, cy - 0.08f * u, cx + 0.25f * u, cy + 0.66f * u, p);
            } else if (kind == CHECK) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, s * 0.12f));
                float u = s * 0.34f;
                cv.drawLine(cx - 0.9f * u, cy + 0.05f * u, cx - 0.3f * u, cy + 0.65f * u, p);
                cv.drawLine(cx - 0.3f * u, cy + 0.65f * u, cx + 0.95f * u, cy - 0.7f * u, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                float a = s * 0.18f;
                if (kind == CHEV_DOWN) {
                    cv.drawLine(cx - a, cy - a / 2f, cx, cy + a / 2f, p);
                    cv.drawLine(cx, cy + a / 2f, cx + a, cy - a / 2f, p);
                } else if (kind == CHEV_LEFT) {
                    cv.drawLine(cx + a / 2f, cy - a, cx - a / 2f, cy, p);
                    cv.drawLine(cx - a / 2f, cy, cx + a / 2f, cy + a, p);
                } else {
                    cv.drawLine(cx - a / 2f, cy - a, cx + a / 2f, cy, p);
                    cv.drawLine(cx + a / 2f, cy, cx - a / 2f, cy + a, p);
                }
            }
        }
    }

    /**
     * The pixel grid. Draws the model and applies pen / eraser / fill from touch.
     * Only the two cape faces can be painted; everything else is drawn dark and
     * locked, and a stroke stays inside the face it started in.
     *
     * The model can have many cells (up to 220 x 170 at 640x320), so the painted
     * cells are drawn from one small bitmap (one pixel per cell, scaled up without
     * smoothing) instead of one rectangle per cell.
     */
    static final class PixelView extends View {
        static final int TOOL_PEN = 0;
        static final int TOOL_ERASER = 1;
        static final int TOOL_FILL = 2;

        final CapeEditorModel model;
        final int cell;
        int color = 0xFFFFFFFF;
        int tool = TOOL_PEN;
        int brush = 1;                   // pen / eraser size, in cells

        private int lastX = -1;
        private int lastY = -1;
        Runnable onStrokeStart;          // called when a finger goes down on the canvas
        private int[] strokeFace;        // face the current stroke began in (null = not on a face yet)
        private boolean strokeSnapshot;  // undo snapshot already taken for this stroke
        private final Paint fillP = new Paint();
        private final Paint lineP = new Paint();
        private final Paint bmpP = new Paint();
        private final Rect dst = new Rect();
        private Bitmap bmp;

        PixelView(Context c, CapeEditorModel model, int cellPx) {
            super(c);
            this.model = model;
            this.cell = Math.max(1, cellPx);
            lineP.setStyle(Paint.Style.STROKE);
            bmpP.setFilterBitmap(false); // crisp square cells
            bmpP.setAntiAlias(false);
            bmpP.setDither(false);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(model.w * cell, model.h * cell);
        }

        @Override protected void onDraw(Canvas cv) {
            fillP.setStyle(Paint.Style.FILL);

            // Everything outside the two faces is locked: flat dark, never painted.
            fillP.setColor(C_LOCKED);
            cv.drawRect(0, 0, model.w * cell, model.h * cell, fillP);

            // transparent / translucent cells show a checkerboard (one square per standard 64x32 pixel)
            drawChecker(cv, model.outer);
            drawChecker(cv, model.inner);

            // the painted cells (locked cells are always transparent, so they stay dark)
            if (bmp == null) bmp = Bitmap.createBitmap(model.w, model.h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(model.px, 0, model.w, 0, 0, model.w, model.h);
            dst.set(0, 0, model.w * cell, model.h * cell);
            cv.drawBitmap(bmp, null, dst, bmpP);

            // faint grid, inside the faces only: every cell, or every standard pixel when cells are tiny
            int step = cell >= 8 ? 1 : model.scale;
            lineP.setStrokeWidth(1f);
            lineP.setColor(0x1FFFFFFF);
            drawGrid(cv, model.outer, step);
            drawGrid(cv, model.inner, step);

            // borders: outer face (blue) and inner face (white)
            lineP.setStrokeWidth(Math.max(2f, cell / 6f));
            lineP.setColor(0xCC74A3FF);
            drawBox(cv, model.outer);
            lineP.setColor(0x88FFFFFF);
            drawBox(cv, model.inner);
        }

        private void drawChecker(Canvas cv, int[] r) {
            final int s = model.scale;
            final int tile = s * cell;
            final int baseX = r[0] / s;
            final int baseY = r[1] / s;
            for (int ty = 0; ty < r[3] / s; ty++) {
                for (int tx = 0; tx < r[2] / s; tx++) {
                    fillP.setColor(((baseX + tx + baseY + ty) & 1) == 0 ? 0xFF1A2231 : 0xFF141B27);
                    float l = r[0] * cell + tx * tile;
                    float t = r[1] * cell + ty * tile;
                    cv.drawRect(l, t, l + tile, t + tile, fillP);
                }
            }
        }

        private void drawGrid(Canvas cv, int[] r, int step) {
            float left = r[0] * cell;
            float top = r[1] * cell;
            float right = (r[0] + r[2]) * cell;
            float bottom = (r[1] + r[3]) * cell;
            for (int x = r[0]; x <= r[0] + r[2]; x += step) cv.drawLine(x * cell, top, x * cell, bottom, lineP);
            for (int y = r[1]; y <= r[1] + r[3]; y += step) cv.drawLine(left, y * cell, right, y * cell, lineP);
        }

        private void drawBox(Canvas cv, int[] r) {
            cv.drawRect(r[0] * cell, r[1] * cell, (r[0] + r[2]) * cell, (r[1] + r[3]) * cell, lineP);
        }

        private int cellAt(float v, int count) {
            int c = (int) (v / cell);
            return Math.max(0, Math.min(count - 1, c));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int x = cellAt(e.getX(), model.w);
            int y = cellAt(e.getY(), model.h);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // drawing must never be mistaken for a scroll of the card
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (onStrokeStart != null) onStrokeStart.run();
                    strokeFace = model.faceAt(x, y);
                    strokeSnapshot = false;
                    lastX = -1;
                    lastY = -1;
                    if (tool == TOOL_FILL) {
                        if (strokeFace != null) {
                            snapshotOnce();
                            model.fill(x, y, color);
                            invalidate();
                        }
                    } else {
                        stroke(x, y);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tool != TOOL_FILL && (x != lastX || y != lastY)) stroke(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastX = -1;
                    lastY = -1;
                    strokeFace = null;
                    return true;
                default:
                    return true;
            }
        }

        /**
         * Pen / eraser. Paints only inside the face where the stroke began, so a
         * colour can never cross a border. A stroke that begins on an edge strip
         * or in the gap starts as soon as the finger reaches a face.
         */
        private void stroke(int x, int y) {
            if (strokeFace == null) {
                strokeFace = model.faceAt(x, y);
                if (strokeFace == null) return;
                lastX = -1;
                lastY = -1;
            }
            snapshotOnce();
            int fromX = lastX < 0 ? x : lastX;
            int fromY = lastY < 0 ? y : lastY;
            model.line(fromX, fromY, x, y, tool == TOOL_ERASER ? 0 : color, strokeFace, brush);
            lastX = x;
            lastY = y;
            invalidate();
        }

        /** One undo step per stroke, and none for a stroke that never touched a face. */
        private void snapshotOnce() {
            if (!strokeSnapshot) {
                strokeSnapshot = true;
                model.snapshot();
            }
        }
    }

    /**
     * An empty gap. A plain View is greedy: offered "at most 300dp" it measures 300dp, which would make a
     * whole keyboard row (and the card) as wide as the screen. This one only takes what it is told to.
     */
    static final class Spacer extends View {
        Spacer(Context c) {
            super(c);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(resolveSize(0, wSpec), resolveSize(0, hSpec));
        }
    }

    /** ScrollView that never grows taller than maxHeightPx, so the card always fits on the screen. */
    static final class MaxHeightScroll extends ScrollView {
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxHeightScroll(Context c) {
            super(c);
        }

        void setMaxHeightPx(int px) {
            if (px != maxHeightPx) {
                maxHeightPx = px;
                requestLayout();
            }
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            int size = MeasureSpec.getSize(heightSpec);
            if (mode == MeasureSpec.UNSPECIFIED || size > maxHeightPx) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    // ------------------------------------------------------------------
    // The floating card
    // ------------------------------------------------------------------

    private abstract static class Job {
        /** Runs on the background thread. */
        abstract String work() throws Throwable;

        /** Runs on the main thread with whatever work() returned. */
        void finish(Ui ui, String result) {
            ui.setStatus(result);
        }
    }

    static final int MODE_MENU = 0;
    static final int MODE_LIST = 1;
    static final int MODE_CANVAS = 2;
    static final int MODE_NAME = 3;

    private static final int[] PALETTE = {
            0xFF000000, 0xFF3B3B3B, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF, 0xFF6B4423, 0xFFC8A165, 0xFFF1E3C3,
            0xFFD32F2F, 0xFFF57C00, 0xFFFBC02D, 0xFF9CCC65, 0xFF2E7D32, 0xFF00897B, 0xFF29B6F6, 0xFF1565C0,
            0xFF6A1B9A, 0xFFC2185B, 0xFFF48FB1, 0xFF7B1F1F, 0xFF1B3D1B, 0xFF0D1B4D, 0xFF4AE0A0, 0xFFFFD54F
    };

    static final class Ui {
        final Activity activity;
        private final WindowManager wm;
        private final SharedPreferences prefs;
        private final float density;

        private LinearLayout card;
        private MaxHeightScroll scroll;
        private LinearLayout content;
        private LinearLayout chip;
        private Glyph chipDot;
        private LinearLayout menuBox;
        private LinearLayout listBox;
        private LinearLayout canvasBox;
        private LinearLayout nameBox;
        private LinearLayout footerList;
        private LinearLayout footerCanvas;
        private LinearLayout footerName;
        private TextView activeText;
        private TextView msgText;
        private Glyph chevron;
        private PixelView pixelView;
        private final View[] swatches = new View[PALETTE.length];
        private final TextView[] toolButtons = new TextView[3];
        private int curColor = 0xFFFFFFFF;
        private int curTool = PixelView.TOOL_PEN;
        private int curBrush = 1;          // pen size in cells
        private TextView brushButton;
        private int builtW;
        private int builtH;
        private int lastMaxHeight = -1;
        private boolean statusIsError;
        private int refitCount;

        // the Select Cape list
        private List<String> capeNames = new ArrayList<String>();
        private String pendingDelete = ""; // the cape whose "Delete?" question is open ("" = none)

        // the Save screen (name + on-screen keyboard)
        private String nameText = "";
        private boolean shiftOn = true;
        private TextView nameValue;
        private TextView nameHint;
        private TextView shiftKey;
        private final List<TextView> letterKeys = new ArrayList<TextView>();

        private WindowManager.LayoutParams lp;
        private boolean attached;
        private boolean viaWindow;
        private boolean shown;
        private boolean collapsed;
        private int mode = MODE_MENU;
        private int posX;
        private int posY;
        private String activeName = "";
        private long nextAttachTry;
        private int attachFailLogs;
        private boolean attachLogged;

        Ui(Activity a) {
            activity = a;
            wm = (WindowManager) a.getSystemService(Context.WINDOW_SERVICE);
            prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            density = a.getResources().getDisplayMetrics().density;
            // first start: top-right, so the vanilla buttons on the left stay uncovered
            int defX = Math.max(dp(12), a.getResources().getDisplayMetrics().widthPixels - dp(300));
            posX = prefs.getInt("x", defX);
            posY = prefs.getInt("y", dp(12));
            collapsed = prefs.getBoolean("collapsed", false);
            // the resolution chosen last time (the drawing itself only lives as long as the game does)
            int savedRes = CapeEditorModel.normalizeScale(prefs.getInt("res", 1));
            if (savedRes != sModel.scale) sModel = sModel.resampled(savedRes);
            curBrush = CapeEditorModel.defaultBrush(sModel.scale);
            build();
            // not attached yet: setShown(true) does that, and only while the pause menu is open
        }

        // ---- sizes -------------------------------------------------------
        int dp(float v) {
            return Math.round(v * density);
        }

        private float sp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                    activity.getResources().getDisplayMetrics());
        }

        private int hairline() {
            return Math.max(1, dp(1));
        }

        // ---- view factory -------------------------------------------------
        private TextView label(String text, float sizeSp, int color, boolean bold) {
            TextView t = new TextView(activity);
            t.setText(text);
            t.setTextColor(color);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(sizeSp));
            if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setIncludeFontPadding(false);
            return t;
        }

        /** A click listener that can never throw into the game: a failure is logged and shown instead. */
        private View.OnClickListener safe(final Runnable onClick) {
            return new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        onClick.run();
                    } catch (Throwable t) {
                        logFile("click failed: " + t);
                        setStatus("ERR: " + t);
                    }
                }
            };
        }

        /**
         * style: 0 normal, 1 primary (blue), 2 danger (red on dark red), 3 solid red.
         * Red is only ever used for things that remove something.
         */
        private TextView button(String text, int style, Runnable onClick) {
            int fg = C_TEXT;
            int bg = C_BTN;
            int down = C_BTN_DOWN;
            int edge = C_BTN_EDGE;
            if (style == 1) {
                fg = C_ON_ACCENT;
                bg = C_ACCENT;
                down = C_ACCENT_DOWN;
                edge = C_ACCENT;
            } else if (style == 2) {
                fg = C_DANGER;
                bg = C_DANGER_BG;
                down = C_DANGER_BG_DOWN;
                edge = C_DANGER_EDGE;
            } else if (style == 3) {
                fg = C_ON_ACCENT;
                bg = C_DANGER_SOLID;
                down = C_DANGER_SOLID_DOWN;
                edge = C_DANGER_SOLID;
            }
            TextView b = label(text, 13f, fg, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(10), dp(12), dp(10));
            b.setMinHeight(dp(42));
            b.setClickable(true);
            b.setBackground(pressable(bg, down, dp(12), edge, hairline()));
            b.setOnClickListener(safe(onClick));
            return b;
        }

        /** Smaller button for the toolbar and the pinned footers. Same styles as button(). */
        private TextView compactButton(String text, int style, Runnable onClick) {
            TextView b = button(text, style, onClick);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f));
            b.setPadding(dp(8), dp(7), dp(8), dp(7));
            b.setMinHeight(dp(34));
            b.setSingleLine(true);
            return b;
        }

        /** Rounded bar that holds a row of segments (resolution, tools). */
        private LinearLayout segmentedBar() {
            LinearLayout bar = row();
            bar.setPadding(dp(2), dp(2), dp(2), dp(2));
            bar.setBackground(round(C_BTN, dp(12), C_BTN_EDGE, hairline()));
            return bar;
        }

        /** One choice inside a segmentedBar(); the selected one is a blue pill. Text size is in dp so it always fits. */
        private TextView segment(String text, float textDp, boolean on, Runnable onClick) {
            TextView t = label(text, 11f, C_TEXT, true);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(3), dp(6), dp(3), dp(6));
            t.setMinHeight(dp(28));
            t.setClickable(true);
            styleSegment(t, on);
            t.setOnClickListener(safe(onClick));
            return t;
        }

        private void styleSegment(TextView t, boolean on) {
            t.setBackground(on
                    ? pressable(C_ACCENT, C_ACCENT_DOWN, dp(10))
                    : pressable(0x00000000, C_BTN_DOWN, dp(10)));
            t.setTextColor(on ? C_ON_ACCENT : C_MUTED);
        }

        private LinearLayout row() {
            LinearLayout r = new LinearLayout(activity);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setBaselineAligned(false); // otherwise buttons with different font sizes get shifted against each other
            return r;
        }

        private LinearLayout column() {
            LinearLayout r = new LinearLayout(activity);
            r.setOrientation(LinearLayout.VERTICAL);
            return r;
        }

        private LinearLayout.LayoutParams weighted(int marginDp) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(dp(marginDp), dp(marginDp), dp(marginDp), dp(marginDp));
            return p;
        }

        private LinearLayout.LayoutParams fullWidth(int topDp) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(topDp);
            return p;
        }

        // ---- build ----------------------------------------------------------
        private void build() {
            card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            card.setBackground(round(C_CARD, dp(20), C_CARD_EDGE, hairline()));
            card.setMinimumWidth(dp(280));

            // header = drag handle (it never scrolls)
            LinearLayout header = row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(2), dp(2), dp(2), dp(6));
            Glyph grip = new Glyph(activity, Glyph.GRIP, C_MUTED);
            header.addView(grip, new LinearLayout.LayoutParams(dp(22), dp(24)));
            TextView title = label("Custom Cape", 16f, C_TEXT, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(6);
            header.addView(title, tlp);
            TextView ver = label(VERSION_LABEL, 10f, C_ACCENT_LIGHT, true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            vlp.leftMargin = dp(8);
            header.addView(ver, vlp);
            chevron = new Glyph(activity, collapsed ? Glyph.CHEV_RIGHT : Glyph.CHEV_DOWN, C_MUTED);
            chevron.setClickable(true);
            chevron.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    setCollapsed(!collapsed);
                }
            });
            header.addView(chevron, new LinearLayout.LayoutParams(dp(34), dp(28)));
            installDrag(header);
            card.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // The middle part scrolls when the screen is too short for it.
            scroll = new MaxHeightScroll(activity);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.setScrollbarFadingEnabled(false); // always show the bar when there is more to scroll
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // status chip: which cape is on
            chip = row();
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(6), dp(12), dp(6));
            chip.setBackground(round(C_BTN, dp(20), C_BTN_EDGE, hairline()));
            chipDot = new Glyph(activity, Glyph.DOT, C_MUTED);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dlp.rightMargin = dp(8);
            chip.addView(chipDot, dlp);
            activeText = label("Loading...", 12f, C_TEXT, false);
            activeText.setSingleLine(true);
            activeText.setEllipsize(TextUtils.TruncateAt.END);
            activeText.setMaxWidth(dp(230));
            chip.addView(activeText);
            content.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            buildMenu();
            buildList();
            buildCanvas();
            buildNameBox();
            content.addView(menuBox);
            content.addView(listBox);
            content.addView(canvasBox);
            content.addView(nameBox);

            // Status line: pinned below the scrolling part so feedback is always visible.
            msgText = label("", 12f, C_MUTED, false);
            msgText.setMaxLines(3);
            msgText.setEllipsize(TextUtils.TruncateAt.END);
            msgText.setMaxWidth(dp(256)); // wraps (up to 3 lines) instead of making the card wider
            msgText.setVisibility(View.GONE);
            card.addView(msgText, fullWidth(8));

            // Pinned buttons of the list / canvas / save screens (always reachable, never scrolled away).
            footerList = row();
            footerList.addView(compactButton("Back", 0, new Runnable() {
                @Override public void run() {
                    pendingDelete = "";
                    setMode(MODE_MENU);
                }
            }), weighted(2));
            card.addView(footerList, fullWidth(6));

            footerCanvas = row();
            footerCanvas.addView(compactButton("Close", 0, new Runnable() {
                @Override public void run() {
                    setMode(MODE_MENU);
                }
            }), weighted(2));
            footerCanvas.addView(compactButton("Save", 1, new Runnable() {
                @Override public void run() {
                    openNameScreen();
                }
            }), weighted(2));
            card.addView(footerCanvas, fullWidth(6));

            footerName = row();
            footerName.addView(compactButton("Back", 0, new Runnable() {
                @Override public void run() {
                    setMode(MODE_CANVAS);
                }
            }), weighted(2));
            footerName.addView(compactButton("Save", 1, new Runnable() {
                @Override public void run() {
                    confirmSave();
                }
            }), weighted(2));
            card.addView(footerName, fullWidth(6));

            applyMode();
        }

        private void buildMenu() {
            menuBox = column();
            menuBox.setPadding(0, dp(8), 0, 0);

            LinearLayout r1 = row();
            r1.addView(button("Select Cape", 1, new Runnable() {
                @Override public void run() {
                    showList();
                }
            }), weighted(3));
            r1.addView(button("Canvas", 0, new Runnable() {
                @Override public void run() {
                    showCanvas();
                }
            }), weighted(3));
            menuBox.addView(r1);

            LinearLayout r2 = row();
            r2.addView(button("Remove Cape", 2, new Runnable() {
                @Override public void run() {
                    removeCape();
                }
            }), weighted(3));
            menuBox.addView(r2);
        }

        private void buildList() {
            // The card's own scroll area scrolls this list; its Back button is pinned (footerList).
            listBox = column();
            listBox.setPadding(0, dp(8), 0, 0);
        }

        /**
         * Builds the canvas screen for the current display size and resolution (also
         * used to rebuild it when either changes). The canvas keeps about the same
         * size on screen at every resolution: more cells, smaller cells.
         */
        private void buildCanvas() {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            builtW = dm.widthPixels;
            builtH = dm.heightPixels;
            CapeEditorModel.CanvasPlan plan = CapeEditorModel.planCanvas(builtW, builtH, density, sModel.scale);
            if (!plan.fits) { // a very small / low-density display: this resolution would be wider than the screen
                logFile("resolution " + CapeEditorModel.label(sModel.scale) + " does not fit this screen, using 64x32");
                sModel = sModel.resampled(1);
                plan = CapeEditorModel.planCanvas(builtW, builtH, density, 1);
            }
            final CapeEditorModel model = sModel;
            curBrush = CapeEditorModel.clampBrush(model.scale, curBrush);
            logFile("canvas layout: " + CapeEditorModel.label(model.scale) + " cell=" + plan.cellPx
                    + "px sideBySide=" + plan.sideBySide + " screen=" + builtW + "x" + builtH + " density=" + density);
            final int cell = plan.cellPx;
            final int canvasW = cell * model.w;
            final int canvasH = cell * model.h;

            canvasBox = column();
            canvasBox.setPadding(0, dp(6), 0, 0);

            TextView hint = label("Blue = outside, White = inside (auto-copied if empty)", 11f, C_MUTED, false);
            hint.setMaxWidth(plan.sideBySide ? canvasW + dp(10 + 208) : Math.max(canvasW, dp(208)));
            canvasBox.addView(hint);

            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(plan.sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(6);
            canvasBox.addView(body, bp);

            pixelView = new PixelView(activity, model, cell);
            pixelView.color = curColor;
            pixelView.tool = curTool;
            pixelView.brush = curBrush;
            pixelView.onStrokeStart = new Runnable() {
                @Override public void run() {
                    if (statusIsError) setStatus(""); // drawing answers "Canvas is empty."
                }
            };
            body.addView(pixelView, new LinearLayout.LayoutParams(canvasW, canvasH));

            LinearLayout side = column();
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (plan.sideBySide) sideLp.leftMargin = dp(10);
            else sideLp.topMargin = dp(8);
            body.addView(side, sideLp);

            // resolution: one segment per size that fits this screen, the current one is the blue pill
            LinearLayout resBar = segmentedBar();
            for (int i = 0; i < CapeEditorModel.SCALES.length; i++) {
                final int s = CapeEditorModel.SCALES[i];
                if (!CapeEditorModel.planCanvas(builtW, builtH, density, s).fits) continue;
                resBar.addView(segment(CapeEditorModel.label(s), 10f, s == model.scale, new Runnable() {
                    @Override public void run() {
                        setResolution(s);
                    }
                }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            side.addView(resBar);

            // palette: 8 colours per row
            final int cols = 8;
            final int sw = dp(20);
            LinearLayout palette = column();
            palette.setPadding(0, dp(6), 0, 0);
            for (int r = 0; r * cols < PALETTE.length; r++) {
                LinearLayout pr = row();
                pr.setGravity(Gravity.CENTER_HORIZONTAL);
                for (int c = 0; c < cols && r * cols + c < PALETTE.length; c++) {
                    final int idx = r * cols + c;
                    View v = new View(activity);
                    v.setClickable(true);
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View x) {
                            curColor = PALETTE[idx];
                            if (pixelView != null) pixelView.color = curColor;
                            if (curTool == PixelView.TOOL_ERASER) selectTool(PixelView.TOOL_PEN);
                            refreshSwatches();
                        }
                    });
                    swatches[idx] = v;
                    LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(sw, sw);
                    lpS.setMargins(dp(2), dp(2), dp(2), dp(2));
                    pr.addView(v, lpS);
                }
                palette.addView(pr);
            }
            side.addView(palette);
            refreshSwatches();

            // tools: one segmented control
            LinearLayout toolBar = segmentedBar();
            String[] toolNames = {"Pen", "Erase", "Fill"};
            for (int i = 0; i < toolNames.length; i++) {
                final int tool = i;
                toolButtons[i] = segment(toolNames[i], 12f, false, new Runnable() {
                    @Override public void run() {
                        selectTool(tool);
                    }
                });
                toolBar.addView(toolButtons[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            side.addView(toolBar, fullWidth(6));
            selectTool(curTool);

            LinearLayout t2 = row();
            t2.addView(compactButton("Undo", 0, new Runnable() {
                @Override public void run() {
                    undoStep();
                }
            }), weighted(2));
            t2.addView(compactButton("Clear", 0, new Runnable() {
                @Override public void run() {
                    sModel.clear();
                    pixelView.invalidate();
                }
            }), weighted(2));
            // pen size: only useful when there are more cells than pixels in a 64x32 cape
            brushButton = compactButton("", 0, new Runnable() {
                @Override public void run() {
                    cycleBrush();
                }
            });
            brushButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); // dp: "Size 10" must fit its slot at any font scale
            brushButton.setVisibility(model.scale > 1 ? View.VISIBLE : View.GONE);
            updateBrushButton();
            t2.addView(brushButton, weighted(2));
            side.addView(t2, fullWidth(4));
        }

        /** Converts the drawing to another resolution and rebuilds the canvas screen. */
        private void setResolution(int s) {
            if (s == sModel.scale) return;
            boolean smaller = s < sModel.scale;
            sModel = sModel.resampled(s);
            afterResolutionChange();
            setStatus(CapeEditorModel.label(s) + (smaller ? ": fine detail merged. " : ". ")
                    + "Undo goes back.");
        }

        /** Undo; once nothing is left to undo, step back across the last resolution change. */
        private void undoStep() {
            if (sModel.undo()) {
                pixelView.invalidate();
                return;
            }
            CapeEditorModel before = sModel.previous();
            if (before != null) {
                sModel = before;
                afterResolutionChange();
                setStatus("Back to " + CapeEditorModel.label(sModel.scale) + ".");
            }
        }

        private void afterResolutionChange() {
            curBrush = CapeEditorModel.defaultBrush(sModel.scale);
            prefs.edit().putInt("res", sModel.scale).apply();
            logFile("resolution: " + CapeEditorModel.label(sModel.scale));
            rebuildCanvas();
        }

        private void cycleBrush() {
            curBrush = CapeEditorModel.nextBrush(sModel.scale, curBrush);
            if (pixelView != null) pixelView.brush = curBrush;
            updateBrushButton();
        }

        private void updateBrushButton() {
            if (brushButton != null) brushButton.setText("Size " + curBrush);
        }

        /** Replaces the canvas screen with a freshly built one. */
        private void rebuildCanvas() {
            int idx = content.indexOfChild(canvasBox);
            content.removeView(canvasBox);
            try {
                buildCanvas(); // also records the screen size, so a failure is not retried on every tick
            } finally {
                content.addView(canvasBox, Math.max(0, idx));
            }
            applyMode();
            clampAfterLayout();
        }

        /** Called whenever the card is about to be shown: rebuilds the canvas if the screen size changed. */
        private void rebuildCanvasIfScreenChanged() {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            if (dm.widthPixels == builtW && dm.heightPixels == builtH) return;
            try {
                rebuildCanvas();
            } catch (Throwable t) {
                builtW = dm.widthPixels;
                builtH = dm.heightPixels;
                logFile("canvas relayout failed: " + t);
            }
        }

        private void refreshSwatches() {
            for (int i = 0; i < swatches.length; i++) {
                if (swatches[i] == null) continue;
                boolean sel = PALETTE[i] == curColor;
                swatches[i].setBackground(round(PALETTE[i], dp(6), sel ? 0xFFFFFFFF : 0x44FFFFFF,
                        sel ? Math.max(2, dp(2)) : hairline()));
            }
        }

        private void selectTool(int tool) {
            curTool = tool;
            if (pixelView != null) pixelView.tool = tool;
            for (int i = 0; i < toolButtons.length; i++) {
                if (toolButtons[i] != null) styleSegment(toolButtons[i], i == tool);
            }
        }

        // ---- the Save screen: name + on-screen keyboard ---------------------------
        // The panel never opens the system keyboard: a window that takes the keyboard
        // takes the focus away from Minecraft. This small keyboard types the only
        // characters a cape name can have (see CapeNames).
        private void buildNameBox() {
            nameBox = column();
            nameBox.setPadding(0, dp(8), 0, 0);
            nameBox.setMinimumWidth(dp(300));

            LinearLayout heading = row();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(label("Name your cape", 14f, C_TEXT, true));
            TextView sub = label("optional", 11f, C_MUTED, false);
            sub.setPadding(dp(8), 0, 0, 0);
            heading.addView(sub);
            nameBox.addView(heading);

            FrameLayout field = new FrameLayout(activity);
            field.setBackground(round(C_FIELD, dp(12), C_BTN_EDGE, hairline()));
            field.setPadding(dp(12), dp(0), dp(12), dp(0));
            field.setMinimumHeight(dp(44));
            nameHint = label("Empty = automatic name", 14f, C_MUTED, false);
            nameHint.setPadding(dp(6), 0, 0, 0); // starts after the cursor instead of under it
            field.addView(nameHint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.START));
            LinearLayout valueRow = row();
            valueRow.setGravity(Gravity.CENTER_VERTICAL);
            nameValue = label("", 15f, C_TEXT, true);
            nameValue.setSingleLine(true);
            nameValue.setEllipsize(TextUtils.TruncateAt.START); // a long name shows its end, where you are typing
            nameValue.setMaxWidth(dp(262));
            valueRow.addView(nameValue);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Math.max(2, dp(2)), dp(18));
            clp.leftMargin = dp(2);
            valueRow.addView(new Glyph(activity, Glyph.CURSOR, C_ACCENT_LIGHT), clp);
            field.addView(valueRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL | Gravity.START));
            nameBox.addView(field, fullWidth(8));

            // key height follows the screen: tall enough to hit, short enough to leave room on a small phone
            int screenDp = Math.round(activity.getResources().getDisplayMetrics().heightPixels / density);
            int keyDp = Math.max(26, Math.min(36, (screenDp - 250) / 5));
            letterKeys.clear();
            nameBox.addView(keyRow(CapeNames.ROW_DIGITS, keyDp, 0f, null, null), fullWidth(8));
            nameBox.addView(keyRow(CapeNames.ROW_TOP, keyDp, 0f, null, null), fullWidth(0));
            nameBox.addView(keyRow(CapeNames.ROW_MID, keyDp, 0.5f, null, null), fullWidth(0));

            shiftKey = key("Shift", keyDp, 1.5f, new Runnable() {
                @Override public void run() {
                    shiftOn = !shiftOn;
                    updateNameField();
                }
            });
            TextView del = key("Del", keyDp, 1.5f, new Runnable() {
                @Override public void run() {
                    nameText = CapeNames.backspace(nameText);
                    shiftOn = CapeNames.wantsShift(nameText);
                    updateNameField();
                }
            });
            nameBox.addView(keyRow(CapeNames.ROW_LOW, keyDp, 0f, shiftKey, del), fullWidth(0));

            LinearLayout last = row();
            last.addView(typeKey('-', keyDp, 1.5f));
            last.addView(typeKey('_', keyDp, 1.5f));
            last.addView(key("space", keyDp, 7f, new Runnable() {
                @Override public void run() {
                    pressKey(' ');
                }
            }));
            nameBox.addView(last, fullWidth(0));
            updateNameField();
        }

        /** A row of letter / digit keys; `pad` adds half-key gaps at both ends; `left` / `right` are optional special keys. */
        private LinearLayout keyRow(String chars, int keyDp, float pad, TextView left, TextView right) {
            LinearLayout r = row();
            if (pad > 0f) r.addView(new Spacer(activity), new LinearLayout.LayoutParams(0, 1, pad));
            if (left != null) r.addView(left);
            for (int i = 0; i < chars.length(); i++) {
                TextView k = typeKey(chars.charAt(i), keyDp, 1f);
                if (chars.charAt(i) >= 'a' && chars.charAt(i) <= 'z') letterKeys.add(k);
                r.addView(k);
            }
            if (right != null) r.addView(right);
            if (pad > 0f) r.addView(new Spacer(activity), new LinearLayout.LayoutParams(0, 1, pad));
            return r;
        }

        private TextView typeKey(final char c, int keyDp, float weight) {
            return key(String.valueOf(c), keyDp, weight, new Runnable() {
                @Override public void run() {
                    pressKey(c);
                }
            });
        }

        private TextView key(String text, int keyDp, float weight, Runnable onClick) {
            TextView k = label(text, 12f, C_TEXT, true);
            k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            k.setGravity(Gravity.CENTER);
            k.setSingleLine(true);
            k.setClickable(true);
            k.setBackground(pressable(C_BTN, C_BTN_DOWN, dp(8), C_BTN_EDGE, hairline()));
            k.setOnClickListener(safe(onClick));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(keyDp), weight);
            p.setMargins(dp(2), dp(2), dp(2), dp(2));
            k.setLayoutParams(p);
            return k;
        }

        private void pressKey(char c) {
            nameText = CapeNames.type(nameText, c, shiftOn);
            shiftOn = CapeNames.wantsShift(nameText);
            updateNameField();
        }

        private void updateNameField() {
            if (nameValue == null) return;
            nameValue.setText(nameText);
            nameHint.setVisibility(nameText.length() == 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < letterKeys.size(); i++) {
                TextView k = letterKeys.get(i);
                String t = k.getText().toString().toLowerCase(Locale.US);
                k.setText(shiftOn ? t.toUpperCase(Locale.US) : t);
            }
            if (shiftKey != null) {
                shiftKey.setTextColor(shiftOn ? C_ACCENT_LIGHT : C_TEXT);
                shiftKey.setBackground(shiftOn
                        ? pressable(C_ACCENT_SOFT, C_BTN_DOWN, dp(8), C_ACCENT, hairline())
                        : pressable(C_BTN, C_BTN_DOWN, dp(8), C_BTN_EDGE, hairline()));
            }
        }

        // ---- window -----------------------------------------------------------
        private boolean tryAttach() {
            if (attached) return true;
            try {
                lp = new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = posX;
                lp.y = posY;
                lp.token = activity.getWindow().getDecorView().getWindowToken();
                card.setVisibility(View.GONE);
                wm.addView(card, lp);
                viaWindow = true;
                attached = true;
                if (!attachLogged) {
                    attachLogged = true;
                    logFile("card attached as window (it is removed again when the pause menu closes)");
                }
                return true;
            } catch (Throwable t) {
                if (attachFailLogs++ < 4) logFile("window attach failed (" + t + "), trying content view");
            }
            try {
                ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
                if (content == null) return false;
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START);
                fp.leftMargin = posX;
                fp.topMargin = posY;
                card.setVisibility(View.GONE);
                content.addView(card, fp);
                viaWindow = false;
                attached = true;
                if (!attachLogged) {
                    attachLogged = true;
                    logFile("card attached to content view");
                }
                return true;
            } catch (Throwable t) {
                if (attachFailLogs++ < 4) logFile("content attach failed: " + t);
                return false;
            }
        }

        boolean isAttached() {
            return attached;
        }

        void detach() {
            shown = false;
            if (!attached) return;
            attached = false;
            try {
                if (viaWindow) {
                    wm.removeView(card);
                } else if (card.getParent() instanceof ViewGroup) {
                    ((ViewGroup) card.getParent()).removeView(card);
                }
            } catch (Throwable ignored) {
            }
        }

        /**
         * true: attach the card and show it. false: take it out of the game's
         * window hierarchy completely, so while you play (HUD, inventory, chat...)
         * nothing of this mod is attached to the game window at all.
         */
        void setShown(boolean s) {
            if (!s) {
                if (attached) detach();
                return;
            }
            if (!attached) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now < nextAttachTry) return;
                if (!tryAttach()) {
                    nextAttachTry = now + 3000L; // do not retry (and log) every tick
                    return;
                }
            }
            if (shown) return;
            shown = true;
            rebuildCanvasIfScreenChanged();
            card.setVisibility(View.VISIBLE);
            applyMaxHeight();
            refreshActive();
            clampAfterLayout();
        }

        // ---- dragging -----------------------------------------------------------
        private void installDrag(View handle) {
            handle.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;
                private int startX;
                private int startY;

                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startX = posX;
                            startY = posY;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            moveTo(startX + Math.round(e.getRawX() - downX),
                                    startY + Math.round(e.getRawY() - downY));
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            prefs.edit().putInt("x", posX).putInt("y", posY).apply();
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        private void moveTo(int x, int y) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int maxX = Math.max(0, dm.widthPixels - Math.max(card.getWidth(), dp(120)));
            int maxY = Math.max(0, dm.heightPixels - Math.max(card.getHeight(), dp(40)));
            posX = Math.max(0, Math.min(maxX, x));
            posY = Math.max(0, Math.min(maxY, y));
            if (!attached) return;
            if (viaWindow) {
                lp.x = posX;
                lp.y = posY;
                try {
                    wm.updateViewLayout(card, lp);
                } catch (Throwable ignored) {
                }
            } else if (card.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) card.getLayoutParams();
                fp.leftMargin = posX;
                fp.topMargin = posY;
                card.setLayoutParams(fp);
            }
        }

        /** After the card changes size (mode switch) keep it fully on screen. */
        private void clampAfterLayout() {
            final ViewTreeObserver vto = card.getViewTreeObserver();
            if (!vto.isAlive()) return;
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    ViewTreeObserver o = card.getViewTreeObserver();
                    if (o.isAlive()) o.removeOnGlobalLayoutListener(this);
                    // the card has its final width now, so the status line height is known better: fit again
                    int before = lastMaxHeight;
                    applyMaxHeight();
                    if (lastMaxHeight != before && refitCount++ < 3) {
                        clampAfterLayout(); // the size changes once more: clamp after that layout
                        return;
                    }
                    refitCount = 0;
                    moveTo(posX, posY);
                }
            });
        }

        // ---- modes ----------------------------------------------------------------
        private void setCollapsed(boolean c) {
            collapsed = c;
            prefs.edit().putBoolean("collapsed", c).apply();
            chevron.setVisibility(View.GONE);
            ViewGroup parent = (ViewGroup) chevron.getParent();
            int idx = parent.indexOfChild(chevron);
            parent.removeView(chevron);
            chevron = new Glyph(activity, c ? Glyph.CHEV_RIGHT : Glyph.CHEV_DOWN, C_MUTED);
            chevron.setClickable(true);
            chevron.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    setCollapsed(!collapsed);
                }
            });
            parent.addView(chevron, idx, new LinearLayout.LayoutParams(dp(34), dp(28)));
            applyMode();
            clampAfterLayout();
        }

        private void setMode(int m) {
            mode = m;
            if (collapsed) {
                collapsed = false;
                prefs.edit().putBoolean("collapsed", false).apply();
            }
            applyMode();
            clampAfterLayout();
        }

        private void applyMode() {
            boolean open = !collapsed;
            scroll.setVisibility(open ? View.VISIBLE : View.GONE);
            chip.setVisibility(open && (mode == MODE_MENU || mode == MODE_LIST) ? View.VISIBLE : View.GONE);
            menuBox.setVisibility(open && mode == MODE_MENU ? View.VISIBLE : View.GONE);
            listBox.setVisibility(open && mode == MODE_LIST ? View.VISIBLE : View.GONE);
            canvasBox.setVisibility(open && mode == MODE_CANVAS ? View.VISIBLE : View.GONE);
            nameBox.setVisibility(open && mode == MODE_NAME ? View.VISIBLE : View.GONE);
            footerList.setVisibility(open && mode == MODE_LIST ? View.VISIBLE : View.GONE);
            footerCanvas.setVisibility(open && mode == MODE_CANVAS ? View.VISIBLE : View.GONE);
            footerName.setVisibility(open && mode == MODE_NAME ? View.VISIBLE : View.GONE);
            msgText.setVisibility(open && msgText.getText().length() > 0 ? View.VISIBLE : View.GONE);
            scroll.scrollTo(0, 0);
            applyMaxHeight();
            if (open && mode == MODE_CANVAS && pixelView != null) pixelView.invalidate();
        }

        /** The scrolling part may use whatever the pinned parts and the screen margins leave over. */
        private void applyMaxHeight() {
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            boolean footer = !collapsed && mode != MODE_MENU;
            boolean status = !collapsed && msgText.getVisibility() == View.VISIBLE;
            int statusPx = 0;
            if (status) {
                // lines the status text needs (a little pessimistic on purpose), 3 at most
                float textW = msgText.getPaint().measureText(msgText.getText().toString());
                float cardW = card.getWidth();
                float availW = Math.max(dp(256), cardW > 0 ? cardW - dp(24) : dp(256));
                int lines = Math.min(3, Math.max(1, (int) Math.ceil(textW / (availW * 0.92f))));
                statusPx = dp(8) + Math.round(lines * msgText.getTextSize() * 1.25f);
            }
            int reserved = dp(16)                  // 8dp free above and below the card
                    + dp(22)                       // card padding
                    + dp(36)                       // header
                    + (footer ? dp(48) : 0)        // pinned Back / Close + Save row
                    + statusPx;                    // pinned status line
            int max = Math.max(dp(90), screenH - reserved);
            scroll.setMaxHeightPx(max);
            if (max != lastMaxHeight) {
                lastMaxHeight = max;
                logFile("scroll area max height " + Math.round(max / density) + "dp (screen "
                        + Math.round(screenH / density) + "dp)");
            }
        }

        // ---- status ------------------------------------------------------------------
        void setStatus(String raw) {
            if (raw == null) raw = "";
            boolean err = raw.startsWith("ERR:");
            boolean ok = raw.startsWith("OK:");
            statusIsError = err;
            String text = (err || ok) ? raw.substring(raw.indexOf(':') + 1).trim() : raw.trim();
            msgText.setTextColor(err ? C_DANGER : ok ? C_ACCENT_LIGHT : C_MUTED);
            msgText.setText(text);
            msgText.setVisibility(text.length() == 0 || collapsed ? View.GONE : View.VISIBLE);
            applyMaxHeight();
        }

        private void refreshActive() {
            run(new Job() {
                @Override String work() {
                    return nativeActiveName();
                }

                @Override void finish(Ui ui, String result) {
                    ui.activeName = result == null ? "" : result;
                    boolean on = ui.activeName.length() > 0;
                    ui.activeText.setText(on ? "Active: " + ui.activeName : "No cape selected");
                    ui.activeText.setTextColor(on ? C_TEXT : C_MUTED);
                    ui.chipDot.setGlyphColor(on ? C_ACCENT_LIGHT : C_MUTED);
                    if (ui.mode == MODE_LIST) ui.renderList(); // the highlighted row follows the active cape
                }
            });
        }

        private void run(final Job job) {
            io(new Runnable() {
                @Override public void run() {
                    String r;
                    try {
                        r = job.work();
                    } catch (Throwable t) {
                        logFile("job failed: " + t);
                        r = "ERR: " + t.getMessage();
                    }
                    final String res = r == null ? "" : r;
                    ui(new Runnable() {
                        @Override public void run() {
                            try {
                                job.finish(Ui.this, res);
                            } catch (Throwable t) {
                                logFile("job finish failed: " + t);
                            }
                        }
                    });
                }
            });
        }

        // ---- actions ---------------------------------------------------------------------
        private void showCanvas() {
            setStatus("");
            setMode(MODE_CANVAS);
        }

        private void showList() {
            pendingDelete = "";
            setMode(MODE_LIST);
            listBox.removeAllViews();
            listBox.addView(label("Loading list...", 12f, C_MUTED, false));
            loadList();
        }

        /** Asks the native side for the capes on disk and draws the list. */
        private void loadList() {
            run(new Job() {
                @Override String work() {
                    return nativeListCapes();
                }

                @Override void finish(Ui ui, String result) {
                    ui.capeNames = CapeNames.split(result);
                    ui.renderList();
                }
            });
        }

        private void renderList() {
            listBox.removeAllViews();
            if (capeNames.isEmpty()) {
                TextView empty = label("No capes yet. Use Canvas, or add a .png to the customcape folder.", 12f, C_MUTED, false);
                empty.setMaxWidth(dp(260));
                empty.setPadding(dp(4), dp(8), dp(4), dp(8));
                listBox.addView(empty);
                return;
            }
            for (int i = 0; i < capeNames.size(); i++) {
                String name = capeNames.get(i);
                View row = name.equals(pendingDelete) ? confirmRow(name) : capeRow(name);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(6);
                listBox.addView(row, rp);
            }
        }

        /** A cape in the list: tap the row to wear it, tap the bin to delete it. */
        private View capeRow(final String name) {
            boolean active = name.equals(activeName);
            LinearLayout r = row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(2), dp(4), dp(2));
            r.setMinimumHeight(dp(44));
            r.setClickable(true);
            r.setBackground(active
                    ? round(C_ACCENT_SOFT, dp(12), C_ACCENT, hairline())
                    : pressable(C_BTN, C_BTN_DOWN, dp(12), C_BTN_EDGE, hairline()));
            r.setOnClickListener(safe(new Runnable() {
                @Override public void run() {
                    equip(name);
                }
            }));

            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dotLp.rightMargin = dp(10);
            r.addView(new Glyph(activity, active ? Glyph.DOT : Glyph.RING, active ? C_ACCENT_LIGHT : C_MUTED), dotLp);

            TextView nm = label(name, 13f, C_TEXT, active);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout bin = new LinearLayout(activity);
            bin.setGravity(Gravity.CENTER);
            bin.setClickable(true);
            bin.setBackground(pressable(0x00000000, C_DANGER_BG, dp(10)));
            bin.setOnClickListener(safe(new Runnable() {
                @Override public void run() {
                    pendingDelete = name;
                    renderList();
                }
            }));
            bin.addView(new Glyph(activity, Glyph.TRASH, C_DANGER), new LinearLayout.LayoutParams(dp(20), dp(20)));
            r.addView(bin, new LinearLayout.LayoutParams(dp(40), dp(40)));
            return r;
        }

        /** The "Delete?" question that replaces a row. Nothing is deleted before the red button is pressed. */
        private View confirmRow(final String name) {
            LinearLayout box = column();
            box.setPadding(dp(12), dp(10), dp(12), dp(8));
            box.setBackground(round(C_DANGER_BG, dp(12), C_DANGER_EDGE, hairline()));

            TextView q = label("Delete " + name + "?", 13f, C_TEXT, true);
            q.setSingleLine(true);
            q.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(q);
            TextView note = label("The cape file is removed for good.", 11f, C_MUTED, false);
            note.setPadding(0, dp(2), 0, dp(6));
            box.addView(note);

            LinearLayout btns = row();
            btns.addView(compactButton("Cancel", 0, new Runnable() {
                @Override public void run() {
                    pendingDelete = "";
                    renderList();
                }
            }), weighted(2));
            btns.addView(compactButton("Delete", 3, new Runnable() {
                @Override public void run() {
                    deleteCape(name);
                }
            }), weighted(2));
            box.addView(btns);
            return box;
        }

        private void equip(final String name) {
            setStatus("Selecting " + name + "...");
            run(new Job() {
                @Override String work() {
                    return nativeEquip(name);
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                    ui.setMode(MODE_MENU);
                }
            });
        }

        /** Takes the cape off. The file stays: it is still in Select Cape (delete it there). */
        private void removeCape() {
            run(new Job() {
                @Override String work() {
                    return nativeRemoveCape();
                }

                @Override void finish(Ui ui, String result) {
                    boolean ok = result != null && result.startsWith("OK:");
                    ui.setStatus(ok ? "OK: Cape removed. It stays in Select Cape; delete it there to erase it." : result);
                    ui.refreshActive();
                }
            });
        }

        /** Deletes the cape file(s) for good. The active cape is taken off first, so no selection is left pointing at nothing. */
        private void deleteCape(final String name) {
            final boolean wasActive = name.equals(activeName);
            final String dir = sCapeDir;
            pendingDelete = "";
            setStatus("Deleting " + name + "...");
            run(new Job() {
                @Override String work() {
                    if (dir.length() == 0) return "ERR: The cape folder is unknown.";
                    if (wasActive) nativeRemoveCape();
                    int n = CapeNames.deleteCapeFiles(new File(dir), name);
                    if (n == 0) return "ERR: Could not delete " + name + " (file missing or storage is read-only).";
                    logFile("deleted cape " + name + " (" + n + " file(s))");
                    return "OK: Deleted " + name + ".";
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                    ui.loadList();
                }
            });
        }

        /** Save pressed on the canvas: ask for an (optional) name first. */
        private void openNameScreen() {
            if (sModel.isEmpty()) {
                setStatus("ERR: Canvas is empty.");
                return;
            }
            setStatus("");
            shiftOn = CapeNames.wantsShift(nameText);
            updateNameField();
            setMode(MODE_NAME);
        }

        /** Save pressed on the name screen: no name typed = the automatic "Drawing..." name. */
        private void confirmSave() {
            if (sModel.isEmpty()) {
                setStatus("ERR: Canvas is empty.");
                return;
            }
            final byte[] rgba = sModel.toRgba();
            final int texW = sModel.texW;
            final int texH = sModel.texH;
            final String typed = CapeNames.clean(nameText);
            setStatus("Saving...");
            run(new Job() {
                @Override String work() {
                    // never replace another cape by accident: a taken name gets " (2)" and so on
                    List<String> existing = CapeNames.split(nativeListCapes());
                    String name = CapeNames.unique(typed.length() == 0 ? CapeNames.autoName(new Date()) : typed, existing);
                    return nativeSaveDrawn(rgba, texW, texH, name);
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                    if (result != null && result.startsWith("OK:")) {
                        ui.nameText = "";
                        ui.shiftOn = true;
                        ui.updateNameField();
                        ui.setMode(MODE_MENU);
                    }
                }
            });
        }
    }
}

package org.levimc.launcher.customcape;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Custom Cape UI that only exists while Minecraft's vanilla PAUSE MENU is open.
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
    private static native String nativeImportPng(String pngPath, String name);
    private static native String nativeSaveDrawn(byte[] rgba, int width, int height, String name);

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private static final Object LOCK = new Object();
    private static final CapeEditorModel sModel = new CapeEditorModel();

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

    // Colours (same mint accent as the launcher's own accent buttons).
    static final int C_CARD = 0xF2151A20;
    static final int C_CARD_EDGE = 0x554AE0A0;
    static final int C_BTN = 0xFF242B33;
    static final int C_BTN_DOWN = 0xFF34404B;
    static final int C_ACCENT = 0xFF4AE0A0;
    static final int C_ACCENT_DOWN = 0xFF39C58C;
    static final int C_ON_ACCENT = 0xFF08130E;
    static final int C_TEXT = 0xFFE9EEF2;
    static final int C_MUTED = 0xFF8E9BA6;
    static final int C_DANGER = 0xFFFF7A7A;
    static final int C_FIELD = 0xFF0F1318;

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
                Toast.makeText(a, "Custom Cape aktif - buka pause menu", Toast.LENGTH_LONG).show();
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
        String flags = "pause=" + paused + " hud=" + callBool(sHudMethod) + " menu=" + callBool(sMenuMethod);
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
            if (sUi == null) sUi = new Ui(a);
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
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusPx, 0, 0));
        s.addState(new int[]{}, round(normal, radiusPx, 0, 0));
        return s;
    }

    // ------------------------------------------------------------------
    // Tiny custom views
    // ------------------------------------------------------------------

    /** Drag grip (dots) / chevrons, drawn so we do not depend on font glyphs. */
    static final class Glyph extends View {
        static final int GRIP = 0;
        static final int CHEV_DOWN = 1;
        static final int CHEV_RIGHT = 2;

        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
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
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                float a = s * 0.18f;
                if (kind == CHEV_DOWN) {
                    cv.drawLine(cx - a, cy - a / 2f, cx, cy + a / 2f, p);
                    cv.drawLine(cx, cy + a / 2f, cx + a, cy - a / 2f, p);
                } else {
                    cv.drawLine(cx - a / 2f, cy - a, cx + a / 2f, cy, p);
                    cv.drawLine(cx + a / 2f, cy, cx - a / 2f, cy + a, p);
                }
            }
        }
    }

    /** The pixel grid. Draws the model and applies pen / eraser / fill from touch. */
    static final class PixelView extends View {
        static final int TOOL_PEN = 0;
        static final int TOOL_ERASER = 1;
        static final int TOOL_FILL = 2;

        final CapeEditorModel model;
        final int cell;
        int color = 0xFFFFFFFF;
        int tool = TOOL_PEN;

        private int lastX = -1;
        private int lastY = -1;
        private final Paint fillP = new Paint();
        private final Paint lineP = new Paint();

        PixelView(Context c, CapeEditorModel model, int cellPx) {
            super(c);
            this.model = model;
            this.cell = cellPx;
            lineP.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(CapeEditorModel.W * cell, CapeEditorModel.H * cell);
        }

        @Override protected void onDraw(Canvas cv) {
            fillP.setStyle(Paint.Style.FILL);
            for (int y = 0; y < CapeEditorModel.H; y++) {
                for (int x = 0; x < CapeEditorModel.W; x++) {
                    int c = model.px[y * CapeEditorModel.W + x];
                    float l = x * cell;
                    float t = y * cell;
                    if ((c >>> 24) != 255) { // transparent or translucent: checkerboard underneath
                        fillP.setColor(((x + y) & 1) == 0 ? 0xFF2B333B : 0xFF222930);
                        cv.drawRect(l, t, l + cell, t + cell, fillP);
                    }
                    if ((c >>> 24) != 0) {
                        fillP.setColor(c);
                        cv.drawRect(l, t, l + cell, t + cell, fillP);
                    }
                }
            }
            // faint grid
            lineP.setStrokeWidth(1f);
            lineP.setColor(0x22FFFFFF);
            for (int x = 0; x <= CapeEditorModel.W; x++) {
                cv.drawLine(x * cell, 0, x * cell, CapeEditorModel.H * cell, lineP);
            }
            for (int y = 0; y <= CapeEditorModel.H; y++) {
                cv.drawLine(0, y * cell, CapeEditorModel.W * cell, y * cell, lineP);
            }
            // outer face (green) and inner face (white)
            lineP.setStrokeWidth(Math.max(2f, cell / 6f));
            lineP.setColor(0xCC4AE0A0);
            cv.drawRect(CapeEditorModel.OUTER[0] * cell, CapeEditorModel.OUTER[1] * cell,
                    (CapeEditorModel.OUTER[0] + CapeEditorModel.OUTER[2]) * cell,
                    (CapeEditorModel.OUTER[1] + CapeEditorModel.OUTER[3]) * cell, lineP);
            lineP.setColor(0x88FFFFFF);
            cv.drawRect(CapeEditorModel.INNER[0] * cell, CapeEditorModel.INNER[1] * cell,
                    (CapeEditorModel.INNER[0] + CapeEditorModel.INNER[2]) * cell,
                    (CapeEditorModel.INNER[1] + CapeEditorModel.INNER[3]) * cell, lineP);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int x = (int) (e.getX() / cell);
            int y = (int) (e.getY() / cell);
            x = Math.max(0, Math.min(CapeEditorModel.W - 1, x));
            y = Math.max(0, Math.min(CapeEditorModel.H - 1, y));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    model.snapshot();
                    if (tool == TOOL_FILL) {
                        model.fill(x, y, color);
                        lastX = -1;
                        lastY = -1;
                    } else {
                        model.line(x, y, x, y, tool == TOOL_ERASER ? 0 : color);
                        lastX = x;
                        lastY = y;
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tool != TOOL_FILL && (x != lastX || y != lastY)) {
                        int fx = lastX < 0 ? x : lastX;
                        int fy = lastY < 0 ? y : lastY;
                        model.line(fx, fy, x, y, tool == TOOL_ERASER ? 0 : color);
                        lastX = x;
                        lastY = y;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastX = -1;
                    lastY = -1;
                    return true;
                default:
                    return true;
            }
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
        private LinearLayout menuBox;
        private LinearLayout listBox;
        private LinearLayout listItems;
        private LinearLayout canvasBox;
        private TextView activeText;
        private TextView msgText;
        private Glyph chevron;
        private PixelView pixelView;
        private final View[] swatches = new View[PALETTE.length];
        private final TextView[] toolButtons = new TextView[3];

        private WindowManager.LayoutParams lp;
        private boolean attached;
        private boolean viaWindow;
        private boolean shown;
        private boolean collapsed;
        private int mode = MODE_MENU;
        private int posX;
        private int posY;
        private String activeName = "";
        private Dialog dialog;
        private long nextAttachTry;
        private int attachFailLogs;

        Ui(Activity a) {
            activity = a;
            wm = (WindowManager) a.getSystemService(Context.WINDOW_SERVICE);
            prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            density = a.getResources().getDisplayMetrics().density;
            // first start: top-right, so the vanilla buttons on the left stay uncovered
            int defX = Math.max(dp(12), a.getResources().getDisplayMetrics().widthPixels - dp(290));
            posX = prefs.getInt("x", defX);
            posY = prefs.getInt("y", dp(12));
            collapsed = prefs.getBoolean("collapsed", false);
            build();
            tryAttach();
        }

        // ---- sizes -------------------------------------------------------
        int dp(float v) {
            return Math.round(v * density);
        }

        private float sp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                    activity.getResources().getDisplayMetrics());
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

        /** style: 0 normal, 1 accent, 2 danger */
        private TextView button(String text, int style, final Runnable onClick) {
            TextView b = label(text, 13f, style == 1 ? C_ON_ACCENT : style == 2 ? C_DANGER : C_TEXT, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(10), dp(12), dp(10));
            b.setMinHeight(dp(40));
            b.setClickable(true);
            b.setBackground(style == 1
                    ? pressable(C_ACCENT, C_ACCENT_DOWN, dp(10))
                    : pressable(C_BTN, C_BTN_DOWN, dp(10)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        onClick.run();
                    } catch (Throwable t) {
                        logFile("click failed: " + t);
                        setStatus("ERR: " + t);
                    }
                }
            });
            return b;
        }

        private LinearLayout row() {
            LinearLayout r = new LinearLayout(activity);
            r.setOrientation(LinearLayout.HORIZONTAL);
            return r;
        }

        private LinearLayout.LayoutParams weighted(int marginDp) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(dp(marginDp), dp(marginDp), dp(marginDp), dp(marginDp));
            return p;
        }

        // ---- build ----------------------------------------------------------
        private void build() {
            card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(10));
            card.setBackground(round(C_CARD, dp(16), C_CARD_EDGE, Math.max(1, dp(1))));
            card.setMinimumWidth(dp(250));

            // header = drag handle
            LinearLayout header = row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(2), dp(2), dp(2), dp(6));
            Glyph grip = new Glyph(activity, Glyph.GRIP, C_MUTED);
            header.addView(grip, new LinearLayout.LayoutParams(dp(22), dp(24)));
            TextView title = label("Custom Cape", 15f, C_TEXT, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = dp(6);
            header.addView(title, tlp);
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

            // status
            activeText = label("Memuat...", 12f, C_MUTED, false);
            card.addView(activeText);
            msgText = label("", 12f, C_MUTED, false);
            msgText.setMaxLines(3);
            msgText.setEllipsize(TextUtils.TruncateAt.END);
            msgText.setMaxWidth(dp(330));
            msgText.setVisibility(View.GONE);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(3);
            card.addView(msgText, mlp);

            buildMenu();
            buildList();
            buildCanvas();
            card.addView(menuBox);
            card.addView(listBox);
            card.addView(canvasBox);
            applyMode();
        }

        private void buildMenu() {
            menuBox = new LinearLayout(activity);
            menuBox.setOrientation(LinearLayout.VERTICAL);
            menuBox.setPadding(0, dp(6), 0, 0);

            LinearLayout r1 = row();
            r1.addView(button("Pilih Cape", 1, new Runnable() {
                @Override public void run() {
                    showList();
                }
            }), weighted(3));
            r1.addView(button("Lepas Cape", 2, new Runnable() {
                @Override public void run() {
                    removeCape();
                }
            }), weighted(3));
            menuBox.addView(r1);

            LinearLayout r2 = row();
            r2.addView(button("Tempel Link", 0, new Runnable() {
                @Override public void run() {
                    showLinkDialog();
                }
            }), weighted(3));
            r2.addView(button("Canvas", 0, new Runnable() {
                @Override public void run() {
                    setMode(MODE_CANVAS);
                }
            }), weighted(3));
            menuBox.addView(r2);
        }

        private void buildList() {
            listBox = new LinearLayout(activity);
            listBox.setOrientation(LinearLayout.VERTICAL);
            listBox.setPadding(0, dp(6), 0, 0);

            ScrollView sv = new ScrollView(activity);
            listItems = new LinearLayout(activity);
            listItems.setOrientation(LinearLayout.VERTICAL);
            sv.addView(listItems, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listBox.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(6);
            listBox.addView(button("Kembali", 0, new Runnable() {
                @Override public void run() {
                    setMode(MODE_MENU);
                }
            }), blp);
        }

        private void buildCanvas() {
            canvasBox = new LinearLayout(activity);
            canvasBox.setOrientation(LinearLayout.VERTICAL);
            canvasBox.setPadding(0, dp(4), 0, 0);

            // pick a cell size that fits this screen
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int byHeight = (int) ((dm.heightPixels * 0.86f - dp(150)) / CapeEditorModel.H);
            int byWidth = (int) ((dm.widthPixels * 0.94f - dp(150)) / CapeEditorModel.W);
            int cell = Math.max(dp(7), Math.min(dp(15), Math.min(byHeight, byWidth)));

            TextView hint = label("Hijau = sisi luar, putih = sisi dalam (disalin otomatis kalau kosong)",
                    11f, C_MUTED, false);
            hint.setMaxWidth(cell * CapeEditorModel.W + dp(150));
            canvasBox.addView(hint);

            LinearLayout body = row();
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(6);
            canvasBox.addView(body, bp);

            pixelView = new PixelView(activity, sModel, cell);
            body.addView(pixelView, new LinearLayout.LayoutParams(
                    cell * CapeEditorModel.W, cell * CapeEditorModel.H));

            LinearLayout side = new LinearLayout(activity);
            side.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams sp0 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp0.leftMargin = dp(10);
            body.addView(side, sp0);

            // palette: 4 columns
            int sw = dp(26);
            for (int r = 0; r < PALETTE.length / 4; r++) {
                LinearLayout pr = row();
                for (int c = 0; c < 4; c++) {
                    final int idx = r * 4 + c;
                    View v = new View(activity);
                    v.setClickable(true);
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View x) {
                            pixelView.color = PALETTE[idx];
                            if (pixelView.tool == PixelView.TOOL_ERASER) selectTool(PixelView.TOOL_PEN);
                            refreshSwatches();
                        }
                    });
                    swatches[idx] = v;
                    LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(sw, sw);
                    lpS.setMargins(dp(2), dp(2), dp(2), dp(2));
                    pr.addView(v, lpS);
                }
                side.addView(pr);
            }
            refreshSwatches();

            // tools
            LinearLayout t1 = row();
            toolButtons[PixelView.TOOL_PEN] = smallButton("Pena", new Runnable() {
                @Override public void run() {
                    selectTool(PixelView.TOOL_PEN);
                }
            });
            toolButtons[PixelView.TOOL_ERASER] = smallButton("Hapus", new Runnable() {
                @Override public void run() {
                    selectTool(PixelView.TOOL_ERASER);
                }
            });
            toolButtons[PixelView.TOOL_FILL] = smallButton("Isi", new Runnable() {
                @Override public void run() {
                    selectTool(PixelView.TOOL_FILL);
                }
            });
            t1.addView(toolButtons[0], weighted(2));
            t1.addView(toolButtons[1], weighted(2));
            t1.addView(toolButtons[2], weighted(2));
            LinearLayout.LayoutParams t1p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t1p.topMargin = dp(6);
            side.addView(t1, t1p);
            selectTool(PixelView.TOOL_PEN);

            LinearLayout t2 = row();
            t2.addView(smallButton("Undo", new Runnable() {
                @Override public void run() {
                    sModel.undo();
                    pixelView.invalidate();
                }
            }), weighted(2));
            t2.addView(smallButton("Bersih", new Runnable() {
                @Override public void run() {
                    sModel.clear();
                    pixelView.invalidate();
                }
            }), weighted(2));
            side.addView(t2);

            LinearLayout t3 = row();
            t3.addView(button("Simpan", 1, new Runnable() {
                @Override public void run() {
                    saveDrawing();
                }
            }), weighted(2));
            t3.addView(button("Tutup", 0, new Runnable() {
                @Override public void run() {
                    setMode(MODE_MENU);
                }
            }), weighted(2));
            side.addView(t3);
        }

        private TextView smallButton(String text, Runnable r) {
            TextView b = button(text, 0, r);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f));
            b.setPadding(dp(6), dp(8), dp(6), dp(8));
            b.setMinHeight(dp(34));
            b.setMinWidth(dp(46));
            return b;
        }

        private void refreshSwatches() {
            for (int i = 0; i < swatches.length; i++) {
                boolean sel = pixelView != null && PALETTE[i] == pixelView.color;
                swatches[i].setBackground(round(PALETTE[i], dp(6), sel ? 0xFFFFFFFF : 0x44FFFFFF,
                        sel ? Math.max(2, dp(2)) : Math.max(1, dp(1))));
            }
        }

        private void selectTool(int tool) {
            pixelView.tool = tool;
            for (int i = 0; i < toolButtons.length; i++) {
                boolean on = i == tool;
                toolButtons[i].setBackground(on
                        ? pressable(C_ACCENT, C_ACCENT_DOWN, dp(10))
                        : pressable(C_BTN, C_BTN_DOWN, dp(10)));
                toolButtons[i].setTextColor(on ? C_ON_ACCENT : C_TEXT);
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
                logFile("card attached as window");
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
                logFile("card attached to content view");
                return true;
            } catch (Throwable t) {
                if (attachFailLogs++ < 4) logFile("content attach failed: " + t);
                return false;
            }
        }

        void detach() {
            dismissDialog();
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

        void setShown(boolean s) {
            if (!attached) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now < nextAttachTry) return;
                if (!tryAttach()) {
                    nextAttachTry = now + 3000L; // do not retry (and log) every tick
                    return;
                }
            }
            if (shown == s) return;
            shown = s;
            card.setVisibility(s ? View.VISIBLE : View.GONE);
            if (s) {
                refreshActive();
                clampAfterLayout();
            } else {
                dismissDialog();
            }
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
            int body = collapsed ? View.GONE : View.VISIBLE;
            activeText.setVisibility(body);
            if (collapsed) msgText.setVisibility(View.GONE);
            menuBox.setVisibility(!collapsed && mode == MODE_MENU ? View.VISIBLE : View.GONE);
            listBox.setVisibility(!collapsed && mode == MODE_LIST ? View.VISIBLE : View.GONE);
            canvasBox.setVisibility(!collapsed && mode == MODE_CANVAS ? View.VISIBLE : View.GONE);
            if (!collapsed && mode == MODE_CANVAS && pixelView != null) pixelView.invalidate();
        }

        // ---- status ------------------------------------------------------------------
        void setStatus(String raw) {
            if (raw == null) raw = "";
            boolean err = raw.startsWith("ERR:");
            boolean ok = raw.startsWith("OK:");
            String text = (err || ok) ? raw.substring(raw.indexOf(':') + 1).trim() : raw.trim();
            msgText.setTextColor(err ? C_DANGER : ok ? C_ACCENT : C_MUTED);
            msgText.setText(text);
            msgText.setVisibility(text.length() == 0 || collapsed ? View.GONE : View.VISIBLE);
        }

        private void refreshActive() {
            run(new Job() {
                @Override String work() {
                    return nativeActiveName();
                }

                @Override void finish(Ui ui, String result) {
                    ui.activeName = result == null ? "" : result;
                    ui.activeText.setText(ui.activeName.length() == 0
                            ? "Belum ada cape aktif" : "Aktif: " + ui.activeName);
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
        private void showList() {
            setMode(MODE_LIST);
            listItems.removeAllViews();
            listItems.addView(label("Memuat daftar...", 12f, C_MUTED, false));
            run(new Job() {
                @Override String work() {
                    return nativeListCapes();
                }

                @Override void finish(Ui ui, String result) {
                    ui.fillList(result);
                }
            });
        }

        private void fillList(String names) {
            listItems.removeAllViews();
            String[] parts = (names == null || names.length() == 0) ? new String[0] : names.split("\n");
            if (parts.length == 0) {
                TextView empty = label("Belum ada cape. Pakai Tempel Link atau Canvas.", 12f, C_MUTED, false);
                empty.setPadding(dp(4), dp(8), dp(4), dp(8));
                listItems.addView(empty);
                return;
            }
            for (int i = 0; i < parts.length; i++) {
                final String name = parts[i].trim();
                if (name.length() == 0) continue;
                boolean active = name.equals(activeName);
                TextView row = label(name, 13f, active ? C_ON_ACCENT : C_TEXT, active);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setMinHeight(dp(38));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setClickable(true);
                row.setBackground(active
                        ? pressable(C_ACCENT, C_ACCENT_DOWN, dp(10))
                        : pressable(0x00000000, C_BTN_DOWN, dp(10)));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        equip(name);
                    }
                });
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(2);
                listItems.addView(row, rp);
            }
        }

        private void equip(final String name) {
            setStatus("Memasang " + name + "...");
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

        private void removeCape() {
            run(new Job() {
                @Override String work() {
                    return nativeRemoveCape();
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                }
            });
        }

        private void saveDrawing() {
            if (sModel.isEmpty()) {
                setStatus("ERR: Canvas masih kosong.");
                return;
            }
            final byte[] rgba = sModel.toRgba();
            final String name = "Gambar" + new SimpleDateFormat("MMddHHmmss", Locale.US).format(new Date());
            setStatus("Menyimpan...");
            run(new Job() {
                @Override String work() {
                    return nativeSaveDrawn(rgba, CapeEditorModel.TEX_W, CapeEditorModel.TEX_H, name);
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                    if (result != null && result.startsWith("OK:")) ui.setMode(MODE_MENU);
                }
            });
        }

        /** Link (Bedrock Cosmos zip/png) or a local file path. */
        private void importFromText(String raw) {
            final String src = raw == null ? "" : raw.trim();
            if (src.length() == 0) {
                setStatus("ERR: Link kosong.");
                return;
            }
            setStatus("Mengunduh cape...");
            final File cache = new File(activity.getApplicationContext().getCacheDir(), "customcape_dl");
            run(new Job() {
                @Override String work() {
                    if (CapeEditorModel.looksLikeHttp(src)) {
                        String png = CapeDownloadHelper.downloadAndExtractCape(src, cache.getAbsolutePath());
                        if (png == null) {
                            String why = CapeDownloadHelper.lastError;
                            return "ERR: " + (why == null || why.length() == 0
                                    ? "Gagal mengunduh. Cek link dan internet." : why);
                        }
                        return nativeImportPng(png, CapeEditorModel.nameFromUrl(src));
                    }
                    File f = new File(src);
                    if (!f.isFile()) return "ERR: File tidak ditemukan.";
                    return nativeImportPng(f.getAbsolutePath(), CapeEditorModel.nameFromPath(src));
                }

                @Override void finish(Ui ui, String result) {
                    ui.setStatus(result);
                    ui.refreshActive();
                }
            });
        }

        // ---- link dialog ---------------------------------------------------------------------
        private void dismissDialog() {
            if (dialog != null) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
                dialog = null;
            }
        }

        private String clipboardText() {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return "";
                CharSequence t = cd.getItemAt(0).coerceToText(activity);
                return t == null ? "" : t.toString().trim();
            } catch (Throwable t) {
                return "";
            }
        }

        private void showLinkDialog() {
            dismissDialog();
            final Dialog d = new Dialog(activity);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(16), dp(18), dp(14));
            box.setBackground(round(0xFF161A1F, dp(18), C_CARD_EDGE, Math.max(1, dp(1))));

            box.addView(label("Tempel link cape", 16f, C_TEXT, true));
            TextView hint = label("Link Bedrock Cosmos (.zip atau .png), atau path file di HP.", 12f, C_MUTED, false);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(4);
            hp.bottomMargin = dp(10);
            box.addView(hint, hp);

            final EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setTextColor(C_TEXT);
            input.setHintTextColor(C_MUTED);
            input.setHint("https://capes.bedrock-cosmos.app/zips/nama.zip");
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13f));
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setBackground(round(C_FIELD, dp(10), 0x33FFFFFF, Math.max(1, dp(1))));
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
            String clip = clipboardText();
            if (CapeEditorModel.looksLikeHttp(clip)) {
                input.setText(clip);
                input.setSelection(clip.length());
            }
            box.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout buttons = row();
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(12);
            buttons.addView(button("Tempel", 0, new Runnable() {
                @Override public void run() {
                    String c = clipboardText();
                    if (c.length() > 0) {
                        input.setText(c);
                        input.setSelection(c.length());
                    }
                }
            }), weighted(3));
            buttons.addView(button("Batal", 0, new Runnable() {
                @Override public void run() {
                    dismissDialog();
                }
            }), weighted(3));
            buttons.addView(button("Import", 1, new Runnable() {
                @Override public void run() {
                    String text = input.getText().toString();
                    dismissDialog();
                    importFromText(text);
                }
            }), weighted(3));
            box.addView(buttons, bp);

            d.setContentView(box);
            Window w = d.getWindow();
            if (w != null) {
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                int width = Math.min(dp(440), (int) (dm.widthPixels * 0.9f));
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                // Show without focus first so the game's immersive mode is not
                // interrupted, then copy its system-UI flags and take focus.
                w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
            input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        String text = input.getText().toString();
                        dismissDialog();
                        importFromText(text);
                        return true;
                    }
                    return false;
                }
            });
            dialog = d;
            d.show();
            if (w != null) {
                try {
                    w.getDecorView().setSystemUiVisibility(
                            activity.getWindow().getDecorView().getSystemUiVisibility());
                } catch (Throwable ignored) {
                }
                w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
            input.requestFocus();
        }
    }
}

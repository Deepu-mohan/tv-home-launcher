package com.tvhome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Window;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight Apple-TV-style home screen. No services, no polling, no remote-key interception:
 * Home/Back/volume are left to the system. Everything is built from plain views.
 */
public class HomeActivity extends Activity {

    private static final String PREFS = "tvhome";
    private static final String K_DOCK = "dock";          // comma-separated packages, in order
    private static final String K_HIDDEN = "hidden";      // string set of packages
    private static final String K_LAST = "last";          // last focused package
    private static final String K_DOCK_N = "dockN";       // number of dock slots
    private static final String K_DOCK_SIZE = "dockSize"; // 0 small, 1 medium, 2 large
    private static final String K_CITY = "city";          // "Name" or "Name,CC" (country code narrows the search)
    private static final String K_GEO = "geo";            // cached lookup: city|lat|lon
    private static final String K_WX = "wx2";             // cached weather JSON: city, now, feels, code, day, fetched, next hours
    private static final long WX_REFRESH_MS = 60 * 60 * 1000L;
    private static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.android.vending", "com.cltv.hybrid"};

    private static class App {
        String pkg;
        String label;
        ComponentName cn;
        Drawable image;
        int mode = GlassTileView.PLAIN;   // how the artwork is drawn on its tile
    }

    // ---------------------------------------------------------------- glass look (no blur: gradients only)

    /**
     * The one wallpaper: abstract glass spheres on deep navy, drawn from shaders
     * once into a half-size bitmap and then just scaled, so it costs nothing per frame. No image file.
     */
    private static class WallpaperDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bmp;

        @Override protected void onBoundsChange(Rect b) {
            if (bmp != null) bmp.recycle();
            bmp = null;
            if (b.width() <= 0 || b.height() <= 0) return;
            render();
        }

        private int style;   // 0 near black, 1 warm sunset, 2 spheres

        void setStyle(int s) {
            if (s == style && bmp != null) return;
            style = s;
            if (!getBounds().isEmpty()) render();
            invalidateSelf();
        }

        /** Full logical resolution (not half), then film grain so smooth gradients never band on a big 4K panel. */
        private void render() {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (bmp != null) bmp.recycle();
            int w = b.width(), h = b.height();
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (style == 3 && photoLoader != null) {   // a real photo, full screen
                Bitmap ph = photoLoader.get();
                if (ph != null) {
                    bmp.recycle();
                    bmp = ph;
                    scrim(new Canvas(bmp), bmp.getWidth(), bmp.getHeight());
                    return;
                }
            }
            Canvas c = new Canvas(bmp);
            switch (style) {
                case 1: sunset(c, w, h); break;
                case 2: paint(c, w, h); break;
                default: nearBlack(c, w, h); break;
            }
            grain(bmp, w, h, style == 0 ? 2 : 3);
        }

        java.util.function.Supplier<Bitmap> photoLoader;   // returns a mutable bitmap the size of the screen, or null

        /** Soft dark fades at the top and bottom so the white clock, title and dock text stay readable on a bright photo. */
        private void scrim(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h * 0.42f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.42f, p);
            p.setShader(new LinearGradient(0, h * 0.60f, 0, h, 0x00000000, 0x80000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.60f, w, h, p);
            p.setShader(null);
        }

        private void grain(Bitmap bm, int w, int h, int amp) {
            java.util.Random rnd = new java.util.Random(7);
            int[] row = new int[w * 16];
            for (int y = 0; y < h; y += 16) {
                int rows = Math.min(16, h - y);
                bm.getPixels(row, 0, w, 0, y, w, rows);
                for (int i = 0; i < w * rows; i++) {
                    int n = rnd.nextInt(amp * 2 + 1) - amp;
                    int px = row[i];
                    int r = Math.max(0, Math.min(255, ((px >> 16) & 0xFF) + n));
                    int g = Math.max(0, Math.min(255, ((px >> 8) & 0xFF) + n));
                    int bl = Math.max(0, Math.min(255, (px & 0xFF) + n));
                    row[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                }
                bm.setPixels(row, 0, w, 0, y, w, rows);
            }
        }

        /** Apple-TV-like near black with only a faint cool light at the top. */
        private void nearBlack(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF08090E, 0xFF030305, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            glow(c, w * 0.5f, -h * 0.15f, w * 0.75f, 0x2A4A62B0);
            glow(c, w * 0.5f, h * 1.05f, w * 0.55f, 0x143A4A80);
            p.setShader(null);
        }

        /** Dark base with a low warm sun glow rising from the bottom, rose to one side, a hint of violet above. */
        private void sunset(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF0B0912, 0xFF170B12, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            glow(c, w * 0.88f, -h * 0.05f, w * 0.50f, 0x40503A9A);
            glow(c, w * 0.26f, h * 1.10f, w * 0.65f, 0x70C2446E);
            glow(c, w * 0.58f, h * 1.18f, w * 0.80f, 0xA0E8743A);
            p.setShader(null);
        }

        /** Radial glow with a long smooth falloff (many stops) so there is no visible edge or banding. */
        private void glow(Canvas c, float cx, float cy, float r, int color) {
            int rgb = color & 0x00FFFFFF;
            int a = color >>> 24;
            int[] cols = new int[6];
            float[] pos = {0f, 0.2f, 0.45f, 0.7f, 0.88f, 1f};
            float[] k = {1f, 0.68f, 0.36f, 0.14f, 0.04f, 0f};
            for (int i = 0; i < 6; i++) cols[i] = ((int) (a * k[i]) << 24) | rgb;
            p.setShader(new RadialGradient(cx, cy, r, cols, pos, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }

        /** Big overlapping lit spheres on deep navy, each with a thin glass rim (Liquid Glass lock-screen style). */
        private void paint(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);
            // base
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF060822, 0xFF0A0E34, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            // violet glow behind the upper-right sphere
            p.setShader(new RadialGradient(w * 0.80f, h * 0.15f, h * 0.95f, 0x663F2A9A, 0x003F2A9A, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.80f, h * 0.15f, h * 0.95f, p);
            // upper-right violet sphere, lit from its lower-left
            sphere(c, w * 0.80f, h * 0.10f, h * 0.62f, w * 0.68f, h * 0.38f, 0xFF8B5CE6, 0xFF4A2D9E, 0xFF1A1248);
            // centre dark sphere that overlaps both
            sphere(c, w * 0.52f, h * 0.62f, h * 0.40f, w * 0.46f, h * 0.45f, 0xFF1B2A78, 0xFF0C1244, 0xFF060822);
            // lower-left blue sphere, lit from its upper-right
            sphere(c, w * 0.08f, h * 1.00f, h * 0.78f, w * 0.20f, h * 0.55f, 0xFF4F86FF, 0xFF1F3FC4, 0xFF0A1550);
            // soft light spill where the blue sphere meets the dark one
            p.setShader(new RadialGradient(w * 0.34f, h * 0.70f, h * 0.45f, 0x334F86FF, 0x004F86FF, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.34f, h * 0.70f, h * 0.45f, p);
            p.setShader(null);
        }

        private void sphere(Canvas c, float cx, float cy, float r, float lx, float ly, int light, int mid, int dark) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(lx, ly, r * 1.55f, new int[]{light, mid, dark},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            // glass rim: bright where the light hits, almost gone on the far side
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{0x66FFFFFF, 0x10FFFFFF, 0x10FFFFFF, 0x55FFFFFF}, new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, r * 0.006f));
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas c) {
            if (bmp != null) c.drawBitmap(bmp, null, getBounds(), blit);
            else c.drawColor(0xFF05060B);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * Wide Apple-TV-style tile: a rounded rectangle of glass. A banner fills the whole tile; an icon sits
     * centred on the glass (rounded-square masked when it is a full square picture).
     */
    private static class GlassTileView extends View {
        static final int BANNER = 0, MASKED = 1, PLAIN = 2;
        private final Drawable icon;
        private final int mode;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path iconClip = new Path();
        private final RectF rf = new RectF();
        private final float density;

        GlassTileView(Context c, Drawable icon, int mode) {
            super(c);
            this.icon = icon;
            this.mode = mode;
            density = c.getResources().getDisplayMetrics().density;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * density);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3f * density);
            ring.setColor(RING);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float inset = 2f * density;
            float r = Math.min(w, h) * 0.17f;
            rf.set(inset, inset, w - inset, h - inset);
            path.rewind();
            path.addRoundRect(rf, r, r, Path.Direction.CW);
            fill.setShader(new LinearGradient(0, 0, 0, h, 0x52FFFFFF, 0x1CFFFFFF, Shader.TileMode.CLAMP));
            gloss.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x3CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            // two highlights, top-left and bottom-right, like the rim of a glass pane
            edge.setShader(new LinearGradient(0, 0, w, h, new int[]{0xE6FFFFFF, 0x20FFFFFF, 0x20FFFFFF, 0xB0FFFFFF},
                    new float[]{0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.save();
            c.clipPath(path);
            if (mode != BANNER || icon == null) c.drawPath(path, fill);
            if (icon != null) {
                Rect old = icon.copyBounds();
                int iw = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
                if (mode == BANNER) {
                    // cover the tile, centred, keeping the banner's aspect ratio
                    float s = (iw > 0 && ih > 0) ? Math.max(w / (float) iw, h / (float) ih) : 1f;
                    int dw = iw > 0 ? Math.round(iw * s) : w, dh = ih > 0 ? Math.round(ih * s) : h;
                    int l = (w - dw) / 2, t = (h - dh) / 2;
                    icon.setBounds(l, t, l + dw, t + dh);
                    icon.draw(c);
                } else {
                    int side = Math.round(h * 0.68f);
                    int l = (w - side) / 2, t = (h - side) / 2;
                    if (mode == MASKED) {
                        c.save();
                        iconClip.rewind();
                        rf.set(l, t, l + side, t + side);
                        iconClip.addRoundRect(rf, side * 0.22f, side * 0.22f, Path.Direction.CW);
                        c.clipPath(iconClip);
                        icon.setBounds(l, t, l + side, t + side);
                        icon.draw(c);
                        c.restore();
                    } else {   // transparent artwork: keep its aspect ratio, centred on the glass
                        int dw = side, dh = side;
                        if (iw > 0 && ih > 0) {
                            float s = Math.min(side / (float) iw, side / (float) ih);
                            dw = Math.round(iw * s);
                            dh = Math.round(ih * s);
                        }
                        int ll = (w - dw) / 2, tt = (h - dh) / 2;
                        icon.setBounds(ll, tt, ll + dw, tt + dh);
                        icon.draw(c);
                    }
                }
                icon.setBounds(old);
            }
            c.drawPath(path, gloss);
            c.restore();
            c.drawPath(path, isSelected() ? ring : edge);
        }
    }

    private StateListDrawable glass(int radius, int top, int bottom, int topFocus, int bottomFocus) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glassShape(radius, topFocus, bottomFocus, RING, dp(2)));
        sl.addState(new int[]{}, glassShape(radius, top, bottom, 0x55FFFFFF, 1));
        return sl;
    }

    private Drawable glassShape(int radius, int top, int bottom, int stroke, int strokeW) {
        return new GlassDrawable(radius, top, bottom, stroke, strokeW <= 1 ? 3 : strokeW);
    }

    /**
     * Liquid-glass panel: translucent fill plus a thin rim that is bright at the top-left and bottom-right
     * corners and nearly clear in between. A near-opaque stroke colour (focus) draws a solid ring instead.
     */
    private static class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius, strokeW;
        private final int top, bottom, stroke;

        GlassDrawable(float radius, int top, int bottom, int stroke, float strokeW) {
            this.radius = radius; this.top = top; this.bottom = bottom; this.stroke = stroke; this.strokeW = strokeW;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(strokeW);
        }

        @Override protected void onBoundsChange(Rect b) {
            r.set(b);
            r.inset(strokeW / 2f, strokeW / 2f);
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
            if (Color.alpha(stroke) >= 0xE0) {
                rim.setShader(null);
                rim.setColor(stroke);
            } else {
                rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[]{0xE6FFFFFF, 0x1CFFFFFF, 0x1CFFFFFF, 0xB0FFFFFF}, new float[]{0f, 0.30f, 0.70f, 1f},
                        Shader.TileMode.CLAMP));
            }
        }

        @Override public void draw(Canvas c) {
            c.drawRoundRect(r, radius, radius, fill);
            c.drawRoundRect(r, radius, radius, rim);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Tall, thin, see-through clock digits (gradient fill plus a light outline), like the Liquid Glass lock screen. */
    private static class GlassClockView extends View {
        private static final float SIZE_DP = 50f, STRETCH = 1.45f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private String text = "";
        private String suffix = "";   // AM / PM, empty in 24-hour mode
        private final Paint suffixPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassClockView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            Typeface tf = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL);
            fill.setTypeface(tf);
            fill.setTextSize(SIZE_DP * d);
            line.setTypeface(tf);
            line.setTextSize(SIZE_DP * d);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * d);
            float cap = SIZE_DP * d * 0.72f;
            // warm white fading to the moon's cream, with a soft plum shadow so it lifts off the pink sky
            fill.setShader(new LinearGradient(0, -cap, 0, 0, 0xF2FFFFFF, 0x99FFEFD2, Shader.TileMode.CLAMP));
            fill.setShadowLayer(7 * d, 0, 2 * d, 0x66401428);
            line.setShader(new LinearGradient(0, -cap, 0, 0, 0xE6FFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP));
        }

        void setTime(String s, String ampm) {
            if (s.equals(text) && ampm.equals(suffix)) return;
            text = s;
            suffix = ampm;
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            suffixPaint.setTextSize(17 * d);
            suffixPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            int w = (int) (fill.measureText(text) + 4 * d
                    + (suffix.isEmpty() ? 0 : suffixPaint.measureText(suffix) + 8 * d));
            int h = (int) (SIZE_DP * d * 0.72f * STRETCH + 8 * d);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas c) {
            float baseline = getHeight() - 4 * d;
            c.translate(2 * d, baseline);
            c.save();
            c.scale(1f, STRETCH);
            c.drawText(text, 0, 0, fill);
            c.drawText(text, 0, 0, line);
            c.restore();
            if (!suffix.isEmpty()) {   // AM / PM sits small beside the digits, on the same baseline
                suffixPaint.setColor(0xE6FFFFFF);
                c.drawText(suffix, fill.measureText(text) + 8 * d, 0, suffixPaint);
            }
        }
    }

    private GlassClockView clockView;
    private boolean clockReceiverRegistered;
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private void updateClock() {
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        java.util.Date now = new java.util.Date();
        clockView.setTime(new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", java.util.Locale.getDefault()).format(now),
                h24 ? "" : new java.text.SimpleDateFormat("a", java.util.Locale.US).format(now).toUpperCase(java.util.Locale.US));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();   // weather only, so a slow network never delays the app list
    private WeatherCard weatherCard;
    // palette taken from the wallpaper: warm white, blush pink sky, the moon's cream, cerulean glass
    private static final int INK = 0xFFFFF8F2;     // main text
    private static final int BLUSH = 0xFFFFE4D8;   // secondary text
    private static final int CREAM = 0xFFFFE9BE;   // accents (moon)
    private static final int RING = 0xF2FFF0C8;    // focus ring
    private static final int GLASS_A = 0x602C4C7C; // glass tint, top
    private static final int GLASS_B = 0x4A1F3562; // glass tint, bottom
    private static final int TILE_W_DP = 150;   // wide 16:9 app tile, also the dock tile at medium size
    private static final int DEFAULT_DOCK_N = 5;
    private static final String K_WALL = "wall";   // 0 near black, 1 warm sunset, 2 spheres
    private WallpaperDrawable wallpaper;
    private final Map<String, Drawable> imageCache = new HashMap<>();
    private final Map<String, Integer> modes = new HashMap<>();   // artwork mode per package, guarded by imageCache
    private List<App> apps = new ArrayList<>();
    private SharedPreferences prefs;

    private LinearLayout gridHolder;
    private LinearLayout dock;
    private ConnectivityManager.NetworkCallback netCallback;
    private String lastFocused;
    private boolean receiverRegistered;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 600);
            Runnable r = allAppsRefresh;
            if (r != null) main.postDelayed(r, 800);   // keep an open All apps list current after an uninstall
        }
    };
    private final Runnable reload = this::loadApps;

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lastFocused = null;   // like Apple TV, Home always starts on the dock (page one)
        buildSkeleton();
        loadApps();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(pkgReceiver, f);
            receiverRegistered = true;
        }
        if (!clockReceiverRegistered) {
            IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);   // once a minute, no polling
            tf.addAction(Intent.ACTION_TIME_CHANGED);
            tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(clockReceiver, tf);
            clockReceiverRegistered = true;
        }
        updateClock();
        watchNetwork();
        showWeather();
        main.removeCallbacks(weatherTick);
        weatherTick.run();   // fetches only if the cached value is older than an hour, then repeats hourly
    }

    @Override protected void onStop() {
        main.removeCallbacks(weatherTick);
        if (clockReceiverRegistered) {
            unregisterReceiver(clockReceiver);
            clockReceiverRegistered = false;
        }
        if (receiverRegistered) {
            unregisterReceiver(pkgReceiver);
            receiverRegistered = false;
        }
        unwatchNetwork();
        super.onStop();
    }

    @Override protected void onPause() {
        if (lastFocused != null) prefs.edit().putString(K_LAST, lastFocused).apply();
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    /**
     * A real Settings key (for example on a keyboard) opens the Control Centre. This TV's own remote button sends a raw
     * code that Android drops before any app sees it (verified), so for that remote the on-screen Settings icon is used.
     */
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_SETTINGS) {
            if (e.getRepeatCount() == 0) showQuickSettings();   // ignore auto-repeat
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override public void onBackPressed() {
        // A home screen has nowhere to go back to; stay put.
    }

    // ---------------------------------------------------------------- skeleton

    private void buildSkeleton() {
        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperDrawable();
        wallpaper.photoLoader = this::loadPhoto;
        wallpaper.setStyle(prefs.getInt(K_WALL, hasPhoto() ? 3 : 0));   // the photo is the default when one is bundled
        root.setBackground(wallpaper);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(14), dp(48), dp(16));
        col.setClipToPadding(false);   // zoomed focus rings may extend into the screen margin
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // The home screen is two pages in one scroller, like Apple TV: page one is the header, a big title and
        // the dock at the bottom; the other apps are on the page below and slide up when you press Down.
        final PageScrollView page = new PageScrollView(this);
        pageScroll = page;
        page.setVerticalScrollBarEnabled(false);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        page.addView(content, new ViewGroup.LayoutParams(-1, -2));
        col.addView(page, new LinearLayout.LayoutParams(-1, -1));
        final LinearLayout pageOne = new LinearLayout(this);
        pageOne.setOrientation(LinearLayout.VERTICAL);
        pageOne.setClipChildren(false);
        pageOne.setClipToPadding(false);
        content.addView(pageOne, new LinearLayout.LayoutParams(-1, dp(480)));
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {   // page one is exactly one screen tall
            int h = b - t;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pageOne.getLayoutParams();
            if (h > 0 && lp.height != h) { lp.height = h; pageOne.setLayoutParams(lp); }
        });

        // top band: clock, date and shortcuts on the left, the weather card on the right
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        top.setClipChildren(false);
        pageOne.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setClipChildren(false);
        clockView = new GlassClockView(this);
        updateClock();
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xFFFFEDE6);
        date.setTextSize(13);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setAllCaps(true);
        date.setLetterSpacing(0.12f);
        date.setShadowLayer(dp(3), 0, dp(1), 0xB0301020);
        clockBox.addView(clockView);
        clockBox.addView(date);

        // shortcut buttons: a glass key with a drawn glyph and a caption, to the right of the clock
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.addView(glyphButton(GlyphView.INPUT, "Inputs", v -> showInputs()), pillLp());
        LinearLayout wifi = glyphButton(GlyphView.WIFI, "Wi-Fi", v -> startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiLabel = (TextView) wifi.getChildAt(1);
        buttons.addView(wifi, pillLp());
        // the Settings key opens our Control Centre panel; its "All settings" tile opens the TV's full Settings app
        buttons.addView(glyphButton(GlyphView.SETTINGS, "Settings", v -> showQuickSettings()), pillLp());
        buttons.addView(glyphButton(GlyphView.OPTIONS, "Options", v -> showOptions()), pillLp());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setClipChildren(false);
        left.setClipToPadding(false);
        left.addView(clockBox);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(36);
        left.addView(buttons, blp);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        weatherCard = new WeatherCard();
        top.addView(weatherCard.view, new LinearLayout.LayoutParams(dp(340), -2));

        // the big title of whichever app is focused, in see-through letters like the tvOS title art
        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        heroTitle = new GlassTitleView(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, -2);
        hlp.gravity = Gravity.BOTTOM;
        hlp.bottomMargin = dp(34);
        hero.addView(heroTitle, hlp);
        TextView hint = new TextView(this);
        hint.setText("OK TO OPEN   ·   HOLD OK TO EDIT");
        hint.setTextColor(INK);
        hint.setShadowLayer(dp(3), 0, dp(1), 0xB0301020);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setLetterSpacing(0.1f);
        hint.setTextSize(11);
        hint.setPadding(dp(4), 0, 0, 0);
        FrameLayout.LayoutParams hl2 = new FrameLayout.LayoutParams(-2, -2);
        hl2.gravity = Gravity.BOTTOM;
        hl2.bottomMargin = dp(10);
        hero.addView(hint, hl2);
        pageOne.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1f));

        // the dock: a glass bar at the bottom of page one, with the page dots under it
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);   // the zoomed focus ring may extend past the bar's padding
        dock.setPadding(dp(14), dp(12), dp(14), dp(10));   // room for the focused tile's name inside the bar
        dock.setBackground(glassShape(dp(30), GLASS_A, GLASS_B, 0x66FFFFFF, 1));
        pageOne.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(8), 0, dp(2));
        for (int i = 0; i < 2; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(CREAM);
            dot.setBackground(g);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(6), dp(6));
            dl.leftMargin = dp(4);
            dl.rightMargin = dp(4);
            dots.addView(dot, dl);
            pageDots[i] = dot;
        }
        pageDots[0].setAlpha(1f);
        pageDots[1].setAlpha(0.35f);

        // footer row: page dots in the middle, the credit on the right
        FrameLayout footer = new FrameLayout(this);
        footer.setClipChildren(false);
        footer.addView(dots, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout credit = new LinearLayout(this);
        credit.setOrientation(LinearLayout.HORIZONTAL);
        credit.setGravity(Gravity.CENTER_VERTICAL);
        credit.setPadding(dp(10), dp(2), dp(10), dp(2));
        credit.setBackground(glassShape(dp(14), 0x4A2C4C7C, 0x3A1F3562, 0x55FFFFFF, 1));
        TextView by = new TextView(this);
        by.setText("Designed and developed by  :  ");
        by.setTextColor(BLUSH);
        by.setTextSize(9);
        by.setSingleLine(true);
        by.setShadowLayer(dp(2), 0, dp(1), 0xA0301020);
        TextView who = new TextView(this);
        who.setText("Deepu Mohan");
        who.setTextColor(CREAM);   // the moon's cream
        who.setTextSize(10);
        who.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        who.setSingleLine(true);
        who.setShadowLayer(dp(2), 0, dp(1), 0xA0301020);
        credit.addView(by);
        credit.addView(who);
        footer.addView(credit, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        pageOne.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        page.onScrolled = y -> {
            boolean second = y > pageOne.getHeight() / 3;
            pageDots[0].setAlpha(second ? 0.35f : 1f);
            pageDots[1].setAlpha(second ? 1f : 0.35f);
        };

        // page two: the other apps, five to a row
        gridHolder = new LinearLayout(this);
        gridHolder.setOrientation(LinearLayout.VERTICAL);
        gridHolder.setClipChildren(false);
        gridHolder.setClipToPadding(false);
        gridHolder.setPadding(0, dp(24), 0, dp(120));
        content.addView(gridHolder, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private int photoId() {
        return getResources().getIdentifier("wallpaper_photo", "drawable", getPackageName());
    }

    private boolean hasPhoto() { return photoId() != 0; }

    /** Decodes the bundled photo (res/drawable-nodpi/wallpaper_photo.jpg) at screen size; null if none is bundled. */
    private Bitmap loadPhoto() {
        int id = photoId();
        if (id == 0) return null;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inMutable = true;
            Bitmap src = BitmapFactory.decodeResource(getResources(), id, o);
            if (src == null) return null;
            if (src.getWidth() == dm.widthPixels && src.getHeight() == dm.heightPixels && src.isMutable()) return src;
            // cover the screen, centred, keeping the photo's proportions
            float s = Math.max(dm.widthPixels / (float) src.getWidth(), dm.heightPixels / (float) src.getHeight());
            int sw = Math.round(src.getWidth() * s), sh = Math.round(src.getHeight() * s);
            Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
            Bitmap out = Bitmap.createBitmap(scaled, (sw - dm.widthPixels) / 2, (sh - dm.heightPixels) / 2,
                    dm.widthPixels, dm.heightPixels).copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != src) scaled.recycle();
            src.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private PageScrollView pageScroll;
    private GlassTitleView heroTitle;
    private final View[] pageDots = new View[2];

    /** ScrollView that never scrolls by itself on focus; scrollPageFor() moves whole rows instead. */
    private static class PageScrollView extends ScrollView {
        java.util.function.IntConsumer onScrolled;
        PageScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            if (onScrolled != null) onScrolled.accept(t);
        }
    }

    /** Big see-through title: gradient fill with a light outline; shrinks to fit the width. */
    private static class GlassTitleView extends View {
        private static final float SIZE_DP = 64f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private String text = "";

        GlassTitleView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            Typeface tf = Typeface.create("sans-serif-light", Typeface.NORMAL);   // light, airy, like the sky
            fill.setTypeface(tf);
            fill.setTextSize(SIZE_DP * d);
            fill.setLetterSpacing(0.02f);
            line.setTypeface(tf);
            line.setTextSize(SIZE_DP * d);
            line.setLetterSpacing(0.02f);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * d);
            float cap = SIZE_DP * d * 0.75f;
            fill.setShader(new LinearGradient(0, -cap, 0, 0, 0xFAFFFFFF, 0xB3FFE9BE, Shader.TileMode.CLAMP));
            fill.setShadowLayer(9 * d, 0, 3 * d, 0x70301020);
            line.setShader(new LinearGradient(0, -cap, 0, 0, 0xFFFFFFFF, 0x80FFFFFF, Shader.TileMode.CLAMP));
        }

        void setText(String s) {
            if (s.equals(text)) return;
            text = s;
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (SIZE_DP * d * 1.0f));
        }

        @Override protected void onDraw(Canvas c) {
            float tw = fill.measureText(text);
            float avail = getWidth() * 0.62f;   // the right side stays free for the wallpaper
            float s = tw > avail ? avail / tw : 1f;
            c.translate(2 * d, getHeight() - 10 * d);
            c.scale(s, s);
            c.drawText(text, 0, 0, fill);
            c.drawText(text, 0, 0, line);
        }
    }

    /** Called when a tile or key gains focus: slide page two up to its row, or back to page one. */
    private void scrollPageFor(View v) {
        if (pageScroll == null) return;
        int y = 0;
        android.view.ViewParent par = v.getParent();
        boolean inGrid = false;
        while (par instanceof View) {
            if (par == gridHolder) { inGrid = true; break; }
            par = ((View) par).getParent();
        }
        if (inGrid && v.getParent() instanceof View) {
            y = gridHolder.getTop() + ((View) v.getParent()).getTop() - dp(30);
        }
        pageScroll.smoothScrollTo(0, Math.max(0, y));
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(12);
        return lp;
    }

    private TextView wifiLabel;

    /** Glass key with a drawn glyph above a caption. Child 0 is the glyph, child 1 the caption. */
    private LinearLayout glyphButton(int glyph, String caption, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(dp(8), dp(9), dp(8), dp(7));
        b.setMinimumWidth(dp(68));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setBackground(glass(dp(18), GLASS_A, GLASS_B, 0x884C78B8, 0x703A66A8));
        b.addView(new GlyphView(this, glyph), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = new TextView(this);
        t.setText(caption);
        t.setTextColor(INK);
        t.setShadowLayer(dp(2), 0, dp(1), 0xA0301020);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.04f);
        t.setTextSize(11);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        b.addView(t);
        b.setOnFocusChangeListener((v, f) -> { animate(v, f, 1.1f); if (f) scrollPageFor(v); });
        return b;
    }

    /** Line glyphs for the shortcut keys: input (screen with arrow), Wi-Fi, gear, sliders. */
    private static class GlyphView extends View {
        static final int INPUT = 0, WIFI = 1, SETTINGS = 2, OPTIONS = 3, SOUND = 4, DISPLAY = 5, BLUETOOTH = 6;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        GlyphView(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.085f);
            switch (kind) {
                case INPUT: {   // monitor with an arrow going in
                    rf.set(cx - s * 0.30f, cy - s * 0.30f, cx + s * 0.46f, cy + s * 0.22f);
                    c.drawRoundRect(rf, s * 0.08f, s * 0.08f, p);
                    c.drawLine(cx + s * 0.08f, cy + s * 0.36f, cx + s * 0.08f, cy + s * 0.22f, p);
                    c.drawLine(cx - s * 0.08f, cy + s * 0.40f, cx + s * 0.24f, cy + s * 0.40f, p);
                    c.drawLine(cx - s * 0.46f, cy - s * 0.04f, cx - s * 0.02f, cy - s * 0.04f, p);
                    c.drawLine(cx - s * 0.16f, cy - s * 0.18f, cx - s * 0.02f, cy - s * 0.04f, p);
                    c.drawLine(cx - s * 0.16f, cy + s * 0.10f, cx - s * 0.02f, cy - s * 0.04f, p);
                    break;
                }
                case WIFI: {
                    float bx = cx, by = cy + s * 0.34f;
                    for (int i = 1; i <= 3; i++) {
                        float r = s * 0.17f * i;
                        rf.set(bx - r, by - r, bx + r, by + r);
                        c.drawArc(rf, 225, 90, false, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(bx, by - s * 0.01f, s * 0.07f, p);
                    break;
                }
                case SETTINGS: {   // gear
                    p.setStrokeWidth(s * 0.09f);
                    c.drawCircle(cx, cy, s * 0.20f, p);
                    p.setStrokeWidth(s * 0.13f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine((float) (cx + Math.cos(a) * s * 0.30f), (float) (cy + Math.sin(a) * s * 0.30f),
                                (float) (cx + Math.cos(a) * s * 0.42f), (float) (cy + Math.sin(a) * s * 0.42f), p);
                    }
                    break;
                }
                case SOUND: {   // speaker with two waves
                    Path sp = new Path();
                    sp.moveTo(cx - s * 0.40f, cy - s * 0.12f);
                    sp.lineTo(cx - s * 0.22f, cy - s * 0.12f);
                    sp.lineTo(cx - s * 0.02f, cy - s * 0.32f);
                    sp.lineTo(cx - s * 0.02f, cy + s * 0.32f);
                    sp.lineTo(cx - s * 0.22f, cy + s * 0.12f);
                    sp.lineTo(cx - s * 0.40f, cy + s * 0.12f);
                    sp.close();
                    c.drawPath(sp, p);
                    for (int i = 1; i <= 2; i++) {
                        float r = s * 0.17f * i;
                        rf.set(cx + s * 0.04f - r, cy - r, cx + s * 0.04f + r, cy + r);
                        c.drawArc(rf, -45, 90, false, p);
                    }
                    break;
                }
                case DISPLAY: {   // screen with a sun
                    rf.set(cx - s * 0.44f, cy - s * 0.34f, cx + s * 0.44f, cy + s * 0.24f);
                    c.drawRoundRect(rf, s * 0.08f, s * 0.08f, p);
                    c.drawLine(cx - s * 0.16f, cy + s * 0.40f, cx + s * 0.16f, cy + s * 0.40f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy - s * 0.05f, s * 0.09f, p);
                    break;
                }
                case BLUETOOTH: {   // the Bluetooth rune
                    Path bt = new Path();
                    bt.moveTo(cx - s * 0.20f, cy - s * 0.20f);
                    bt.lineTo(cx + s * 0.22f, cy + s * 0.20f);
                    bt.lineTo(cx, cy + s * 0.42f);
                    bt.lineTo(cx, cy - s * 0.42f);
                    bt.lineTo(cx + s * 0.22f, cy - s * 0.20f);
                    bt.lineTo(cx - s * 0.20f, cy + s * 0.20f);
                    c.drawPath(bt, p);
                    break;
                }
                default: {   // sliders
                    p.setStrokeWidth(s * 0.075f);
                    float[] ys = {cy - s * 0.26f, cy, cy + s * 0.26f};
                    float[] ks = {0.30f, 0.62f, 0.42f};
                    for (int i = 0; i < 3; i++) {
                        c.drawLine(cx - s * 0.44f, ys[i], cx + s * 0.44f, ys[i], p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) {
                        c.drawCircle(cx - s * 0.44f + s * 0.88f * ks[i], ys[i], s * 0.11f, p);
                    }
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        io.execute(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            Set<String> seen = new HashSet<>();
            List<App> list = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                Drawable d;
                synchronized (imageCache) { d = imageCache.get(pkg); }
                if (d == null) {
                    // Apple-TV style: the app's TV banner when it is sharp and wide enough, otherwise its icon on a
                    // glass tile. Art is read at its best native resolution and scaled down once, never up.
                    int mode = GlassTileView.PLAIN;
                    Art banner = loadArt(pm, ri, true);
                    float ratio = banner != null && banner.h > 0 ? banner.w / (float) banner.h : 0f;
                    if (banner != null && ratio >= 1.55f && ratio <= 2.0f && (banner.vector || banner.w >= 256)) {
                        d = raster(banner, 336);   // tile is about 300 px wide, 330 px when zoomed
                        mode = GlassTileView.BANNER;
                    } else {
                        Art icon = loadArt(pm, ri, false);
                        if (icon == null) {
                            try {
                                icon = new Art();
                                icon.d = ri.loadIcon(pm);
                                icon.vector = true;
                                icon.w = icon.d.getIntrinsicWidth();
                                icon.h = icon.d.getIntrinsicHeight();
                            } catch (Throwable t) { icon = null; }
                        }
                        if (icon != null) {
                            if (icon.d instanceof AdaptiveIconDrawable || isOpaqueSquare(icon.d)) mode = GlassTileView.MASKED;
                            d = raster(icon, 192);   // drawn about 115 px square, 125 px when zoomed
                        }
                    }
                    if (d != null) synchronized (imageCache) {
                        imageCache.put(pkg, d);
                        modes.put(pkg, mode);
                    }
                }
                a.image = d;
                synchronized (imageCache) { Integer m = modes.get(pkg); a.mode = m == null ? GlassTileView.PLAIN : m; }
                list.add(a);
            }
            Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            main.post(() -> {
                apps = list;
                synchronized (imageCache) { imageCache.keySet().retainAll(seen); }
                rebuild(lastFocused);
            });
        });
    }

    private static class Art {
        Drawable d;
        int w, h;          // native pixel size
        boolean vector;    // vector / adaptive: sharp at any size
    }

    /** Reads an app's banner or icon from its highest-density file, at native pixels (no density upscaling). */
    private Art loadArt(PackageManager pm, ResolveInfo ri, boolean banner) {
        try {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            int id = banner ? (ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ai.banner)
                    : ri.activityInfo.getIconResource();
            if (id == 0) return null;
            Resources res = pm.getResourcesForApplication(ai);
            TypedValue tv = new TypedValue();
            res.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, tv, true);
            String path = tv.string == null ? "" : tv.string.toString().toLowerCase();
            Art a = new Art();
            if (path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                // the file inside the app's APK (base or a split), decoded without any density scaling
                List<String> apks = new ArrayList<>();
                apks.add(ai.publicSourceDir);
                if (ai.splitPublicSourceDirs != null) Collections.addAll(apks, ai.splitPublicSourceDirs);
                for (String apk : apks) {
                    if (apk == null) continue;
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                        java.util.zip.ZipEntry e = zf.getEntry(tv.string.toString());
                        if (e == null) continue;
                        try (InputStream in = zf.getInputStream(e)) {
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inScaled = false;
                            Bitmap b = BitmapFactory.decodeStream(in, null, o);
                            if (b == null) continue;
                            a.d = new BitmapDrawable(getResources(), b);
                            a.w = b.getWidth();
                            a.h = b.getHeight();
                            return a;
                        }
                    } catch (Exception ignored) { }
                }
                // not readable that way: fall through to the normal drawable loader
            }
            Drawable d = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
            if (d == null) return null;
            a.d = d;
            a.vector = true;
            a.w = d.getIntrinsicWidth();
            a.h = d.getIntrinsicHeight();
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scales art down to targetW pixels wide in halving steps (so it stays crisp); never scales up. */
    private Drawable raster(Art a, int targetW) {
        if (a.d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) a.d).getBitmap();
            Bitmap cur = src;
            // every intermediate bitmap and the decoded original are released as soon as they are replaced
            while (cur.getWidth() >= targetW * 2) {
                Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
                if (cur != src) cur.recycle();
                cur = next;
            }
            if (cur.getWidth() > targetW) {
                Bitmap next = Bitmap.createScaledBitmap(cur, targetW, Math.max(1, cur.getHeight() * targetW / cur.getWidth()), true);
                if (cur != src) cur.recycle();
                cur = next;
            }
            if (cur != src) src.recycle();
            a.d = null;
            return new BitmapDrawable(getResources(), cur);
        }
        int w = targetW;
        int h = (a.w > 0 && a.h > 0) ? Math.max(1, a.h * targetW / a.w) : targetW;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        a.d.setBounds(0, 0, w, h);
        a.d.draw(new Canvas(b));
        return new BitmapDrawable(getResources(), b);
    }

    /** True when a legacy icon is a (nearly) square, fully opaque picture, so it can be masked like an adaptive icon. */
    private static boolean isOpaqueSquare(Drawable d) {
        if (d == null) return false;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0 || Math.abs(iw - ih) > 0.1f * Math.max(iw, ih)) return false;
        final int n = 32;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(b));
        d.setBounds(old);
        int[][] pts = {{4, 4}, {27, 4}, {4, 27}, {27, 27}, {16, 1}, {1, 16}, {30, 16}, {16, 30}};
        boolean opaque = true;
        for (int[] p : pts) if (Color.alpha(b.getPixel(p[0], p[1])) < 230) { opaque = false; break; }
        b.recycle();
        return opaque;
    }

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(K_HIDDEN, new HashSet<>()));
    }

    private List<String> dockList() {
        String s = prefs.getString(K_DOCK, null);
        List<String> l = new ArrayList<>();
        if (s == null) {
            for (String p : DEFAULT_DOCK) l.add(p);
        } else if (!s.isEmpty()) {
            Collections.addAll(l, s.split(","));
        }
        return l;
    }

    private void saveDock(List<String> l) {
        prefs.edit().putString(K_DOCK, TextUtils.join(",", l)).apply();
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    // ---------------------------------------------------------------- rendering

    private void rebuild(String focusPkg) {
        Set<String> hid = hidden();
        int dockN = prefs.getInt(K_DOCK_N, DEFAULT_DOCK_N);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int usable = screenW - dp(96);   // screen width minus the side margins

        // dock shelf first (also decides which apps are left for the grid below)
        dock.removeAllViews();
        int size = prefs.getInt(K_DOCK_SIZE, 1);
        float mult = size == 0 ? 0.82f : size == 2 ? 1.12f : 1f;
        int gap = dp(20);
        int tw = Math.min(Math.round(dp(TILE_W_DP) * mult), (usable - dp(28)) / Math.max(1, dockN) - gap);
        int th = tw * 9 / 16;
        Set<String> inDock = new HashSet<>();
        int shown = 0;
        for (String p : dockList()) {
            App a = find(p);
            if (a == null || hid.contains(p) || shown >= dockN) continue;
            inDock.add(p);
            View t = tile(a, tw, th, 1.10f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tw, -2);
            lp.leftMargin = gap / 2;
            lp.rightMargin = gap / 2;
            dock.addView(t, lp);
            shown++;
        }
        if (shown == 0) {
            TextView hint = new TextView(this);
            hint.setText("Press and hold OK on an app to add it here");
            hint.setTextColor(0xFFB0B0B8);
            hint.setTextSize(14);
            hint.setPadding(dp(16), dp(30), dp(16), dp(30));
            dock.addView(hint);
        }

        // the remaining apps, five to a row, in wide tiles; names show only on the focused tile
        gridHolder.removeAllViews();
        int gw = dp(TILE_W_DP), gh = gw * 9 / 16;
        int cols = Math.max(2, (usable + gap) / (gw + gap));
        LinearLayout row = null;
        int n = 0;
        for (App a : apps) {
            if (hid.contains(a.pkg) || inDock.contains(a.pkg)) continue;
            if (n % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(0, dp(2), 0, dp(6));
                gridHolder.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View t = tile(a, gw, gh, 1.10f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(gw, -2);
            lp.leftMargin = gap / 2;
            lp.rightMargin = gap / 2;
            row.addView(t, lp);
            n++;
        }
        if (n == 0) {
            TextView empty = new TextView(this);
            empty.setText("No other apps to show. Open Options to unhide apps.");
            empty.setTextColor(0xFF9A9AA0);
            empty.setPadding(0, dp(40), 0, 0);
            gridHolder.addView(empty);
        }

        focusPackage(focusPkg);
    }

    private void focusPackage(String pkg) {
        View target = null;
        if (pkg != null) {
            target = findTile(gridHolder, pkg);
            if (target == null) target = findTile(dock, pkg);
        }
        if (target == null) target = firstTile(dock);
        if (target == null) target = firstTile(gridHolder);
        if (target != null) target.requestFocus();
    }

    private View findTile(ViewGroup g, String pkg) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (pkg.equals(c.getTag())) return c;
            if (c instanceof ViewGroup && !c.isFocusable()) {
                View r = findTile((ViewGroup) c, pkg);
                if (r != null) return r;
            }
        }
        return null;
    }

    private View firstTile(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View r = firstTile((ViewGroup) c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private View tile(final App a, int w, int h, float scale) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(a.pkg);
        t.setClipChildren(false);
        t.setClipToPadding(false);

        GlassTileView card = new GlassTileView(this, a.image, a.mode);
        t.addView(card, new LinearLayout.LayoutParams(w, h));

        // like Apple TV, the name is shown only for the focused tile (the space is always reserved)
        final TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        label.setPadding(0, dp(4), 0, 0);
        label.setAlpha(0f);
        t.addView(label, new LinearLayout.LayoutParams(w, dp(22)));

        t.setOnFocusChangeListener((v, f) -> {
            card.setSelected(f);
            label.animate().alpha(f ? 1f : 0f).setDuration(120).start();
            animate(v, f, scale);
            if (f) {
                lastFocused = a.pkg;
                heroTitle.setText(a.label);
                scrollPageFor(v);
            }
        });
        t.setOnClickListener(v -> launch(a));
        t.setOnLongClickListener(v -> { showAppMenu(a); return true; });
        return t;
    }

    private StateListDrawable stateBg(int normal, int selected, int radius, int stroke) {
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(radius);
        GradientDrawable s = new GradientDrawable();
        s.setColor(selected);
        s.setCornerRadius(radius);
        if (stroke > 0) s.setStroke(stroke, Color.WHITE);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_selected}, s);
        sl.addState(new int[]{android.R.attr.state_focused}, s);
        sl.addState(new int[]{}, n);
        return sl;
    }

    private void animate(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        // translationZ draws the focused view on top without re-ordering siblings (bringToFront would
        // move it to the end of its row and break the D-pad order)
        v.setTranslationZ(focused ? dp(8) : 0);
    }

    // ---------------------------------------------------------------- weather (Open-Meteo, at most hourly, only while the home screen is visible)

    private String cityPref() {
        return prefs.getString(K_CITY, "London,GB").replace("|", "");   // change it in Options > Weather city
    }

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather(false);
            main.postDelayed(this, WX_REFRESH_MS);
        }
    };

    private JSONObject cachedWeather() {
        try {
            String s = prefs.getString(K_WX, null);
            if (s == null) return null;
            JSONObject j = new JSONObject(s);
            return j.optString("city").equals(cityPref()) ? j : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void showWeather() {
        weatherCard.show(cachedWeather());
    }

    // ---- weather card: glass card with location, current conditions and the next four hours

    private class WeatherCard {
        final LinearLayout view;
        private final TextView place, updated, temp, feels, cond;
        private final WeatherIconView mainIcon;
        private final TextView[] hTime = new TextView[4], hTemp = new TextView[4], hFeels = new TextView[4];
        private final WeatherIconView[] hIcon = new WeatherIconView[4];

        WeatherCard() {
            view = new LinearLayout(HomeActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(14), dp(8), dp(14), dp(8));
            // a blue-tinted glass that sits well on both the pink sky and the blue clouds of the wallpaper
            view.setBackground(glassShape(dp(20), 0x8A2C4C7C, 0x7A1F3562, 0x70FFFFFF, 1));

            LinearLayout head = new LinearLayout(HomeActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(new LocationIconView(HomeActivity.this), new LinearLayout.LayoutParams(dp(11), dp(11)));
            place = text(13, Color.WHITE, true);
            place.setPadding(dp(5), 0, 0, 0);
            head.addView(place, new LinearLayout.LayoutParams(0, -2, 1f));
            updated = text(9, BLUSH, false);
            head.addView(updated);
            view.addView(head);

            LinearLayout main = new LinearLayout(HomeActivity.this);
            main.setGravity(Gravity.CENTER_VERTICAL);
            mainIcon = new WeatherIconView(HomeActivity.this);
            main.addView(mainIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            temp = text(34, Color.WHITE, false);
            temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            temp.setPadding(dp(8), 0, 0, 0);
            main.addView(temp);
            TextView unit = text(13, Color.WHITE, false);
            unit.setText("°C");
            unit.setPadding(dp(2), 0, 0, dp(14));
            main.addView(unit);
            LinearLayout fc = new LinearLayout(HomeActivity.this);
            fc.setOrientation(LinearLayout.VERTICAL);
            fc.setPadding(dp(12), 0, 0, 0);
            feels = text(11, BLUSH, false);
            cond = text(11, Color.WHITE, true);
            fc.addView(feels);
            fc.addView(cond);
            main.addView(fc);
            view.addView(main);

            LinearLayout hours = new LinearLayout(HomeActivity.this);
            hours.setPadding(0, dp(2), 0, 0);
            for (int i = 0; i < 4; i++) {
                LinearLayout c = new LinearLayout(HomeActivity.this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                hTime[i] = text(9, BLUSH, false);
                hIcon[i] = new WeatherIconView(HomeActivity.this);
                hTemp[i] = text(12, Color.WHITE, true);
                hFeels[i] = text(8, 0xCCFFE4D8, false);
                c.addView(hTime[i]);
                c.addView(hIcon[i], new LinearLayout.LayoutParams(dp(20), dp(20)));
                c.addView(hTemp[i]);
                c.addView(hFeels[i]);
                hours.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            view.addView(hours);
            show(null);
        }

        private TextView text(int sp, int color, boolean bold) {
            TextView t = new TextView(HomeActivity.this);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return t;
        }

        void show(JSONObject j) {
            if (j == null) {
                place.setText(cityPref().split(",", 2)[0].trim());
                updated.setText("");
                temp.setText("--");
                feels.setText("");
                cond.setText("Loading weather...");
                mainIcon.set(3, true);
                for (int i = 0; i < 4; i++) { hTime[i].setText(""); hTemp[i].setText(""); hFeels[i].setText(""); hIcon[i].set(-1, true); }
                return;
            }
            try {
                place.setText(j.getString("city").split(",", 2)[0].trim());
                updated.setText("Updated at " + new java.text.SimpleDateFormat("h:mma", java.util.Locale.getDefault())
                        .format(new java.util.Date(j.getLong("fetched"))).toLowerCase());
                boolean day = j.getInt("day") == 1;
                temp.setText(String.valueOf(Math.round(j.getDouble("t"))));
                feels.setText("Feels " + Math.round(j.getDouble("feels")));
                cond.setText(describe(j.getInt("code"), day));
                mainIcon.set(j.getInt("code"), day);
                JSONArray h = j.getJSONArray("hours");
                for (int i = 0; i < 4; i++) {
                    if (i >= h.length()) { hTime[i].setText(""); hTemp[i].setText(""); hFeels[i].setText(""); hIcon[i].set(-1, true); continue; }
                    JSONObject o = h.getJSONObject(i);
                    int hr = Integer.parseInt(o.getString("ts").substring(11, 13));
                    hTime[i].setText((hr % 12 == 0 ? 12 : hr % 12) + (hr < 12 ? "am" : "pm"));
                    hTemp[i].setText(Math.round(o.getDouble("t")) + "°");
                    hFeels[i].setText("Feels " + Math.round(o.getDouble("f")));
                    hIcon[i].set(o.getInt("code"), o.getInt("day") == 1);
                }
            } catch (Exception e) {
                show(null);
            }
        }
    }

    /** Small navigation-arrow glyph for the location line. */
    private static class LocationIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        LocationIconView(Context c) { super(c); p.setColor(Color.WHITE); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            path.rewind();
            path.moveTo(w * 0.5f, h * 0.02f);
            path.lineTo(w * 0.95f, h * 0.98f);
            path.lineTo(w * 0.5f, h * 0.72f);
            path.lineTo(w * 0.05f, h * 0.98f);
            path.close();
            c.drawPath(path, p);
        }
    }

    /** Draws sun / moon / cloud / rain / snow / storm / fog icons from simple shapes; code -1 draws nothing. */
    private static class WeatherIconView extends View {
        private int code = -1;
        private boolean day = true;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        WeatherIconView(Context c) { super(c); }

        void set(int code, boolean day) { this.code = code; this.day = day; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (code < 0) return;
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (code == 0) {
                if (day) sun(c, cx, cy, s * 0.24f); else moon(c, cx, cy, s * 0.34f);
            } else if (code <= 2) {
                if (day) sun(c, cx - s * 0.12f, cy - s * 0.12f, s * 0.17f); else moon(c, cx - s * 0.12f, cy - s * 0.12f, s * 0.26f);
                cloud(c, cx + s * 0.04f, cy + s * 0.12f, s * 0.78f, 0xFFF2F5FA);
            } else if (code == 3) {
                cloud(c, cx, cy, s * 0.9f, 0xFFE4E9F2);
            } else if (code == 45 || code == 48) {
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, 0xFFD5DBE6);
                p.setColor(0xFFD5DBE6);
                p.setStrokeWidth(s * 0.07f);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx - s * 0.30f, cy + s * 0.26f, cx + s * 0.30f, cy + s * 0.26f, p);
                c.drawLine(cx - s * 0.20f, cy + s * 0.40f, cx + s * 0.20f, cy + s * 0.40f, p);
            } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, 0xFFE4E9F2);
                p.setColor(Color.WHITE);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.22f, cy + s * 0.32f + (i == 0 ? s * 0.05f : 0), s * 0.055f, p);
            } else if (code >= 95) {
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, 0xFFB9C1D0);
                p.setColor(0xFFFFC53D);
                path.rewind();
                path.moveTo(cx + s * 0.06f, cy + s * 0.04f);
                path.lineTo(cx - s * 0.12f, cy + s * 0.28f);
                path.lineTo(cx, cy + s * 0.28f);
                path.lineTo(cx - s * 0.06f, cy + s * 0.46f);
                path.lineTo(cx + s * 0.14f, cy + s * 0.20f);
                path.lineTo(cx + s * 0.02f, cy + s * 0.20f);
                path.close();
                c.drawPath(path, p);
            } else {   // drizzle, rain, showers
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, 0xFFCBD3E1);
                p.setColor(0xFF5BB6FF);
                p.setStrokeWidth(s * 0.06f);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * s * 0.22f;
                    c.drawLine(x + s * 0.04f, cy + s * 0.20f, x - s * 0.04f, cy + s * 0.38f, p);
                }
            }
        }

        private void sun(Canvas c, float cx, float cy, float r) {
            p.setColor(0xFFFFC53D);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r, p);
            p.setStrokeWidth(r * 0.28f);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                c.drawLine((float) (cx + Math.cos(a) * r * 1.4f), (float) (cy + Math.sin(a) * r * 1.4f),
                        (float) (cx + Math.cos(a) * r * 1.85f), (float) (cy + Math.sin(a) * r * 1.85f), p);
            }
        }

        private void moon(Canvas c, float cx, float cy, float r) {
            Path a = new Path(), b = new Path();
            a.addCircle(cx, cy, r, Path.Direction.CW);
            b.addCircle(cx + r * 0.5f, cy - r * 0.3f, r * 0.85f, Path.Direction.CW);
            a.op(b, Path.Op.DIFFERENCE);
            p.setColor(0xFFEAEEFF);
            p.setStyle(Paint.Style.FILL);
            c.drawPath(a, p);
        }

        private void cloud(Canvas c, float cx, float cy, float w, int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - w * 0.22f, cy + w * 0.06f, w * 0.18f, p);
            c.drawCircle(cx - w * 0.02f, cy - w * 0.06f, w * 0.25f, p);
            c.drawCircle(cx + w * 0.24f, cy + w * 0.05f, w * 0.19f, p);
            c.drawRoundRect(cx - w * 0.40f, cy + w * 0.0f, cx + w * 0.43f, cy + w * 0.24f, w * 0.12f, w * 0.12f, p);
        }
    }

    private static String describe(int code, boolean day) {
        if (code == 0) return day ? "Clear" : "Clear night";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    private void refreshWeather(boolean force) {
        final String city = cityPref();
        JSONObject cached = cachedWeather();
        if (!force && cached != null
                && System.currentTimeMillis() - cached.optLong("fetched") < WX_REFRESH_MS) return;
        net.execute(() -> {
            try {
                double lat, lon;
                String geo = prefs.getString(K_GEO, null);   // city|lat|lon, so the city name is sent only once
                if (geo != null && geo.startsWith(city + "|")) {
                    String[] g = geo.split("\\|");
                    lat = Double.parseDouble(g[1]);
                    lon = Double.parseDouble(g[2]);
                } else {
                    String[] parts = city.split(",", 2);
                    String u = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                            + URLEncoder.encode(parts[0].trim(), "UTF-8")
                            + (parts.length > 1 && !parts[1].trim().isEmpty()
                            ? "&countryCode=" + URLEncoder.encode(parts[1].trim(), "UTF-8") : "");
                    JSONArray r = getJson(u).optJSONArray("results");
                    if (r == null || r.length() == 0) {
                        main.post(() -> Toast.makeText(this, "Weather city not found: " + city, Toast.LENGTH_LONG).show());
                        return;
                    }
                    lat = r.getJSONObject(0).getDouble("latitude");
                    lon = r.getJSONObject(0).getDouble("longitude");
                    prefs.edit().putString(K_GEO, city + "|" + lat + "|" + lon).apply();
                }
                String q = "temperature_2m,apparent_temperature,weather_code,is_day";
                JSONObject w = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=" + q + "&hourly=" + q + "&forecast_hours=8&timezone=auto");
                JSONObject cur = w.getJSONObject("current");
                JSONObject hr = w.getJSONObject("hourly");
                JSONArray ht = hr.getJSONArray("time");
                JSONArray hours = new JSONArray();
                for (int i = 0; i < ht.length() && hours.length() < 4; i++) {
                    if (ht.getString(i).compareTo(cur.getString("time")) <= 0) continue;   // only hours after now
                    JSONObject o = new JSONObject();
                    o.put("ts", ht.getString(i));
                    o.put("t", hr.getJSONArray("temperature_2m").getDouble(i));
                    o.put("f", hr.getJSONArray("apparent_temperature").getDouble(i));
                    o.put("code", hr.getJSONArray("weather_code").getInt(i));
                    o.put("day", hr.getJSONArray("is_day").getInt(i));
                    hours.put(o);
                }
                JSONObject out = new JSONObject();
                out.put("city", city);
                out.put("t", cur.getDouble("temperature_2m"));
                out.put("feels", cur.getDouble("apparent_temperature"));
                out.put("code", cur.getInt("weather_code"));
                out.put("day", cur.getInt("is_day"));
                out.put("fetched", System.currentTimeMillis());
                out.put("hours", hours);
                prefs.edit().putString(K_WX, out.toString()).apply();
                main.post(this::showWeather);
            } catch (Exception e) {
                // offline or service down: keep the last value, the next hourly tick retries
            }
        });
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Lists the TV's external inputs (HDMI, AV...) and switches to the chosen one via the documented passthrough URI. */
    private void showInputs() {
        TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        final List<TvInputInfo> inputs = new ArrayList<>();
        if (tm != null) {
            try {
                for (TvInputInfo i : tm.getTvInputList()) if (i.isPassthroughInput()) inputs.add(i);
            } catch (RuntimeException ignored) { }
        }
        if (inputs.isEmpty()) {
            Toast.makeText(this, "No external inputs found", Toast.LENGTH_SHORT).show();
            return;
        }
        final Map<String, String> label = new HashMap<>();
        for (TvInputInfo i : inputs) {
            CharSequence custom = i.loadCustomLabel(this);
            label.put(i.getId(), String.valueOf(custom != null ? custom : i.loadLabel(this)));
        }
        // HDMI 1, 2, 3, 4 first, then everything else (Composite, AV...), each group alphabetical
        Collections.sort(inputs, (x, y) -> {
            String a = label.get(x.getId()), b = label.get(y.getId());
            boolean ha = a.startsWith("HDMI"), hb = b.startsWith("HDMI");
            if (ha != hb) return ha ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });
        Menu m = new Menu("Inputs", 620);
        m.subtitle("Green: a device is connected   ·   Amber: standby   ·   Grey: nothing connected");
        for (final TvInputInfo in : inputs) {
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try { state = tm.getInputState(in.getId()); } catch (RuntimeException ignored) { }
            final String status;
            final int color;
            if (state == TvInputManager.INPUT_STATE_CONNECTED) { status = "Connected"; color = 0xFF34C759; }
            else if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) { status = "Standby"; color = 0xFFFFB020; }
            else { status = "No signal"; color = 0xFF7A8294; }
            // Android's public API gives the port's state but not the connected device's name; a name only
            // appears if you have renamed the input in the TV's own settings (that custom label is used above).
            View row = m.row(label.get(in.getId()), null, new DotDrawable(color), () -> status,
                    () -> startSafe(new Intent(Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(in.getId()))), false);
            if (state == TvInputManager.INPUT_STATE_DISCONNECTED) row.setAlpha(0.55f);   // unused ports fade back
        }
        m.show();
    }

    private void showCityDialog() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(cityPref());
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Weather city (e.g. London,GB)")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String v = e.getText().toString().replace("|", "").trim();
                    if (v.isEmpty()) return;
                    prefs.edit().putString(K_CITY, v).apply();
                    showWeather();
                    refreshWeather(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- actions

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, a.label + " is not available", Toast.LENGTH_SHORT).show();
            loadApps();
        }
    }

    private void startSafe(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppMenu(final App a) {
        final List<String> dockL = dockList();
        final boolean inDock = dockL.contains(a.pkg);
        Menu m = new Menu(a.label, 520);
        if (inDock) {
            m.add("Remove from dock", () -> { dockL.remove(a.pkg); saveDock(dockL); rebuild(a.pkg); });
            m.add("Move left in dock", () -> {
                int i = dockL.indexOf(a.pkg);
                if (i > 0) Collections.swap(dockL, i, i - 1);
                saveDock(dockL);
                rebuild(a.pkg);
            });
            m.add("Move right in dock", () -> {
                int i = dockL.indexOf(a.pkg);
                if (i >= 0 && i < dockL.size() - 1) Collections.swap(dockL, i, i + 1);
                saveDock(dockL);
                rebuild(a.pkg);
            });
        } else {
            m.add("Add to dock", () -> {
                if (visibleDockCount(dockL) >= prefs.getInt(K_DOCK_N, DEFAULT_DOCK_N)) {
                    Toast.makeText(this, "Dock is full - remove an app or raise the slots in Options",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                dockL.add(a.pkg);
                saveDock(dockL);
                rebuild(a.pkg);
            });
        }
        m.add("Hide from launcher", () -> {
            Set<String> h = hidden();
            h.add(a.pkg);
            prefs.edit().putStringSet(K_HIDDEN, h).apply();
            rebuild(null);
        });
        m.show();
    }

    private int visibleDockCount(List<String> l) {
        Set<String> hid = hidden();
        int c = 0;
        for (String p : l) if (find(p) != null && !hid.contains(p)) c++;
        return c;
    }

    /**
     * Our own quick-settings panel. The TV's Settings button opens Google's panel, which lives inside the Google TV
     * launcher; with that launcher disabled this panel gives the same shortcuts. Every row opens the TV's own screen.
     */
    private Dialog quickDialog;

    private void showQuickSettings() {
        if (quickDialog != null && quickDialog.isShowing()) { quickDialog.dismiss(); return; }   // second press closes it
        final Dialog dlg = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        quickDialog = dlg;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(glassShape(dp(28), 0xEA26324A, 0xEA161D30, 0x70FFFFFF, 1));

        TextView title = new TextView(this);
        title.setText("Control Centre");
        title.setTextColor(INK);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(10));
        panel.addView(title);

        final String net = wifiLabel == null ? "Wi-Fi" : wifiLabel.getText().toString();
        final boolean online = !net.equals("Offline");
        final List<View> tiles = new ArrayList<>();

        // big tiles: a round glyph bubble and two lines of text, like the Mac Control Centre
        LinearLayout r1 = ccRow(panel);
        tiles.add(ccTile(r1, "Wi-Fi", online ? "Connected" : "Offline", GlyphView.WIFI, online ? 0xFF2F7FD8 : 0xFF5A6478, dlg,
                () -> startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        tiles.add(ccTile(r1, "Bluetooth", "Remotes, speakers", GlyphView.BLUETOOTH, 0xFF2F7FD8, dlg, () -> {
            Intent i = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.accessories.AddAccessoryActivity"));
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (ActivityNotFoundException | SecurityException e) { startSafe(new Intent(Settings.ACTION_SETTINGS)); }
        }));
        LinearLayout r2 = ccRow(panel);
        tiles.add(ccTile(r2, "Inputs", "HDMI, AV", GlyphView.INPUT, 0xFF7B5CE6, dlg, this::showInputs));
        tiles.add(ccTile(r2, "Sound", "Volume, Dolby", GlyphView.SOUND, 0xFFE0657A, dlg,
                () -> startSafe(new Intent(Settings.ACTION_SOUND_SETTINGS))));
        LinearLayout r3 = ccRow(panel);
        tiles.add(ccTile(r3, "Display", "Picture, HDR", GlyphView.DISPLAY, 0xFFE89A3C, dlg,
                () -> startSafe(new Intent(Settings.ACTION_DISPLAY_SETTINGS))));
        tiles.add(ccTile(r3, "Options", "Home screen", GlyphView.OPTIONS, 0xFF3FA37A, dlg, this::showOptions));
        LinearLayout r4 = ccRow(panel);
        tiles.add(ccTile(r4, "All settings", "Full Settings app", GlyphView.SETTINGS, 0xFF5A6478, dlg,
                () -> startSafe(new Intent(Settings.ACTION_SETTINGS))));

        TextView hint = new TextView(this);
        hint.setText("BACK TO CLOSE");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(9);
        hint.setLetterSpacing(0.12f);
        hint.setPadding(dp(6), dp(8), 0, 0);
        panel.addView(hint);

        dlg.setContentView(panel);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(430), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.TOP | Gravity.END);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = dp(36);
            lp.y = dp(22);
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    private LinearLayout ccRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private View ccTile(LinearLayout row, String name, String sub, int glyph, int bubble, final Dialog dlg, final Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setColor(ACCENT);
        on.setCornerRadius(dp(20));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, glassShape(dp(20), 0x40FFFFFF, 0x24FFFFFF, 0x40FFFFFF, 1));
        t.setBackground(bg);

        FrameLayout b = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(bubble);
        b.setBackground(circle);
        GlyphView g = new GlyphView(this, glyph);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        b.addView(g, glp);
        t.addView(b, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(11), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(INK);
        n.setTextSize(14);
        n.setSingleLine(true);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xCCFFE4D8);
        s.setTextSize(11);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(n);
        tx.addView(s);
        t.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(t, lp);
        t.setOnClickListener(v -> { dlg.dismiss(); action.run(); });
        return t;
    }

    private void showOptions() {
        final String[] sizes = {"Small", "Medium", "Large"};
        Menu m = new Menu("Home options", 600);
        m.addStay("Dock slots", () -> String.valueOf(prefs.getInt(K_DOCK_N, DEFAULT_DOCK_N)), () -> {
            int n = prefs.getInt(K_DOCK_N, DEFAULT_DOCK_N);
            prefs.edit().putInt(K_DOCK_N, n >= 6 ? 3 : n + 1).apply();
            rebuild(lastFocused);
        });
        m.addStay("Dock icon size", () -> sizes[prefs.getInt(K_DOCK_SIZE, 1)], () -> {
            prefs.edit().putInt(K_DOCK_SIZE, (prefs.getInt(K_DOCK_SIZE, 1) + 1) % 3).apply();
            rebuild(lastFocused);
        });
        final String[] walls = {"Near black", "Warm sunset", "Spheres", "Photo"};
        final int wallCount = hasPhoto() ? walls.length : walls.length - 1;
        final int wallDefault = hasPhoto() ? 3 : 0;
        m.addStay("Wallpaper", () -> walls[prefs.getInt(K_WALL, wallDefault)], () -> {
            int w = (prefs.getInt(K_WALL, wallDefault) + 1) % wallCount;
            prefs.edit().putInt(K_WALL, w).apply();
            wallpaper.setStyle(w);
        });
        m.add("Quick settings", null, this::showQuickSettings);
        m.add("Apps shown in launcher", () -> hidden().size() + " hidden", this::showVisibilityDialog);
        m.add("All apps  (sort, uninstall)", null, this::showAllApps);
        m.add("Weather city", this::cityPref, this::showCityDialog);
        m.add("Reset dock to defaults", null, () -> {
            prefs.edit().remove(K_DOCK).remove(K_DOCK_N).remove(K_DOCK_SIZE).apply();
            rebuild(lastFocused);
        });
        m.show();
    }

    private void showVisibilityDialog() {
        Menu m = new Menu("Apps shown in launcher", 640);
        m.subtitle("Hiding an app does not uninstall it");
        for (final App a : apps) {
            m.row(a.label, null, a.image, () -> hidden().contains(a.pkg) ? "Hidden" : "Shown", () -> {
                Set<String> h = hidden();
                if (!h.remove(a.pkg)) h.add(a.pkg);
                prefs.edit().putStringSet(K_HIDDEN, h).apply();
            }, true);
        }
        m.dialog.setOnDismissListener(d -> rebuild(lastFocused));
        m.show();
    }

    // ---------------------------------------------------------------- all apps (sort + uninstall)

    private static class InstalledApp {
        String pkg, label;
        Drawable icon;
        long installed;
        boolean system, launchable;
    }

    private List<InstalledApp> allAppsData;
    private Runnable allAppsRefresh;   // set while the All apps list is open, so it follows installs/uninstalls

    private void loadAllApps(final Runnable then) {
        final PackageManager pm = getPackageManager();
        io.execute(() -> {
            List<InstalledApp> out = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(getPackageName())) continue;
                boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                boolean launchable = pm.getLeanbackLaunchIntentForPackage(ai.packageName) != null
                        || pm.getLaunchIntentForPackage(ai.packageName) != null;
                if (sys && !launchable) continue;   // skip background system components
                InstalledApp r = new InstalledApp();
                r.pkg = ai.packageName;
                r.label = String.valueOf(pm.getApplicationLabel(ai));
                r.system = sys;
                r.launchable = launchable;
                try { r.icon = pm.getApplicationIcon(ai); } catch (Throwable ignored) { }
                try { r.installed = pm.getPackageInfo(ai.packageName, 0).firstInstallTime; } catch (Exception ignored) { }
                out.add(r);
            }
            main.post(() -> { allAppsData = out; then.run(); });
        });
    }

    private void showAllApps() {
        final Menu m = new Menu("All apps", 780);
        final int[] sort = {0};          // 0 = name A-Z (default), 1 = date installed, newest first
        final boolean[] userOnly = {false};
        final java.text.DateFormat df = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
        final Runnable[] populate = new Runnable[1];
        populate[0] = () -> {
            m.clear();
            if (allAppsData == null) { m.info("Loading..."); return; }
            List<InstalledApp> list = new ArrayList<>();
            for (InstalledApp r : allAppsData) if (!userOnly[0] || !r.system) list.add(r);
            if (sort[0] == 0) Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            else Collections.sort(list, (x, y) -> Long.compare(y.installed, x.installed));
            m.subtitle(list.size() + " apps");
            m.row("Sort by", null, null, () -> sort[0] == 0 ? "Name (A-Z)" : "Date installed (newest first)",
                    () -> { sort[0] = 1 - sort[0]; populate[0].run(); m.focusRow(0); }, true);
            m.row("Show", null, null, () -> userOnly[0] ? "Apps I installed" : "All apps",
                    () -> { userOnly[0] = !userOnly[0]; populate[0].run(); m.focusRow(1); }, true);
            for (final InstalledApp r : list) {
                String sub = r.pkg + "  ·  installed " + (r.installed > 0 ? df.format(new java.util.Date(r.installed)) : "?");
                // stay = true: the list stays open underneath the app's own action menu
                m.row(r.label, sub, r.icon, () -> r.system ? "System" : "", () -> showAppActions(r), true);
            }
        };
        final Runnable reload = () -> loadAllApps(() -> { populate[0].run(); m.focusRow(0); });
        allAppsRefresh = reload;
        m.dialog.setOnDismissListener(d -> { allAppsRefresh = null; allAppsData = null; });
        populate[0].run();   // shows "Loading..." first
        m.show();
        reload.run();
    }

    private void showAppActions(final InstalledApp r) {
        Menu m = new Menu(r.label, 520);
        m.subtitle(r.pkg);
        if (r.launchable) m.add("Open", null, () -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(r.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(r.pkg);
            if (i != null) startSafe(i);
        });
        if (!r.system) {
            // the system installer shows its own confirmation; nothing is removed until it is accepted there
            m.add("Uninstall", null, () -> startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.pkg))));
        }
        m.add("App info", null, () -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + r.pkg))));
        if (r.system) m.info("System app: it can't be uninstalled from here");
        m.show();
    }

    // ---------------------------------------------------------------- glass menu dialog

    private static final int ACCENT = 0xFF2F7FD8;   // highlighted option: the cerulean of the wallpaper

    private static class MaxHeightScrollView extends ScrollView {
        private final int max;
        MaxHeightScrollView(Context c, int max) { super(c); this.max = max; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    /** A glass panel with a list of options; the focused option is filled with the accent colour. */
    private class Menu {
        final Dialog dialog;
        final LinearLayout rows;
        private final TextView subtitleView;
        private final List<Runnable> refreshers = new ArrayList<>();

        Menu(String title, int widthDp) {
            dialog = new Dialog(HomeActivity.this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            LinearLayout panel = new LinearLayout(HomeActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(24), dp(22), dp(24), dp(16));
            panel.setBackground(glassShape(dp(28), 0xF2262E44, 0xF2131825, 0x70FFFFFF, 1));
            TextView t = new TextView(HomeActivity.this);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(22);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            panel.addView(t);
            subtitleView = new TextView(HomeActivity.this);
            subtitleView.setTextColor(0xFFA9B1C6);
            subtitleView.setTextSize(13);
            subtitleView.setVisibility(View.GONE);
            subtitleView.setPadding(0, dp(2), 0, 0);
            panel.addView(subtitleView);
            MaxHeightScrollView sv = new MaxHeightScrollView(HomeActivity.this,
                    Math.round(getResources().getDisplayMetrics().heightPixels * 0.68f));
            sv.setVerticalScrollBarEnabled(false);
            sv.setPadding(0, dp(12), 0, 0);
            rows = new LinearLayout(HomeActivity.this);
            rows.setOrientation(LinearLayout.VERTICAL);
            sv.addView(rows, new ViewGroup.LayoutParams(-1, -2));
            panel.addView(sv, new LinearLayout.LayoutParams(-1, -2));
            dialog.setContentView(panel);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setDimAmount(0.6f);
            }
        }

        void subtitle(String s) { subtitleView.setText(s); subtitleView.setVisibility(View.VISIBLE); }
        void clear() { rows.removeAllViews(); refreshers.clear(); }
        void show() { dialog.show(); focusRow(0); }
        void focusRow(int i) { if (i < rows.getChildCount()) rows.getChildAt(i).requestFocus(); }

        /** Plain option: closes the menu, then runs the action. */
        void add(String text, java.util.function.Supplier<String> value, Runnable action) {
            row(text, null, null, value, action, false);
        }
        void add(String text, Runnable action) { row(text, null, null, null, action, false); }
        /** Option that changes a setting and keeps the menu open, refreshing the shown values. */
        void addStay(String text, java.util.function.Supplier<String> value, Runnable action) {
            row(text, null, null, value, action, true);
        }
        /** Non-selectable note. */
        void info(String text) {
            TextView t = new TextView(HomeActivity.this);
            t.setText(text);
            t.setTextColor(0xFFA9B1C6);
            t.setTextSize(14);
            t.setPadding(dp(16), dp(10), dp(16), dp(10));
            rows.addView(t);
        }

        View row(String text, String sub, Drawable icon, java.util.function.Supplier<String> value,
                 final Runnable action, final boolean stay) {
            LinearLayout r = new LinearLayout(HomeActivity.this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(54));
            r.setPadding(dp(16), dp(6), dp(16), dp(6));
            r.setFocusable(true);
            r.setClickable(true);
            GradientDrawable on = new GradientDrawable();
            on.setColor(ACCENT);
            on.setCornerRadius(dp(14));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[]{android.R.attr.state_pressed}, on);
            bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            r.setBackground(bg);
            if (icon != null) {
                ImageView iv = new ImageView(HomeActivity.this);
                iv.setImageDrawable(icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(9));
                    }
                });
                iv.setClipToOutline(true);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
                ilp.rightMargin = dp(14);
                r.addView(iv, ilp);
            }
            LinearLayout texts = new LinearLayout(HomeActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(HomeActivity.this);
            name.setText(text);
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            if (sub != null) {
                TextView st = new TextView(HomeActivity.this);
                st.setText(sub);
                st.setTextColor(0xFFC3CADB);
                st.setTextSize(12);
                st.setSingleLine(true);
                st.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(st);
            }
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            if (value != null) {
                final TextView vt = new TextView(HomeActivity.this);
                vt.setTextColor(0xFFE1E6F2);
                vt.setTextSize(15);
                vt.setPadding(dp(16), 0, 0, 0);
                vt.setText(value.get());
                r.addView(vt);
                refreshers.add(() -> vt.setText(value.get()));
            }
            r.setOnClickListener(v -> {
                if (stay) {
                    action.run();
                    for (Runnable f : refreshers) f.run();
                } else {
                    dialog.dismiss();
                    action.run();
                }
            });
            rows.addView(r, new LinearLayout.LayoutParams(-1, -2));
            return r;
        }
    }

    /** A status dot centred in the icon slot (green, amber or grey). */
    private static class DotDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotDrawable(int color) {
            p.setColor(color);
            halo.setColor((color & 0x00FFFFFF) | 0x40000000);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
            c.drawCircle(cx, cy, r * 0.62f, halo);
            c.drawCircle(cx, cy, r * 0.34f, p);
        }
        @Override public int getIntrinsicWidth() { return 64; }
        @Override public int getIntrinsicHeight() { return 64; }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- network status (callback, no polling)

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || netCallback != null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                final String s = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Network";
                main.post(() -> wifiLabel.setText(s));
            }
            @Override public void onLost(Network n) { main.post(() -> wifiLabel.setText("Offline")); }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (RuntimeException e) { netCallback = null; }
    }

    private void unwatchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (RuntimeException ignored) { }
        }
        netCallback = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

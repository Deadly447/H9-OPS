package io.github.deadly447.h9ops;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/**
 * The "OLED Field" look: true black, one accent colour (amber, or red in
 * night mode to keep night vision), callsigns in JetBrains Mono.
 */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF000000, SURFACE = 0xFF0D0D0D, BORDER = 0xFF262626, DIVIDER = 0xFF1A1A1A,
            BUBBLE = 0xFF141414, CHIP = 0xFF333333, OFF = 0xFF555555, ON_ACCENT = 0xFF000000;
    static int accent = 0xFFFFB000, text = 0xFFEDEDED, muted = 0xFF9A9A9A;
    static boolean night;

    private static Typeface mono, monoBold;

    /** Reads the night-red setting. Call before building any view. */
    static void load(Context c) {
        night = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("nightred", false);
        accent = night ? 0xFFFF453A : 0xFFFFB000;
        text = night ? 0xFFFF6B61 : 0xFFEDEDED;
        muted = night ? 0xFFB8443D : 0xFF9A9A9A;
        if (mono == null) {
            try {
                mono = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Regular.ttf");
                monoBold = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Bold.ttf");
            } catch (RuntimeException e) {
                mono = Typeface.MONOSPACE;
                monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            }
        }
    }

    static void setNight(Context c, boolean on) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("nightred", on).apply();
        load(c);
    }

    static Typeface mono() { return mono; }
    static Typeface monoBold() { return monoBold; }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable box(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** Rounded corners set one by one (top-left, top-right, bottom-right, bottom-left), in dp. */
    static GradientDrawable bubble(Context c, int fill, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float a = dp(c, tl), b = dp(c, tr), d = dp(c, br), e = dp(c, bl);
        g.setCornerRadii(new float[]{a, a, b, b, d, d, e, e});
        return g;
    }

    private static void base(Button b) {
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(b.getContext(), 44));
        b.setMinimumHeight(dp(b.getContext(), 44));
        b.setMinWidth(dp(b.getContext(), 44));
        b.setMinimumWidth(dp(b.getContext(), 44));
        b.setPadding(dp(b.getContext(), 14), 0, dp(b.getContext(), 14), 0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
    }

    private static void ripple(Button b, GradientDrawable bg) {
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
    }

    /** Quiet button: dark surface, thin border. */
    static void plain(Button b) {
        base(b);
        b.setTextColor(text);
        ripple(b, box(b.getContext(), SURFACE, BORDER, 12));
    }

    /** Outlined in the accent colour (Connect, New message). */
    static void outline(Button b) {
        base(b);
        b.setTextColor(accent);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        ripple(b, box(b.getContext(), SURFACE, accent, 12));
    }

    /** Filled with the accent colour (Send, the selected tab). */
    static void filled(Button b, float radiusDp) {
        base(b);
        b.setTextColor(ON_ACCENT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        ripple(b, box(b.getContext(), accent, 0, radiusDp));
    }

    /** A small bordered tag such as DIRECT or VIA RIDGE. */
    static void chip(TextView t, String label, boolean highlight) {
        t.setText(label);
        t.setTextSize(11);
        t.setTypeface(mono);
        t.setTextColor(highlight ? accent : muted);
        int p = dp(t.getContext(), 6), q = dp(t.getContext(), 2);
        t.setPadding(p, q, p, q);
        t.setBackground(box(t.getContext(), BG, highlight ? accent : CHIP, 6));
    }

    /** "34s", "2m", "1h", "3d": short age for list rows. */
    static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }
}

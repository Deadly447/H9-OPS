package io.github.deadly447.h9ops;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The radio card at the top of the main screen ("tactical readout"): link state and
 * battery, the radio's name, a table of channels A and B (firmware P2 and later),
 * and the Connect / Disconnect button. Tapping the card opens the radio details.
 */
final class RadioCard {
    final LinearLayout root;
    final Button button;
    private final Context c;
    private final View dot;
    private final TextView state, battery, device;
    private final LinearLayout bar, table;
    private final View barFill, barRest;
    private final LinearLayout[] rows = new LinearLayout[2];
    private final TextView[][] cells = new TextView[2][6];
    /** Column widths in dp; 0 = the frequency column, which takes the rest. */
    private static final int[] COLS = {30, 0, 40, 40, 30, 58};

    RadioCard(Context c, View.OnClickListener onCard, View.OnClickListener onButton) {
        this.c = c;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackground(new Brackets(dp(1), dp(2), dp(14), dp(6)));
        root.setOnClickListener(onCard);

        // RADIO LINK  ● LISTENING                    62% · 7.74V
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("RADIO LINK", 11, Ui.muted, false);
        label.setLetterSpacing(0.2f);
        top.addView(label);
        dot = new View(c);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
        dl.setMargins(dp(10), 0, dp(6), 0);
        top.addView(dot, dl);
        state = text("", 13, Ui.accent, true);
        top.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        battery = text("", 13, Ui.text, true);
        top.addView(battery);
        root.addView(top);

        // battery bar
        bar = new LinearLayout(c);
        bar.setBackground(Ui.box(c, 0xFF1F1F1F, 0, 2));
        barFill = new View(c);
        barFill.setBackground(Ui.box(c, Ui.accent, 0, 2));
        barRest = new View(c);
        bar.addView(barFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 50));
        bar.addView(barRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 50));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        bl.setMargins(0, dp(10), 0, 0);
        root.addView(bar, bl);

        // TD-H9-1234 · BLE · PIN LOCKED (or the state, or the quiet hint)
        device = text("", 12, Ui.muted, false);
        device.setMaxLines(2);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.setMargins(0, dp(10), 0, 0);
        root.addView(device, vl);

        // channel table
        table = new LinearLayout(c);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = row();
        String[] names = {"", "FREQ MHz", "CH", "PWR", "BW", "USE"};
        for (int i = 0; i < 6; i++) {
            TextView t = text(names[i], 10, Ui.muted, false);
            t.setLetterSpacing(0.15f);
            head.addView(t, col(i));
        }
        head.setPadding(dp(6), 0, dp(6), dp(4));
        table.addView(head);
        View rule = new View(c);
        rule.setBackgroundColor(Ui.BORDER);
        table.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        for (int r = 0; r < 2; r++) {
            rows[r] = row();
            rows[r].setPadding(dp(6), dp(8), dp(6), dp(8));
            for (int i = 0; i < 6; i++) {
                TextView t = text("", i == 1 ? 20 : i == 5 ? 11 : 13, i == 0 ? Ui.muted : Ui.text, i <= 1 || i == 5);
                cells[r][i] = t;
                rows[r].addView(t, col(i));
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.setMargins(0, dp(2), 0, 0);
            table.addView(rows[r], rl);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMargins(0, dp(12), 0, 0);
        root.addView(table, tl);

        // CONNECT / DISCONNECT
        button = new Button(c);
        button.setAllCaps(false);
        button.setTypeface(Ui.monoBold());
        button.setTextSize(13);
        button.setLetterSpacing(0.15f);
        button.setTextColor(Ui.accent);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(4));
        g.setStroke(Math.max(1, dp(1.5f)), Ui.accent);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        button.setOnClickListener(onButton);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        btl.setMargins(0, dp(12), 0, 0);
        root.addView(button, btl);
    }

    /** @param deviceLine the radio's name and PIN; @param detail the state text when not connected; @param hint the quiet hint or null */
    void update(Repo.State s, RadioInfo info, String deviceLine, String detail, String hint) {
        boolean connected = s == Repo.State.CONNECTED;
        state.setText(connected ? "LISTENING" : s == Repo.State.SCANNING ? "SEARCHING"
                : s == Repo.State.CONNECTING ? "CONNECTING" : s == Repo.State.WAITING ? "RECONNECTING" : "OFFLINE");
        state.setTextColor(connected ? Ui.accent : Ui.muted);
        dot.setBackground(oval(connected ? Ui.accent : s == Repo.State.STOPPED ? Ui.OFF : Ui.muted));
        battery.setVisibility(info != null ? View.VISIBLE : View.GONE);
        bar.setVisibility(info != null ? View.VISIBLE : View.GONE);
        table.setVisibility(info != null ? View.VISIBLE : View.GONE);
        if (info != null) {
            int pc = info.percent();
            battery.setText(pc + "% · " + String.format(java.util.Locale.US, "%.2fV", info.centivolts / 100.0));
            ((LinearLayout.LayoutParams) barFill.getLayoutParams()).weight = Math.max(1, pc);
            ((LinearLayout.LayoutParams) barRest.getLayoutParams()).weight = Math.max(0, 100 - pc);
            barFill.setBackground(Ui.box(c, pc <= 15 ? 0xFFFF453A : Ui.accent, 0, 2));
            bar.requestLayout();
            for (int r = 0; r < 2; r++) {
                String[] v = info.row(r == 1);
                boolean sel = (r == 1) == info.sideB;
                for (int i = 0; i < 6; i++) cells[r][i].setText(v[i]);
                cells[r][0].setTextColor(sel ? Ui.accent : Ui.muted);
                cells[r][4].setTextColor(v[4].equals("N") && v[5].contains("APRS") ? Ui.accent : Ui.text);
                cells[r][5].setTextColor(v[5].contains("APRS") ? Ui.accent : Ui.muted);
                rows[r].setBackground(sel ? Ui.box(c, (Ui.accent & 0x00FFFFFF) | 0x1A000000, 0, 4) : null);
            }
        }
        String line = hint != null ? hint : connected || s == Repo.State.STOPPED ? deviceLine : detail;
        device.setText(line);
        device.setTextColor(hint != null ? Ui.accent : Ui.muted);
        button.setText(s == Repo.State.STOPPED ? "CONNECT" : "DISCONNECT");
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams col(int i) {
        return COLS[i] == 0
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                : new LinearLayout.LayoutParams(dp(COLS[i]), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Ui.monoBold() : Ui.mono());
        t.setSingleLine(true);
        return t;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private int dp(float v) { return Ui.dp(c, v); }

    /** Dark panel with a thin border and an accent bracket on each corner. */
    static final class Brackets extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                corner = new Paint();
        private final float len, radius;

        Brackets(int borderPx, int cornerPx, int lenPx, int radiusPx) {
            fill.setColor(0xFF0A0A0A);
            edge.setColor(Ui.BORDER);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(borderPx);
            corner.setColor(Ui.accent);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(cornerPx);
            corner.setStrokeCap(Paint.Cap.SQUARE);
            len = lenPx;
            radius = radiusPx;
        }

        @Override
        public void draw(Canvas cv) {
            RectF b = new RectF(getBounds());
            float h = edge.getStrokeWidth() / 2f;
            RectF in = new RectF(b.left + h, b.top + h, b.right - h, b.bottom - h);
            cv.drawRoundRect(in, radius, radius, fill);
            cv.drawRoundRect(in, radius, radius, edge);
            float w = corner.getStrokeWidth() / 2f;
            float l = b.left + w, t = b.top + w, r = b.right - w, bt = b.bottom - w;
            cv.drawLine(l, t, l + len, t, corner);
            cv.drawLine(l, t, l, t + len, corner);
            cv.drawLine(r, t, r - len, t, corner);
            cv.drawLine(r, t, r, t + len, corner);
            cv.drawLine(l, bt, l + len, bt, corner);
            cv.drawLine(l, bt, l, bt - len, corner);
            cv.drawLine(r, bt, r - len, bt, corner);
            cv.drawLine(r, bt, r, bt - len, corner);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

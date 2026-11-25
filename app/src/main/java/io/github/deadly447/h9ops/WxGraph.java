package io.github.deadly447.h9ops;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import io.github.deadly447.h9ops.aprs.WxHistory;

/**
 * One small weather graph: a title and the latest value on top, a thin line below
 * (with an optional lighter second line, for gusts), three faint guide lines and
 * time marks. Touch and drag to read a value at any time.
 */
final class WxGraph extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), line2 = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), small = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String title = "", value = "", sub = "";
    private WxHistory.Field field, field2;
    private double[] v, v2;
    private long from, to;
    /** Bucket under the finger, or -1. */
    private int touch = -1;
    private final float d;

    WxGraph(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line2.set(line);
        line2.setStrokeWidth(1.5f * d);
        grid.setStrokeWidth(Math.max(1, d));
        small.setTextSize(10 * d);
        label.setTextSize(13 * d);
        big.setTextSize(16 * d);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        colours();
        setContentDescription("Weather graph");
    }

    private void colours() {
        line.setColor(Ui.accent);
        line2.setColor((Ui.accent & 0x00FFFFFF) | 0x73000000);
        dot.setColor(Ui.accent);
        grid.setColor(Ui.BORDER);
        small.setColor(Ui.muted);
        label.setColor(Ui.muted);
        big.setColor(Ui.text);
    }

    /**
     * @param v  one value per bucket across [from, to), NaN for none
     * @param v2 an optional second line (lighter), or null
     */
    void set(String title, String value, String sub, WxHistory.Field field, double[] v,
             WxHistory.Field field2, double[] v2, long from, long to) {
        this.title = title;
        this.value = value;
        this.sub = sub;
        this.field = field;
        this.field2 = field2;
        this.v = v;
        this.v2 = v2;
        this.from = from;
        this.to = to;
        touch = -1;
        colours();
        setContentDescription(title + " " + value + ". " + sub);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (150 * d));
    }

    // plot area
    private float left() { return 40 * d; }
    private float right() { return getWidth() - 6 * d; }
    private float top() { return 50 * d; }
    private float bottom() { return getHeight() - 20 * d; }

    @Override
    protected void onDraw(Canvas c) {
        if (v == null) return;
        int n = v.length;
        String head = value;
        String note = sub;
        if (touch >= 0 && touch < n) {
            long t = from + (long) ((to - from) * (touch + 0.5) / n);
            head = field.format(v[touch]);
            if (v2 != null && !Double.isNaN(v2[touch])) head += ", " + field2.label.toLowerCase(Locale.US) + " " + field2.format(v2[touch]);
            note = new SimpleDateFormat(to - from > 2 * 86_400_000L ? "EEE d MMM HH:mm" : "HH:mm", Locale.US).format(new Date(t));
        }
        c.drawText(title, 0, 16 * d, label);
        c.drawText(head, getWidth() - big.measureText(head), 18 * d, big);
        c.drawText(note, 0, 34 * d, small);

        double lo = Double.NaN, hi = Double.NaN;
        for (double[] a : new double[][]{v, v2}) {
            if (a == null) continue;
            for (double x : a) {
                if (Double.isNaN(x)) continue;
                if (Double.isNaN(lo) || x < lo) lo = x;
                if (Double.isNaN(hi) || x > hi) hi = x;
            }
        }
        float l = left(), r = right(), t = top(), b = bottom();
        if (Double.isNaN(lo)) {
            c.drawText("No reports in this time", l, (t + b) / 2, small);
            return;
        }
        double minSpan = minSpan(field);
        if (hi - lo < minSpan) { double m = (hi + lo) / 2; lo = m - minSpan / 2; hi = m + minSpan / 2; }
        if (field.peak || field == WxHistory.Field.WIND) lo = Math.max(0, lo);   // no negative wind or rain
        final double lo2 = lo, hi2 = hi;
        // three guide lines: low, middle, high
        for (int i = 0; i <= 2; i++) {
            float y = t + (b - t) * i / 2f;
            c.drawLine(l, y, r, y, grid);
            double val = hi2 - (hi2 - lo2) * i / 2.0;
            String s = axis(field, val);
            c.drawText(s, l - 4 * d - small.measureText(s), y + 4 * d, small);
        }
        drawTicks(c, l, r, b);
        if (v2 != null) drawLine(c, v2, lo2, hi2, line2, false);
        drawLine(c, v, lo2, hi2, line, true);
        if (touch >= 0 && touch < n) {
            float x = l + (r - l) * (touch + 0.5f) / n;
            c.drawLine(x, t - 4 * d, x, b, small);
            if (!Double.isNaN(v[touch])) c.drawCircle(x, y(v[touch], lo2, hi2), 4 * d, dot);
        }
    }

    /** The smallest range the graph shows, so tiny wobbles don't look like big swings. */
    private static double minSpan(WxHistory.Field f) {
        switch (f) {
            case PRESSURE: return 4;
            case RAIN_1H: case RAIN_24H: return 0.1;
            case HUMIDITY: return 10;
            default: return 5;
        }
    }

    private static String axis(WxHistory.Field f, double v) {
        if (f == WxHistory.Field.RAIN_1H || f == WxHistory.Field.RAIN_24H) return String.format(Locale.US, "%.2f", v);
        if (f == WxHistory.Field.PRESSURE) return String.format(Locale.US, "%.0f", v);
        return Long.toString(Math.round(v));
    }

    private float y(double val, double lo, double hi) {
        return (float) (bottom() - (bottom() - top()) * (val - lo) / (hi - lo));
    }

    /** Joins points across short gaps (a report every 30 minutes in 15-minute buckets); longer gaps stay gaps. */
    private void drawLine(Canvas c, double[] a, double lo, double hi, Paint p, boolean dots) {
        int n = a.length;
        float l = left(), w = right() - l;
        double bucketMs = (to - from) / (double) n;
        int bridge = (int) Math.max(1, Math.ceil(90 * 60_000.0 / bucketMs));
        path.reset();
        int last = -100, runStart = -1;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(a[i])) continue;
            float x = l + w * (i + 0.5f) / n, yy = y(a[i], lo, hi);
            if (i - last <= bridge) path.lineTo(x, yy);
            else {
                if (dots && runStart >= 0 && runStart == last) dot(c, a, last, lo, hi);   // a lone point
                path.moveTo(x, yy);
                runStart = i;
            }
            last = i;
        }
        if (dots && runStart >= 0 && runStart == last) dot(c, a, last, lo, hi);
        c.drawPath(path, p);
    }

    private void dot(Canvas c, double[] a, int i, double lo, double hi) {
        float x = left() + (right() - left()) * (i + 0.5f) / a.length;
        c.drawCircle(x, y(a[i], lo, hi), 2.5f * d, dot);
    }

    /** Time marks: every 6 hours for a day or so, daily for a week, weekly beyond. */
    private void drawTicks(Canvas c, float l, float r, float b) {
        long span = to - from;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(from);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int unit, step;
        SimpleDateFormat f;
        if (span <= 36 * 3_600_000L) {
            cal.set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY) / 6 * 6);
            unit = Calendar.HOUR_OF_DAY; step = 6; f = new SimpleDateFormat("HH:mm", Locale.US);
        } else if (span <= 8 * 86_400_000L) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            unit = Calendar.DAY_OF_MONTH; step = 1; f = new SimpleDateFormat("EEE", Locale.US);
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            unit = Calendar.DAY_OF_MONTH; step = 7; f = new SimpleDateFormat("d MMM", Locale.US);
        }
        float lastRight = -1;
        for (int guard = 0; guard < 60; guard++) {
            cal.add(unit, step);
            long t = cal.getTimeInMillis();
            if (t >= to) break;
            if (t <= from) continue;
            float x = l + (r - l) * (float) ((t - from) / (double) span);
            c.drawLine(x, b, x, b + 3 * d, grid);
            String s = f.format(new Date(t));
            float tw = small.measureText(s);
            float tx = Math.min(r - tw, Math.max(l, x - tw / 2));
            if (tx > lastRight + 6 * d) {
                c.drawText(s, tx, b + 14 * d, small);
                lastRight = tx + tw;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (v == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                point(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                // a sideways drag reads values; an up-down one is left to scroll the dialog
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (dx > 8 * d && dx > dy) getParent().requestDisallowInterceptTouchEvent(true);
                point(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                touch = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private float downX, downY;

    private void point(float x) {
        int i = (int) ((x - left()) / (right() - left()) * v.length);
        touch = Math.max(0, Math.min(v.length - 1, i));
        invalidate();
    }
}

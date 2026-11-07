package com.bh6emp.saspec;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public class PlotView extends View {
    public static final String[] UNITS = { "dBm", "dBμV", "dBmV", "Vrms", "Vpp", "W" };

    private double yTop = 10.0, yBot = -130.0;   // 纵轴可手动设置
    private static final float WF_FRACTION = 0.42f;   // 瀑布图占高度比例

    private double[] ys;
    private double f0 = 0, f1 = 0;
    private int unit = 0;
    private float markerPos = -1f;

    // 瀑布图
    private boolean wfOn = false;
    private Bitmap wfBmp;
    private int[] wfPx;
    private int wfW = 0, wfH = 0;
    private int[] lut;

    private final Paint pGrid = new Paint();
    private final Paint pText = new Paint();
    private final Paint pTrace = new Paint();
    private final Paint pMark = new Paint();
    private final Paint pMarkText = new Paint();
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Rect wfRect = new Rect();

    private float dens = 1f, sdens = 1f;
    private final String[] yLabels = new String[8];
    private int cacheUnit = -1;
    private double yBotR = -130;
    private String xLeft = "", xRight = "";
    private double cacheF0 = -1, cacheF1 = -1;

    public PlotView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        dens = d;
        sdens = sp;

        pGrid.setColor(0xFF1E5E5E);
        pGrid.setStrokeWidth(Math.max(1f, d * 0.8f));

        pText.setColor(0xFF9FE8E8);
        pText.setAntiAlias(true);
        pText.setTextSize(10 * sp);

        pTrace.setColor(0xFFFFD800);
        pTrace.setAntiAlias(true);
        pTrace.setStyle(Paint.Style.STROKE);
        pTrace.setStrokeWidth(Math.max(1.5f, d * 1.2f));
        pTrace.setStrokeJoin(Paint.Join.ROUND);

        pMark.setColor(0xFFFF4040);
        pMark.setStrokeWidth(Math.max(1f, d));
        pMark.setStyle(Paint.Style.STROKE);

        pMarkText.setColor(0xFFFFFFFF);
        pMarkText.setAntiAlias(true);
        pMarkText.setTextSize(11 * sp);

        buildLut();
        setBackgroundColor(Color.BLACK);
    }

    /** 256 级色标: 黑→蓝→青→绿→黄→红→白 */
    private void buildLut() {
        lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float t = i / 255f;
            int r, g, b;
            if (t < 0.18f) { r = 0; g = 0; b = (int) (t / 0.18f * 180); }
            else if (t < 0.38f) { r = 0; g = (int) ((t - 0.18f) / 0.20f * 255); b = 180; }
            else if (t < 0.58f) { r = 0; g = 255; b = (int) (180 * (1 - (t - 0.38f) / 0.20f)); }
            else if (t < 0.78f) { r = (int) ((t - 0.58f) / 0.20f * 255); g = 255; b = 0; }
            else if (t < 0.92f) { r = 255; g = (int) (255 * (1 - (t - 0.78f) / 0.14f)); b = 0; }
            else { r = 255; g = (int) (255 * ((t - 0.92f) / 0.08f)); b = g; }
            lut[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    public void setUnit(int u) { unit = u % UNITS.length; invalidate(); }

    public void setYRange(double top, double bot) {
        if (top <= bot) return;
        yTop = top;
        yBot = bot;
        cacheUnit = -1;          // 触发刻度重算
        invalidate();
    }

    /** 按频率定位标记; 超出当前扫频范围则忽略 */
    public boolean setMarkerHz(double hz) {
        if (f1 <= f0) return false;
        if (hz < f0 || hz > f1) return false;
        markerPos = (float) ((hz - f0) / (f1 - f0));
        invalidate();
        return true;
    }

    /** 当前标记频率; 未放置标记时返回中心频率 */
    public double getMarkerHz() {
        if (markerPos < 0) return (f0 + f1) / 2;
        return f0 + (f1 - f0) * markerPos;
    }

    public double getF0() { return f0; }
    public double getF1() { return f1; }

    public double getYTop() { return yTop; }
    public double getYBot() { return yBot; }

    public void setWaterfall(boolean on) {
        wfOn = on;
        if (!on) { wfBmp = null; wfPx = null; wfW = wfH = 0; }
        invalidate();
    }

    public boolean isWaterfall() { return wfOn; }

    public void setData(double[] data, double startHz, double stopHz) {
        ys = data;
        f0 = startHz;
        f1 = stopHz;
        pushWaterfallRow();
        invalidate();
    }

    /** 把当前这一帧压入瀑布图(最新在最上, 旧行下移) */
    private void pushWaterfallRow() {
        if (!wfOn || wfPx == null || ys == null || ys.length < 2) return;
        if (wfH > 1) System.arraycopy(wfPx, 0, wfPx, wfW, wfW * (wfH - 1));
        int n = ys.length;
        for (int x = 0; x < wfW; x++) {
            int i = (int) ((long) x * (n - 1) / Math.max(1, wfW - 1));
            double v = ys[i];
            double t = (v - yBotR) / (yTop - yBotR);
            int k = (int) (t * 255);
            if (k < 0) k = 0;
            if (k > 255) k = 255;
            wfPx[x] = lut[k];
        }
        wfBmp.setPixels(wfPx, 0, wfW, 0, 0, wfW, wfH);
    }

    private void ensureWf(int w, int h) {
        int nh = Math.max(8, (int) (h * WF_FRACTION));
        if (wfBmp != null && wfW == w && wfH == nh) return;
        wfW = w;
        wfH = nh;
        wfPx = new int[wfW * wfH];
        Arrays.fill(wfPx, 0xFF000000);
        wfBmp = Bitmap.createBitmap(wfW, wfH, Bitmap.Config.ARGB_8888);
        wfBmp.setPixels(wfPx, 0, wfW, 0, 0, wfW, wfH);
    }

    public void clearWaterfall() {
        if (wfPx != null) {
            Arrays.fill(wfPx, 0xFF000000);
            wfBmp.setPixels(wfPx, 0, wfW, 0, 0, wfW, wfH);
            invalidate();
        }
    }

    private double conv(double dbm) {
        switch (unit) {
            case 1: return dbm + 107.0;
            case 2: return dbm + 47.0;
            case 3: return Math.sqrt(50.0 * Math.pow(10.0, (dbm - 30.0) / 10.0));
            case 4: return Math.sqrt(50.0 * Math.pow(10.0, (dbm - 30.0) / 10.0)) * 2.828;
            case 5: return Math.pow(10.0, (dbm - 30.0) / 10.0);
            default: return dbm;
        }
    }

    private String fmt(double dbm) {
        double v = conv(dbm);
        switch (unit) {
            case 3:
            case 4:
                if (v >= 1) return String.format(Locale.US, "%.2f", v);
                if (v >= 0.001) return String.format(Locale.US, "%.1fm", v * 1000);
                return String.format(Locale.US, "%.0fu", v * 1e6);
            case 5:
                return String.format(Locale.US, "%.1e", v);
            default:
                return String.format(Locale.US, "%.0f", v);
        }
    }

    private float yOf(double dbm, float top, float h) {
        return top + (float) ((yTop - dbm) / (yTop - yBotR) * h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float lm = w * 0.10f, rm = w * 0.02f, tm = h * 0.10f, bm = h * 0.10f;
        float pw = w - lm - rm;

        // 瀑布图开启时压缩频谱区
        float wfHpx = wfOn ? h * WF_FRACTION : 0;
        float ph = h - tm - bm - wfHpx;

        float sp = sdens, d = dens;

        if (cacheUnit != unit) {
            cacheUnit = unit;
            double step = (yTop - yBot) / 7.0;
            // 取整到 1/2/5/10/20/50 之类的整数档, 便于读数
            double mag = Math.pow(10, Math.floor(Math.log10(Math.max(step, 1e-6))));
            double r = step / mag;
            double nice = (r <= 1) ? 1 : (r <= 2) ? 2 : (r <= 5) ? 5 : 10;
            step = nice * mag;
            yBotR = yTop - step * 7;
            for (int k = 0; k <= 7; k++) yLabels[k] = fmt(yTop - step * k);
        }
        if (cacheF0 != f0 || cacheF1 != f1) {
            cacheF0 = f0; cacheF1 = f1;
            xLeft = fmtFreq(f0);
            xRight = fmtFreq(f1);
        }

        pText.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 7; i++) {
            float y = tm + ph * i / 7f;
            c.drawLine(lm, y, lm + pw, y, pGrid);
            c.drawText(yLabels[i], lm - 4, y + 4, pText);
        }
        for (int i = 0; i <= 4; i++) {
            float x = lm + pw * i / 4f;
            c.drawLine(x, tm, x, tm + ph, pGrid);
        }
        pText.setTextAlign(Paint.Align.LEFT);
        c.drawText(xLeft, lm, tm + ph + 14 * sp, pText);
        pText.setTextAlign(Paint.Align.RIGHT);
        c.drawText(xRight, lm + pw, tm + ph + 14 * sp, pText);
        pText.setTextAlign(Paint.Align.RIGHT);
        c.drawText(UNITS[unit], lm + pw, tm - 4, pText);

        if (ys == null || ys.length < 2) {
            // 不画任何提示文字, 避免遮挡图谱; 连接状态由上方状态栏显示
            if (wfOn) drawWaterfall(c, w, h, tm + ph + 20 * sp);
            return;
        }

        int n = ys.length;
        path.reset();
        for (int i = 0; i < n; i++) {
            float x = lm + pw * i / (float) (n - 1);
            float y = yOf(ys[i], tm, ph);
            if (y < tm) y = tm;
            if (y > tm + ph) y = tm + ph;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, pTrace);

        if (markerPos >= 0f) {
            int mi = Math.round(markerPos * (n - 1));
            if (mi < 0) mi = 0;
            if (mi > n - 1) mi = n - 1;
            float mx = lm + pw * markerPos;
            float my = yOf(ys[mi], tm, ph);
            if (my < tm) my = tm;
            if (my > tm + ph) my = tm + ph;

            c.drawLine(mx, tm, mx, tm + ph, pMark);
            pMark.setStyle(Paint.Style.FILL);
            c.drawCircle(mx, my, 5 * d, pMark);
            pMark.setStyle(Paint.Style.STROKE);

            double hz = f0 + (f1 - f0) * markerPos;
            String info = fmtFreq(hz) + "  " + fmt(ys[mi]) + " " + UNITS[unit];
            pMarkText.setTextAlign(Paint.Align.LEFT);
            float tw = pMarkText.measureText(info);
            float tx = (mx + 10 + tw > w) ? mx - 10 - tw : mx + 10;
            c.drawText(info, Math.max(2, tx), tm + 14 * sp, pMarkText);
        }

        if (wfOn) drawWaterfall(c, w, h, tm + ph + 20 * sp);
    }

    private void drawWaterfall(Canvas c, int w, int h, float topY) {
        float lm = w * 0.10f, rm = w * 0.02f;
        float pw = w - lm - rm;
        float bottom = h - h * 0.02f;
        float wfh = bottom - topY;
        if (wfh < 8) return;

        ensureWf((int) pw, (int) wfh);
        wfRect.set((int) lm, (int) topY, (int) (lm + pw), (int) (topY + wfh));
        c.drawBitmap(wfBmp, null, wfRect, pBmp);

        pMark.setColor(0xFF3A6A6A);
        c.drawRect(wfRect, pMark);
        pMark.setColor(0xFFFF4040);
    }

    private String fmtFreq(double hz) {
        if (hz >= 1e9) return String.format(Locale.US, "%.4f GHz", hz / 1e9);
        if (hz >= 1e6) return String.format(Locale.US, "%.4f MHz", hz / 1e6);
        if (hz >= 1e3) return String.format(Locale.US, "%.3f kHz", hz / 1e3);
        return String.format(Locale.US, "%.0f Hz", hz);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (ys == null || ys.length < 2) return true;
        int w = getWidth(), h = getHeight();
        float lm = w * 0.10f, rm = w * 0.02f;
        float pw = w - lm - rm;

        // 只在上半部(频谱区)才响应标记拖动
        float tm = h * 0.10f;
        float ph = h - tm - h * 0.10f - (wfOn ? h * WF_FRACTION : 0);
        if (e.getY() > tm + ph + 10 * dens) return true;

        float rel = (e.getX() - lm) / pw;
        if (rel < 0) rel = 0;
        if (rel > 1) rel = 1;
        markerPos = rel;
        invalidate();
        return true;
    }
}

package com.bh6emp.saspec;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends Activity {
    static final int VID = 0x0483, PID = 0x5740;
    static final int W = 320, H = 240, FRAME = W * H * 2;
    static final String ACT = "com.bh6emp.saspec.USB_PERMISSION";
    

    UsbManager um;
    UsbDevice dev;
    UsbDeviceConnection conn;
    UsbEndpoint epIn, epOut;
    PlotView plot;
    TextView status;
    Button btnConn, btnAvg, btnSet;
    volatile boolean running = false;
    Thread worker;
    String freqLo = "", freqHi = "";
    String calcFreq = "145.000";
    volatile boolean needFreq = false;
    double fStart = 1e3, fStop = 350e6;
    int traceSel = 0;
    volatile boolean pausedFlag = false;
    volatile boolean usbOk = false;      // USB 链路是否可用
    volatile boolean quiesce = false;
    volatile boolean synced = false;   // 是否已从设备读取过设置
    int ioTimeouts = 0;          // 连续超时计数, 用于判定链路失步
    volatile int pollMs = 900;   // 轮询周期, 探测到设备扫描时间后自适应
    int stableN = 0, pendingN = 0, pendingCnt = 0;   // 点数稳定过滤
    double devMaxF = 950e6;    // 使用上限(Basic 950M, 固件理论值 959M / Ultra 1130M)
    int devMaxPts = 290;       // 仪器最大点数
    boolean ultra = false;
    boolean deviceHigh = false;   // 当前是否处于高波段(240-959M)
    final Object io = new Object();
    // 命令统一走后台单线程, 避免阻塞 UI(断开时会 join 数据线程)
    final java.util.concurrent.ExecutorService ioExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();   // USB 收发互斥: 后台读数据与菜单下发命令不能并发
    FrameLayout menuLayer;
    LinearLayout menuBox;
    TextView menuTitle;
    TextView menuStatus;   // 面板内即时反馈(状态栏被面板遮挡)
    final java.util.ArrayList<Integer> menuStack = new java.util.ArrayList<Integer>();
    int traceMode = 0;              // 0=实时 1=平均4 2=平均16 3=峰值保持
    double[] accPow = null, peakDb = null;
    int accCnt = 0;
    final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACT.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openDev();
                else setStatus("USB 权限被拒绝");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                setStatus("设备已拔出");
                closeDev();
            }
        }
    };

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        um = (UsbManager) getSystemService(Context.USB_SERVICE);


        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        plot = new PlotView(this);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(6));
        status.setText("未连接");

        // 状态栏与图表上下排布, 不再互相覆盖
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(status, new LinearLayout.LayoutParams(-1, -2));
        column.addView(plot, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        btnConn = new Button(this);
        btnConn.setText("连接");
        btnConn.setAlpha(0.85f);
        btnConn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (conn == null) connect(); else closeDev(); }
        });
        bar.addView(btnConn, new LinearLayout.LayoutParams((int) dp(110), (int) dp(34)));

        btnAvg = new Button(this);
        btnAvg.setText("实时");
        btnAvg.setAlpha(0.85f);
        btnAvg.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                traceMode = (traceMode + 1) % 4;
                resetAcc();
                String[] nm = { "实时", "平均4", "平均16", "峰值" };
                btnAvg.setText(nm[traceMode]);
                setStatus("迹线处理：" + nm[traceMode]);
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int) dp(110), (int) dp(34));
        alp.leftMargin = (int) dp(10);
        bar.addView(btnAvg, alp);

        btnSet = new Button(this);
        btnSet.setText("菜单");
        btnSet.setAlpha(0.85f);
        btnSet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(0); if (conn == null) setStatus("未连接：菜单可查看，命令不会下发"); }
        });
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams((int) dp(110), (int) dp(34));
        slp2.leftMargin = (int) dp(10);
        bar.addView(btnSet, slp2);

        // 按钮改为独立底栏, 不再浮在图表上
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = (int) dp(4);
        blp.bottomMargin = (int) dp(6);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(bar, blp);

        buildMenuLayer(root);
        setContentView(root);

        IntentFilter f = new IntentFilter(ACT);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(rx, f);
    }


    EditText field(LinearLayout ll, String hint, String def) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(def);
        e.setTextSize(14);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        ll.addView(e);
        return e;
    }

    void showCalc() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding((int) dp(18), (int) dp(8), (int) dp(18), (int) dp(8));

        final EditText ef = field(ll, "频率 MHz", calcFreq);
        final EditText ev = field(ll, "缩短系数 (0.66~1.0)", "0.95");
        final EditText ed = field(ll, "电平 dBm", "0");
        final EditText ea = field(ll, "天线系数 dB/m", "0");

        final TextView out = new TextView(this);
        out.setTextColor(0xFF9FE8E8);
        out.setTextSize(14);
        out.setPadding(0, (int) dp(10), 0, 0);
        ll.addView(out);

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);

        final Runnable calc = new Runnable() {
            public void run() {
                double f, vf, dbm, af;
                try { f = Double.parseDouble(ef.getText().toString()); } catch (Exception e) { f = 0; }
                try { vf = Double.parseDouble(ev.getText().toString()); } catch (Exception e) { vf = 0.95; }
                try { dbm = Double.parseDouble(ed.getText().toString()); } catch (Exception e) { dbm = 0; }
                try { af = Double.parseDouble(ea.getText().toString()); } catch (Exception e) { af = 0; }

                StringBuilder sb = new StringBuilder();
                if (f > 0) {
                    double lam = 299.792458 / f;
                    sb.append(String.format("波长 λ      %.4f m%n", lam));
                    sb.append(String.format("λ/2        %.4f m%n", lam / 2));
                    sb.append(String.format("λ/4        %.4f m%n", lam / 4));
                    sb.append(String.format("λ/8        %.4f m%n", lam / 8));
                    sb.append(String.format("λ/4 ×%.2f  %.4f m%n", vf, lam / 4 * vf));
                    sb.append(String.format("5λ/8 ×%.2f %.4f m%n", vf, lam * 0.625 * vf));
                } else {
                    sb.append("请输入频率\n");
                }
                sb.append("\n");
                double pw = Math.pow(10.0, (dbm - 30.0) / 10.0);
                double vrms = Math.sqrt(pw * 50.0);
                sb.append(String.format("%+.1f dBm = %.3e W%n", dbm, pw));
                sb.append(String.format("50Ω 电压   %.4f Vrms / %.4f Vpp%n", vrms, vrms * 2.828));
                sb.append(String.format("等效       %.1f dBμV%n", dbm + 107.0));
                sb.append(String.format("场强       %.1f dBμV/m%n", dbm + 107.0 + af));
                out.setText(sb.toString());
            }
        };

        android.text.TextWatcher w = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { calc.run(); }
        };
        ef.addTextChangedListener(w);
        ev.addTextChangedListener(w);
        ed.addTextChangedListener(w);
        ea.addTextChangedListener(w);
        calc.run();

        new android.app.AlertDialog.Builder(this)
                .setTitle("辅助计算")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ==== 参数面板: 按手机交互重新设计, 不照搬设备菜单 ====
    static final String[] RBW_LIST = { "auto", "3", "10", "30", "100", "300", "600" };
    static final String[] TIME_LIST = { "0.01", "0.03", "0.1", "0.3", "1", "3" };
    static final String[] ATT_LIST = { "auto", "0", "5", "10", "15", "20", "25", "30" };
    static final String[] PTS_LIST = { "100", "145", "200", "290", "450" };
    String[] ptsList = { "100", "145", "200", "290" };   // 按机型上限裁剪
    int iRbw = 0, iTime = 2, iAtt = 0, iPts = 3;
    int iUnit = 0;
    boolean wfOn = false;

    void buildMenuLayer(FrameLayout root) {
        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0xF0101010);   // 全屏实底
        menuLayer.setVisibility(View.GONE);
        menuLayer.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));

        android.graphics.drawable.GradientDrawable card =
                new android.graphics.drawable.GradientDrawable();
        card.setColor(0xF0181818);
        card.setCornerRadius(dp(14));
        card.setStroke((int) Math.max(1, dp(1)), 0xFF3A6A6A);
        box.setBackground(card);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        menuTitle = new TextView(this);
        menuTitle.setTextColor(0xFFFFFFFF);
        menuTitle.setTextSize(15);
        titleRow.addView(menuTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        Button closeTop = new Button(this);
        closeTop.setText("关闭");
        closeTop.setAllCaps(false);
        closeTop.setTextSize(13);
        closeTop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePanel(); }
        });
        titleRow.addView(closeTop, new LinearLayout.LayoutParams((int) dp(64), (int) dp(32)));

        box.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        menuStatus = new TextView(this);
        menuStatus.setTextColor(0xFF9FE8E8);
        menuStatus.setTextSize(11);
        menuStatus.setPadding(0, (int) dp(2), 0, (int) dp(2));
        menuStatus.setVisibility(View.GONE);      // 无消息时不占位
        box.addView(menuStatus);

        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        sv.addView(menuBox, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svlp.topMargin = (int) dp(6);
        box.addView(sv, svlp);

        // 参数面板占满全屏
        menuLayer.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.addView(menuLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    void openPanel() {
        if (menuLayer == null) return;
        menuLayer.setVisibility(View.VISIBLE);
        menuLayer.setAlpha(0f);
        menuLayer.setScaleX(0.96f);
        menuLayer.setScaleY(0.96f);
        menuLayer.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        menuLayer.bringToFront();
    }

    void closePanel() {
        if (menuLayer == null || menuLayer.getVisibility() != View.VISIBLE) return;
        menuLayer.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(130).setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() { menuLayer.setVisibility(View.GONE); }
                }).start();
    }

    void showMenu(int id) {
        if (menuLayer == null) return;
        menuTitle.setText("参数");
        panelMsg("");
        menuTitle.setOnClickListener(null);
        renderPanel();
        openPanel();
    }

    Button mkBtn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    LinearLayout mkRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    TextView mkLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(0xFF9FE8E8);
        v.setTextSize(11);
        v.setPadding(0, (int) dp(10), 0, (int) dp(2));
        return v;
    }

    void renderPanel() {
        menuBox.removeAllViews();

        // 低波段 / 高波段 分组显示
        menuBox.addView(mkLabel("LOW 口   0 – 350M"));
        addRangeRow(new double[][]{ { 1e3, 100e6 }, { 100e6, 350e6 } });

        menuBox.addView(mkLabel("HIGH 口  240 – 950M"));
        addRangeRow(new double[][]{ { 350e6, 500e6 }, { 500e6, 950e6 } });

        menuBox.addView(mkLabel("自定义频率范围 (MHz)"));
        LinearLayout r3 = mkRow();
        final EditText e1 = new EditText(this);
        e1.setText(String.format("%.3f", fStart / 1e6));
        e1.setTextSize(13);
        e1.setTextColor(0xFFFFFFFF);
        e1.setBackgroundColor(0x33FFFFFF);
        e1.setPadding((int) dp(8), 0, (int) dp(8), 0);
        e1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText e2 = new EditText(this);
        e2.setText(String.format("%.3f", fStop / 1e6));
        e2.setTextSize(13);
        e2.setTextColor(0xFFFFFFFF);
        e2.setBackgroundColor(0x33FFFFFF);
        e2.setPadding((int) dp(8), 0, (int) dp(8), 0);
        e2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
        w1.rightMargin = (int) dp(6);
        r3.addView(e1, w1);
        TextView tilde = new TextView(this);
        tilde.setText("~");
        tilde.setTextColor(0xFFFFFFFF);
        r3.addView(tilde);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
        w2.leftMargin = (int) dp(6);
        w2.rightMargin = (int) dp(6);
        r3.addView(e2, w2);
        Button apply = mkBtn("应用", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    double a = Double.parseDouble(e1.getText().toString().trim()) * 1e6;
                    double b = Double.parseDouble(e2.getText().toString().trim()) * 1e6;
                    applyRange(a, b);
                } catch (Exception ex) { panelMsg("频率输入无效"); }
            }
        });
        r3.addView(apply, new LinearLayout.LayoutParams((int) dp(80), (int) dp(36)));
        menuBox.addView(r3);

        menuBox.addView(mkLabel("扫描设置"));
        LinearLayout r4 = mkRow();
        Button bRbw = mkBtn("带宽 " + RBW_LIST[iRbw], new View.OnClickListener() {
            public void onClick(View v) {
                iRbw = (iRbw + 1) % RBW_LIST.length;
                ((Button) v).setText("带宽 " + RBW_LIST[iRbw]);
                safeCmdAsync("rbw " + RBW_LIST[iRbw], "带宽 = " + RBW_LIST[iRbw]);
            }
        });
        Button bTime = mkBtn("时间 " + TIME_LIST[iTime] + "s", new View.OnClickListener() {
            public void onClick(View v) {
                iTime = (iTime + 1) % TIME_LIST.length;
                ((Button) v).setText("时间 " + TIME_LIST[iTime] + "s");
                safeCmdAsync("sweeptime " + TIME_LIST[iTime], "扫描时间 = " + TIME_LIST[iTime] + "s");
                ioExec.execute(new Runnable() { public void run() { sleep(400); readSweepTime(); } });
            }
        });
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, (int) dp(34), 1f);
        h1.rightMargin = (int) dp(6);
        r4.addView(bRbw, h1);
        r4.addView(bTime, new LinearLayout.LayoutParams(0, (int) dp(34), 1f));
        menuBox.addView(r4);

        LinearLayout r5 = mkRow();
        Button bAtt = mkBtn("衰减 " + ATT_LIST[iAtt], new View.OnClickListener() {
            public void onClick(View v) {
                iAtt = (iAtt + 1) % ATT_LIST.length;
                ((Button) v).setText("衰减 " + ATT_LIST[iAtt]);
                safeCmdAsync("attenuate " + ATT_LIST[iAtt], "衰减 = " + ATT_LIST[iAtt]);
            }
        });
        Button bPts = mkBtn("点数 " + ptsList[iPts % ptsList.length], new View.OnClickListener() {
            public void onClick(View v) {
                iPts = (iPts + 1) % ptsList.length;
                int np = Integer.parseInt(ptsList[iPts]);
                ((Button) v).setText("点数 " + np);
                if (!usbOk) { panelMsg("未连接仪器"); return; }
                final int fpts = np;
                safeCmdAsync(String.format(java.util.Locale.US, "sweep %.0f %.0f %d", fStart, fStop, fpts),
                        "点数 = " + fpts);
                needFreq = true; resetAcc();
            }
        });
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, (int) dp(34), 1f);
        h2.rightMargin = (int) dp(6);
        r5.addView(bAtt, h2);
        r5.addView(bPts, new LinearLayout.LayoutParams(0, (int) dp(34), 1f));
        menuBox.addView(r5);

        menuBox.addView(mkLabel("纵轴范围 (dBm)"));
        LinearLayout rY = mkRow();
        final EditText ey1 = new EditText(this);
        ey1.setText(String.format(java.util.Locale.US, "%.0f", plot.getYTop()));
        ey1.setTextSize(13);
        ey1.setTextColor(0xFFFFFFFF);
        ey1.setBackgroundColor(0x33FFFFFF);
        ey1.setPadding((int) dp(8), 0, (int) dp(8), 0);
        ey1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        final EditText ey2 = new EditText(this);
        ey2.setText(String.format(java.util.Locale.US, "%.0f", plot.getYBot()));
        ey2.setTextSize(13);
        ey2.setTextColor(0xFFFFFFFF);
        ey2.setBackgroundColor(0x33FFFFFF);
        ey2.setPadding((int) dp(8), 0, (int) dp(8), 0);
        ey2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout.LayoutParams yl1 = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
        yl1.rightMargin = (int) dp(6);
        rY.addView(ey1, yl1);
        TextView tilde2 = new TextView(this);
        tilde2.setText("~");
        tilde2.setTextColor(0xFFFFFFFF);
        rY.addView(tilde2);
        LinearLayout.LayoutParams yl2 = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
        yl2.leftMargin = (int) dp(6);
        yl2.rightMargin = (int) dp(6);
        rY.addView(ey2, yl2);
        Button applyY = mkBtn("应用", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    double top = Double.parseDouble(ey1.getText().toString().trim());
                    double bot = Double.parseDouble(ey2.getText().toString().trim());
                    if (top <= bot) { panelMsg("上沿必须大于下沿"); return; }
                    plot.setYRange(top, bot);
                    panelMsg(String.format(java.util.Locale.US, "纵轴: %.0f ~ %.0f dBm", top, bot));
                } catch (Exception ex) { panelMsg("纵轴输入无效"); }
            }
        });
        rY.addView(applyY, new LinearLayout.LayoutParams((int) dp(72), (int) dp(36)));
        menuBox.addView(rY);

        menuBox.addView(mkLabel("标记频率 (MHz)"));
        LinearLayout rM = mkRow();
        final EditText em = new EditText(this);
        em.setText(String.format(java.util.Locale.US, "%.4f", plot.getMarkerHz() / 1e6));
        em.setTextSize(13);
        em.setTextColor(0xFFFFFFFF);
        em.setBackgroundColor(0x33FFFFFF);
        em.setPadding((int) dp(8), 0, (int) dp(8), 0);
        em.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams ml1 = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
        ml1.rightMargin = (int) dp(6);
        rM.addView(em, ml1);
        Button applyM = mkBtn("定位", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    double hz = Double.parseDouble(em.getText().toString().trim()) * 1e6;
                    if (plot.setMarkerHz(hz)) {
                        panelMsg(String.format(java.util.Locale.US, "标记移到 %.4f MHz", hz / 1e6));
                    } else {
                        panelMsg(String.format(java.util.Locale.US, "%.4f MHz 不在 %.4f–%.4f 范围内",
                                hz / 1e6, plot.getF0() / 1e6, plot.getF1() / 1e6));
                    }
                } catch (Exception ex) { panelMsg("频率输入无效"); }
            }
        });
        rM.addView(applyM, new LinearLayout.LayoutParams((int) dp(72), (int) dp(36)));
        menuBox.addView(rM);

        menuBox.addView(mkLabel("显示单位"));
        Button bUnit = mkBtn("单位 " + PlotView.UNITS[iUnit], new View.OnClickListener() {
            public void onClick(View v) {
                iUnit = (iUnit + 1) % PlotView.UNITS.length;
                ((Button) v).setText("单位 " + PlotView.UNITS[iUnit]);
                plot.setUnit(iUnit);
                panelMsg("显示单位: " + PlotView.UNITS[iUnit]);
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, (int) dp(34));
        ulp.topMargin = (int) dp(6);
        menuBox.addView(bUnit, ulp);

        Button bWf = mkBtn(wfOn ? "瀑布图: 开" : "瀑布图: 关", new View.OnClickListener() {
            public void onClick(View v) {
                wfOn = !wfOn;
                ((Button) v).setText(wfOn ? "瀑布图: 开" : "瀑布图: 关");
                plot.setWaterfall(wfOn);
                panelMsg(wfOn ? "瀑布图已开启" : "瀑布图已关闭");
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, (int) dp(34));
        wlp.topMargin = (int) dp(6);
        menuBox.addView(bWf, wlp);


    }

    void panelMsg(String s) {
        if (menuStatus != null) {
            if (s == null || s.length() == 0) {
                menuStatus.setVisibility(View.GONE);
            } else {
                menuStatus.setText(s);
                menuStatus.setVisibility(View.VISIBLE);
            }
        }
        setStatus(s);
    }

    void addRangeRow(final double[][] ranges) {
        LinearLayout row = mkRow();
        for (int i = 0; i < ranges.length; i++) {
            final double ra = ranges[i][0], rb = ranges[i][1];
            Button b = mkBtn(rangeLabel(ra, rb), new View.OnClickListener() {
                public void onClick(View v) { applyRange(ra, rb); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1f);
            lp.rightMargin = (int) dp(6);
            row.addView(b, lp);
        }
        menuBox.addView(row);
    }

    String rangeLabel(double a, double b) {
        return hzShort(a) + " – " + hzShort(b);
    }

    String hzShort(double hz) {
        if (hz >= 1e6) return String.format(java.util.Locale.US, "%.0fM", hz / 1e6);
        if (hz >= 1e3) return String.format(java.util.Locale.US, "%.0fk", hz / 1e3);
        return String.format(java.util.Locale.US, "%.0f", hz);
    }

    void applyRange(double a0, double b0) {
        if (!usbOk) { panelMsg("未连接仪器"); return; }
        if (b0 <= a0) { panelMsg("终止频率必须大于起始频率"); return; }
        double a = a0 < 0 ? 0 : a0;
        final double b = b0;
        if (b > devMaxF) {
            panelMsg(String.format(java.util.Locale.US,
                    "终止频率 %.3f MHz 超出上限 %.0f MHz", b / 1e6, devMaxF / 1e6));
            return;
        }
        int pts = Integer.parseInt(ptsList[iPts % ptsList.length]);
        if (pts > devMaxPts) pts = devMaxPts;

        // <=350M 走 LOW 口, 超过 350M 走 HIGH 口; 跨两口的范围单次扫不了
        final boolean wantHigh = b > 350e6;
        if (wantHigh && a < 240e6) {
            panelMsg(String.format(java.util.Locale.US,
                    "%.0f–%.0f MHz 跨两段, 请分开扫 (0–350 用 LOW, 240–950 用 HIGH)",
                    a / 1e6, b / 1e6));
            return;
        }
        final double fa = a;
        final int fpts = pts;

        panelMsg("正在应用…");
        ioExec.execute(new Runnable() {
            public void run() {
                quiesce = true;
                try {
                    synchronized (io) {
                        if (wantHigh != deviceHigh) {
                            cmdResp(wantHigh ? "mode high input" : "mode low input");
                            deviceHigh = wantHigh;
                        }
                        cmdResp(String.format(java.util.Locale.US, "sweep %.0f %.0f %d", fa, b, fpts));
                    }
                } catch (Throwable th) {
                    usbOk = false;
                } finally {
                    quiesce = false;
                }
                pausedFlag = false;
                needFreq = true;
                resetAcc();
                sleep(120);
                readSweepTime();          // 新范围可能改变扫描时间
                final String msg = String.format(java.util.Locale.US, "%.3f – %.3f MHz · %d 点 · %s",
                        fa / 1e6, b / 1e6, fpts, wantHigh ? "HIGH 口" : "LOW 口");
                ui.post(new Runnable() {
                    public void run() {
                        setStatus(msg);
                        if (menuLayer != null) closePanel();
                    }
                });
            }
        });
    }

    boolean ensureRunning() {
        synchronized (io) {
            String st = cmdResp("status");
            if (st.contains("Paused")) {
                cmd("resume");
                return true;
            }
            return false;
        }
    }

    // 安全下发(异步): 后台线程执行, 结果回传到面板
    void safeCmdAsync(final String c, final String label) {
        if (!usbOk) { panelMsg("未连接仪器"); return; }
        panelMsg(label + " …");
        ioExec.execute(new Runnable() {
            public void run() {
                quiesce = true;
                String r = "";
                try {
                    synchronized (io) { r = cmdResp(c); }
                } catch (Throwable th) {
                    usbOk = false;
                } finally {
                    quiesce = false;
                }
                final String msg = label + "  " + shortReply(r);
                ui.post(new Runnable() {
                    public void run() { panelMsg(msg); }
                });
            }
        });
    }

    String shortReply(String s) {
        if (s == null || s.length() == 0) return "";
        String[] ls = s.split("[\r\n]+");
        String last = "";
        for (String x : ls) if (x.trim().length() > 0 && !x.trim().startsWith("ch>")) last = x.trim();
        if (last.length() > 40) last = last.substring(0, 40);
        return last;
    }

    // 向设备问当前扫描时间, 用来自适应轮询节奏, 避免抢占扫描线程
    // 从设备读取当前设置, 让面板与仪器一致
    void syncFromDevice() {
        try {
            // 频率范围与点数: sweep 无参返回 "起始 终止 点数"
            String sw = cmdResp("sweep");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d+)\\s+(\\d+)\\s+(\\d+)").matcher(sw);
            if (m.find()) {
                fStart = Double.parseDouble(m.group(1));
                fStop = Double.parseDouble(m.group(2));
                freqLo = fmtHz(fStart);
                freqHi = fmtHz(fStop);
                int pts = Integer.parseInt(m.group(3));
                iPts = nearestIdx(ptsList, pts);
                deviceHigh = fStop > 350e6;
            }
            // 带宽: 最后一行形如 "10.0Hz"
            double v = lastNumber(cmdResp("rbw"), 1.0);
            if (v > 0) iRbw = nearestIdx(RBW_LIST, v);
            // 衰减: 最后一行形如 " 2.00"
            double a = lastNumber(cmdResp("attenuate"), 1.0);
            if (a >= 0) iAtt = nearestIdx(ATT_LIST, a);
            // 扫描时间
            String st = cmdResp("sweeptime");
            java.util.regex.Matcher mt = java.util.regex.Pattern
                    .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s)").matcher(st);
            if (mt.find()) {
                double sec = Double.parseDouble(mt.group(1));
                if ("ms".equals(mt.group(2))) sec /= 1000.0;
                iTime = nearestIdx(TIME_LIST, sec);
                pollMs = Math.max(150, Math.min(3000, (int) Math.round(sec * 1000) + 120));
            }
            synced = true;
        } catch (Throwable th) { }
    }

    // 取响应最后一行里的数字(可带单位)
    double lastNumber(String resp, double dflt) {
        String[] ls = resp.split("[\\r\\n]+");
        for (int i = ls.length - 1; i >= 0; i--) {
            String s = ls[i].trim();
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);
            if (m.find() && !s.startsWith("usage") && !s.contains("ch>")) {
                try { return Double.parseDouble(m.group(1)); } catch (Exception e) { }
            }
        }
        return dflt;
    }

    int nearestIdx(String[] list, double v) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < list.length; i++) {
            double x;
            try { x = Double.parseDouble(list[i]); } catch (Exception e) { continue; }
            double d = Math.abs(x - v);
            if (d < bd) { bd = d; best = i; }
        }
        return best < 0 ? 0 : best;
    }

    void readSweepTime() {
        // 固件返回形如 " 904ms" 或 "1.234s"
        String r = cmdResp("sweeptime");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s)").matcher(r);
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if ("s".equals(m.group(2))) v *= 1000;
                int ms = (int) Math.round(v) + 120;      // 留 120ms 余量
                pollMs = Math.max(150, Math.min(3000, ms));
            } catch (Exception e) { }
        }
    }

    void readFreqTable() {
        ArrayList<Double> fr = numbers(readResponse("frequencies\r", 2500));
        if (fr.size() > 1) {
            fStart = fr.get(0);
            fStop = fr.get(fr.size() - 1);
            freqLo = fmtHz(fStart);
            freqHi = fmtHz(fStop);
            calcFreq = String.format("%.3f", (fStart + fStop) / 2.0 / 1e6);
        }
    }

    void cmd(String c) { synchronized (io) { send(c + "\r"); } sleep(40); }

    // 下发命令并取回设备回显(用于判断命令是否被接受)
    String cmdResp(String c) {
        String r = readResponse(c + "\r", 900);
        String s = r.trim();
        int i = s.lastIndexOf("ch>");
        if (i > 0) s = s.substring(0, i).trim();
        return s;
    }

    void immersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override protected void onDestroy() {
        running = false;
        try { unregisterReceiver(rx); } catch (Exception e) {}
        closeDev();
        super.onDestroy();
    }

    void setStatus(final String s) {
        ui.post(new Runnable() { public void run() { status.setText(s); } });
    }

    void sleep(int ms) { try { Thread.sleep(ms); } catch (Exception e) {} }

    UsbDevice find() {
        HashMap<String, UsbDevice> list = um.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    void connect() {
        dev = find();
        if (dev == null) { setStatus("未找到仪器，请检查 OTG 连接"); return; }
        if (um.hasPermission(dev)) { openDev(); return; }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACT).setPackage(getPackageName()), PendingIntent.FLAG_MUTABLE);
        um.requestPermission(dev, pi);
        setStatus("等待 USB 授权…");
    }

    void openDev() {
        if (dev == null) return;
        conn = um.openDevice(dev);
        if (conn == null) { setStatus("打开设备失败"); return; }
        UsbInterface data = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            UsbEndpoint in = null, out = null;
            for (int k = 0; k < itf.getEndpointCount(); k++) {
                UsbEndpoint e = itf.getEndpoint(k);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
                }
            }
            if (in != null && out != null) { data = itf; epIn = in; epOut = out; break; }
        }
        if (data == null) { setStatus("找不到可用端点"); closeDev(); return; }
        if (!conn.claimInterface(data, true)) { setStatus("无法占用接口"); closeDev(); return; }

        byte[] line = new byte[]{ 0x00, (byte) 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08 };
        conn.controlTransfer(0x21, 0x20, 0, data.getId(), line, line.length, 1000);
        conn.controlTransfer(0x21, 0x22, 0x03, data.getId(), null, 0, 1000);

        usbOk = true;
        immersive(true);
        btnConn.setText("断开");
        setStatus("已连接 · 读取频率表…");
        running = true;
        worker = new Thread(new Runnable() { public void run() { loop(); } });
        worker.start();
    }

    void closeDev() {
        running = false;
        quiesce = true;
        final Thread t = worker;
        worker = null;
        ioExec.execute(new Runnable() {          // 后台 join + 关闭, 不阻塞 UI
            public void run() {
                if (t != null) {
                    try { t.join(2500); } catch (Throwable e) { }
                }
                synchronized (io) {
                    usbOk = false;
                    UsbDeviceConnection c = conn;
                    conn = null;
                    if (c != null) { try { c.close(); } catch (Throwable e) { } }
                }
                quiesce = false;
                ui.post(new Runnable() {
                    public void run() {
                        immersive(false);
                        btnConn.setText("连接");
                        setStatus("已断开");
                    }
                });
            }
        });
    }

    // 清空接收缓冲: 保证下一条命令的响应不会与残留数据混淆
    void drainInput() {
        byte[] buf = new byte[4096];
        long last = System.currentTimeMillis();
        long limit = last + 500;
        while (System.currentTimeMillis() < limit && usbOk) {
            UsbDeviceConnection c = conn;
            if (c == null || epIn == null) return;
            int n;
            try {
                n = c.bulkTransfer(epIn, buf, buf.length, 40);
            } catch (Throwable th) {
                usbOk = false;
                return;
            }
            if (n > 0) last = System.currentTimeMillis();
            else if (System.currentTimeMillis() - last > 60) return;   // 安静 60ms 视为已清空
        }
    }

    String readResponse(String cmd, int timeoutMs) {
      synchronized (io) {
        if (!usbOk) return "";
        drainInput();                      // 先排空, 避免读到上一条的残留
        if (!send(cmd)) return "";
        StringBuilder sb = new StringBuilder(4096);
        byte[] buf = new byte[8192];
        boolean gotPrompt = false;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            UsbDeviceConnection c = conn;
            if (!usbOk || c == null || epIn == null) break;
            int n;
            try {
                n = c.bulkTransfer(epIn, buf, buf.length, 80);
            } catch (Throwable th) {
                usbOk = false;
                break;
            }
            if (n > 0) sb.append(new String(buf, 0, n));
            if (sb.indexOf("ch>") >= 0) { gotPrompt = true; break; }
        }
        if (!gotPrompt) {
            if (++ioTimeouts > 4) {        // 连续失步: 停止轮询, 提示重连
                usbOk = false;
                ioTimeouts = 0;
            }
        } else {
            ioTimeouts = 0;
        }
        return sb.toString();
      }
    }

    static ArrayList<Double> numbers(String s) {
        ArrayList<Double> out = new ArrayList<Double>(512);
        int i = 0, len = s.length();
        while (i < len) {
            while (i < len) {
                char c = s.charAt(i);
                if (c == '\r' || c == '\n' || c == ' ') i++; else break;
            }
            int start = i;
            while (i < len) {
                char c = s.charAt(i);
                if (c == '\r' || c == '\n') break;
                i++;
            }
            if (i > start) {
                char c0 = s.charAt(start);
                if (c0 == '-' || c0 == '+' || c0 == '.' || (c0 >= '0' && c0 <= '9')) {
                    try { out.add(Double.parseDouble(s.substring(start, i).trim())); }
                    catch (Exception e) { }
                }
            }
        }
        return out;
    }

    String fmtHz(double hz) {
        if (hz >= 1e9) return String.format("%.3f GHz", hz / 1e9);
        if (hz >= 1e6) return String.format("%.3f MHz", hz / 1e6);
        if (hz >= 1e3) return String.format("%.3f kHz", hz / 1e3);
        return String.format("%.0f Hz", hz);
    }


    void resetAcc() { accPow = null; peakDb = null; accCnt = 0; }

    void loop() {
        readFreqTable();

        // 识别机型, 决定频率上限与最大点数
        sleep(60);
        String ver = cmdResp("version");
        ultra = ver.contains("tinySA4");
        devMaxF = ultra ? 1130e6 : 950e6;
        devMaxPts = ultra ? 450 : 290;
        deviceHigh = fStop > 350e6;
        sleep(60);
        syncFromDevice();          // 读取设备当前设置, 面板与仪器保持一致
        ptsList = ultra ? new String[]{ "100", "145", "200", "450" }
                        : new String[]{ "100", "145", "200", "290" };

        // 帧周期由设备扫描时间决定(见 readSweepTime)
        int fps = 0;
        long t0 = System.currentTimeMillis();
        long lastBeat = t0, lastGood = t0;

        while (running) {
            long frameStart = System.currentTimeMillis();
            if (quiesce) { sleep(20); continue; }
            if (needFreq) { needFreq = false; readFreqTable(); }

            // 心跳只在"长时间收不到数据"时触发, 平时不占 USB 总线
            if (frameStart - lastGood > 3000 && frameStart - lastBeat > 8000) {
                lastBeat = frameStart;
                pausedFlag = ensureRunning();
            }

            ArrayList<Double> vals;
            try {
                vals = numbers(readResponse("data " + traceSel + "\r", 1200));
            } catch (Throwable th) {
                usbOk = false;
                break;
            }
            if (!usbOk) break;
            if (vals.size() < 2) { sleep(120); continue; }
            lastGood = System.currentTimeMillis();

            int n = vals.size();
            // 点数突变多半是解析截断造成的坏帧, 丢弃, 避免曲线横向抖动
            if (stableN > 0 && n != stableN) {
                if (n == pendingN) pendingCnt++; else { pendingN = n; pendingCnt = 1; }
                if (pendingCnt < 3) { sleep(60); continue; }   // 连续 3 帧一致才认可
            }
            stableN = n; pendingCnt = 0;
            if (accPow == null || accPow.length != n) {
                accPow = new double[n];
                peakDb = new double[n];
                for (int i = 0; i < n; i++) { accPow[i] = -1; peakDb[i] = -1e9; }
            }
            double[] y = new double[n];
            if (traceMode == 0) {                       // 实时: 无需幂运算
                for (int i = 0; i < n; i++) y[i] = vals.get(i);
            } else if (traceMode == 3) {                // 峰值保持
                for (int i = 0; i < n; i++) {
                    double db = vals.get(i);
                    if (db > peakDb[i]) peakDb[i] = db;
                    y[i] = peakDb[i];
                }
            } else {                                     // 平均: 功率域
                int navg = (traceMode == 1) ? 4 : 16;
                double alpha = 1.0 / navg;
                for (int i = 0; i < n; i++) {
                    double pw = Math.pow(10.0, vals.get(i) / 10.0);
                    if (accPow[i] < 0) accPow[i] = pw;
                    else accPow[i] = accPow[i] * (1.0 - alpha) + pw * alpha;
                    y[i] = 10.0 * Math.log10(accPow[i] > 1e-30 ? accPow[i] : 1e-30);
                }
            }

            final double[] yy = y;
            final double fa = fStart, fb = fStop;
            ui.post(new Runnable() {
                public void run() { plot.setData(yy, fa, fb); }
            });

            fps++;
            long dt = System.currentTimeMillis() - t0;
            if (dt > 2000) {
                final int f = (int) (fps * 1000 / dt);
                ui.post(new Runnable() {
                    public void run() {
                        status.setText("曲线 · " + f + " fps · " + freqLo + " ~ " + freqHi
                                + (pausedFlag ? " · 已暂停" : ""));
                    }
                });
                fps = 0; t0 = System.currentTimeMillis();
            }

            long spent = System.currentTimeMillis() - frameStart;
            long target = pollMs;
            if (spent < target) sleep((int) (target - spent));
        }
    }

    boolean send(String s) {
        synchronized (io) {
            if (!usbOk || conn == null || epOut == null) return false;
            try {
                byte[] b = s.getBytes();
                return conn.bulkTransfer(epOut, b, b.length, 1000) == b.length;
            } catch (Throwable th) {
                usbOk = false;
                return false;
            }
        }
    }

    boolean readFrame(byte[] out) {
        byte[] buf = new byte[8192];
        int off = 0;
        boolean echoDone = false;
        long t0 = System.currentTimeMillis();
        while (off < FRAME && System.currentTimeMillis() - t0 < 6000) {
            int n = conn.bulkTransfer(epIn, buf, buf.length, 300);
            if (n <= 0) continue;
            int i = 0;
            if (!echoDone) {
                while (i < n) { if (buf[i++] == '\n') { echoDone = true; break; } }
                if (!echoDone) continue;
            }
            while (i < n && off < FRAME) out[off++] = buf[i++];
        }
        return off == FRAME;
    }

    Bitmap toBitmap(byte[] f) {
        int[] px = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            int v = ((f[2 * i] & 0xFF) << 8) | (f[2 * i + 1] & 0xFF);
            int r = ((v >> 11) & 0x1F) * 255 / 31;
            int g = ((v >> 5) & 0x3F) * 255 / 63;
            int b = (v & 0x1F) * 255 / 31;
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }
}

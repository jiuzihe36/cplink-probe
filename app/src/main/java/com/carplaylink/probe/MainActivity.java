package com.carplaylink.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ParcelUuid;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P0 探测界面（v0.2）：
 *  - 自动给设备打标：SDP 里带 CarPlay 配件 UUID 的标"★ 车机"
 *  - 【自动找车机】：逐个试，谁有 iAP2 响应就锁定谁
 *  - 强制显示"当前选中"，避免点错设备
 */
public class MainActivity extends Activity implements ProbeSession.Listener {

    private static final int REQ_PERMS = 1001;
    /** 界面/日志里显示的版本（每次发版同步 app/build.gradle 的 versionName） */
    private static final String VERSION = "v0.2";

    private TextView statusView;
    private TextView selectedView;
    private TextView resultView;
    private TextView logView;
    private ArrayAdapter<String> deviceAdapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, List<UUID>> uuidMap = new HashMap<>();
    private BluetoothDevice selected;
    private ProbeSession session;
    private final StringBuilder logBuffer = new StringBuilder();
    private int logLines;
    private String lastDeviceTable = "";
    /** 设备集合签名（只用于抑制"设备表"刷屏：SDP 异步回来会反复触发重打） */
    private String lastDeviceSignature = "";
    private String lastSsid;
    private CarLifeProbe carLifeProbe;
    private String lastPass;
    private volatile long lastP1At;
    private volatile int p1Fails;
    /** 单飞锁：②③/诊断/导出 这类长任务同时跑会让日志互相穿插，没法看 */
    private static volatile boolean busy;
    private static volatile String busyName = "";

    /**
     * ② 的候选入口，按优先级排 —— **每次点只拉一个**。
     *
     * v3.5 改法：v3.4 是"一次点按连续拉 8 个界面"，在手机上就是屏幕不停跳到 CarLife 又跳回来，
     * 用户看到的就是"卡死黑屏"。而且 Android 10+ 有后台启动 Activity 限制：第一个拉起来之后
     * 我们的 App 就到后台了，后面的 startActivity 多半被静默拦截 —— 又甩屏又没用。
     * 现在改成"一次一个"，并且把当前试到第几个、该干什么写进日志。
     *
     * 顺序上把 CarLife+ 的**主界面**放最前：它是正常 UI（会显示"未连接"），
     * 而 PermissionActivity 是权限门，直接拉容易是一片空白。
     */
    private static final String[][] CARLIFE_ENTRIES = {
            {"com.baidu.carlife.oppo", "com.baidu.che.codriver.ui.MainActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.carlife.CarlifeActivity"},
            {"com.oplus.ocar", "com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity"},
            {"com.oplus.ocar", "com.oplus.ocar.CarlifeActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.carlife.core.base.activity.PermissionActivity"},
    };
    private static volatile int entryIdx;
    /** 内容切换按钮（① 回退成镜像时要同步它的文字） */
    private Button contentBtn;
    /** ★ v1.2：一键流程第一段跑完、等用户切回本 App 再跑第二段（后台不许启动 Activity） */
    private volatile boolean pendingOneKey;
    private volatile boolean oneKeyRunning;

    // 日志：内存环形缓冲（按字符裁剪）+ UI 节流渲染（只渲染尾部若干行）
    private static final int LOG_MAX_CHARS = 120000;
    private static final int LOG_KEEP_CHARS = 80000;
    private static final int LOG_TAIL_LINES = 400;
    private final java.util.concurrent.atomic.AtomicBoolean uiPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable uiRefresh = new Runnable() {
        @Override
        public void run() {
            uiPending.set(false);
            renderLogTail();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    addDevice(d);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                onLog("扫描结束，列表共 " + devices.size() + " 台");
                status("扫描结束，点【自动找车机】最省事");
                rebuildList();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d != null) {
                    onLog("配对状态变化 " + safeName(d) + " -> " + bondStateName(state));
                    rebuildList();
                }
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                if (d != null && uuids != null) {
                    List<UUID> list = new ArrayList<>();
                    for (Parcelable p : uuids) {
                        if (p instanceof ParcelUuid) {
                            list.add(((ParcelUuid) p).getUuid());
                        }
                    }
                    uuidMap.put(d.getAddress(), list);
                    if (list.contains(ProbeSession.UUID_ACCESSORY)) {
                        onLog("★ " + safeName(d) + " 的 SDP 里有 CarPlay 配件服务 → 这就是车机");
                    }
                    rebuildList();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestPerms();
        onLog("CPLink 探测 " + VERSION + " 启动");
        onLog("日志文件: " + logFile().getAbsolutePath());
        installStreamingHook();
        installCarLifeRelaunchHook();
        refreshBonded();
    }

    /** Car+ 的车机界面 —— **必须先摆到前台**（见 bringCarPlusFront 的说明） */
    static final String CARPLUS_FRONT = "com.oplus.ocar.CarlifeActivity";

    /**
     * 把 Car+ 的车机界面摆到前台。
     *
     * ★ v3.20 的根因修正（对照 6 份历史日志得出的硬结论）：
     *   CarLife+（`com.baidu.carlife.oppo`）只是 **Car+（`com.oplus.ocar`）的一个组件**。
     *   它要编码的画面，必须由 Car+ 通过它的 cast 服务**交给它一个 Surface**。
     *   **Car+ 不在前台 → Car+ 不 bind CarLife+ → CarLife+ 只跑握手、根本不开编码器**
     *   （表现就是通道 2 上 0 字节，甚至一连上就被对端 reset）。
     *
     * 实测对照（同一份代码，只有这个顺序不同）：
     *   v3.13/14/15/17 —— 接 CarLife **之前**先拉过 Car+ 前台 → 都有视频帧（v3.17 一路 10300 帧）；
     *   v3.18/19      —— Car+ 是投流之后才试的 → 0 帧。
     *
     * 所以凡是"要接 CarLife"的路径（②、③、自动重连、自动重来）都必须先走这里。
     */
    private void bringCarPlusFront(String why) {
        onLog("★ " + why + "：先把 **Car+ 的车机界面**摆到前台（" + CARPLUS_FRONT + "）");
        onLog("   为什么：CarLife+ 只是 Car+ 的**组件**，要编码的画面得由 Car+ 通过 cast 服务"
                + "交给它一个 Surface；Car+ 不在前台，CarLife+ 就只握手、**不开编码器**。");
        boolean ok = CarPlusUi.launchOne(this, CARPLUS_FRONT, uiLogger());
        if (!ok) {
            onLog("   ✗ 首选界面拉不起来，按候选表再试一遍…");
            ok = CarPlusUi.launch(this, uiLogger()) != null;
        }
        if (ok) {
            onLog("   ✓ 已拉起（等 2.5 秒，让 Car+ 把 Surface 交给 CarLife+）");
        } else {
            onLog("   !! 拉不起来 —— 请**手动**打开 Car+ 并停在它的车机界面，再点【②】");
        }
        try {
            Thread.sleep(2500);
        } catch (InterruptedException ignored) {
            // 继续
        }
    }

    /**
     * 让 CarLifeProbe 在"换局域网地址重试"之前能把 CarLife+ 重新拉起来。
     *
     * 为什么必须有：CarLife+ 在一轮会话失败后会把 7240 等端口**关掉** —— v3.11 的第二轮
     * 就是这么白跑的（7 条通道全 refused）。换地址之前先重拉一次，端口才会再开。
     * ★ v3.20：重拉的时候**先拉 Car+**（否则重拉起来的 CarLife+ 照样不编码）。
     */
    private void installCarLifeRelaunchHook() {
        CarLifeProbe.relaunchHook = new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // ★ v1.2：**先 CarLife+、再 Car+** —— 顺序和 v3.20 相反，原因：
                        // 我们现在已经在后台（Car+ 钉着前台），而**后台 App 的 startActivity 会被静默丢掉**。
                        // 第一次 startActivity 还有机会成功（刚被切后台的宽限期），所以把"必须起来"的
                        // CarLife+ 放第一发；Car+ 摆前台放第二发（它失败也不致命）。
                        for (String[] e : CARLIFE_ENTRIES) {
                            if (launchComponent(e[0], e[1])) {
                                onLog("   （已重新拉起 " + friendly(e[0]) + ": " + e[1] + "）");
                                break;
                            }
                        }
                        boolean cp = CarPlusUi.launchOne(MainActivity.this, CARPLUS_FRONT, uiLogger());
                        onLog(cp
                                ? "   （已重新拉起 Car+ 车机界面）"
                                : "   !! Car+ 车机界面重拉失败 —— 请手动打开 Car+");
                        if (!cp) {
                            onLog("   （提示：如果重拉一直失败，点【打开 CarLife+】手动来一次更稳）");
                        }
                    }
                }, "relaunch-carlife").start();
            }
        };
    }

    /**
     * ★ v1.2：一键的**第二段** —— 必须在 onResume（我们刚回到前台）里跑，
     * 因为只有前台 App 才被允许 startActivity（Android 10+ 的后台启动限制）。
     *
     * 做四件事：等 CarLife+ 的端口 → 把 Car+ 摆前台（CarLife+ 要它在前台才开编码器）
     * → 接握手 → 等它出画面 → 起 CarPlay。
     */
    private void runOneKeyRest() {
        if (oneKeyRunning) {
            return;
        }
        if (!beginTask("一键·接 CarLife")) {
            return;
        }
        oneKeyRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String h2 = null;
                    boolean vid = false;
                    for (int t = 0; t < 16; t++) {
                        h2 = LocalNetDiag.anyCarLifeOpen(800);
                        if (h2 != null) {
                            vid = CarLifeProbe.videoPortListening();
                            break;
                        }
                        Thread.sleep(1500);
                    }
                    if (h2 == null) {
                        // ★ v1.8：**不再自动回退成手机整屏镜像** —— 那样车机上就是手机屏，
                        // 还得弹一次录屏授权，用户要的不是这个。这里直接不起投屏，把话说明白。
                        onLog("!! CarLife+ 24 秒都没开端口 —— 它没真的起来（或被系统清掉了）。");
                        onLog("   → **这次没有起投屏**（免得又变成手机整屏）。");
                        onLog("   → 手动来一次：点【高级 ▾】→【打开 CarLife+】→ 切回本 App →"
                                + "【高级 ▾】→【③ 当车机·接Car+】");
                        onLog("     等日志出现「★★ 收到视频帧 #1」再点【高级 ▾】→【① 自动找车机】。");
                        return;
                    }
                    onLog("   ✓ CarLife+ 端口开了（" + h2
                            + (vid ? "，视频通道 8240 也在 ✓" : "，⚠ 视频通道 8240 没开") + "）");
                    bringCarPlusFront("② 再把 Car+ 摆前台");
                    // ★ v1.7：多等一会儿 —— Car+ 冷启动时要把它的 CarLife 组件初始化好，
                    // CarLife+ 才会真的进"等车机"状态（以前只等 2 秒，太早握手它就不回 65538）。
                    onLog("   再等 6 秒让 Car+ 的组件就绪（冷启动时它要更久），然后才发版本协商");
                    Thread.sleep(6000);
                    probeNow();
                    onLog("   已发起握手 —— 等 CarLife+ 开始推车机界面（最多 25 秒）…");
                    for (int i = 0; i < 25; i++) {
                        Thread.sleep(1000);
                        if (CarLifeProbe.isStreaming()) {
                            break;
                        }
                    }
                    if (!CarLifeProbe.isStreaming()) {
                        // ★ v1.8：握手通了但手机一个字节都没往视频通道写 → 起 CarPlay 只会是手机屏。
                        // 不起，把原因和下一步说清楚。
                        onLog("!! 握手通了，但 CarLife+ 25 秒没往视频通道写一个字节（通道 2 零字节）。");
                        onLog("   最常见原因：**Car+ 不在前台**（CarLife+ 就不开编码器）。");
                        onLog("   → 手动来一次：点【高级 ▾】→【打开 Car+】把 Car+ 摆前台，"
                                + "再点【高级 ▾】→【③ 当车机·接Car+】，等「★★ 收到视频帧 #1」出现，"
                                + "最后点【高级 ▾】→【① 自动找车机】。");
                        onLog("   → **这次没有起投屏**（免得又变成手机整屏）。");
                        return;
                    }
                    onLog("★★ CarLife 已经在推车机界面了 → 现在起 CarPlay");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            autoFind();
                        }
                    });
                } catch (Throwable t) {
                    onLog("!! 一键异常: " + t);
                } finally {
                    oneKeyRunning = false;
                    endTask();
                }
            }
        }, "onekey-carlife").start();
    }

    /**
     * 投流一开始就把 Car+ / CarLife+ 摆到前台 —— 这样车机上显示的就是 Car+ 的界面，
     * 而不是我们自己的 App（整屏投屏投的是"手机当前画面"，所以前台是谁就投谁）。
     */
    private void installStreamingHook() {
        VideoSender.streamingHook = new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (VideoSender.carPlusCastMode) {
                            onLog("★ **Car+ 投屏模式** → 把编码器输入 Surface 交给 Car+（服务已在按钮里绑好）");
                            CarPlusCast.attachSurface(VideoSender.encoderInputSurface,
                                    1280, 720, 160, uiLogger());
                            onLog("   （Car+ 出画面后车机上就是 Car+ 的车机界面；触摸走 addTouchListener 直达 Car+）");
                            return;
                        }
                        if (VideoSender.carLifePassthroughAllowed) {
                            // ★ v1.1：直通模式下车机上放的是 **CarLife+ 的画面**，
                            // 所以完全不需要"逐个试 Car+ 的界面、找哪个能录"（那是镜像模式才需要的）。
                            // 但有一件事必须做：**把 Car+ 钉在最前台** ——
                            // 实测（v3.18/19 vs v3.13/14/15/17 六份日志对照）：Car+ 不在前台，
                            // CarLife+ 就只握手、**不开编码器**（通道 2 上 0 字节）。
                            onLog("★ **CarLife 直通**：车机上放的是 CarLife+ 的车机界面 ——"
                                    + " 不需要找\"能录的 Car+ 界面\"（那是镜像模式的事）");
                            onLog("   但 Car+ 必须**钉在最前台**：实测 Car+ 不在前台 → CarLife+ 不开编码器"
                                    + "（通道 2 上 0 字节，握手全通也没画面）");
                            int pt = 0;
                            while (!VideoSender.stopRequested) {
                                CarPlusUi.launchOne(MainActivity.this, CARPLUS_FRONT, uiLogger());
                                try {
                                    Thread.sleep(15000);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                pt++;
                                if (pt % 4 == 0) {
                                    onLog("   （Car+ 钉前台中，已 " + (pt * 15) + " 秒）");
                                }
                            }
                            onLog("   （投屏结束，停止钉前台）");
                            return;
                        }
                        try {
                            Thread.sleep(3000);
                        } catch (Throwable ignored) {
                        }
                        onLog("★ 投屏已开始 → 现在逐个试 Car+ 的界面：**能录的留下**，"
                                + "被系统掐掉的换下一个");
                        onLog("   （判断标准：拉起后等 2.5 秒，看屏幕投影还在不在 —— "
                                + "不在了就说明那个界面是受保护窗口 FLAG_SECURE）");
                        String[] acts = CarPlusUi.activities();
                        boolean found = false;
                        for (int i = 0; i < acts.length; i++) {
                            if (!VideoSender.projectionAlive) {
                                onLog("   （投影已经没了，不再往下试）");
                                break;
                            }
                            onLog("   试 Car+ 界面 " + (i + 1) + "/" + acts.length + ": " + acts[i]);
                            if (!CarPlusUi.launchOne(MainActivity.this, acts[i], uiLogger())) {
                                continue;
                            }
                            // ★ v3.12：盯 **12 秒**，不能只等 2.5 秒。受保护窗口有时是**延迟**出现的 ——
                            // v3.11 实测：2.5 秒检查时还活着、被判定"能录"，结果后来又弹出来把录屏掐了。
                            boolean alive = true;
                            for (int s = 0; s < 8; s++) {
                                try {
                                    Thread.sleep(1500);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                if (!VideoSender.projectionAlive) {
                                    alive = false;
                                    break;
                                }
                            }
                            if (alive) {
                                onLog("   ✓ 这个界面**能录**（盯了 12 秒投影都没被掐）→ 就停在这儿，"
                                        + "车机上现在看到的就是它");
                                found = true;
                                break;
                            }
                            onLog("   ✗ 这个界面是**受保护窗口**（投影被系统掐了）→ 换下一个");
                        }
                        if (!found) {
                            onLog("!! Car+ 的这些界面要么拉不起来、要么**全是受保护窗口** ——"
                                    + " 整屏投屏 Car+ 这条路**封死**（系统限制，绕不过）。");
                            onLog("   → 那就用【内容】按钮切到【CarLife直通】：画面是 CarLife+ 自己的镜像"
                                    + "（不是 Car+，但触摸能用）。");
                        }
                        // ★ v0.5：**把 Car+ 钉在最前台**。
                        // 为什么：实测 Car+ 的原生车机界面要百度账号+服务器激活（"Mix"，box_not_activate），
                        // 拿不到；所以想看到 Car+，唯一的路就是"投手机屏 + Car+ 一直在最前"。
                        // 注意：这个模式下**触摸到不了 Car+**（CarLife 的触控只发给 CarLife+ 自己），
                        // 只能看不能点；要能点就用【内容】切到 CarLife 直通。
                        final String pinned = found ? acts[0] : CARPLUS_FRONT;
                        onLog("★ 开始**把 Car+ 钉在最前台**（每 15 秒确认一次，只到本次投屏结束）");
                        onLog("   → 车机上现在显示的就是手机屏 = Car+。**这个模式只能看、不能点**"
                                + "（要能点请点【内容】切到【CarLife直通】）。");
                        int ticks = 0;
                        while (!VideoSender.stopRequested) {
                            try {
                                Thread.sleep(15000);
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (VideoSender.stopRequested) {
                                break;
                            }
                            CarPlusUi.launchOne(MainActivity.this, pinned, uiLogger());
                            ticks++;
                            if (ticks % 4 == 0) {
                                onLog("   （Car+ 钉前台中，已 " + (ticks * 15) + " 秒）");
                            }
                        }
                        onLog("   （投屏结束，停止钉前台）");
                    }
                }, "bring-front").start();
            }
        };
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("CPLink 探测 " + VERSION + "（手机扮演 iPhone 走无线 CarPlay / CarLife 桥）");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("用法：点【▶ 开始投屏】—— 它会拉起 CarLife+ 并提示你**切回本 App**，"
                + "然后自动接上、把车机界面投到车机上（触摸可用）。"
                + "要投手机整屏 / 手动补救 / 各种探针，都在【高级 ▾】里。原用法：① 自动找车机（走完 CarPlay）→ 车机上就是手机屏，"
                + "并自动**把 Car+ 钉在最前台**（所以看到的是 Car+）。"
                + "【内容】按钮可切：Car+镜像（能看不能点）/ CarLife直通（画面是 CarLife+ 自己的，"
                + "但触摸能用，需先 ② 接上 CarLife）。② 接上后不用再点 ③；"
                + "CarLife 黑屏/停在等待页是正常的）→ 端口开了会自动接握手；"
                + "也可手动打开 CarLife+ 停在\"无线连接 → 百度CarLife+\"那页，再点 ③");
        hint.setTextSize(11);
        hint.setTextColor(Color.parseColor("#888888"));
        root.addView(hint);

        statusView = new TextView(this);
        statusView.setText("就绪");
        statusView.setTextSize(13);
        root.addView(statusView);

        selectedView = new TextView(this);
        selectedView.setText("当前选中：无（先在列表里点一台设备）");
        selectedView.setTextSize(13);
        selectedView.setTypeface(Typeface.DEFAULT_BOLD);
        selectedView.setTextColor(Color.parseColor("#0066CC"));
        selectedView.setPadding(0, dp(2), 0, dp(6));
        root.addView(selectedView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        // ★ v1.4：**主界面只留用户要点的**（开始 / 停止 / 高级）。
        // 其余按钮全部收进折叠的「高级 ▾」里 —— 界面上一眼看过去只有两个动作。
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);

        Button bigStart = button("▶ 开始投屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v1.2：拆成**两段** —— 因为 **Android 不许后台 App 启动别的 Activity**。
                //   第一段（这里，我们一定在前台，是你这一下点击给的权限）：只把 CarLife+ 拉起来。
                //   然后请用户切回本 App；第二段在 onResume 里跑（那时我们又在前台，才能把 Car+ 摆前台）。
                //   v1.1 把两段写在一起：拉起 Car+ 之后我们的 App 就退到后台了，
                //   后面那次 startActivity(CarLife+) 被系统**静默丢掉**（返回成功但什么都没发生）——
                //   日志表现就是「等了 12 秒 CarLife+ 还是没开端口」。
                VideoSender.carLifePassthroughAllowed = true;
                VideoSender.carLifePassthrough = false;
                VideoSender.carPlusCastMode = false;
                onLog("★ 一键投车机界面：CarLife+ 进\"等车机\"状态 → 我们冒充车机接上它 →"
                        + " 它把车机界面（横屏 1280x720）推给我们 → 走 CarPlay 上屏");
                if (CarLifeProbe.isConnected()) {
                    onLog("   （CarLife 已经连着 → 直接进第二段）");
                    runOneKeyRest();
                    return;
                }
                String hit = LocalNetDiag.anyCarLifeOpen(600);
                if (hit != null) {
                    onLog("★ 已经有 CarLife 在听: " + hit + " → 直接进第二段");
                    runOneKeyRest();
                    return;
                }
                onLog("   ① 先拉起 CarLife+（" + CARLIFE_ENTRIES[0][0] + "/" + CARLIFE_ENTRIES[0][1]
                        + "）—— **必须趁我们还在前台**，否则会被系统的后台启动限制拦掉");
                if (!launchComponent(CARLIFE_ENTRIES[0][0], CARLIFE_ENTRIES[0][1])) {
                    onLog("   !! 拉不起来 —— 请手动打开 CarLife+ 停在\"无线连接\"那一页，"
                            + "再点【③ 当车机·接Car+】");
                    return;
                }
                onLog("   ⚠ 它停在等待页 / **黑屏**是正常的（在等车机连它）");
                pendingOneKey = true;
                onLog("★★ 现在**切回本 App**（返回键或最近任务）—— 我会自动接着做：");
                onLog("     等它的端口开 → 把 Car+ 摆前台 → 接握手 → 等画面 → 起 CarPlay");
                toast("已拉起 CarLife+：请切回本 App，我自动接着做");
            }
        });
        bigStart.setTextSize(18);
        mainRow.addView(bigStart);
        mainRow.addView(button("■ 停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoSender.stopRequested = true;
                AudioSender.stop();          // ★ v2.0：音频流也要停
                if (session != null) {
                    session.stop();
                }
                status("已停止");
                onLog("■ 已停止投屏");
            }
        }));
        mainRow.addView(button("高级 ▾", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = adv.getVisibility() != View.VISIBLE;
                adv.setVisibility(show ? View.VISIBLE : View.GONE);
                ((Button) v).setText(show ? "高级 ▴" : "高级 ▾");
            }
        }));
        root.addView(mainRow);
        root.addView(adv);


        // ★ v2.3：**一键投手机整屏** —— 不用 CarLife、不用 Car+，直接把手机屏推到车机上。

        // 用途：手机把 SmartDock（开源桌面模式启动器）当桌面时，车机上就是它的横屏车机桌面，

        // 而不是 CarLife+/Car+ 那张卡住的静态页。屏幕录制权限要弹一次，允许即可。

        adv.addView(button("★ 投手机整屏到车机（不用 CarLife）", new View.OnClickListener() {

            @Override

            public void onClick(View v) {

                onLog("—— ★ 投手机整屏：不接 CarLife、不用 Car+，直接把手机屏推到车机 ——");

                VideoSender.carLifePassthroughAllowed = false;

                VideoSender.carLifePassthrough = false;

                if (contentBtn != null) {

                    contentBtn.setText("内容:Car+镜像");

                }

                onLog("   1) 马上会弹一次【屏幕录制】授权 —— 点【立即开始/允许】");

                onLog("   2) 投起来之后按 **Home 键回桌面**（装了 SmartDock 就是它的桌面）");

                onLog("   3) **手机横过来**（打开自动旋转）—— 车机屏是 1280x720 横屏，竖着推过去两边是黑边");

                onLog("   4) 车机触摸这条链还没验证（日志里会报有没有收到车机的触摸事件）");

                autoFind();

            }

        }));


        adv.addView(button("把 CarLife+ 摆前台（试内容源）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v2.1：画面"卡住不动"时的排查动作。
                // 推流时我们每 15 秒把 **Car+** 钉在最前台（Car+ 不在前台 CarLife+ 不开编码器）。
                // 但如果车机上那张画面就是 Car+ 自己的静止界面，说明内容源其实该是 CarLife+ 自己 ——
                // 按这个按钮把 CarLife+ 摆前台，看画面变不变（变了就说明内容源搞错了）。
                onLog("—— 把 CarLife+ 摆到前台（com.baidu.carlife.oppo）——");
                boolean ok = CarPlusUi.launchOne(MainActivity.this,
                        "com.baidu.carlife.oppo/com.baidu.che.codriver.ui.MainActivity", uiLogger());
                onLog(ok ? "   ✓ 已拉起 —— 看车机上那张画面有没有变活" : "   !! 拉不起来");
            }
        }));
        adv.addView(button("测试触摸（往 CarLife 画面中心点一下）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v1.9：分辨"哪一侧的问题"。这条**绕开车机**，直接往 CarLife 触控通道（9340）发一次
                // 按下+抬起（画面中心）。车机上 CarLife 界面中心如果有反应 → 我们这条转发链是对的，
                // 问题在车机没把触摸发过来；如果毫无反应 → 问题在 CarLife 这侧的报文/坐标。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        onLog("—— 测试触摸：直接往 CarLife 触控通道发「按下+抬起」（画面中心）——");
                        if (!CarLifeProbe.isStreaming()) {
                            onLog("   !! CarLife 还没在推流 —— 先让它连上再测");
                        }
                        int cx = 640;
                        int cy = 360;
                        if (VideoSender.carLifeW > 0 && VideoSender.carLifeH > 0) {
                            cx = VideoSender.carLifeW / 2;
                            cy = VideoSender.carLifeH / 2;
                        }
                        boolean a = CarLifeProbe.sendTouch(0, cx, cy);
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException ignored) {
                        }
                        boolean b = CarLifeProbe.sendTouch(1, cx, cy);
                        onLog("   按下=" + (a ? "已发" : "失败") + "  抬起=" + (b ? "已发" : "失败")
                                + "  （" + cx + "," + cy + "）");
                        onLog("   → 看车机上 CarLife 界面**中心**有没有反应（高亮/按下效果/跳转）");
                        onLog("     有反应 = 我们这条链没问题，问题在车机没把触摸发过来（要在 DiPlay 那台设备上点）");
                        onLog("     没反应 = 问题在 CarLife 这侧（报文/坐标），把这段日志发我");
                    }
                }, "test-touch").start();
            }
        }));
        adv.addView(button("打开 Car+（摆前台）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 手动补救：Car+ 不在前台时 CarLife+ 不开编码器（通道 2 零字节）
                onLog("—— 把 Car+ 摆到前台（" + CARPLUS_FRONT + "）——");
                boolean ok = CarPlusUi.launchOne(MainActivity.this, CARPLUS_FRONT, uiLogger());
                onLog(ok ? "   ✓ 已拉起" : "   !! 拉不起来 —— 从设置→应用里打开 Car+");
            }
        }));
        adv.addView(button("打开 CarLife+（手动补救用）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v1.2：**这一下点击本身就是权限** —— 我们此刻在前台，所以拉起 CarLife+ 不会被拦。
                // 卡住的时候用它手动来一次：打开 CarLife+ → 停在"无线连接"页 → 切回本 App → 点【③】。
                onLog("—— 拉起 CarLife+（" + CARLIFE_ENTRIES[0][0] + "/" + CARLIFE_ENTRIES[0][1] + "）——");
                boolean ok = launchComponent(CARLIFE_ENTRIES[0][0], CARLIFE_ENTRIES[0][1]);
                onLog(ok
                        ? "   ✓ 已拉起。它在等待页/黑屏是正常的（在等车机连它）→ 切回本 App 点【③ 当车机·接Car+】"
                        : "   !! 拉不起来 —— 从系统设置里打开它（设置→应用→CarLife+）");
            }
        }));
        row1.addView(button("① 自动找车机", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v3.19：① 一按下去，CarPlay 那条流的尺寸就**定死了**（SETUP 那一刻决定），
                // 内容源也只有那时才选。所以先提醒：CarLife 没接上的话，车机现在看到的会是
                // **整块手机屏**（整屏镜像），而不是 Car+。
                // ★ v0.5：默认内容模式是【Car+镜像】—— 投手机屏 + 把 Car+ 钉最前台，
                // 这条**不需要 CarLife**（CarLife 那条拿到的只是 CarLife+ 自己的镜像）。
                // 只有切到【CarLife直通】时才需要先 ② 把 CarLife 接上。
                if (VideoSender.carLifePassthroughAllowed && !CarLifeProbe.isConnected()) {
                    // ★ v1.0：默认内容就是【CarLife直通】。没接上 CarLife 就直通 = 黑屏，
                    // 所以这里**自动回退成整屏镜像**（至少能看到手机屏），并把按钮文字同步过来，
                    // 免得用户以为模式变了却看不出来。
                    onLog("⚠ 内容是【CarLife直通】但 **CarLife 还没接上** —— 直通会黑屏。");
                    onLog("   → 想投车机界面：点【★ 一键·车机界面】（它会先接 CarLife 再走 CarPlay）。");
                    onLog("   → 这次先按【Car+镜像】投手机屏（能看到画面，只是只能看不能点）。");
                    VideoSender.carLifePassthroughAllowed = false;
                    VideoSender.carLifePassthrough = false;
                    if (contentBtn != null) {
                        contentBtn.setText("内容:Car+镜像");
                    }
                } else if (!VideoSender.carLifePassthroughAllowed) {
                    onLog("   （内容模式【Car+镜像】：投手机屏并把 Car+ 钉在最前台 —— 不需要 CarLife）");
                } else if (!CarLifeProbe.isStreaming()) {
                    onLog("   （CarLife 已连上，但还没收到视频帧 —— 车机可能先显示整屏，"
                            + "CarLife 一开始推流就会切成它的画面）");
                }
                autoFind();
            }
        }));
        row1.addView(button("扫描", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        }));
        row1.addView(button("已配对", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshBonded();
            }
        }));
        adv.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("配对选中", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pairSelected();
            }
        }));
        row2.addView(button("连接选中", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectSelected();
            }
        }));
        row2.addView(button("蓝牙设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                    onLog("—— 已打开系统蓝牙设置：点车机（或 DiPlay 那台）把它连上 ——");
                    onLog("   Car+ 的 CarLife+ 页面第 1 步要的就是这个连接，它显示\"蓝牙未连接\"时【开始连接】是灰的");
                } catch (Throwable t) {
                    onLog("!! 打不开蓝牙设置: " + t);
                }
            }
        }));

        adv.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("Car+车机界面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLog("—— 拉起 Car+ 自己的车机界面（然后只录它这一个应用）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String a = CarPlusUi.launch(MainActivity.this, new AirPlayProbe.Logger() {
                            @Override
                            public void log(String line) {
                                onLog(line);
                            }
                        });
                        onLog(a == null
                                ? "   手动打开 Car+ 也行；停在它的车机/驾驶界面"
                                : "   下一步：点【授权录屏】→ 选【单个应用】→ Car+ 车联");
                    }
                }, "ocar-ui").start();
            }
        }));
        row3.addView(button("CarLife+组件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLog("—— 直接拉起 CarLife+ 组件（Car+ 的上游，没桌面图标）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        CarAppLauncher.launchCarLife(MainActivity.this, new AirPlayProbe.Logger() {
                            @Override
                            public void log(String line) {
                                onLog(line);
                            }
                        });
                    }
                }, "carlife-app").start();
            }
        }));
        row3.addView(button("② Car+等车机", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!beginTask("② Car+等车机")) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // ★ v3.20：**不管走哪条路，先把 Car+ 摆到前台**。
                            // 这是对照 6 份日志找出来的根因：Car+ 不在前台 → CarLife+ 不开编码器
                            // → 握手全通、通道 2 上 0 字节。以前这一步只在投流之后才做，
                            // 所以"能用"的那些次都是顺序碰巧对了。
                            bringCarPlusFront("② 的第一步");
                            // 0) 再 看有没有人在听 —— 用户可能刚在 CarLife 里点过"连接"，
                            //    这种情况**不用再甩屏**，直接接握手（v3.5：以前不管三七二十一先甩 8 个界面）
                            String hit = LocalNetDiag.anyCarLifeOpen(600);
                            if (hit != null) {
                                onLog("★ 已经有 CarLife 在听: " + hit + "（不用切界面）→ 直接接握手");
                                entryIdx = 0;
                                probeNow();
                                return;
                            }
                            if (entryIdx >= CARLIFE_ENTRIES.length) {
                                entryIdx = 0;
                                onLog("!! 候选入口都试过一遍了，端口还是没开。");
                                onLog("   → 请**手动**打开 CarLife+（没有桌面图标，可从"
                                        + "\"设置→应用→CarLife+\"或最近任务进），进"
                                        + "\"无线连接 → 百度CarLife+\"那一页停住，再点【③ 当车机·接Car+】。");
                                return;
                            }
                            String[] e = CARLIFE_ENTRIES[entryIdx];
                            entryIdx++;
                            onLog("—— ② 试第 " + entryIdx + "/" + CARLIFE_ENTRIES.length
                                    + " 个入口：" + e[0] + "/" + e[1] + " ——");
                            boolean ok = launchComponent(e[0], e[1]);
                            if (!ok) {
                                onLog("   ✗ 拉不起来（组件被禁或没有权限），再点一次【②】试下一个");
                                return;
                            }
                            onLog("   已切到 " + friendly(e[0]) + "。");
                            onLog("   ⚠ 它停在等待页 / **黑屏**是正常的（在等车机连它），不是卡死；");
                            onLog("     如果它黑屏且点不动，从最近任务把它划掉再切回本 App 即可。");
                            onLog("   现在切回本 App：端口开了就会自动接握手。"
                                    + "**第 1 个入口要 5~10 秒才开端口，别急着点第二次**");
                            // ★ v3.13：原来只等 3 秒就判"端口没开" → 第 1 个入口（唯一会开 8240 的那个状态）
                            // 常常还没开好就被跳过，落到第 2 个入口（只开 6 条、没有视频通道）→ 白跑。
                            // 现在每 1.5 秒查一次，最多等 12 秒，并且把"视频通道 8240 开没开"一起报出来。
                            String h2 = null;
                            boolean vid = false;
                            for (int t = 0; t < 8; t++) {
                                Thread.sleep(1500);
                                h2 = LocalNetDiag.anyCarLifeOpen(800);
                                if (h2 != null) {
                                    vid = CarLifeProbe.videoPortListening();
                                    break;
                                }
                            }
                            if (h2 != null) {
                                // ★ v0.4：端口"开着"≠ CarLife+ 已经进了"等车机"状态 ——
                                // 实测刚拉起来的 CarLife+ 要 5~10 秒才真的能应答 98305，
                                // 太早连上去的结果就是"6 秒没等到版本协商"（白跑一轮）。
                                onLog("   端口开了 —— 再等 3 秒，让 CarLife+ 真的进到\"等车机\"状态");
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ignored) {
                                }
                                onLog("★★ 命中！" + h2 + " 有人在听 → 自动接握手"
                                        + (vid
                                        ? "（视频通道 8240 也开着 ✓）"
                                        : "（⚠ **视频通道 8240 没开** —— 这个状态推不了画面，会自动补救）"));
                                entryIdx = 0;
                                probeNow();
                            } else {
                                onLog("   等了 12 秒端口还是没开（很多入口要再点一次，"
                                        + "或要手动点它的\"开始连接\"）");
                            }
                        } catch (Throwable t) {
                            onLog("!! ② 异常: " + t);
                        } finally {
                            endTask();
                        }
                    }
                }, "carlife-entry").start();
            }
        }));
        row3.addView(button("③ 当车机·接Car+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!beginTask("③ 当车机·接Car+")) {
                    return;
                }
                // ★ v3.16：已经在投屏时点 ③ = 亲手把好会话掐掉（实测整个会话被 reset、端口全关）。
                // ★ v3.19：**已经连着 CarLife 就一律拦住**（不只是"正在推流"那一种）——实测最常见的
                // 翻车就是 ② 已经自动接上了，用户又照提示点了 ③：③ 把好会话关掉，CarLife+ 顺手把
                // 7 条端口全收了，于是连不上 → CarPlay 那边只能回退成**整屏镜像**，
                // 用户看到的就是"车机上显示的是整个屏幕"。**② 接上之后就不需要 ③ 了。**
                if (CarLifeProbe.isConnected() || CarLifeProbe.isConnecting()) {
                    onLog(CarLifeProbe.isConnected()
                            ? "⚠ **已经连着 CarLife 了**（② 接上之后就不需要 ③）。"
                            : "⚠ **正在接 CarLife**（② 还没接完）—— 现在点 ③ 会把刚建立的会话关掉，"
                            + "CarLife+ 又要重新进状态，越点越乱。等它接完（日志里会出现"
                            + "「★ 已连上视频」或「自动重来第 N/3 轮」）。");
                    onLog("   " + (CarLifeProbe.isStreaming()
                            ? "现在正在收 CarLife 的视频帧 —— 再点 ③ 会把这条流掐掉。"
                            : "再点 ③ 会把这条好会话关掉，CarLife+ 会顺手把 7 条端口全收，"
                            + "然后 CarPlay 只能回退成整屏镜像。"));
                    onLog("   → 接着点【① 自动找车机】就行；要重连请先点【停止】。");
                    endTask();
                    return;
                }
                onLog("—— ③ 当车机：连 CarLife 的 7 条通道接握手（不占端口、不做诊断）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // ★ v3.20：③ 也要先摆 Car+ 前台（同样的根因，别只改 ②）
                            bringCarPlusFront("③ 的第一步");
                            probeNow();
                        } finally {
                            endTask();
                        }
                    }
                }, "carlife-peer").start();
            }
        }));
        // ★ v0.5：投屏内容切换（默认【Car+镜像】= 投手机屏 + 把 Car+ 钉最前台）
        contentBtn = button("内容:CarLife直通", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoSender.carLifePassthroughAllowed = !VideoSender.carLifePassthroughAllowed;
                VideoSender.carLifePassthrough = false;
                if (VideoSender.carLifePassthroughAllowed) {
                    ((Button) v).setText("内容:CarLife直通");
                    onLog("—— 投屏内容切成【CarLife直通】：车机上显示 CarLife+ 推来的画面"
                            + "（实测那是 CarLife+ 自己的镜像，不是 Car+；但**触摸能直达 CarLife+**）——");
                    onLog("   改完要**重新投屏**才生效（点【停止】再走一遍）。");
                    status("内容：CarLife直通");
                } else {
                    ((Button) v).setText("内容:Car+镜像");
                    onLog("—— 投屏内容切成【Car+镜像】：投手机屏 + 把 Car+ 钉在最前台"
                            + "（这样车机上看到的是 Car+；但**只能看、不能点**）——");
                    onLog("   改完要**重新投屏**才生效（点【停止】再走一遍）。");
                    status("内容：Car+镜像");
                }
            }
        });
        row3.addView(contentBtn);
        row3.addView(button("扫 Car+ 的端口", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ v1.0：Car+ 的 cast 接口被**双重锁死**（反编译 Car+ 17.31.0 证实）：
                //   ① 调用方必须是 com.baidu.carlife.oppo（CarLife+）或 Car+ 自己 —— 我们是别的包，直接 invalid caller；
                //   ② Car+ 自己必须先连上一台 CarLife 车机（ConnectionEngine 非 idle + CARLIFE 协议已连）。
                // 所以"Car+接屏"这条路在只有 CarPlay 的车上走不通。
                // 退一步的实验：**看 Car+ 自己在本机开了哪些端口** —— 如果它也用 CarLife 那一套
                // （7240/8240/…），那我们就能像对 CarLife+ 那样对 Car+ 也冒充"车机"，让它把界面推给我们。
                if (!beginTask("扫 Car+ 的端口")) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            onLog("—— 扫 Car+ 的本机端口（看它是不是也用 CarLife 那套协议）——");
                            onLog("   Car+ 的接屏接口为什么走不通：调用方必须是 CarLife+ 的包名"
                                    + "（com.baidu.carlife.oppo）或 Car+ 自己，且 Car+ 必须先连上一台 CarLife 车机。");
                            String launched = CarPlusUi.launch(MainActivity.this, uiLogger());
                            onLog("   已拉起 Car+ 界面: " + (launched == null ? "失败" : launched));
                            Thread.sleep(3000);
                            StringBuilder sb = new StringBuilder();
                            for (int port : CarLifeProbe.PORTS) {
                                if (SelfPorts.holds(port)) {
                                    continue;
                                }
                                java.net.Socket sk = new java.net.Socket();
                                try {
                                    sk.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                                    sb.append(port).append(" ");
                                } catch (Throwable ignored) {
                                } finally {
                                    try {
                                        sk.close();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            onLog("   CarLife 那 7 个端口(7240/8240/9240/9241/9242/9340/9440)里开着的: "
                                    + (sb.length() == 0 ? "一个都没有" : sb.toString()));
                            onLog("   正在并行扫 127.0.0.1 的 1024~20000（约 20 秒）…");
                            java.util.List<Integer> open = LocalNetDiag.scanLocalOpenPorts(1024, 20000, 128, 150);
                            onLog("   本机在听的端口（不含本 App 自己占的）共 " + open.size() + " 个:");
                            StringBuilder line = new StringBuilder("     ");
                            for (int i = 0; i < open.size(); i++) {
                                line.append(open.get(i)).append(" ");
                                if ((i + 1) % 12 == 0) {
                                    onLog(line.toString());
                                    line = new StringBuilder("     ");
                                }
                            }
                            if (line.length() > 5) {
                                onLog(line.toString());
                            }
                            onLog("   → 如果上面出现 7240/8240/…，说明 Car+ 也用 CarLife 那套 →"
                                    + " 接着点【③ 当车机·接Car+】试握手（它会先把 Car+ 摆前台）。");
                            onLog("   → 如果只有 Car+ 自己的端口（几千/几万号），那就得另找协议，成本很高。");
                        } catch (Throwable t) {
                            onLog("!! 扫端口异常: " + t);
                        } finally {
                            endTask();
                        }
                    }
                }, "ocar-ports").start();
            }
        }));
        row3.addView(button("旧·Car+探针", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLog("—— 旧探针：绑 Car+ 的 CastManagerService，prepareCast 拿它的画面 ——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            CarPlusCastProbe.run(MainActivity.this, new AirPlayProbe.Logger() {
                                @Override
                                public void log(String line) {
                                    onLog(line);
                                }
                            });
                        } catch (Throwable t) {
                            onLog("!! Car+ 接屏异常: " + t);
                        }
                    }
                }, "ocar-cast").start();
            }
        }));
        row3.addView(button("连车机热点", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastSsid == null) {
                    toast("还没有拿到车机热点凭据（先跑完 P0）");
                    return;
                }
                onLog("—— 手动申请连车机热点 " + lastSsid + "（会弹系统确认框）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AirPlayProbe.joinHotspot(MainActivity.this, lastSsid, lastPass, new AirPlayProbe.Logger() {
                                @Override
                                public void log(String line) {
                                    onLog(line);
                                }
                            }, true);
                        } catch (Throwable t) {
                            onLog("!! 连热点异常: " + t);
                        }
                    }
                }, "manual-join").start();
            }
        }));
        adv.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(button("导出组件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLog("—— 导出车联组件的 APK（要逆向 OPPO 的契约，必须先拿到这些文件）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        VendorDump.run(MainActivity.this, new AirPlayProbe.Logger() {
                            @Override
                            public void log(String line) {
                                onLog(line);
                            }
                        });
                    }
                }, "vendor-dump").start();
            }
        }));
        row4.addView(button("授权录屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLog("—— 手动申请屏幕录制授权（投屏内容源）——");
                requestScreenCapture();
            }
        }));
        row4.addView(button("复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        }));
        row4.addView(button("分享日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        }));
        row2.addView(button("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (logBuffer) {
                    logBuffer.setLength(0);
                }
                logLines = 0;
                try {
                    logFile().delete();
                } catch (Throwable ignored) {
                }
                logView.setText("");
                onLog("—— 日志已清空（内存 + 日志文件都清了）——");
            }
        }));
        row4.addView(button("P1连热点", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastSsid == null) {
                    toast("还没有拿到车机热点凭据（先跑完 P0）");
                    return;
                }
                onLog("—— 手动重试 P1：连 " + lastSsid + " + 打进 AirPlay 端口 ——");
                lastP1At = System.currentTimeMillis();
                startAirPlayProbe(lastSsid, lastPass);
            }
        }));
        row4.addView(button("网络诊断", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!beginTask("网络诊断")) {
                    return;
                }
                onLog("—— 网络诊断：全网卡 + 全端口 + UDP7999（30 秒观察窗口）——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            LocalNetDiag diag = new LocalNetDiag(MainActivity.this, uiLogger());
                            diag.run(30);
                        } catch (Throwable t) {
                            onLog("!! 网络诊断异常: " + t);
                        } finally {
                            endTask();
                        }
                    }
                }, "netdiag").start();
            }
        }));
        row4.addView(button("CarLife直通", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoSender.carLifePassthrough = !VideoSender.carLifePassthrough;
                if (VideoSender.carLifePassthrough) {
                    onLog("★ CarLife 直通【开】—— CarLife 推来的 H.264 会直接转进 CarPlay 视频流，"
                            + "车机上显示 CarLife 的画面（不再靠手机屏幕镜像）");
                    onLog("   前提：① 已经连上车机（CarPlay 那条流在跑）、② ③ 已经收到 CarLife 视频帧");
                } else {
                    onLog("★ CarLife 直通【关】—— 回到手机屏幕镜像当内容源");
                }
            }
        }));
        row4.addView(button("测试触摸", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 往 CarLife 的画面中心点一下（DOWN → 抬起）—— 手机那边应该有反应（比如点开某个图标）。
                // 这一步是在验证"我们发触控"这条路；车机自己的触摸要从 eventPort 那条通道收（见日志里的 event#）。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int w = VideoSender.carLifeW > 0 ? VideoSender.carLifeW : 1280;
                        int h = VideoSender.carLifeH > 0 ? VideoSender.carLifeH : 720;
                        int x = w / 2;
                        int y = h / 2;
                        onLog("—— 测试触摸：在 CarLife 画面中心点一下 (" + x + "," + y + " / " + w + "x" + h + ") ——");
                        if (!CarLifeProbe.sendTouch(0, x, y)) {
                            onLog("   !! 发不出去 —— 先点【③ 当车机·接Car+】把通道连上");
                            return;
                        }
                        try {
                            Thread.sleep(80);
                        } catch (InterruptedException ignored) {
                        }
                        CarLifeProbe.sendTouch(1, x, y);
                        onLog("   （如果手机画面没反应，说明 CarLife 那边没认这个报文，把日志发我）");
                    }
                }, "touch-test").start();
            }
        }));
        row4.addView(button("反向监听", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SelfPorts.isListening()) {
                    SelfPorts.closeAll();
                    onLog("★ 已停止反向监听，端口已交还（本机自占端口: " + SelfPorts.heldText() + "）");
                    return;
                }
                onLog("—— 反向监听：占住那 7 个端口，看是不是对方主动来连\"车机\" ——");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        LocalNetDiag.reverseListenNow(uiLogger());
                    }
                }, "reverse-listen").start();
            }
        }));
        adv.addView(row4);

        ListView deviceList = new ListView(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        deviceList.setAdapter(deviceAdapter);
        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BluetoothDevice d = deviceAt(position);
                if (d != null) {
                    selected = d;
                    selectedView.setText("当前选中：" + safeName(d) + " [" + d.getAddress() + "]");
                    fetchUuids(d);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f);
        deviceList.setLayoutParams(lp);
        root.addView(deviceList);

        resultView = new TextView(this);
        resultView.setTextSize(15);
        resultView.setTypeface(Typeface.DEFAULT_BOLD);
        resultView.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.addView(resultView);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#DDDDDD"));
        logView.setBackgroundColor(Color.parseColor("#111111"));
        logView.setPadding(dp(6), dp(6), dp(6), dp(6));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.0f);
        scroll.setLayoutParams(lp2);
        root.addView(scroll);
        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------- 权限

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need.add("android.permission.NEARBY_WIFI_DEVICES");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                }
            }
            onLog(ok ? "权限已授予" : "!! 权限被拒绝，蓝牙功能无法使用");
            if (ok) {
                refreshBonded();
            }
        }
    }

    // -------------------------------------------------------------- 设备

    private void refreshBonded() {
        devices.clear();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            onLog("!! 本机没有蓝牙适配器");
            return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                devices.addAll(bonded);
            }
            onLog("已配对设备 " + devices.size() + " 台");
        } catch (Throwable t) {
            onLog("!! 读取已配对设备失败: " + t);
        }
        rebuildList();
        for (BluetoothDevice d : new ArrayList<>(devices)) {
            fetchUuids(d);
        }
    }

    private void startScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            boolean ok = adapter.startDiscovery();
            onLog(ok ? "开始扫描（约 12 秒，请让车机停在无线 CarPlay 配对界面）" : "!! 扫描启动失败");
            status("扫描中…");
        } catch (Throwable t) {
            onLog("!! 扫描失败: " + t);
        }
    }

    private void fetchUuids(BluetoothDevice d) {
        try {
            d.fetchUuidsWithSdp();
        } catch (Throwable ignored) {
        }
    }

    private void pairSelected() {
        if (selected == null) {
            toast("请先在列表里点选设备");
            return;
        }
        try {
            boolean ok = selected.createBond();
            onLog(ok ? ("发起配对: " + safeName(selected)) : "配对请求未发出（可能已配对）");
        } catch (Throwable t) {
            onLog("!! 配对失败: " + t);
        }
    }

    private void connectSelected() {
        if (selected == null) {
            toast("请先在列表里点选设备");
            return;
        }
        if (session != null && session.isRunning()) {
            toast("已有会话在运行，先点停止");
            return;
        }
        final BluetoothDevice target = selected;
        boolean suspicious = !isCarplay(target) && !nameHint(target);
        if (suspicious) {
            // 用户习惯性点【连接选中】——与其弹窗教育，不如直接转成"逐台找车机"
            toast("这台不像车机，自动改为逐台找车机");
            onLog("你选的 " + safeName(target) + " 不像车机（SDP 里没有 CarPlay 服务），自动切换为【① 自动找车机】");
            autoFind();
            return;
        }
        String msg = "要连接这台设备吗？\n\n" + safeName(target) + "\n" + target.getAddress();
        new AlertDialog.Builder(this)
                .setTitle("确认连接")
                .setMessage(msg)
                .setPositiveButton("连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resultView.setText("");
                        session = new ProbeSession(MainActivity.this, logFile());
                        session.start(target);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void autoFind() {
        if (session != null && session.isRunning()) {
            toast("已有会话在运行，先点停止");
            return;
        }
        // 屏幕录制授权不在这里要 —— 等到真要推流那一刻再弹框（令牌必须"新鲜"，用完即废）
        if (devices.isEmpty()) {
            toast("列表为空，先点【已配对】或【扫描】");
            return;
        }
        resultView.setText("");
        session = new ProbeSession(this, logFile());
        session.startAuto(orderedDevices());
    }

    private void addDevice(BluetoothDevice d) {
        for (BluetoothDevice e : devices) {
            if (e.getAddress().equals(d.getAddress())) {
                return;
            }
        }
        devices.add(d);
        fetchUuids(d);
        rebuildList();
    }

    /** 排序：SDP 带 CarPlay 服务的 > 名字像车机的 > 已配对 > 其他 */
    private List<BluetoothDevice> orderedDevices() {
        List<BluetoothDevice> list = new ArrayList<>(devices);
        Collections.sort(list, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                return Integer.compare(rank(a), rank(b));
            }
        });
        return list;
    }

    private int rank(BluetoothDevice d) {
        if (isCarplay(d)) {
            return 0;
        }
        if (nameHint(d)) {
            return 1;
        }
        try {
            return d.getBondState() == BluetoothDevice.BOND_BONDED ? 2 : 3;
        } catch (Throwable t) {
            return 3;
        }
    }

    private boolean isCarplay(BluetoothDevice d) {
        List<UUID> u = uuidMap.get(d.getAddress());
        return u != null && u.contains(ProbeSession.UUID_ACCESSORY);
    }

    private boolean nameHint(BluetoothDevice d) {
        String n = safeName(d).toLowerCase(Locale.US);
        return n.contains("carplay") || n.contains("chery") || n.contains("奇瑞")
                || n.contains("t1e") || n.contains("desay") || n.contains("德赛")
                || n.contains("cloudrive") || n.contains("carkey") || n.contains("tbox")
                || n.contains("car") || n.contains("sync") || n.contains("hud");
    }

    private BluetoothDevice deviceAt(int position) {
        List<BluetoothDevice> ordered = orderedDevices();
        if (position >= 0 && position < ordered.size()) {
            return ordered.get(position);
        }
        return null;
    }

    private void rebuildList() {
        List<BluetoothDevice> ordered = orderedDevices();
        List<String> rows = new ArrayList<>();
        for (BluetoothDevice d : ordered) {
            StringBuilder sb = new StringBuilder();
            if (isCarplay(d)) {
                sb.append("★ 车机(CarPlay) ");
            } else if (nameHint(d)) {
                sb.append("? 可能是车机 ");
            }
            sb.append(safeName(d)).append("\n").append(d.getAddress());
            try {
                if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                    sb.append("  [已配对]");
                }
            } catch (Throwable ignored) {
            }
            List<UUID> u = uuidMap.get(d.getAddress());
            if (u != null) {
                sb.append("  SDP服务:").append(u.size());
            }
            rows.add(sb.toString());
        }
        deviceAdapter.clear();
        deviceAdapter.addAll(rows);
        logDeviceTable(ordered);
    }

    /** 把整张设备表写进日志（只在"设备集合"变化时写，避免刷屏）——诊断用 */
    private void logDeviceTable(List<BluetoothDevice> ordered) {
        // 去噪：SDP 是异步陆续回来的，按整张表比对会被 SDP 更新反复刷屏
        // （实测一轮 10 台设备把整张表打了 10 遍，有效日志全被挤没了）。
        // 只有"设备集合"变了才重打整张表；SDP 命中 CarPlay 单独报一行。
        StringBuilder sig = new StringBuilder();
        for (BluetoothDevice d : ordered) {
            sig.append(d.getAddress()).append('|');
        }
        if (sig.toString().equals(lastDeviceSignature)) {
            return;
        }
        lastDeviceSignature = sig.toString();

        StringBuilder sb = new StringBuilder("---- 设备表 (").append(ordered.size()).append(" 台) ----\n");
        for (BluetoothDevice d : ordered) {
            sb.append("  ").append(safeName(d)).append("  ").append(d.getAddress());
            try {
                sb.append("  ").append(d.getBondState() == BluetoothDevice.BOND_BONDED ? "已配对" : "未配对");
            } catch (Throwable ignored) {
                sb.append("  状态?");
            }
            List<UUID> u = uuidMap.get(d.getAddress());
            if (u == null) {
                sb.append("  SDP:未知");
            } else if (u.contains(ProbeSession.UUID_ACCESSORY)) {
                sb.append("  SDP:★CarPlay(").append(u.size()).append(")");
            } else {
                sb.append("  SDP:").append(u.size()).append("个(无CarPlay)");
            }
            if (nameHint(d)) {
                sb.append("  名字像车机");
            }
            sb.append('\n');
        }
        String table = sb.toString();
        if (!table.equals(lastDeviceTable)) {
            lastDeviceTable = table;
            onLog("---- 设备表 ----");
            for (String line : table.split("\n")) {
                if (!line.trim().isEmpty()) {
                    onLog(line);
                }
            }
        }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(无名)" : n;
        } catch (Throwable t) {
            return "(需权限)";
        }
    }

    private String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED: return "已配对";
            case BluetoothDevice.BOND_BONDING: return "配对中";
            case BluetoothDevice.BOND_NONE: return "未配对";
            default: return String.valueOf(state);
        }
    }

    // -------------------------------------------------------------- 日志

    private File logFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        return new File(dir, "cplink.log");
    }

    /**
     * 日志：**先落文件，再节流刷 UI**。
     *
     * v3.4 及以前的写法是"每来一行就 runOnUiThread + setText(整个 buffer)"，
     * 日志一多（v3.3 那轮每秒几百行、buffer 上到 4000 行 ≈ 200KB）就把主线程塞满 ——
     * 表现就是**点完按钮界面卡死/黑屏**（消息队列堆着一大堆 setText 排不出去）。
     * 现在：① 先写文件（就算 UI 卡住日志也不丢 —— CarLife 这条线的日志以前只进内存）；
     * ② 内存按字符裁剪；③ UI 每 200ms 最多刷一次，且只渲染最后 400 行。
     */
    @Override
    public void onLog(final String line) {
        appendLogFile(line);
        synchronized (logBuffer) {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > LOG_MAX_CHARS) {
                logBuffer.delete(0, logBuffer.length() - LOG_KEEP_CHARS);
            }
        }
        if (uiPending.compareAndSet(false, true)) {
            logView.postDelayed(uiRefresh, 200);
        }
    }

    /** 只渲染最后 LOG_TAIL_LINES 行（在 UI 线程跑） */
    private void renderLogTail() {
        String s;
        synchronized (logBuffer) {
            s = logBuffer.toString();
        }
        int start = 0;
        int lines = 0;
        for (int i = s.length() - 1; i >= 0 && lines < LOG_TAIL_LINES; i--) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
            start = i;
        }
        if (start > 0) {
            logView.setText("（只显示最后 " + LOG_TAIL_LINES + " 行；完整日志见日志文件或【分享日志】）\n"
                    + s.substring(start));
        } else {
            logView.setText(s);
        }
    }

    /** 每行都追加写日志文件。ProbeSession 也写同一个文件，都是 append，不冲突。 */
    private void appendLogFile(String line) {
        try {
            File f = logFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                fos.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读日志文件尾部（分享/复制用：文件里是整轮日志，含卡死前只进过内存的行） */
    private String readLogFileTail(int maxChars) {
        try {
            File f = logFile();
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            int n = (int) Math.min(f.length(), maxChars);
            byte[] buf = new byte[n];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(f.length() - n);
                raf.readFully(buf);
            }
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status(s);
            }
        });
    }

    @Override
    public void onWifi(final String ssid, final String pass, final int security, final int channel) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultView.setTextColor(Color.parseColor("#00AA00"));
                resultView.setText("★ 车机热点已拿到\nSSID: " + ssid + "\n密码: " + pass
                        + "\n加密类型: " + security + "  信道: " + channel
                        + "\n\n（P0 目标达成：iAP2 鉴权 + 取 Wi-Fi 凭据成功）");
                toast("成功拿到车机 Wi-Fi 凭据！");
            }
        });
        lastSsid = ssid;
        lastPass = pass;
        // DiPlay 每 4 秒重发一次 0x5703：正常 12 秒节流；上次失败则立刻允许重试
        long now = System.currentTimeMillis();
        if (now - lastP1At < 12000) {
            onLog("（凭据重复：P1 刚跑过，跳过本次）");
            return;
        }
        lastP1At = now;
        onLog("—— P0 完成，进入 P1：连车机热点 + 打进 AirPlay 端口 ——");
        startAirPlayProbe(ssid, pass);
    }

    /** P1 第一步：连热点 → 找网关 → 对 7000 端口做 OPTIONS / GET /info */
    private void startAirPlayProbe(final String ssid, final String pass) {
        final AirPlayProbe.Logger logger = new AirPlayProbe.Logger() {
            @Override
            public void log(final String line) {
                onLog(line);
                if (line.startsWith("★ TCP 已连上")) {
                    status("P1：已连上车机 AirPlay 端口，正在握手");
                } else if (line.startsWith("!!")) {
                    status("P1 出错，详见日志");
                }
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    android.net.Network n = AirPlayProbe.joinHotspot(
                            MainActivity.this, ssid, pass, logger, false);
                    if (n == null) {
                        return;
                    }
                    String mine = AirPlayProbe.ownAddresses(MainActivity.this, n);
                    onLog("本机在车机热点里的地址: " + mine);
                    String gw = AirPlayProbe.gatewayOf(MainActivity.this, n);
                    onLog("网关地址: " + (gw == null ? "未知" : gw) + "（只当线索用，真正靠发现）");
                    ok = AirPlayProbe.rtspProbe(MainActivity.this, n, gw, 7000, logger);
                    onLog("—— P1 第一步结束 ——");
                } catch (Throwable t) {
                    onLog("!! P1 探测异常: " + t);
                } finally {
                    if (!ok) {
                        // 失败就放开节流：车机每 4 秒重发凭据，下一轮立刻重试（最多连试 4 轮）
                        p1Fails++;
                        if (p1Fails <= 4) {
                            lastP1At = 0;
                            onLog("（本轮 P1 未完成，第 " + p1Fails + " 次；下次凭据到达立即重试）");
                        } else {
                            onLog("（已连续失败 " + p1Fails + " 次，改回 12 秒节流）");
                        }
                    } else {
                        p1Fails = 0;
                    }
                }
            }
        }, "airplay-probe").start();
    }

    @Override
    public void onFinished(final String reason) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status("结束：" + reason);
            }
        });
    }

    // ------------------------------------------------------------ 单飞锁 / 日志工具

    /**
     * 长任务单飞：同时点两个按钮会让两轮流程的日志互相穿插（v3.3 的日志就是这么变成一团乱麻的），
     * 也会让"端口在听"这类判据互相污染。拿不到锁就明确告诉用户，而不是悄悄再跑一轮。
     */
    private boolean beginTask(String name) {
        synchronized (MainActivity.class) {
            if (busy) {
                toast("上一轮还在跑：" + busyName);
                onLog("（本次点击已忽略：上一轮「" + busyName + "」还没结束，同时跑两轮日志会互相穿插）");
                return false;
            }
            busy = true;
            busyName = name;
            return true;
        }
    }

    private void endTask() {
        synchronized (MainActivity.class) {
            busy = false;
            busyName = "";
        }
    }

    private AirPlayProbe.Logger uiLogger() {
        return new AirPlayProbe.Logger() {
            @Override
            public void log(String line) {
                onLog(line);
            }
        };
    }

    /** 起一轮"当车机"握手（③ 与 ② 命中后共用）：不占端口、不做诊断，很快 */
    private void probeNow() {
        try {
            // ★ v3.11：CarLife 只允许一个"车机"。上一轮还活着时直接开新一轮，
            // 对端会把**新旧两套连接一起 reset**（v3.10 实测：③ 在自动会话还活着时被点了一下，
            // 结果 7 条通道全被掐，前面跑通的握手白费）。所以这里先关、再多等一会儿。
            if (carLifeProbe != null) {
                onLog("   （上一轮 CarLife 会话还活着 → 先关掉它，等 2.5 秒让对端回收，再开新的）");
                carLifeProbe.close();
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                }
            }
            carLifeProbe = new CarLifeProbe(uiLogger());
            carLifeProbe.run(LocalNetDiag.allLocalHosts());
        } catch (Throwable t) {
            onLog("!! 接握手异常: " + t);
        }
    }

    /** 按组件拉起界面（可能从后台线程调用；拉不起来返回 false 并写日志） */
    private boolean launchComponent(String pkg, String act) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new android.content.ComponentName(pkg, act));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            onLog("   （拉不起来: " + t + "）");
            return false;
        }
    }

    private static String friendly(String pkg) {
        if ("com.baidu.carlife.oppo".equals(pkg)) {
            return "CarLife+（百度 CarLife 手机端）";
        }
        if ("com.oplus.ocar".equals(pkg)) {
            return "Car+ 车联";
        }
        return pkg;
    }

    /** 请求屏幕录制权限（投屏内容源）；拿不到也不影响协议流程 */
    private void requestScreenCapture() {
        startMirrorService();
        if (ScreenCapture.hasFreshToken()) {
            onLog("（已有屏幕录制授权，直接复用）");
            return;
        }
        onLog("—— 请在弹出的系统框里点【立即开始】允许屏幕录制（投屏要把手机屏幕发给车机）——");
        onLog("   " + CarPlusUi.recordHint());
        ScreenCapture.requestConsent(this);
    }

    private void startMirrorService() {
        try {
            Intent i = new Intent(this, MirrorService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            onLog("!! 启动前台服务失败（Android 14 拿 MediaProjection 需要它）: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ScreenCapture.REQ_CAPTURE) {
            return;
        }
        ScreenCapture.onResult(resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            startMirrorService();
            onLog("★ 屏幕录制权限已拿到（前台服务: " + MirrorService.lastStatus + "）");
            status("已允许屏幕录制，投屏将显示手机屏幕");
        } else {
            onLog("!! 你拒绝了屏幕录制 —— 投屏只能推测试图案（协议流程不受影响）。"
                    + "想再来一次：点【授权录屏】");
        }
    }

    private void status(String s) {
        statusView.setText(s);
    }

    private void copyLog() {
        String body = readLogFileTail(200000);
        if (body == null) {
            synchronized (logBuffer) {
                body = logBuffer.toString();
            }
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("cplink", body));
            toast("日志已复制（" + body.length() + " 字）");
        }
    }

    private void shareLog() {
        String body = readLogFileTail(200000);
        if (body == null) {
            synchronized (logBuffer) {
                body = logBuffer.toString();
            }
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "CPLink 探测日志 "
                + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()));
        i.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "分享日志"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_UUID);
        if (Build.VERSION.SDK_INT >= 33) {
            // 这些是系统广播（蓝牙栈发的），必须 EXPORTED 才收得到；
            // 用 NOT_EXPORTED 会导致 ACTION_UUID 永远不回来（实测踩过）。
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        // ★ v1.2：一键的第一段（拉起 CarLife+）是在前台做的，第二段必须等**用户切回本 App**
        // 再做 —— 因为 Android 10+ 不许后台 App 启动别的 Activity（startActivity 会被静默丢掉）。
        if (pendingOneKey && !oneKeyRunning) {
            pendingOneKey = false;
            onLog("   （已回到本 App → 接着做一键的第二段）");
            runOneKeyRest();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.stop();
        }
    }
}

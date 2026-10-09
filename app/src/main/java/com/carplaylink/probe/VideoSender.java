package com.carplaylink.probe;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Network;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频数据通道（AirPlay 镜像流 type=110）。
 *
 * 线上格式（与 DiPlay 的 ScreenStream 对应）：
 *   [128 字节头][body]
 *   头：[0..3] = body 长度（小端），[4] = opcode（0=视频帧，1=视频配置），其余补零
 *   opcode=1：明文 avcC（MP4 的 avcC box：4 字节长度 + "avcC" + 记录）
 *   opcode=0：ChaCha20-Poly1305 密文+tag，nonce = 4 个零字节 + 8 字节小端帧号，AAD = 那 128 字节头
 *             注意头里的长度必须是**密文长度**（明文+16），因为接收端按它读线上字节、且它参与 AAD
 *   帧内 NAL：4 字节大端长度前缀（接收端自己转 Annex B）
 *   密钥：HKDF(shared, "DataStream-Salt"+streamConnectionID, "DataStream-Output-Encryption-Key", 32)
 */
public final class VideoSender {

    /** 外部（停止按钮）置 true 就停止推流 */
    public static volatile boolean stopRequested;

    /**
     * 投流真正开始时的回调（只回调一次）。MainActivity 用它把 Car+ / CarLife+ 摆到前台 ——
     * 整屏投屏已经能通，唯一缺的就是「手机上显示的是 Car+ 而不是我们自己的 App」。
     */
    public static volatile Runnable streamingHook;

    /** 编码尺寸（由 resolveSize 按手机真实分辨率算出来，SETUP 也要用它） */
    public static volatile int WIDTH = 1280;
    public static volatile int HEIGHT = 720;

    // ================= CarLife → CarPlay 直通 =================
    /**
     * 直通模式：**不再用手机屏幕镜像当内容源**，改成把 CarLife 推来的 H.264 帧
     * 直接转进 CarPlay 视频流。
     *
     * 为什么这是对的做法：
     *  - 手机侧 CarLife+ 是**系统组件**，它抓屏不受 FLAG_SECURE 限制（Car+ 的车机界面它抓得到），
     *    而我们的 MediaProjection 抓不到 —— 所以走它这条流反而能拿到 Car+ 的画面；
     *  - 不再需要录屏授权，也不会有"受保护窗口把录屏掐掉"的问题；
     *  - 不需要解码/重编码：CarLife 推的就是 Annex-B H.264，而 {@link #sendFrame} 本来就吃 Annex-B。
     */
    public static volatile boolean carLifePassthrough;

    /**
     * 允不允许用 CarLife 直通（用户可切）。
     *
     * ★ v0.5：**默认关**。实测（读 CarLife+ 8.6.8 的包）Car+ 那套投屏叫 "Mix"，
     * 要 `MixAuthorization` 拿百度账号（bduss）去服务器激活，未激活返回 `box_not_activate`，
     * CarLife+ 只能退回**它自己的镜像**（内容是 CarLife+ 的界面，不是 Car+）。
     * 所以想看到 Car+，得走**我们自己的整屏镜像 + 把 Car+ 钉在最前台**。
     * 直通留着当备选：它的好处是触摸能直达 CarLife+（通道 6），镜像那条触摸到不了 Car+。
     */
    public static volatile boolean carLifePassthroughAllowed = true;   // ★ v1.0：默认就是【CarLife直通】（车机界面 + 触摸）

    /**
     * ★ v0.6：Car+ 投屏模式 —— 编码器的输入 Surface **直接交给 Car+**（`prepareCast`），
     * Car+ 把它的车机界面渲染进来，我们只负责编码 + 走 CarPlay 上车。
     *
     * 为什么这是正路（实测得到）：
     *   - Car+ 的 cast 服务（`com.oplus.ocar/…CarlifeCastManagerService`，
     *     AIDL `com.baidu.carlife.mixing.aidl.ICarLifeCastManager`）现在**绑得上**
     *     （v3.2 时还是 bindService=false），`setCastCallback` 也返回 true；
     *   - `prepareCast(CastConfig{width,height,dpi,Surface})` 是**我们给它一块 Surface**，
     *     Car+ 往里面画 —— **不需要 CarLife、不需要 Mix 授权、也不需要录屏授权**；
     *   - 它还有 `addTouchListener(IRemoteTouchListener)`，车机触摸可以直达 Car+。
     */
    public static volatile boolean carPlusCastMode;

    /** 编码器输入 Surface（Car+ 投屏模式下由 CarPlusCast 拿去 prepareCast） */
    public static volatile android.view.Surface encoderInputSurface;

    /**
     * 最近一帧 IDR（关键帧）。
     *
     * ★ v0.3：车机会通过 event 通道发 `type=forceKeyFrame` 要关键帧（比如它的解码器刚重置、
     * 或画面从黑屏里出来）。一个真 iPhone 收到就会立刻编码一个 IDR 推过去。
     * 我们这边 CarLife 的编码器不受我们控制，没法"立刻"产 IDR ——
     * 但可以把**最近一个 I 帧**重发一遍（连 SPS/PPS 一起），解码器就能重新对齐。
     */
    private static volatile byte[] lastKeyFrameAu;
    private static volatile boolean keyFrameRequested;

    /** 车机要关键帧（event 通道 type=forceKeyFrame）→ 让推流循环重发一次 */
    public static void requestKeyFrame() {
        keyFrameRequested = true;
    }

    /** 一帧 Annex-B 里有没有 IDR（NAL 类型 5） */
    private static boolean isKeyFrame(byte[] au) {
        for (int i = 0; i + 4 < au.length; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && (au[i + 2] == 1 || (au[i + 2] == 0 && au[i + 3] == 1))) {
                int off = (au[i + 2] == 1) ? i + 3 : i + 4;
                if (off < au.length && (au[off] & 0x1f) == 5) {
                    return true;
                }
            }
        }
        return false;
    }
    /** 待转发的 CarLife 帧（CarLifeProbe 收到就往里放） */
    private static final java.util.concurrent.LinkedBlockingQueue<byte[]> CARLIFE_Q =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private static volatile boolean carLifeConfigSent;
    // ★ v1.6：CarLife 的**参数集和 I 帧要在收到帧的时候就缓存**。
    // 为什么：CarLife 的 SPS/PPS 只出现在**第一帧**里（`000000016742801e…`），
    // 而那一帧往往在我们起 CarPlay 流之前就过去了 —— 队列只留最新 120 帧，早就把它挤掉，
    // 于是 `extractParamSets` 永远返回 null、**一条 VideoConfig 都发不出去**，
    // 车机（DiPlay）的 `onConfig()` 就不会被调用、解码器没配置 → **黑屏**。
    private static volatile byte[] carLifeSps;
    private static volatile byte[] carLifePps;
    private static volatile byte[] carLifeIdr;
    /** ★ v2.1：静态方法（feedCarLife）里也要能打日志 */
    static AirPlayProbe.Logger LOG;
    /** ★ v2.1：画面活动度统计（CarLife 推来的帧大小分布） */
    private static long carLifeFrameSum;
    private static int carLifeFrameCount;
    private static int carLifeFrameMin = -1;
    private static int carLifeFrameMax;
    /** 镜像→直通 的切换是否已经处理过（切换时要补发 CarLife 的参数集 + I 帧） */
    private static volatile boolean passthroughSwitchDone;

    /**
     * CarLife 自报的视频尺寸（`65544` 里的 f1/f2）。
     *
     * 为什么重要：CarPlay 这条流的分辨率是在 **SETUP 那一刻定死**的（我们原来按手机屏幕报 872x1920），
     * 而 CarLife 推的是 1280x720 —— 车机按 872x1920 解码 1280x720 的画面就会**拉伸变形**。
     * 所以：**在点 ① 之前**先 ②→③ 拿到 CarLife 的尺寸，① 就会按这个尺寸开流，车机不用拉伸。
     */
    public static volatile int carLifeW;
    public static volatile int carLifeH;
    /** CarPlay 视频流是否已经在推（用来判断"尺寸已经定死了"） */
    public static volatile boolean streamLive;

    public static void setCarLifeSize(int w, int h, AirPlayProbe.Logger log) {
        if (w <= 0 || h <= 0) {
            return;
        }
        carLifeW = w;
        carLifeH = h;
        log.log("   （记下 CarLife 的画面尺寸: " + w + "x" + h + "）");
        if (streamLive && (WIDTH != w || HEIGHT != h)) {
            log.log("   ⚠ **尺寸不匹配**：CarPlay 这条流已经按 " + WIDTH + "x" + HEIGHT
                    + " 开好了，而 CarLife 是 " + w + "x" + h + " → 车机上画面会被拉伸。");
            log.log("      想要正确比例：**先点 ②、再点 ③（等收到 CarLife 视频帧），最后才点 ①** —— "
                    + "这样 CarPlay 就会按 CarLife 的尺寸开流。");
        }
    }

    /** CarLife 侧收到一个视频访问单元（Annex-B）时调用 */
    /** 新一轮 CarLife 会话开始时清掉上一轮的缓存（尺寸/参数集可能变了） */
    public static void resetCarLifeStream() {
        carLifeSps = null;
        carLifePps = null;
        carLifeIdr = null;
        carLifeConfigSent = false;
        lastKeyFrameAu = null;
    }

    public static void feedCarLife(byte[] annexBAu) {
        if (!carLifePassthrough || annexBAu == null || annexBAu.length == 0) {
            return;
        }
        // ★ v2.1：画面活动度 —— CarLife+ 有时推的是**一张静止画面**（每帧几百字节、帧头一模一样），
        // 车机上就"卡在某一屏"。每 100 帧报一次 平均/最小/最大，静止时直接写出来，省得靠猜。
        if (LOG == null) {
            return;
        }
        carLifeFrameSum += annexBAu.length;
        carLifeFrameCount++;
        if (carLifeFrameMin < 0 || annexBAu.length < carLifeFrameMin) {
            carLifeFrameMin = annexBAu.length;
        }
        if (annexBAu.length > carLifeFrameMax) {
            carLifeFrameMax = annexBAu.length;
        }
        if (carLifeFrameCount % 100 == 0) {
            long avg = carLifeFrameSum / carLifeFrameCount;
            LOG.log("   （CarLife 画面活动度：最近 " + carLifeFrameCount + " 帧 平均 " + avg
                    + " 字节 / 最小 " + carLifeFrameMin + " / 最大 " + carLifeFrameMax
                    + (avg < 500 && carLifeFrameMax - carLifeFrameMin < 200
                       ? " —— **画面基本静止**（CarLife+ 推的是一张不动的画面）" : "）"));
            carLifeFrameSum = 0;
            carLifeFrameCount = 0;
            carLifeFrameMin = -1;
            carLifeFrameMax = 0;
        }
        // ★ v1.6：先缓存参数集 / 最近的 I 帧（见上面 carLifeSps 的注释）——
        // 起流时要立刻把 VideoConfig + 一个 I 帧发给车机，否则解码器起不来（黑屏）。
        try {
            if (carLifeSps == null || carLifePps == null || isKeyFrame(annexBAu)) {
                byte[][] sp = extractParamSets(annexBAu);
                if (sp != null) {
                    carLifeSps = sp[0];
                    carLifePps = sp[1];
                }
            }
            if (isKeyFrame(annexBAu)) {
                carLifeIdr = annexBAu;
            }
        } catch (Throwable ignored) {
            // 缓存失败不影响转发
        }
        while (CARLIFE_Q.size() > 120) {
            CARLIFE_Q.poll();   // 丢最旧的，别把内存堆爆
        }
        CARLIFE_Q.offer(annexBAu);
    }

    /** 从 Annex-B 里挑出 SPS(7)/PPS(8)，用来给车机发一份 CarLife 自己的 VideoConfig */
    private static byte[][] extractParamSets(byte[] au) {
        byte[] sps = null;
        byte[] pps = null;
        int i = 0;
        while (i + 3 < au.length) {
            int sc = -1;
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) {
                sc = i + 3;
            } else if (i + 4 < au.length && au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                sc = i + 4;
            }
            if (sc < 0) {
                i++;
                continue;
            }
            int type = au[sc] & 0x1F;
            int end = au.length;
            for (int j = sc + 1; j + 2 < au.length; j++) {
                if (au[j] == 0 && au[j + 1] == 0 && (au[j + 2] == 1
                        || (j + 3 < au.length && au[j + 2] == 0 && au[j + 3] == 1))) {
                    end = j;
                    break;
                }
            }
            if (type == 7 && sps == null) {
                sps = java.util.Arrays.copyOfRange(au, sc, end);
            } else if (type == 8 && pps == null) {
                pps = java.util.Arrays.copyOfRange(au, sc, end);
            }
            i = end;
        }
        return (sps != null && pps != null) ? new byte[][]{sps, pps} : null;
    }

    /**
     * 屏幕投影是否还活着（MediaProjection 被系统撤销时置 false）。
     *
     * MainActivity 用它做一件事：把 Car+ 的界面**逐个**拉起来，每拉一个等 2 秒看投影还在不在 ——
     * 还活着说明这个界面能录（不是受保护窗口），就停在那儿；被掐掉就说明那个界面是
     * FLAG_SECURE，记下来换下一个。
     */
    public static volatile boolean projectionAlive;

    /** 按手机真实屏幕分辨率定编码尺寸：等比缩到长边 ≤ 1920，且宽高都是偶数（H.264 要求） */
    public static void resolveSize(android.content.Context ctx, AirPlayProbe.Logger log) {
        // ★ 优先用 CarLife 自报的尺寸 —— 直通模式下 CarPlay 流里跑的就是 CarLife 的画面，
        //   流的分辨率必须跟它一致，否则车机按另一个尺寸解码就会拉伸。
        if (carLifeW > 0 && carLifeH > 0) {
            WIDTH = carLifeW - (carLifeW % 2);
            HEIGHT = carLifeH - (carLifeH % 2);
            log.log("   编码尺寸按 **CarLife 报的** 定为 " + WIDTH + "x" + HEIGHT
                    + "（这样车机不用拉伸；直通模式下流里就是 CarLife 的画面）");
            return;
        }
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            if (w <= 0 || h <= 0) {
                return;
            }
            int longSide = Math.max(w, h);
            if (longSide > 1920) {
                double k = 1920.0 / longSide;
                w = (int) Math.round(w * k);
                h = (int) Math.round(h * k);
            }
            w -= (w % 2);
            h -= (h % 2);
            if (w >= 64 && h >= 64) {
                WIDTH = w;
                HEIGHT = h;
                log.log("   编码尺寸按手机真实屏幕定为 " + WIDTH + "x" + HEIGHT);
            }
        } catch (Throwable t) {
            log.log("   （取手机分辨率失败，沿用 " + WIDTH + "x" + HEIGHT + "）");
        }
    }

    private static final int OP_VIDEO_FRAME = 0;
    private static final int OP_VIDEO_CONFIG = 1;
    private static final int HEADER_LEN = 128;
    private static final int TAG_LEN = 16;

    private final android.content.Context ctx;
    private final Network network;
    private final String host;
    private final int dataPort;
    private final long connectionId;
    private final AirPlayProbe.Logger log;

    private Socket sock;
    private OutputStream out;
    private MediaCodec codec;
    private Surface input;
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private boolean mirrored;
    private byte[] key;
    private byte[] sps;
    private byte[] pps;
    private long frameCounter;
    private int drawnFrames;
    private boolean passthroughHintLogged;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final List<byte[]> readyAccessUnits = new ArrayList<>();

    public VideoSender(android.content.Context ctx, Network network, String host, int dataPort,
                       long connectionId, AirPlayProbe.Logger log) {
        this.ctx = ctx;
        this.network = network;
        this.host = host;
        this.dataPort = dataPort;
        this.connectionId = connectionId;
        this.log = log;
    }

    public boolean run() {
        stopRequested = false;
        LOG = log;
        // ★ v2.1：这里只能**清队列**，不能 stop() —— 音频线程是 AirPlayProbe 在 SETUP 之后
        // 刚启动的，run() 这时才跑到，stop() 会把它当场关掉（v2.0 的 `音频流结束：共发 0 包`）。
        AudioSender.resetQueue();
        // ★ v1.3：每次起流都把"直通配置已发/缓存的 I 帧"清干净 —— 否则上一轮留下的
        // 标记会让这一轮的 CarLife SPS/PPS 发不出去（车机就解不了 CarLife 的帧）。
        carLifeConfigSent = false;
        lastKeyFrameAu = null;
        passthroughHintLogged = false;
        passthroughSwitchDone = false;
        try {
            key = Crypto.hkdfSha512(Pairing.sharedSecret,
                    Crypto.ascii("DataStream-Salt" + connectionId),
                    Crypto.ascii("DataStream-Output-Encryption-Key"), 32);
            log.log("   视频流密钥 = HKDF(shared, \"DataStream-Salt" + connectionId
                    + "\", \"DataStream-Output-Encryption-Key\") = " + Crypto.hex(key, 8) + "…");

            List<byte[]> first = new ArrayList<byte[]>();

            // ★ v1.3：**CarLife 已经在推流 → 整条流直接按直通开**，不启动录屏、不启动镜像编码器。
            //
            // 为什么必须这样（v1.2 的日志就是这个坑）：以前不管怎样都先按"镜像"开流，
            // 车机第一次要关键帧（event#1）时 `lastKeyFrameAu` 里还是**镜像流的 I 帧** ——
            // 于是我们把**镜像的 SPS/PPS** 当成"CarLife 的配置"发出去，还把 `carLifeConfigSent`
            // 置成 true → **CarLife 自己的 SPS/PPS 永远发不出去** → 车机拿镜像的参数集去解
            // CarLife 的帧，解不出来就一直显示上一帧（现象：车机上还是整个手机屏，
            // 而且车机反复要关键帧，一次会话能要十几次）。
            boolean passthroughFromStart = carLifePassthroughAllowed && CarLifeProbe.isStreaming();
            if (passthroughFromStart) {
                carLifePassthrough = true;
                lastKeyFrameAu = null;
                carLifeConfigSent = false;
                mirrored = false;
                log.log("★ **CarLife 直通模式**：CarLife+ 已经在推流 → 这条 CarPlay 流**直接承载它的画面**"
                        + "（不启动录屏、不启动镜像编码器）");
                if (!connectDataPort()) {
                    return false;
                }
                streamLive = true;
                // ★ v1.6：**先把 CarLife 的 VideoConfig 和一个 I 帧发出去**，解码器才起得来。
                // （DiPlay 收到 VideoConfig 才会 onConfig() 去配解码器；只发帧它会一直黑屏。）
                if (carLifeSps != null && carLifePps != null) {
                    try {
                        send(OP_VIDEO_CONFIG, avcCBox(carLifeSps, carLifePps), false);
                        carLifeConfigSent = true;
                        log.log("★ 已发 CarLife 自己的 VideoConfig（SPS " + carLifeSps.length
                                + " 字节 / PPS " + carLifePps.length + " 字节，明文）");
                    } catch (Throwable t) {
                        log.log("   （发 CarLife VideoConfig 失败: " + t + "）");
                    }
                } else {
                    log.log("   （还没见过 CarLife 的 SPS/PPS —— VideoConfig 等它的关键帧到了再发）");
                    // ★ v2.2：我们是在它已经推流之后才起流的，SPS/PPS 只在第一帧里、早过去了。
                    // 不主动要一次，车机就永远拿不到 VideoConfig → 一直停在旧画面（实测就是"卡在某一屏"）。
                    CarLifeProbe.requestEncoderReset("起流时手上没有它的参数集");
                }
                if (carLifeIdr != null) {
                    try {
                        sendFrame(carLifeIdr);
                        log.log("★ 已补发 CarLife 最近一个 I 帧（" + carLifeIdr.length
                                + " 字节）—— 车机解码器从这里开始出画面");
                    } catch (Throwable t) {
                        log.log("   （补发 I 帧失败: " + t + "）");
                    }
                }
                passthroughSwitchDone = true;
                log.log("★ 开始持续推流：CarLife 直通 → 车机（点【停止】结束）");
                if (streamingHook != null) {
                    try {
                        streamingHook.run();
                    } catch (Throwable t) {
                        log.log("   （前台切换回调失败，不影响推流: " + t + "）");
                    }
                }
            } else {
            if (!startEncoder()) {
                return false;
            }
            startContentSource();
            if (!connectDataPort()) {
                return false;
            }
            streamLive = true;

            // 1) 逼出 SPS/PPS 和第一个关键帧：
            //    真屏幕镜像最多等 8 秒；一直没帧就说明 MediaProjection 没往编码器送帧，
            //    自动退回测试图案（保证每次都有画面，日志也能看出是哪条路出的帧）。
            // ★ v0.6：Car+ 投屏模式下 Car+ 要等我们 prepareCast 之后才会出画面，
            // 所以等久一点（25 秒），而且**不能**因为"没帧"就放弃整条流。
            long waitUntil = System.currentTimeMillis() + (carPlusCastMode ? 25000 : 8000);
            long lastLog = 0;
            while (System.currentTimeMillis() < waitUntil && (sps == null || readyAccessUnits.isEmpty())) {
                if (!mirrored) {
                    drawFrame();
                }
                drain(500);
                if (mirrored && System.currentTimeMillis() - lastLog > 2500) {
                    lastLog = System.currentTimeMillis();
                    log.log(carPlusCastMode
                            ? "   还在等 Car+ 出画面…（Car+ 要先被绑上并 prepareCast 才会渲染）"
                            : ("   还在等真实屏幕出帧…（已等 "
                            + ((System.currentTimeMillis() - (waitUntil - 8000)) / 1000) + " 秒）"));
                }
            }
            if (mirrored && !carPlusCastMode && (sps == null || readyAccessUnits.isEmpty())) {
                log.log("!! 真实屏幕 8 秒没出帧 —— 退回测试图案（说明 MediaProjection 没把画面送进编码器）");
                releaseVirtualDisplay();
                mirrored = false;
                for (int i = 0; i < 25 && (sps == null || readyAccessUnits.isEmpty()); i++) {
                    drawFrame();
                    drain(400);
                }
            }
            if (carPlusCastMode) {
                // Car+ 模式：编码器要等 Car+ 渲染才有帧，这里**不放弃** ——
                // 继续往下走，主循环会一直等（Car+ 一出画面就自动开始推）。
                log.log("   （Car+ 还没出画面 —— 不放弃，继续等；Car+ 一开始渲染就自动推流）");
            } else {
                if (sps == null || pps == null) {
                    log.log("!! 编码器没吐 SPS/PPS，视频推不了");
                    return false;
                }
                if (readyAccessUnits.isEmpty()) {
                    log.log("!! 没拿到任何编码帧");
                    return false;
                }
            }

            // 2) VideoConfig（明文）—— 还没有 SPS/PPS 就跳过（Car+ 模式常见）
            if (sps != null && pps != null) {
                byte[] configBody = avcCBox(sps, pps);
                send(OP_VIDEO_CONFIG, configBody, false);
                log.log("   已发 VideoConfig（avcC " + configBody.length + " 字节，明文）");
            } else {
                log.log("   （还没有 SPS/PPS，VideoConfig 等第一帧出来再发）");
            }

            // 3) 关键帧（加密）
            first = new ArrayList<>(readyAccessUnits);
            readyAccessUnits.clear();
            for (byte[] au : first) {
                sendFrame(au);
            }
            log.log("★ 已把 " + first.size() + " 个编码帧推给车机（第一个关键帧 + 参数集）");

            // 4) 持续推流，直到用户点【停止】（最长 10 分钟兜底）
            log.log(mirrored
                    ? "★ 开始持续推流：手机真实屏幕 → 车机（点【停止】结束）"
                    : "★ 开始持续推测试图案（点【停止】结束）");
            if (mirrored && streamingHook != null) {
                try {
                    streamingHook.run();
                } catch (Throwable t) {
                    log.log("   （前台切换回调失败，不影响推流: " + t + "）");
                }
            }
            }   // ← v1.3：镜像/测试图案那条路的收尾（直通模式在上面那个分支里已经起好流了）
            // ★ v3.17：原来是 10 分钟兜底 —— 到点就自己把投屏停了，用户看到的就是"不稳定/会自己断"。
            // 改成 6 小时（真正停只有两条路：点【停止】或进程被杀）。
            long endAt = System.currentTimeMillis() + 6 * 60 * 60 * 1000L;
            int pushed = 0;
            while (!stopRequested && System.currentTimeMillis() < endAt) {
                // ★ v0.4：车机要关键帧（event 通道 forceKeyFrame）—— **两条路都要答**。
                // 实测车机会连要 5 次（CSeq 1..5），说明它的解码器一直没拿到可用画面
                // （现象就是车机黑屏/冻住）。真 iPhone 收到就立刻编一个 IDR，我们必须照做。
                if (keyFrameRequested) {
                    keyFrameRequested = false;
                    if (carLifePassthrough) {
                        // 直通：CarLife 的编码器不归我们管，只能把**最近一个 I 帧**重发一遍。
                        // ★ v1.7：优先用 **CarLife 自己的** I 帧（carLifeIdr）——
                        // lastKeyFrameAu 在"先镜像后直通"的场景里可能是**镜像的** I 帧，
                        // 拿它去答车机等于又把镜像的参数集喂过去（老 bug 的根源）。
                        byte[] kf = (carLifeIdr != null) ? carLifeIdr : lastKeyFrameAu;
                        if (kf != null) {
                            try {
                                byte[][] sp = extractParamSets(kf);
                                if (sp != null) {
                                    send(OP_VIDEO_CONFIG, avcCBox(sp[0], sp[1]), false);
                                    carLifeConfigSent = true;
                                }
                                sendFrame(kf);
                                log.log("   ★ 车机要 keyframe → 已重发 SPS/PPS + 最近一个 I 帧（"
                                        + kf.length + " 字节）");
                            } catch (Throwable t) {
                                log.log("   （重发 I 帧失败: " + t + "）");
                            }
                        } else {
                            log.log("   ★ 车机要 keyframe，但还没见过 I 帧 —— 等下一个");
                        }
                    } else if (codec != null) {
                        // 镜像：编码器是我们自己的 → 直接让它立刻出一个 I 帧（这才是"真答案"）
                        try {
                            android.os.Bundle b = new android.os.Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            codec.setParameters(b);
                            log.log("   ★ 车机要 keyframe → 已让编码器立刻出一个 I 帧");
                        } catch (Throwable t) {
                            log.log("   （让编码器出 I 帧失败: " + t + "）");
                        }
                    }
                }
                if (carLifePassthrough) {
                    // ★ v1.7：**从镜像切到直通的那一瞬间**，必须重新发一份 CarLife 自己的
                    // VideoConfig + 一个 I 帧 —— 否则车机的解码器还在按镜像的参数集解，
                    // 表现就是"停在手机屏那一帧 / 花屏"。
                    if (!passthroughSwitchDone) {
                        passthroughSwitchDone = true;
                        carLifeConfigSent = false;
                        lastKeyFrameAu = null;
                        log.log("★ 已切到 CarLife 直通 —— 先补发 CarLife 自己的参数集，再推它的帧");
                        if (carLifeSps != null && carLifePps != null) {
                            try {
                                send(OP_VIDEO_CONFIG, avcCBox(carLifeSps, carLifePps), false);
                                carLifeConfigSent = true;
                                log.log("★ 已发 CarLife 自己的 VideoConfig（SPS " + carLifeSps.length
                                        + " 字节 / PPS " + carLifePps.length + " 字节，明文）");
                            } catch (Throwable t) {
                                log.log("   （发 CarLife VideoConfig 失败: " + t + "）");
                            }
                        }
                        if (carLifeIdr != null) {
                            try {
                                sendFrame(carLifeIdr);
                                log.log("★ 已补发 CarLife 最近一个 I 帧（" + carLifeIdr.length + " 字节）");
                            } catch (Throwable t) {
                                log.log("   （补发 I 帧失败: " + t + "）");
                            }
                        }
                    }
                    // ★ v3.17：**一次把队列里攒下的帧全发掉**，别让下面那个 40ms 的节流
                    // 把直通帧率卡到 25fps（CarLife 推 30fps，卡住就会越积越多 → 丢帧 → 画面顿）。
                    int sent = 0;
                    byte[] au;
                    // ★ v2.2：参数集**一到就立刻补发 VideoConfig + I 帧**（不再等到下一次关键帧请求）
                    if (!carLifeConfigSent && carLifeSps != null && carLifePps != null) {
                        try {
                            send(OP_VIDEO_CONFIG, avcCBox(carLifeSps, carLifePps), false);
                            carLifeConfigSent = true;
                            log.log("★ 已发 CarLife 自己的 VideoConfig（SPS " + carLifeSps.length
                                    + " 字节 / PPS " + carLifePps.length + " 字节，明文）"
                                    + "—— 它是重置编码器后重新出的");
                            if (carLifeIdr != null) {
                                sendFrame(carLifeIdr);
                                log.log("★ 已补发 CarLife 最近一个 I 帧（" + carLifeIdr.length
                                        + " 字节）—— 车机从这里开始出画面");
                            }
                        } catch (Throwable t) {
                            log.log("   （发 CarLife VideoConfig 失败: " + t + "）");
                        }
                    }
                    while ((au = CARLIFE_Q.poll()) != null) {
                        if (!carLifeConfigSent) {
                            byte[][] sp = extractParamSets(au);
                            if (sp != null) {
                                try {
                                    send(OP_VIDEO_CONFIG, avcCBox(sp[0], sp[1]), false);
                                    carLifeConfigSent = true;
                                    log.log("★ 已发 CarLife 自己的 VideoConfig（SPS " + sp[0].length
                                            + " 字节 / PPS " + sp[1].length + " 字节，明文）");
                                } catch (Throwable t) {
                                    log.log("   （发 CarLife VideoConfig 失败: " + t + "）");
                                }
                            }
                        }
                        if (isKeyFrame(au)) {
                            lastKeyFrameAu = au;
                        }
                        sendFrame(au);
                        pushed++;
                        sent++;
                        if (sent >= 120) {
                            break;   // 一轮别发太多，留点余地给收帧线程
                        }
                    }
                    if (sent == 0) {
                        if (!passthroughHintLogged) {
                            passthroughHintLogged = true;
                            log.log("★ 已切到 CarLife 直通 —— 等 CarLife 推来的第一帧"
                                    + "（收到就转进 CarPlay 流；期间车机画面会停在上一帧）");
                        }
                        Thread.sleep(2);
                        continue;
                    }
                    if (pushed % 100 < sent) {
                        log.log("   已推 " + pushed + " 帧（仍在推流中） [CarLife 直通]");
                    }
                    continue;   // 直通模式不睡那 40ms
                }
                if (!mirrored && codec != null) {
                    drawFrame();
                }
                drain(20);
                for (byte[] au : readyAccessUnits) {
                    if (isKeyFrame(au)) {
                        lastKeyFrameAu = au;
                    }
                }
                List<byte[]> batch = new ArrayList<>(readyAccessUnits);
                readyAccessUnits.clear();
                for (byte[] au : batch) {
                    sendFrame(au);
                    pushed++;
                }
                if (pushed > 0 && pushed % 100 == 0) {
                    log.log("   已推 " + pushed + " 帧（仍在推流中）");
                }
                Thread.sleep(40);
            }
            log.log("★ 推流结束：本次共推 " + (first.size() + pushed) + " 帧"
                    + (stopRequested ? "（你点了停止）" : "（到时）"));
            return true;
        } catch (Throwable t) {
            log.log("!! 视频推送异常: " + t);
            return false;
        } finally {
            close();
        }
    }

    // ------------------------------------------------------------------ 各步骤

    private boolean startEncoder() {
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            encoderInputSurface = input;
            codec.start();
            log.log("   H.264 编码器就绪 " + WIDTH + "x" + HEIGHT + "（输入 Surface）");
            return true;
        } catch (Throwable t) {
            log.log("!! 创建编码器失败: " + t);
            return false;
        }
    }

    private boolean connectDataPort() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sock = (network != null) ? network.getSocketFactory().createSocket() : new Socket();
                sock.connect(new InetSocketAddress(host, dataPort), 8000);
                sock.setTcpNoDelay(true);
                out = sock.getOutputStream();
                log.log("★ 已连上视频数据端口 " + host + ":" + dataPort);
                return true;
            } catch (Throwable t) {
                log.log("   连视频数据端口失败（第 " + attempt + "/3 次）: " + t);
                try {
                    if (sock != null) {
                        sock.close();
                    }
                } catch (Throwable ignored) {
                }
                sock = null;
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        log.log("!! 视频数据端口连不上 —— 车机那边的流多半已经收了（下一轮会重新 SETUP）");
        return false;
    }

    /**
     * 内容源：优先把手机真实屏幕镜像进编码器输入 Surface（MediaProjection → VirtualDisplay），
     * 没有屏幕录制权限时才退回自己画测试图案。
     * 注意 MediaCodec 的输入 Surface 不是给 CPU 画的（lockCanvas 会返回 null），
     * 所以"真屏幕"这条路才是正路。
     */
    private void startContentSource() {
        if (ctx == null) {
            return;
        }
        // ★ v0.6：Car+ 投屏模式 —— 不要 MediaProjection、不要 VirtualDisplay、不要录屏授权。
        // 编码器输入 Surface 交给 Car+ 去渲染（CarPlusCast.prepareCast），
        // 复用"mirrored"那条 drain 路径（只是不再 drawFrame）。
        if (carPlusCastMode) {
            log.log("   ★ **Car+ 投屏模式**：不用录屏授权 —— 编码器输入 Surface 直接交给 Car+，"
                    + "Car+ 把它的车机界面渲染进来");
            log.log("      （车机上显示的会是 Car+ 自己的车机界面；触摸走 addTouchListener 直达 Car+）");
            mirrored = true;
            projectionAlive = true;
            return;
        }
        try {
            // 0) 确保 mediaProjection 类型的前台服务在跑（Android 14 拿投影的前置条件）
            startMirrorService();
            log.log("   前台服务状态: " + MirrorService.lastStatus);

            // 1) 没有可用令牌（没授权过 / 令牌已被用掉）→ 现场弹授权框并等用户点
            if (!ScreenCapture.hasFreshToken()) {
                if (!requestConsentAndWait()) {
                    log.log("（没有屏幕录制授权，退回测试图案）");
                    return;
                }
                startMirrorService();
                log.log("   前台服务状态: " + MirrorService.lastStatus);
            }

            // 2) 拿投影 + 起前台服务 —— Android 14 与 15 要求的顺序是相反的，两种都试
            log.log("   顺序A：先起前台服务，再拿投影（Android 14 的要求）");
            boolean fgsOk = ensureMirrorService(2500);
            log.log("      前台服务状态: " + MirrorService.lastStatus);
            android.media.projection.MediaProjection projection = ScreenCapture.projection(ctx);

            if (projection == null || !fgsOk) {
                log.log("   顺序A没成，换成顺序B：先拿投影再起前台服务"
                        + "（这台机器要求先有投影才给起 mediaProjection 类型前台服务）");
                if (projection == null) {
                    projection = ScreenCapture.projection(ctx);
                }
                if (projection == null) {
                    // 令牌可能已被上一次失败的调用作废 —— 重新要一次授权
                    log.log("   令牌可能已作废，重新申请屏幕录制授权…");
                    requestConsentAndWait();
                    projection = ScreenCapture.projection(ctx);
                }
                fgsOk = ensureMirrorService(2500);
                log.log("      顺序B结果: 投影=" + (projection != null) + "  前台服务=" + MirrorService.lastStatus);
            }
            if (projection == null) {
                log.log("!! 两种顺序都没拿到投影，退回测试图案");
                return;
            }
            if (!fgsOk) {
                log.log("!! 前台服务仍起不来 —— 投影很可能被系统立刻撤销，先试一下看");
            }

            // 3) Android 14 硬要求：createVirtualDisplay 之前必须先注册回调
            final boolean[] stopped = {false};
            final boolean[] notified = {false};
            projection.registerCallback(new android.media.projection.MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopped[0] = true;
                    projectionAlive = false;
                    if (notified[0]) {
                        return;   // 别重复刷（invalidate() 里我们自己 stop() 也会回到这里）
                    }
                    notified[0] = true;
                    log.log("!! 系统停止了屏幕录制（镜像中断）");
                    log.log("   常见原因（按可能性排）：① 被录的画面上出现了**受保护窗口**"
                            + "（Car+ / CarLife+ 这类系统车联 App 的车机界面常带 FLAG_SECURE，"
                            + "系统一出现这种窗口就会掐掉录屏）；② 又弹了一次录屏授权框（新授权会作废旧投影）；"
                            + "③ 用户从通知里点了\"停止\"。");
                    log.log("   → 这条路（整屏录屏 Car+ 界面）如果反复在这里断，就别再试了；"
                            + "改用【② Car+等车机】+【③ 当车机·接Car+】走协议那条。");
                    ScreenCapture.invalidate();
                    releaseVirtualDisplay();
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));

            // 4) 建虚拟屏，直接渲染进编码器输入 Surface
            virtualDisplay = projection.createVirtualDisplay(
                    "cplink-mirror", WIDTH, HEIGHT, 160,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    input, null, null);
            if (virtualDisplay == null) {
                log.log("!! 建 VirtualDisplay 返回 null，退回测试图案");
                return;
            }
            Thread.sleep(800);
            if (stopped[0]) {
                log.log("!! 投影被系统立刻撤销 —— 多半是前台服务没真正跑起来（见上面的前台服务状态），"
                        + "或 ColorOS 不允许第三方录屏。退回测试图案。");
                ScreenCapture.invalidate();
                releaseVirtualDisplay();
                return;
            }
            mirrored = true;
            projectionAlive = true;
            log.log("★ 屏幕镜像已启动：手机真实屏幕 → 编码器（" + WIDTH + "x" + HEIGHT + "）");
            // 不再自动拉起 Car+：v2.6 起方向是"Car+ 走 CarLife 桥上车"，在这里把用户甩到 Car+
            // 的某个设置页只会干扰他 —— 实测被甩到 com.oplus.ocar…DownloadCarlifeComponentActivity
            // 后，紧接着弹出的录屏授权框就被拒了。想只投 Car+ 界面：手动点【启动Car+】，
            // 或在录屏授权框里选【只共享 Car+】。
            log.log("   投屏内容 = 手机真实屏幕（整个屏幕）。");
            log.log("   ⚠ Car+ / CarLife+ 都是**系统组件、没有桌面入口**，所以录屏弹框里的"
                    + "「单个应用」列表**永远列不到它们** —— 只能选「整个屏幕」（这是系统限制）。");
            log.log("     投流一开始会自动**逐个**试 Car+ 的界面：能录的留在屏幕上；"
                    + "如果全都被系统掐掉（日志里一连串 ✗），说明 Car+ 的界面都是受保护窗口，录不了。");
        } catch (Throwable t) {
            log.log("!! 启动屏幕镜像失败（" + t + "），退回测试图案");
        }
    }

    private void startMirrorService() {
        try {
            MirrorService.beginStart();
            android.content.Intent intent = new android.content.Intent(ctx, MirrorService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable t) {
            log.log("!! 启动前台服务失败: " + t);
        }
    }

    /** 起前台服务并等它报出确定结果（失败重试 3 次：刚授权完的那一瞬间系统还没登记投影，会报 SecurityException） */
    private boolean ensureMirrorService(long waitMs) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            startMirrorService();
            long deadline = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadline) {
                String s = MirrorService.lastStatus;
                if (s != null && s.startsWith("前台服务已启动")) {
                    return true;
                }
                if (s != null && s.startsWith("前台服务启动失败")) {
                    break;   // 这轮失败，等一下再试（多半是系统还没登记好投影）
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            if (attempt < 3) {
                log.log("   前台服务第 " + attempt + " 次没起来（" + MirrorService.lastStatus
                        + "），300ms 后重试…");
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 弹屏幕录制授权框并等用户点（投屏线程用） */
    private boolean requestConsentAndWait() {
        if (!(ctx instanceof android.app.Activity)) {
            return false;
        }
        final android.app.Activity activity = (android.app.Activity) ctx;
        log.log("—— 需要屏幕录制授权：请在手机上点【立即开始】——");
        log.log("   选【整个屏幕】（Car+ / CarLife+ 是系统组件、没有桌面入口，"
                + "\"单个应用\"列表里永远选不到它们）；本次授权只在这次投屏用。");
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ScreenCapture.requestConsent(activity);
            }
        });
        boolean ok = ScreenCapture.awaitResult(60000);
        log.log(ok ? "★ 屏幕录制授权已拿到" : "!! 没拿到屏幕录制授权（超时或被拒绝）");
        return ok;
    }

    /** 往编码器输入 Surface 画一帧测试图案（方块会动、带帧号，便于肉眼确认） */
    private void drawFrame() {
        Canvas canvas = null;
        try {
            canvas = input.lockCanvas(null);
        } catch (Throwable t) {
            if (drawnFrames == 0) {
                log.log("!! 编码器输入 Surface 画不上去（lockCanvas 抛异常）: " + t);
            }
            return;
        }
        if (canvas == null) {
            if (drawnFrames == 0) {
                log.log("!! 编码器输入 Surface 不给 CPU 画（lockCanvas 返回 null）——测试图案这条路走不通，"
                        + "必须用屏幕录制（MediaProjection）");
            }
            return;
        }
        try {
            int n = drawnFrames++;
            canvas.drawColor(Color.rgb(20, 24, 32));
            Paint bar = new Paint();
            bar.setColor(Color.rgb(0, 160, 255));
            int x = (n * 24) % (WIDTH - 240);
            canvas.drawRect(x, HEIGHT / 2 - 90, x + 240, HEIGHT / 2 + 90, bar);
            Paint text = new Paint();
            text.setColor(Color.WHITE);
            text.setTextSize(72);
            text.setAntiAlias(true);
            canvas.drawText("CPLink " + n, 60, 120, text);
            text.setTextSize(40);
            canvas.drawText("AirPlay mirroring stream from Android", 60, HEIGHT - 60, text);
        } finally {
            try {
                input.unlockCanvasAndPost(canvas);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把编码器已产出的东西取回来（参数集 + 访问单元） */
    private void drain(int budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 100);
            } catch (Throwable t) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                byte[] c0 = bufferBytes(of.getByteBuffer("csd-0"));
                byte[] c1 = bufferBytes(of.getByteBuffer("csd-1"));
                if (c0 != null) {
                    sps = stripStartCode(c0);
                }
                if (c1 != null) {
                    pps = stripStartCode(c1);
                }
                log.log("   编码参数：SPS=" + (sps == null ? "无" : sps.length + " 字节")
                        + "  PPS=" + (pps == null ? "无" : pps.length + " 字节"));
            } else if (index >= 0) {
                byte[] data = outputBytes(index);
                codec.releaseOutputBuffer(index, false);
                if (data.length > 0) {
                    readyAccessUnits.add(data);
                }
            } else {
                return;
            }
        }
    }

    private byte[] outputBytes(int index) {
        ByteBuffer buf = codec.getOutputBuffer(index);
        if (buf == null || info.size <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[info.size];
        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        buf.get(data);
        return data;
    }

    private void sendFrame(byte[] accessUnit) throws Exception {
        byte[] payload = annexBToLengthPrefixed(accessUnit);
        if (payload.length == 0) {
            return;
        }
        send(OP_VIDEO_FRAME, payload, true);
        frameCounter++;
    }

    private void send(int opcode, byte[] body, boolean seal) throws Exception {
        byte[] header = new byte[HEADER_LEN];
        int wireLen = seal ? body.length + TAG_LEN : body.length;
        header[0] = (byte) (wireLen & 0xff);
        header[1] = (byte) ((wireLen >>> 8) & 0xff);
        header[2] = (byte) ((wireLen >>> 16) & 0xff);
        header[3] = (byte) ((wireLen >>> 24) & 0xff);
        header[4] = (byte) opcode;
        byte[] wire = seal ? Crypto.chachaSeal(key, Crypto.nonce64(frameCounter), body, header) : body;
        out.write(header);
        out.write(wire);
        out.flush();
    }

    private void releaseVirtualDisplay() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;
    }

    private void close() {
        streamLive = false;
        releaseVirtualDisplay();
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (input != null) {
                input.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (sock != null) {
                sock.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] bufferBytes(ByteBuffer buf) {
        if (buf == null) {
            return null;
        }
        ByteBuffer copy = buf.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }

    /** 去掉 Annex B 起始码，得到裸 NAL */
    private static byte[] stripStartCode(byte[] data) {
        int offset = 0;
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            offset = 4;
        } else if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            offset = 3;
        }
        byte[] out = new byte[data.length - offset];
        System.arraycopy(data, offset, out, 0, out.length);
        return out;
    }

    /** MP4 的 avcC box：[u32 长度]["avcC"][configurationVersion…] */
    private static byte[] avcCBox(byte[] sps, byte[] pps) {
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        rec.write(0x01);                       // configurationVersion
        rec.write(sps.length > 1 ? sps[1] : 0x42);  // AVCProfileIndication
        rec.write(sps.length > 2 ? sps[2] : 0x00);  // profile_compatibility
        rec.write(sps.length > 3 ? sps[3] : 0x1E);  // AVCLevelIndication
        rec.write(0xFF);                       // 保留 6 位 + lengthSizeMinusOne=3
        rec.write(0xE1);                       // 保留 3 位 + numOfSPS=1
        writeU16(rec, sps.length);
        rec.write(sps, 0, sps.length);
        rec.write(0x01);                       // numOfPPS
        writeU16(rec, pps.length);
        rec.write(pps, 0, pps.length);
        byte[] record = rec.toByteArray();

        ByteArrayOutputStream box = new ByteArrayOutputStream();
        writeU32(box, record.length + 8);
        box.write('a');
        box.write('v');
        box.write('c');
        box.write('C');
        box.write(record, 0, record.length);
        return box.toByteArray();
    }

    /** Annex B → 4 字节大端长度前缀 */
    private static byte[] annexBToLengthPrefixed(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<int[]> nals = new ArrayList<>();
        int i = 0;
        int start = -1;
        while (i + 3 <= data.length) {
            boolean sc4 = i + 4 <= data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1;
            boolean sc3 = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
            if (sc4 || sc3) {
                int scLen = sc4 ? 4 : 3;
                if (start >= 0) {
                    nals.add(new int[]{start, i});
                }
                i += scLen;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < data.length) {
            nals.add(new int[]{start, data.length});
        }
        if (nals.isEmpty()) {
            return new byte[0];
        }
        for (int[] span : nals) {
            int len = span[1] - span[0];
            writeU32(out, len);
            out.write(data, span[0], len);
        }
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}

package com.carplaylink.probe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度 CarLife 「车机端」—— 当车机，接住手机侧 CarLife（OPPO 侧是
 * `com.baidu.carlife.oppo` = 智能车载百度 CarLife+ 组件，实测它会监听 7240 等端口）。
 *
 * 依据反编译 CarLife 车机端 App 的开源实现（MeshHun/carlife_pc_tool）：
 *   7 条 TCP 通道（车机主动连手机）：1=7240 控制、2=8240 视频、3=9240 媒体音频、
 *   4=9241 TTS、5=9242 语音、6=9340 触控、7=9440 车况。
 *   帧头（全大端）：通道 1/6 用 8 字节 [u16 len][u16 rsv][u32 service]，
 *                  其余通道用 12 字节 [u32 len][u32 seq/ts][u32 service]。
 *   载荷是 protobuf（CarlifeXxxProto）。
 *
 * 握手（车机侧主动）：
 *   98305 版本协商{major=4,minor=0} → 手机回 65538
 *   98307 车机设备信息 → 手机回 65540 自己的设备信息
 *   98380 鉴权确认（手机先发鉴权相关）
 *   98311 编码参数{width,height,fps}  ← 关键：发这个手机才开始推流
 *   98386 特征配置（手机请求 65617 后）
 *   98313 确认开始推流（手机发 65551 后）
 *   131074 心跳（每 1000ms）
 *   视频帧 = 通道 2 上的 131073 + H.264 裸流
 *
 * ===== v3.4 的两处根本修正（v3.3 及以前的证据全是假的）=====
 *
 * 1) **绝不再和自己握手**：v3.3 的流程里 LocalNetDiag 会自动反向监听这 7 个端口，然后本类再去
 *    connect 它们 —— 连上的其实是自己。日志里"★ 通道 1 已连上 127.0.0.1:7240"、"
 *    首包 12 字节: 000400000001800108041000"（就是本类自己发出去的 98305 帧）全是自连产物。
 *    现在每个端口连接前都先查 {@link SelfPorts#holds(int)}，自占端口直接跳过并标注。
 * 2) **连接成功必须带证据**：打印 `本机 a:p → 对端 b:q`，并把对端**主动先发**的报文原样 hex 打出来
 *    （先静默听 1.5 秒再说话），这样"到底有没有真的 CarLife 对端"一眼可辨。
 *
 * 所有收到的报文都打 service + 原始 hex（前 64 字节）—— 下一轮排错不需要再猜。
 */
public final class CarLifeProbe {

    public static final int[] PORTS = {7240, 8240, 9240, 9241, 9242, 9340, 9440};
    private static final int[] CHANNELS = {1, 2, 3, 4, 5, 6, 7};

    private static final int MSG_HU_PROTOCOL_VERSION = 98305;
    private static final int MSG_MD_PROTOCOL_VERSION_MATCH = 65538;
    private static final int MSG_HU_STATISTICS = 98343;
    private static final int MSG_HU_INFO = 98307;
    private static final int MSG_MD_INFO = 65540;
    private static final int MSG_MD_AUTHEN_RESPONSE = 65609;
    private static final int MSG_MD_AUTHEN_RESULT = 65611;
    private static final int MSG_HU_AUTHEN_RESULT = 98380;
    private static final int MSG_MD_FEATURE_CONFIG_REQ = 65617;
    private static final int MSG_MD_FEATURE_LIST = 65574;
    private static final int MSG_MD_KEEPALIVE = 65563;
    private static final int MSG_HU_FEATURE_CONFIG_LIST = 98386;
    private static final int MSG_HU_VIDEO_ENCODER_INIT = 98311;
    private static final int MSG_MD_VIDEO_ENCODER_INIT = 65544;
    private static final int MSG_MD_VIDEO_ENCODER_INIT_DONE = 65551;
    private static final int MSG_VIDEO_ENCODER_START = 98313;
    private static final int MSG_HU_HEARTBEAT = 131074;
    private static final int MSG_VIDEO_DATA = 131073;
    private static final int MSG_MD_BACK_TO_HOME = 65569;
    /** 通道 6：触控事件（CarlifeTouchAction{action=1,x=2,y=3,pointerx=4,pointery=5}） */
    private static final int MSG_TOUCH_ACTION = 425985;
    /** 通道 6：车机实体按键（CarlifeCarHardKeyCode{keycode=1}），1=HOME 2=BACK */
    private static final int MSG_HARD_KEY_CODE = 425992;

    /** 要 CarLife 编出来的画面尺寸（车机屏按这个收） */
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;

    private final AirPlayProbe.Logger log;
    private final LogFold fold;
    private final List<Socket> sockets = new ArrayList<>();
    private volatile Socket cmdSocket;
    private volatile boolean running;
    private volatile boolean quiet;
    private volatile boolean handshakeSeen;
    private long videoFrames;
    private long videoBytes;
    /** 每种 service 收到多少次（用来把刷屏折掉） */
    private final java.util.Map<Integer, Integer> msgCount = new java.util.concurrent.ConcurrentHashMap<>();
    /** 每种 service 上次自动应答的时间（同一个 service 最多 1.5 秒应答一次） */
    private final java.util.Map<Integer, Long> lastReplyAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** 每种 service 已经应答过几次（有些只能回有限次） */
    private final java.util.Map<Integer, Integer> replyCount = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean videoExpectLogged;
    private volatile boolean resetHintLogged;
    private volatile boolean triggersStarted;
    /** 手机在 65544 里自报的编码参数（按它的回 98311，别硬塞我们的） */
    private volatile int phoneW;
    private volatile int phoneH;
    private volatile int phoneFps;
    /** 手机报过"编码器就绪 65551" */
    private volatile boolean encDoneSeen;
    /** 通道 2 的 socket 和它上面收到的**任意**报文数/字节数（不只是视频帧）——用来判断通道是不是还活着 */
    private volatile Socket videoSocket;
    private volatile long videoChMsgs;
    private volatile long videoChBytes;
    /** 通道 6（触控）的 socket —— 车机侧的触摸要往这里发 */
    private volatile Socket touchSocket;
    /** 当前活着的会话（静态，好让界面上的按钮把触摸/按键发进来） */
    private static volatile CarLifeProbe LIVE;

    /**
     * 车机侧的触摸 → CarLife 触控报文（通道 6，service 425985）。
     *
     * @param action 0=按下 1=抬起 2=移动（依据 RemoteDisplayGLView.java:220 与
     *               CarlifeTouchActionProto 的字段顺序）
     * @param x,y    坐标，必须是 **CarLife 画面自己的坐标系**（即 65544 里它报的 w×h）
     */
    public static boolean sendTouch(int action, int x, int y) {
        CarLifeProbe p = LIVE;
        return p != null && p.sendTouchInternal(action, x, y);
    }

    /** 当前会话是不是已经在收 CarLife 视频帧（在投屏）—— 用来阻止"手贱点 ③ 把好会话掐掉" */
    public static boolean isStreaming() {
        CarLifeProbe p = LIVE;
        return p != null && p.videoFrames > 0;
    }

    /** 本次会话用过的候选地址（自动重连时复用） */
    private volatile List<String> lastHosts;
    /** 正在自动重连（防重入） */
    private volatile boolean reconnecting;

    /**
     * 投屏中 CarLife 会话断了 → 自动重连。
     *
     * 实测：CarLife+ 有时会把 7 条通道一起 reset（会话超时/被别的连接挤掉），
     * 表现就是车机画面**冻住**。以前只能手动重来一遍（还得先 ② 把 CarLife+ 拉起来）。
     * 这里自动做：关掉旧连接 → 重新拉起 CarLife+ → 等端口开 → 重走握手。
     */
    private void autoReconnect() {
        if (reconnecting) {
            return;
        }
        reconnecting = true;
        final List<String> hosts = lastHosts;
        log.log("   ★ CarLife 会话断了 —— **自动重连**（CarPlay 那条流不受影响，画面会停一小会儿）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    close();
                    Thread.sleep(1500);
                    Runnable rl = relaunchHook;
                    if (rl != null) {
                        rl.run();
                    }
                    boolean open = false;
                    for (int i = 0; i < 20; i++) {
                        Thread.sleep(1000);
                        if (LocalNetDiag.anyCarLifeOpen(600) != null) {
                            open = true;
                            log.log("   ✓ CarLife+ 端口又开了（等了 " + (i + 1) + " 秒），重新接握手");
                            break;
                        }
                    }
                    if (!open) {
                        log.log("   !! 自动重连：等了 20 秒端口还没开 —— 手动点【② Car+等车机】再点【③】");
                        return;
                    }
                    running = true;
                    resetSessionState();
                    CarLifeProbe.this.run(hosts == null ? LocalNetDiag.allLocalHosts() : hosts);
                } catch (Throwable t) {
                    log.log("   !! 自动重连失败: " + t);
                } finally {
                    reconnecting = false;
                }
            }
        }, "carlife-reconnect").start();
    }

    /** 车机实体按键 → CarLife（通道 6，service 425992）；1=HOME 2=BACK */
    public static boolean sendHardKey(int keycode) {
        CarLifeProbe p = LIVE;
        return p != null && p.sendHardKeyInternal(keycode);
    }

    private boolean sendTouchInternal(int action, int x, int y) {
        try {
            Socket s = touchSocket;
            if (s == null || s.isClosed()) {
                log.log("!! 触控通道（9340）没连上，触摸发不出去");
                return false;
            }
            String payload = pbInt(1, action) + pbInt(2, x) + pbInt(3, y);
            OutputStream out = s.getOutputStream();
            out.write(frame(6, MSG_TOUCH_ACTION, payload));
            out.flush();
            log.log(">> 通道6 service=" + MSG_TOUCH_ACTION + "（车机:触控）action=" + action
                    + " x=" + x + " y=" + y);
            return true;
        } catch (Throwable t) {
            log.log("!! 发触控失败: " + brief(t));
            return false;
        }
    }

    private boolean sendHardKeyInternal(int keycode) {
        try {
            Socket s = touchSocket;
            if (s == null || s.isClosed()) {
                log.log("!! 触控通道（9340）没连上，按键发不出去");
                return false;
            }
            OutputStream out = s.getOutputStream();
            out.write(frame(6, MSG_HARD_KEY_CODE, pbInt(1, keycode)));
            out.flush();
            log.log(">> 通道6 service=" + MSG_HARD_KEY_CODE + "（车机:实体按键）keycode=" + keycode);
            return true;
        } catch (Throwable t) {
            log.log("!! 发按键失败: " + brief(t));
            return false;
        }
    }
    /** 本次会话用的是哪个地址（判断"回环 vs 局域网"） */
    private volatile String currentHost = "";
    /** 因为"握手全通但手机不推流"自动重来过的轮数（最多 3 轮） */
    private volatile int videoRetries;
    /** 本次会话视频通道（8240）开没开 —— 实测它取决于 CarLife+ 处在哪个界面状态 */
    private volatile boolean videoChannelOpen;
    /** 因为"没开视频通道"自动重来过一次（只来一次） */
    private volatile boolean recoveredVideo;

    /** 本机所有地址（转发给 LocalNetDiag，省得到处写类名） */
    private static List<String> LocalDiagHosts() {
        return LocalNetDiag.allLocalHosts();
    }

    /** 8240（视频通道）在任意本机地址上开着没 */
    static boolean videoPortListening() {
        for (String host : LocalNetDiag.allLocalHosts()) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), 8240), 600);
                return true;
            } catch (Throwable ignored) {
            } finally {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 手机没开视频通道（8240）时的自动补救。
     *
     * v3.12 实测：从第 2 个入口（`com.baidu.carlife.CarlifeActivity`）起来的 CarLife+
     * **只开 6 条通道、没有 8240** —— 那种状态下握手全通也不可能有画面（通道都不存在）。
     * 从第 1 个入口（`com.baidu.che.codriver.ui.MainActivity`）起来时 7 条全开。
     * 所以：没看到 8240 就把它拉回第 1 个入口、等端口开齐，再连一次（只来一次）。
     */
    private void recoverVideoChannel(final List<String> hosts) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                log.log("   → 自动补救：把 CarLife+ 拉回第 1 个入口"
                        + "（com.baidu.che.codriver.ui.MainActivity —— 实测只有它那个状态会开 8240），"
                        + "等端口开齐再连一次");
                close();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                Runnable rl = relaunchHook;
                if (rl != null) {
                    try {
                        rl.run();
                    } catch (Throwable t) {
                        log.log("   （重拉失败: " + t + "）");
                    }
                }
                boolean ok = false;
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (videoPortListening()) {
                        ok = true;
                        log.log("   ✓ 视频通道 8240 开了（等了 " + (i + 1) + " 秒）");
                        break;
                    }
                }
                if (!ok) {
                    log.log("   !! 等了 20 秒 8240 还是没开 —— 手机这次不打算推流。"
                            + "手动点【② Car+等车机】多等一会儿（第 1 个入口要 5~10 秒才开端口）再点【③】。");
                    return;
                }
                running = true;
                resetSessionState();
                log.log("—— 自动重连（这次带视频通道）——");
                CarLifeProbe.this.run(hosts);
            }
        }, "carlife-vidfix").start();
    }
    /** 车机侧的 UDP 7999 发现监听（CarLife 手机端在找车机时会往这里广播） */
    private volatile DatagramSocket udp7999;

    /**
     * 让外部（MainActivity）帮忙"重新把 CarLife+ 拉起来"—— 换地址重试前必须做，
     * 因为 CarLife+ 在一轮会话失败后会把 7240 等端口**关掉**（v3.11 实测：第二轮跑的时候
     * 端口已经关了，7 条通道全 refused，等于没测）。
     */
    public static volatile Runnable relaunchHook;

    /** 监听 UDP 7999：手机侧 CarLife 在 Wi-Fi 模式下会广播自己，收到就原样打出来 */
    private void startDiscoveryListener() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DatagramSocket ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.bind(new InetSocketAddress("0.0.0.0", 7999));
                    udp7999 = ds;
                    log.log("   ★ 已监听 UDP 7999（CarLife 发现口）—— 手机若在\"找车机\"，"
                            + "它会往这里广播，收到就原样打出来");
                    byte[] buf = new byte[2048];
                    while (running) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        byte[] d = new byte[p.getLength()];
                        System.arraycopy(p.getData(), p.getOffset(), d, 0, p.getLength());
                        log.log("   ★★ UDP 7999 收到手机广播！来自 "
                                + p.getAddress().getHostAddress() + ":" + p.getPort()
                                + " len=" + d.length + " hex=" + Crypto.hex(d, 96));
                        String txt = new String(d, StandardCharsets.ISO_8859_1);
                        log.log("      可读内容: " + txt.replaceAll("[^\\x20-\\x7e]", "."));
                    }
                } catch (Throwable t) {
                    if (running) {
                        log.log("   （UDP 7999 监听失败: " + t + " —— 可能被系统诊断占着，不影响其它）");
                    }
                }
            }
        }, "carlife-udp").start();
    }

    /** 换地址重连前把上一轮的会话状态清干净（否则节流门会把新一轮的应答全挡掉） */
    private void resetSessionState() {
        // ★ v1.6：新一轮会话 → 清掉上一轮缓存的 CarLife 参数集/I 帧（尺寸可能变了）
        VideoSender.resetCarLifeStream();
        msgCount.clear();
        lastReplyAt.clear();
        replyCount.clear();
        handshakeSeen = false;
        videoExpectLogged = false;
        triggersStarted = false;
        encDoneSeen = false;
        videoChMsgs = 0;
        videoChBytes = 0;
        videoSocket = null;
        phoneW = 0;
        phoneH = 0;
        phoneFps = 0;
    }

    /** 从 65544 的 protobuf 里取 f1/f2/f3 = 宽/高/帧率 */
    private static int[] parseEncoderInfo(byte[] payload) {
        try {
            int w = 0;
            int h = 0;
            int f = 0;
            int i = 0;
            while (i < payload.length) {
                int tag = payload[i++] & 0xFF;
                int field = tag >>> 3;
                int wire = tag & 7;
                if (wire == 0) {
                    long v = 0;
                    int shift = 0;
                    while (i < payload.length) {
                        int b = payload[i++] & 0xFF;
                        v |= ((long) (b & 0x7F)) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    if (field == 1) {
                        w = (int) v;
                    } else if (field == 2) {
                        h = (int) v;
                    } else if (field == 3) {
                        f = (int) v;
                    }
                } else if (wire == 2) {
                    long len = 0;
                    int shift = 0;
                    while (i < payload.length) {
                        int b = payload[i++] & 0xFF;
                        len |= ((long) (b & 0x7F)) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    i += (int) len;
                } else {
                    break;
                }
            }
            return (w > 0 && h > 0) ? new int[]{w, h, f} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public CarLifeProbe(AirPlayProbe.Logger log) {
        this.log = log;
        this.fold = new LogFold(log);
    }

    /** 返回是否至少连上了控制通道 */
    public boolean run(List<String> candidateHosts) {
        running = true;
        LIVE = this;
        lastHosts = candidateHosts;
        if (udp7999 == null) {
            startDiscoveryListener();
        }
        if (!SelfPorts.heldText().equals("无")) {
            log.log("   本机自己占着的端口: " + SelfPorts.heldText()
                    + " —— 这些已排除，不会再出现\"连上自己\"的假证据");
        }
        for (String host : candidateHosts) {
            if (!running) {
                break;
            }
            if (tryHost(host)) {
                if (!videoChannelOpen && !recoveredVideo) {
                    recoveredVideo = true;
                    recoverVideoChannel(candidateHosts);
                }
                return true;
            }
        }
        log.log("!! 7 条通道在所有候选地址上都连不上（" + candidateHosts + "）");
        log.log("   → 说明此刻**没有** CarLife 手机端在等车机（端口关着）。");
        // ★ v3.19：这几乎总是"上一轮会话刚被关掉、CarLife+ 把端口收了"——实测里
        // 用户点一下 ③ 就会走到这个状态（③ 关了旧会话却没重拉 CarLife+），
        // 结果 CarPlay 那边只能回退成**整屏镜像**，看起来就是"车机上显示的是整个屏幕"。
        // 所以这里不要放弃，直接走自动重来：重拉 CarLife+ → 等端口开 → 再连。
        scheduleRetry("一条通道都没连上（CarLife+ 的端口当时是关的）");
        return false;
    }

    /**
     * 会话没起来时**自动重来**（最多 3 轮）。
     *
     * 触发它的两种实测情况：
     *   ① 7 条通道一条都没连上 —— 上一轮会话刚被关，CarLife+ 把端口收了（点 ③ 就会这样）；
     *   ② 握手全通但手机不推流 —— 同一版本一次推一次不推，是 CarLife+ 自己的状态问题。
     *
     * 每一轮：关掉旧连接 → 重新拉起 CarLife+ → **等视频通道 8240 真的开**（最多 20 秒）→ 重走握手。
     * 轮换本机地址与局域网地址（两种都试过）。
     * 必须等 8240 而不只是"任意一个 CarLife 端口开"—— 没有 8240 就永远不可能有画面。
     */
    private void scheduleRetry(String why) {
        if (videoRetries >= 3) {
            log.log("   !! 已经自动重来 3 轮都没成功（" + why + "）。");
            log.log("      → 手动来一次：点【停止】→【② Car+等车机】→ 等日志出现\"已连上视频\" →【① 自动找车机】。");
            return;
        }
        videoRetries++;
        final int round = videoRetries;
        final List<String> hosts = new ArrayList<>();
        if (round % 2 == 0) {
            for (String h : LocalDiagHosts()) {
                if (!h.startsWith("127.") && !h.startsWith("localhost")) {
                    hosts.add(h);
                }
            }
        }
        if (hosts.isEmpty()) {
            hosts.addAll(LocalDiagHosts());
        }
        log.log("   → **自动重来第 " + round + "/3 轮**（" + why + "）");
        log.log("      这一轮连: " + hosts);
        new Thread(new Runnable() {
            @Override
            public void run() {
                close();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                Runnable rl = relaunchHook;
                if (rl != null) {
                    rl.run();
                }
                boolean ok = false;
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (videoPortListening()) {
                        ok = true;
                        log.log("   ✓ 视频通道 8240 开了（等了 " + (i + 1) + " 秒），重连");
                        break;
                    }
                }
                if (!ok) {
                    log.log("   !! 第 " + round + " 轮：等了 20 秒 8240 还没开，这一轮放弃");
                    return;
                }
                // ★ v0.4：端口开着 ≠ CarLife+ 已进"等车机"状态（实测要 5~10 秒），
                // 这里再压 3 秒，免得又走到"6 秒没等到版本协商"。
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                running = true;
                resetSessionState();
                CarLifeProbe.this.run(hosts);
            }
        }, "carlife-retry" + round).start();
    }

    /**
     * 正在接 CarLife（已经开连了、但控制通道还没通）—— 用来拦住"接的过程中手贱点 ③"。
     * 实测：② 还在接的时候点 ③，会把刚建立的会话关掉、CarLife+ 又要重新进状态，越点越乱。
     */
    public static boolean isConnecting() {
        CarLifeProbe p = LIVE;
        return p != null && p.running && p.cmdSocket == null;
    }

    /** 现在是不是已经连着 CarLife（有控制通道）—— 用来阻止"重复点 ③ 把好会话关掉" */
    public static boolean isConnected() {
        CarLifeProbe p = LIVE;
        return p != null && p.cmdSocket != null;
    }

    private boolean tryHost(String host) {
        currentHost = host;
        log.log("—— 找 CarLife 对端：试连 " + host + " 的 7 条通道（本机自占端口已排除）——");
        List<Socket> opened = new ArrayList<>();
        List<Integer> openedCh = new ArrayList<>();
        Socket cmd = null;
        int skipped = 0;
        for (int i = 0; i < PORTS.length; i++) {
            final int channel = CHANNELS[i];
            final int port = PORTS[i];
            if (SelfPorts.holds(port)) {
                skipped++;
                continue;
            }
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 2000);
                s.setTcpNoDelay(true);
                opened.add(s);
                openedCh.add(channel);
                log.log("★ 通道 " + channel + " 有人监听 " + host + ":" + port
                        + "（" + channelName(channel) + "）  本机 "
                        + s.getLocalAddress().getHostAddress() + ":" + s.getLocalPort()
                        + " → 对端 " + s.getInetAddress().getHostAddress() + ":" + s.getPort());
                if (channel == 1) {
                    cmd = s;
                }
            } catch (Throwable t) {
                log.log("   通道 " + channel + "（" + host + ":" + port + "）连不上: " + brief(t));
            }
        }
        if (skipped > 0) {
            log.log("   （跳过 " + skipped + " 个本机自占端口: " + SelfPorts.heldText() + "）");
        }
        videoChannelOpen = openedCh.contains(2);
        if (!videoChannelOpen) {
            log.log("   ⚠ 手机这次**没开视频通道 8240**（只连上 " + opened.size() + " 条）—— "
                    + "握手跑完也不可能有画面，因为通道本身不存在。");
            log.log("      实测这取决于 CarLife+ 是从哪个界面起来的：第 1 个入口"
                    + "（com.baidu.che.codriver.ui.MainActivity）会开满 7 条，"
                    + "第 2 个入口（com.baidu.carlife.CarlifeActivity）只开 6 条、**没有 8240**。");
        }
        if (cmd == null) {
            quiet = true;
            for (Socket s : opened) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            quiet = false;
            return false;
        }

        // 先静默听 400ms：看对端会不会主动先说话（原版是车机先发 98305，但 OEM 版可能反过来）。
        // 注意别等太久 —— 实测对端可能在 1 秒左右就把"没人说话"的连接掐掉（表现为我们一发就 Broken pipe）。
        String firstHex = quietRead(cmd, 400);
        if (firstHex != null) {
            log.log("   ★ 对端主动先发了: " + firstHex + "（原版是车机先发，说明这个对端角色相反）");
        } else {
            log.log("   （对端没主动说话 —— 按车机端剧本，由我们先发 98305）");
        }

        sockets.addAll(opened);
        cmdSocket = cmd;
        for (int i = 0; i < opened.size(); i++) {
            startReader(openedCh.get(i), opened.get(i));
        }
        log.log("   控制通道在 " + host + " 上通了（共连上 " + opened.size() + " 条通道）"
                + " → 发 98305 版本协商{major=4,minor=0}，等手机回 65538…");
        sendCmd(MSG_HU_PROTOCOL_VERSION, pbInt(1, 4) + pbInt(2, 0));
        startHeartbeat();
        startKick();
        return true;
    }

    /**
     * 兜底推进：原版剧本要等手机回包才发下一步，但 OEM 版可能不回或回别的 ID，
     * 那样就永远卡在第一步。所以 6 秒后不管有没有收到 65538，都按剧本把
     * 98307（车机设备信息）和 98311（编码参数，**发这个手机才开始推流**）发出去，
     * 并把"这是兜底发的"写进日志，方便下一轮对照。
     */
    private void startKick() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ★ v1.7：**别 6 秒就放弃**。实测同一段代码有时通、有时不通：
                // Car+ 冷启动时要更久才把 CarLife+ 带进"等车机"状态，这时它对 98305 就是不回。
                // 以前 6 秒没回包就整轮拆掉重来（重拉 Car+ + CarLife+），反而更难进状态。
                // 现在改成**每 3 秒重发一次 98305**，最多 5 次（≈15 秒）；期间一回包就继续往下走。
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running || handshakeSeen) {
                        return;
                    }
                    log.log("   （还没等到 65538 → 第 " + (attempt + 1) + "/5 次重发 98305 版本协商…）");
                    sendCmd(MSG_HU_PROTOCOL_VERSION, pbInt(1, 4) + pbInt(2, 0));
                }
                if (!running || handshakeSeen) {
                    return;
                }
                // ★ v3.11：对端**一个字都没回**时别再硬发 98307+98311 —— 那只会把手机惹毛
                // （实测：它随后把 7 条通道全 reset）。这种情况说明它压根没进"等待连接车机"状态，
                // 该做的是先点【②】把它拉起来，而不是继续灌报文。
                log.log("   !! 15 秒都没等到手机的版本匹配回包 —— 说明 CarLife+ **还没进\"等待连接车机\"状态**。");
                log.log("   → 不再硬发 98307/98311（v3.10 那么做之后手机立刻把 7 条通道全 reset 了）；"
                        + "改成**重新拉起 Car+ + CarLife+ 再来一轮**（实测它进状态要 5~10 秒）。");
                // ★ v0.4：这里原来是直接 close() 放弃。但"刚拉起来的 CarLife+ 要 5~10 秒才进状态"
                // 是必然现象 —— 太早连上去就会走到这里。改成交给自动重来：
                // 重拉 Car+ + CarLife+ → 等 8240 真的开 → 再连，最多 3 轮。
                scheduleRetry("连上了但手机没回版本协商（CarLife+ 还没进\"等车机\"状态）");
                return;
            }
        }, "carlife-kick").start();
    }

    private void startHeartbeat() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(1000);
                        sendCmd(MSG_HU_HEARTBEAT, "");
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "carlife-hb").start();
    }

    private void startReader(final int channel, final Socket socket) {
        if (channel == 2) {
            videoSocket = socket;
        }
        if (channel == 6) {
            touchSocket = socket;
            log.log("   （触控通道已记下 —— 车机的触摸会往通道 6 发）");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    int seen = 0;
                    while (running) {
                        int headerLen = (channel == 1 || channel == 6) ? 8 : 12;
                        byte[] header = readFully(in, headerLen);
                        if (header == null) {
                            break;
                        }
                        long payloadLen;
                        long extra;
                        long service;
                        if (headerLen == 8) {
                            payloadLen = u16(header, 0);
                            extra = u16(header, 2);
                            service = u32(header, 4);
                        } else {
                            payloadLen = u32(header, 0);
                            extra = u32(header, 4);
                            service = u32(header, 8);
                        }
                        if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024) {
                            log.log("!! 通道 " + channel + " 长度异常 " + payloadLen + "，原始头: "
                                    + Crypto.hex(header) + "，断开");
                            break;
                        }
                        byte[] payload = payloadLen > 0 ? readFully(in, (int) payloadLen) : new byte[0];
                        if (payload == null) {
                            break;
                        }
                        seen++;
                        if (channel == 2) {
                            videoChMsgs++;
                            videoChBytes += payload.length;
                            // 视频通道在推流前应该是**完全安静**的。上面只要出现任何报文都是线索：
                            // 说明手机确实在动这条通道（只是报文不是 131073），或者帧格式和我们想的不一样。
                            if (service != MSG_VIDEO_DATA) {
                                log.log("   !! 通道 2（视频）上收到**非视频帧**报文: service=" + service
                                        + "（" + serviceName(service) + "）len=" + payload.length
                                        + " 原始头=" + Crypto.hex(header));
                            }
                        }
                        // 每条通道的头 3 个报文都原样打出来 —— 帧格式跟参考实现不一致时，靠这个一眼看出来
                        if (seen <= 3) {
                            log.log("   通道 " + channel + " 第 " + seen + " 个报文原始头: "
                                    + Crypto.hex(header) + " 载荷前 "
                                    + Math.min(32, payload.length) + " 字节: "
                                    + Crypto.hex(java.util.Arrays.copyOf(payload, Math.min(32, payload.length))));
                        }
                        onMessage(channel, service, extra, payload);
                    }
                } catch (Throwable t) {
                    if (running && !quiet) {
                        fold.log("   通道 " + channel + " 读结束: " + brief(t));
                        // ★ v3.17：正在投屏时通道断了 = 画面要冻住 → 自动把 CarLife 会话重连起来。
                        // CarPlay 那条流是另一条连接，不受影响；重连期间车机画面停在上一帧。
                        if (videoFrames > 0) {
                            autoReconnect();
                        } else if (String.valueOf(t).contains("reset") && !resetHintLogged) {
                            resetHintLogged = true;
                            log.log("   ⚠ 对端把连接重置了（Connection reset）。常见原因，按可能性排：");
                            log.log("      ① **上一次会话还占着** —— CarLife 通常只允许一个\"车机\"，"
                                    + "旧连接没被它自己清掉时，新连接会被立刻掐断；"
                                    + "等 5~10 秒再点一次【③】多半就好了。");
                            log.log("      ② CarLife 只是开着监听、并没进\"等待连接车机\"流程（那就得先点【②】）。");
                            log.log("      ③ 它要的是 CarLife 的发现握手（UDP 7999 广播）之后才认车机 —— "
                                    + "这个用【网络诊断】看它有没有在广播。");
                        }
                    }
                }
            }
        }, "carlife-ch" + channel).start();
    }

    private void onMessage(int channel, long service, long extra, byte[] payload) {
        if (channel == 2 && service == MSG_VIDEO_DATA) {
            videoFrames++;
            videoBytes += payload.length;
            if (videoFrames == 1 || videoFrames % 100 == 0) {
                log.log("★★ 收到视频帧 #" + videoFrames + "（" + payload.length + " 字节，累计 "
                        + videoBytes + " 字节）头: " + Crypto.hex(payload, 16));
            }
            if (videoFrames == 1 && !VideoSender.carLifePassthrough) {
                if (VideoSender.carLifePassthroughAllowed) {
                    VideoSender.carLifePassthrough = true;
                    log.log("   ★★ 自动切到 **CarLife 直通**：CarLife 推来的 H.264 会**直接转进 CarPlay 视频流**。");
                } else {
                    log.log("   （收到 CarLife 视频帧，但当前投屏内容是【Car+镜像】—— 不切直通。"
                            + "想切请点【内容】按钮切到【CarLife直通】后重新投屏）");
                }
            }
            VideoSender.feedCarLife(payload);
            return;
        }
        // ★ v1.5：CarLife 的**媒体音频**（通道 3 的 196614，4096 字节 PCM）每 30ms 一条，
        // 会把有用的日志全冲掉（实测一轮日志里 7000+ 条都是它）。只每 1000 条记一行，其余静默。
        if (channel == 3 && service == 196614) {
            long c = bump(service);
            // ★ v2.0：这就是 CarLife 的**媒体音频**（44100/2/16 裸 PCM），
            // 灌进 AirPlay 音频流（AudioSender）→ 车机才有声音。没开音频流时 pushPcm 直接丢。
            AudioSender.pushPcm(payload);
            if (c == 1 || c % 1000 == 0) {
                log.log("<< 通道 3 媒体音频数据（已收 " + c + " 条，静默中；"
                        + "每条 " + payload.length + " 字节 PCM → 已灌进音频流）");
            }
            return;
        }
        handshakeSeen = true;
        int n = bump(service);
        if (n <= 3 || n % 200 == 0) {
            log.log("<< 通道 " + channel + " service=" + service + "（" + serviceName(service) + "） len="
                    + payload.length + "  第 " + n + " 次"
                    + (payload.length > 0 && payload.length <= 64 ? "  hex=" + Crypto.hex(payload) : ""));
            if (payload.length > 0 && payload.length <= 512) {
                String decoded = Pb.decode(payload);
                if (!decoded.isEmpty()) {
                    log.log("      " + decoded);
                }
            }
        } else {
            fold.log("<< 通道 " + channel + " service=" + service + "（" + serviceName(service) + "）重复中…");
        }
        if (service == MSG_MD_FEATURE_LIST) {
            log.log("   ↑ 65574 = 手机端**各模块状态变更列表**（CarlifeModuleStatusList），"
                    + "不是特征配置请求 —— 不用回；真正要等的是 65617 请求特征配置。");
        }
        try {
            if (service == MSG_MD_PROTOCOL_VERSION_MATCH) {
                // 参考实现：手机回版本匹配后，车机**先发统计信息 98343**（并启动 1000ms 心跳）
                if (gate(service)) {
                    log.log("   手机已回版本匹配状态 → 发统计信息 98343（参考实现这一步在设备信息之前，心跳也在这里起）");
                    sendStatistics();
                }
            } else if (service == MSG_MD_INFO) {
                // ★ v3.9 修正：参考实现是**收到手机设备信息才发 98307**（v3.8 我们提前到 65538 就发了）
                if (gate(service)) {
                    log.log("   收到手机设备信息 65540 → 发车机设备信息 98307"
                            + "（21 个字段全填 + **带 display**；参考实现就是在这个位置发的）");
                    sendHuInfo();
                }
            } else if (service == MSG_MD_AUTHEN_RESULT) {
                // ★ 参考实现的关键顺序：鉴权结果到了，车机才发 98380 + 98311
                if (gate(service)) {
                    log.log("   ★ 收到手机鉴权结果 65611 → 发 98380 确认，再发 98311 编码参数"
                            + "（参考实现说\"鉴权后车机必须主动发 98311 促使手机启动视频协商\"）");
                    sendCmd(MSG_HU_AUTHEN_RESULT, "");
                    sendVideoEncoderInit();
                }
            } else if (service == MSG_MD_AUTHEN_RESPONSE) {
                if (gate(service)) {
                    log.log("   收到手机鉴权响应 65609 → 发 98380 确认");
                    sendCmd(MSG_HU_AUTHEN_RESULT, "");
                }
            } else if (service == MSG_MD_FEATURE_CONFIG_REQ) {
                if (gate(service)) {
                    log.log("   手机请求特征配置 65617 → 发 98386（带 USB_MTU / I_FRAME_INTERVAL / CONNECT_TYPE）");
                    sendFeatureConfig();
                }
            } else if (service == MSG_MD_VIDEO_ENCODER_INIT) {
                // 65544 = 手机报它的编码器参数 → 只回 98313（回 98311 会自激，见 v3.6）
                // 顺手把手机自报的参数记下来：它报的才是它真能编的（实测它会先回我们的 30，再自己改成 20）
                if (gateMax(service, 2)) {
                    int[] p = parseEncoderInfo(payload);
                    if (p != null) {
                        phoneW = p[0];
                        phoneH = p[1];
                        phoneFps = p[2];
                        log.log("   （记下手机自报的编码参数: " + phoneW + "x" + phoneH + " @" + phoneFps
                                + "fps —— 下一步按**它的**参数回 98311，别再硬塞我们的）");
                        // 这个尺寸就是 CarLife 画面的真实尺寸：CarPlay 流要按它开，车机才不会拉伸
                        VideoSender.setCarLifeSize(phoneW, phoneH, log);
                    }
                    log.log("   ★ 手机报了编码器参数 → **只回 98313 确认开始推流**"
                            + "（不再重发 98311，避免把对面拖进死循环）");
                    sendCmd(MSG_VIDEO_ENCODER_START, "");
                    expectVideo();
                    // ★ v2.1：**不再自激**。v1.x 这里会"3 秒没等到 65551 就重发 98311、
                    // 再换 CONNECT_TYPE 重发 98386"，结果 CarLife+ 一直在重新协商、永远进不了
                    // "已连上车机"状态（表现：手机一直广播 CarlifeHost，车机上是一张静止画面）。
                    // 参考实现（carlife_pc_tool/state_machine.py）在这个位置**什么都不发**：
                    // 只有真的收到 65551（编码器就绪）时，车机才再发一次 98311 确认。
                    if (!encDoneSeen) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(8000);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                if (running && !encDoneSeen) {
                                    log.log("   （8 秒没等到 65551 编码器就绪 —— 按参考实现**不重发**，"
                                            + "避免把 CarLife+ 拖在重新协商里；画面已经在推了）");
                                }
                            }
                        }, "enc-done-wait").start();
                    }
                }
            } else if (service == MSG_MD_VIDEO_ENCODER_INIT_DONE) {
                encDoneSeen = true;
                // 参考实现：手机说编码器就绪(65551) → 车机**再发一次 98311**（不是 98313）
                if (gate(service)) {
                    log.log("   ★★ 手机说编码器就绪 65551 → 按参考实现再发一次 98311 确认"
                            + "（能走到这一步说明它真的要开始编了）");
                    sendVideoEncoderInit();
                }
            }
        } catch (Throwable t) {
            log.log("   !! 应答失败: " + t);
        }
    }

    private int bump(long service) {
        Integer c = msgCount.get((int) service);
        int n = (c == null ? 0 : c) + 1;
        msgCount.put((int) service, n);
        return n;
    }

    /** 同一个 service 的自动应答最多 1.5 秒一次（对面重发时别跟着刷） */
    private boolean gate(long service) {
        long now = System.currentTimeMillis();
        Long last = lastReplyAt.get((int) service);
        if (last != null && now - last < 1500) {
            return false;
        }
        lastReplyAt.put((int) service, now);
        return true;
    }

    /** 同一个 service 最多应答 max 次 */
    private boolean gateMax(long service, int max) {
        Integer c = replyCount.get((int) service);
        int n = (c == null ? 0 : c) + 1;
        replyCount.put((int) service, n);
        return n <= max;
    }

    /** 确认开始推流后，盯 10 秒看通道 2 上有没有视频帧；同时按剧本补发几发"启动"报文 */
    private void expectVideo() {
        if (videoExpectLogged) {
            return;
        }
        videoExpectLogged = true;
        log.log("   ★ 已确认开始推流 → 等通道 2（8240）上的视频帧（service=131073）…");
        startTriggers();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i <= 4; i++) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    if (videoFrames > 0) {
                        return;
                    }
                    Socket vs = videoSocket;
                    String state;
                    if (vs == null) {
                        state = "没连上";
                    } else if (vs.isClosed()) {
                        state = "**已断开**（手机把视频通道关了 —— 它不想推流）";
                    } else if (!vs.isConnected()) {
                        state = "未连接";
                    } else {
                        state = "仍连着";
                    }
                    log.log("   [等视频 " + (i * 2500 / 1000) + "s] 通道 2 " + state
                            + "；这上面一共收到 " + videoChMsgs + " 个报文 / " + videoChBytes + " 字节"
                            + (videoChMsgs == 0 ? "（= 手机一个字节都没往视频通道写）" : ""));
                }
                if (running && videoFrames == 0) {
                    log.log("   !! 10 秒没收到视频帧。我们的车机端现在已经和参考实现"
                            + "（MeshHun/carlife_pc_tool）**逐条一致**了："
                            + "98305 → 98343 → 98307(收到65540才发) → 98380+98311(收到65611才发) → "
                            + "98386(带三项) → 98313 → 98311(收到65551) + 1s 心跳 131074。"
                            + "所以剩下的变量都在**手机侧**（见下）—— 把这份日志发我。");
                    log.log("   → ★ v3.20 已确认的根因，按顺序查：");
                    log.log("     ① **Car+（com.oplus.ocar）没在前台** —— 这是实测出来的根因：CarLife+ 只是"
                            + "Car+ 的**组件**，要编码的画面得由 Car+ 通过 cast 服务交给它一个 Surface。"
                            + "Car+ 不在前台，CarLife+ 就只握手、**不开编码器**（通道 2 上 0 字节，"
                            + "甚至一连上就被 reset）。对照 6 份日志：接 CarLife **之前**先拉过 Car+ 的"
                            + "（v3.13/14/15/17）全都有画面；没拉过的（v3.18/19）全是 0 帧。"
                            + "本版 ② 已经会自动先拉 Car+，如果这里还是 0 字节 → 手动打开 Car+ 停在车机界面，"
                            + "再点【②】。");
                    log.log("     ② CarLife+ 自己的界面上有\"连接/开始\"之类的按钮或弹框没点"
                            + "（把 CarLife+ 切到前台看一眼，该点就点）；");
                    log.log("     ③ 它要**蓝牙先连上车机**才肯推流（先点【蓝牙设置】连上 DiPlay，再走 ②③）。");
                    scheduleRetry("握手全通但手机没推流 —— 实测它有时推有时不推，重来一轮多半就好了");
                }
            }
        }, "video-expect").start();
    }

    /**
     * 握手末尾的"启动"试探序列（有界、只跑一次、每步都写日志）。
     *
     * 为什么要有这个：v3.6 实测握手全跑完（65544 都回了）但**手机就是不推视频帧** ——
     * 说明还差一发"启动"报文，而参考实现里 98311 是"握手末尾还要再发一次确认"、
     * 98386 特征配置是手机请求 65617 后车机才回（这版手机没请求，只丢了个 65574 的列表）、
     * 98343 统计信息在规格里是"可选"。到底哪一发是钥匙，猜不出来 —— 就**按顺序各打一发并记日志**，
     * 下一份日志里"哪一发之后手机开始回新报文/开始推流"就是答案。
     */
    private void startTriggers() {
        if (triggersStarted) {
            return;
        }
        triggersStarted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                triggerStep(1500, "特征配置列表 98386（带 USB_MTU/I_FRAME_INTERVAL/CONNECT_TYPE）"
                                + "—— 手机没主动请求 65617 时兜底发一发",
                        new Runnable() {
                            @Override
                            public void run() {
                                sendFeatureConfig();
                            }
                        });
                triggerStep(3000, "再发一次编码参数 98311（参考实现：手机报编码器就绪 65551 时车机要再发一次）",
                        new Runnable() {
                            @Override
                            public void run() {
                                sendVideoEncoderInit();
                            }
                        });
                triggerStep(4500, "再确认开始推流 98313",
                        new Runnable() {
                            @Override
                            public void run() {
                                sendCmd(MSG_VIDEO_ENCODER_START, "");
                            }
                        });
            }
        }, "carlife-triggers").start();
    }

    private void triggerStep(final long delayMs, final String what, final Runnable action) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) {
                    return;
                }
                if (videoFrames > 0) {
                    log.log("   （已经收到视频帧了 → 后面的试探报文不再发）");
                    return;
                }
                log.log("   → 试探：发 " + what);
                action.run();
            }
        }, "trigger").start();
    }

    /** 车机统计信息 98343（参考实现用的就是这些值：原生百度车机的公开测试渠道号 + 固定 CUID） */
    private void sendStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append(pbString(1, "12345678"));   // cuid
        sb.append(pbString(2, "4.0.0"));      // versionName
        sb.append(pbInt(3, 16));              // versionCode
        sb.append(pbString(4, "20029999"));   // channel
        sb.append(pbInt(5, 0));               // connectCount
        sb.append(pbInt(6, 0));               // connectSuccessCount
        sb.append(pbInt(7, 0));               // connectTime
        sendCmd(MSG_HU_STATISTICS, sb.toString());
    }

    // ------------------------------------------------------------------ 发送

    private void sendCmd(int service, String payload) {
        try {
            Socket s = cmdSocket;
            if (s == null || s.isClosed()) {
                return;
            }
            OutputStream out = s.getOutputStream();
            out.write(frame(1, service, payload == null ? "" : payload));
            out.flush();
            fold.log(">> 通道1 service=" + service + "（" + serviceName(service) + "）");
        } catch (Throwable t) {
            fold.log("!! 发送失败: " + brief(t));
        }
    }

    /**
     * 车机设备信息 98307 —— **必须伪装成原生 Android 车机，而且必须带 display**。
     *
     * 参考实现（MeshHun/carlife_pc_tool 的 `send_hu_device_info`，依据反编译源码
     * `sources/d/b/a/a/p/m/a/c.java:91` + `sources/d/b/a/a/o/c.java:368`）把 21 个字段全填了，
     * 其中 `display="{w}x{h}"` 是手机拿去做编码尺寸参考的。v3.7 我们只填了 5 个字段、**没有 display**，
     * 实测手机收到后就没再往下走（不发明文鉴权结果 65611，也不推流）。
     */
    private void sendHuInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(pbString(1, "Android"));            // os
        sb.append(pbString(2, "universal"));          // board
        sb.append(pbString(3, "unknown"));            // bootloader
        sb.append(pbString(4, "baidu"));              // brand
        sb.append(pbString(5, "arm64-v8a"));          // cpuAbi
        sb.append(pbString(6, "armeabi-v7a"));        // cpuAbi2
        sb.append(pbString(7, "carlife"));            // device
        sb.append(pbString(8, WIDTH + "x" + HEIGHT)); // display  ★ 关键
        sb.append(pbString(9, "baidu/carlife/carlife:11/RQ3A.211001.001/1:user/release-keys")); // fingerprint
        sb.append(pbString(10, "universal"));         // hardware
        sb.append(pbString(11, "carlife-build"));     // host
        sb.append(pbString(12, "RQ3A.211001.001"));   // cid
        sb.append(pbString(13, "baidu"));             // manufacturer
        sb.append(pbString(14, "CarLifeVehicle"));    // model
        sb.append(pbString(15, "carlife"));           // product
        sb.append(pbString(16, "unknown"));           // serial
        sb.append(pbString(17, "REL"));               // codename
        sb.append(pbString(18, "1"));                 // incremental
        sb.append(pbString(19, "11"));                // release
        sb.append(pbString(20, "30"));                // sdk
        sb.append(pbInt(21, 30));                     // sdkInt
        sb.append(pbString(22, "4.0.0"));             // carlifeversion
        sendCmd(MSG_HU_INFO, sb.toString());
    }

    /**
     * ★ v2.2：让 CarLife+ **重新出参数集** —— 发 98311（参考实现里它同时是"重置推流"）。
     *
     * 什么时候需要：我们是在 CarLife+ 已经推流之后才起 CarPlay 流的，而它的 SPS/PPS
     * **只出现在第一帧**里，那一帧早就过去了 → 我们手上没有参数集 → VideoConfig 发不出去
     * → 车机解码器起不来（表现：车机一直停在**上一次会话的旧画面**上不动）。
     * 让 CarLife+ 重置一次，它就会重新发 SPS/PPS + 一个 I 帧。
     */
    public static boolean requestEncoderReset(String why) {
        CarLifeProbe p = LIVE;
        if (p == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - p.lastResetAskMs < 5000) {
            return false;              // 5 秒内只求一次，别把对面拖进反复重协商
        }
        p.lastResetAskMs = now;
        p.log.log("   ★ 手上没有 CarLife 的参数集 → 发 98311 让它**重置编码器**重新出 SPS/PPS+I 帧（" + why + "）");
        p.sendVideoEncoderInit();
        return true;
    }

    /** 上次请求重置的时间（限流用） */
    private volatile long lastResetAskMs;

    private void sendVideoEncoderInit() {
        String p = pbInt(1, WIDTH) + pbInt(2, HEIGHT) + pbInt(3, FPS);
        sendCmd(MSG_HU_VIDEO_ENCODER_INIT, p);
    }

    /**
     * 特征配置 98386 —— **必须带那三项**，不能发空列表。
     *
     * 参考实现（依据 `sources/d/b/a/a/p/m/a/f.java:69-80` + `VehicleApplication.java:120`）发的是：
     * `cnt=3`、`huBtAudioSupport=false`、`huBtName`、`huBtMAC`，
     * 外加 `USB_MTU=16384`、`I_FRAME_INTERVAL=300`、`CONNECT_TYPE=2`。
     * v3.7 我们只发了 `cnt=0` 的空列表 —— 手机拿不到 `I_FRAME_INTERVAL` 这类编码参数，多半就是它不推流的原因。
     */
    private void sendFeatureConfig() {
        sendFeatureConfig(2);
    }

    /**
     * @param connectType `CONNECT_TYPE` 的值：参考实现固定发 **2**，但它是**走 USB（adb forward）**连的；
     *                    我们是从网络进手机，真实的车机在 Wi-Fi 下该报什么值没人能确认 ——
     *                    所以 4 秒内等不到 `65551` 就换成 **1** 再发一次（两条都试，日志里记清楚）。
     */
    private void sendFeatureConfig(int connectType) {
        StringBuilder sb = new StringBuilder();
        sb.append(pbInt(1, 3));                                        // cnt
        sb.append(pbMsg(2, pbString(1, "USB_MTU") + pbInt(2, 16384)));  // featureConfig[0]
        sb.append(pbMsg(2, pbString(1, "I_FRAME_INTERVAL") + pbInt(2, 300)));  // [1]
        sb.append(pbMsg(2, pbString(1, "CONNECT_TYPE") + pbInt(2, connectType)));  // [2]
        sb.append(pbString(4, "CarLife_PC"));                          // huBtName
        sb.append(pbString(5, "00:11:22:33:44:55"));                   // huBtMAC
        sendCmd(MSG_HU_FEATURE_CONFIG_LIST, sb.toString());
    }

    /** 嵌套消息（wire type 2）：[tag][长度][内层字节] */
    private static String pbMsg(int field, String inner) {
        byte[] b = inner.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((field << 3) | 2);
        writeVarint(out, b.length);
        out.write(b, 0, b.length);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** 组帧：通道 1/6 = [u16 len][u16 rsv][u32 service]，其余 = [u32 len][u32 ts][u32 service] */
    static byte[] frame(int channel, int service, String payload) {
        byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (channel == 1 || channel == 6) {
            out.write((body.length >>> 8) & 0xff);
            out.write(body.length & 0xff);
            out.write(0);
            out.write(0);
        } else {
            out.write((body.length >>> 24) & 0xff);
            out.write((body.length >>> 16) & 0xff);
            out.write((body.length >>> 8) & 0xff);
            out.write(body.length & 0xff);
            int ts = (int) (System.currentTimeMillis() & 0x7fffffff);
            out.write((ts >>> 24) & 0xff);
            out.write((ts >>> 16) & 0xff);
            out.write((ts >>> 8) & 0xff);
            out.write(ts & 0xff);
        }
        out.write((service >>> 24) & 0xff);
        out.write((service >>> 16) & 0xff);
        out.write((service >>> 8) & 0xff);
        out.write(service & 0xff);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------- 极简 protobuf 编码

    private static String pbInt(int field, int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((field << 3) | 0);
        writeVarint(out, value);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String pbString(int field, String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((field << 3) | 2);
        writeVarint(out, b.length);
        out.write(b, 0, b.length);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 静默读一小段（不发送任何东西），返回 hex；超时/无数据返回 null */
    private static String quietRead(Socket s, int ms) {
        try {
            s.setSoTimeout(ms);
            byte[] b = new byte[256];
            int n = s.getInputStream().read(b);
            if (n > 0) {
                return Crypto.hex(java.util.Arrays.copyOf(b, n));
            }
        } catch (Throwable ignored) {
        }
        try {
            s.setSoTimeout(0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 90) {
            m = m.substring(0, 90) + "…";
        }
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return null;
            }
            off += n;
        }
        return buf;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static String channelName(int c) {
        switch (c) {
            case 1: return "控制";
            case 2: return "视频";
            case 3: return "媒体音频";
            case 4: return "TTS";
            case 5: return "语音";
            case 6: return "触控";
            case 7: return "车况";
            default: return "?";
        }
    }

    private static String serviceName(long s) {
        switch ((int) s) {
            case MSG_HU_PROTOCOL_VERSION: return "车机:版本协商";
            case MSG_MD_PROTOCOL_VERSION_MATCH: return "手机:版本匹配状态";
            case MSG_HU_STATISTICS: return "车机:统计信息";
            case MSG_HU_INFO: return "车机:设备信息";
            case MSG_MD_INFO: return "手机:设备信息";
            case MSG_MD_AUTHEN_RESPONSE: return "手机:鉴权响应";
            case MSG_MD_AUTHEN_RESULT: return "手机:鉴权结果";
            case MSG_HU_AUTHEN_RESULT: return "车机:鉴权确认";
            case MSG_MD_FEATURE_CONFIG_REQ: return "手机:请求特征配置";
            case MSG_MD_FEATURE_LIST: return "手机:模块状态列表";
            case MSG_MD_KEEPALIVE: return "手机:保活心跳";
            case 65560: return "手机:CarLife进入前台";
            case 65561: return "手机:CarLife退入后台";
            case 65570: return "手机:请求语音唤醒录音";
            case 65584: return "手机:导航转向信息";
            case MSG_HU_FEATURE_CONFIG_LIST: return "车机:特征配置";
            case MSG_HU_VIDEO_ENCODER_INIT: return "车机:视频编码参数";
            case MSG_MD_VIDEO_ENCODER_INIT: return "手机:视频编码参数";
            case MSG_MD_VIDEO_ENCODER_INIT_DONE: return "手机:编码器就绪";
            case MSG_VIDEO_ENCODER_START: return "车机:确认开始推流";
            case MSG_HU_HEARTBEAT: return "车机:心跳";
            case MSG_VIDEO_DATA: return "视频帧数据";
            case MSG_MD_BACK_TO_HOME: return "手机:返回车机主页";
            default: return "未定义";
        }
    }

    public void close() {
        running = false;
        if (LIVE == this) {
            LIVE = null;
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        sockets.clear();
        DatagramSocket u = udp7999;
        udp7999 = null;
        if (u != null) {
            try {
                u.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 极简 protobuf 解码，用于把手机发来的报文打成可读字段 */
    static final class Pb {
        static String decode(byte[] data) {
            StringBuilder sb = new StringBuilder();
            try {
                int i = 0;
                while (i < data.length) {
                    long tag = readVarint(data, i);
                    int field = (int) (tag >>> 3);
                    int wire = (int) (tag & 7);
                    i += varintLen(data, i);
                    if (wire == 0) {
                        long v = readVarint(data, i);
                        sb.append("f").append(field).append("=").append(v).append(' ');
                        i += varintLen(data, i);
                    } else if (wire == 2) {
                        long len = readVarint(data, i);
                        i += varintLen(data, i);
                        int n = (int) len;
                        if (n < 0 || i + n > data.length) {
                            break;
                        }
                        String s = new String(data, i, n, StandardCharsets.UTF_8);
                        boolean printable = n > 0;
                        for (int k = 0; k < s.length(); k++) {
                            char c = s.charAt(k);
                            if (c < 0x20 || c > 0x7e) {
                                printable = false;
                                break;
                            }
                        }
                        if (printable) {
                            sb.append("f").append(field).append("=\"").append(s).append("\" ");
                        } else {
                            sb.append("f").append(field).append("=(").append(n).append("字节) ");
                        }
                        i += n;
                    } else if (wire == 5) {
                        i += 4;
                    } else if (wire == 1) {
                        i += 8;
                    } else {
                        break;
                    }
                }
            } catch (Throwable t) {
                return sb.append("（解析中断）").toString();
            }
            return sb.toString().trim();
        }

        private static long readVarint(byte[] b, int off) {
            long v = 0;
            int shift = 0;
            while (off < b.length) {
                int x = b[off++] & 0xff;
                v |= ((long) (x & 0x7f)) << shift;
                if ((x & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            return v;
        }

        private static int varintLen(byte[] b, int off) {
            int n = 0;
            while (off + n < b.length && (b[off + n] & 0x80) != 0) {
                n++;
            }
            return n + 1;
        }
    }
}

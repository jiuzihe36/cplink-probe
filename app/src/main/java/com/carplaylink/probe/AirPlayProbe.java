package com.carplaylink.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * P1：拿到车机热点凭据后，连上热点并用 RTSP 打进车机的 AirPlay 端口。
 *
 * 无线 CarPlay 第二段就是标准 AirPlay 2：手机(发送端) 连车机(接收端) 的 7000 端口，
 * 依次 OPTIONS → GET /info → POST /pair-setup(SRP-6a 3072/SHA-512, PIN 3939)
 * → POST /pair-verify(X25519+Ed25519) → POST /auth-setup(MFi-SAP) → SETUP → RECORD。
 */
public final class AirPlayProbe {

    public interface Logger {
        void log(String line);
    }

    private AirPlayProbe() {
    }

    /** 已经申请到的车机热点网络：全程不撤销（撤销会让 socket 直接 EPERM 死掉） */
    private static volatile Network heldNetwork;
    private static volatile ConnectivityManager.NetworkCallback heldCallback;
    private static volatile String heldSsid;

    /** 连车机热点。allowRequest=false 时绝不弹系统的 Wi-Fi 申请框，直接用手机当前连着的网络 */
    public static Network joinHotspot(Context ctx, String ssid, String pass, Logger log, boolean allowRequest)
            throws Exception {
        if (ssid == null || ssid.isEmpty()) {
            log.log("!! 车机没给 SSID，跳过连热点");
            return null;
        }
        // 0) 上一轮已经申请到同一个热点：直接复用
        if (heldNetwork != null && ssid.equals(heldSsid)) {
            log.log("★ 复用已持有的车机网络（不再申请）");
            return heldNetwork;
        }
        logWifiState(ctx, ssid, log);

        // 1) 默认路径：手机自己连着的 Wi-Fi 就是车机热点 —— 不弹任何框
        if (!allowRequest) {
            Network active = activeWifiNetwork(ctx, log);
            if (active != null) {
                heldNetwork = active;
                heldSsid = ssid;
                log.log("   （按设置：不弹 Wi-Fi 申请框，直接用手机当前连着的网络）");
                return active;
            }
            log.log("!! 手机现在没连 Wi-Fi —— 请手动连上车机热点 " + ssid
                    + "，或点【连车机热点】按钮走系统申请流程");
            return null;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log.log("!! Android 10 以下不能在应用内连热点，请手动连到 " + ssid);
            return activeWifiNetwork(ctx, log);
        }
        // 2) 手动申请路径（只有点【连车机热点】才会走到这里，会弹系统框）
        for (int attempt = 1; attempt <= 3; attempt++) {
            log.log("正在申请连车机热点 " + ssid + "（第 " + attempt + "/3 次；手机上会弹确认框，点允许）…");
            Network n = requestOnce(ctx, ssid, pass, log);
            if (n != null) {
                heldNetwork = n;
                heldSsid = ssid;
                log.log("★ 已连上车机热点 " + ssid + "（这个网络本轮不再撤销）");
                return n;
            }
            Thread.sleep(2000);
        }
        log.log("!! 3 次都没申请到热点，退一步用手机当前的活动 Wi-Fi 网络");
        Network active = activeWifiNetwork(ctx, log);
        if (active != null) {
            heldNetwork = active;
            heldSsid = ssid;
        }
        return active;
    }

    /**
     * 手机当前连着的 Wi-Fi 网络。
     * 注意：不能用 getActiveNetwork() —— 车机热点没有外网时，系统会把"活动网络"算成蜂窝网，
     * 于是明明连着车机热点却拿不到它。所以在所有网络里找 TRANSPORT_WIFI。
     */
    private static Network activeWifiNetwork(Context ctx, Logger log) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network fallback = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        // 优先能上网的 Wi-Fi（一般不是车机热点）
                        if (fallback == null) {
                            fallback = n;
                        }
                    } else {
                        return n;
                    }
                }
            }
            if (fallback != null) {
                log.log("   用手机当前连着的 Wi-Fi 网络");
                return fallback;
            }
            log.log("   没找到 Wi-Fi 网络");
        } catch (Throwable t) {
            log.log("   取 Wi-Fi 网络失败: " + t);
        }
        return null;
    }

    private static Network requestOnce(Context ctx, String ssid, String pass, Logger log) throws Exception {
        WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (pass != null && !pass.isEmpty()) {
            b.setWpa2Passphrase(pass);
        }
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(b.build())
                .build();
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            log.log("!! 没有 ConnectivityManager");
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Network[] holder = new Network[1];
        final boolean[] unavailable = {false};
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                holder[0] = network;
                latch.countDown();
            }

            @Override
            public void onUnavailable() {
                unavailable[0] = true;
                latch.countDown();
            }
        };
        cm.requestNetwork(req, cb, 20000);
        boolean done = latch.await(25, TimeUnit.SECONDS);
        if (holder[0] != null) {
            // 成功：回调保持注册状态，网络就不会被系统回收
            heldCallback = cb;
            return holder[0];
        }
        try {
            cm.unregisterNetworkCallback(cb);
        } catch (Throwable ignored) {
        }
        if (!done) {
            log.log("   请求超时（25 秒没有任何回调）");
        } else if (unavailable[0]) {
            log.log("   系统直接回了 onUnavailable（多半是弹框没允许，或该热点已连但被系统拒绝）");
        }
        return null;
    }

    private static void logWifiState(Context ctx, String ssid, Logger log) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                log.log("Wi-Fi 状态: 拿不到 WifiManager");
                return;
            }
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            String cur = (info == null || info.getSSID() == null) ? "未知"
                    : info.getSSID().replace("\"", "");
            log.log("Wi-Fi 状态: 开关=" + wm.isWifiEnabled() + "  当前连着=" + cur
                    + "  目标=" + ssid + "  已持有车机网络=" + (heldNetwork != null));
        } catch (Throwable t) {
            log.log("Wi-Fi 状态: 读取失败 " + t);
        }
    }

    /** 列出本机在该网络上的 IPv4 地址（诊断用） */
    public static String ownAddresses(Context ctx, Network network) {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || network == null) {
                return "?";
            }
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) {
                return "?";
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() != null && la.getAddress().getHostAddress().contains(".")) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(la.getAddress().getHostAddress());
                }
            }
            String iface = lp.getInterfaceName();
            if (iface != null) {
                sb.append(" (网卡 ").append(iface).append(")");
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    /** 本机在该网络上的第一个 IPv4 地址（没有则 null） */
    public static String localIpv4(Context ctx, Network network) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = (cm == null || network == null) ? null : cm.getLinkProperties(network);
            if (lp == null) {
                return null;
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() != null && la.getAddress().getHostAddress().contains(".")) {
                    return la.getAddress().getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从 LinkProperties 里取默认网关（车机热点场景下，网关可能就是车机） */
    public static String gatewayOf(Context ctx, Network network) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || network == null) {
                return null;
            }
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) {
                return null;
            }
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null) {
                    return r.getGateway().getHostAddress();
                }
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() != null) {
                    String a = la.getAddress().getHostAddress();
                    if (a.contains(".")) {
                        int idx = a.lastIndexOf('.');
                        return a.substring(0, idx + 1) + "1";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 找车机 → OPTIONS → GET /info → pair-setup → pair-verify → auth-setup → SETUP → 推流 */
    public static boolean rtspProbe(android.content.Context ctx, Network network, String gatewayHint, int port, Logger log) {
        RtspClient rtsp = null;
        try {
            log.log("—— 找车机（AirPlay 接收端）——");
            String host = null;
            // ★ v1.5：找车机**多试几轮**。车机（DiPlay）可能比我们晚起来、mDNS 还没广播出来 ——
            // 以前只找一次，找不到整条流程就白跑（日志：只发现 Mac → 共 1 个候选 → 全是 403 → 结束）。
            for (int round = 0; round < 3 && rtsp == null; round++) {
                if (round > 0) {
                    log.log("   （没找到车机 → 第 " + round + " 次重找，等 6 秒（车机可能刚起来）…）");
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException ignored) {
                        // 继续
                    }
                }
                java.util.List<AirPlayDiscovery.Found> targets =
                        AirPlayDiscovery.discoverAll(ctx, network, gatewayHint, log);
                if (targets.isEmpty()) {
                    log.log("   （这一轮一个 AirPlay 设备都没发现）");
                    continue;
                }
                for (AirPlayDiscovery.Found t : targets) {
                    log.log("试 " + t + " …");
                    RtspClient candidate;
                    try {
                        candidate = new RtspClient(network, t.host, t.port, log);
                    } catch (Throwable e) {
                        log.log("   连不上: " + e);
                        continue;
                    }
                    RtspClient.Response opt;
                    try {
                        opt = candidate.request("OPTIONS", "*", null, null);
                    } catch (Throwable e) {
                        log.log("   OPTIONS 失败: " + e);
                        candidate.close();
                        continue;
                    }
                    if (opt.status == 200) {
                        log.log("★ 这台是车机: " + t + "（OPTIONS 200）");
                        rtsp = candidate;
                        host = t.host;
                        break;
                    }
                    log.log("   " + t + " 回 " + opt.statusLine + "（不是车机，换下一台）");
                    candidate.close();
                }
            }
            if (rtsp == null) {
                log.log("!! 找了三轮都没找到车机 —— 请确认**假车机 DiPlay 开着**（它在广播 _airplay._tcp）、"
                        + "手机连着车机热点" + (heldSsid == null ? "" : "（" + heldSsid + "）"));
                return false;
            }

            RtspClient.Response r = rtsp.request("GET", "/info", null, "application/x-apple-binary-plist");
            logResponse(log, "GET /info", r);

            // P1 第二步：pair-setup（SRP-6a，固定 PIN 3939）
            if (!PairSetupClient.run(rtsp, log)) {
                log.log("!! pair-setup 没跑通，后面的 pair-verify 先不跑");
                return false;
            }
            // P1 第三步：pair-verify（X25519 + Ed25519）→ 控制通道转加密
            if (!PairVerifyClient.run(rtsp, log)) {
                log.log("!! pair-verify 没跑通，后面的 auth-setup 先不跑");
                return false;
            }
            // P1 第四步：auth-setup（MFi-SAP）—— 加密通道的第一次真实往返
            if (!MfiSapClient.run(rtsp, log)) {
                return false;
            }
            // P1 第五步：SETUP（协商端口 + 给视频流 type=110 开数据端口）
            VideoSender.resolveSize(ctx, log);
            AirPlaySetup.Result setup = AirPlaySetup.run(rtsp, host, log);
            if (setup == null) {
                return false;
            }
            // P1 五点四步：★ v2.0 音频流 —— 车机给了 audioDataPort 就派生密钥、开 UDP 发送线程
            if (setup.audioDataPort > 0) {
                // ★ v2.1：**先**把上一轮的 stop 标志清掉，否则音频线程一起来就退出（发 0 包）
                VideoSender.stopRequested = false;
                byte[] akey = Crypto.hkdfSha512(Pairing.sharedSecret,
                        Crypto.ascii("DataStream-Salt" + setup.audioConnectionId),
                        Crypto.ascii("DataStream-Output-Encryption-Key"), 32);
                log.log("   音频流密钥 = HKDF(shared, \"DataStream-Salt" + setup.audioConnectionId
                        + "\", \"DataStream-Output-Encryption-Key\") = " + Crypto.hex(akey, 8) + "…");
                AudioSender.start(host, setup.audioDataPort, akey, log);
            } else {
                log.log("   （这条会话没有音频流 —— 车机没给音频数据端口）");
            }
            // P1 五点五步：连车机的 eventPort 当"触摸嗅探"—— 车机上的点按如果从这条通道来，
            // 日志里就会看到原始字节，我们才知道怎么把它转成 CarLife 的触控报文。
            if (setup.eventPort > 0) {
                startEventSniffer(network, host, setup.eventPort, log);
            }
            // P1 第六步：连视频数据端口，发 avcC + 推加密视频帧
            return new VideoSender(ctx, network, host, setup.dataPort, setup.connectionId, log).run();
        } catch (Throwable t) {
            log.log("!! AirPlay RTSP 探测失败: " + t);
            return false;
        } finally {
            if (rtsp != null) {
                rtsp.close();
            }
        }
    }

    private static void logResponse(Logger log, String tag, RtspClient.Response r) {
        log.log("<< [" + tag + "] " + r.statusLine + "  (" + r.body.length + " 字节 body)");
        for (String k : new String[]{"public", "server", "content-type", "cseq"}) {
            String v = r.header(k);
            if (v != null) {
                log.log("   " + k + ": " + v);
            }
        }
        if (r.body.length > 0) {
            log.log("   body 前 256 字节: " + Crypto.hex(r.body, 256));
            String ascii = ascii(r.body);
            if (!ascii.isEmpty()) {
                log.log("   body 里可读内容: " + ascii);
            }
        }
    }

    /**
     * 连上车机的 eventPort，把收到的**任何**东西原样打出来（触摸嗅探）。
     *
     * 为什么要有它：AirPlay 镜像里，车机上的触摸/按键是由接收端往这条通道回送的。
     * 我们原来只把 eventPort 打印出来、从没连上去读过，所以车机怎么点都传不到 CarLife。
     * 先把原始字节看清（是 bplist 还是 TLV、坐标是像素还是归一化），再决定怎么转成
     * CarLife 的 `425985 触控报文`（通道 6）。
     */
    /** ★ v1.9：event 通道的活体计数 —— 触摸不通时要能一眼分清"通道死了"还是"车机根本没发" */
    static volatile int eventSeen;
    static volatile int touchSeen;
    static volatile boolean eventConnected;
    static volatile long eventConnectedAt;

    private static void startEventSniffer(final Network network, final String host, final int port,
                                          final AirPlayProbe.Logger log) {
        // ★ v1.9：心跳 —— 车机一直不发消息时也要有行日志，证明通道还活着；
        // 并且把"触摸 0 条"直接写出来（这就是"不能触摸"的判据）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!VideoSender.stopRequested) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (VideoSender.stopRequested || !eventConnected) {
                        continue;
                    }
                    long secs = (System.currentTimeMillis() - eventConnectedAt) / 1000;
                    log.log("   （event 通道心跳：已连 " + secs + " 秒，收到车机消息 " + eventSeen
                            + " 条，其中触摸 " + touchSeen + " 条"
                            + (touchSeen == 0 ? " —— **车机一次触摸都没发过来**" : "") + "）");
                }
            }
        }, "event-heartbeat").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ★ v1.0：**断了要自己重连**。实测车机（DiPlay）会主动掐掉这条连接，
                // 以前一断就再也不连了 → 车机后面的触摸和 forceKeyFrame 请求全部收不到
                // （表现：投屏在跑，但点车机没反应）。现在只要会话还在就循环重连。
                for (int round = 0; !VideoSender.stopRequested; round++) {
                if (round > 0) {
                    log.log("   （event 通道断了 → 第 " + round + " 次重连（触摸/关键帧请求走它）…）");
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    if (VideoSender.stopRequested) {
                        break;
                    }
                }
                Socket s = null;
                try {
                    s = (network != null) ? network.getSocketFactory().createSocket() : new Socket();
                    s.connect(new InetSocketAddress(host, port), 5000);
                    s.setTcpNoDelay(true);
                    log.log("★ 已连上车机 eventPort " + host + ":" + port
                            + "（触摸通道）—— **去车机上点一下试试**");
                    eventConnected = true;
                    eventConnectedAt = System.currentTimeMillis();
                    java.io.InputStream in = s.getInputStream();
                    byte[] buf = new byte[4096];
                    byte[] pending = new byte[0];
                    int n;
                    int seen = 0;
                    while ((n = in.read(buf)) > 0) {
                        byte[] d = new byte[n];
                        System.arraycopy(buf, 0, d, 0, n);
                        pending = Crypto.concat(pending, d);
                        // event 通道 = 控制通道那套 ChaCha20-Poly1305 分帧：
                        // [2 字节小端长度][密文][16 字节 tag]；明文是 **RTSP 报文**
                        // （POST /command … + bplist body），不是裸 bplist —— 见 handleEvent
                        while (pending.length >= 18) {
                            int len = (pending[0] & 0xff) | ((pending[1] & 0xff) << 8);
                            int frameEnd = 2 + len + 16;
                            if (len <= 0 || pending.length < frameEnd) {
                                break;
                            }
                            byte[] frame = java.util.Arrays.copyOfRange(pending, 0, frameEnd);
                            pending = java.util.Arrays.copyOfRange(pending, frameEnd, pending.length);
                            seen++;
                            byte[] plain = openEventFrame(frame, log, seen);
                            if (plain != null) {
                                eventSeen++;
                                handleEvent(plain, log);
                                if (RESPOND_EVENTS) {
                                    respondEvent(s, plain, log);
                                }
                            }
                        }
                    }
                    log.log("   （event 通道读结束，共 " + seen + " 条）");
                } catch (Throwable t) {
                    if (!VideoSender.stopRequested) {
                        log.log("   （event 通道连不上/读失败: " + t + " → 会自动重连）");
                    }
                } finally {
                    eventConnected = false;
                    try {
                        if (s != null) {
                            s.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                }   // ← v1.0 重连循环结束（会话没停就一直重连）
            }
        }, "airplay-event").start();
    }

    /**
     * 解开一条 event 帧。
     *
     * 实测车机发来的两条都是 **186 字节、开头 a8 00** —— `2 + 168 + 16 = 186`，正好是
     * ControlCipher 的一帧（2 字节小端长度 + 168 密文 + 16 tag）。所以 event 通道用的就是
     * 控制通道同一套 ChaCha20-Poly1305；明文是 bplist（`a8 00` 异或密钥流正好还原成 "bp"）。
     *
     * 计数器试 0..7：控制通道有自己的收发计数器，event 是另一条连接，从哪个值起我们不知道 ——
     * 直接试到明文以 "bp"（bplist00）开头为止，试到几就记下来。
     */
    /**
     * 判定"这一份明文对不对"。
     *
     * ★ v0.2 的关键修正：event 通道的明文是 **RTSP 报文**（`POST /command RTSP/1.0\r\n…\r\n\r\n` + bplist body），
     * 开头是 `POST`，**不是** `bplist00`。老版本只认 `62 70`（"bp"），所以密钥和计数器全对时
     * 也会被当成"解不开"丢掉 —— 触摸一直不通就是这个原因。
     */
    private static boolean looksLikeEventPlain(byte[] p) {
        if (p.length < 8) {
            return false;
        }
        String head = new String(p, 0, Math.min(p.length, 24), java.nio.charset.StandardCharsets.US_ASCII);
        if (head.startsWith("POST ") || head.startsWith("GET ") || head.startsWith("RTSP")
                || head.startsWith("HTTP") || head.startsWith("bplist") || head.startsWith("plist")) {
            return true;
        }
        // 兜底：前 16 字节全是可打印 ASCII 也算（RTSP 头一定是）
        for (int i = 0; i < 16; i++) {
            int c = p[i] & 0xff;
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 要不要回 `RTSP/1.0 200 OK`。
     *
     * ★ v0.3：**默认关掉**。原因（v0.2 实测）：DiPlay 的 event 读循环里
     * `cipher.decrypt` 一旦抛异常就 `break` → `finally { close() }` —— **一帧解不开就关掉整个会话**。
     * 而我们那次回 200 用错了密钥（DiPlay 的 event 通道 readKey = `Events-Read`、
     * writeKey = `Events-Write`，跟控制通道的镜像命名**不一样**），于是车机立刻把整条会话掐了
     * （日志表现：`Connection reset` + 视频 `Broken pipe`）。
     * 而 DiPlay 的 sendCommand 并不等响应（只有 sendIapMessage 会等），所以**不回也没事**。
     * 想试就把它改成 true（下面用的是正确的 Events-Read 密钥）。
     */
    private static final boolean RESPOND_EVENTS = false;

    /** event 通道的写密钥（回 200 响应用）与写计数器 */
    private static volatile byte[] eventWriteKey;
    private static volatile int eventWriteCounter;

    /** event 通道解密用过的密钥下标（试对一次就记住，别每帧再全搜一遍） */
    private static volatile int eventKeyIdx = -1;
    /** 记住的计数器走到哪了（对端每发一帧 +1） */
    private static volatile int eventCounter;

    private static void addKey(java.util.List<byte[]> ks, java.util.List<String> ns, byte[] k, String n) {
        if (k != null) {
            ks.add(k);
            ns.add(n);
        }
    }

    /**
     * event 通道的一帧 → 明文。
     *
     * 实测车机发来的每条都是 **186 字节、开头 a8 00** —— `2 + 168 + 16 = 186`，正好是
     * ControlCipher 的一帧（2 字节小端长度 + 168 密文 + 16 tag）。明文是 bplist。
     *
     * ★ v3.18：控制密钥试了 读/写 × 计数器 0..511 全失败 → 说明 **event 很可能有自己一套密钥**。
     *   AirPlay 2 里 control 用 `"Control-Salt"`，event 用 `"Events-Salt"` —— v3.19 把 events
     *   两把（读/写）也加进候选，连 `"Event-Salt"` 单数版一起试。
     * ★ 试对一次后记住（密钥下标 + 计数器），之后每帧只试 9 个计数器，不再全搜。
     */
    private static byte[] openEventFrame(byte[] frame, AirPlayProbe.Logger log, int index) {
        int len = (frame[0] & 0xff) | ((frame[1] & 0xff) << 8);
        if (len <= 0 || frame.length < 2 + len + 16) {
            log.log("   [event#" + index + "] 长度对不上（len=" + len + " 总长=" + frame.length + "）");
            return null;
        }
        byte[] aadHeader = java.util.Arrays.copyOfRange(frame, 0, 2);
        byte[] aadNone = new byte[0];
        byte[] ct = java.util.Arrays.copyOfRange(frame, 2, 2 + len + 16);
        byte[][] aads = {aadHeader, aadNone};
        String[] an = {"AAD=长度头", "AAD=空"};

        java.util.List<byte[]> ks = new java.util.ArrayList<byte[]>();
        java.util.List<String> ns = new java.util.ArrayList<String>();
        addKey(ks, ns, Pairing.controlReadKey, "控制读密钥");
        addKey(ks, ns, Pairing.controlWriteKey, "控制写密钥");
        byte[] ss = Pairing.sharedSecret;
        if (ss != null) {
            addKey(ks, ns, Crypto.hkdfSha512(ss, Crypto.ascii("Events-Salt"),
                    Crypto.ascii("Events-Read-Encryption-Key"), 32), "events读密钥");
            addKey(ks, ns, Crypto.hkdfSha512(ss, Crypto.ascii("Events-Salt"),
                    Crypto.ascii("Events-Write-Encryption-Key"), 32), "events写密钥");
            addKey(ks, ns, Crypto.hkdfSha512(ss, Crypto.ascii("Event-Salt"),
                    Crypto.ascii("Event-Read-Encryption-Key"), 32), "event单数读密钥");
        }
        if (ks.isEmpty()) {
            log.log("   [event#" + index + "] 还没有密钥（没配对成功）");
            return null;
        }

        // 已经试对过 → 只试"记住的那把密钥 + 附近几个计数器"
        int cached = eventKeyIdx;
        if (cached >= 0 && cached < ks.size()) {
            for (int d = 0; d < 9; d++) {
                int c = eventCounter + d;
                for (int a = 0; a < 2; a++) {
                    try {
                        byte[] plain = Crypto.chachaOpen(ks.get(cached), Crypto.nonce64(c), ct, aads[a]);
                        if (looksLikeEventPlain(plain)) {
                            eventCounter = c + 1;
                            log.log("   [event#" + index + "] 解开（" + ns.get(cached) + " / " + an[a]
                                    + " / 计数器=" + c + "，明文 " + plain.length + " 字节）");
                            return plain;
                        }
                    } catch (Throwable ignored) {
                        // 继续试
                    }
                }
            }
        }

        // 全搜一遍：每把密钥 × 两种 AAD × 计数器 0..511
        for (int k = 0; k < ks.size(); k++) {
            for (int a = 0; a < 2; a++) {
                for (int c = 0; c < 512; c++) {
                    try {
                        byte[] plain = Crypto.chachaOpen(ks.get(k), Crypto.nonce64(c), ct, aads[a]);
                        if (looksLikeEventPlain(plain)) {
                            eventKeyIdx = k;
                            eventCounter = c + 1;
                            // ★ 注意方向：车机**发**给我们的帧用 `Events-Write` 密钥（实测解开成功的就是它），
                            // 所以车机**解**我们的帧用的是 `Events-Read` —— 我们回话必须用这把。
                            int wi = ns.indexOf("events读密钥");
                            eventWriteKey = wi >= 0 ? ks.get(wi) : ks.get(0);
                            eventWriteCounter = 0;
                            log.log("   [event#" + index + "] ★ 解开成功（" + ns.get(k) + " / " + an[a]
                                    + " / 计数器=" + c + "，明文 " + plain.length + " 字节）"
                                    + " —— 记住这把密钥，之后每帧只试附近计数器");
                            return plain;
                        }
                    } catch (Throwable ignored) {
                        // 换下一个组合
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String n : ns) {
            sb.append(n).append(" ");
        }
        log.log("   [event#" + index + "] 解不开（试了 " + sb.toString().trim()
                + " × AAD两种 × 计数器0..511）原样: " + Crypto.hex(frame, 48));
        return null;
    }

    /**
     * 回一条 `RTSP/1.0 200 OK`（真 iPhone 收到 `POST /command` 就会这么回）。
     * 实测 DiPlay 不等这个响应，所以失败也不影响触摸；但协议上该回，别的车机可能要。
     */
    private static void respondEvent(Socket s, byte[] plain, AirPlayProbe.Logger log) {
        byte[] key = eventWriteKey;
        if (key == null || s == null) {
            return;
        }
        try {
            String head = new String(plain, 0,
                    Math.min(plain.length, Math.max(0, indexOf(plain, new byte[]{0x0d, 0x0a, 0x0d, 0x0a}))),
                    java.nio.charset.StandardCharsets.US_ASCII);
            if (!head.startsWith("POST ") && !head.startsWith("GET ")) {
                return;
            }
            String cseq = "0";
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("cseq:")) {
                    cseq = line.substring(5).trim();
                }
            }
            byte[] resp = ("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] header = new byte[]{(byte) resp.length, (byte) (resp.length >> 8)};
            byte[] sealed = Crypto.chachaSeal(key, Crypto.nonce64(eventWriteCounter), resp, header);
            eventWriteCounter++;
            java.io.OutputStream out = s.getOutputStream();
            out.write(Crypto.concat(header, sealed));
            out.flush();
        } catch (Throwable t) {
            log.log("      （回 200 失败: " + t + "）");
        }
    }

    /**
     * 处理一条 event 明文。
     *
     * 实测格式（对照 DiPlay 开源实现 `shihabal3amri/DiPlay` 的 AirPlaySession.sendCommandLocked）：
     *   `POST /command RTSP/1.0\r\nContent-Type: application/x-apple-binary-plist\r\n
     *    Content-Length: N\r\nCSeq: k\r\n\r\n` + bplist body
     * body 是字典，触摸是：`{type:"hidSendReport", uuid:"2a2a2a2a", hidReport:<12字节>}`
     * `hidReport` = 2 个触点 × 6 字节 `[槽位][按下 0x01/0x00][x u16 小端][y u16 小端]`
     * 坐标已经是我们开流时定的尺寸（= CarLife 的尺寸），所以**不需要换算**。
     * 旋钮（uuid 2a2a2a2b）的 report[0] 位：0x01 select / 0x02 HOME / 0x04 BACK。
     */
    private static void handleEvent(byte[] plain, AirPlayProbe.Logger log) {
        int sep = indexOf(plain, new byte[]{0x0d, 0x0a, 0x0d, 0x0a});
        String head = sep > 0
                ? new String(plain, 0, sep, java.nio.charset.StandardCharsets.US_ASCII) : "";
        byte[] body = sep > 0 ? java.util.Arrays.copyOfRange(plain, sep + 4, plain.length) : plain;
        log.log("      event 报文头: " + head.replace("\r\n", " | "));
        Object dec = null;
        try {
            dec = Bplist.decode(body);
        } catch (Throwable t) {
            log.log("      （body 不是 plist: " + t + "）");
        }
        if (!(dec instanceof java.util.Map)) {
            log.log("      body 明文 hex=" + Crypto.hex(body, 96));
            return;
        }
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) dec;
        String cmd = String.valueOf(m.get("type"));
        if ("forceKeyFrame".equals(cmd)) {
            log.log("      → 车机要关键帧（forceKeyFrame）→ 让推流循环重发最近一个 I 帧");
            VideoSender.requestKeyFrame();
            return;
        }
        if ("setNightMode".equals(cmd) || "videoPlaybackAllowed".equals(cmd)
                || "showUI".equals(cmd) || "stopUI".equals(cmd)) {
            log.log("      → 车机命令 " + cmd + "（不用回）");
            return;
        }
        Object hr = m.get("hidReport");
        Object uuid = m.get("uuid");
        byte[] rep = hr instanceof byte[] ? (byte[]) hr : null;
        log.log("      plist: type=" + m.get("type") + " uuid=" + uuid
                + (rep == null ? "" : " hidReport=" + rep.length + " 字节 " + Crypto.hex(rep, 24)));
        if (rep == null || rep.length < 6) {
            return;
        }
        String uid = String.valueOf(uuid);
        int vw = VideoSender.carLifeW > 0 ? VideoSender.carLifeW : 1280;
        int vh = VideoSender.carLifeH > 0 ? VideoSender.carLifeH : 720;
        if ("2a2a2a2a".equalsIgnoreCase(uid)) {
            // 触摸屏：2 触点 × 6 字节
            touchSeen++;
            for (int i = 0; i + 6 <= rep.length; i += 6) {
                int down = rep[i + 1] & 0xff;
                int x = (rep[i + 2] & 0xff) | ((rep[i + 3] & 0xff) << 8);
                int y = (rep[i + 4] & 0xff) | ((rep[i + 5] & 0xff) << 8);
                if (x == 0 && y == 0 && down == 0) {
                    continue;
                }
                if (VideoSender.carPlusCastMode) {
                    // ★ v0.6：Car+ 投屏模式下触摸直达 Car+（IRemoteTouchListener.onTouchEvent）
                    boolean ok = CarPlusCast.sendTouch(down != 0 ? 0 : 1, x, y);
                    log.log("      → ★ 触摸 触点#" + (i / 6) + " " + (down != 0 ? "按下" : "抬起")
                            + " (" + x + "," + y + ") → 发给 Car+（" + (ok ? "已发" : "还没拿到触摸通道") + "）");
                } else {
                    boolean ok = CarLifeProbe.sendTouch(down != 0 ? 0 : 1, x, y);
                    log.log("      → ★ 触摸 触点#" + (i / 6) + " " + (down != 0 ? "按下" : "抬起")
                            + " (" + x + "," + y + ") → 转给 CarLife（画面 " + vw + "x" + vh + "）"
                            + (ok ? " 已发" : " **失败：CarLife 触控通道没连上**"));
                }
            }
            return;
        }
        if ("2a2a2a2b".equalsIgnoreCase(uid)) {
            // 旋钮/按键
            int bits = rep[0] & 0xff;
            log.log("      → ★ 旋钮按键 bits=0x" + Integer.toHexString(bits));
            if ((bits & 0x02) != 0) {
                if (VideoSender.carPlusCastMode) {
                    CarPlusCast.sendKey(android.view.KeyEvent.KEYCODE_HOME);
                } else {
                    CarLifeProbe.sendHardKey(1);   // HOME
                }
            } else if ((bits & 0x04) != 0) {
                if (VideoSender.carPlusCastMode) {
                    CarPlusCast.sendKey(android.view.KeyEvent.KEYCODE_BACK);
                } else {
                    CarLifeProbe.sendHardKey(2);   // BACK
                }
            }
        }
    }

    /** 在字节数组里找子串，找不到返回 -1 */
    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 从 plist 字典里按若干候选键名取数字 */
    private static Double numLike(java.util.Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number) {
                return ((Number) v).doubleValue();
            }
        }
        return null;
    }

    /** 从二进制 plist / TLV 里把可读字符串挑出来，便于人眼判断 */
    private static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (byte value : b) {
            int c = value & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                cur.append((char) c);
            } else {
                if (cur.length() >= 4) {
                    sb.append('[').append(cur).append(']');
                }
                cur.setLength(0);
            }
        }
        if (cur.length() >= 4) {
            sb.append('[').append(cur).append(']');
        }
        return sb.length() > 600 ? sb.substring(0, 600) + "…" : sb.toString();
    }
}

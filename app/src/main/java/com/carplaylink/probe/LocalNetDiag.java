package com.carplaylink.probe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机网络 + Car+ 行为诊断（v2.7）。
 *
 * v2.6 只扫了 127.0.0.1，这有个致命盲区：**应用可以把监听绑在某块网卡的地址上**
 * （比如只绑 wlan0 的 192.168.x.x、或建热点后的 192.168.43.1），这种情况下
 * 回环扫描会得出"它没在监听"的假结论。所以 v2.7 做四件事：
 *
 *   A. 列出本机**所有网卡与 IPv4 地址**（含 p2p0 / ap0 / swlan0 —— Car+ 建热点或
 *      Wi-Fi Direct 组时会冒出这些网卡，出现了就说明它换了一套连接机制）；
 *   B. 对**每个本机地址**做全端口扫描（1-65535），不只看 127.0.0.1；
 *   C. 读 /proc/net/tcp(6) 直接列 LISTEN 套接字（Android 10+ 多半按 UID 过滤，能读到就是白捡）；
 *   D. 监听 UDP 7999（CarLife 发现：手机广播、车机监听）+ 往各网段广播地址发探测包，
 *      收到广播的**来源 IP 直接拿去连那 7 个 CarLife 端口**（照抄参考实现的做法）。
 *
 * 跑完再进观察窗口：每 2 秒复扫一次，一旦有 CarLife 端口打开 / 新网卡出现就当场报出来 ——
 * 用户只要在窗口内去 Car+ 里点【连接车机】，就能拿到"它到底在哪等我们"的硬证据。
 */
public final class LocalNetDiag {

    private static final int[] CARLIFE_PORTS = {7240, 8240, 9240, 9241, 9242, 9340, 9440};
    private static final int UDP_DISCOVERY_PORT = 7999;

    private final Context ctx;
    private final AirPlayProbe.Logger log;
    private volatile boolean running = true;

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<DatagramSocket> udps = new ArrayList<>();
    private final Set<String> hits = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final Set<String> hitHosts = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final AtomicInteger udpCount = new AtomicInteger();
    private final AtomicInteger tcpAccepted = new AtomicInteger();

    public LocalNetDiag(Context ctx, AirPlayProbe.Logger log) {
        this.ctx = ctx;
        this.log = log;
    }

    /** 返回所有候选地址（第一个总是 127.0.0.1），供 CarLifeProbe 逐个试 */
    public List<String> run(int watchSeconds) {
        log.log("══ 本机网络 / Car+ 行为诊断（v2.7）══");

        // ---------- A. 网卡与地址 ----------
        Map<String, List<String>> ifs = interfaces();
        logInterfaces(ifs);
        logNetworks();

        // ---------- 蓝牙连接状态（Car+ 的 CarLife+ 页面第 1 步的前置条件）----------
        logBtConnections();

        // ---------- C. /proc/net/tcp ----------
        dumpProcNet();

        // ---------- D. UDP 7999 ----------
        startUdpSniff(ifs);

        // ---------- 反向监听：**不再自动做** ----------
        // v3.3 及以前在这里自动 bind 那 7 个端口，然后别处再去 connect 它们 —— 连上的是自己，
        // 日志里就出现"端口在听 / 通道已连上 / 首包 12 字节"这些全是自连产物的假证据。
        // 现在改成：诊断永不占端口；要反向监听请点【反向监听】按钮（SelfPorts.listen）。
        log.log("   （诊断不再自动反向监听那 7 个端口 —— 免得我们自己占住它造成\"自连\"假象；"
                + "本机自占端口: " + SelfPorts.heldText() + "）");

        // ---------- B. 全端口扫描每个本机地址 ----------
        List<String> hosts = localHosts(ifs);
        for (String host : hosts) {
            long t0 = System.currentTimeMillis();
            List<Integer> open = scanHost(host);
            log.log("★ B. " + host + " 开放 TCP 端口 " + open.size() + " 个（"
                    + (System.currentTimeMillis() - t0) / 1000 + " 秒）: " + open);
            for (int p : CARLIFE_PORTS) {
                if (open.contains(p)) {
                    markHit(host, p, "全端口扫描");
                }
            }
        }
        if (hits.isEmpty()) {
            log.log("     （所有本机地址上都没看到 CarLife 的 7 个端口 → Car+ 此刻没在等车机连它）");
        }

        // ---------- 观察窗口 ----------
        log.log("★ 观察 " + watchSeconds + " 秒：现在去 Car+ 里点【连接车机 / 无线连接】，"
                + "并停在那界面别退出（每 2 秒复扫一次，有新东西当场报）…");
        long deadline = System.currentTimeMillis() + watchSeconds * 1000L;
        Map<String, String> lastIfs = flatten(ifs);
        while (running && System.currentTimeMillis() < deadline) {
            sleep(2000);
            Map<String, List<String>> now = interfaces();
            Map<String, String> flat = flatten(now);
            for (Map.Entry<String, String> e : flat.entrySet()) {
                if (!lastIfs.containsKey(e.getKey())) {
                    log.log("★★ 新出现网卡/地址: " + e.getKey() + " → " + e.getValue()
                            + "（Car+ 起了热点或 Wi-Fi Direct 组？说明它换机制了）");
                }
            }
            lastIfs = flat;
            for (String host : localHosts(now)) {
                for (int p : CARLIFE_PORTS) {
                    if (isOpen(host, p, 400)) {
                        markHit(host, p, "实时监视");
                    }
                }
            }
        }

        log.log("══ 诊断结束：UDP 收到 " + udpCount.get() + " 个包，TCP 被连 " + tcpAccepted.get()
                + " 次，CarLife 命中 " + (hits.isEmpty() ? "无" : hits) + " ══");
        if (hits.isEmpty()) {
            log.log("   结论：Car+ 既没在监听、也没来连我们、也没广播 → 它的 CarLife 入口还没被打开，"
                    + "或这个协议压根由别的组件承担（com.baidu.carlife.oppo）。"
                    + "下一步：点【② Car+等车机】把 CarLife+ 组件那个界面直接拉出来，再跑一次本诊断。");
        } else {
            log.log("   结论：真的有 CarLife 端口在听（上面这些地址）→ 点【③ 当车机·接Car+】即可接住握手。");
        }
        close();
        return hosts;
    }

    // ------------------------------------------------------------ A. 网卡 / 网络

    private Map<String, List<String>> interfaces() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                List<String> addrs = new ArrayList<>();
                try {
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (a instanceof Inet4Address) {
                            String bc = ia.getBroadcast() == null ? "-" : ia.getBroadcast().getHostAddress();
                            addrs.add(a.getHostAddress() + "/" + ia.getNetworkPrefixLength() + " bc=" + bc);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!addrs.isEmpty() || !ni.isLoopback()) {
                    String flags = (ni.isUp() ? "up" : "down")
                            + (ni.isLoopback() ? " loopback" : "")
                            + (ni.isPointToPoint() ? " p2p" : "");
                    out.put(ni.getName() + " [" + flags + "]", addrs);
                }
            }
        } catch (Throwable t) {
            log.log("!! 枚举网卡失败: " + t);
        }
        return out;
    }

    private void logInterfaces(Map<String, List<String>> ifs) {
        log.log("★ A. 本机网卡 " + ifs.size() + " 块:");
        for (Map.Entry<String, List<String>> e : ifs.entrySet()) {
            log.log("     " + e.getKey() + "  " + (e.getValue().isEmpty() ? "(无 IPv4)" : e.getValue()));
        }
    }

    /** 把当前所有网络（Wi-Fi / 蜂窝 / VPN / P2P）的类型与地址打出来 */
    private void logNetworks() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            Network[] nets = cm.getAllNetworks();
            log.log("★ 系统网络 " + nets.length + " 个:");
            int idx = 0;
            for (Network n : nets) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                StringBuilder sb = new StringBuilder("     net#" + (idx++) + " if="
                        + (lp == null ? "?" : lp.getInterfaceName()) + " 类型=");
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        sb.append("WIFI ");
                    }
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        sb.append("CELL ");
                    }
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        sb.append("VPN ");
                    }
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        sb.append("ETH ");
                    }
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                        sb.append("BT ");
                    }
                }
                if (lp != null) {
                    List<String> v4 = new ArrayList<>();
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address) {
                            v4.add(la.getAddress().getHostAddress() + "/" + la.getPrefixLength());
                        }
                    }
                    sb.append(" addr=").append(v4);
                }
                log.log(sb.toString());
            }
        } catch (Throwable t) {
            log.log("   （列系统网络失败: " + t + "）");
        }
    }

    /** 所有本机 IPv4（回环排第一，保证 CarLifeProbe 至少有一个候选） */
    private List<String> localHosts(Map<String, List<String>> ifs) {
        Set<String> out = new LinkedHashSet<>();
        out.add("127.0.0.1");
        for (List<String> addrs : ifs.values()) {
            for (String a : addrs) {
                int cut = a.indexOf('/');
                String ip = cut > 0 ? a.substring(0, cut) : a;
                if (!ip.startsWith("0.") && !"0.0.0.0".equals(ip)) {
                    out.add(ip);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private Map<String, String> flatten(Map<String, List<String>> ifs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : ifs.entrySet()) {
            out.put(e.getKey(), e.getValue().toString());
        }
        return out;
    }

    // ------------------------------------------------------------ 蓝牙连接状态

    /**
     * Car+ 的「无线连接 → 百度CarLife+」页面第 1 步是"确认已连接车载蓝牙"，
     * 那一步显示"蓝牙未连接"时【开始连接】按钮是灰的、**它不会广播也不会开那 7 个端口** ——
     * 我们这边就什么都接不到。所以诊断第一步先把这个前置条件查清楚。
     *
     * 用公开的 profile 代理（A2DP / HEADSET）查"谁真的连着"，而不是只看"谁配对了"。
     */
    private void logBtConnections() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            log.log("★ 蓝牙: 本机没有蓝牙适配器");
            return;
        }
        Map<String, String> conn = new LinkedHashMap<>();
        int[] profiles = {BluetoothProfile.HEADSET, BluetoothProfile.A2DP};
        String[] names = {"通话", "媒体"};
        for (int i = 0; i < profiles.length; i++) {
            final int prof = profiles[i];
            final List<BluetoothDevice> got = new ArrayList<>();
            final Object lock = new Object();
            BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int p, BluetoothProfile proxy) {
                    try {
                        got.addAll(proxy.getConnectedDevices());
                    } catch (Throwable ignored) {
                    }
                    try {
                        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                        if (a != null) {
                            a.closeProfileProxy(p, proxy);
                        }
                    } catch (Throwable ignored) {
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                public void onServiceDisconnected(int p) {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            };
            try {
                if (ad.getProfileProxy(ctx, sl, prof)) {
                    synchronized (lock) {
                        lock.wait(1500);
                    }
                }
            } catch (Throwable ignored) {
            }
            for (BluetoothDevice d : got) {
                String prev = conn.get(d.getAddress());
                String nm;
                try {
                    nm = d.getName();
                } catch (Throwable t) {
                    nm = "?";
                }
                conn.put(d.getAddress(), (prev == null ? "" : prev + "+") + names[i] + "(" + nm + ")");
            }
        }
        if (conn.isEmpty()) {
            log.log("★ 蓝牙: 当前**没有任何蓝牙设备处于连接状态**");
            log.log("   !! 这就是 Car+ 那个页面显示\"蓝牙未连接\"的原因，也是【开始连接】灰着的原因 ——"
                    + " 它不广播、不开端口，我们什么都接不到。");
            log.log("   → 先去系统蓝牙里把车机（或 DiPlay 那台）连上，再点【CarLife上车】。");
        } else {
            log.log("★ 蓝牙: 当前已连接 " + conn.size() + " 台 ——");
            for (Map.Entry<String, String> e : conn.entrySet()) {
                log.log("   ★ " + e.getKey() + "  " + e.getValue());
            }
        }
    }

    // ------------------------------------------------------------ C. /proc/net

    private void dumpProcNet() {
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                int total = 0;
                List<String> listen = new ArrayList<>();
                while ((line = r.readLine()) != null) {
                    String[] c = line.trim().split("\\s+");
                    if (c.length < 10 || "sl".equals(c[0])) {
                        continue;
                    }
                    total++;
                    if ("0A".equals(c[3])) {   // TCP_LISTEN
                        listen.add(hexPort(c[1]) + "(uid" + c[7] + ")");
                    }
                }
                r.close();
                log.log("★ C. " + f + " 可见 " + total + " 条，其中 LISTEN: "
                        + (listen.isEmpty() ? "无" : listen));
            } catch (Throwable t) {
                log.log("   C. 读 " + f + " 不可用（Android 10+ 按 UID 隔离，属正常）: " + t);
            }
        }
    }

    private static String hexPort(String local) {
        try {
            int i = local.indexOf(':');
            return String.valueOf(Integer.parseInt(local.substring(i + 1), 16));
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------ B. 端口扫描

    private List<Integer> scanHost(final String host) {
        final InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        final ConcurrentLinkedQueue<Integer> found = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(128);
        final AtomicInteger idx = new AtomicInteger(1);
        for (int i = 0; i < 128; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        int port = idx.getAndIncrement();
                        if (port > 65535) {
                            return;
                        }
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(addr, port), 300);
                            found.add(port);
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(150, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        List<Integer> list = new ArrayList<>(found);
        Collections.sort(list);
        return list;
    }

    private boolean isOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(host), port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void markHit(String host, int port, String how) {
        if (SelfPorts.holds(port)) {
            return;   // 我们自己占着的端口，绝不算"CarLife 在听"
        }
        if (hits.add(host + ":" + port)) {
            hitHosts.add(host);
            log.log("★★ CarLife 端口在听: " + host + ":" + port + "（" + how + "）");
        }
    }

    // ------------------------------------------------------------ D. UDP 7999

    private void startUdpSniff(final Map<String, List<String>> ifs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.bind(new InetSocketAddress("0.0.0.0", UDP_DISCOVERY_PORT));
                    udps.add(ds);
                    ds.setSoTimeout(1000);
                    log.log("★ D. 已监听 UDP " + UDP_DISCOVERY_PORT + "（CarLife 发现：手机广播、车机监听）");
                    byte[] buf = new byte[2048];
                    while (running) {
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            ds.receive(p);
                            udpCount.incrementAndGet();
                            String from = p.getAddress().getHostAddress();
                            log.log("★★ UDP " + UDP_DISCOVERY_PORT + " 收到包！来自 " + from + ":"
                                    + p.getPort() + " len=" + p.getLength() + " hex="
                                    + Crypto.hex(java.util.Arrays.copyOf(p.getData(), Math.min(p.getLength(), 64))));
                            hitHosts.add(from);
                            probeHost(from);
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Throwable t) {
                            if (running) {
                                log.log("   D. UDP 接收结束: " + t);
                            }
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log.log("    D. 绑定 UDP " + UDP_DISCOVERY_PORT + " 失败: " + t);
                } finally {
                    if (ds != null) {
                        ds.close();
                    }
                }
            }
        }, "udp7999").start();

        // 主动往各网段的广播地址发一轮（万一是"车机广播、手机监听"，我们发它就答）
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket();
                    ds.setBroadcast(true);
                    Set<String> targets = new LinkedHashSet<>();
                    targets.add("127.0.0.1");
                    targets.add("255.255.255.255");
                    for (List<String> addrs : ifs.values()) {
                        for (String a : addrs) {
                            int i = a.indexOf("bc=");
                            if (i > 0) {
                                String bc = a.substring(i + 3).trim();
                                if (!"-".equals(bc)) {
                                    targets.add(bc);
                                }
                            }
                        }
                    }
                    byte[] probe = new byte[]{0x00, 0x00, 0x00, 0x00};
                    for (int round = 0; round < 3 && running; round++) {
                        for (String t : targets) {
                            try {
                                ds.send(new DatagramPacket(probe, probe.length,
                                        InetAddress.getByName(t), UDP_DISCOVERY_PORT));
                            } catch (Throwable ignored) {
                            }
                        }
                        sleep(500);
                    }
                    log.log("    D. 已往 " + targets + " 的 " + UDP_DISCOVERY_PORT + " 各发 3 个探测包");
                } catch (Throwable t) {
                    log.log("    D. 广播探测失败: " + t);
                } finally {
                    if (ds != null) {
                        ds.close();
                    }
                }
            }
        }, "udp-bc").start();
    }

    /** 拿到一个来源 IP 后照参考实现的做法：直接去连它的 7 个 CarLife 端口 */
    private void probeHost(final String host) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int port : CARLIFE_PORTS) {
                    if (!running) {
                        return;
                    }
                    if (isOpen(host, port, 800)) {
                        markHit(host, port, "UDP 广播来源 " + host);
                    }
                }
            }
        }, "probe-" + host).start();
    }

    // ------------------------------------------------------------ 反向监听（改成显式）

    /**
     * 反向监听那 7 个 CarLife 端口，看会不会是**对方主动连"车机"**（角色与参考实现相反）。
     *
     * 注意：一旦监听了，这些端口就归我们自己 —— 之后再 connect 它们连到的就是自己。
     * 所以只能由用户显式点【反向监听】调用，且调用方要在日志里讲清楚这一点。
     */
    public static int reverseListenNow(AirPlayProbe.Logger log) {
        int ok = SelfPorts.listen(CARLIFE_PORTS, log);
        if (ok == CARLIFE_PORTS.length) {
            log.log("★ 已反向监听 7 个 CarLife 端口（0.0.0.0），等手机主动连过来…");
        } else if (ok > 0) {
            log.log("★ 只反向监听成功 " + ok + "/7 个端口（其余被占）—— 被占的那几个多半是 CarLife 自己在听");
        } else {
            log.log("★ 一个都没监听上 —— 说明这 7 个端口**已经有别的进程在听**，"
                    + "那正是我们要找的 CarLife 手机端，别关它，去点【③ 当车机·接Car+】");
        }
        log.log("   ⚠ 现在这些端口归我们自己了：这一步之后任何\"端口在听\"都不再算证据。"
                + "再点一次【反向监听】= 关掉。");
        return ok;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------ 给"入口爆破"用的静态小工具

    /** 本机所有 IPv4（回环排第一），不打印任何东西 */
    static List<String> allLocalHosts() {
        Set<String> out = new LinkedHashSet<>();
        out.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(out);
    }

    /**
     * 那 7 个 CarLife 端口只要有一个在本机某个地址上开着，就返回 "地址:端口"，否则 null。
     *
     * v3.4：**跳过本机自己占着的端口**（{@link SelfPorts}）—— 否则自己监听再自己 connect，
     * 永远返回"在听"，把 Car+ 到底开没开端口这件事彻底盖掉（v3.3 就是这么被骗的）。
     */
    static String anyCarLifeOpen(int timeoutMs) {
        for (String host : allLocalHosts()) {
            for (int port : CARLIFE_PORTS) {
                if (SelfPorts.holds(port)) {
                    continue;
                }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(InetAddress.getByName(host), port), timeoutMs);
                    return host + ":" + port;
                } catch (Throwable ignored) {
                } finally {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * 并行扫 127.0.0.1 的 [lo, hi]，返回**开着**（能 connect 上）的端口，升序。
     *
     * 用途：看 **Car+ 自己在本机开了哪些端口** —— 如果它也用 CarLife 那套（7240/8240/…），
     * 那我们就能像对 CarLife+ 那样、对 Car+ 也冒充"车机"，让它把车机界面推给我们。
     * （Android 10+ 读 /proc/net/tcp 只看得到自己 uid 的 socket，所以只能用 connect 探。）
     */
    static List<Integer> scanLocalOpenPorts(int lo, int hi, int threads, int timeoutMs) {
        final ConcurrentLinkedQueue<Integer> open = new ConcurrentLinkedQueue<Integer>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicInteger next = new AtomicInteger(lo);
        for (int i = 0; i < threads; i++) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    int p;
                    while ((p = next.getAndIncrement()) <= hi) {
                        if (SelfPorts.holds(p)) {
                            continue;
                        }
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), timeoutMs);
                            open.add(p);
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(240, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        List<Integer> r = new ArrayList<Integer>(open);
        Collections.sort(r);
        return r;
    }

    public void close() {
        running = false;
        for (ServerSocket s : servers) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        for (DatagramSocket d : udps) {
            try {
                d.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

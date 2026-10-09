package com.carplaylink.probe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Parcelable;
import android.os.ParcelUuid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * P0 探测：在安卓手机（OPPO）上扮演 iPhone，走完无线 CarPlay 的蓝牙 iAP2 前半段。
 *
 * v0.2 新增"自动找车机"：逐个候选设备试连，只认两种信号——
 *   a) SDP 里能查到 CarPlay 配件服务 UUID（...caff）→ 一定是车机（配对过才有缓存）
 *   b) 连上后 7 秒内收到任何 iAP2 字节 → 是车机
 * 因为安卓对 RFCOMM 通道 3 的连接会"假成功"（connect() 返回成功但对面根本不说话）。
 */
public class ProbeSession {

    public interface Listener {
        void onLog(String line);

        void onStatus(String status);

        void onWifi(String ssid, String passphrase, int securityType, int channel);

        void onFinished(String reason);
    }

    public static final UUID UUID_ACCESSORY = UUID.fromString("00000000-deca-fade-deca-deafdecacaff");
    public static final UUID UUID_PHONE = UUID.fromString("00000000-deca-fade-deca-deafdecacafe");

    private final Listener listener;
    private final File logFile;
    private final ByteQueue queue = new ByteQueue();
    private final Map<Integer, byte[]> sentPackets = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean stop;
    private volatile boolean running;
    private volatile boolean readerStop;

    private BluetoothSocket socket;
    private BluetoothServerSocket phoneProfile;
    private InputStream in;
    private OutputStream out;
    private Thread worker;

    // 链路层状态
    private int seq;
    private int lastRecv;
    private int unacked;
    private int maxAck = 3;
    private boolean synSent;
    private boolean linkUp;
    private boolean connectedViaSdp;
    private boolean deviceHasCarplayUuid;
    private boolean inboundConnected;

    // 握手状态机
    private int stage;              // 0 等链路 / 1 已发识别请求 / 2 已发识别接受 / 3 鉴权 / 4 已请求 Wi-Fi / 5 完成
    private int authState;          // 0 未开始 / 1 等证书 / 2 等签名 / 3 完成
    private long stageAt;
    private int retries;
    private boolean done;
    private int challengeAttempts;

    // 自动模式
    private boolean autoMode;
    private List<BluetoothDevice> candidates;

    public ProbeSession(Listener listener, File logFile) {
        this.listener = listener;
        this.logFile = logFile;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------- 生命周期

    /** 手动模式：只探测选中的那一台 */
    public void start(final BluetoothDevice device) {
        if (running) {
            return;
        }
        running = true;
        stop = false;
        autoMode = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // DiPlay 这类接收端是"它来连手机"，所以先挂着等连入，等不到再主动拨
                    if (!inboundFirst(15000)) {
                        runOne(device);
                    }
                } catch (Throwable t) {
                    log("!! 异常终止: " + t);
                    listener.onFinished("异常：" + t);
                } finally {
                    cleanup();
                    running = false;
                }
            }
        }, "cplink-probe");
        worker.start();
    }

    /** 自动模式：按顺序逐台试，第一个有 iAP2 响应的就当车机并继续走完握手 */
    public void startAuto(final List<BluetoothDevice> list) {
        if (running) {
            return;
        }
        running = true;
        stop = false;
        autoMode = true;
        candidates = list;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log("== 自动找车机：共 " + candidates.size() + " 台候选设备 ==");
                    log("第一步：挂着等车机主动连进来（最多 25 秒）");
                    listener.onStatus("等待车机连进来…");
                    if (inboundFirst(25000)) {
                        log("★ 已锁定车机（对方主动连入）");
                        listener.onStatus("已锁定车机（对方主动连入）");
                        return;
                    }
                    log("没有人主动连进来 → 第二步：逐台主动拨");
                    int idx = 0;
                    for (BluetoothDevice d : candidates) {
                        if (stop) {
                            break;
                        }
                        idx++;
                        log("---- 第 " + idx + "/" + candidates.size() + " 台：" + nameOf(d)
                                + " [" + d.getAddress() + "] ----");
                        boolean ok = false;
                        try {
                            ok = runOne(d);
                        } catch (Throwable t) {
                            log("!! 该设备异常: " + t);
                        }
                        if (ok) {
                            log("★ 已锁定车机: " + nameOf(d) + " [" + d.getAddress() + "]");
                            listener.onStatus("已锁定车机: " + nameOf(d));
                            return;
                        }
                        log("   这台不是车机，换下一台");
                    }
                    log("!! 所有候选设备都没有 iAP2 响应");
                    listener.onFinished("没找到有 iAP2 响应的设备");
                } catch (Throwable t) {
                    log("!! 异常终止: " + t);
                    listener.onFinished("异常：" + t);
                } finally {
                    cleanup();
                    running = false;
                }
            }
        }, "cplink-auto");
        worker.start();
    }

    public void stop() {
        stop = true;
        closeQuietly();
    }

    private void cleanup() {
        closeQuietly();
        log("—— 会话结束 ——");
    }

    private void closeQuietly() {
        readerStop = true;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (phoneProfile != null) {
                phoneProfile.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
        phoneProfile = null;
    }

    // --------------------------------------------------------------- 单台流程

    /** 确保手机侧 CarPlay 服务记录（...cafe）已注册并处于监听状态 */
    private void ensurePhoneProfile() {
        if (phoneProfile != null) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        try {
            adapter.cancelDiscovery();
            phoneProfile = adapter.listenUsingRfcommWithServiceRecord("CPLink Phone", UUID_PHONE);
            log("已注册并监听手机侧 CarPlay 服务记录 " + UUID_PHONE);
        } catch (Throwable t) {
            log("注册手机侧服务记录失败（不致命）: " + t.getMessage());
        }
    }

    /**
     * 挂着等对面主动连进来。DiPlay 这类接收端（以及部分车机）是它来连手机的 ...cafe 服务。
     * @return true 表示连进来了并且握手成功
     */
    private boolean inboundFirst(int timeoutMs) throws Exception {
        ensurePhoneProfile();
        if (phoneProfile == null) {
            return false;
        }
        log("等待车机主动连进来（接收端由它来连手机），最多 " + (timeoutMs / 1000) + " 秒…");
        listener.onStatus("等待车机连进来…");
        BluetoothSocket s = null;
        try {
            s = phoneProfile.accept(timeoutMs);
        } catch (Throwable t) {
            log("等待连入结束: " + t.getMessage());
        }
        if (s == null || stop) {
            return false;
        }
        String mac = "?";
        try {
            mac = s.getRemoteDevice().getAddress();
        } catch (Throwable ignored) {
        }
        log("★ 有设备主动连进来了: " + mac + "（这就是车机/接收端）");
        inboundConnected = true;
        connectedViaSdp = false;
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        startReader();
        boolean ok = handshake();
        if (!ok) {
            log("   这次连入没走通 iAP2，继续等下一个");
            closeQuietly();
            ensurePhoneProfile();
        }
        return ok;
    }

    private void startReader() {
        readerStop = false;
        final InputStream localIn = in;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[512];
                try {
                    while (!stop && !readerStop) {
                        int n = localIn.read(buf);
                        if (n < 0) {
                            break;
                        }
                        if (n > 0) {
                            synchronized (queue) {
                                queue.add(buf, n);
                                queue.notifyAll();
                            }
                        }
                    }
                } catch (Throwable t) {
                    // 关闭 socket 时正常会抛异常，不刷屏
                }
                synchronized (queue) {
                    queue.notifyAll();
                }
            }
        }, "cplink-reader");
        reader.start();
    }

    /** @return true 表示这台就是车机（iAP2 链路已建立） */
    private boolean runOne(BluetoothDevice device) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("!! 蓝牙未开启");
            listener.onFinished("蓝牙未开启");
            return false;
        }

        log(autoMode
                ? "=== 自动模式：逐台试探（正确用法）==="
                : "=== 手动模式：只连这一台 ===（日志出现这行，说明你点的是【连接选中】，不是【① 自动找车机】）");

        // 该设备对外广播的 SDP 服务：能查到 CarPlay 配件 UUID 就基本确定是车机
        // 不依赖 ACTION_UUID 广播：主动触发一次 SDP 查询，再读缓存
        deviceHasCarplayUuid = false;
        try {
            device.fetchUuidsWithSdp();
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
        try {
            Parcelable[] uu = device.getUuids();
            if (uu != null && uu.length > 0) {
                StringBuilder sb = new StringBuilder("该设备 SDP 服务: ");
                for (Parcelable p : uu) {
                    sb.append(p.toString()).append("  ");
                    if (p instanceof ParcelUuid
                            && UUID_ACCESSORY.equals(((ParcelUuid) p).getUuid())) {
                        deviceHasCarplayUuid = true;
                    }
                }
                log(sb.toString());
                if (deviceHasCarplayUuid) {
                    log("★ 该设备 SDP 里有 CarPlay 配件服务 " + UUID_ACCESSORY);
                }
            } else {
                log("该设备 SDP 服务: 查不到（未配对或未缓存）");
            }
        } catch (Throwable t) {
            log("读 SDP 服务失败: " + t.getMessage());
        }

        seq = random.nextInt(200) + 20;
        log("本机 iAP2 起始序号 seq=" + seq);

        if (phoneProfile == null) {
            try {
                adapter.cancelDiscovery();
                phoneProfile = adapter.listenUsingRfcommWithServiceRecord("CPLink Phone", UUID_PHONE);
                log("已注册手机侧 CarPlay 服务记录 " + UUID_PHONE);
            } catch (Throwable t) {
                log("注册手机侧服务记录失败（不致命）: " + t.getMessage());
            }
        }

        listener.onStatus("正在连接 " + nameOf(device));
        connectedViaSdp = false;
        socket = openSocket(device);
        if (socket == null) {
            log("!! 连接失败");
            return false;
        }
        in = socket.getInputStream();
        out = socket.getOutputStream();
        log("RFCOMM 已连接: " + nameOf(device) + " [" + device.getAddress() + "]");

        readerStop = false;
        final InputStream localIn = in;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[512];
                try {
                    while (!stop && !readerStop) {
                        int n = localIn.read(buf);
                        if (n < 0) {
                            break;
                        }
                        if (n > 0) {
                            synchronized (queue) {
                                queue.add(buf, n);
                                queue.notifyAll();
                            }
                        }
                    }
                } catch (Throwable t) {
                    // 关闭 socket 时正常会抛异常，不刷屏
                }
                synchronized (queue) {
                    queue.notifyAll();
                }
            }
        }, "cplink-reader");
        reader.start();

        return handshake();
    }

    private BluetoothSocket openSocket(BluetoothDevice device) {
        // a) 标准 SDP 连接（安全）：能成功说明对面有 CarPlay 配件服务记录
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(UUID_ACCESSORY);
            s.connect();
            connectedViaSdp = true;
            log("连接方式：SDP(安全) 命中 CarPlay 配件服务 ★");
            return s;
        } catch (Throwable t) {
            log("SDP(安全) 失败: " + t.getMessage());
        }
        // b) SDP 连接（不安全）
        try {
            BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(UUID_ACCESSORY);
            s.connect();
            connectedViaSdp = true;
            log("连接方式：SDP(不安全) 命中 CarPlay 配件服务 ★");
            return s;
        } catch (Throwable t) {
            log("SDP(不安全) 失败: " + t.getMessage());
        }
        // c) 直接指定 RFCOMM 通道（参考实现用通道 3）
        int[] channels = {3, 1, 2, 4};
        for (int ch : channels) {
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                BluetoothSocket s = (BluetoothSocket) m.invoke(device, ch);
                s.connect();
                log("连接方式：RFCOMM 通道 " + ch + "（这种成功可能是假的，要靠有没有数据来判定）");
                return s;
            } catch (Throwable t) {
                log("RFCOMM 通道 " + ch + " 失败: " + t.getMessage());
            }
        }
        // d) 兜底：有些车机/接收端是自己主动连手机的（连到手机侧 ...cafe 服务），那就等它连进来
        if (phoneProfile != null && (!autoMode || deviceHasCarplayUuid)) {
            log("主动连不上，改为等对面连进来（最多 15 秒）…");
            try {
                BluetoothSocket s = phoneProfile.accept(15000);
                if (s != null) {
                    log("连接方式：对面主动连进来的（inbound）");
                    return s;
                }
            } catch (Throwable t) {
                log("等待对方连入失败: " + t.getMessage());
            }
        }
        return null;
    }

    // --------------------------------------------------------------- 握手

    private boolean handshake() throws Exception {
        int markerWait;
        if (inboundConnected) {
            markerWait = autoMode ? 12000 : 15000;
        } else if (!autoMode) {
            markerWait = 8000;
        } else if (connectedViaSdp) {
            markerWait = 9000;
        } else {
            markerWait = 5000;
        }
        log("等待车机 iAP2 marker (FF550200EE10)，最多 " + (markerWait / 1000) + " 秒…");
        listener.onStatus("等待车机 marker");
        byte[] marker = readN(6, markerWait);
        if (marker != null) {
            if (sameBytes(marker, Iap2Link.MARKER)) {
                log("★ 收到车机 marker，回显");
                out.write(Iap2Link.MARKER);
                out.flush();
            } else {
                log("收到的不是 marker（" + Iap2Link.hex(marker) + "），放回缓冲区继续解析");
                synchronized (queue) {
                    queue.pushFront(marker);
                }
                out.write(Iap2Link.MARKER);
                out.flush();
            }
        } else {
            if (autoMode && !connectedViaSdp) {
                log("   7 秒没有任何数据，且 SDP 里也没有 CarPlay 服务 → 判定不是车机");
                hintNoCarplayService();
                return false;
            }
            log("超时未收到 marker，主动发 marker 再等一轮");
            out.write(Iap2Link.MARKER);
            out.flush();
            byte[] again = readN(6, autoMode ? 5000 : 10000);
            if (again == null) {
                if (autoMode) {
                    log("   仍无任何数据 → 判定不是车机");
                    hintNoCarplayService();
                    return false;
                }
                log("!! 车机一直没回应，但连接还在，继续等");
                hintNoCarplayService();
            } else if (sameBytes(again, Iap2Link.MARKER)) {
                log("★ 收到车机 marker（第二轮），回显");
                out.write(Iap2Link.MARKER);
                out.flush();
            } else {
                log("收到非 marker 数据（" + Iap2Link.hex(again) + "），放回缓冲区");
                synchronized (queue) {
                    queue.pushFront(again);
                }
            }
        }

        listener.onStatus("链路协商中");
        long lastProgress = System.currentTimeMillis();
        while (!stop && !readerStop) {
            byte[] pkt = tryReadPacket();
            if (pkt != null) {
                lastProgress = System.currentTimeMillis();
                processPacket(pkt);
            }
            advance();
            if (done) {
                // P0 目标达成后继续监听，把车机后续报文记进日志（为 P1 铺路）
                Thread.sleep(50);
                continue;
            }
            if (System.currentTimeMillis() - lastProgress > 45000) {
                log("!! 45 秒没有收到车机任何数据");
                listener.onFinished("超时：车机无响应");
                break;
            }
            Thread.sleep(30);
        }
        return linkUp || done;
    }

    private void processPacket(byte[] pkt) throws IOException {
        byte[] h = new byte[9];
        System.arraycopy(pkt, 0, h, 0, 9);
        Iap2Link.Header hdr = Iap2Link.parseHeader(h);
        if (hdr == null) {
            log("< 无法解析的包头 " + Iap2Link.hex(pkt));
            return;
        }
        byte[] payload = null;
        if (hdr.length > 9) {
            payload = new byte[hdr.length - 10];
            System.arraycopy(pkt, 9, payload, 0, payload.length);
            if (!Iap2Link.checksumOk(pkt, 9, payload.length + 1)) {
                log("!! 载荷校验失败，仍继续解析");
            }
        }

        log("< " + hdr.toString() + (payload != null ? " payload=" + payload.length + "B" : ""));

        if ((hdr.control & Iap2Link.RST) != 0) {
            log("!! 车机发送 RST，链路被重置");
            listener.onFinished("车机 RST");
            stop = true;
            return;
        }

        if ((hdr.control & Iap2Link.SYN) != 0 && payload != null) {
            log("   车机 LSP: " + Iap2Link.describeLsp(payload));
            maxAck = Iap2Link.lspMaxAck(payload);
            lastRecv = hdr.seq;
            if (!synSent) {
                byte[] p = Iap2Link.linkPacket(Iap2Link.SYN | Iap2Link.ACK, seq, lastRecv, 0, Iap2Link.lspPayload());
                out.write(p);
                out.flush();
                sentPackets.put(seq & 0xFF, p);
                synSent = true;
                log("> 回 SYN|ACK + 我方 LSP (seq=" + (seq & 0xFF) + " ack=" + lastRecv + ")");
            }
        }

        if ((hdr.control & Iap2Link.ACK) != 0) {
            unacked++;
            if (!linkUp && synSent) {
                linkUp = true;
                log("== iAP2 链路已建立（NORMAL）★ 这台就是车机 ==");
                listener.onStatus("链路已建立，开始识别");
            }
        }

        if ((hdr.control & Iap2Link.EAK) != 0 && payload != null) {
            log("   车机要求重传: " + Iap2Link.hex(payload));
            for (byte b : payload) {
                byte[] cached = sentPackets.get(b & 0xFF);
                if (cached != null) {
                    out.write(cached);
                    out.flush();
                    log("> 重传 seq=" + (b & 0xFF));
                }
            }
        }

        boolean isData = (hdr.control & ~Iap2Link.ACK) == 0 && payload != null;
        if (isData) {
            lastRecv = hdr.seq;
            if (hdr.session == Iap2Link.SESSION_CONTROL) {
                handleCsm(payload);
            } else {
                log("   [会话 " + hdr.session + "] 数据 " + payload.length + "B: "
                        + Iap2Link.hex(payload).substring(0, Math.min(64, payload.length * 2)));
            }
        }

        if (unacked >= maxAck) {
            unacked = 0;
            sendAck();
        }
    }

    private void handleCsm(byte[] payload) throws IOException {
        Iap2Link.Csm csm = Iap2Link.parseCsm(payload);
        if (csm == null) {
            log("   非 CSM 控制报文: " + Iap2Link.hex(payload));
            return;
        }
        log("   CSM " + csm);

        switch (csm.msgId) {
            case Iap2Link.CSM_IDENTIFICATION_INFORMATION: {
                log("   车机识别信息: name=" + csm.strOf(0) + " model=" + csm.strOf(1)
                        + " 厂商=" + csm.strOf(2) + " 序列号=" + csm.strOf(3) + " 固件=" + csm.strOf(4));
                if (csm.valueOf(24) != null) {
                    log("   >> 参数24 = wireless_car_play_transport_component（车机支持无线 CarPlay）");
                }
                if (csm.valueOf(17) != null) {
                    log("   >> 参数17 = bluetooth_transport_component");
                }
                sendCsm(Iap2Link.CSM_IDENTIFICATION_ACCEPTED);
                stage = 2;
                stageAt = System.currentTimeMillis();
                listener.onStatus("识别通过，开始鉴权");
                break;
            }
            case Iap2Link.CSM_START_IDENTIFICATION: {
                log("   车机要求我方识别信息，回一个最小识别信息");
                sendCsm(Iap2Link.CSM_IDENTIFICATION_INFORMATION, ourIdentification());
                break;
            }
            case Iap2Link.CSM_IDENTIFICATION_REJECTED: {
                log("!! 车机拒绝识别");
                listener.onFinished("车机拒绝识别（IdentificationRejected）");
                break;
            }
            case Iap2Link.CSM_AUTH_CERT: {
                byte[] cert = csm.valueOf(0);
                log("   收到车机 MFi 证书 " + (cert == null ? 0 : cert.length) + " 字节");
                if (cert != null && cert.length > 16) {
                    log("   证书前 16 字节: " + Iap2Link.hex(cert).substring(0, 32));
                }
                sendChallenge();
                authState = 2;
                break;
            }
            case Iap2Link.CSM_AUTH_RESPONSE: {
                byte[] resp = csm.valueOf(0);
                log("   收到车机签名 " + (resp == null ? 0 : resp.length) + " 字节（iAP2 单向认证，不验证，直接报成功）");
                sendCsm(Iap2Link.CSM_AUTH_SUCCEEDED);
                authState = 3;
                log("== 鉴权完成（0xAA05 已发送）★ ==");
                listener.onStatus("鉴权完成，请求车机 Wi-Fi 信息");
                break;
            }
            case Iap2Link.CSM_AUTH_FAILED: {
                log("!! 车机回报鉴权失败");
                listener.onFinished("鉴权失败");
                break;
            }
            case Iap2Link.CSM_WIFI_CONFIG: {
                String ssid = csm.strOf(1);
                String pass = csm.strOf(2);
                byte[] sec = csm.valueOf(3);
                byte[] ch = csm.valueOf(4);
                log("== 拿到车机热点 ==  SSID=" + ssid + "  密码=" + pass
                        + "  加密=" + (sec == null ? -1 : Iap2Link.u8(sec, 0))
                        + "  信道=" + (ch == null ? -1 : Iap2Link.u8(ch, 0)));
                listener.onWifi(ssid, pass,
                        sec == null ? -1 : Iap2Link.u8(sec, 0),
                        ch == null ? -1 : Iap2Link.u8(ch, 0));
                listener.onStatus("成功拿到车机热点信息");
                done = true;
                stage = 5;
                break;
            }
            case Iap2Link.CSM_WIFI_INFO: {
                log("   车机回 WiFiInformation: status=" + csm.strOf(0) + " ssid=" + csm.strOf(1));
                break;
            }
            default: {
                log("   （未处理的 CSM 0x" + String.format("%04X", csm.msgId) + "）");
            }
        }
    }

    private void advance() throws IOException {
        if (!linkUp) {
            return;
        }
        long now = System.currentTimeMillis();
        if (stage == 0) {
            log("> 发送 StartIdentification (0x1D00)");
            sendCsm(Iap2Link.CSM_START_IDENTIFICATION);
            stage = 1;
            stageAt = now;
            listener.onStatus("等待车机识别信息");
            return;
        }
        if (stage == 1 && now - stageAt > 8000) {
            retries++;
            if (retries <= 5) {
                log("> 未收到识别信息，重发 StartIdentification（第 " + retries + " 次）");
                sendCsm(Iap2Link.CSM_START_IDENTIFICATION);
            }
            stageAt = now;
            return;
        }
        if (stage == 2 && authState == 0) {
            log("> 发送 RequestAuthenticationCertificate (0xAA00)");
            sendCsm(Iap2Link.CSM_REQUEST_AUTH_CERT);
            authState = 1;
            stageAt = now;
            return;
        }
        if (authState == 1 && now - stageAt > 8000) {
            log("> 未收到车机证书，重发 0xAA00");
            sendCsm(Iap2Link.CSM_REQUEST_AUTH_CERT);
            stageAt = now;
            return;
        }
        if (authState == 2 && now - stageAt > 8000) {
            log("> 未收到签名响应，重发挑战 0xAA02");
            sendChallenge();
            stageAt = now;
            return;
        }
        if (authState == 3 && stage == 2) {
            log("> 发送 RequestAccessoryWiFiConfigurationInformation (0x5702)");
            sendCsm(Iap2Link.CSM_REQUEST_WIFI_CONFIG);
            stage = 4;
            stageAt = now;
            listener.onStatus("等待车机 Wi-Fi 凭据");
            return;
        }
        if (stage == 4 && now - stageAt > 10000) {
            log("> 未收到 Wi-Fi 凭据，重发 0x5702");
            sendCsm(Iap2Link.CSM_REQUEST_WIFI_CONFIG);
            stageAt = now;
        }
    }

    /**
     * 发认证挑战。第一次必须 32 字节：DiPlay 的本地 P-256 MFi 身份里写死了
     * require(challenge.size == 32)，发 20 字节它会直接抛异常并断开连接。
     * 重试时退回 20 字节，兼容传统 MFi 协处理器。
     */
    private void sendChallenge() throws IOException {
        int len = challengeAttempts == 0 ? 32 : 20;
        challengeAttempts++;
        byte[] challenge = new byte[len];
        random.nextBytes(challenge);
        sendCsm(Iap2Link.CSM_REQUEST_AUTH_CHALLENGE, Iap2Link.param(0, challenge));
        log("> 发送 " + len + " 字节随机挑战 " + Iap2Link.hex(challenge));
    }

    /** 我们（手机角色）的最小识别信息 */
    private byte[] ourIdentification() {
        return concat(
                Iap2Link.paramStr(0, "iPhone"),
                Iap2Link.paramStr(1, "iPhone14,5"),
                Iap2Link.paramStr(2, "Apple Inc."),
                Iap2Link.paramStr(3, "cplinkprobe0001"),
                Iap2Link.paramStr(4, "18.0"),
                Iap2Link.paramStr(5, "iPhone14,5"),
                Iap2Link.paramStr(12, "zh-CN"),
                Iap2Link.paramStr(13, "zh-CN"));
    }

    // ------------------------------------------------------------- 发包工具

    private void sendCsm(int msgId, byte[]... params) throws IOException {
        sendData(Iap2Link.csm(msgId, params), Iap2Link.SESSION_CONTROL);
    }

    private void sendData(byte[] data, int session) throws IOException {
        seq = (seq + 1) & 0xFF;
        byte[] p = Iap2Link.linkPacket(Iap2Link.ACK, seq, lastRecv, session, data);
        out.write(p);
        out.flush();
        sentPackets.put(seq, p);
        if (sentPackets.size() > 32) {
            sentPackets.clear();
            sentPackets.put(seq, p);
        }
        log("> 发送 " + data.length + "B 会话=" + session + " seq=" + seq + " ack=" + lastRecv);
    }

    private void sendAck() throws IOException {
        byte[] p = Iap2Link.linkPacket(Iap2Link.ACK, seq, lastRecv, 0, null);
        out.write(p);
        out.flush();
        log("> 发送独立 ACK ack=" + lastRecv);
    }

    // ------------------------------------------------------------- 收包工具

    private static final class ByteQueue {
        private byte[] buf = new byte[8192];
        private int head;
        private int tail;

        synchronized void add(byte[] b, int n) {
            ensure(n);
            System.arraycopy(b, 0, buf, tail, n);
            tail += n;
        }

        synchronized void pushFront(byte[] b) {
            ensure(b.length);
            System.arraycopy(buf, head, buf, head + b.length, tail - head);
            System.arraycopy(b, 0, buf, head, b.length);
            tail += b.length;
        }

        private void ensure(int extra) {
            if (tail + extra <= buf.length) {
                return;
            }
            int len = tail - head;
            if (len + extra <= buf.length) {
                System.arraycopy(buf, head, buf, 0, len);
                head = 0;
                tail = len;
                return;
            }
            int newLen = Math.max(buf.length * 2, len + extra + 1024);
            byte[] nb = new byte[newLen];
            System.arraycopy(buf, head, nb, 0, len);
            buf = nb;
            head = 0;
            tail = len;
        }

        synchronized int size() {
            return tail - head;
        }

        synchronized int peek(int i) {
            return buf[head + i] & 0xFF;
        }

        synchronized void drop(int n) {
            head = Math.min(tail, head + n);
        }

        synchronized byte[] read(int n) {
            byte[] out = new byte[n];
            System.arraycopy(buf, head, out, 0, n);
            head += n;
            return out;
        }
    }

    /** 从缓冲区取一个完整 iAP2 包，不足则返回 null */
    private byte[] tryReadPacket() {
        synchronized (queue) {
            while (queue.size() >= 2) {
                if (queue.peek(0) == 0xFF && queue.peek(1) == 0x5A) {
                    break;
                }
                log("丢弃同步字节 0x" + String.format("%02X", queue.peek(0)));
                queue.drop(1);
            }
            if (queue.size() < 9) {
                return null;
            }
            byte[] h = new byte[9];
            for (int i = 0; i < 9; i++) {
                h[i] = (byte) queue.peek(i);
            }
            Iap2Link.Header hdr = Iap2Link.parseHeader(h);
            if (hdr == null) {
                queue.drop(1);
                return null;
            }
            if (hdr.length < 9) {
                queue.drop(1);
                return null;
            }
            if (queue.size() < hdr.length) {
                return null;
            }
            return queue.read(hdr.length);
        }
    }

    private byte[] readN(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stop && !readerStop) {
            synchronized (queue) {
                if (queue.size() >= n) {
                    return queue.read(n);
                }
            }
            if (System.currentTimeMillis() > deadline) {
                return null;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    /** 没数据/连不上时，给出 DiPlay 侧的排查提示 */
    private void hintNoCarplayService() {
        if (!deviceHasCarplayUuid) {
            log("   提示：这台设备的 SDP 里没有 CarPlay 配件服务（…caff）。");
            log("   如果它是跑 DiPlay 的设备：请确认 DiPlay 已切【无线】模式、在设置里配好车机热点、"
                    + "并点了它界面的【连接手机/Connect phone】，且保持 DiPlay 在前台不锁屏。");
            log("   然后回到本应用点【① 自动找车机】重试。");
        }
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) {
            n += a.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    private static String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(无名)" : n;
        } catch (Throwable t) {
            return "(需权限)";
        }
    }

    // --------------------------------------------------------------- 日志

    private void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String full = stamp + " " + line;
        listener.onLog(full);
        // 文件落盘交给 MainActivity.onLog 统一做（v3.5 起它每行都写文件）——
        // 这里再写一次会把每行都存两遍（日志文件体积翻倍），所以去掉。
    }
}

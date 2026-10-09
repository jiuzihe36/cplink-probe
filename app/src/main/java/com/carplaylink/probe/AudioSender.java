package com.carplaylink.probe;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ★ v2.0：AirPlay **音频流**（SETUP type=100）—— 把 CarLife 的媒体音频送进车机。
 *
 * 以前只对车机做了**视频流**（SETUP type=110），音频流从来没建过 → 画面通、**没声音**。
 * 线格式对照 DiPlay 开源实现 `shared/.../airplay/AudioStream.kt` 的 `runData()`（逐字节对齐）：
 *
 *   一条 UDP 数据报 = [12 字节 RTP 头][密文][16 字节 Poly1305 tag][8 字节小端 nonce]
 *     RTP 头：[0x80][payloadType][seq u16 大端][timestamp u32 大端][ssrc u32 大端]
 *     AEAD 的 AAD = **头里后 8 字节**（timestamp + SSRC），**不含 seq**
 *     nonce = 12 字节 = 4 个零字节 + 8 字节**小端**计数器（收发各自计数，计数器随包尾一起发过去）
 *   密钥 = HKDF(shared, "DataStream-Salt" + 音频流的 streamConnectionID,
 *               "DataStream-Output-Encryption-Key", 32)
 *          —— 与视频流**同一套派生**，只是用音频流自己的 connectionID。
 *
 * 两个实现上的坑：
 * 1. **必须分片**：车机的收包缓冲是 4096 字节（DiPlay `DATAGRAM_BYTES`），
 *    CarLife 一条音频就是 4096 字节，加头加尾 4132 字节 → 超 MTU 被 IP 分片、还可能被截断。
 *    这里按 1KB（512 采样 ≈ 11.6ms）切开，一包 1060 字节。
 * 2. **不用转码**，但**必须换成大端**：CarLife 的音频是 44100/2/16 裸 PCM（小端），
 *    而 DiPlay 收到 LPCM 后会调 `byteSwapS16(...)`（`AndroidMediaSink.handle()`）——
 *    也就是**线格式是大端 16 位**。原样转发小端 PCM 的后果实测是"车机上很大的杂音"。
 */
public final class AudioSender {

    /** 每包 PCM 字节数（512 采样 × 2 声道 × 2 字节） */
    private static final int CHUNK = 1024;
    /** 队列上限（约 0.37 秒的 44100/2/16），满了丢最老的，避免延迟越攒越大 */
    private static final int MAX_QUEUE = 64 * 1024;

    private static final Object LOCK = new Object();
    private static final byte[] queue = new byte[MAX_QUEUE];
    private static int qHead;
    private static int qLen;

    private static volatile boolean running;
    private static final AtomicLong pushed = new AtomicLong();
    private static final AtomicLong dropped = new AtomicLong();
    private static volatile long sentPackets;
    private static volatile long sentBytes;

    private AudioSender() {
    }

    /** CarLife 通道 3 的 PCM 往这里灌（线程安全；没开音频流时直接丢） */
    public static void pushPcm(byte[] pcm) {
        if (!running || pcm == null || pcm.length == 0) {
            return;
        }
        synchronized (LOCK) {
            pushed.addAndGet(pcm.length);
            if (pcm.length >= MAX_QUEUE) {
                dropped.addAndGet(pcm.length);
                return;
            }
            if (qLen + pcm.length > MAX_QUEUE) {
                int drop = qLen + pcm.length - MAX_QUEUE;
                qHead = (qHead + drop) % MAX_QUEUE;
                qLen -= drop;
                dropped.addAndGet(drop);
            }
            int tail = (qHead + qLen) % MAX_QUEUE;
            int first = Math.min(pcm.length, MAX_QUEUE - tail);
            System.arraycopy(pcm, 0, queue, tail, first);
            if (first < pcm.length) {
                System.arraycopy(pcm, first, queue, 0, pcm.length - first);
            }
            qLen += pcm.length;
            LOCK.notifyAll();
        }
    }

    /** 取一整包（不够就等）；返回实际字节数，流停了返回 -1 */
    private static int take(byte[] out) {
        synchronized (LOCK) {
            while (running && qLen < out.length) {
                try {
                    LOCK.wait(200);
                } catch (InterruptedException e) {
                    return -1;
                }
            }
            if (!running) {
                return -1;
            }
            int n = Math.min(out.length, qLen);
            int first = Math.min(n, MAX_QUEUE - qHead);
            System.arraycopy(queue, qHead, out, 0, first);
            if (first < n) {
                System.arraycopy(queue, 0, out, first, n - first);
            }
            qHead = (qHead + n) % MAX_QUEUE;
            qLen -= n;
            return n;
        }
    }

    /** 新一轮会话开始：清掉上一轮积压的 PCM，但**不要**把线程杀掉
     *  （v2.0 的 bug 就是这里：VideoSender.run() 里调了 stop()，把刚启动的音频线程当场关了
     *   → 日志里 `音频流结束：共发 0 包`） */
    public static void resetQueue() {
        synchronized (LOCK) {
            qLen = 0;
            qHead = 0;
        }
    }

    public static void stop() {
        running = false;
        synchronized (LOCK) {
            qLen = 0;
            qHead = 0;
            LOCK.notifyAll();
        }
    }

    /** 车机已经给了音频 dataPort + 密钥 → 开一条 UDP 发送线程 */
    public static boolean start(final String host, final int port, final byte[] key,
                                final AirPlayProbe.Logger log) {
        if (running) {
            return true;
        }
        if (key == null || key.length != 32) {
            log.log("!! 音频流密钥没派生出来（" + (key == null ? "null" : key.length + " 字节")
                    + "）—— 音频不推");
            return false;
        }
        running = true;
        sentPackets = 0;
        sentBytes = 0;
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket();
                    sock.setSendBufferSize(256 * 1024);
                    InetSocketAddress dst = new InetSocketAddress(host, port);
                    final int ssrc = 0x43504C4B;   // "CPLK"
                    byte[] pcm = new byte[CHUNK];
                    byte[] header = new byte[12];
                    byte[] counter = new byte[8];
                    byte[] nonce = new byte[12];
                    byte[] aad = new byte[8];
                    int seq = 0;
                    long ts = 0;
                    log.log("★ 音频流已开：往车机 " + host + ":" + port
                            + " 发 PCM 44100/2/16（每包 " + CHUNK + " 字节，"
                            + "线格式 [12 头][密文][16 tag][8 小端 nonce]）");
                    while (running && !VideoSender.stopRequested) {
                        int n = take(pcm);
                        if (n <= 0) {
                            break;
                        }
                        header[0] = (byte) 0x80;
                        header[1] = (byte) 0x60;                       // PCM 载荷类型
                        header[2] = (byte) (seq >> 8);
                        header[3] = (byte) seq;
                        header[4] = (byte) (ts >> 24);
                        header[5] = (byte) (ts >> 16);
                        header[6] = (byte) (ts >> 8);
                        header[7] = (byte) ts;
                        header[8] = (byte) (ssrc >> 24);
                        header[9] = (byte) (ssrc >> 16);
                        header[10] = (byte) (ssrc >> 8);
                        header[11] = (byte) ssrc;
                        System.arraycopy(header, 4, aad, 0, 8);
                        long ctr = seq;
                        for (int i = 0; i < 8; i++) {
                            counter[i] = (byte) (ctr >> (8 * i));      // 小端
                        }
                        for (int i = 0; i < 12; i++) {
                            nonce[i] = 0;
                        }
                        System.arraycopy(counter, 0, nonce, 4, 8);
                        // ★ v2.2：小端 → **大端**（DiPlay 会 byteSwapS16；不换就是一片杂音）
                        int even = n & ~1;
                        if (even <= 0) {
                            continue;
                        }
                        byte[] body = new byte[even];
                        for (int i = 0; i < even; i += 2) {
                            body[i] = pcm[i + 1];
                            body[i + 1] = pcm[i];
                        }
                        byte[] sealed = Crypto.chachaSeal(key, nonce, body, aad);
                        byte[] wire = new byte[12 + sealed.length + 8];
                        System.arraycopy(header, 0, wire, 0, 12);
                        System.arraycopy(sealed, 0, wire, 12, sealed.length);
                        System.arraycopy(counter, 0, wire, 12 + sealed.length, 8);
                        sock.send(new DatagramPacket(wire, wire.length, dst));
                        seq = (seq + 1) & 0xFFFF;
                        ts += even / 4;                                // 16 位立体声 = 4 字节/采样
                        long sp = ++sentPackets;
                        sentBytes += n;
                        if (sp == 1) {
                            log.log("   ✓ 音频第 1 包已发出（" + wire.length + " 字节 UDP，seq=0 ts=0）");
                        } else if (sp % 500 == 0) {
                            log.log("   （音频已发 " + sp + " 包 / " + (sentBytes / 1024)
                                    + " KB PCM；源共灌入 " + (pushed.get() / 1024)
                                    + " KB，丢 " + (dropped.get() / 1024) + " KB）");
                        }
                    }
                } catch (Throwable t) {
                    log.log("!! 音频发送线程出错: " + t);
                } finally {
                    running = false;
                    if (sock != null) {
                        try {
                            sock.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    log.log("   （音频流结束：共发 " + sentPackets + " 包 / "
                            + (sentBytes / 1024) + " KB PCM）");
                }
            }
        }, "airplay-audio-tx").start();
        return true;
    }
}

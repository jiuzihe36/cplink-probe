package com.carplaylink.probe;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录「我们自己占着的端口」—— v3.4 修掉的第一个大坑。
 *
 * v3.3 及以前：诊断流程里**自动反向监听** 7240/8240/… 这 7 个端口，然后再去 connect 它们。
 * 结果连上的是**我们自己**，于是日志里出现一串看着像成功的证据：
 *
 *   ★ 通道 1 已连上 127.0.0.1:7240（控制）
 *   ★★ 有连接进来！本机端口 7240 ← 127.0.0.1:58996
 *        首包 12 字节: 000400000001800108041000   ← 这正是我们自己刚发出去的 98305 帧
 *   >> 通道1 service=98305（车机:版本协商）
 *   !! 发送失败: java.net.SocketException: Broken pipe  ← 自己的 accept 读完就 close 了
 *
 * 也就是说：**"端口在听" 和 "握手回包" 全是自己跟自己对暗号**，Car+ 到底开没开端口根本没被验证过。
 * 修法：把所有自己 bind 的端口登记在这里，任何"探测/命中"逻辑都必须先问 {@link #holds(int)}，
 * 自占的一律跳过并标注。这样 connect 成功 = 真的有别人在听，一个字都不用猜。
 *
 * 反向监听保留，但改成**用户显式点按钮**才做（见 {@link #listen}），不再进诊断流程。
 */
public final class SelfPorts {

    private static final Set<Integer> HELD = new LinkedHashSet<>();
    private static final List<ServerSocket> SERVERS = new ArrayList<>();
    private static volatile boolean listening;

    private SelfPorts() {
    }

    /** 这个端口是不是我们自己占着的（占着就绝不能再把它当"对端"） */
    public static synchronized boolean holds(int port) {
        return HELD.contains(port);
    }

    public static synchronized String heldText() {
        return HELD.isEmpty() ? "无" : HELD.toString();
    }

    public static synchronized boolean isListening() {
        return listening;
    }

    /** 显式反向监听（只有用户主动点【反向监听】才走这里）。返回成功 bind 的端口数。 */
    public static synchronized int listen(int[] ports, final AirPlayProbe.Logger log) {
        final LogFold fold = new LogFold(log);
        closeAll();
        listening = true;
        int ok = 0;
        for (final int port : ports) {
            try {
                final ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", port), 8);
                SERVERS.add(ss);
                HELD.add(port);
                ok++;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (listening) {
                            try {
                                Socket s = ss.accept();
                                String peer = s.getInetAddress().getHostAddress() + ":" + s.getPort();
                                String me = s.getLocalAddress().getHostAddress() + ":" + s.getLocalPort();
                                fold.log("★★ 有连接进来！本机端口 " + port + " ← " + peer
                                        + "（本地地址 " + me + "）");
                                s.setSoTimeout(3000);
                                byte[] b = new byte[512];
                                int n = s.getInputStream().read(b);
                                if (n > 0) {
                                    fold.log("     首包 " + n + " 字节: "
                                            + Crypto.hex(java.util.Arrays.copyOf(b, n)));
                                }
                                s.close();
                            } catch (Throwable t) {
                                return;
                            }
                        }
                    }
                }, "acc-" + port).start();
            } catch (Throwable t) {
                log.log("   反向监听 " + port + " 失败: " + t);
            }
        }
        return ok;
    }

    public static synchronized void closeAll() {
        listening = false;
        for (ServerSocket s : SERVERS) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        SERVERS.clear();
        HELD.clear();
    }
}

package com.carplaylink.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 找车机（AirPlay 接收端）在哪台机器上。
 *
 * 两个坑：
 *  1) 不能假设"网关就是车机" —— 车机自己的热点里它常是网关，接在别的路由器上时就只是普通设备。
 *  2) 不能只取 mDNS 的第一个结果 —— 家里/办公室的 Mac、Apple TV 也广播 _airplay._tcp，
 *     往往比车机先答（而且回 403）。所以这里把所有候选都收起来，由调用方逐个试。
 */
public final class AirPlayDiscovery {

    public static final class Found {
        public final String host;
        public final int port;

        Found(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private AirPlayDiscovery() {
    }

    /** 收集所有可能的 AirPlay 目标：mDNS 全部 → 网关 → 扫网段 */
    public static List<Found> discoverAll(Context ctx, Network network, String gatewayHint, AirPlayProbe.Logger log) {
        List<Found> out = new ArrayList<>();
        mdns(ctx, log, 8000, out);
        if (gatewayHint != null && probe(network, gatewayHint, 7000, 1500)) {
            if (addUnique(out, new Found(gatewayHint, 7000))) {
                log.log("   网关 " + gatewayHint + ":7000 能连上，也列进候选");
            }
        }
        if (out.isEmpty()) {
            scan(ctx, network, log, out);
        }
        log.log("   共 " + out.size() + " 个 AirPlay 候选，逐个试");
        return out;
    }

    private static boolean addUnique(List<Found> out, Found f) {
        for (Found e : out) {
            if (e.host.equals(f.host) && e.port == f.port) {
                return false;
            }
        }
        out.add(f);
        return true;
    }

    /** mDNS（Bonjour）：窗口期内解析出来的 _airplay._tcp 全收 */
    private static void mdns(Context ctx, AirPlayProbe.Logger log, int timeoutMs, List<Found> out) {
        NsdManager mgr = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (mgr == null) {
            return;
        }
        final AtomicBoolean resolving = new AtomicBoolean(false);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                log.log("   mDNS 启动失败（错误码 " + error + "）");
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
                log.log("   mDNS 开始搜索 " + type + " …");
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                log.log("   mDNS 发现: " + info.getServiceName());
                if (!resolving.compareAndSet(false, true)) {
                    return;
                }
                try {
                    mgr.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int error) {
                            resolving.set(false);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            resolving.set(false);
                            InetAddress addr = serviceInfo.getHost();
                            if (addr != null) {
                                synchronized (out) {
                                    addUnique(out, new Found(addr.getHostAddress(), serviceInfo.getPort()));
                                }
                            }
                        }
                    });
                } catch (Throwable t) {
                    resolving.set(false);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            mgr.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            log.log("   mDNS 起不来: " + t);
            return;
        }
        try {
            Thread.sleep(timeoutMs);
        } catch (InterruptedException ignored) {
        } finally {
            try {
                mgr.stopServiceDiscovery(listener);
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            log.log("   mDNS 没找到（车机可能不广播，或手机不在车机那个网里）");
        }
    }

    /** 扫本机所在 /24 网段的 7000 端口 */
    private static void scan(Context ctx, Network network, AirPlayProbe.Logger log, List<Found> out) {
        String local = AirPlayProbe.localIpv4(ctx, network);
        if (local == null || !local.contains(".")) {
            log.log("   拿不到本机 IP，没法扫网段");
            return;
        }
        final String prefix = local.substring(0, local.lastIndexOf('.') + 1);
        log.log("   扫网段 " + prefix + "1-254 的 7000 端口（最多 12 秒）…");
        final ArrayBlockingQueue<Found> queue = new ArrayBlockingQueue<>(32);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    if (probe(network, host, 7000, 400)) {
                        queue.offer(new Found(host, 7000));
                    }
                }
            });
        }
        pool.shutdown();
        try {
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                Found f = queue.poll(500, TimeUnit.MILLISECONDS);
                if (f != null) {
                    synchronized (out) {
                        addUnique(out, f);
                    }
                } else if (pool.isTerminated()) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            pool.shutdownNow();
        }
        if (out.isEmpty()) {
            log.log("   网段里没有开 7000 端口的机器");
        }
    }

    static boolean probe(Network network, String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = (network != null) ? network.getSocketFactory().createSocket() : new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s.isConnected();
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static String gatewayOf(Context ctx, Network network) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = (cm == null || network == null) ? null : cm.getLinkProperties(network);
            if (lp == null) {
                return null;
            }
            for (android.net.RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null) {
                    return r.getGateway().getHostAddress();
                }
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() != null && la.getAddress().getHostAddress().contains(".")) {
                    String a = la.getAddress().getHostAddress();
                    return a.substring(0, a.lastIndexOf('.') + 1) + "1";
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

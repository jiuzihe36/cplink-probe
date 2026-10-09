package com.carplaylink.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Car+ 接屏探针（v3.1）—— 直接接住 OPPO Car+ 渲染的车机画面。
 *
 * 契约来自对 com.oplus.ocar（Car+ 车联）APK 的反向，全部实测值：
 *
 *   服务：com.oplus.ocar / com.oplus.ocar.connect.carlife.CarlifeCastManagerService（exported，无权限）
 *   接口：com.baidu.carlife.mixing.aidl.ICarLifeCastManager（百度的 AIDL，和小米侧同一个）
 *   事务号（从它的 CastManagerServiceStub.onTransact 里读出来的）：
 *     1 = d4(Intent) -> Bundle        查询（**无签名校验**）
 *     2 = setCastCallback(cb)         注册回调（**有签名校验**：硬编码 sha256 509e03e3…8667）
 *     3 = setSystemAudioCallback(cb)  同上，有校验
 *     4 = stopCast()                  无校验
 *     5 = prepareCast(CastConfig)     **无校验** —— CastConfig{width,height,dpi,Surface}
 *
 * 关键点：`prepareCast` 不校验签名，而它的参数里带一个 Surface —— 也就是
 * **Car+ 会把车机界面渲染进我们给的那块 Surface**。所以流程是：
 *   绑服务 → prepareCast(我们的 Surface) → Car+ 往这块 Surface 出画面 → 我们编码后走 CarPlay 上车。
 * 不需要 CarLife 协议、不需要车机上装任何东西。
 *
 * 本版先只做"接住"：用 ImageReader 当 Surface，数帧、报尺寸；通了再换成编码器输入 Surface。
 */
public final class CarPlusCastProbe {

    private static final String PKG = "com.oplus.ocar";
    private static final String SVC = "com.oplus.ocar.connect.carlife.CarlifeCastManagerService";
    private static final String DESC = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager";

    private static final int TX_QUERY = 1;        // d4(Intent) -> Bundle
    private static final int TX_SET_CALLBACK = 2; // setCastCallback(cb)
    private static final int TX_STOP = 4;         // stopCast()
    private static final int TX_PREPARE = 5;      // prepareCast(CastConfig)

    private static final int W = 1280;
    private static final int H = 720;
    private static final int DPI = 160;

    private CarPlusCastProbe() {
    }

    public static void run(Context ctx, AirPlayProbe.Logger log) {
        log.log("══ Car+ 接屏探针（v3.2）══");
        diagnose(ctx, log);
        log.log("");
        log.log("—— 进入正式流程：绑 " + SVC + " ——");

        final Object lock = new Object();
        final IBinder[] holder = new IBinder[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName n, IBinder b) {
                synchronized (lock) {
                    holder[0] = b;
                    lock.notifyAll();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName n) {
                synchronized (lock) {
                    holder[0] = null;
                    lock.notifyAll();
                }
            }
        };

        Intent bind = new Intent();
        bind.setComponent(new ComponentName(PKG, SVC));
        boolean ok;
        try {
            ok = ctx.bindService(bind, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            log.log("!! bindService 抛异常（可能被签名/权限拦）: " + t);
            return;
        }
        log.log("   bindService 返回 " + ok + "，等 onServiceConnected（最多 8 秒）…");
        synchronized (lock) {
            long dl = System.currentTimeMillis() + 8000;
            while (holder[0] == null && System.currentTimeMillis() < dl) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        IBinder b = holder[0];
        if (b == null) {
            log.log("!! 没拿到 binder —— 服务没起来 / 被拒（这是第一道门，先把这个结果发我）");
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            log.log("★ 拿到 binder！descriptor=" + b.getInterfaceDescriptor());
        } catch (Throwable t) {
            log.log("★ 拿到 binder（descriptor 读不到: " + t + "）");
        }

        query(b, log);
        registerCallback(b, log);
        prepareCast(ctx, b, log);

        try {
            ctx.unbindService(conn);
            log.log("   已解绑");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 绑定诊断

    /** 对照实验用的候选组件（同一包里的其它 exported 服务 + 隔壁包的） */
    private static final String[][] CONTROLS = {
            {"com.oplus.ocar", "com.oplus.ocar.connect.vdp.ProxyAudioVDPService"},
            {"com.oplus.ocar", "com.oplus.ocar.launcher.CarLinkLauncherService"},
            {"com.oplus.ocar", "com.ucar.app.ability.UCarAbilityService"},
            {"com.heytap.opluscarlink", "com.heytap.opluscarlink.car.OplusSdkService"},
            {"com.oplus.linker", "com.oplus.linker.synergy.service.SynergyCoreService"},
    };

    private static void diagnose(Context ctx, AirPlayProbe.Logger log) {
        PackageManager pm = ctx.getPackageManager();
        log.log("★ 1) 目标组件在本机上的真实状态（读的是设备上那份 manifest）:");
        describe(pm, log, PKG, SVC);

        log.log("★ 2) 对照：同包/隔壁包的其它 exported 服务谁绑得上（区分「包级限制」还是「这个组件」）:");
        for (String[] c : CONTROLS) {
            describe(pm, log, c[0], c[1]);
            log.log("      → bind 结果 " + tryBind(ctx, c[0], c[1]));
        }

        log.log("★ 3) 相关权限我们有没有:");
        String[] perms = {"com.oplus.permission.safe.CAR_LINK", "com.ucar.permission.UCAR_SERVICE",
                "com.oplus.permission.safe.IOT", "com.oplus.permission.safe.CONNECTIVITY"};
        for (String p : perms) {
            int r;
            try {
                r = ctx.checkSelfPermission(p);
            } catch (Throwable t) {
                r = -1;
            }
            log.log("      " + p + " → " + (r == PackageManager.PERMISSION_GRANTED ? "有" : "没有"));
        }

        log.log("★ 4) CarLife 组件在不在（很多车联入口要它先装上才启用）:");
        for (String p : new String[]{"com.baidu.carlife.oppo", "com.baidu.carlife", "com.baidu.carlife.xiaomi"}) {
            String r;
            try {
                pm.getPackageInfo(p, 0);
                r = "已装";
            } catch (Throwable t) {
                r = "未装";
            }
            log.log("      " + p + " → " + r);
        }
    }

    /** 打印组件的 exported / enabled / permission / 启用状态，并 resolve 一下 */
    private static void describe(PackageManager pm, AirPlayProbe.Logger log, String pkg, String svc) {
        ComponentName cn = new ComponentName(pkg, svc);
        try {
            ServiceInfo si = pm.getServiceInfo(cn, 0);
            String enabledSetting;
            try {
                int es = pm.getComponentEnabledSetting(cn);
                enabledSetting = es == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ? "启用"
                        : es == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? "被禁用"
                        : es == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ? "被用户禁用"
                        : "默认";
            } catch (Throwable t) {
                enabledSetting = "?";
            }
            log.log("   " + pkg + "/" + svc);
            log.log("      exported=" + si.exported + "  manifest.enabled=" + si.enabled
                    + "  实际启用状态=" + enabledSetting
                    + "  permission=" + (si.permission == null ? "无" : si.permission)
                    + "  process=" + si.processName);
            try {
                android.content.pm.ApplicationInfo ai = si.applicationInfo;
                if (ai != null) {
                    log.log("      应用 enabled=" + ai.enabled
                            + "  flags=0x" + Integer.toHexString(ai.flags));
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log.log("   " + pkg + "/" + svc + " 读不到组件信息: " + t);
        }
        try {
            Intent i = new Intent();
            i.setComponent(cn);
            ResolveInfo ri = pm.resolveService(i, 0);
            log.log("      resolveService: " + (ri == null ? "解析不到（不可绑）" : "可解析"));
        } catch (Throwable t) {
            log.log("      resolveService 异常: " + t);
        }
    }

    /** 只绑一下、立刻解绑，返回 bind 结果（true/false）或异常名 */
    private static String tryBind(Context ctx, String pkg, String svc) {
        final Object lock = new Object();
        final boolean[] got = {false};
        ServiceConnection c = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName n, IBinder b) {
                synchronized (lock) {
                    got[0] = true;
                    lock.notifyAll();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName n) {
            }
        };
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, svc));
        try {
            boolean r = ctx.bindService(i, c, Context.BIND_AUTO_CREATE);
            if (r) {
                synchronized (lock) {
                    if (!got[0]) {
                        try {
                            lock.wait(1500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            try {
                ctx.unbindService(c);
            } catch (Throwable ignored) {
            }
            return r + (got[0] ? "（且已连上）" : "");
        } catch (Throwable t) {
            return "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    // ---------------------------------------------------------------- 1) 查询

    private static void query(IBinder b, AirPlayProbe.Logger log) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Intent q = new Intent();
            // 参考实现查询视频格式时用的那几个键，照抄一份，让它认得出这是"问格式"
            q.putExtra("width", W);
            q.putExtra("height", H);
            q.putExtra("real_width", W);
            q.putExtra("real_height", H);
            q.putExtra("default_width", W);
            q.putExtra("default_height", H);
            q.putExtra("carlife_encode_format", 0);
            data.writeInterfaceToken(DESC);
            data.writeInt(1);
            q.writeToParcel(data, 0);
            boolean r = b.transact(TX_QUERY, data, reply, 0);
            reply.readException();
            int flag = reply.dataAvail() > 0 ? reply.readInt() : 0;
            log.log("★ transact(1) 查询返回 " + r + "，null标志=" + flag + "，reply 剩余 " + reply.dataAvail());
            if (flag != 0) {
                Bundle bun = reply.readBundle(CarPlusCastProbe.class.getClassLoader());
                if (bun != null) {
                    StringBuilder sb = new StringBuilder("   Bundle 内容: ");
                    for (String k : bun.keySet()) {
                        sb.append(k).append('=').append(bun.get(k)).append("  ");
                    }
                    log.log(sb.toString());
                } else {
                    log.log("   Bundle 为空");
                }
            }
        } catch (Throwable t) {
            log.log("   transact(1) 查询失败: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ------------------------------------------------- 2) 试注册回调（预期被拒）

    private static void registerCallback(IBinder b, AirPlayProbe.Logger log) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            android.os.Binder cb = new android.os.Binder() {
                @Override
                protected boolean onTransact(int code, Parcel d, Parcel r, int flags) {
                    log.log("   ← 回调被调用 code=" + code);
                    return true;
                }
            };
            data.writeInterfaceToken(DESC);
            data.writeStrongBinder(cb);
            boolean r = b.transact(TX_SET_CALLBACK, data, reply, 0);
            reply.readException();
            log.log("★ transact(2) setCastCallback 返回 " + r + "（若 Car+ 侧日志出现 invalid signature 就是被签名门挡了）");
        } catch (Throwable t) {
            log.log("   transact(2) setCastCallback 失败: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ------------------------------------------------------------ 3) prepareCast

    private static void prepareCast(Context ctx, IBinder b, AirPlayProbe.Logger log) {
        ImageReader reader = null;
        final AtomicInteger frames = new AtomicInteger();
        try {
            reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
            final AirPlayProbe.Logger lg = log;
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                    } catch (Throwable ignored) {
                    }
                    if (img == null) {
                        return;
                    }
                    int n = frames.incrementAndGet();
                    if (n == 1 || n % 30 == 0) {
                        lg.log("   ★ Car+ 画面帧 #" + n + "  " + img.getWidth() + "x" + img.getHeight());
                    }
                    img.close();
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));   // ★ 必须给 Handler：后台线程没有 Looper
            Surface sf = reader.getSurface();

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(DESC);
            data.writeInt(1);                 // CastConfig 非空
            data.writeInt(W);                 // width
            data.writeInt(H);                 // height
            data.writeInt(DPI);               // dpi
            data.writeParcelable(sf, 0);      // surface —— Car+ 会往这里画
            boolean r = b.transact(TX_PREPARE, data, reply, 0);
            reply.readException();
            log.log("★ transact(5) prepareCast(" + W + "x" + H + " dpi" + DPI + ") 返回 " + r);
            data.recycle();
            reply.recycle();

            log.log("   等 12 秒，看 Car+ 会不会往我们给的 Surface 出画面…");
            long dl = System.currentTimeMillis() + 12000;
            int last = -1;
            while (System.currentTimeMillis() < dl) {
                Thread.sleep(1000);
                int n = frames.get();
                if (n != last) {
                    log.log("   …已收到 " + n + " 帧");
                    last = n;
                }
            }
            log.log("★ 12 秒共收到 Car+ 画面帧 " + frames.get() + " 张"
                    + (frames.get() > 0 ? " —— 接住了！下一步把它接进编码器/ CarPlay" : "（没出帧：可能要先把回调注册上，或被签名门挡住）"));

            // stopCast，避免留一个半死的会话
            Parcel d2 = Parcel.obtain();
            Parcel r2 = Parcel.obtain();
            try {
                d2.writeInterfaceToken(DESC);
                b.transact(TX_STOP, d2, r2, 0);
                r2.readException();
                log.log("★ transact(4) stopCast 已发");
            } catch (Throwable t) {
                log.log("   stopCast 失败: " + t);
            } finally {
                d2.recycle();
                r2.recycle();
            }
        } catch (Throwable t) {
            log.log("   prepareCast 失败: " + t);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

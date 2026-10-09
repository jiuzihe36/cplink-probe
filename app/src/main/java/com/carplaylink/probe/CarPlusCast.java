package com.carplaylink.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * 让 **Car+ 把它的车机界面渲染进我们给的 Surface**（绕开 CarLife / Mix 授权的那条正路）。
 *
 * 全部事实都来自**反编译真包**（Car+ 17.31.0 用户手机上的版本 + CarLife+ 8.6.8），不是猜的：
 *
 * 服务：`com.oplus.ocar/com.oplus.ocar.connect.carlife.CarlifeCastManagerService`
 * AIDL `com.baidu.carlife.mixing.aidl.ICarLifeCastManager`
 *
 * ★ 事务号（**踩过大坑**）：Car+ 17.31.0 的 `Stub.onTransact` 分派与 CarLife+ 8.6.8 的客户端
 *   `Stub$Proxy` 两边**完全一致**，但和我们最早以为的**顺序不一样**：
 *
 *   | 号 | 方法 | 参数 |
 *   |---|---|---|
 *   | **1** | **prepareCast** | `CastConfig`（写 `writeInt(1)` 非空 + `CastConfig.writeToParcel`）|
 *   | 2 | stopCast | 无 |
 *   | 3 | setSystemAudioCallback | `ISystemAudioCallback` |
 *   | **4** | **setCastCallback** | `ICarLifeCastCallback` |
 *   | 5 | invokeMethod | `Intent` → `Bundle` |
 *
 *   历史教训：早前误以为 5=prepareCast、2=setCastCallback，结果
 *   ① 发给 2 的"setCastCallback"其实是 stopCast（无参数，所以"返回 true"是假的）；
 *   ② 发给 5 的 CastConfig 被当成 **Intent** 解 —— `Intent` 里有个 Uri 字段，
 *      它读到的"Uri 类型"正是我们写的 height，于是报
 *      `IllegalArgumentException: Unknown URI type: 720`。**8 种字段布局全试过都是这个错**，
 *      因为根本不是布局问题，是事务号错了。
 *
 * `CastConfig`（`com.baidu.carlife.mixing.CastConfig`，两个包里的字段顺序相同）：
 *   `writeInt(width); writeInt(height); writeInt(dpi); writeParcelable(surface, 0)`
 *
 * Car+ → 我们（回调 `ICarLifeCastCallback`，编号来自 Car+ 里生成的代理）：
 *   1=`onServiceReady()`、2=`onCastPrepared(int)`、3/4=给触摸监听器、5/6=给按键监听器、
 *   9=`invokeMethod(Intent)->Bundle`
 *
 * 我们 → Car+：
 *   `IRemoteTouchListener.onTouchEvent(MotionEvent)->boolean` = **transact(1)**
 *   `IRemoteKeyListener.onKeyEvent(KeyEvent)->boolean`       = **transact(1)**
 *
 * 链路：Car+ --渲染--> 我们的编码器输入 Surface --H.264--> CarPlay 视频流 --> 车机
 *      车机触摸 (AirPlay HID) --> IRemoteTouchListener.onTouchEvent --> Car+
 */
public final class CarPlusCast {

    private static final String SVC = "com.oplus.ocar";
    private static final String CLS = "com.oplus.ocar.connect.carlife.CarlifeCastManagerService";
    private static final String MGR_DESC = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager";
    private static final String CB_DESC = "com.baidu.carlife.mixing.aidl.ICarLifeCastCallback";
    private static final String TOUCH_DESC = "com.baidu.carlife.mixing.aidl.IRemoteTouchListener";
    private static final String KEY_DESC = "com.baidu.carlife.mixing.aidl.IRemoteKeyListener";

    // ★ ICarLifeCastManager 的事务号（反编译 Car+ 17.31.0 Stub + CarLife+ 8.6.8 Proxy 双向确认）
    private static final int TX_PREPARE = 1;
    private static final int TX_STOP = 2;
    private static final int TX_SET_AUDIO = 3;
    private static final int TX_SET_CALLBACK = 4;
    private static final int TX_INVOKE = 5;

    // Car+ 回调我们时用的事务号
    private static final int CB_ON_SERVICE_READY = 1;
    private static final int CB_ON_CAST_PREPARED = 2;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile IBinder mgr;
    private static volatile IBinder touchBinder;
    private static volatile IBinder keyBinder;
    private static volatile boolean prepared;
    private static volatile boolean callbackSet;
    private static volatile int lastPreparedCode = Integer.MIN_VALUE;

    private static AirPlayProbe.Logger log;
    private static Surface surface;
    private static int w = 1280;
    private static int h = 720;
    private static int dpi = 160;

    private CarPlusCast() {
    }

    private static void log(String s) {
        AirPlayProbe.Logger l = log;
        if (l != null) {
            l.log(s);
        }
    }

    /** 车机触摸是不是已经能直达 Car+ */
    public static boolean touchReady() {
        return touchBinder != null;
    }

    /** Car+ 有没有确认接过我们的 Surface（回调 onCastPrepared） */
    public static boolean prepared() {
        return prepared;
    }

    /**
     * Car+ 往我们这里回调。
     *
     * 编号（反编译 Car+ 里生成的代理 `Lh/a` 得到，就是它 `transact(N)` 的 N）：
     *   1=onServiceReady()   2=onCastPrepared(int)   3/4=触摸监听器   5/6=按键监听器
     *   9=invokeMethod(Intent)->Bundle   10/12/13=音频相关
     *
     * 触摸/按键监听器怎么区分：拿到的 binder 是 Car+ 里的 `$c`（继承 `IRemoteTouchListener$Stub`）
     * 或 `$b`（继承 `IRemoteKeyListener$Stub`），用 `queryLocalInterface` 问一下就知道是哪个。
     */
    private static final Binder CALLBACK = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                data.enforceInterface(CB_DESC);
            } catch (Throwable ignored) {
                // 描述符不符也继续
            }
            try {
                switch (code) {
                    case CB_ON_SERVICE_READY:
                        log("★ [Car+回调] onServiceReady() —— Car+ 的投屏服务就绪");
                        break;
                    case CB_ON_CAST_PREPARED: {
                        int v = data.readInt();
                        lastPreparedCode = v;
                        log("★★ [Car+回调] onCastPrepared(" + v + ") —— **Car+ 接过 Surface 了**");
                        break;
                    }
                    default: {
                        // 3/4/5/6 之类：参数是 listener binder；9 是 invokeMethod(Intent)
                        IBinder b = null;
                        try {
                            b = data.readStrongBinder();
                        } catch (Throwable ignored) {
                            // 没有 binder 参数
                        }
                        if (b != null) {
                            String kind = null;
                            try {
                                if (b.queryLocalInterface(TOUCH_DESC) != null) {
                                    kind = "触摸";
                                    touchBinder = b;
                                } else if (b.queryLocalInterface(KEY_DESC) != null) {
                                    kind = "按键";
                                    keyBinder = b;
                                }
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            if (kind == null) {
                                // 问不出来就按顺序补位（先触摸后按键）
                                if (touchBinder == null) {
                                    touchBinder = b;
                                    kind = "触摸(补位)";
                                } else if (keyBinder == null) {
                                    keyBinder = b;
                                    kind = "按键(补位)";
                                } else {
                                    kind = "多余 listener";
                                }
                            }
                            log("   [Car+回调] code=" + code + " → 记下" + kind + " listener binder");
                        } else {
                            log("   [Car+回调] code=" + code + "（无 binder 参数）");
                        }
                        break;
                    }
                }
            } catch (Throwable t) {
                log("   [Car+回调] code=" + code + " 处理异常: " + t);
            }
            if (reply != null) {
                try {
                    reply.writeNoException();
                    if (code == TX_INVOKE) {
                        // 回调的 invokeMethod 期望回一个 Bundle（非空标志 + Bundle）
                        reply.writeInt(1);
                        new Bundle().writeToParcel(reply, 0);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            return true;
        }
    };

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mgr = service;
            String desc = null;
            try {
                desc = service == null ? "null" : service.getInterfaceDescriptor();
            } catch (Throwable ignored) {
                // 忽略
            }
            log("★ 已绑上 Car+ cast 服务，descriptor=" + desc);
            UI.post(new Runnable() {
                @Override
                public void run() {
                    setCallback();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mgr = null;
            prepared = false;
            callbackSet = false;
            touchBinder = null;
            keyBinder = null;
            log("   Car+ cast 服务断开");
        }
    };

    /**
     * ★ 关键：**必须趁我们自己的界面还在前台时绑**。
     *
     * 实测：在 CarPlay 流程跑完（约 20 秒）之后再绑 → `bindService` 返回 **false**
     * （Android 的后台限制：后台 App 不允许绑定别的 App 的服务，系统日志是
     * "Background start not allowed: service ..."）。而刚点完按钮、我们界面还在最前时绑 → true。
     *
     * 所以流程拆开：**按下按钮就绑**（这里），Surface 等 CarPlay 流起来后再用
     * {@link #attachSurface} 补上（绑定关系在，后台也不会被断）。
     */
    public static void bind(Context ctx, AirPlayProbe.Logger logger) {
        log = logger;
        if (mgr != null) {
            log("   （Car+ cast 服务已经绑着 → 直接等 Surface）");
            return;
        }
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(SVC, CLS));
            boolean ok = ctx.bindService(i, CONN, Context.BIND_AUTO_CREATE);
            log("   bindService(" + CLS + ") 返回 " + ok
                    + "（**要趁我们界面在前台时绑**，后台绑会返回 false）");
            if (!ok) {
                log("   !! 绑不上 —— 请**保持本 App 在前台**再点一次【Car+接屏】");
            }
        } catch (Throwable t) {
            log("!! bindService 异常: " + t);
        }
    }

    /** CarPlay 流起来之后，把**编码器输入 Surface** 交给 Car+（绑好的话立刻 prepareCast） */
    public static void attachSurface(Surface sf, int width, int height, int dpiValue,
                                     AirPlayProbe.Logger logger) {
        if (logger != null) {
            log = logger;
        }
        surface = sf;
        w = width;
        h = height;
        dpi = dpiValue;
        if (sf == null) {
            log("!! 编码器输入 Surface 还没准备好");
            return;
        }
        log("★ 编码器输入 Surface 已就绪（" + width + "x" + height + "），"
                + (mgr == null ? "但 Car+ cast 服务还没绑上" : "交给 Car+"));
        if (mgr == null) {
            log("   !! 服务没绑上（多半是绑的时候我们不在前台）——"
                    + " 请**回到本 App 界面**再点一次【Car+接屏】");
            return;
        }
        UI.post(new Runnable() {
            @Override
            public void run() {
                prepared = false;
                setCallback();     // 先确保回调登记过，再 prepareCast
                prepare();
            }
        });
    }

    private static void setCallback() {
        IBinder b = mgr;
        if (b == null || callbackSet) {
            return;
        }
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            d.writeInterfaceToken(MGR_DESC);
            d.writeStrongBinder(CALLBACK);
            boolean ok = b.transact(TX_SET_CALLBACK, d, r, 0);
            r.readException();
            callbackSet = true;
            log("★ setCastCallback（事务 4）返回 " + ok);
            d.recycle();
            r.recycle();
        } catch (Throwable t) {
            log("!! setCastCallback 失败: " + t);
        }
    }

    private static volatile boolean preparing;

    /**
     * `prepareCast`（**事务 1**）：把我们的编码器输入 Surface 交给 Car+。
     *
     * 参数就一种写法（反编译两边包确认）：
     *   `writeInterfaceToken` → `writeInt(1)`（CastConfig 非空）→
     *   `writeInt(width)` → `writeInt(height)` → `writeInt(dpi)` → `writeParcelable(surface, 0)`
     */
    private static void prepare() {
        IBinder b = mgr;
        if (b == null || surface == null || prepared || preparing) {
            return;
        }
        preparing = true;
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                d.writeInterfaceToken(MGR_DESC);
                d.writeInt(1);                     // CastConfig 非空
                d.writeInt(w);                     // width
                d.writeInt(h);                     // height
                d.writeInt(dpi);                   // dpi
                d.writeParcelable(surface, 0);     // surface
                boolean ok = b.transact(TX_PREPARE, d, r, 0);
                r.readException();
                log("★★ prepareCast（事务 1，" + w + "x" + h + " dpi=" + dpi
                        + "）成功，返回 " + ok + " —— 等 Car+ 回调 onCastPrepared / 出画面");
            } catch (Throwable t) {
                log("!! prepareCast（事务 1）失败: " + t);
            } finally {
                d.recycle();
                r.recycle();
            }
        } finally {
            preparing = false;
        }
    }

    /** 车机触摸 → Car+（`IRemoteTouchListener.onTouchEvent(MotionEvent)`，事务 1） */
    public static boolean sendTouch(int action, float x, float y) {
        IBinder b = touchBinder;
        if (b == null) {
            return false;
        }
        MotionEvent ev = null;
        try {
            long now = SystemClock.uptimeMillis();
            ev = MotionEvent.obtain(now, now, action, x, y, 0);
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            d.writeInterfaceToken(TOUCH_DESC);
            d.writeInt(1);                 // MotionEvent 非空
            d.writeParcelable(ev, 0);
            b.transact(1, d, r, 0);
            r.readException();
            d.recycle();
            r.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (ev != null) {
                ev.recycle();
            }
        }
    }

    /** 车机实体按键 → Car+（`IRemoteKeyListener.onKeyEvent(KeyEvent)`，事务 1） */
    public static boolean sendKey(int keyCode) {
        IBinder b = keyBinder;
        if (b == null) {
            return false;
        }
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            d.writeInterfaceToken(KEY_DESC);
            d.writeInt(1);                 // KeyEvent 非空
            new KeyEvent(KeyEvent.ACTION_DOWN, keyCode).writeToParcel(d, 0);
            b.transact(1, d, r, 0);
            r.readException();
            d.recycle();
            r.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收尾：stopCast（事务 2）+ 解绑 */
    public static void stop(Context ctx) {
        try {
            if (mgr != null && prepared) {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                d.writeInterfaceToken(MGR_DESC);
                mgr.transact(TX_STOP, d, r, 0);
                r.readException();
                d.recycle();
                r.recycle();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (ctx != null && mgr != null) {
                ctx.unbindService(CONN);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        mgr = null;
        prepared = false;
        callbackSet = false;
        touchBinder = null;
        keyBinder = null;
    }
}

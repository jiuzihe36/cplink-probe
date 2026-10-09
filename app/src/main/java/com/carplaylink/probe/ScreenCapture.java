package com.carplaylink.probe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

/**
 * 保存"屏幕录制"授权结果，供投屏用。
 *
 * 两个 Android 硬约束必须遵守：
 *  1) 一个授权令牌只能 getMediaProjection() 一次；用过的令牌再拿，拿到的投影会被系统立刻撤销
 *     → 用 consumed 标记，用过就重新申请。
 *  2) Android 14 起，拿投影之前必须已有 foregroundServiceType=mediaProjection 的前台服务在跑。
 */
public final class ScreenCapture {

    /** 屏幕录制授权的 requestCode（MainActivity 与投屏线程共用） */
    public static final int REQ_CAPTURE = 1001;

    private static final Object LOCK = new Object();

    public static volatile int resultCode;
    public static volatile Intent token;
    /** 该令牌是否已被 getMediaProjection 用掉 */
    public static volatile boolean consumed;
    /** 用户是否拒绝了 */
    public static volatile boolean denied;

    private static volatile MediaProjection projection;

    private ScreenCapture() {
    }

    public static boolean hasFreshToken() {
        return token != null && resultCode != 0 && !consumed;
    }

    /** 主线程拿到授权结果后调用 */
    public static void onResult(int code, Intent data) {
        resultCode = code;
        token = data;
        consumed = false;
        denied = (code == 0 || data == null);
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    /** 投屏线程等用户点授权框（最多 ms 毫秒） */
    public static boolean awaitResult(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        synchronized (LOCK) {
            while (!hasFreshToken() && !denied && System.currentTimeMillis() < deadline) {
                try {
                    LOCK.wait(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return hasFreshToken();
    }

    /** 弹系统授权框（必须在主线程调用） */
    public static void requestConsent(Activity activity) {
        try {
            // v3.4 修的坑：用户上一次点了"拒绝"之后 denied=true，awaitResult 会**立刻返回失败**，
            // 于是再点【授权录屏】根本不会等新弹框 —— 用户看到弹框、点了允许，程序却已经放弃了。
            // 每次重新申请都先把 denied 清掉。
            denied = false;
            MediaProjectionManager mgr = (MediaProjectionManager)
                    activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                return;
            }
            activity.startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable ignored) {
        }
    }

    /** 拿投影（一个令牌只能用一次；用过/没有则返回 null，调用方应重新申请） */
    public static synchronized MediaProjection projection(Context ctx) {
        if (projection != null) {
            return projection;
        }
        if (!hasFreshToken()) {
            return null;
        }
        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                    ctx.getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                return null;
            }
            MediaProjection p = mgr.getMediaProjection(resultCode, token);
            if (p != null) {
                consumed = true;
                projection = p;
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 投影被系统停掉后清空，下次重新授权 */
    public static synchronized void invalidate() {
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        projection = null;
        token = null;
        resultCode = 0;
        consumed = false;
    }
}

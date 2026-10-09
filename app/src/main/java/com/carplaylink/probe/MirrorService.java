package com.carplaylink.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 只为满足 Android 14 的要求：拿 MediaProjection 之前必须先有一个
 * foregroundServiceType=mediaProjection 的前台服务在跑。
 * 启动结果记在 lastStatus 里供日志显示（之前静默吞异常，排查时完全看不见）。
 */
public final class MirrorService extends Service {

    private static final String CHANNEL_ID = "cplink_mirror";

    /** 最近一次启动结果，供 UI 日志显示 */
    public static volatile String lastStatus = "还没启动过";

    /** 每次尝试启动前先置为"正在启动"，这样调用方才能判断这次是新结果还是上一轮的残留 */
    public static void beginStart() {
        lastStatus = "正在启动…";
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL_ID, "CPLink 投屏", NotificationManager.IMPORTANCE_LOW));
                }
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            builder.setContentTitle("CPLink 正在投屏")
                    .setContentText("手机屏幕正在镜像到车机")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true);
            startForeground(1001, builder.build());
            lastStatus = "前台服务已启动（type=mediaProjection）";
        } catch (Throwable t) {
            lastStatus = "前台服务启动失败: " + t;
        }
        return START_STICKY;
    }
}

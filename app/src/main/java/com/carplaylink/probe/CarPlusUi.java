package com.carplaylink.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 把 Car+ 自己的「车机界面」拉起来。
 *
 * 背景（v3.2 实测）：Car+ 的 cast 服务对第三方**一律不许绑定**（同包里三个 exported 服务
 * 分别返回 bindService=false / SecurityException: Not allowed to bind，而同机隔壁的
 * com.heytap.opluscarlink 却绑得上）—— 这是 ColorOS 的包级封锁，没 root/LSPosed 走不通。
 *
 * 所以改走"看得见"的那条路：Car+ 本身有一套**车机模式界面**（CarlifeActivity / 驾驶模式 /
 * CarModeActivity / carfusion 桌面…）。把它拉起来，再用 Android 14 的
 * **「单个应用」录屏**只录 Car+ 这一个应用 —— 录到的就是 Car+ 的车机界面，
 * 直接喂进已经跑通的 CarPlay 发送端上原车屏（v3.2 实测已推 800 帧）。
 *
 * 这里只挑 **exported 且不要权限** 的那些活动先试（带 OPPO_COMPONENT_SAFE 的放后面，
 * 拉不起来会抛异常，正好当"这条路被挡"的证据）。
 */
public final class CarPlusUi {

    private static final String PKG = "com.oplus.ocar";

    /** 按"最像车机界面"的顺序试 */
    private static final String[] ACTIVITIES = {
            "com.oplus.ocar.CarlifeActivity",
            "com.oplus.ocar.smartdrive.core.AutoEnterDriveModeActivity",
            "com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity",
            "com.oplus.ocar.smartdrive.shell.DriveModeActivity",
            "com.oplus.ocar.carmode.CarModeActivity",
            "com.oplus.ocar.settings.connect.WirelessConnectGuideActivity",
            "com.oplus.ocar.smartdrive.core.DriveModeSettingsActivity",
    };

    private CarPlusUi() {
    }

    /** 候选界面（给"逐个试、看哪个能录"用） */
    public static String[] activities() {
        return ACTIVITIES.clone();
    }

    /** 只拉指定的那一个界面（逐个试的时候用）；拉不起来返回 false */
    public static boolean launchOne(Context ctx, String activity, AirPlayProbe.Logger log) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(PKG, activity));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            log.log("   ✗ " + activity + " 拉不起来: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 返回成功拉起的活动名，全失败返回 null */
    public static String launch(Context ctx, AirPlayProbe.Logger log) {
        for (String a : ACTIVITIES) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(PKG, a));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(i);
                log.log("★ 已拉起 Car+ 界面: " + a);
                return a;
            } catch (Throwable t) {
                String msg = t.getMessage();
                log.log("   ✗ " + a + " 拉不起来: " + t.getClass().getSimpleName()
                        + (msg == null ? "" : " — " + msg));
            }
        }
        log.log("!! Car+ 的车机界面都没拉起来（多半被权限/组件安全挡）—— 请手动打开 Car+，"
                + "停在它的车机/驾驶界面");
        return null;
    }

    /** 给"授权录屏"用的提示：告诉用户该选哪一项 */
    public static String recordHint() {
        return "在弹框里选【整个屏幕】。注意：系统的\"单个应用\"列表只列**有桌面图标**的应用，"
                + "Car+ 是系统应用、没有桌面入口，所以那里选不到它 —— 这是系统的限制，不是我们的问题。"
                + "（投流开始后会自动**逐个**试 Car+ 的界面：能录的会留在屏幕上；"
                + "受保护窗口 FLAG_SECURE 会被系统掐掉录屏，那就说明它录不了）";
    }
}

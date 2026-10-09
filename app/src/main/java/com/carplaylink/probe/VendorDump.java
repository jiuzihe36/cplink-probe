package com.carplaylink.probe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 把"车联组件"的家底掏出来 —— 因为参考实现（iamr0s 的 carplay-xiaomi-carlife）证明：
 * 正确的做法不是手搓 CarLife 车机端协议，而是**当厂商车联 App 要找的那个组件**。
 *
 * 参考实现的做法（从它的 dex 里挖出来的）：
 *   1. 它把自己的包名注册成 `com.baidu.carlife.xiaomi`（厂商车联 App 认的"CarLife 组件"包名），
 *      并实现服务 `com.baidu.carlife.service.CarlifeConnectService`；
 *   2. 厂商车联 App（CarWith）在用户点连接时，会**用 Intent 启动这个服务**，带上连接契约：
 *      `usb_type` / `bindPackageName` / `bindClassName` / `manufacturer` / `bindVersion` / `is_box`；
 *   3. 它 accept 之后，**反过来 bindService 到厂商 App 那个组件**，再用裸 `IBinder.transact`
 *      调厂商内部的 AIDL（它已逆出事务号）拿视频格式和帧 —— 也就是**当车机端，但不伪造协议**；
 *   4. 拿到的帧直接转发进 CarPlay（我们这一半已经通了）。
 *
 * 所以我们要的是：**OPPO 这一侧同样的两个东西** ——
 *   (a) Car+ 认的组件包名/服务名与启动契约字段；
 *   (b) 厂商侧那个给视频的 AIDL（事务号 + Parcel 布局）。
 * 这两样都在 APK 里。本类负责把它们导出到手机上，好让它们能被逆向。
 */
public final class VendorDump {

    /** 车联相关包：OPPO Car+ 自己 + 它可能用的 CarLife/ICCOA 组件（含小米那份，参考实现用的就是它） */
    private static final String[] PKGS = {
            "com.oplus.ocar",
            "com.baidu.carlife.oppo",
            "com.baidu.carlife.xiaomi",
            "com.baidu.carlife",
            "com.heytap.opluscarlink",
            "com.oplus.linker",
    };

    /** 参考实现用到的包名（它自己就叫这个），单独标一下 */
    private static final String REF_PKG = "com.baidu.carlife.xiaomi";

    private VendorDump() {
    }

    public static void run(Context ctx, AirPlayProbe.Logger log) {
        log.log("══ 车联组件家底导出（v3.0）══");
        PackageManager pm = ctx.getPackageManager();
        logTools(ctx, log);

        File dir = new File(ctx.getExternalFilesDir(null), "dump");
        if (!dir.exists() && !dir.mkdirs()) {
            log.log("!! 建不了目录 " + dir);
        }

        for (String pkg : PKGS) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
                ApplicationInfo ai = pi.applicationInfo;
                log.log("");
                log.log("──── " + pkg + (pkg.equals(REF_PKG) ? "（参考实现占用的包名）" : "") + " ────");
                log.log("   版本 " + pi.versionName + "(" + pi.versionCode + ")"
                        + "  系统应用=" + ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                        + "  可停用=" + ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0));
                log.log("   APK: " + ai.sourceDir);
                if (ai.splitSourceDirs != null) {
                    for (String s : ai.splitSourceDirs) {
                        log.log("   split: " + s);
                    }
                }
                logServices(pi, log);
                logActivities(pi, log);
                logReceivers(pi, log);
                logProviders(pi, log);
                exportApk(ctx, pkg, ai.sourceDir, pi.versionName, dir, log);
            } catch (Throwable t) {
                log.log("   （没装或读不到: " + t + "）");
            }
        }
        log.log("");
        log.log("★ 导出目录（应用私有）: " + dir.getAbsolutePath());
        log.log("★ 若上面出现 /sdcard/Download/cplink_dump/…，直接从手机的「下载」里把 APK 发我即可");
    }

    // ---------------------------------------------------------------- 组件清单

    private static void logServices(PackageInfo pi, AirPlayProbe.Logger log) {
        if (pi.services == null) {
            return;
        }
        for (ServiceInfo s : pi.services) {
            StringBuilder sb = new StringBuilder("   服务 ").append(s.name)
                    .append(s.exported ? " [exported]" : " [内部]");
            if (s.permission != null) {
                sb.append(" perm=").append(s.permission);
            }
            sb.append(" ").append(filters(s));
            log.log(sb.toString());
        }
    }

    private static void logActivities(PackageInfo pi, AirPlayProbe.Logger log) {
        if (pi.activities == null) {
            return;
        }
        for (ActivityInfo a : pi.activities) {
            log.log("   活动 " + a.name + (a.exported ? " [exported]" : " [内部]")
                    + (a.permission != null ? " perm=" + a.permission : "") + " " + filters(a));
        }
    }

    private static void logReceivers(PackageInfo pi, AirPlayProbe.Logger log) {
        if (pi.receivers == null) {
            return;
        }
        for (ActivityInfo a : pi.receivers) {
            log.log("   广播 " + a.name + (a.exported ? " [exported]" : " [内部]") + " " + filters(a));
        }
    }

    private static void logProviders(PackageInfo pi, AirPlayProbe.Logger log) {
        if (pi.providers == null) {
            return;
        }
        for (ProviderInfo p : pi.providers) {
            log.log("   提供者 " + p.name + (p.exported ? " [exported]" : " [内部]")
                    + " authority=" + p.authority);
        }
    }

    /** 把 intent-filter 的 action 抽出来 —— 契约入口就藏在这里 */
    private static String filters(android.content.pm.ComponentInfo ci) {
        if (ci == null) {
            return "";
        }
        // ComponentInfo 没有直接暴露 filters 的公开 API，走 PackageManager 查询太重；
        // 这里用 metaData / 名字兜底，真正的 action 由下一轮用 APK 静态分析拿。
        return ci.metaData != null ? "meta=" + ci.metaData.keySet() : "";
    }

    // ---------------------------------------------------------------- 工具检测

    private static void logTools(Context ctx, AirPlayProbe.Logger log) {
        PackageManager pm = ctx.getPackageManager();
        log.log("   Shizuku(管理器): " + installed(pm, "moe.shizuku.manager"));
        log.log("   LSPosed(管理器): " + installed(pm, "org.lsposed.manager"));
        log.log("   Magisk: " + installed(pm, "com.topjohnwu.magisk"));
        log.log("   CarWith(小米车联): " + installed(pm, "com.xiaomi.ucar.carwith")
                + " / " + installed(pm, "com.miui.carlink"));
    }

    private static String installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return "已装";
        } catch (Throwable t) {
            return "未装";
        }
    }

    // ---------------------------------------------------------------- 导出 APK

    private static void exportApk(Context ctx, String pkg, String src, String ver,
                                  File dir, AirPlayProbe.Logger log) {
        if (src == null) {
            return;
        }
        File in = new File(src);
        if (!in.canRead()) {
            log.log("   !! 读不到 APK（" + src + "）—— 这个包只能靠别人给 APK 了");
            return;
        }
        String name = pkg + "_" + ver + ".apk";
        // 1) 应用私有目录（一定能写）
        try {
            File out = new File(dir, name);
            copy(in, new FileOutputStream(out));
            log.log("   ★ 已导出 " + out.getAbsolutePath() + "  " + out.length() + " 字节"
                    + "  sha256=" + sha256(out).substring(0, 16) + "…");
        } catch (Throwable t) {
            log.log("   !! 导出到私有目录失败: " + t);
        }
        // 2) 公共「下载」目录（Android 10+ 走 MediaStore，不需要权限；用户最容易拿到）
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/cplink_dump");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream os = cr.openOutputStream(uri);
                    copy(in, os);
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    log.log("   ★ 已放进「下载/cplink_dump/" + name + "」");
                }
            } catch (Throwable t) {
                log.log("   （写公共下载目录失败，用私有目录那份即可: " + t + "）");
            }
        }
    }

    private static void copy(File in, OutputStream os) throws Exception {
        InputStream is = new java.io.FileInputStream(in);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream is = new java.io.FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            is.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 供 UI 用：目标包名列表 */
    public static List<String> packages() {
        return new ArrayList<>(java.util.Arrays.asList(PKGS));
    }
}

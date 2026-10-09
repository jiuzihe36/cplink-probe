package com.carplaylink.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 投屏内容源 = OPPO 的 Car+ 车联界面（而不是手机桌面）。
 *
 * 两条实测教训：
 *  1) **不能只按包名找**：OPPO 车联的包名是 `com.heytap.opluscarlink`（名字里没有 car），
 *     只匹配 "car" 会整个漏掉；要按「应用显示名」+「包名关键字」双路匹配。
 *  2) **回退逻辑必须排除自己**：否则第一个"有启动入口的候选"就是我们自己，
 *     表现就是点【启动Car+】毫无反应（只是把自己又切到前台）。
 *
 * 另外配合 Android 14 的"只共享单个应用"：在屏幕录制授权框里选车联应用，
 * 系统就只把那一个应用的画面给投影，车机上不会看到桌面和通知。
 */
public final class CarAppLauncher {

    /** 已知/可能的 Car+ 车联包名，按优先级排列（com.oplus.ocar 的显示名实测就是「car+ 车联」） */
    private static final String[] PREFERRED = {
            "com.oplus.ocar",
            "com.baidu.carlife.oppo",
            "com.heytap.opluscarlink",
            "com.oplus.carhome",
            "com.heytap.carhome",
            "com.coloros.carhome",
            "com.oppo.carhome",
            "com.oplus.autolink",
            "com.oplus.icar",
            "com.oplus.carlife",
            "com.heytap.auto",
            "com.baidu.carlife",
    };

    /** 应用名里出现这些词，基本就是车联/车机类 */
    private static final String[] LABEL_HINTS = {
            "车联", "car+", "car +", "车机", "驾驶", "智慧车载", "carlife", "carhome",
    };

    private CarAppLauncher() {
    }

    /** 我们自己 + 系统组件，绝不能当"车联应用"启动 */
    private static boolean launchable(String pkg) {
        if (pkg == null) {
            return false;
        }
        if ("com.carplaylink.probe".equals(pkg)) {
            return false;
        }
        return !pkg.startsWith("com.android.") && !pkg.startsWith("android.");
    }

    /** 找 + 启动；返回启动成功的包名，没找到返回 null */
    public static String launch(Context ctx, AirPlayProbe.Logger log) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<String> byPackage = new ArrayList<>();
            List<String> byLabel = new ArrayList<>();
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    String pkg = pi.packageName;
                    if (!launchable(pkg)) {
                        continue;
                    }
                    String lower = pkg.toLowerCase(Locale.US);
                    if (lower.contains("car") || lower.contains("auto")
                            || lower.contains("drive") || lower.contains("link")) {
                        byPackage.add(pkg);
                    }
                    String label = "";
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        label = pm.getApplicationLabel(ai).toString().toLowerCase(Locale.US);
                    } catch (Throwable ignored) {
                    }
                    for (String hint : LABEL_HINTS) {
                        if (label.contains(hint)) {
                            byLabel.add(pkg + " [" + label + "]");
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                log.log("   （枚举应用失败，可能缺 QUERY_ALL_PACKAGES: " + t + "）");
            }
            log.log("   名字像车联的应用: " + (byLabel.isEmpty() ? "无" : byLabel));
            log.log("   包名像车机的应用: " + (byPackage.isEmpty() ? "无" : byPackage));

            for (String pkg : PREFERRED) {
                if (start(ctx, pm, pkg, log)) {
                    return pkg;
                }
            }
            for (String entry : byLabel) {
                int cut = entry.indexOf(" [");
                String pkg = cut > 0 ? entry.substring(0, cut) : entry;
                if (start(ctx, pm, pkg, log)) {
                    return pkg;
                }
            }
            // 注意：这里**故意不再按包名兜底**。之前那样做，最后会启动第一个"有启动入口"的包，
            // 结果把高德地图拉起来了 —— 完全不是车联应用。宁可什么都不启动。
            log.log("!! 没找到可启动的车联应用 —— 请手动打开 Car+ 车联，"
                    + "并在录屏授权框里选【只共享它】。上面两行是手机上的实际清单，发我可对症调整。");
            return null;
        } catch (Throwable t) {
            log.log("!! 启动车联应用失败: " + t);
            return null;
        }
    }

    private static boolean start(Context ctx, PackageManager pm, String pkg, AirPlayProbe.Logger log) {
        if (!launchable(pkg)) {
            return false;
        }
        Intent intent;
        try {
            intent = pm.getLaunchIntentForPackage(pkg);
        } catch (Throwable t) {
            log.log("   " + pkg + " 取启动入口异常: " + t);
            return false;
        }
        if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(intent);
                log.log("★ 已把投屏内容切到车联应用: " + describe(pm, pkg));
                return true;
            } catch (Throwable t) {
                log.log("   " + pkg + " 启动失败: " + t);
            }
        }
        // 没有桌面启动入口的系统车联（例如 com.oplus.ocar「car+ 车联」）：
        // 直接按 Activity 组件拉起，优先名字像主页/入口的那些。
        return startByActivity(ctx, pm, pkg, log);
    }

    /** 按 Activity 组件直接拉起（用于没有 LAUNCHER 入口的系统应用） */
    private static boolean startByActivity(Context ctx, PackageManager pm, String pkg, AirPlayProbe.Logger log) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.activities == null || pi.activities.length == 0) {
                return false;
            }
            String[] preferredNames = {"main", "launcher", "home", "entry", "splash", "start",
                    "connect", "link", "wireless", "search", "device", "guide", "carlife", "welcome"};
            // 先试名字像入口的，再逐个试其余 exported 的
            for (int pass = 0; pass < 2; pass++) {
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    if (ai == null || ai.name == null || !ai.exported) {
                        continue;
                    }
                    String lower = ai.name.toLowerCase(Locale.US);
                    boolean looksLikeEntry = false;
                    for (String hint : preferredNames) {
                        if (lower.contains(hint)) {
                            looksLikeEntry = true;
                            break;
                        }
                    }
                    if ((pass == 0) != looksLikeEntry) {
                        continue;
                    }
                    try {
                        Intent i = new Intent(Intent.ACTION_MAIN);
                        i.setComponent(new android.content.ComponentName(pkg, ai.name));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                        log.log("★ 已把投屏内容切到车联应用: " + describe(pm, pkg) + " 活动 " + ai.name);
                        return true;
                    } catch (Throwable ignored) {
                        // 这个活动不让外部拉起，继续试下一个
                    }
                }
            }
        } catch (Throwable t) {
            log.log("   " + pkg + " 列活动失败: " + t);
        }
        return false;
    }

    // ------------------------------------------- CarLife+ 手机端组件（Car+ 的上游）

    /** CarLife 的手机端组件包名 —— Car+ 把 CarLife 交给它们，它们的界面才是"等待连接车机" */
    private static final String[] CARLIFE_PKGS = {
            "com.baidu.carlife.oppo",
            "com.baidu.carlife",
            "com.baidu.carlifeauto",
            "com.oplus.ocar",
            "com.heytap.opluscarlink",
    };

    /**
     * 直接把 CarLife 组件拉起来，并把它所有 Activity/Service 名字打进日志。
     *
     * 为什么需要：实测 OPPO Car+ 在"等待连接"状态下**根本没开**那 7 个 CarLife 端口，
     * 说明真正承担 CarLife 的可能是上游组件（`com.baidu.carlife.oppo`）。这类组件没有桌面图标，
     * 只能按组件名拉起；拉不起来时，日志里那串组件名就是下一轮要试的靶子。
     */
    public static String launchCarLife(Context ctx, AirPlayProbe.Logger log) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : CARLIFE_PKGS) {
            dumpComponents(pm, pkg, log);
        }
        for (String pkg : CARLIFE_PKGS) {
            if (start(ctx, pm, pkg, log)) {
                return pkg;
            }
        }
        log.log("!! 手机上没找到 CarLife+ 组件（可能只有 Car+ 自己的实现）。"
                + "请手动打开 Car+ → 连接设置 → 投屏协议选【百度 CarLife+】→ 无线 → 停在等待连接界面。");
        return null;
    }

    private static void dumpComponents(PackageManager pm, String pkg, AirPlayProbe.Logger log) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
            StringBuilder sb = new StringBuilder("   " + pkg + " 已安装");
            if (pi.activities != null && pi.activities.length > 0) {
                sb.append("\n      活动: ");
                for (ActivityInfo ai : pi.activities) {
                    sb.append(ai.name).append(ai.exported ? "" : "(内部)").append("  ");
                }
            }
            if (pi.services != null && pi.services.length > 0) {
                sb.append("\n      服务: ");
                for (ServiceInfo si : pi.services) {
                    sb.append(si.name).append("  ");
                }
            }
            log.log(sb.toString());
        } catch (Throwable ignored) {
            // 这个包没装，正常
        }
    }

    private static String describe(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString() + " (" + pkg + ")";
        } catch (Throwable ignored) {
            return pkg;
        }
    }
}

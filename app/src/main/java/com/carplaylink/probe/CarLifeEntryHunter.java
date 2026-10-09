package com.carplaylink.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把「手机侧 CarLife 的等待连接界面」点出来 —— 让 7240 那 7 个端口真的开起来。
 *
 * v3.4 的变化（两处都是被 v3.3 的假日志逼出来的）：
 *
 * 1) **命中判据不再可能自欺**：以前诊断流程会自动反向监听这 7 个端口，于是"端口在听"永远为真。
 *    现在所有端口检查都走 {@link LocalNetDiag#anyCarLifeOpen(int)}，它跳过本机自占端口
 *    （{@link SelfPorts}），所以 connect 成功 = 真有别人在听。
 *
 * 2) **已知入口排最前**：v3.3 的日志里已经实测出两个真正管用的入口，不再靠名字打分去撞：
 *      · `com.oplus.ocar` / `com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity`
 *        —— Car+ 的「找到车机配件」页，拉起后 127.0.0.1:7240 被打开过（实测）。
 *      · `com.baidu.carlife.oppo`（智能车载百度 CarLife+ 组件，8.6.8）的主界面
 *        —— 它就是百度 CarLife 手机端，**它才是监听 7240 的那个**（原版就是手机端监听、车机连过来）。
 */
public final class CarLifeEntryHunter {

    /** 实测/推断最可能是"等待车机连接"的入口，按顺序先试 */
    private static final String[][] KNOWN = {
            {"com.oplus.ocar", "com.oplus.ocar.connect.carlife.CarlifeAccessoryFoundActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.carlife.CarlifeActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.che.codriver.ui.MainActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.carlife.core.base.activity.PermissionActivity"},
            {"com.baidu.carlife.oppo", "com.baidu.carlife.mix.oppo.OppoPermissionActivity"},
            {"com.oplus.ocar", "com.oplus.ocar.CarlifeActivity"},
    };

    /** 兜底爆破的候选包：Car+ 自己 + 它的 CarLife 上游组件 */
    private static final String[] PKGS = {
            "com.oplus.ocar",
            "com.baidu.carlife.oppo",
            "com.baidu.carlife",
            "com.heytap.opluscarlink",
    };

    private static final String[] GOOD = {"connect", "link", "carlife", "wireless", "device",
            "search", "main", "home", "entry", "launcher", "welcome", "guide", "start",
            "accessory", "found"};
    private static final String[] BAD = {"download", "privacy", "agreement",
            "webview", "browser", "about", "debug", "crash", "feedback", "update",
            "login", "account", "share", "ocr", "camera", "realname", "forget", "register"};

    private CarLifeEntryHunter() {
    }

    /**
     * @param perTryMs 拉起一个活动后等多久再查端口
     * @param maxTries 最多试几个活动（防跑飞）
     * @return 命中的组件名 "包/活动"，没命中返回 null
     */
    public static String hunt(Context ctx, AirPlayProbe.Logger log, int perTryMs, int maxTries) {
        PackageManager pm = ctx.getPackageManager();
        List<String[]> cands = new ArrayList<>();

        // 1) 已知入口排最前（分数给满，顺序即优先级）
        int prio = 10000;
        for (String[] k : KNOWN) {
            if (installed(pm, k[0]) && exported(pm, k[0], k[1])) {
                cands.add(new String[]{k[0], k[1], String.valueOf(prio--)});
            }
        }
        if (!cands.isEmpty()) {
            log.log("★ 已知入口 " + cands.size() + " 个（先试这些）：");
            for (String[] c : cands) {
                log.log("     " + c[0] + "/" + c[1]);
            }
        }

        // 2) 兜底：按名字打分爆破
        List<String[]> rest = new ArrayList<>();
        for (String pkg : PKGS) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                if (pi.activities == null) {
                    continue;
                }
                int exp = 0;
                for (ActivityInfo ai : pi.activities) {
                    if (ai == null || ai.name == null || !ai.exported) {
                        continue;
                    }
                    exp++;
                    if (contains(cands, pkg, ai.name)) {
                        continue;
                    }
                    rest.add(new String[]{pkg, ai.name, String.valueOf(score(ai.name))});
                }
                log.log("   " + pkg + " 另有 " + exp + " 个可拉起活动（兜底爆破用）");
            } catch (Throwable ignored) {
                // 这个包没装
            }
        }
        Collections.sort(rest, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Integer.compare(Integer.parseInt(b[2]), Integer.parseInt(a[2]));
            }
        });
        cands.addAll(rest);

        if (cands.isEmpty()) {
            log.log("!! 没有可拉起的候选活动 —— 请把日志发我（Car+ 可能不在上面这几个包里）");
            return null;
        }
        log.log("★ 共 " + cands.size() + " 个候选，逐个拉起并查 7 个 CarLife 端口"
                + "（本机自占端口已排除，命中 = 真有别人在听）…");

        int tried = 0;
        for (String[] c : cands) {
            if (tried++ >= maxTries) {
                log.log("   已达尝试上限 " + maxTries + " 个，停止");
                break;
            }
            String comp = c[0] + "/" + c[1];
            if (!launch(ctx, c[0], c[1])) {
                log.log("   ✗ " + comp + "（拉不起来）");
                continue;
            }
            log.log("   → 已拉起 " + comp + "，等 " + (perTryMs / 1000.0) + " 秒看端口…");
            sleep(perTryMs);
            String hit = LocalNetDiag.anyCarLifeOpen(600);
            if (hit != null) {
                log.log("★★ 命中！拉起 " + comp + " 之后，真的有 CarLife 端口在听：" + hit);
                log.log("   停在这个界面别退出，然后点【③ 当车机·接Car+】接住握手");
                return comp;
            }
            log.log("   ✗ " + comp + "（端口仍未开）");
        }
        log.log("!! 试遍候选都没让那 7 个端口打开 —— 把这段日志发我。");
        return null;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean exported(PackageManager pm, String pkg, String act) {
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, act), 0);
            return ai != null && ai.exported;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean contains(List<String[]> list, String pkg, String act) {
        for (String[] c : list) {
            if (c[0].equals(pkg) && c[1].equals(act)) {
                return true;
            }
        }
        return false;
    }

    private static int score(String name) {
        String s = name.toLowerCase(Locale.US);
        int v = 0;
        for (String g : GOOD) {
            if (s.contains(g)) {
                v += 10;
            }
        }
        for (String b : BAD) {
            if (s.contains(b)) {
                v -= 25;
            }
        }
        return v;
    }

    private static boolean launch(Context ctx, String pkg, String activity) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(pkg, activity));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}

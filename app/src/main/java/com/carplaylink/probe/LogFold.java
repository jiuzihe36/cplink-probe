package com.carplaylink.probe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把**完全相同**的日志行折叠掉：前 3 条原样打，之后每 50 条打一句汇总。
 *
 * 为什么必须做：v3.3 的日志里 "!! 发送失败: Broken pipe" 和 "★★ 有连接进来！" 各刷了几百行，
 * 把真正有用的信息（谁连了谁、收到什么）埋掉了 —— 用户贴过来的 9 万字日志里 90% 是这两条。
 */
public final class LogFold {

    private static final int SHOW_FIRST = 3;
    private static final int EVERY = 50;

    private final AirPlayProbe.Logger log;
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public LogFold(AirPlayProbe.Logger log) {
        this.log = log;
    }

    public synchronized void log(String line) {
        Integer c = counts.get(line);
        int n = (c == null ? 0 : c) + 1;
        counts.put(line, n);
        if (n <= SHOW_FIRST) {
            log.log(line);
        } else if (n % EVERY == 0) {
            log.log("   （上面那条日志已重复 " + n + " 次，中间已折叠）");
        }
    }
}

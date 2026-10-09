package com.carplaylink.probe;

import java.util.Map;
import java.util.UUID;

/**
 * 本机（控制器/手机）的长效配对身份。
 * pair-setup 里生成，pair-verify 里要用同一把 Ed25519 长效密钥（LTPK）。
 * 现在放在内存里；跨进程持久化留给后续版本。
 */
public final class Pairing {

    public static final String SETUP_USERNAME = "Pair-Setup";
    /** AirPlay pair-setup 的固定 PIN —— DiPlay 接收端写死 3939 */
    public static final String SETUP_CODE = "3939";

    public static volatile String controllerId;
    public static volatile byte[] controllerLtpkPriv;
    public static volatile byte[] controllerLtpkPub;
    /** 接收端（车机）的身份，pair-setup M6 里拿到 */
    public static volatile String accessoryId;
    public static volatile byte[] accessoryLtpkPub;
    /** pair-verify 的 X25519 共享密钥：视频/音频数据流的密钥都从它派生 */
    public static volatile byte[] sharedSecret;
    /** pair-verify 派生出来的控制通道密钥（event 通道用的是同一套 ChaCha20-Poly1305 分帧） */
    public static volatile byte[] controlReadKey;
    public static volatile byte[] controlWriteKey;

    private Pairing() {
    }

    public static String newControllerId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "CPLINK-" + suffix;
    }

    public static void rememberController(byte[] priv, byte[] pub) {
        controllerLtpkPriv = priv;
        controllerLtpkPub = pub;
    }

    public static void rememberAccessory(String id, byte[] ltpkPub) {
        accessoryId = id;
        accessoryLtpkPub = ltpkPub;
    }

    /** 调试用：把 TLV 打成可读字符串 */
    public static String describeTlv(Map<Integer, byte[]> tlv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, byte[]> e : tlv.entrySet()) {
            sb.append(" tlv[0x").append(String.format("%02x", e.getKey())).append("]=");
            byte[] v = e.getValue();
            if (v.length <= 32) {
                sb.append(Crypto.hex(v));
            } else {
                sb.append(v.length).append("B(").append(Crypto.hex(v, 16)).append("…)");
            }
        }
        return sb.toString();
    }
}

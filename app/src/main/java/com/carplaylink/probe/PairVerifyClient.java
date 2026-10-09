package com.carplaylink.probe;

import java.util.Map;

/**
 * pair-verify 客户端（M1→M4）—— 与 DiPlay 的 PairVerify（应答端）对应。
 *
 * M1 {state=1, publicKey=我方临时 X25519 公钥}                    ← 明文
 * M2 {state=2, publicKey=车机临时公钥, encryptedData=…}            ← 明文
 * M3 {state=3, encryptedData=ChaCha(PV-Msg03, {id, sig})}          ← **明文**
 * M4 {state=4}                                                    ← **明文**
 * 之后控制通道整体加密（收发密钥由 shared 派生）。
 *
 * 签名内容（车机侧）：Ed25519(车机 LTPK, 车机临时公钥 ‖ 车机 id ‖ 我方临时公钥)
 * 我方签名：         Ed25519(我方 LTPK,   我方临时公钥 ‖ 我方 id ‖ 车机临时公钥)
 */
public final class PairVerifyClient {

    private static final String PATH = "/pair-verify";
    private static final String CT = "application/pairing+tlv8";

    private PairVerifyClient() {
    }

    public static boolean run(RtspClient rtsp, AirPlayProbe.Logger log) {
        try {
            if (Pairing.controllerLtpkPriv == null || Pairing.controllerId == null) {
                log.log("!! pair-verify 需要先跑通 pair-setup（我方长效密钥缺失）");
                return false;
            }
            log.log("—— pair-verify 开始（临时 X25519 + Ed25519 双向签名）——");
            Crypto.XPair eph = Crypto.x25519Generate();

            // ---- M1 → M2（明文）
            byte[] m1 = Tlv8.encodeItems(Tlv8.TYPE_STATE, Tlv8.one(1), Tlv8.TYPE_PUBLIC_KEY, eph.pub);
            RtspClient.Response r = rtsp.request("POST", PATH, m1, CT);
            log.log("<< /pair-verify M2 状态 " + r.status + "  " + r.statusLine);
            Map<Integer, byte[]> t = Tlv8.decode(r.body);
            if (Tlv8.stateOf(t) != 2) {
                log.log("!! 期望 state=2，实际 " + Tlv8.stateOf(t) + "  " + Pairing.describeTlv(t));
                return false;
            }
            byte[] accEph = t.get(Tlv8.TYPE_PUBLIC_KEY);
            byte[] sealed = t.get(Tlv8.TYPE_ENCRYPTED_DATA);
            if (accEph == null || sealed == null) {
                log.log("!! M2 缺公钥或密文  " + Pairing.describeTlv(t));
                return false;
            }

            byte[] shared = Crypto.x25519Shared(eph.priv, accEph);
            byte[] encKey = Crypto.hkdfSha512(shared,
                    Crypto.ascii("Pair-Verify-Encrypt-Salt"), Crypto.ascii("Pair-Verify-Encrypt-Info"), 32);
            byte[] sub = Crypto.chachaOpen(encKey, Crypto.nonceLabel("PV-Msg02"), sealed);
            Map<Integer, byte[]> acc = Tlv8.decode(sub);
            byte[] accIdBytes = acc.get(Tlv8.TYPE_IDENTIFIER);
            byte[] accSig = acc.get(Tlv8.TYPE_SIGNATURE);
            String accId = accIdBytes == null ? "?"
                    : new String(accIdBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.log("   车机 id=" + accId + "  签名=" + (accSig == null ? "无" : accSig.length + " 字节"));

            boolean accSigOk = false;
            if (accSig != null && accIdBytes != null && Pairing.accessoryLtpkPub != null) {
                accSigOk = Crypto.ed25519Verify(Pairing.accessoryLtpkPub,
                        Crypto.concat(accEph, accIdBytes, eph.pub), accSig);
            }
            log.log("   车机签名校验（用 pair-setup 拿到的 LTPK）: "
                    + (Pairing.accessoryLtpkPub == null ? "无 LTPK，跳过" : (accSigOk ? "通过 ★" : "不通过")));

            // ---- M3 → M4（依然明文！DiPlay 是写完 M4 才打开加密）
            byte[] myId = Crypto.utf8(Pairing.controllerId);
            byte[] mySig = Crypto.ed25519Sign(Pairing.controllerLtpkPriv,
                    Crypto.concat(eph.pub, myId, accEph));
            byte[] sub3 = Tlv8.encodeItems(
                    Tlv8.TYPE_IDENTIFIER, myId,
                    Tlv8.TYPE_SIGNATURE, mySig);
            byte[] sealed3 = Crypto.chachaSeal(encKey, Crypto.nonceLabel("PV-Msg03"), sub3);
            byte[] m3 = Tlv8.encodeItems(Tlv8.TYPE_STATE, Tlv8.one(3), Tlv8.TYPE_ENCRYPTED_DATA, sealed3);

            r = rtsp.request("POST", PATH, m3, CT);
            log.log("<< /pair-verify M4 状态 " + r.status + "  " + r.statusLine);
            t = Tlv8.decode(r.body);
            int st = Tlv8.stateOf(t);
            if (st != 4) {
                log.log("!! 期望 state=4，实际 " + st + "  " + Pairing.describeTlv(t));
                return false;
            }

            // ---- 派生控制通道密钥并打开加密
            Pairing.sharedSecret = shared;
            byte[] readKey = Crypto.hkdfSha512(shared,
                    Crypto.ascii("Control-Salt"), Crypto.ascii("Control-Read-Encryption-Key"), 32);
            byte[] writeKey = Crypto.hkdfSha512(shared,
                    Crypto.ascii("Control-Salt"), Crypto.ascii("Control-Write-Encryption-Key"), 32);
            rtsp.enableEncryption(readKey, writeKey);
            Pairing.controlReadKey = readKey;
            Pairing.controlWriteKey = writeKey;
            log.log("★ pair-verify 通过：控制通道已加密（读密钥 " + Crypto.hex(readKey, 8) + "… / 写密钥 "
                    + Crypto.hex(writeKey, 8) + "…）");
            return true;
        } catch (Throwable e) {
            log.log("!! pair-verify 异常: " + e);
            return false;
        }
    }
}

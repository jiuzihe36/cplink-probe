package com.carplaylink.probe;

import java.util.Map;

/**
 * pair-setup 客户端（M1→M6）：与 DiPlay 的 PairSetup 应答端对应。
 *
 * M1 {state=1} → M2 {state=2, salt, B}
 * M3 {state=3, publicKey=A, proof=M1} → M4 {state=4, proof=M2}
 * M5 {state=5, encryptedData=ChaCha(K, PS-Msg05, {id, LTPK, sig})}
 *    → M6 {state=6, encryptedData=ChaCha(K, PS-Msg06, {id, LTPK, sig})}
 *
 * 全程明文 RTSP（pair-verify 之后通道才会被加密），所以这一步不需要控制通道密钥。
 */
public final class PairSetupClient {

    private static final String PATH = "/pair-setup";
    private static final String CT = "application/pairing+tlv8";

    private PairSetupClient() {
    }

    public static boolean run(RtspClient rtsp, AirPlayProbe.Logger log) {
        try {
            String ourId = Pairing.newControllerId();
            Srp6a.Client srp = Srp6a.start(Pairing.SETUP_USERNAME, Pairing.SETUP_CODE);
            log.log("—— pair-setup 开始（PIN " + Pairing.SETUP_CODE + "，我方 id=" + ourId + "）——");

            // ---- M1 → M2
            byte[] m1 = Tlv8.encodeItems(Tlv8.TYPE_STATE, Tlv8.one(1), Tlv8.TYPE_METHOD, Tlv8.one(0));
            RtspClient.Response r = rtsp.request("POST", PATH, m1, CT);
            log.log("<< /pair-setup M2 状态 " + r.status + "  " + r.statusLine);
            Map<Integer, byte[]> t = Tlv8.decode(r.body);
            int st = Tlv8.stateOf(t);
            if (st != 2) {
                log.log("!! 期望 state=2，实际 " + st + describe(t));
                return false;
            }
            byte[] salt = t.get(Tlv8.TYPE_SALT);
            byte[] bPub = t.get(Tlv8.TYPE_PUBLIC_KEY);
            if (salt == null || bPub == null) {
                log.log("!! M2 缺 salt 或 B" + describe(t));
                return false;
            }
            log.log("   salt=" + Crypto.hex(salt) + "  B=" + bPub.length + " 字节");

            // ---- M3 → M4
            byte[] proof = srp.proofForServer(salt, bPub);
            byte[] m3 = Tlv8.encodeItems(
                    Tlv8.TYPE_STATE, Tlv8.one(3),
                    Tlv8.TYPE_PUBLIC_KEY, srp.publicKeyA,
                    Tlv8.TYPE_PROOF, proof);
            r = rtsp.request("POST", PATH, m3, CT);
            log.log("<< /pair-setup M4 状态 " + r.status + "  " + r.statusLine);
            t = Tlv8.decode(r.body);
            st = Tlv8.stateOf(t);
            if (st != 4) {
                log.log("!! 期望 state=4，实际 " + st + describe(t));
                return false;
            }
            byte[] serverProof = t.get(Tlv8.TYPE_PROOF);
            boolean proofOk = srp.verifyServerProof(serverProof);
            log.log("   服务端 proof 校验: " + (proofOk ? "通过 ★" : "不通过（继续跑，只为拿后续数据）"));
            byte[] sessionKey = srp.sessionKey();
            log.log("   SRP 会话密钥已算出 " + sessionKey.length + " 字节");

            // ---- M5 → M6
            byte[] encKey = Crypto.hkdfSha512(sessionKey,
                    Crypto.ascii("Pair-Setup-Encrypt-Salt"), Crypto.ascii("Pair-Setup-Encrypt-Info"), 32);
            byte[] signKey = Crypto.hkdfSha512(sessionKey,
                    Crypto.ascii("Pair-Setup-Controller-Sign-Salt"),
                    Crypto.ascii("Pair-Setup-Controller-Sign-Info"), 32);

            Crypto.EdPair ed = Crypto.ed25519Generate();
            byte[] id = Crypto.utf8(ourId);
            byte[] sig = Crypto.ed25519Sign(ed.priv, Crypto.concat(signKey, id, ed.pub));
            byte[] sub = Tlv8.encodeItems(
                    Tlv8.TYPE_IDENTIFIER, id,
                    Tlv8.TYPE_PUBLIC_KEY, ed.pub,
                    Tlv8.TYPE_SIGNATURE, sig);
            byte[] sealed = Crypto.chachaSeal(encKey, Crypto.nonceLabel("PS-Msg05"), sub);
            byte[] m5 = Tlv8.encodeItems(Tlv8.TYPE_STATE, Tlv8.one(5), Tlv8.TYPE_ENCRYPTED_DATA, sealed);

            r = rtsp.request("POST", PATH, m5, CT);
            log.log("<< /pair-setup M6 状态 " + r.status + "  " + r.statusLine);
            t = Tlv8.decode(r.body);
            st = Tlv8.stateOf(t);
            if (st != 6) {
                log.log("!! 期望 state=6，实际 " + st + describe(t));
                return false;
            }
            byte[] sealedBack = t.get(Tlv8.TYPE_ENCRYPTED_DATA);
            if (sealedBack == null) {
                log.log("!! M6 没有 encryptedData" + describe(t));
                return false;
            }
            byte[] plain = Crypto.chachaOpen(encKey, Crypto.nonceLabel("PS-Msg06"), sealedBack);
            Map<Integer, byte[]> acc = Tlv8.decode(plain);
            String accId = acc.get(Tlv8.TYPE_IDENTIFIER) == null
                    ? "?" : new String(acc.get(Tlv8.TYPE_IDENTIFIER), java.nio.charset.StandardCharsets.UTF_8);
            byte[] accLtpk = acc.get(Tlv8.TYPE_PUBLIC_KEY);
            byte[] accSig = acc.get(Tlv8.TYPE_SIGNATURE);
            log.log("★ pair-setup 完成：车机 id=" + accId
                    + "  LTPK=" + (accLtpk == null ? "无" : accLtpk.length + " 字节")
                    + "  签名=" + (accSig == null ? "无" : accSig.length + " 字节"));

            Pairing.rememberController(ed.priv, ed.pub);
            Pairing.controllerId = ourId;
            Pairing.rememberAccessory(accId, accLtpk);
            return true;
        } catch (Throwable e) {
            log.log("!! pair-setup 异常: " + e);
            return false;
        }
    }

    private static String describe(Map<Integer, byte[]> t) {
        return "  " + Pairing.describeTlv(t);
    }
}

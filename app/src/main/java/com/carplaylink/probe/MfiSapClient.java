package com.carplaylink.probe;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

/**
 * /auth-setup（MFi-SAP）客户端。
 *
 * 请求体是裸二进制（不是 TLV、不是 plist）：[1 字节版本=1][32 字节我方临时 X25519 公钥]
 * 响应：[32 字节车机临时公钥][u32 证书长度][证书][u32 签名长度][AES-128-CTR 加密的签名]
 *   aesKey = SHA1("AES-KEY" ‖ shared)[:16]，aesIv = SHA1("AES-IV" ‖ shared)[:16]
 *
 * 这一步跑在**已加密**的控制通道上，所以只要收到并能正确解开响应，
 * 就说明 pair-verify 派生的控制通道密钥完全正确（ChaCha20-Poly1305 的 tag 校验不会骗人）。
 */
public final class MfiSapClient {

    private static final String PATH = "/auth-setup";
    private static final String CT = "application/octet-stream";

    private MfiSapClient() {
    }

    public static boolean run(RtspClient rtsp, AirPlayProbe.Logger log) {
        try {
            Crypto.XPair kp = Crypto.x25519Generate();
            byte[] body = new byte[33];
            body[0] = 0x01;
            System.arraycopy(kp.pub, 0, body, 1, 32);

            log.log("—— /auth-setup（MFi-SAP，走加密通道）——");
            RtspClient.Response r = rtsp.request("POST", PATH, body, CT);
            log.log("<< /auth-setup " + r.statusLine + "  (" + r.body.length + " 字节)");
            if (r.status != 200) {
                log.log("!! /auth-setup 状态不是 200");
                return false;
            }
            if (r.body.length < 36) {
                log.log("!! 响应太短（" + r.body.length + " 字节）");
                return false;
            }
            byte[] accPub = Arrays.copyOfRange(r.body, 0, 32);
            int certLen = readU32(r.body, 32);
            if (certLen < 0 || 36 + certLen + 4 > r.body.length) {
                log.log("!! 证书长度越界: " + certLen);
                return false;
            }
            byte[] cert = Arrays.copyOfRange(r.body, 36, 36 + certLen);
            int sigLen = readU32(r.body, 36 + certLen);
            if (sigLen < 0 || 40 + certLen + sigLen > r.body.length) {
                log.log("!! 签名长度越界: " + sigLen);
                return false;
            }
            byte[] sig = Arrays.copyOfRange(r.body, 40 + certLen, 40 + certLen + sigLen);
            log.log("   车机临时公钥 " + Crypto.hex(accPub, 8) + "…  证书 " + certLen
                    + " 字节  加密签名 " + sigLen + " 字节");

            byte[] shared = Crypto.x25519Shared(kp.priv, accPub);
            byte[] aesKey = Arrays.copyOf(Crypto.sha1(Crypto.ascii("AES-KEY"), shared), 16);
            byte[] aesIv = Arrays.copyOf(Crypto.sha1(Crypto.ascii("AES-IV"), shared), 16);
            byte[] plainSig = aesCtr128(aesKey, aesIv, sig);
            log.log("   AES-128-CTR 解开签名: " + plainSig.length + " 字节  "
                    + Crypto.hex(plainSig, 16) + "…");

            try {
                java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                java.security.cert.X509Certificate x = (java.security.cert.X509Certificate)
                        cf.generateCertificate(new ByteArrayInputStream(cert));
                log.log("   证书 subject=" + x.getSubjectDN() + "  issuer=" + x.getIssuerDN());
            } catch (Throwable t) {
                log.log("   （证书按 X.509 解析失败: " + t + "）");
            }

            log.log("★ 加密控制通道双向可用（加密请求 → 合法加密响应）");
            return true;
        } catch (Throwable e) {
            log.log("!! /auth-setup 异常: " + e);
            return false;
        }
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static byte[] aesCtr128(byte[] key, byte[] iv, byte[] data) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        return c.doFinal(data);
    }
}

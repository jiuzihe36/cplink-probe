package com.carplaylink.probe;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * CarPlay 配对/控制通道用的密码学原语。
 * 与 DiPlay（接收端）的实现一一对应，保证互通：
 *   SHA-512、HKDF-SHA512、ChaCha20-Poly1305(IETF, 12 字节 nonce)、Ed25519、X25519。
 *
 * 注意 nonceLabel 的字节序：DiPlay 用的是「4 个零字节 + 标签」，与某些资料里的
 * 「标签 + 4 个零字节」相反 —— 必须跟对手方一致，否则解密直接失败。
 */
public final class Crypto {

    private static final int NONCE_SIZE = 12;
    private static final int LABEL_SIZE = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    // ------------------------------------------------------------------ 摘要

    public static byte[] sha512(byte[]... parts) {
        Digest d = new SHA512Digest();
        for (byte[] p : parts) {
            d.update(p, 0, p.length);
        }
        byte[] out = new byte[d.getDigestSize()];
        d.doFinal(out, 0);
        return out;
    }

    public static byte[] sha1(byte[]... parts) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ HKDF

    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator gen = new HKDFBytesGenerator(new SHA512Digest());
        gen.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        gen.generateBytes(out, 0, length);
        return out;
    }

    public static byte[] hkdfSha512(byte[] ikm, String salt, String info) {
        return hkdfSha512(ikm, ascii(salt), ascii(info), 32);
    }

    // --------------------------------------------------- ChaCha20-Poly1305

    public static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] out = new byte[cipher.getOutputSize(plaintext.length)];
        try {
            int len = cipher.processBytes(plaintext, 0, plaintext.length, out, 0);
            len += cipher.doFinal(out, len);
            return Arrays.copyOf(out, len);
        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 加密失败", e);
        }
    }

    public static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plaintext) {
        return chachaSeal(key, nonce, plaintext, new byte[0]);
    }

    public static byte[] chachaOpen(byte[] key, byte[] nonce, byte[] ciphertextAndTag, byte[] aad) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(false, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] out = new byte[cipher.getOutputSize(ciphertextAndTag.length)];
        try {
            int len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.length, out, 0);
            len += cipher.doFinal(out, len);
            return Arrays.copyOf(out, len);
        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 校验失败（密钥/nonce/数据不匹配）", e);
        }
    }

    public static byte[] chachaOpen(byte[] key, byte[] nonce, byte[] ciphertextAndTag) {
        return chachaOpen(key, nonce, ciphertextAndTag, new byte[0]);
    }

    /** 12 字节 nonce：4 个零字节 + 标签（最多 8 字节）+ 补零 */
    public static byte[] nonceLabel(String label) {
        byte[] nonce = new byte[NONCE_SIZE];
        byte[] ascii = ascii(label);
        int n = Math.min(ascii.length, LABEL_SIZE);
        System.arraycopy(ascii, 0, nonce, 4, n);
        return nonce;
    }

    /** 12 字节 nonce：4 个零字节 + 8 字节**小端**计数器（控制通道每帧递增） */
    public static byte[] nonce64(long counter) {
        byte[] nonce = new byte[NONCE_SIZE];
        long value = counter;
        for (int i = 4; i < NONCE_SIZE; i++) {
            nonce[i] = (byte) value;
            value >>>= 8;
        }
        return nonce;
    }

    // ------------------------------------------------------------- Ed25519

    public static final class EdPair {
        public final byte[] priv;
        public final byte[] pub;

        EdPair(byte[] priv, byte[] pub) {
            this.priv = priv;
            this.pub = pub;
        }
    }

    public static EdPair ed25519Generate() {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(RANDOM);
        return new EdPair(priv.getEncoded(), priv.generatePublicKey().getEncoded());
    }

    public static byte[] ed25519Sign(byte[] privRaw, byte[] data) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(privRaw, 0));
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    public static boolean ed25519Verify(byte[] pubRaw, byte[] data, byte[] signature) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(pubRaw, 0));
            signer.update(data, 0, data.length);
            return signer.verifySignature(signature);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- X25519

    public static final class XPair {
        public final byte[] priv;
        public final byte[] pub;

        XPair(byte[] priv, byte[] pub) {
            this.priv = priv;
            this.pub = pub;
        }
    }

    public static XPair x25519Generate() {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(RANDOM);
        return new XPair(priv.getEncoded(), priv.generatePublicKey().getEncoded());
    }

    public static byte[] x25519Shared(byte[] privRaw, byte[] peerPubRaw) {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(privRaw, 0);
        X25519PublicKeyParameters peer = new X25519PublicKeyParameters(peerPubRaw, 0);
        byte[] shared = new byte[X25519PrivateKeyParameters.SECRET_SIZE];
        priv.generateSecret(peer, shared, 0);
        return shared;
    }

    // ------------------------------------------------------------------ 工具

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) {
            n += a.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    public static String hex(byte[] b) {
        return hex(b, b == null ? 0 : b.length);
    }

    public static String hex(byte[] b, int len) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, b.length); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }
}

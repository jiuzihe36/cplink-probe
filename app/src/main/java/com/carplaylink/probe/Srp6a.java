package com.carplaylink.probe;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * SRP-6a 客户端（RFC 5054 3072 位组 + SHA-512），AirPlay pair-setup 变体。
 *
 * 与 DiPlay 的 Srp6a（服务端）逐项对齐，特别注意两处容易踩的差异：
 *   1) 乘数 k = H(N || PAD(g))（N 本身正好 384 字节，无需再补零）
 *   2) 会话密钥 K = H(toBytes(S)) —— S 用**最短字节表示**，不是补零到 384！
 */
public final class Srp6a {

    /** RFC 5054 3072-bit prime */
    private static final String N_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
                    + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
                    + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
                    + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
                    + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                    + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183"
                    + "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A"
                    + "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB"
                    + "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8"
                    + "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208"
                    + "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

    private static final int N_BYTES = 384;
    private static final BigInteger N = new BigInteger(N_HEX, 16);
    private static final BigInteger G = BigInteger.valueOf(5);
    private static final BigInteger K = toBigInt(Crypto.sha512(toBytes(N), pad(G)));
    private static final byte[] COLON = {':'};
    private static final SecureRandom RANDOM = new SecureRandom();

    private Srp6a() {
    }

    public static final class Client {
        public final byte[] publicKeyA;
        private final BigInteger a;
        private final byte[] identifier;
        private final byte[] password;
        private byte[] sessionKey;
        private byte[] expectedServerProof;

        Client(String username, String password) {
            this.identifier = Crypto.utf8(username);
            this.password = Crypto.utf8(password);
            byte[] aBytes = new byte[32];
            RANDOM.nextBytes(aBytes);
            this.a = toBigInt(aBytes);
            this.publicKeyA = pad(G.modPow(a, N));
        }

        /** 收到服务端 M2（salt + B）后计算 proof 与会话密钥 */
        public byte[] proofForServer(byte[] salt, byte[] publicKeyB) {
            BigInteger b = toBigInt(publicKeyB);
            BigInteger u = toBigInt(Crypto.sha512(padBytes(publicKeyA), padBytes(publicKeyB)));
            BigInteger x = toBigInt(Crypto.sha512(salt, Crypto.sha512(identifier, COLON, password)));
            BigInteger gx = G.modPow(x, N);
            BigInteger base = b.subtract(K.multiply(gx)).mod(N);
            BigInteger s = base.modPow(a.add(u.multiply(x)), N);

            sessionKey = Crypto.sha512(toBytes(s));

            byte[] hashN = Crypto.sha512(toBytes(N));
            byte[] hashG = Crypto.sha512(toBytes(G));
            byte[] hashXor = new byte[hashN.length];
            for (int i = 0; i < hashN.length; i++) {
                hashXor[i] = (byte) (hashN[i] ^ hashG[i]);
            }
            byte[] proof = Crypto.sha512(hashXor, Crypto.sha512(identifier), salt,
                    padBytes(publicKeyA), padBytes(publicKeyB), sessionKey);
            expectedServerProof = Crypto.sha512(padBytes(publicKeyA), proof, sessionKey);
            return proof;
        }

        public boolean verifyServerProof(byte[] serverProof) {
            if (expectedServerProof == null || serverProof == null
                    || expectedServerProof.length != serverProof.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < serverProof.length; i++) {
                diff |= expectedServerProof[i] ^ serverProof[i];
            }
            return diff == 0;
        }

        public byte[] sessionKey() {
            return sessionKey;
        }
    }

    public static Client start(String username, String password) {
        return new Client(username, password);
    }

    // ------------------------------------------------------------------ 工具

    /** BigInteger → 最短字节表示（去掉多余的符号零字节） */
    static byte[] toBytes(BigInteger value) {
        if (value.signum() == 0) {
            return new byte[]{0};
        }
        byte[] encoded = value.toByteArray();
        if (encoded.length > 1 && encoded[0] == 0) {
            byte[] trimmed = new byte[encoded.length - 1];
            System.arraycopy(encoded, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return encoded;
    }

    static BigInteger toBigInt(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    /** BigInteger → 左侧补零到 384 字节 */
    static byte[] pad(BigInteger value) {
        return padBytes(toBytes(value));
    }

    /** 字节数组 → 左侧补零到 384 字节 */
    static byte[] padBytes(byte[] bytes) {
        if (bytes.length >= N_BYTES) {
            return bytes;
        }
        byte[] out = new byte[N_BYTES];
        System.arraycopy(bytes, 0, out, N_BYTES - bytes.length, bytes.length);
        return out;
    }
}

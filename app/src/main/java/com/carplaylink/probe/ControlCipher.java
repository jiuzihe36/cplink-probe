package com.carplaylink.probe;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * CarPlay 控制通道的 ChaCha20-Poly1305 分帧（与 DiPlay 的 ControlCipher 一致）。
 *
 * 每帧： [2 字节小端密文长度][密文][16 字节 tag]
 * AAD  = 那 2 字节长度头本身
 * nonce = 4 个零字节 + 8 字节小端计数器（收发各一套计数器，从 0 开始）
 *
 * 单向最多 0x4000 字节一块，超出就分多帧。
 */
public final class ControlCipher {

    private static final int HEADER_SIZE = 2;
    private static final int TAG_SIZE = 16;
    private static final int MAX_PAYLOAD = 0x4000;

    private final byte[] readKey;
    private final byte[] writeKey;
    private long readCounter;
    private long writeCounter;

    public ControlCipher(byte[] readKey, byte[] writeKey) {
        this.readKey = readKey;
        this.writeKey = writeKey;
    }

    public static final class Decrypted {
        public final byte[] data;
        public final byte[] rest;

        Decrypted(byte[] data, byte[] rest) {
            this.data = data;
            this.rest = rest;
        }
    }

    /** 把已缓冲的原始字节尽量解成明文，返回明文与不足一帧的残留 */
    public Decrypted decrypt(byte[] buffer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (buffer.length - offset >= HEADER_SIZE) {
            int length = (buffer[offset] & 0xff) | ((buffer[offset + 1] & 0xff) << 8);
            int frameEnd = offset + HEADER_SIZE + length + TAG_SIZE;
            if (buffer.length < frameEnd) {
                break;
            }
            byte[] aad = Arrays.copyOfRange(buffer, offset, offset + HEADER_SIZE);
            byte[] ciphertextAndTag = Arrays.copyOfRange(buffer, offset + HEADER_SIZE, frameEnd);
            byte[] plain = Crypto.chachaOpen(readKey, Crypto.nonce64(readCounter), ciphertextAndTag, aad);
            out.write(plain, 0, plain.length);
            readCounter++;
            offset = frameEnd;
        }
        byte[] data = out.toByteArray();
        byte[] rest = Arrays.copyOfRange(buffer, offset, buffer.length);
        return new Decrypted(data, rest);
    }

    public byte[] encrypt(byte[] plaintext) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        do {
            int end = Math.min(offset + MAX_PAYLOAD, plaintext.length);
            byte[] chunk = Arrays.copyOfRange(plaintext, offset, end);
            byte[] header = {(byte) (chunk.length & 0xff), (byte) ((chunk.length >>> 8) & 0xff)};
            byte[] ciphertextAndTag = Crypto.chachaSeal(writeKey, Crypto.nonce64(writeCounter), chunk, header);
            out.write(header, 0, header.length);
            out.write(ciphertextAndTag, 0, ciphertextAndTag.length);
            writeCounter++;
            offset += MAX_PAYLOAD;
        } while (offset < plaintext.length);
        return out.toByteArray();
    }
}

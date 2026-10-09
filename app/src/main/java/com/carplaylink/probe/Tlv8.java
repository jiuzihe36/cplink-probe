package com.carplaylink.probe;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HomeKit / AirPlay 配对用的 TLV8 编解码（与 DiPlay 的 Tlv8Codec 等价）。
 * 格式：type(1) | length(1) | value(length)；同类型超过 255 字节时按 255 分片，
 * 连续同类型分片之间插入 0xFF 分隔符（长度 0）。
 */
public final class Tlv8 {

    public static final int TYPE_METHOD = 0x00;
    public static final int TYPE_IDENTIFIER = 0x01;
    public static final int TYPE_SALT = 0x02;
    public static final int TYPE_PUBLIC_KEY = 0x03;
    public static final int TYPE_PROOF = 0x04;
    public static final int TYPE_ENCRYPTED_DATA = 0x05;
    public static final int TYPE_STATE = 0x06;
    public static final int TYPE_ERROR = 0x07;
    public static final int TYPE_SIGNATURE = 0x0A;

    private static final int SEPARATOR_TYPE = 0xFF;
    private static final int MAX_FRAGMENT = 255;

    private Tlv8() {
    }

    public static byte[] encode(Map<Integer, byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Integer previousType = null;
        for (Map.Entry<Integer, byte[]> e : items.entrySet()) {
            int type = e.getKey();
            byte[] value = e.getValue() == null ? new byte[0] : e.getValue();
            if (previousType != null && previousType == type) {
                out.write(SEPARATOR_TYPE);
                out.write(0);
            }
            int offset = 0;
            do {
                int length = Math.min(MAX_FRAGMENT, value.length - offset);
                out.write(type & 0xFF);
                out.write(length & 0xFF);
                out.write(value, offset, length);
                offset += length;
            } while (offset < value.length);
            previousType = type;
        }
        return out.toByteArray();
    }

    public static byte[] encodeItems(Object... typeAndValue) {
        Map<Integer, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < typeAndValue.length; i += 2) {
            m.put((Integer) typeAndValue[i], (byte[]) typeAndValue[i + 1]);
        }
        return encode(m);
    }

    public static Map<Integer, byte[]> decode(byte[] buffer) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int position = 0;
        Integer lastType = null;
        int lastLength = 0;
        while (position + 2 <= buffer.length) {
            int type = buffer[position] & 0xFF;
            int length = buffer[position + 1] & 0xFF;
            position += 2;
            if (position + length > buffer.length) {
                break;
            }
            byte[] value = new byte[length];
            System.arraycopy(buffer, position, value, 0, length);
            position += length;
            if (lastType != null && lastType == type && lastLength == MAX_FRAGMENT) {
                byte[] previous = out.get(type);
                out.put(type, previous == null ? value : Crypto.concat(previous, value));
            } else {
                out.put(type, value);
            }
            lastType = type;
            lastLength = length;
        }
        return out;
    }

    /** 取 state 字段（第一个字节） */
    public static int stateOf(Map<Integer, byte[]> tlv) {
        byte[] s = tlv.get(TYPE_STATE);
        return (s == null || s.length == 0) ? -1 : (s[0] & 0xFF);
    }

    public static byte[] one(int value) {
        return new byte[]{(byte) (value & 0xFF)};
    }
}

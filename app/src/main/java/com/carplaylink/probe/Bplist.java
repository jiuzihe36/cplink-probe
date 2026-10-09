package com.carplaylink.probe;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 Apple 二进制 plist（bplist00）**写入**器。
 *
 * DiPlay 的解码器开头就校验 "bplist00" 魔数（XML plist 会被直接拒），
 * 所以 SETUP 的 body 必须自己拼二进制 plist。
 * 覆盖我们需要的子集：dict / array / 非负整数 / ASCII 字符串 / data / bool。
 */
public final class Bplist {

    private static final byte[] MAGIC = {'b', 'p', 'l', 'i', 's', 't', '0', '0'};

    /** 容器节点：序列化时才填引用（引用宽度取决于对象总数） */
    private static final class Arr {
        final int[] refs;

        Arr(int[] refs) {
            this.refs = refs;
        }
    }

    private static final class Dict {
        final int[] keys;
        final int[] values;

        Dict(int[] keys, int[] values) {
            this.keys = keys;
            this.values = values;
        }
    }

    private final List<Object> nodes = new ArrayList<>();

    private Bplist() {
    }

    public static byte[] encode(Map<String, Object> root) {
        return new Bplist().build(root);
    }

    /** 便捷构造：成对的 key/value */
    public static Map<String, Object> dict(Object... keyValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValue.length; i += 2) {
            m.put((String) keyValue[i], keyValue[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ 编码

    private int add(Object value) {
        int index = nodes.size();
        nodes.add(null);
        if (value == null) {
            nodes.set(index, new byte[]{0x00});
        } else if (value instanceof Boolean) {
            nodes.set(index, new byte[]{(byte) (((Boolean) value) ? 0x09 : 0x08)});
        } else if (value instanceof Number) {
            nodes.set(index, intObject(((Number) value).longValue()));
        } else if (value instanceof String) {
            nodes.set(index, stringObject((String) value));
        } else if (value instanceof byte[]) {
            nodes.set(index, dataObject((byte[]) value));
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            int[] refs = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                refs[i] = add(list.get(i));
            }
            nodes.set(index, new Arr(refs));
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            int[] keys = new int[map.size()];
            int[] values = new int[map.size()];
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                keys[i] = add(String.valueOf(e.getKey()));
                values[i] = add(e.getValue());
                i++;
            }
            nodes.set(index, new Dict(keys, values));
        } else {
            nodes.set(index, stringObject(String.valueOf(value)));
        }
        return index;
    }

    private byte[] build(Map<String, Object> root) {
        int top = add(root);
        int count = nodes.size();
        int refSize = count < 256 ? 1 : 2;
        int offsetSize = 2; // 我们的 plist 都很小，2 字节偏移足够且合法

        // 序列化每个对象
        List<byte[]> encoded = new ArrayList<>();
        for (Object node : nodes) {
            if (node instanceof byte[]) {
                encoded.add((byte[]) node);
            } else if (node instanceof Arr) {
                Arr a = (Arr) node;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeMarker(out, 0xA0, a.refs.length);
                for (int r : a.refs) {
                    writeRef(out, r, refSize);
                }
                encoded.add(out.toByteArray());
            } else if (node instanceof Dict) {
                Dict d = (Dict) node;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeMarker(out, 0xD0, d.keys.length);
                for (int r : d.keys) {
                    writeRef(out, r, refSize);
                }
                for (int r : d.values) {
                    writeRef(out, r, refSize);
                }
                encoded.add(out.toByteArray());
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = out.size();
            byte[] b = encoded.get(i);
            out.write(b, 0, b.length);
        }
        int offsetTableOffset = out.size();
        for (int i = 0; i < count; i++) {
            writeBigEndian(out, offsets[i], offsetSize);
        }
        out.write(new byte[6], 0, 6);          // 保留
        out.write(offsetSize);
        out.write(refSize);
        writeBigEndianLong(out, count, 8);      // numObjects
        writeBigEndianLong(out, top, 8);        // topObject
        writeBigEndianLong(out, offsetTableOffset, 8);
        return out.toByteArray();
    }

    private static void writeMarker(ByteArrayOutputStream out, int base, int count) {
        if (count < 15) {
            out.write(base | count);
        } else {
            out.write(base | 0x0F);
            byte[] n = intObject(count);
            out.write(n, 0, n.length);
        }
    }

    private static void writeRef(ByteArrayOutputStream out, int ref, int refSize) {
        writeBigEndian(out, ref, refSize);
    }

    private static void writeBigEndian(ByteArrayOutputStream out, int value, int width) {
        for (int i = width - 1; i >= 0; i--) {
            out.write((value >>> (8 * i)) & 0xff);
        }
    }

    private static void writeBigEndianLong(ByteArrayOutputStream out, long value, int width) {
        for (int i = width - 1; i >= 0; i--) {
            out.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }

    private static byte[] intObject(long value) {
        BigInteger v = BigInteger.valueOf(value);
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }
        int power = 0;
        while ((1L << (8 * (power + 1))) <= value && power < 3) {
            power++;
        }
        int width = 1 << power;
        byte[] out = new byte[1 + width];
        out[0] = (byte) (0x10 | power);
        for (int i = 0; i < width; i++) {
            out[1 + i] = (byte) ((value >>> (8 * (width - 1 - i))) & 0xff);
        }
        return out;
    }

    private static byte[] stringObject(String s) {
        byte[] ascii = Crypto.ascii(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMarker(out, 0x50, ascii.length);
        out.write(ascii, 0, ascii.length);
        return out.toByteArray();
    }

    private static byte[] dataObject(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMarker(out, 0x40, data.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 解码

    /** 解码我们关心的子集；失败抛异常（调用方自己兜） */
    public static Object decode(byte[] b) {
        if (b == null || b.length < 40) {
            throw new IllegalArgumentException("bplist 太短");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (b[i] != MAGIC[i]) {
                throw new IllegalArgumentException("bplist 魔数不对（不是 bplist00）");
            }
        }
        int trailer = b.length - 32;
        int offsetSize = b[trailer + 6] & 0xff;
        int refSize = b[trailer + 7] & 0xff;
        int numObjects = (int) readBe(b, trailer + 8, 8);
        int top = (int) readBe(b, trailer + 16, 8);
        int tableOffset = (int) readBe(b, trailer + 24, 8);
        int[] offsets = new int[numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = (int) readBe(b, tableOffset + i * offsetSize, offsetSize);
        }
        return readObject(b, offsets, refSize, top, 0);
    }

    private static Object readObject(byte[] b, int[] offsets, int refSize, int index, int depth) {
        if (depth > 40 || index < 0 || index >= offsets.length) {
            return null;
        }
        int p = offsets[index];
        int marker = b[p] & 0xff;
        int type = marker >> 4;
        int info = marker & 0x0f;
        switch (type) {
            case 0x0:
                if (info == 0x08) {
                    return Boolean.FALSE;
                }
                if (info == 0x09) {
                    return Boolean.TRUE;
                }
                return null;
            case 0x1:
                return readBe(b, p + 1, 1 << info);
            case 0x2: {
                int width = 1 << info;
                long bits = readBe(b, p + 1, width);
                return Double.longBitsToDouble(width == 4 ? bits << 32 : bits);
            }
            case 0x4:
            case 0x5:
            case 0x6: {
                int[] countAndStart = readCount(b, p, info);
                int count = countAndStart[0];
                int start = countAndStart[1];
                if (type == 0x5) {
                    return new String(b, start, count, java.nio.charset.StandardCharsets.US_ASCII);
                }
                if (type == 0x4) {
                    byte[] d = new byte[count];
                    System.arraycopy(b, start, d, 0, count);
                    return d;
                }
                return new String(b, start, count * 2, java.nio.charset.StandardCharsets.UTF_16BE);
            }
            case 0xA: {
                int[] countAndStart = readCount(b, p, info);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < countAndStart[0]; i++) {
                    int ref = (int) readBe(b, countAndStart[1] + i * refSize, refSize);
                    list.add(readObject(b, offsets, refSize, ref, depth + 1));
                }
                return list;
            }
            case 0xD: {
                int[] countAndStart = readCount(b, p, info);
                int count = countAndStart[0];
                int start = countAndStart[1];
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    int keyRef = (int) readBe(b, start + i * refSize, refSize);
                    int valRef = (int) readBe(b, start + (count + i) * refSize, refSize);
                    Object key = readObject(b, offsets, refSize, keyRef, depth + 1);
                    map.put(String.valueOf(key), readObject(b, offsets, refSize, valRef, depth + 1));
                }
                return map;
            }
            default:
                return null;
        }
    }

    /** 返回 [元素个数, 数据起始偏移] */
    private static int[] readCount(byte[] b, int p, int info) {
        if (info < 15) {
            return new int[]{info, p + 1};
        }
        int intMarker = b[p + 1] & 0xff;
        int width = 1 << (intMarker & 0x0f);
        int count = (int) readBe(b, p + 2, width);
        return new int[]{count, p + 2 + width};
    }

    private static long readBe(byte[] b, int offset, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v = (v << 8) | (b[offset + i] & 0xffL);
        }
        return v;
    }
}

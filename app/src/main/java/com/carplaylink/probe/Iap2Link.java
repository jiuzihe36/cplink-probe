package com.carplaylink.probe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * iAP2 链路层 + 控制会话消息(CSM) 编解码。
 *
 * 规格来源：HaToan/carplay-wifi-extractor 的可工作实现（已对真车验证），
 * 以及 Oligo Security 对 iAP2 的公开分析。字节序全部大端。
 *
 * 链路层包头（9 字节）：
 *   FF 5A | length(2, 含头含尾校验) | control | seq | ack | sessionId | checksum(1)
 * 载荷：payload + checksum(1)；校验算法 = 所有字节按位求和后取二补数（使总和为 0）
 *
 * CSM：40 40 | length(2, 含 6 字节头) | msgId(2) | 参数...
 * 参数：length(2, 含 4 字节) | paramId(2) | value
 * 字符串 = UTF-8 + 结尾 \0
 */
public final class Iap2Link {

    public static final byte[] MARKER = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};

    public static final int SYN = 0x80;
    public static final int ACK = 0x40;
    public static final int EAK = 0x20;
    public static final int RST = 0x10;

    /** 参考实现使用 10=控制会话，11=EA 会话 */
    public static final int SESSION_CONTROL = 10;
    public static final int SESSION_EA = 11;

    public static final int CSM_START_IDENTIFICATION = 0x1D00;
    public static final int CSM_IDENTIFICATION_INFORMATION = 0x1D01;
    public static final int CSM_IDENTIFICATION_ACCEPTED = 0x1D02;
    public static final int CSM_IDENTIFICATION_REJECTED = 0x1D03;

    public static final int CSM_REQUEST_AUTH_CERT = 0xAA00;
    public static final int CSM_AUTH_CERT = 0xAA01;
    public static final int CSM_REQUEST_AUTH_CHALLENGE = 0xAA02;
    public static final int CSM_AUTH_RESPONSE = 0xAA03;
    public static final int CSM_AUTH_FAILED = 0xAA04;
    public static final int CSM_AUTH_SUCCEEDED = 0xAA05;

    public static final int CSM_REQUEST_WIFI_INFO = 0x5700;
    public static final int CSM_WIFI_INFO = 0x5701;
    public static final int CSM_REQUEST_WIFI_CONFIG = 0x5702;
    public static final int CSM_WIFI_CONFIG = 0x5703;
    // ★ v0.2：真 iPhone 在 WiFi 交接后会补发这几条（对照 carplay-wifi-extractor / CPC200 逆向文档）
    public static final int CSM_DEVICE_LANGUAGE_UPDATE = 0x4E0A;
    public static final int CSM_DEVICE_TIME_UPDATE = 0x4E0B;
    public static final int CSM_WIRELESS_CARPLAY_UPDATE = 0x4E0D;
    public static final int CSM_DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E;

    private Iap2Link() {
    }

    // ------------------------------------------------------------------ 校验

    public static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i++) {
            sum = (sum + (b[i] & 0xFF)) & 0xFF;
        }
        return (-sum) & 0xFF;
    }

    public static boolean checksumOk(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i++) {
            sum = (sum + (b[i] & 0xFF)) & 0xFF;
        }
        return sum == 0;
    }

    // -------------------------------------------------------------- 链路层包

    public static byte[] linkPacket(int control, int seq, int ack, int session, byte[] payload) {
        int len = (payload == null || payload.length == 0) ? 9 : payload.length + 10;
        byte[] out = new byte[len];
        out[0] = (byte) 0xFF;
        out[1] = 0x5A;
        out[2] = (byte) ((len >> 8) & 0xFF);
        out[3] = (byte) (len & 0xFF);
        out[4] = (byte) (control & 0xFF);
        out[5] = (byte) (seq & 0xFF);
        out[6] = (byte) (ack & 0xFF);
        out[7] = (byte) (session & 0xFF);
        out[8] = (byte) checksum(out, 0, 8);
        if (len > 9) {
            System.arraycopy(payload, 0, out, 9, payload.length);
            out[len - 1] = (byte) checksum(out, 9, payload.length);
        }
        return out;
    }

    public static final class Header {
        public int length;
        public int control;
        public int seq;
        public int ack;
        public int session;

        @Override
        public String toString() {
            return "len=" + length + " ctrl=0x" + String.format("%02X", control)
                    + " seq=" + seq + " ack=" + ack + " sess=" + session;
        }
    }

    public static Header parseHeader(byte[] h) {
        if (h == null || h.length < 9) {
            return null;
        }
        if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xFF) != 0x5A) {
            return null;
        }
        if (!checksumOk(h, 0, 9)) {
            return null;
        }
        Header x = new Header();
        x.length = ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        x.control = h[4] & 0xFF;
        x.seq = h[5] & 0xFF;
        x.ack = h[6] & 0xFF;
        x.session = h[7] & 0xFF;
        return x;
    }

    // ------------------------------------------------------- 链路同步载荷 LSP

    /** 我们这一侧（手机角色）宣告的链路参数，取值与参考实现一致。 */
    public static byte[] lspPayload() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x01);          // version
        o.write(30);            // maxOutgoing
        writeU16(o, 8192);      // maxLen
        writeU16(o, 4000);      // retransmissionTimeout
        writeU16(o, 500);       // ackTimeout
        o.write(4);             // maxRetransmissions
        o.write(3);             // maxAck
        o.write(SESSION_CONTROL);
        o.write(0);
        o.write(1);
        o.write(SESSION_EA);
        o.write(2);
        o.write(1);
        return o.toByteArray();
    }

    /** 解析车机宣告的 LSP，仅用于日志。 */
    public static String describeLsp(byte[] p) {
        if (p == null || p.length < 10) {
            return "LSP 太短";
        }
        int version = p[0] & 0xFF;
        int maxOutgoing = p[1] & 0xFF;
        int maxLen = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int rtTimeout = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
        int ackTimeout = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        int maxRetrans = p[8] & 0xFF;
        int maxAck = p[9] & 0xFF;
        StringBuilder sb = new StringBuilder();
        sb.append("LSP v").append(version)
                .append(" maxOut=").append(maxOutgoing)
                .append(" maxLen=").append(maxLen)
                .append(" rt=").append(rtTimeout)
                .append(" ackTo=").append(ackTimeout)
                .append(" maxRetrans=").append(maxRetrans)
                .append(" maxAck=").append(maxAck)
                .append(" sessions=[");
        for (int i = 10; i + 2 < p.length; i += 3) {
            sb.append("(id=").append(p[i] & 0xFF)
                    .append(",type=").append(p[i + 1] & 0xFF)
                    .append(",v=").append(p[i + 2] & 0xFF).append(")");
        }
        sb.append("]");
        return sb.toString();
    }

    public static int lspMaxLen(byte[] p) {
        if (p == null || p.length < 4) {
            return 8192;
        }
        int v = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        return v <= 0 ? 8192 : v;
    }

    public static int lspMaxAck(byte[] p) {
        if (p == null || p.length < 10) {
            return 3;
        }
        int v = p[9] & 0xFF;
        return v <= 0 ? 3 : v;
    }

    // ------------------------------------------------------------- CSM 编码

    public static byte[] csm(int msgId, byte[]... params) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (params != null) {
            for (byte[] p : params) {
                if (p != null) {
                    body.write(p, 0, p.length);
                }
            }
        }
        byte[] paramsBytes = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x40);
        o.write(0x40);
        int total = paramsBytes.length + 6;
        o.write((total >> 8) & 0xFF);
        o.write(total & 0xFF);
        o.write((msgId >> 8) & 0xFF);
        o.write(msgId & 0xFF);
        o.write(paramsBytes, 0, paramsBytes.length);
        return o.toByteArray();
    }

    public static byte[] param(int id, byte[] value) {
        int total = value.length + 4;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((total >> 8) & 0xFF);
        o.write(total & 0xFF);
        o.write((id >> 8) & 0xFF);
        o.write(id & 0xFF);
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    public static byte[] paramStr(int id, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        byte[] withNul = new byte[raw.length + 1];
        System.arraycopy(raw, 0, withNul, 0, raw.length);
        withNul[raw.length] = 0;
        return param(id, withNul);
    }

    public static byte[] paramU8(int id, int v) {
        return param(id, new byte[]{(byte) (v & 0xFF)});
    }

    public static byte[] paramU16(int id, int v) {
        return param(id, new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)});
    }

    // ------------------------------------------------------------- CSM 解码

    public static final class Param {
        public int id;
        public byte[] value;
    }

    public static final class Csm {
        public int msgId;
        public final List<Param> params = new ArrayList<>();

        public byte[] valueOf(int id) {
            for (Param p : params) {
                if (p.id == id) {
                    return p.value;
                }
            }
            return null;
        }

        public String strOf(int id) {
            return str(valueOf(id));
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(nameOf(msgId));
            sb.append("(0x").append(String.format("%04X", msgId)).append(")");
            for (Param p : params) {
                sb.append("\n      param[").append(p.id).append("] len=").append(p.value.length);
                String asText = printable(p.value);
                if (asText != null) {
                    sb.append(" text=\"").append(asText).append("\"");
                } else if (p.value.length <= 32) {
                    sb.append(" hex=").append(hex(p.value));
                }
            }
            return sb.toString();
        }
    }

    public static Csm parseCsm(byte[] payload) {
        if (payload == null || payload.length < 6) {
            return null;
        }
        if ((payload[0] & 0xFF) != 0x40 || (payload[1] & 0xFF) != 0x40) {
            return null;
        }
        int total = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        if (total < 6 || total > payload.length) {
            total = payload.length;
        }
        Csm csm = new Csm();
        csm.msgId = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        int off = 6;
        while (off + 4 <= total) {
            int plen = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
            int pid = ((payload[off + 2] & 0xFF) << 8) | (payload[off + 3] & 0xFF);
            if (plen < 4 || off + plen > total) {
                break;
            }
            Param p = new Param();
            p.id = pid;
            p.value = new byte[plen - 4];
            System.arraycopy(payload, off + 4, p.value, 0, plen - 4);
            csm.params.add(p);
            off += plen;
        }
        return csm;
    }

    // ------------------------------------------------------------- 小工具

    public static String nameOf(int msgId) {
        switch (msgId) {
            case CSM_START_IDENTIFICATION: return "StartIdentification";
            case CSM_IDENTIFICATION_INFORMATION: return "IdentificationInformation";
            case CSM_IDENTIFICATION_ACCEPTED: return "IdentificationAccepted";
            case CSM_IDENTIFICATION_REJECTED: return "IdentificationRejected";
            case CSM_REQUEST_AUTH_CERT: return "RequestAuthenticationCertificate";
            case CSM_AUTH_CERT: return "AuthenticationCertificate";
            case CSM_REQUEST_AUTH_CHALLENGE: return "RequestAuthenticationChallengeResponse";
            case CSM_AUTH_RESPONSE: return "AuthenticationResponse";
            case CSM_AUTH_FAILED: return "AuthenticationFailed";
            case CSM_AUTH_SUCCEEDED: return "AuthenticationSucceeded";
            case CSM_REQUEST_WIFI_INFO: return "RequestWiFiInformation";
            case CSM_WIFI_INFO: return "WiFiInformation";
            case CSM_REQUEST_WIFI_CONFIG: return "RequestAccessoryWiFiConfigurationInformation";
            case CSM_WIFI_CONFIG: return "AccessoryWiFiConfigurationInformation";
            case CSM_DEVICE_LANGUAGE_UPDATE: return "DeviceLanguageUpdate";
            case CSM_DEVICE_TIME_UPDATE: return "DeviceTimeUpdate";
            case CSM_WIRELESS_CARPLAY_UPDATE: return "WirelessCarPlayUpdate";
            case CSM_DEVICE_TRANSPORT_IDENTIFIER: return "DeviceTransportIdentifierNotification";
            default: return "Unknown";
        }
    }

    public static String str(byte[] v) {
        if (v == null) {
            return null;
        }
        int len = v.length;
        if (len > 0 && v[len - 1] == 0) {
            len--;
        }
        return new String(v, 0, len, StandardCharsets.UTF_8);
    }

    /** 看起来像可读文本时返回文本，否则返回 null（用于日志） */
    public static String printable(byte[] v) {
        if (v == null || v.length == 0 || v.length > 128) {
            return null;
        }
        for (byte b : v) {
            int c = b & 0xFF;
            if (c == 0) {
                continue;
            }
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
        }
        String s = str(v);
        return (s == null || s.isEmpty()) ? null : s;
    }

    public static String hex(byte[] b) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    public static void writeU16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }
}

package com.carplaylink.probe;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 RTSP 客户端（AirPlay 控制通道用）。
 * pair-verify 之后整条通道被 ChaCha20-Poly1305 包起来（见 ControlCipher），
 * 所以这里的读写都在"明文流"上做，加解密在收发时透明处理。
 */
public final class RtspClient implements Closeable {

    public static final class Response {
        public int status;
        public String statusLine = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AirPlayProbe.Logger log;
    private int cseq = 1;
    private ControlCipher cipher;

    private byte[] plainBuf = new byte[16384];
    private int plainLen;
    private int plainPos;
    private byte[] rawBuf = new byte[32768];
    private int rawLen;

    public RtspClient(Network network, String host, int port, AirPlayProbe.Logger log) throws Exception {
        this.log = log;
        socket = (network != null) ? network.getSocketFactory().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(15000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /** pair-verify 成功后打开：readKey 解入站、writeKey 加出站 */
    public void enableEncryption(byte[] readKey, byte[] writeKey) {
        cipher = new ControlCipher(readKey, writeKey);
    }

    public Response request(String method, String url, byte[] body, String contentType) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq++).append("\r\n");
        sb.append("User-Agent: CPLink/1.0\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (body != null) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] wire;
        if (body != null) {
            wire = new byte[head.length + body.length];
            System.arraycopy(head, 0, wire, 0, head.length);
            System.arraycopy(body, 0, wire, head.length, body.length);
        } else {
            wire = head;
        }
        out.write(cipher == null ? wire : cipher.encrypt(wire));
        out.flush();
        return readResponse();
    }

    private Response readResponse() throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (true) {
            Response parsed = tryParse();
            if (parsed != null) {
                return parsed;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new SocketTimeoutException("等 RTSP 响应超时（已缓冲明文 "
                        + (plainLen - plainPos) + " 字节）");
            }
            if (!fillPlain()) {
                throw new IOException("对端关闭连接（已缓冲明文 " + (plainLen - plainPos) + " 字节）");
            }
        }
    }

    private Response tryParse() {
        int headerEnd = indexOf(plainBuf, plainPos, plainLen, HEADER_END);
        if (headerEnd < 0) {
            return null;
        }
        String text = new String(plainBuf, plainPos, headerEnd - plainPos, StandardCharsets.ISO_8859_1);
        Response r = new Response();
        String[] lines = text.split("\r\n");
        if (lines.length > 0) {
            r.statusLine = lines[0];
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                try {
                    r.status = Integer.parseInt(parts[1]);
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                r.headers.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.US),
                        lines[i].substring(idx + 1).trim());
            }
        }
        int contentLength = 0;
        try {
            String cl = r.header("content-length");
            if (cl != null) {
                contentLength = Integer.parseInt(cl.trim());
            }
        } catch (Throwable ignored) {
        }
        int bodyStart = headerEnd + HEADER_END.length;
        if (plainLen - bodyStart < contentLength) {
            return null;
        }
        r.body = Arrays.copyOfRange(plainBuf, bodyStart, bodyStart + contentLength);
        plainPos = bodyStart + contentLength;
        compact();
        return r;
    }

    /** 从 socket 读一段并（必要时解密后）追加到明文缓冲；返回 false 表示对端关闭 */
    private boolean fillPlain() throws IOException {
        if (rawLen == rawBuf.length) {
            rawBuf = Arrays.copyOf(rawBuf, rawBuf.length * 2);
        }
        int n = in.read(rawBuf, rawLen, rawBuf.length - rawLen);
        if (n < 0) {
            return false;
        }
        rawLen += n;
        if (cipher == null) {
            appendPlain(rawBuf, 0, rawLen);
            rawLen = 0;
        } else {
            ControlCipher.Decrypted d = cipher.decrypt(Arrays.copyOf(rawBuf, rawLen));
            if (d.data.length > 0) {
                appendPlain(d.data, 0, d.data.length);
            }
            System.arraycopy(d.rest, 0, rawBuf, 0, d.rest.length);
            rawLen = d.rest.length;
        }
        return true;
    }

    private void appendPlain(byte[] src, int off, int len) {
        if (plainLen + len > plainBuf.length) {
            plainBuf = Arrays.copyOf(plainBuf, Math.max(plainBuf.length * 2, plainLen + len));
        }
        System.arraycopy(src, off, plainBuf, plainLen, len);
        plainLen += len;
    }

    private void compact() {
        if (plainPos == 0) {
            return;
        }
        int remaining = plainLen - plainPos;
        if (remaining > 0) {
            System.arraycopy(plainBuf, plainPos, plainBuf, 0, remaining);
        }
        plainLen = remaining;
        plainPos = 0;
    }

    private static int indexOf(byte[] hay, int from, int to, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }
}

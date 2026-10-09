package com.carplaylink.probe;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SETUP 阶段：先做端口/特性协商（不带 streams），再单独为视频流 type=110 开数据端口。
 *
 * 两次 SETUP 的 body 都是 bplist00：
 *   #1 {deviceID, name, model, macAddress, osVersion, sourceVersion, timingPort, keepAliveLowPower}
 *      → {timingPort, eventPort, keepAlivePort?, enabledFeatures}
 *   #2 {streams: [{type:110, streamConnectionID:N, ...}]}
 *      → {streams: [{type:110, dataPort:P}]}
 */
public final class AirPlaySetup {

    public static final class Result {
        public int dataPort;
        public long connectionId;
        public int timingPort;
        public int eventPort;
        /** ★ v2.0：音频流（type=100）—— 车机给的 UDP 数据端口和这条流自己的 connectionID */
        public int audioDataPort;
        public long audioConnectionId;
    }

    /** PCM 44100/2/16 —— DiPlay `AudioStreamCodec.PCM_FORMAT[0x800] = 44100 to 2`，也是 CarLife 音频的原格式 */
    private static final long AUDIO_FORMAT_PCM_44K_STEREO = 0x800L;
    public static final int STREAM_TYPE_MAIN_AUDIO = 100;

    private static final String PLIST_CT = "application/x-apple-binary-plist";
    private static final String RTSP_URL = "/1";

    private AirPlaySetup() {
    }

    public static Result run(RtspClient rtsp, String host, AirPlayProbe.Logger log) {
        try {
            String deviceId = ourDeviceId();

            // ---------------- SETUP #1：端口与特性协商
            Map<String, Object> req = Bplist.dict(
                    "deviceID", deviceId,
                    "name", "CPLink",
                    "model", "Android",
                    "macAddress", deviceId,
                    "osVersion", "14.0",
                    "sourceVersion", "950.7.1",
                    "timingPort", 0,
                    "keepAliveLowPower", Boolean.TRUE);
            byte[] body = Bplist.encode(req);
            log.log("—— SETUP #1（端口/特性协商，bplist " + body.length + " 字节）——");
            RtspClient.Response r = rtsp.request("SETUP", "rtsp://" + host + RTSP_URL, body, PLIST_CT);
            log.log("<< SETUP#1 " + r.statusLine + "  (" + r.body.length + " 字节)");
            Result res = new Result();
            try {
                Object decoded = Bplist.decode(r.body);
                if (decoded instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) decoded;
                    res.timingPort = num(m.get("timingPort"));
                    res.eventPort = num(m.get("eventPort"));
                    Object features = m.get("enabledFeatures");
                    log.log("   timingPort=" + res.timingPort + "  eventPort=" + res.eventPort
                            + "  keepAlivePort=" + num(m.get("keepAlivePort")));
                    if (features instanceof List) {
                        log.log("   enabledFeatures " + ((List<?>) features).size() + " 项");
                    }
                } else {
                    log.log("   响应不是字典（前 64 字节 " + Crypto.hex(r.body, 64) + "）");
                }
            } catch (Throwable t) {
                log.log("   （SETUP#1 响应解析失败: " + t + "）");
            }

            // ---------------- SETUP #2：视频流 type=110
            long connectionId = 0x1000 + (System.currentTimeMillis() & 0x0FFF);
            Map<String, Object> stream = Bplist.dict(
                    "type", 110,
                    "streamConnectionID", connectionId,
                    "encryptionType", 0,
                    "displayWidth", VideoSender.WIDTH,
                    "displayHeight", VideoSender.HEIGHT);
            byte[] body2 = Bplist.encode(Bplist.dict("streams", Collections.singletonList(stream)));
            log.log("—— SETUP #2（视频流 type=110，streamConnectionID=" + connectionId
                    + "，" + VideoSender.WIDTH + "x" + VideoSender.HEIGHT + "）——");
            r = rtsp.request("SETUP", "rtsp://" + host + RTSP_URL, body2, PLIST_CT);
            log.log("<< SETUP#2 " + r.statusLine + "  (" + r.body.length + " 字节)");
            try {
                Object decoded = Bplist.decode(r.body);
                if (decoded instanceof Map) {
                    Object streams = ((Map<?, ?>) decoded).get("streams");
                    if (streams instanceof List && !((List<?>) streams).isEmpty()) {
                        Object first = ((List<?>) streams).get(0);
                        if (first instanceof Map) {
                            Map<?, ?> sm = (Map<?, ?>) first;
                            res.dataPort = num(sm.get("dataPort"));
                            log.log("   车机回：type=" + num(sm.get("type")) + "  dataPort=" + res.dataPort);
                        }
                    } else {
                        log.log("   响应里没有 streams（前 64 字节 " + Crypto.hex(r.body, 64) + "）");
                    }
                }
            } catch (Throwable t) {
                log.log("   （SETUP#2 响应解析失败: " + t + "）");
            }
            res.connectionId = connectionId;
            if (res.dataPort <= 0) {
                log.log("!! 车机没给视频数据端口，后面推不了流");
                return null;
            }

            // ---------------- SETUP #3：音频流 type=100（★ v2.0 新增）
            // 以前只做视频流 → 车机永远没声音。音频是**独立的一条流**：
            // 车机回一个 UDP dataPort，我们用 HKDF(shared,"DataStream-Salt<这条流的ID>",
            // "DataStream-Output-Encryption-Key") 加密后按 [12 头][密文][16 tag][8 小端 nonce] 发。
            // 格式选 **PCM 44100/2/16**（0x800）：CarLife 的媒体音频本来就是它 → 不用转码。
            long audioId = connectionId + 1;
            Map<String, Object> astream = Bplist.dict(
                    "type", STREAM_TYPE_MAIN_AUDIO,
                    "audioFormat", AUDIO_FORMAT_PCM_44K_STEREO,
                    "audioType", "media",
                    "streamConnectionID", audioId,
                    "audioLatencyMs", 100,
                    "ct", 0,
                    "spf", 1024,
                    "encryptionType", 0);
            byte[] body3 = Bplist.encode(Bplist.dict("streams", Collections.singletonList(astream)));
            log.log("—— SETUP #3（音频流 type=100，streamConnectionID=" + audioId
                    + "，PCM 44100/2/16）——");
            r = rtsp.request("SETUP", "rtsp://" + host + RTSP_URL, body3, PLIST_CT);
            log.log("<< SETUP#3 " + r.statusLine + "  (" + r.body.length + " 字节)");
            try {
                Object decoded = Bplist.decode(r.body);
                if (decoded instanceof Map) {
                    Object streams = ((Map<?, ?>) decoded).get("streams");
                    if (streams instanceof List && !((List<?>) streams).isEmpty()) {
                        Object first = ((List<?>) streams).get(0);
                        if (first instanceof Map) {
                            Map<?, ?> sm = (Map<?, ?>) first;
                            res.audioDataPort = num(sm.get("dataPort"));
                            log.log("   车机回：type=" + num(sm.get("type"))
                                    + "  audioDataPort=" + res.audioDataPort
                                    + "  controlPort=" + num(sm.get("controlPort")));
                        }
                    } else {
                        log.log("   !! 音频流被拒（响应里没有 streams）—— 这条会话不会有声音");
                    }
                }
            } catch (Throwable t) {
                log.log("   （SETUP#3 响应解析失败: " + t + "）");
            }
            res.audioConnectionId = audioId;
            if (res.audioDataPort <= 0) {
                log.log("   !! 车机没给音频数据端口 —— 画面照旧，但不会有声音");
            }
            return res;
        } catch (Throwable t) {
            log.log("!! SETUP 异常: " + t);
            return null;
        }
    }

    /** 由配对 id 派生一个稳定的 MAC 风格 deviceID */
    static String ourDeviceId() {
        String seed = Pairing.controllerId == null ? "CPLINK" : Pairing.controllerId;
        byte[] h = Crypto.sha512(Crypto.utf8(seed));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", h[i]));
        }
        return sb.toString();
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}

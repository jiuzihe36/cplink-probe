# CPLink 探测 —— 让安卓手机冒充 iPhone，占住只有无线 CarPlay 的车机

手机侧**全程自研**讲 CarPlay：蓝牙 **iAP2**（配对/鉴权/拿车机热点）→ 切到车机热点 →
Wi-Fi **AirPlay**（pair-setup / pair-verify / MFi auth-setup / SETUP）→ 把手机侧的画面当视频推上车机屏，
车机的触摸与声音走 AirPlay 的 event / audio 通道回来。手机侧不需要任何 MFi 芯片（iAP2 认证是单向的）。

## 实测环境

- 手机：OnePlus `PKG110` / `OP5D2BL1`，Android 16（`UKQ1.231108.001`）；CarLife+ 8.6.8、Car+ 17.31.0
- 假车机（测试用）：DiPlay（开源接收端，`shihabal3amri/DiPlay`）跑在 LAVA LZX409 上
- 真车（目标）：奇瑞瑞虎7 PLUS，德赛西威 T1E NV8341，QNX + Android 9，**只有无线 CarPlay**

## 能做什么 / 不能做什么

| 能力 | 状态 | 实测证据（日志原文） |
|---|---|---|
| 蓝牙 iAP2 配对 + 鉴权 + 拿车机热点 | ✅ | `== iAP2 链路已建立（NORMAL）★` → `== 拿到车机热点 == SSID=@NRadio-4B02-5G` |
| AirPlay 握手（pair-setup / pair-verify / auth-setup / SETUP） | ✅ | `★ pair-verify 通过` → `SETUP#2 RTSP/1.0 200 OK  dataPort=42411` |
| 视频上屏（H.264 直通，不录屏） | ✅ | `★ 开始持续推流：CarLife 直通 → 车机` / `已推 1000 帧（仍在推流中）` |
| 视频参数集（VideoConfig / avcC） | ✅ | `★ 已发 CarLife 自己的 VideoConfig（SPS 17 字节 / PPS 4 字节，明文）` |
| 音频流（AirPlay `type=100`，PCM 44100/2/16） | ⚠️ 已发出、未听验 | `✓ 音频第 1 包已发出（1060 字节 UDP，seq=0 ts=0）` / `音频已发 8000 包 / 8000 KB PCM` |
| 手机整屏镜像（不依赖 CarLife） | ✅ | `已推 N 帧 [手机整屏]` |
| 车机触摸回传 | ❌ 车机侧不发 | `（event 通道心跳：已连 44 秒，收到车机消息 N 条，其中触摸 0 条）` |
| 内容源＝CarLife+ / Car+ 的车机界面 | ❌ | `（CarLife 画面活动度：… 平均 312 字节 / 最小 312 / 最大 312 —— **画面基本静止**）` |
| 对 Car+（OPPO 智行）冒充车机 | ❌ 已放弃 | 它用 `net.easyconn.carman` 自己的协议：本机端口 `10016 10150 10152 10156 10157 10162 12121`，**不用** CarLife 那 7 条 |
| 调 Car+ 的 cast 接口拿画面 | ❌ 双重锁死 | 调用方白名单（必须 `com.baidu.carlife.oppo` 或 Car+ 自己）+ Car+ 自己必须先连上一台 CarLife 车机 |
| 真车验证 | ❌ 未做 | 目前只在 DiPlay 假车机上跑通 |

## 用法

**主界面只有三个控件**：【▶ 开始投屏】【■ 停止】【高级 ▾】。

1. 【▶ 开始投屏】= 一键：拉起 CarLife+ → 提示你**切回本 App**（Android 不许后台 App 启动界面）→
   自动接上 CarLife+ → 把 Car+ 摆前台 → 起 CarPlay。
   该等到的信号：`✓ CarLife+ 端口开了` → `★★ 收到视频帧 #1` → `★ 已发 CarLife 自己的 VideoConfig`。
2. 【高级 ▾】→ **【★ 投手机整屏到车机（不用 CarLife）】**：不接 CarLife，直接把手机屏推上车机
   （手机把 SmartDock 这类开源桌面模式启动器当桌面时，车机上就是它的横屏车机桌面）。
   该等到的信号：`★ 已连上视频数据端口` → 车机上出现手机屏。
3. 触摸排查：【高级 ▾】→【测试触摸】= 绕开车机，直接往 CarLife 触控通道发一次"按下+抬起"，
   用来分清"我们没转出去"还是"车机没发过来"；同时看每 15 秒一行
   `（event 通道心跳：… 收到车机消息 N 条，其中触摸 M 条）`。
4. 停止：【■ 停止】（会同时停视频流和音频流）。

## 关键实现要点（每条都是踩过的坑）

1. **iAP2 认证是单向的**：车机向手机证明自己；最后一步由手机自己上报 `0xAA05` 表示成功。
   挑战长度是硬约束 —— **先发 32 字节**，接收端用软件身份时写死 `require(challenge.size == 32)`，
   发 20 字节它会抛异常并直接断连（手机侧只看到 `Broken pipe`，毫无提示）。
2. **谁发起连接**：DiPlay 这类接收端是**它主动拨手机**（`createRfcommSocketToServiceRecord("…cafe")`）。
   手机侧必须**先 `listenUsingRfcommWithServiceRecord(name, …cafe)` 并 accept 20~25 秒**，
   只做主动拨 = 永远连不上。
3. **AirPlay 视频：必须发 `VideoConfig`(avcC)，只发帧会一直黑屏**（DiPlay 收到 VideoConfig 才配解码器）；
   帧必须是 **4 字节大端长度前缀（AVCC）**，不是 Annex-B 起始码。
4. **转发第三方 H.264（CarLife）时参数集只在第一帧里**，而那一帧往往在起流之前就过去了。
   做法：收到帧时就缓存 SPS/PPS + 最近 I 帧；**缺参数集时发 `98311` 让源端重置编码器**逼它重出，
   参数集一到立刻补发 VideoConfig + I 帧。（不做这步的现象：车机一直停在**上一次会话的旧画面**。）
5. **event 通道**：密钥方向与控制通道**相反**（车机加密它发出的帧用 `Events-Write`，回话用 `Events-Read`）；
   分帧 `[2 字节小端密文长度][密文][16 字节 tag]`，**AAD = 那 2 字节长度头**；
   一帧解不开 DiPlay 会 `close()` **整条会话** → 不确定加密是否正确时**宁可不回话**（它不等响应）。
6. **音频是独立的一条流**（SETUP `type=100`），不做就永远没声音。线格式：
   `[12 字节 RTP 头][密文][16 字节 tag][8 字节小端 nonce]`，AAD = 头里**后 8 字节**（时间戳+SSRC），
   密钥 `HKDF(shared, "DataStream-Salt<该流ID>", "DataStream-Output-Encryption-Key")`（与视频同一套派生）。
   ⚠️ **PCM 是大端**（接收端会 `byteSwapS16`），原样转发小端 PCM = 一片杂音；接收端收包缓冲 4096 → 按 1KB 分片。
7. **触摸**：车机→手机是 event 通道的 `{type:"hidSendReport", uuid:"2a2a2a2a", hidReport:<2×6 字节>}`
   （`[槽位][按下][x u16 小端][y u16 小端]`）；转给 CarLife 走**通道 6** 的 `425985 CarlifeTouchAction`
   （**8 字节头** `[u16 len][u16 rsv][u32 service]`，其余通道 12 字节）。
8. **一键流程必须拆两段**：Android 不许后台 App `startActivity` / 绑别人的服务 ——
   按下按钮时（我们一定在前台）只做第一段，回到前台后（`onResume`）再做第二段。
9. **握手不要"等不到就重发"式自激**：超时重发关键报文会让对端一直重新协商、永远进不了连接完成态
   （现象：手机一直广播发现包、推过来的画面是一张静止图）。参考实现（`carlife_pc_tool`）在该位置什么都不发。
10. **清理调用不要放在新一轮启动流程中间**：`stop()` 会打到刚启动的线程（现象：日志刚写"音频流已开"，
    下一行就是"共发 0 包"）。清理用 `resetQueue()` 这类不杀线程的调用，并且**先把上一轮的停止标志清零再启动**。

## 协议细节

**iAP2（蓝牙 RFCOMM）**：marker `FF550200EE10` → SYN/LSP（`maxOut=4 maxLen=65535 rt=4000 ackTo=500 maxRetrans=4 maxAck=3`）
→ CSM 鉴权（`0xAA00/0xAA01` 证书 607 字节、`0xAA02/0xAA03` 签名 64 字节、手机报 `0xAA05`）
→ `0x5702/0x5703` 拿车机热点 `SSID/密码/加密方式/信道`。

**AirPlay（RTSP 7000，加密后走控制通道）**：
`GET /info`（displays / hidDevices / audioFormats）→ `/pair-setup`（SRP-3072，PIN 3939）
→ `/pair-verify`（X25519 + Ed25519）→ `/auth-setup`（MFi-SAP）→
`SETUP#1`（回 `timingPort/eventPort/keepAlivePort`）→ `SETUP#2` 视频流 `type=110`（回 `dataPort`）
→ `SETUP#3` 音频流 `type=100`（回 UDP `dataPort/controlPort`）。

**CarLife（本机回环 7 条通道）**：`7240 控制 / 8240 视频 / 9240 媒体音频 / 9241 TTS / 9242 语音 / 9340 触控 / 9440 车况`。
握手：`98305 版本协商 → 65538 → 98343 + 心跳 131074 → 65540 → 98307 → 65617 → 98386 →
65611 → 98380 + 98311 → 65544 → 98313`（收到 `65551` 才再发一次 `98311`）。
视频帧 = 通道 2 的 `131073` + H.264 裸流；音频 = 通道 3 的 `196609 {44100,2,16}` + `196614` 裸 PCM。

## 构建与下载

GitHub Actions 构建（本机无 JDK/SDK）：temurin 17 + cmdline-tools `11076708` +
`platforms;android-34` + `build-tools;34.0.0` + Gradle 8.7。

**下载（手机浏览器直接点）**：
https://github.com/jiuzihe36/cplink-probe/releases/download/v0.1/app-debug.apk

## 代码结构

| 文件 | 职责 |
|---|---|
| `MainActivity` | 界面（3 个主控件 + 折叠的高级）+ 一键流程 |
| `AirPlayProbe` | AirPlay 全流程编排 + event 通道（触摸/关键帧请求）+ 心跳统计 |
| `AirPlaySetup` | SETUP#1/#2/#3（端口协商 / 视频流 / 音频流） |
| `VideoSender` | 视频推流：镜像编码 + CarLife 直通（参数集缓存、关键帧应答、画面活动度） |
| `AudioSender` | AirPlay 音频流（UDP、大端 PCM、1KB 分片、有界队列） |
| `CarLifeProbe` | 冒充 CarLife 车机端（7 通道握手、心跳、触控/按键转发） |
| `CarPlusCast` / `CarPlusUi` | Car+ 的 cast 接口 / 界面拉起（**已证伪，仅供追溯**） |
| `Pairing` / `PairSetupClient` / `PairVerifyClient` / `MfiSapClient` | 配对、密钥交换、MFi 认证 |
| `Crypto` / `Bplist` / `RtspClient` / `Pb` | ChaCha20-Poly1305、HKDF、bplist、RTSP、protobuf |
| `ScreenCapture` / `MirrorService` | 整屏镜像源 |

## 已知限制

- 只在 DiPlay 假车机上跑通；真车（NV8341）未验证。
- 车机触摸目前拿不到 —— DiPlay 侧不发（它的设置界面开着时 `onHostTouch` 第一行 `if (menuOpen) return true` 会吞掉所有触摸）。
- 内容源仍是短板：CarLife+/Car+ 给的是它们自己的界面（且常是静止页）；要用好看的横屏桌面，
  建议手机把 **SmartDock**（开源桌面模式启动器，GPL-3.0，F-Droid 免费）当桌面 + 用【★ 投手机整屏】。
- 同类商业产品「car iPhone 安卓版」= SmartDock + DiPlay + 自己的胶水（OBD/行车记录仪/收音机/商店激活），
  方向相反（它让手机当车机屏去接 iPhone，不连车），**没有发送端** —— 本项目这块是自己写的。

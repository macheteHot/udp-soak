# udp-soak — 车机 UDP 收包诊断工具

一个用来判定**车机 Wi-Fi 会不会稳定地把低速率 UDP 流转进来**的小工具。

它由两部分组成：

| 组件 | 位置 | 作用 |
| --- | --- | --- |
| **Android 接收端** | `app/`（包名 `com.diplay.udptest`） | 装到车机上，在 `:5001` 监听 UDP，**每秒**把累计收包数写进日志和屏幕 |
| **PC 发送端** | `pc/udp-sender.sh` | 从本机向车机打一个和 CarPlay 实时音频同型的流：**600 B/包 @ 47 pps（≈0.23 Mbps）** |

两边都留**逐秒原始计数**，所以"到底丢了多少、是慢慢丢还是一下停"看得一清二楚。

---

## 为什么需要它

无线 CarPlay 的实时音频走 UDP（`type=102`，AAC-LC 48kHz）。有些车机（例如
Geely/Lynk FX11 / `geely_fx11` / `CS11_A4`，Android 11）会出现：

- CarPlay 一连接，声音**响 1~2 秒就断**；
- 车机 App 侧 `rx` 计数归零后**冻结**，没有 `TEARDOWN`，iPhone 端仍在播放；
- 而同一台车机的**上行**（发给 AP）完全正常。

本工具就是用来把这件事**量化、复现、留证**的：在干净的单播链路上打一个低速率 UDP 流，
看车机能不能持续收。若车机在几百个包后**突然停止收包**，那就不是 App 的问题，
而是车机 Wi-Fi 接收侧的问题。

> 实测（iPhone 个人热点，先拆掉 P2P）：发送 **2784 包**，车机只收到 **120 包（95.7% 丢）**，
> 且约 2.5 秒后**完全停收**；同一时刻 `ping` 车机→PC 0% 丢，PC→车机 100% 丢，
> 车机 Wi-Fi 自报 RSSI **-29 dBm / 1200 Mbps**。

完整方法与案例见 [`docs/`](docs/)：

- [`docs/诊断法-实时音频UDP丢包.md`](docs/诊断法-实时音频UDP丢包.md)（中文）
- [`docs/DIAGNOSTIC-UDP-AUDIO-LOSS.md`](docs/DIAGNOSTIC-UDP-AUDIO-LOSS.md)（English）

---

## 构建 & 安装（Android 接收端）

需要 Android SDK（`ANDROID_HOME` 或 `local.properties`）。

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.diplay.udptest android.permission.ACCESS_FINE_LOCATION
```

`ACCESS_FINE_LOCATION` 是 Android 10+ 读取 Wi-Fi 信息所必需的；界面上的热点/P2P 按钮才会用到它。
**只做 UDP 收包测试的话不需要授予**，App 启动后就直接开始监听。

## 跑一次测试

1. **让车机和本机在同一个网段**（同一个路由器 / 热点 / Wi‑Fi Direct 组）。
   - 无线 CarPlay 场景下，让车机连一个**普通 AP** 最干净。
   - ⚠️ 单 Wi-Fi 芯片的车机不要同时建 P2P 组，否则会和 `wlan0` 抢信道、污染结果。
     本工具**不会**自动建组；需要时手动按按钮。
2. 车机上打开 **UDP Test**，记下界面上的 `wlan0` 地址。
3. 本机执行：

```sh
./pc/udp-sender.sh <车机IP> 5001 60
```

不带参数则默认打当前默认网关、端口 5001、60 秒。

4. 结束后在车机上看：

- 界面上的实时计数；
- 日志文件：`/sdcard/Android/data/com.diplay.udptest/files/udptest.log`（**每秒一行**）。

```sh
adb pull /sdcard/Android/data/com.diplay.udptest/files/udptest.log
```

## 输出怎么读

发送端每秒一行：

```
t=   1.0s  sent_total=    47  this_sec= 47  errors=0
...
DONE sent=2784 errors=0 elapsed=60.0s  (13.36 Mbit)
```

接收端每秒一行（`packets` 是**累计值**）：

```
1791724607833 packets=41  bytes=24600 sinceLastMs=15  gaps>200ms=0 maxGapMs=24
1791724608850 packets=88  bytes=52800 sinceLastMs=17  gaps>200ms=0 maxGapMs=24
1791724609869 packets=120 bytes=72000 sinceLastMs=346 gaps>200ms=0 maxGapMs=26
1791724610889 packets=120 bytes=72000 sinceLastMs=1367 ...
```

判读要点：

- `packets` 一直不涨 + `sinceLastMs` 越来越大 → **收包完全停了**（不是变慢）。
- 存活期间 `maxGapMs` 小、`gaps>200ms` 为 0 → 链路"好的时候是真好"，
  丢包是**整体停止**，不是抖动。
- 收/发对比：`车机收到 / 本机发出` = 实际到达率。

## 接口

如果需要复现 DiPlay 的热点行为，界面还提供两个**手动**按钮：

- **创建 Wi-Fi P2P 组**（与 DiPlay 相同，生成 `p2p0`）并显示 SSID / 密码；
- **创建 LocalOnlyHotspot**。

以及一个"发送 50 包"按钮，用于反向验证（车机 → 本机）。

## 已知限制

- 两边都**不做任何应用层重传或 ACK**，测的就是网络本身。
- 车机侧需要能写 `getExternalFilesDir()`（普通应用目录即可，不需要 root）。
- 个别厂商 ROM 会限制后台 App 的 Wi-Fi 接收；请保持 App 在前台。

## 许可

[GPL-3.0](LICENSE)。`docs/` 下的方法与案例取自
[DiPlay](https://github.com/shihabal3amri/DiPlay) 车机兼容记录（同为 GPL-3.0）。

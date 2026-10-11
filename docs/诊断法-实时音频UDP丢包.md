# 诊断法：实时音频丢包（UDP 自检）

> 用途：当 CarPlay **实时音频**（音乐/导航/通话/Siri）**几秒后断**、而**画面/触控/iAP2 正常**时，用它判断
> 「是不是车机 Wi-Fi 的收包问题」——这是应用层无法修复的那一类。
> 出处：本车（`geely_fx11` / `CS11_A4`）的实测；结论见 `车机兼容记录.md` 第十节与上游 #165 / #131。
> English: `carDocs/DIAGNOSTIC-UDP-AUDIO-LOSS.md`

## 原理

在车机上开一个 **UDP 监听**，由 PC 以**与音频同型**的速率持续打流
（AAC 实时音频约 **600B/包 @47pps ≈ 0.23 Mbps**），然后比较**发送数**与**接收数**。

- 只要收包率正常 → 车机 Wi-Fi 没问题，问题在别处；
- 收包率很低 / 几秒后归零 → 车机 Wi-Fi 收包（驱动/固件/省电）问题，与 DiPlay 无关。

## 步骤

1. **让车机和 PC 在同一网络**：
   - 车机连 PC 的热点/家用路由（车机当客户端）；或
   - 用车机自己的热点，PC 连过去。
   > ⚠️ 不要让车机的 **P2P（无线 CarPlay）**同时开着跑——单 Wi-Fi 芯片会 off-channel 抢占，污染结果。
2. **车机侧监听**（任选）：
   - 有 `iperf` 时最省事：`/vendor/bin/iperf -s -u -i 1`
   - 否则用本仓的最小接收 App：**`carDocs/tools/udp-soak/`**（约一百行；绑定 `::5001`、每秒把 `packets/bytes/sinceLastMs/gaps/maxGap` 打到 logcat + 文件；也能自建 P2P 组/热点让 PC 连过来）。构建见其 `README.md`。

## 附录 A：最小接收 App 要点（约一百行）

- Manifest：`INTERNET` 权限即可（UDP 收包不需要别的）。
- 绑定：`DatagramSocket(null)` → `reuseAddress` → `bind(":::<port>")`（这样 IPv4/IPv6 都能收）。
- 计数：`packets`、`bytes`、`lastGapMs`（距上一包的时间，用来发现「几秒后归零」）、`gaps>200ms`。
- 输出：每秒一条日志 + 写到自己 `getExternalFilesDir()` 下的文件（方便事后 `adb pull`）。
3. **PC 侧发送**：
   ```bash
   # 有 iperf：
   iperf -c <车机IP> -u -b 230k -t 60 -i 1
   # 没有就用下面附录的 python 脚本
   ```
4. 读**车机侧**的 `Lost/Total`（iperf）或接收计数（App）。

## 判读

| 结果 | 结论 |
|---|---|
| 丢包 < 5% | 车机 Wi-Fi 收包正常 → 去查 App 侧 stats / 协议 / 焦点 |
| 丢包 > 10%，或几秒后接收归零 | **车机 Wi-Fi 收包问题**（与 App 无关） |

**交叉验证**（同一次测试里做）：

- `PC → AP 网关` 的 ping 应 **0% 丢**（证明发送侧没问题）；
- `车机 → PC` 的 ping 看**延迟**：出现**几百毫秒~秒级**的 RTT，通常说明**省电/速率控制异常**；
- 车机 Wi-Fi 自报的 `Link speed`（低得离谱如 `1Mbps` 也是线索）。

## 本车的实测结果（供对照）

| 路径 | 车机角色 | 本机发送 | 车机收到 | 丢包 |
|---|---|---|---|---|
| iPhone 个人热点 | 客户端 | 929 | 122 | **≈87%** |
| 普通开放 AP | 客户端 | 808 | 189 | **≈77%** |

对照：本机→AP 网关 ping **0% 丢**；车机→本机 ping **avg 584ms / max 1551ms**（25% 丢）；车机自报 `Link speed 1Mbps`。

## 附录 B：PC 侧 python 发送脚本

```python
#!/usr/bin/env python3
# 用法: python3 udp_soak.py <车机IP> [端口=5001] [秒数=60]
# 以 600B/包 @47pps 打流，每秒打印；用 Ctrl-C 结束
import socket, sys, time
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5001
dur  = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0

PKT = b'\x80\x60' + bytes(598)          # 600 字节假 RTP
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
interval = 1.0 / 47.0
t0 = time.time(); n = 0; sec = 0; sec0 = t0
while time.time() - t0 < dur:
    s.sendto(PKT, (host, port)); n += 1; sec += 1
    time.sleep(interval)
    now = time.time()
    if now - sec0 >= 1.0:
        print(f"t={now-t0:5.1f}s sent={n:6d} this_sec={sec:3d}")
        sec = 0; sec0 = now
s.close()
print(f"DONE sent={n}")
```

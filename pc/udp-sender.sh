#!/usr/bin/env bash
#
# udp-sender.sh — 从本机向车机打 UDP（600B/包 @47pps，和 CarPlay 音频同型）
# ---------------------------------------------------------------------------
# 用法（本机已切到车机的 Wi-Fi / P2P 组之后执行）：
#   ~/udp-sender.sh                      # 目标自动取当前默认网关（通常就是车机）
#   ~/udp-sender.sh 192.168.49.1         # 手动指定车机 IP
#   ~/udp-sender.sh 192.168.49.1 5001 120
#
# 参数： <车机IP> [端口=5001] [秒数=60]
#
# 特点：
#   - 每秒打印已发包数，全部落盘到 ~/udp-sender-<时间戳>.log
#   - 发 ICMP 不可达错误（端口没开/被挡）会打印 send error
#   - 先 ping 一下确认能到
#
# 车机侧结果：测试 App 屏幕上有实时计数，也可以事后由我读车机日志。
# ---------------------------------------------------------------------------
set -uo pipefail

TARGET="${1:-}"
PORT="${2:-5001}"
DUR="${3:-60}"

if [ -z "$TARGET" ]; then
  TARGET=$(ip route 2>/dev/null | awk '/^default/{print $3; exit}')
fi
if [ -z "$TARGET" ]; then
  echo "无法自动确定车机 IP，请手动指定：$0 <car-ip> [port] [seconds]"
  exit 1
fi

LOG="$HOME/udp-sender-$(date +%Y%m%d-%H%M%S).log"
{
  echo "=================================================="
  echo "时间        : $(date '+%F %T')"
  echo "目标        : $TARGET:$PORT"
  echo "时长        : ${DUR}s"
  echo "包大小/速率 : 600B/包 @ 47pps (~0.23 Mbps)"
  echo "本机接口:"
  ip -4 -o addr show 2>/dev/null | grep -v ' lo ' | awk '{print "  "$2" "$4}'
  echo "默认路由: $(ip route 2>/dev/null | grep '^default' | head -1)"
  echo "=================================================="
} | tee "$LOG"

echo ">> ping 目标（确认可达）:" | tee -a "$LOG"
ping -c 3 -W 2 "$TARGET" 2>&1 | tee -a "$LOG"

echo ">> 开始发送 ..." | tee -a "$LOG"

python3 -u - "$TARGET" "$PORT" "$DUR" <<'PY' 2>&1 | tee -a "$LOG"
import socket, sys, time
host, port, dur = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
size = 600
pkt = b'\x80\x60' + bytes(size - 2)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.setsockopt(socket.SOL_IP, 11, 1)   # IP_RECVERR: 让 ICMP 错误作为异常抛出
except Exception as e:
    print("  (IP_RECVERR 不可用:", e, ")")

interval = 1.0 / 47.0
t0 = time.time()
n = 0
sec_n = 0
sec_start = t0
errors = 0
try:
    while time.time() - t0 < dur:
        try:
            s.sendto(pkt, (host, port))
            n += 1
            sec_n += 1
        except OSError as e:
            errors += 1
            if errors <= 10:
                print(f"  !! send error after sent={n}: {e}")
        time.sleep(interval)
        now = time.time()
        if now - sec_start >= 1.0:
            print(f"t={now - t0:6.1f}s  sent_total={n:6d}  this_sec={sec_n:3d}  errors={errors}")
            sec_n = 0
            sec_start = now
finally:
    s.close()
elapsed = time.time() - t0
print(f"DONE sent={n} errors={errors} elapsed={elapsed:.1f}s  ({n*size*8/1e6:.2f} Mbit)")
PY

echo "" | tee -a "$LOG"
echo ">> 日志已保存: $LOG" | tee -a "$LOG"

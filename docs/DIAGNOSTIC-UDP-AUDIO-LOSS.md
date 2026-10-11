# Diagnostic method: realtime audio packet loss (UDP soak)

> Use this when CarPlay **realtime audio** (music / navigation / calls / Siri) **stops after a few seconds**
> while **video / touch / iAP2 stay fine** — to decide whether it is the **head unit's Wi-Fi receive path**
> (a class the app cannot fix).
> Source: measured on `geely_fx11` / `CS11_A4` (DesaySV, SA8155, Android 11); conclusions in
> `车机兼容记录.md` section 10 and upstream #165 / #131.
> Chinese: `carDocs/诊断法-实时音频UDP丢包.md`

## Idea

Run a **UDP listener on the head unit** and push a stream from a PC with the **same shape as the audio**
(about **600 B per packet at 47 pps ≈ 0.23 Mbps**), then compare **sent** vs **received**.

- Normal receive → the head unit's Wi-Fi is fine; look elsewhere.
- Very low / stops after a few seconds → the head unit's Wi-Fi receive path (driver / firmware / power
  save) drops it; not an app issue.

## Steps

1. **Put the head unit and the PC on the same network**:
   - head unit joins a PC hotspot / home router (head unit = client); or
   - use the head unit's own hotspot and connect the PC to it.
   > ⚠️ Do **not** keep Wi-Fi Direct (wireless CarPlay) running at the same time — the single Wi-Fi chip
   > time-shares off-channel and pollutes the result.
2. **Listen on the head unit** (either):
   - with `iperf` present: `/vendor/bin/iperf -s -u -i 1`
   - otherwise the minimal receiver app in this repo: **`carDocs/tools/udp-soak/`** (about 100 lines;
     binds `::5001`, logs `packets/bytes/sinceLastMs/gaps/maxGap` per second to logcat and a file, and
     can create the P2P group / local-only hotspot so a PC can join). Build steps in its `README.md`.
3. **Send from the PC**:
   ```bash
   # with iperf:
   iperf -c <head-unit-ip> -u -b 230k -t 60 -i 1
   # otherwise use the python script in Appendix B
   ```
4. Read **`Lost/Total`** on the head-unit side (iperf) or the receiver counters.

## How to read it

| Result | Conclusion |
|---|---|
| loss < 5% | head-unit Wi-Fi receive is fine → check app stats / protocol / focus |
| loss > 10%, or receive goes to zero after a few seconds | **head-unit Wi-Fi receive problem** (not the app) |

**Controls to run in the same session:**

- `PC → AP gateway` ping should be **0% loss** (proves the sender side is fine);
- `head unit → PC` ping **RTT**: hundreds of ms to seconds usually means **power save / rate control**;
- the head unit's reported `Link speed` (an absurdly low value such as `1 Mbps` is another clue).

## Measured on this unit (for reference)

| Path | Head-unit role | Sent | Received | Loss |
|---|---|---|---|---|
| iPhone Personal Hotspot | client | 929 | 122 | **~87%** |
| ordinary open AP | client | 808 | 189 | **~77%** |

Controls: PC → AP gateway ping **0% loss**; head unit → PC ping **avg 584 ms / max 1551 ms** (25% loss);
head unit reported `Link speed 1 Mbps`.

## Appendix A: minimal receiver app (about 100 lines)

- Manifest: only `INTERNET` is needed (UDP receive needs nothing else).
- Bind: `DatagramSocket(null)` → `reuseAddress` → `bind(":::<port>")` (receives IPv4 and IPv6).
- Counters: `packets`, `bytes`, `lastGapMs` (time since the previous packet — this is what reveals
  "goes to zero after a few seconds"), `gaps > 200 ms`.
- Output: one log line per second **and** a file under `getExternalFilesDir()` so it can be
  `adb pull`ed afterwards.

## Appendix B: PC-side python sender

```python
#!/usr/bin/env python3
# usage: python3 udp_soak.py <head-unit-ip> [port=5001] [seconds=60]
# 600 B @ 47 pps, prints per second; Ctrl-C to stop
import socket, sys, time
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5001
dur  = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0

PKT = b'\x80\x60' + bytes(598)          # 600-byte fake RTP
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

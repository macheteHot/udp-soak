# udp-soak — head-unit UDP receive diagnostic

English edition. 中文见 [`README.md`](README.md).

A small tool to answer one question: **can the head unit reliably receive a low-rate UDP
stream?** It has two parts:

| Part | Location | Purpose |
| --- | --- | --- |
| **Android receiver** | `app/` (package `com.diplay.udptest`) | Runs on the head unit, listens on UDP `:5001`, writes its **cumulative** receive counters once per second to the screen and to a log file |
| **PC sender** | `pc/udp-sender.sh` | Sends an audio-shaped stream from your computer: **600 B/packet @ 47 pps (~0.23 Mbps)** |

Both sides keep **raw per-second counters**, so you can see exactly how much is lost and
whether it degrades or stops outright.

---

## Why

Wireless CarPlay realtime audio uses UDP (`type=102`, AAC-LC 48 kHz). Some head units
(for example Geely/Lynk FX11 / `geely_fx11` / `CS11_A4`, Android 11) show this:

- CarPlay connects and audio plays for **1–2 s, then goes silent**;
- on the head unit the `rx` counter reaches a small number and then **freezes**, with no
  `TEARDOWN`, while the iPhone keeps playing;
- yet the head unit's **uplink** (to the AP) is perfectly fine.

This tool quantifies and reproduces that: send a low-rate UDP stream over a clean unicast
link and see whether the head unit keeps receiving. If it stops after a few hundred packets,
the problem is the head unit's Wi-Fi receive path, not the app.

> Measured (iPhone Personal Hotspot, P2P torn down first): **2784 sent, 120 received
> (95.7% loss)**, reception stops after ~2.5 s; at the same moment ping head-unit→PC was 0%
> loss, PC→head-unit 100% loss, and the head unit reported RSSI **-29 dBm / 1200 Mbps**.

Full method and case notes: [`docs/DIAGNOSTIC-UDP-AUDIO-LOSS.md`](docs/DIAGNOSTIC-UDP-AUDIO-LOSS.md)
([中文](docs/诊断法-实时音频UDP丢包.md)).

---

## Build & install (Android receiver)

An Android SDK is required (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.diplay.udptest android.permission.ACCESS_FINE_LOCATION
```

`ACCESS_FINE_LOCATION` is only needed for the Wi-Fi/P2P buttons (Android 10+ requires it to
read Wi-Fi info). **Plain UDP reception works without it** — the app starts listening on launch.

## Run a test

1. **Put the head unit and your computer on the same L2 segment** (same router / hotspot /
   Wi-Fi Direct group). For wireless CarPlay, joining an ordinary AP gives the cleanest result.
   - ⚠️ On a single-radio head unit, do **not** bring up a P2P group at the same time — it
     time-shares the radio with `wlan0` and pollutes the numbers. This tool does **not** create
     a group automatically; the buttons are manual.
2. Open **UDP Test** on the head unit and note the `wlan0` address shown.
3. From the computer:

```sh
./pc/udp-sender.sh <head-unit-ip> 5001 60
```

With no arguments it sends to the current default gateway, port 5001, for 60 s.

4. Afterwards, on the head unit:

- the live counters on screen;
- the log file `/sdcard/Android/data/com.diplay.udptest/files/udptest.log` (**one line per second**).

```sh
adb pull /sdcard/Android/data/com.diplay.udptest/files/udptest.log
```

## Reading the output

Sender, one line per second:

```
t=   1.0s  sent_total=    47  this_sec= 47  errors=0
...
DONE sent=2784 errors=0 elapsed=60.0s  (13.36 Mbit)
```

Receiver, one line per second (`packets` is **cumulative**):

```
1791724607833 packets=41  bytes=24600 sinceLastMs=15  gaps>200ms=0 maxGapMs=24
1791724608850 packets=88  bytes=52800 sinceLastMs=17  gaps>200ms=0 maxGapMs=24
1791724609869 packets=120 bytes=72000 sinceLastMs=346 gaps>200ms=0 maxGapMs=26
1791724610889 packets=120 bytes=72000 sinceLastMs=1367 ...
```

How to read it:

- `packets` stops growing while `sinceLastMs` grows → **reception stopped** (it did not just slow down).
- while alive, `maxGapMs` stays small and `gaps>200ms` is 0 → the link is genuinely clean;
  the loss is a **hard stop**, not jitter.
- compare received/sent for the actual delivery rate.

## Interfaces

Two **manual** buttons reproduce the DiPlay hotspot behaviour if you need it:

- **Create Wi-Fi P2P group** (same as DiPlay, creates `p2p0`) and show SSID / passphrase;
- **Create LocalOnlyHotspot**.

There is also a "send 50 packets" button for the reverse direction (head unit → computer).

## Known limitations

- Neither side does any application-level retransmit or ACK; this measures the network itself.
- The head unit side only needs `getExternalFilesDir()` (no root).
- Some vendor ROMs throttle background Wi-Fi reception; keep the app in the foreground.

## License

[GPL-3.0](LICENSE). The material under `docs/` is taken from the
[DiPlay](https://github.com/shihabal3amri/DiPlay) head-unit compatibility notes (also GPL-3.0).

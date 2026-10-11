package com.diplay.udptest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP diagnostic + hotspot maker. It can start the same Wi-Fi P2P group (or LocalOnlyHotspot) that
 * DiPlay uses, show the group SSID/passphrase, and receive a UDP stream on port 5001 so we can tell
 * whether the head unit's Wi-Fi delivers a steady low-rate flow.
 */
public class MainActivity extends Activity {
    private static final String TAG = "UdpTest";
    private static final int PORT = 5001;
    private static final int REQ_LOC = 1;

    private TextView status;
    private TextView hotspotInfo;
    private final AtomicLong packets = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong gaps = new AtomicLong();
    private final AtomicLong maxGapMs = new AtomicLong();
    private volatile long lastMs = 0L;
    private volatile boolean running = false;
    private DatagramSocket socket;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel p2pChannel;
    private WifiManager.LocalOnlyHotspotReservation lohs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        status = new TextView(this);
        status.setTextSize(19);
        root.addView(status);

        hotspotInfo = new TextView(this);
        hotspotInfo.setTextSize(19);
        hotspotInfo.setGravity(Gravity.START);
        hotspotInfo.setText("\n(尚未创建热点)\n");
        root.addView(hotspotInfo);

        Button p2pBtn = new Button(this);
        p2pBtn.setText("① 创建 Wi-Fi P2P 组（和 DiPlay 一样）");
        p2pBtn.setOnClickListener(v -> createP2pGroup());
        root.addView(p2pBtn);

        Button p2pOff = new Button(this);
        p2pOff.setText("移除 P2P 组");
        p2pOff.setOnClickListener(v -> removeP2pGroup());
        root.addView(p2pOff);

        Button lohsBtn = new Button(this);
        lohsBtn.setText("② 或：创建 LocalOnlyHotspot");
        lohsBtn.setOnClickListener(v -> createLocalOnlyHotspot());
        root.addView(lohsBtn);

        EditText target = new EditText(this);
        target.setHint("目标 ip:port（发送用，可留空）");
        target.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(target);

        Button send = new Button(this);
        send.setText("发送 50 包 (600B @47pps)");
        send.setOnClickListener(v -> sendBurst(target.getText().toString().trim()));
        root.addView(send);

        Button reset = new Button(this);
        reset.setText("清零计数");
        reset.setOnClickListener(v -> resetCounters());
        root.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        startReceiver();
        scheduleRefresh();
        // P2P/hotspot buttons stay manual: an automatic group would steal the single radio.
    }

    // ---------------------------------------------------------------- hotspot

    private void createP2pGroup() {
        p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            hotspotInfo.setText("没有 WifiP2pManager");
            return;
        }
        p2pChannel = p2p.initialize(this, getMainLooper(), null);
        hotspotInfo.setText("正在创建 P2P 组…");
        p2p.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "p2p createGroup success");
                p2p.requestGroupInfo(p2pChannel, group -> showGroup(group));
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "p2p createGroup failed reason=" + reason + " -> showing existing group if any");
                // A group may already exist (BUSY/ERROR); surface it instead of failing.
                p2p.requestGroupInfo(p2pChannel, group -> {
                    if (group != null) {
                        showGroup(group);
                    } else {
                        hotspotInfo.setText("P2P 组创建失败 reason=" + reason
                                + "\n（检查 ACCESS_FINE_LOCATION / 车机 Wi-Fi 是否已开）");
                    }
                });
            }
        });
    }

    private void showGroup(WifiP2pGroup group) {
        if (group == null) {
            hotspotInfo.setText("P2P 组信息为空");
            return;
        }
        String ssid = group.getNetworkName();
        String pass = group.getPassphrase();
        hotspotInfo.setText("P2P 组已创建（DiPlay 同款，p2p0）\n"
                + "SSID     = " + ssid + "\n"
                + "PASSPHRASE = " + pass + "\n"
                + "本机 p2p0 地址见下（发给 <p2p0-ip>:5001）\n");
        Log.i(TAG, "p2p group ssid=" + ssid + " pass=" + pass);
    }

    private void removeP2pGroup() {
        if (p2p == null || p2pChannel == null) return;
        p2p.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { hotspotInfo.setText("P2P 组已移除"); }
            @Override public void onFailure(int reason) { hotspotInfo.setText("移除失败 reason=" + reason); }
        });
    }

    private void createLocalOnlyHotspot() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { hotspotInfo.setText("没有 WifiManager"); return; }
        hotspotInfo.setText("正在创建 LocalOnlyHotspot…");
        wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                lohs = reservation;
                WifiConfiguration c = reservation.getWifiConfiguration();
                hotspotInfo.setText("LocalOnlyHotspot 已创建\n"
                        + "SSID       = " + c.SSID + "\n"
                        + "PASSPHRASE = " + c.preSharedKey + "\n");
                Log.i(TAG, "lohs ssid=" + c.SSID + " pass=" + c.preSharedKey);
            }

            @Override
            public void onFailed(int reason) {
                hotspotInfo.setText("LocalOnlyHotspot 失败 reason=" + reason);
                Log.w(TAG, "lohs failed " + reason);
            }
        }, new Handler(Looper.getMainLooper()));
    }

    // ---------------------------------------------------------------- udp

    private void resetCounters() {
        packets.set(0); bytes.set(0); gaps.set(0); maxGapMs.set(0); lastMs = 0L;
    }

    private void startReceiver() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("::", PORT));
                Log.i(TAG, "listening udp [::]:" + PORT);
                byte[] buf = new byte[4096];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { socket.receive(p); } catch (Exception e) { if (!running) return; continue; }
                    long now = System.currentTimeMillis();
                    long n = packets.incrementAndGet();
                    bytes.addAndGet(p.getLength());
                    if (lastMs != 0L) {
                        long gap = now - lastMs;
                        if (gap > 200L) gaps.incrementAndGet();
                        if (gap > maxGapMs.get()) maxGapMs.set(gap);
                    }
                    lastMs = now;
                    if (n <= 5) {
                        Log.i(TAG, "rx #" + n + " from " + p.getAddress() + ":" + p.getPort()
                                + " len=" + p.getLength());
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "udp socket failed", e);
            }
        }, "udp-rx");
        t.setDaemon(true);
        t.start();
    }

    private void sendBurst(String target) {
        if (target.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                String[] parts = target.split(":");
                InetAddress addr = InetAddress.getByName(parts[0]);
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : PORT;
                byte[] payload = new byte[600];
                payload[0] = (byte) 0x80; payload[1] = (byte) 0x60;
                DatagramSocket s = new DatagramSocket();
                for (int i = 0; i < 50; i++) {
                    s.send(new DatagramPacket(payload, payload.length, addr, port));
                    Thread.sleep(21);
                }
                s.close();
                Log.i(TAG, "sent 50 packets to " + target);
            } catch (Throwable e) {
                Log.w(TAG, "send failed", e);
            }
        }, "udp-tx");
        t.setDaemon(true);
        t.start();
    }

    private void scheduleRefresh() {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            private int tick = 0;
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                long last = lastMs == 0L ? -1L : System.currentTimeMillis() - lastMs;
                String line = "packets=" + packets.get() + " bytes=" + bytes.get()
                        + " sinceLastMs=" + (last < 0 ? "-" : last)
                        + " gaps>200ms=" + gaps.get() + " maxGapMs=" + maxGapMs.get();
                status.setText("监听 UDP [::]:" + PORT + "\n" + line + "\n\n"
                        + "本机接口地址:\n" + ips());
                if (++tick % 2 == 0) {
                    Log.i(TAG, "STATS " + System.currentTimeMillis() + " " + line);
                    appendToFile(System.currentTimeMillis() + " " + line + "\n");
                }
                h.postDelayed(this, 500);
            }
        }, 500);
    }

    private void appendToFile(String text) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "udptest.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(text);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private String ips() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    sb.append("  ").append(ni.getName()).append(": ")
                            .append(a.getHostAddress()).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("  n/a\n");
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        if (lohs != null) { lohs.close(); lohs = null; }
        super.onDestroy();
    }
}

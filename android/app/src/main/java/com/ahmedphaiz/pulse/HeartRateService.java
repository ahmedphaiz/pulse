package com.ahmedphaiz.pulse;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Foreground service for a live workout: finds the Fitbit Air over Bluetooth (standard Heart Rate
 * profile, enabled by "Share heart rate" in the Google Health app), records heart rate even with the
 * screen off, and keeps a live, colour-coded heart-rate notification on the lock screen.
 * On Android 16+ the notification asks to be promoted to a Live Update (status-bar chip, lock screen
 * and always-on display, where the system supports it).
 */
@SuppressLint("MissingPermission")
public class HeartRateService extends Service {
    static final String ACTION_START = "pulse.START", ACTION_STOP = "pulse.STOP";
    static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final String CHANNEL = "workout_live";
    static final int NOTIF_ID = 7;

    static volatile boolean running, connected;
    static volatile String deviceName;
    static volatile int lastBpm;
    private static final List<long[]> samples = Collections.synchronizedList(new ArrayList<>());

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private PowerManager.WakeLock cpuLock;
    private String type = "WORKOUT", label = "Workout", status = "Searching for Fitbit Air…";
    private int maxHr = 187;
    private long start, lastNotif, lastSample;
    private long hrSum; private int hrCnt, hrMax;

    static final double[] ZONE_HI = {.5, .6, .7, .8, .9, 99};
    static final String[] ZONE_N = {"Rest", "Light", "Fat burn", "Cardio", "Hard", "Peak"};
    static final int[] ZONE_C = {0xFF6AA8FF, 0xFF5BE3C0, 0xFFC5F25A, 0xFFF5B84A, 0xFFFF8A3D, 0xFFFF4D5E};

    static List<long[]> samplesSince(long t) {
        List<long[]> out = new ArrayList<>();
        synchronized (samples) { for (long[] s : samples) if (s[0] > t) out.add(s); }
        return out;
    }

    int zone(int bpm) { double p = bpm / (double) maxHr; for (int i = 0; i < ZONE_HI.length; i++) if (p < ZONE_HI[i]) return i; return 5; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) { shutdown(); return START_NOT_STICKY; }
        if (intent != null) {
            type = str(intent.getStringExtra("type"), type);
            label = str(intent.getStringExtra("label"), label);
            maxHr = intent.getIntExtra("maxHr", maxHr);
            long s = intent.getLongExtra("start", 0);
            if (!running) { start = s > 0 ? s : System.currentTimeMillis(); samples.clear(); hrSum = 0; hrCnt = 0; hrMax = 0; }
        }
        startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulse:workout");
            cpuLock.acquire(6 * 3600_000L);
            adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
            if (adapter == null || !adapter.isEnabled()) setStatus(false, "Turn on Bluetooth");
            else connectOrScan();
        } else pushStatus();
        return START_STICKY;
    }

    private static String str(String s, String d) { return s == null || s.isEmpty() ? d : s; }

    // ---------- Bluetooth ----------
    private void connectOrScan() {
        if (!running) return;
        String addr = getSharedPreferences("pulse", MODE_PRIVATE).getString("addr", null);
        if (addr != null) {
            setStatus(false, "Connecting to Fitbit Air…");
            connect(adapter.getRemoteDevice(addr));
            // If the saved device doesn't answer, scan again.
            h.postDelayed(() -> { if (running && !connected) { closeGatt(); scan(); } }, 20000);
        } else scan();
    }

    private void scan() {
        if (!running || adapter == null) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus(false, "Turn on Bluetooth"); return; }
        setStatus(false, "Searching for Fitbit Air…");
        final List<ScanResult> found = new ArrayList<>();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int cb, ScanResult r) {
                String n = r.getDevice().getName();
                if (n != null && n.toLowerCase(Locale.ROOT).matches(".*(fitbit|air|google).*")) { stopScan(); connect(r.getDevice()); }
                else found.add(r);
            }
        };
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build());
        scanner.startScan(filters, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        h.postDelayed(() -> {
            if (scanCb == null || !running || connected) return;
            stopScan();
            if (!found.isEmpty()) connect(found.get(0).getDevice());
            else { setStatus(false, "Fitbit Air not found — turn on Share heart rate"); h.postDelayed(this::scan, 15000); }
        }, 8000);
    }

    private void stopScan() { try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {} scanCb = null; }

    private void connect(BluetoothDevice d) {
        if (!running) return;
        closeGatt();
        deviceName = d.getName() != null ? d.getName() : "Fitbit Air";
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() { try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {} gatt = null; }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.discoverServices(); }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus(false, "Reconnecting…");
                if (running) h.postDelayed(() -> { if (running && !connected) { if (gatt != null) gatt.connect(); h.postDelayed(() -> { if (running && !connected) connectOrScan(); }, 12000); } }, 2000);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService s = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(HR_MEASUREMENT);
            if (c == null) { setStatus(false, "This device has no heart-rate service"); g.disconnect(); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            }
            getSharedPreferences("pulse", MODE_PRIVATE).edit().putString("addr", g.getDevice().getAddress()).apply();
            setStatus(true, "Live");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { onValue(v); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onValue(c.getValue());
        }
    };

    private void onValue(byte[] v) {
        if (v == null || v.length < 2) return;
        int bpm = (v[0] & 1) != 0 && v.length >= 3 ? ((v[1] & 0xFF) | ((v[2] & 0xFF) << 8)) : (v[1] & 0xFF);
        if (bpm < 25 || bpm > 240) return;
        long now = System.currentTimeMillis();
        lastBpm = bpm;
        if (!connected) setStatus(true, "Live");
        if (now - lastSample >= 1000) { samples.add(new long[]{now, bpm}); lastSample = now; hrSum += bpm; hrCnt++; if (bpm > hrMax) hrMax = bpm; }
        MainActivity a = MainActivity.current;
        if (a != null) a.js("window.onNativeHR&&onNativeHR(" + bpm + ")");
        if (now - lastNotif > 2000) { lastNotif = now; notifyNow(); }
    }

    private void setStatus(boolean c, String s) { connected = c; status = s; pushStatus(); notifyNow(); }

    private void pushStatus() {
        MainActivity a = MainActivity.current;
        if (a != null) a.js("window.onNativeStatus&&onNativeStatus(" + connected + "," + JSONObject.quote(deviceName == null ? "" : deviceName) + "," + JSONObject.quote(status) + ")");
    }

    // ---------- Notification ----------
    private void notifyNow() { if (running) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, build()); }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live workout", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Your heart rate during a workout");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        boolean live = connected && lastBpm > 0;
        int z = live ? zone(lastBpm) : 0;
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(live ? lastBpm + " bpm · " + ZONE_N[z] : label)
            .setContentText(live ? label : status)
            .setColor(live ? ZONE_C[z] : 0xFFC5F25A)
            .setStyle(new Notification.DecoratedCustomViewStyle())
            .setCustomContentView(card(false))
            .setCustomBigContentView(card(true))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        // Android 16+ Live Updates (lock screen / status-bar chip / always-on display). Called by
        // reflection so the app still runs on older Android versions.
        call(b, "setRequestPromotedOngoing", boolean.class, true);
        call(b, "setShortCriticalText", CharSequence.class, live ? String.valueOf(lastBpm) : "--");
        return b.build();
    }

    private static final int[] SEG = {R.id.s1, R.id.s2, R.id.s3, R.id.s4, R.id.s5};
    private static final int[] LAB = {R.id.z1, R.id.z2, R.id.z3, R.id.z4, R.id.z5};

    /** WHOOP-style live card: big heart rate, running timer and a Zone 1-5 bar that lights up the current zone. */
    private RemoteViews card(boolean big) {
        RemoteViews v = new RemoteViews(getPackageName(), big ? R.layout.notif_big : R.layout.notif_small);
        boolean live = connected && lastBpm > 0;
        int z = live ? zone(lastBpm) : -1;               // 0 = rest, 1..5 = zone 1..5
        int col = live ? ZONE_C[z] : 0xFF8B929D;
        v.setTextViewText(R.id.bpm, live ? String.valueOf(lastBpm) : "--");
        v.setInt(R.id.heart, "setColorFilter", col);
        v.setTextViewText(R.id.zone, live ? (z == 0 ? "Resting" : "Zone " + z + " · " + ZONE_N[z]) : status);
        v.setTextColor(R.id.zone, col);
        long base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - (start == 0 ? System.currentTimeMillis() : start));
        v.setChronometer(R.id.timer, base, null, true);
        if (big) {
            v.setTextViewText(R.id.title, ("Pulse · " + label).toUpperCase(Locale.ROOT));
            for (int i = 0; i < 5; i++) {
                int zi = i + 1;
                boolean on = live && z == zi, passed = live && z > zi;
                v.setInt(SEG[i], "setBackgroundColor", on ? ZONE_C[zi] : passed ? (ZONE_C[zi] & 0x00FFFFFF) | 0x55000000 : 0x33FFFFFF);
                v.setTextColor(LAB[i], on ? ZONE_C[zi] : 0x80FFFFFF);
            }
            String stats = hrCnt > 0 ? "Avg " + Math.round(hrSum / (double) hrCnt) + " · Max " + hrMax + " bpm" : "";
            v.setTextViewText(R.id.status, connected ? (stats.isEmpty() ? deviceNameOr() : stats + " · " + deviceNameOr()) : (stats.isEmpty() ? status : stats + " · " + status));
        }
        return v;
    }

    private String deviceNameOr() { return deviceName == null ? "Fitbit Air" : deviceName; }

    private static void call(Object o, String name, Class<?> t, Object v) {
        try { Method m = o.getClass().getMethod(name, t); m.invoke(o, v); } catch (Exception ignored) {}
    }

    private void shutdown() {
        running = false; connected = false; lastBpm = 0;
        h.removeCallbacksAndMessages(null);
        stopScan(); closeGatt();
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        pushStatus();
    }

    @Override public void onDestroy() { if (running) shutdown(); super.onDestroy(); }
}

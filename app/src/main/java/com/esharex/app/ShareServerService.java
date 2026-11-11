package com.esharex.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareServerService extends Service {
    public static final String ACTION_MONITOR = "com.esharex.app.MONITOR";
    public static final String ACTION_STOP_MONITOR = "com.esharex.app.STOP_MONITOR";
    public static final String EXTRA_BASE_URL = "base_url";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_USER_NAME = "user_name";
    public static final String EXTRA_READY = "monitor_ready";
    private static final String HOST_CHANNEL = "esharex_host";
    private static final String MESSAGE_CHANNEL = "esharex_messages";
    private static final int FOREGROUND_ID = 11;
    private ShareHttpServer server;
    private PowerManager.WakeLock wakeLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
    private volatile String monitorBaseUrl = "";
    private volatile String monitorUserId = "";
    private volatile String monitorUserName = "";
    private volatile boolean monitorBusy = false;
    private volatile boolean monitorReady = false;
    private boolean monitorOnly = false;
    private volatile boolean destroyed = false;
    private final Runnable monitorTask = new Runnable() {
        @Override public void run() {
            if (monitorReady && !monitorBaseUrl.isEmpty() && !monitorUserId.isEmpty() && !monitorBusy) {
                monitorBusy = true;
                monitorExecutor.execute(() -> {
                    try { sendPresenceHeartbeat(); pollNotificationEvents(); } finally {
                        monitorBusy = false;
                        if (!destroyed) handler.postDelayed(this, 5000L);
                    }
                });
            } else handler.postDelayed(this, 5000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, HOST_CHANNEL)
                .setContentTitle("eShareX is active")
                .setContentText("Your eShareX connection is running")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pending).setOngoing(true).build();
        startForeground(FOREGROUND_ID, notification);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eShareX:HostLock");
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_MONITOR.equals(intent.getAction())) {
            monitorBaseUrl = ""; monitorUserId = "";
            getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().clear().putBoolean("monitor_only", monitorOnly).apply();
            handler.removeCallbacks(monitorTask);
            if (monitorOnly) stopSelf(startId);
        } else if (intent != null && ACTION_MONITOR.equals(intent.getAction())) {
            String base = intent.getStringExtra(EXTRA_BASE_URL);
            String user = intent.getStringExtra(EXTRA_USER_ID);
            String name = intent.getStringExtra(EXTRA_USER_NAME);
            if (base != null && user != null && validBaseUrl(base) && !user.trim().isEmpty()) {
                monitorBaseUrl = base.replaceAll("/+$", "");
                monitorUserId = user;
                monitorUserName = name == null ? "Member" : name;
                if (server == null) monitorOnly = true;
                getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit()
                    .putString("base_url", monitorBaseUrl).putString("user_id", monitorUserId)
                    .putString("user_name", monitorUserName).putBoolean("monitor_only", monitorOnly).apply();
                monitorReady = intent.getBooleanExtra(EXTRA_READY, false);
                handler.removeCallbacks(monitorTask);
                if (monitorReady) handler.post(monitorTask);
            }
        } else if (intent == null) {
            android.content.SharedPreferences saved = getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE);
            monitorOnly = saved.getBoolean("monitor_only", false);
            monitorBaseUrl = saved.getString("base_url", "");
            monitorUserId = saved.getString("user_id", "");
            monitorUserName = saved.getString("user_name", "Member");
            monitorReady = false; // Wait for the app to re-register before delivering events.
            if (!monitorOnly && server == null) startHostingServer();
        } else if (server == null && !monitorOnly) {
            getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().putBoolean("monitor_only", false).apply();
            startHostingServer();
        }
        return START_STICKY;
    }

    private boolean validBaseUrl(String value) {
        return value != null && value.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?");
    }

    private void startHostingServer() {
        try {
            File data = new File(getFilesDir(), "esharex");
            if (!data.exists()) data.mkdirs();
            server = new ShareHttpServer(this, data);
            server.start();
            advertiseService();
        } catch (Exception e) { stopSelf(); }
    }

    private void sendPresenceHeartbeat() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(monitorBaseUrl + "/api/presence").openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject payload = new JSONObject().put("id", monitorUserId).put("name", monitorUserName);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); out.flush(); }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void pollNotificationEvents() {
        HttpURLConnection connection = null;
        try {
            String url = monitorBaseUrl + "/api/notifications?id=" + URLEncoder.encode(monitorUserId, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) return;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray events = new JSONArray(body.toString());
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String peer = event.optString("peer", "");
                String sender = event.optString("sender", "New message");
                String message = event.optString("text", "New message");
                postMessageNotification(sender, message, peer, event.optString("kind", "direct"), event.optString("id", UUID.randomUUID().toString()));
            }
        } catch (Exception ignored) {
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void postMessageNotification(String sender, String body, String peer, String kind, String eventId) {
        android.content.SharedPreferences state = getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE);
        if (state.getBoolean("app_foreground", false)) {
            String activeKind = state.getString("active_kind", "none");
            String activePeer = state.getString("active_peer", "");
            if (("general".equals(kind) && "general".equals(activeKind)) ||
                ("direct".equals(kind) && "dm".equals(activeKind) && peer.equals(activePeer))) return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!TextUtils.isEmpty(peer)) open.putExtra("esharex_open_peer", peer);
        open.putExtra("esharex_open_title", sender);
        int requestCode = (eventId + peer).hashCode();
        PendingIntent pending = PendingIntent.getActivity(this, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, MESSAGE_CHANNEL)
                .setSmallIcon(com.esharex.app.R.drawable.ic_esharex)
                .setContentTitle("eShareX · " + sender)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true).setContentIntent(pending).build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(requestCode, notification);
    }

    private void advertiseService() {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        android.content.SharedPreferences prefs = getSharedPreferences("esharex_identity", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null || id.trim().isEmpty()) { id = "android-" + UUID.randomUUID(); prefs.edit().putString("device_id", id).apply(); }
        String suffix = id.replaceAll("[^A-Za-z0-9]", "");
        info.setServiceName("eShareX-" + suffix.substring(Math.max(0, suffix.length() - 12)));
        info.setServiceType("_esharex._tcp.");
        info.setPort(8080);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {}
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
        try { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener); }
        catch (Exception ignored) {}
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel host = new NotificationChannel(HOST_CHANNEL, "eShareX connection", NotificationManager.IMPORTANCE_LOW);
            host.setDescription("Keeps the eShareX local sharing service running");
            manager.createNotificationChannel(host);
            NotificationChannel messages = new NotificationChannel(MESSAGE_CHANNEL, "eShareX messages", NotificationManager.IMPORTANCE_HIGH);
            messages.setDescription("Alerts for new eShareX messages");
            messages.enableVibration(true);
            manager.createNotificationChannel(messages);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(monitorTask);
        monitorExecutor.shutdownNow();
        if (nsdManager != null && registrationListener != null) { try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {} }
        if (server != null) server.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

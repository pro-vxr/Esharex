package com.esharex.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
import android.content.Context;
import androidx.core.content.ContextCompat;
import java.util.UUID;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.os.Build;
import android.content.pm.PackageManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 4102;
    private static final String HOME_URL = "http://127.0.0.1:8080/";
    private String homeUrl = HOME_URL;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean pageRequested = false;
    private String pendingNotificationPeer = "";
    private String pendingNotificationKind = "";
    private String pendingNotificationTitle = "";
    private boolean notificationNavigationRetryScheduled = false;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Map<String, NsdServiceInfo> nearby = new HashMap<>();
    private WifiManager.MulticastLock multicastLock;
    private boolean autoJoinStarted = false;
    private int autoJoinAttempts = 0;
    private static final String SERVICE_TYPE = "_esharex._tcp.";
    private final ExecutorService discoveryExecutor = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean cycleRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.ArrayDeque<NsdServiceInfo> resolveQueue = new java.util.ArrayDeque<>();
    private boolean resolving = false;
    private boolean resumed = false;
    private boolean loopScheduled = false;
    private int cycleCount = 0;
    private int missingPairedCycles = 0;

    /** Runs forever while the app is open: NSD hints + LAN scan fallback, so discovery never silently gives up. */
    private final Runnable discoveryLoop = new Runnable() {
        @Override public void run() {
            if (isFinishing()) { loopScheduled = false; return; }
            triggerDiscoveryCycle();
            cycleCount++;
            boolean joinedRemote = !HOME_URL.equals(homeUrl);
            long delay = joinedRemote ? 20000L : (cycleCount < 20 ? 8000L : 30000L);
            handler.postDelayed(this, delay);
        }
    };

    private void startDiscoveryLoop() {
        if (loopScheduled) return;
        loopScheduled = true;
        handler.postDelayed(discoveryLoop, 1500L);
    }

    private void triggerDiscoveryCycle() {
        if (!resumed || isFinishing()) return;
        if (!cycleRunning.compareAndSet(false, true)) return;
        discoveryExecutor.execute(() -> {
            try { runDiscoveryCycle(); }
            catch (Exception error) { android.util.Log.w("eShareX", "Discovery cycle failed", error); }
            finally { cycleRunning.set(false); }
        });
    }

    private String httpGet(String urlString, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null && body.length() < 8192) body.append(line);
            return body.toString();
        } catch (Exception ignored) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private List<String> localIpv4Addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("v4-") || n.startsWith("clat") || n.startsWith("ppp")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0) {
                    int ip = dhcp.ipAddress;
                    out.add(String.format(java.util.Locale.US, "%d.%d.%d.%d", ip & 255, (ip >> 8) & 255, (ip >> 16) & 255, (ip >> 24) & 255));
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Probes every address of the local /24 network(s) on port 8080. Returns {host, responseBody} pairs. */
    private List<String[]> scanForServers(String path, boolean stopAtFirst, int timeoutMs) {
        final List<String[]> found = java.util.Collections.synchronizedList(new ArrayList<String[]>());
        List<String> locals = localIpv4Addresses();
        if (locals.isEmpty()) return found;
        final java.util.Set<String> localSet = new java.util.HashSet<>(locals);
        java.util.Set<String> prefixes = new java.util.LinkedHashSet<>();
        for (String ip : locals) prefixes.add(ip.substring(0, ip.lastIndexOf('.') + 1));
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(prefixes.size() * 254);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                workers.execute(() -> {
                    try {
                        if (done.get() || localSet.contains(host)) return;
                        String body = httpGet("http://" + host + ":8080" + path, timeoutMs);
                        if (body != null) { found.add(new String[]{host, body}); if (stopAtFirst) done.set(true); }
                    } finally { latch.countDown(); }
                });
            }
        }
        try { latch.await(12, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        workers.shutdownNow();
        return found;
    }

    private String deviceIdFrom(String body) {
        try { return new org.json.JSONObject(body).optString("deviceId", ""); } catch (Exception e) { return ""; }
    }

    private void addCandidate(List<String[]> candidates, java.util.Set<String> seen, String host, String port, String body) {
        if (body == null) return;
        try {
            org.json.JSONObject info = new org.json.JSONObject(body);
            String id = info.optString("deviceId", "");
            if (id.isEmpty() || !seen.add(id)) return;
            long since = info.optLong("hostSince", Long.MAX_VALUE);       // old builds never win
            if (since <= 0L) since = Long.MAX_VALUE;
            candidates.add(new String[]{"http://" + host + ":" + port + "/", id, String.valueOf(since), String.valueOf(info.optInt("messages", 0))});
        } catch (Exception ignored) {}
    }

    private boolean isOlder(long since, String id, long otherSince, String otherId) {
        return since < otherSince || (since == otherSince && id.compareTo(otherId) < 0);
    }

    private int localMessageCount() {
        String body = httpGet("http://127.0.0.1:8080/api/server-info", 900);
        if (body == null) return -1;
        try { return new org.json.JSONObject(body).optInt("messages", 0); } catch (Exception e) { return -1; }
    }

    private void runDiscoveryCycle() {
        List<String[]> candidates = new ArrayList<>();   // {url, deviceId, hostSince, messages}
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String[]> endpoints = new ArrayList<>();
        synchronized (nearby) {
            for (NsdServiceInfo info : nearby.values()) {
                String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                if (host != null && host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) endpoints.add(new String[]{host, String.valueOf(info.getPort())});
            }
        }
        for (String[] ep : endpoints) addCandidate(candidates, seen, ep[0], ep[1], httpGet("http://" + ep[0] + ":" + ep[1] + "/api/server-info", 1500));
        // The Wi-Fi scan also runs now and then, so phones are found even where NSD is blocked.
        if (candidates.isEmpty() || cycleCount % 4 == 0) {
            for (String[] hit : scanForServers("/api/server-info", false, 450)) addCandidate(candidates, seen, hit[0], "8080", hit[1]);
        }
        String localId = getStableDeviceId();
        List<String> urls = new ArrayList<>();
        for (String[] c : candidates) if (!c[1].equals(localId)) urls.add(c[0]);
        ShareServerService.setPeers(urls);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080808);
        getWindow().setNavigationBarColor(0xFF080808);
        createMessageNotificationChannel();

        android.content.SharedPreferences monitorPrefs = getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE);
        String savedHost = getSharedPreferences("esharex_connection", MODE_PRIVATE).getString("connected_url", "");
        // Every phone always uses its own chat; other phones are synced in the background.
        getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().remove("connected_url").remove("paired_host_id").apply();
        Intent service = new Intent(this, ShareServerService.class);
        if (!HOME_URL.equals(homeUrl)) {
            service.setAction(ShareServerService.ACTION_MONITOR);
            service.putExtra(ShareServerService.EXTRA_BASE_URL, homeUrl.replaceAll("/+$", ""));
            service.putExtra(ShareServerService.EXTRA_USER_ID, monitorPrefs.getString("user_id", getStableDeviceId()));
            service.putExtra(ShareServerService.EXTRA_USER_NAME, monitorPrefs.getString("user_name", "Member"));
        }
        ContextCompat.startForegroundService(this, service);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF080808);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                deliverPendingNotificationTarget(getIntent());
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (failingUrl != null && failingUrl.startsWith(homeUrl)) {
                    pageRequested = false;
                    waitForServerAndLoad();
                }
            }
        });
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidBridge");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (contentDisposition != null) {
                    java.util.regex.Matcher utf8Name = java.util.regex.Pattern.compile("filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
                    java.util.regex.Matcher plainName = java.util.regex.Pattern.compile("filename=\\\"?([^;\\\"]+)\\\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
                    try {
                        if (utf8Name.find()) filename = java.net.URLDecoder.decode(utf8Name.group(1).trim(), "UTF-8");
                        else if (plainName.find()) filename = plainName.group(1).trim();
                    } catch (Exception ignored) { }
                }
                filename = filename == null ? "esharex-download" : filename.replace("/", "_").replace("\\", "_").replaceAll("\\p{Cntrl}", "_");
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(filename);
                request.setDescription("Downloading from eShareX");
                request.setMimeType(mimeType == null ? "application/octet-stream" : mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(false);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(MainActivity.this, "Could not start download", Toast.LENGTH_SHORT).show();
            }
        });
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                    if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, FILE_REQUEST);
                } catch (RuntimeException error) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "No file picker is available", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
        setContentView(webView);
        waitForServerAndLoad();
        beginDiscovery();
        startDiscoveryLoop();
    }


    private void createMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                "esharex_messages", "eShareX messages", android.app.NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts for new eShareX messages");
            channel.enableVibration(true);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void deliverPendingNotificationTarget(Intent intent) {
        if (intent != null) {
            String peer = intent.getStringExtra("esharex_open_peer");
            String kind = intent.getStringExtra("esharex_open_kind");
            if ((peer != null && !peer.isEmpty()) || "general".equals(kind)) {
                pendingNotificationPeer = peer == null ? "" : peer;
                pendingNotificationKind = kind == null ? "direct" : kind;
                pendingNotificationTitle = intent.getStringExtra("esharex_open_title");
                if (pendingNotificationTitle == null) pendingNotificationTitle = "";
                intent.removeExtra("esharex_open_peer");
                intent.removeExtra("esharex_open_kind");
                intent.removeExtra("esharex_open_title");
            }
        }
        deliverPendingNotificationTarget();
    }

    private void deliverPendingNotificationTarget() {
        if (webView == null || pendingNotificationKind.isEmpty()) return;
        final String peer = pendingNotificationPeer;
        final String title = pendingNotificationTitle;
        String js = "(function(){if(typeof window.openNotificationConversation !== 'function')return false;" +
            "window.openNotificationConversation(" + org.json.JSONObject.quote(peer) + "," +
            org.json.JSONObject.quote(title) + ");return true;})()";
        webView.post(() -> webView.evaluateJavascript(js, value -> {
            if ("true".equals(value)) {
                pendingNotificationPeer = "";
                pendingNotificationKind = "";
                pendingNotificationTitle = "";
                notificationNavigationRetryScheduled = false;
            } else if (!notificationNavigationRetryScheduled && !isFinishing()) {
                notificationNavigationRetryScheduled = true;
                handler.postDelayed(() -> {
                    notificationNavigationRetryScheduled = false;
                    deliverPendingNotificationTarget();
                }, 300L);
            }
        }));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        deliverPendingNotificationTarget(intent);
    }

    private String getStableDeviceId() {
        android.content.SharedPreferences prefs = getSharedPreferences("esharex_identity", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null || id.trim().isEmpty()) { id = "android-" + UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).apply(); }
        return id;
    }

    private class NotificationBridge {
        @JavascriptInterface
        public String getDeviceId() { return getStableDeviceId(); }

        @JavascriptInterface
        public String getSavedDisplayName() {
            return getSharedPreferences("esharex_identity", MODE_PRIVATE).getString("display_name", "");
        }

        @JavascriptInterface
        public void saveDisplayName(String name) {
            if (name == null) return;
            String clean = name.trim();
            if (clean.length() > 32) clean = clean.substring(0, 32);
            getSharedPreferences("esharex_identity", MODE_PRIVATE).edit().putString("display_name", clean).apply();
        }

        @JavascriptInterface
        public void clearSavedDisplayName() {
            getSharedPreferences("esharex_identity", MODE_PRIVATE).edit().remove("display_name").apply();
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                try { stopService(new Intent(MainActivity.this, ShareServerService.class)); } catch (Exception ignored) {}
                stopDiscovery();
                if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
                else finish();
            });
        }

        @JavascriptInterface
        public void startDiscovery() {
            runOnUiThread(() -> { beginDiscovery(); startDiscoveryLoop(); triggerDiscoveryCycle(); });
        }

        @JavascriptInterface
        public void refreshDiscovery() { runOnUiThread(() -> { stopDiscovery(); beginDiscovery(); triggerDiscoveryCycle(); }); }

        @JavascriptInterface
        public void joinNearby(String url) {
            discoveryExecutor.execute(() -> {
                String clean = url == null ? "" : url.trim();
                if (!clean.matches("http://[0-9.]+:[0-9]{2,5}/?")) { reportJoin(false, "That address is not valid."); return; }
                String normalized = clean.endsWith("/") ? clean : clean + "/";
                String body = httpGet(normalized + "api/server-info", 2500);
                String id = body == null ? "" : deviceIdFrom(body);
                if (id.isEmpty()) { reportJoin(false, "Could not reach that device. Make sure its app is open."); return; }
                handler.post(() -> {
                    getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().putString("paired_host_id", id).apply();
                    reportJoin(true, "Connecting…");
                    joinNearbyUrl(normalized);
                });
            });
        }

        @JavascriptInterface
        public void retryHost() {
            runOnUiThread(() -> { hostFailures = 0; offlineShown = false; pageRequested = false; waitForServerAndLoad(); });
        }

        @JavascriptInterface
        public void useOwnHost() {
            runOnUiThread(() -> {
                getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().remove("connected_url").remove("paired_host_id").apply();
                recreate();
            });
        }

        @JavascriptInterface
        public boolean isHosting() {
            return HOME_URL.equals(homeUrl) && !getSharedPreferences("esharex_connection", MODE_PRIVATE).contains("paired_host_id");
        }

        @JavascriptInterface
        public String getPairCode() { return PairingCode.get(MainActivity.this); }

        @JavascriptInterface
        public void pairWithCode(String code) {
            String clean = code == null ? "" : code.replaceAll("[^0-9]", "");
            if (!clean.matches("[0-9]{6}")) { notifyPairResult(false, "Enter the six-digit code."); return; }
            scanForPairCode(clean);
        }

        @JavascriptInterface
        public void disconnectPairedHost() {
            getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().remove("paired_host_id").remove("connected_url").apply();
            runOnUiThread(() -> { stopDiscovery(); stopService(new Intent(MainActivity.this, ShareServerService.class)); finish(); });
        }

        @JavascriptInterface
        public void configureNotificationMonitor(String baseUrl, String userId, String userName) {
            if (baseUrl == null || userId == null || !baseUrl.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) return;
            Intent monitor = new Intent(MainActivity.this, ShareServerService.class);
            monitor.setAction(ShareServerService.ACTION_MONITOR);
            monitor.putExtra(ShareServerService.EXTRA_BASE_URL, baseUrl);
            monitor.putExtra(ShareServerService.EXTRA_USER_ID, userId);
            monitor.putExtra(ShareServerService.EXTRA_USER_NAME, userName == null ? "Member" : userName);
            monitor.putExtra(ShareServerService.EXTRA_READY, true);
            ContextCompat.startForegroundService(MainActivity.this, monitor);
        }

        @JavascriptInterface
        public void setActiveConversation(String kind, String peerId) {
            getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit()
                .putString("active_kind", kind == null ? "none" : kind)
                .putString("active_peer", peerId == null ? "" : peerId).apply();
        }

        @JavascriptInterface
        public void stopNotificationMonitor() {
            getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().remove("connected_url").apply();
            Intent stop = new Intent(MainActivity.this, ShareServerService.class);
            stop.setAction(ShareServerService.ACTION_STOP_MONITOR);
            startService(stop);
        }

        @JavascriptInterface
        public void requestNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5201);
                }
            });
        }

        @JavascriptInterface
        public void copyText(String value) {
            runOnUiThread(() -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("eShareX", value == null ? "" : value));
                Toast.makeText(MainActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void shareLink(String url) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "eShareX");
                send.putExtra(Intent.EXTRA_TEXT, "Join me on eShareX: " + (url == null ? homeUrl : url));
                startActivity(Intent.createChooser(send, "Share eShareX"));
            });
        }

        @JavascriptInterface
        public void showNotification(String title, String body, String peerId) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
                Intent open = new Intent(MainActivity.this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (peerId != null && !peerId.isEmpty()) open.putExtra("esharex_open_peer", peerId);
                open.putExtra("esharex_open_title", title == null ? "" : title.replace("eShareX · ", ""));
                int requestCode = peerId == null ? 0 : peerId.hashCode();
                PendingIntent pending = PendingIntent.getActivity(MainActivity.this, requestCode, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                android.app.Notification notification = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, "esharex_messages")
                    .setSmallIcon(com.esharex.app.R.drawable.ic_esharex)
                    .setContentTitle(title == null ? "eShareX" : title)
                    .setContentText(body == null ? "New message" : body)
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(body == null ? "New message" : body))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build();
                android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
            });
        }
    }

    private String serviceNameForId(String id) {
        String clean = id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "");
        return "eShareX-" + clean.substring(Math.max(0, clean.length() - 12));
    }

    private String getLocalServiceName() { return serviceNameForId(getStableDeviceId()); }

    private void notifyPairResult(boolean ok, String message) {
        if (webView == null) return;
        String js = "window.onPairResult && window.onPairResult(" + ok + "," + org.json.JSONObject.quote(message) + ")";
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }

    private void scanForPairCode(String code) {
        discoveryExecutor.execute(() -> {
            if (localIpv4Addresses().isEmpty()) { notifyPairResult(false, "Connect to the host's Wi-Fi or hotspot first."); return; }
            String path = "/api/pair/info?code=" + code;
            List<String[]> hits = scanForServers(path, true, 500);
            if (hits.isEmpty()) hits = scanForServers(path, true, 1100);   // second, slower pass for weak Wi-Fi
            String target = null, hostId = null;
            for (String[] hit : hits) {
                String id = deviceIdFrom(hit[1]);
                if (!id.isEmpty()) { target = "http://" + hit[0] + ":8080/"; hostId = id; break; }
            }
            if (target != null) {
                final String t = target;
                PairingCode.adopt(MainActivity.this, code);     // same code on every phone from now on
                ShareServerService.addPeer(t);
                handler.post(() -> notifyPairResult(true, "Connected. Your chats are syncing."));
            } else notifyPairResult(false, "No host found with that code. Check the code and that both phones share the same Wi-Fi or hotspot.");
        });
    }

    private void joinNearbyUrl(String url) {
        if (url == null || !url.matches("http://[0-9.]+:[0-9]{2,5}/?") || webView == null) return;
        String normalized = url.endsWith("/") ? url : url + "/";
        boolean paired = !getSharedPreferences("esharex_connection", MODE_PRIVATE).getString("paired_host_id", "").isEmpty();
        if (!paired) stopDiscovery();
        if (normalized.equals(homeUrl) && pageRequested) { autoJoinStarted = true; return; }
        homeUrl = normalized;
        getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().putString("connected_url", homeUrl).apply();
        stopService(new Intent(MainActivity.this, ShareServerService.class));
        pageRequested = true;
        webView.loadUrl(homeUrl);
    }

    private void beginDiscovery() {
        // A paired receiver keeps discovering its saved host so it can follow DHCP/IP changes.
        String pairedHostId = getSharedPreferences("esharex_connection", MODE_PRIVATE).getString("paired_host_id", "");
        if ((!HOME_URL.equals(homeUrl) && pairedHostId.isEmpty()) || nsdManager == null || discoveryListener != null) return;
        nearby.clear();
        autoJoinStarted = false;
        autoJoinAttempts = 0;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("eShareX:Discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
            multicastLock = null;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { publishNearby(); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                String type = service.getServiceType() == null ? "" : service.getServiceType().toLowerCase(java.util.Locale.ROOT);
                if (!type.contains("_esharex._tcp")) return;   // some Android versions drop/add the trailing dot
                if (getLocalServiceName().equals(service.getServiceName())) return;
                handler.post(() -> { resolveQueue.add(service); resolveNext(); });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                synchronized (nearby) { nearby.remove(service.getServiceName()); }
                publishNearby();
            }
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int errorCode) {
                android.util.Log.e("eShareX", "NSD discovery start failed: " + errorCode);
                stopDiscovery();
                handler.postDelayed(() -> { if (!isFinishing()) beginDiscovery(); }, 4000L);   // retry instead of giving up
            }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) {
                android.util.Log.e("eShareX", "NSD discovery stop failed: " + errorCode);
                discoveryListener = null;
                releaseMulticastLock();
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        }
        catch (Exception error) {
            android.util.Log.e("eShareX", "Could not start NSD discovery", error);
            discoveryListener = null;
            releaseMulticastLock();
        }
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsdManager == null || discoveryListener == null) return;
        final NsdServiceInfo service = resolveQueue.poll();
        resolving = true;
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    android.util.Log.w("eShareX", "NSD resolve failed: " + errorCode);
                    handler.post(() -> {
                        resolving = false;
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) resolveQueue.add(service);
                        handler.postDelayed(MainActivity.this::resolveNext, 600L);
                    });
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                    handler.post(() -> {
                        resolving = false;
                        if (host != null && !host.isEmpty() && !host.equals("127.0.0.1")) {
                            synchronized (nearby) { nearby.put(info.getServiceName(), info); }
                            publishNearby();
                            triggerDiscoveryCycle();
                        }
                        resolveNext();
                    });
                }
            });
        } catch (Exception error) {
            android.util.Log.w("eShareX", "NSD resolve request failed", error);
            resolving = false;
            handler.postDelayed(this::resolveNext, 600L);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private void publishNearby() {
        if (webView == null) return;
        List<Map<String, String>> items = new ArrayList<>();
        synchronized (nearby) {
            for (NsdServiceInfo info : nearby.values()) {
                String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                if (host.isEmpty()) continue;
                Map<String, String> item = new HashMap<>();
                item.put("name", info.getServiceName());
                item.put("host", host);
                item.put("port", String.valueOf(info.getPort()));
                items.add(item);
            }
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Map<String, String> item : items) arr.put(new org.json.JSONObject(item));
        String payload = org.json.JSONObject.quote(arr.toString());
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript("window.onNearbyDevices && window.onNearbyDevices(" + payload + ")", null); });
    }

    private void stopDiscovery() {
        resolveQueue.clear(); resolving = false;
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            discoveryListener = null;
        }
        releaseMulticastLock();
    }

    private int hostFailures = 0;
    private boolean offlineShown = false;

    private void reportJoin(boolean ok, String message) {
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript("window.onJoinResult&&window.onJoinResult(" + ok + "," + org.json.JSONObject.quote(message) + ")", null); });
    }

    private void showHostOffline() {
        if (webView == null) return;
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#070b12;color:#eaf0ff;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh}.c{padding:28px;max-width:340px;text-align:center}h1{font-size:20px;margin:0 0 8px}p{color:#98a4b8;font-size:14px;line-height:1.5;margin:0 0 20px}button{display:block;width:100%;padding:14px;border:0;border-radius:14px;font-size:15px;font-weight:700;margin-top:10px}.a{background:#b9f26a;color:#0b1209}.b{background:#161e2b;color:#eaf0ff}</style></head><body><div class='c'><h1>Host not reachable</h1><p>The phone hosting this chat is offline or on another Wi-Fi. Your own chat history is still safe on this phone.</p><button class='a' onclick='AndroidBridge.retryHost()'>Try again</button><button class='b' onclick='AndroidBridge.useOwnHost()'>Use this phone as host</button></div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void waitForServerAndLoad() {
        if (isFinishing() || pageRequested) return;
        executor.execute(() -> {
            boolean ready = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(homeUrl + "api/health").openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                ready = connection.getResponseCode() == 200;
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final boolean serverReady = ready;
            handler.post(() -> {
                if (isFinishing() || webView == null) return;
                if (serverReady && !pageRequested) {
                    pageRequested = true;
                    webView.loadUrl(homeUrl);
                } else if (!serverReady) {
                    hostFailures++;
                    if (!HOME_URL.equals(homeUrl) && hostFailures >= 2 && !offlineShown) { offlineShown = true; showHostOffline(); }
                    String paired = getSharedPreferences("esharex_connection", MODE_PRIVATE).getString("paired_host_id", "");
                    if (!paired.isEmpty() && discoveryListener == null) beginDiscovery();
                    handler.postDelayed(this::waitForServerAndLoad, 1800);
                }
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        triggerDiscoveryCycle();
        getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().putBoolean("app_foreground", true).apply();
    }

    @Override protected void onPause() {
        resumed = false;
        getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().putBoolean("app_foreground", false).apply();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (resultCode == RESULT_OK && data != null && data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        handler.removeCallbacksAndMessages(null);
        stopDiscovery();
        executor.shutdownNow();
        discoveryExecutor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

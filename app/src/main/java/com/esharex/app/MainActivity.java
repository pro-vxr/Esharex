package com.esharex.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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
    private static final String SERVICE_TYPE = "_esharex._tcp.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080808);
        getWindow().setNavigationBarColor(0xFF080808);
        createMessageNotificationChannel();

        android.content.SharedPreferences monitorPrefs = getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE);
        String savedHost = getSharedPreferences("esharex_connection", MODE_PRIVATE).getString("connected_url", "");
        if (savedHost != null && savedHost.matches("http://[0-9.]+:8080/?")) homeUrl = savedHost.endsWith("/") ? savedHost : savedHost + "/";
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
        public void startDiscovery() {
            runOnUiThread(() -> beginDiscovery());
        }

        @JavascriptInterface
        public void refreshDiscovery() { runOnUiThread(() -> { stopDiscovery(); beginDiscovery(); }); }

        @JavascriptInterface
        public void joinNearby(String url) {
            runOnUiThread(() -> {
                if (url != null && url.matches("http://[0-9.]+:8080/?")) {
                    stopDiscovery();
                    homeUrl = url.endsWith("/") ? url : url + "/";
                    getSharedPreferences("esharex_connection", MODE_PRIVATE).edit().putString("connected_url", homeUrl).apply();
                    stopService(new Intent(MainActivity.this, ShareServerService.class));
                    webView.loadUrl(homeUrl);
                }
            });
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

    private void beginDiscovery() {
        if (nsdManager == null || discoveryListener != null) return;
        nearby.clear();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { publishNearby(); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!SERVICE_TYPE.equals(service.getServiceType())) return;
                String ownName = "eShareX-" + getStableDeviceId().replaceAll("[^A-Za-z0-9]", "").replaceAll("^.*(?=.{12}$)", "");
                if (ownName.equals(service.getServiceName())) return;
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                        if (!host.isEmpty() && !host.equals("127.0.0.1")) {
                            synchronized (nearby) { nearby.put(info.getServiceName(), info); }
                            publishNearby();
                        }
                    }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                synchronized (nearby) { nearby.remove(service.getServiceName()); }
                publishNearby();
            }
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int errorCode) { stopDiscovery(); }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) { stopDiscovery(); }
        };
        try { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener); }
        catch (Exception ignored) { discoveryListener = null; }
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
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            discoveryListener = null;
        }
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
                    handler.postDelayed(this::waitForServerAndLoad, 500);
                }
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().putBoolean("app_foreground", true).apply();
    }

    @Override protected void onPause() {
        getSharedPreferences("esharex_notification_monitor", MODE_PRIVATE).edit().putBoolean("app_foreground", false).apply();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
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
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

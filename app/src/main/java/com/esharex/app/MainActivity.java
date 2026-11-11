package com.esharex.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.content.Context;
import androidx.core.content.ContextCompat;
import java.util.UUID;
import android.os.Bundle;
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
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean pageRequested = false;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Map<String, NsdServiceInfo> nearby = new HashMap<>();
    private static final String SERVICE_TYPE = "_esharex._tcp.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080808);
        getWindow().setNavigationBarColor(0xFF080808);

        Intent service = new Intent(this, ShareServerService.class);
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
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (failingUrl != null && failingUrl.startsWith(HOME_URL)) {
                    pageRequested = false;
                    waitForServerAndLoad();
                }
            }
        });
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidBridge");
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
                    stopService(new Intent(MainActivity.this, ShareServerService.class));
                    webView.loadUrl(url);
                }
            });
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
                send.putExtra(Intent.EXTRA_TEXT, "Join me on eShareX: " + (url == null ? HOME_URL : url));
                startActivity(Intent.createChooser(send, "Share eShareX"));
            });
        }

        @JavascriptInterface
        public void showNotification(String title, String body) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
                android.app.Notification notification = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, "esharex_host")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(title == null ? "eShareX" : title)
                    .setContentText(body == null ? "New message" : body)
                    .setAutoCancel(true)
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
                connection = (HttpURLConnection) new URL("http://127.0.0.1:8080/api/health").openConnection();
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
                    webView.loadUrl(HOME_URL);
                } else if (!serverReady) {
                    handler.postDelayed(this::waitForServerAndLoad, 500);
                }
            });
        });
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

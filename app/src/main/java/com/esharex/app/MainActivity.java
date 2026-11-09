package com.esharex.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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

public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 4102;
    private static final String HOME_URL = "http://127.0.0.1:8080/";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean pageRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080808);
        getWindow().setNavigationBarColor(0xFF080808);

        Intent service = new Intent(this, ShareServerService.class);
        startService(service);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF080808);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
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


    private class NotificationBridge {
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
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

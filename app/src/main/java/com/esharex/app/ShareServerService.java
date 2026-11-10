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
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.io.File;

public class ShareServerService extends Service {
    private static final String CHANNEL = "esharex_host";
    private ShareHttpServer server;
    private PowerManager.WakeLock wakeLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("eShareX is hosting")
                .setContentText("Other devices on your Wi-Fi can connect")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pending)
                .setOngoing(true).build();
        startForeground(11, notification);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eShareX:HostLock");
        wakeLock.acquire(4 * 60 * 60 * 1000L);

        try {
            File data = new File(getFilesDir(), "esharex");
            if (!data.exists()) data.mkdirs();
            server = new ShareHttpServer(this, data);
            server.start();
            advertiseService();
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void advertiseService() {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("eShareX-" + android.os.Build.MODEL.replaceAll("[^A-Za-z0-9-]", ""));
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

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "eShareX hosting", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        if (nsdManager != null && registrationListener != null) { try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {} }
        if (server != null) server.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

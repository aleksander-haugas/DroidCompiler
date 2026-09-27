package com.droidx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Compatibility shim for CHAINSCAN's C4droid JNI background bridge.
 *
 * V212h dynamically constructs <packageName>.ChainScanForegroundService and
 * calls startForegroundService() through JNI.  Hosting this service in the same
 * :sdlrunner process gives that call a real target while keeping the original
 * ChainScan C++ sources unchanged.
 */
public final class ChainScanForegroundService extends Service {
    private static final String CHANNEL = "droidx_chainscan_runner";
    private static final int ID = 6212;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "ChainScan runner", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Compatibility foreground service for ChainScan/C4droid projects");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 12, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ChainScan running")
                .setContentText("DroidCompiler compatibility foreground service")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();

        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}

package com.droidx.export;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class ChainScanForegroundService extends Service {
    private static final String CHANNEL = "chainscan_runner";
    private static final int ID = 8212;
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ChainScan", NotificationManager.IMPORTANCE_LOW));
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 12, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ExportConfig cfg = ExportConfig.load(this);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(cfg.appName + " running").setContentText("Foreground service active")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }
}

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

public final class KeepAliveService extends Service {
    private static final String CHANNEL = "droidx_export_runner";
    private static final int ID = 8101;
    public static void start(Context c) { c.startForegroundService(new Intent(c, KeepAliveService.class)); }
    public static void stop(Context c) { try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Throwable ignored) {} }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Exported C++ program", NotificationManager.IMPORTANCE_LOW));
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ExportConfig cfg = ExportConfig.load(this);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(cfg.appName).setContentText("Foreground runner is active")
                .setOngoing(true).setContentIntent(pi).build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }
}

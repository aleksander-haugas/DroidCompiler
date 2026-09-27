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

/** Foreground keep-alive that runs in the same :sdlrunner process as SDLActivity. */
public final class SdlKeepAliveService extends Service {
    private static final String CHANNEL = "droidx_sdl_runner";
    private static final int ID = 6202;

    public static void start(Context context) {
        if (!ProjectConfig.foregroundRunner(context)) return;
        context.startForegroundService(new Intent(context, SdlKeepAliveService.class));
    }

    public static void stop(Context context) {
        try { context.stopService(new Intent(context, SdlKeepAliveService.class)); }
        catch (Throwable ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DroidCompiler SDL runner", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps a user SDL/C++ program running while the IDE is backgrounded");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DroidCompiler SDL program running")
                .setContentText("Foreground runner is active")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}

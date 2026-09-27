package com.droidx;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.util.concurrent.atomic.AtomicInteger;

/** Host Android services exposed to dynamically compiled C/C++ through runnerbridge. */
public final class AndroidHostApi {
    private static final String CHANNEL = "droidx_user_programs";
    private static final AtomicInteger NEXT_NOTIFICATION = new AtomicInteger(7000);
    private static PowerManager.WakeLock cpuWakeLock;

    private AndroidHostApi() {}

    public static int sdkInt() { return Build.VERSION.SDK_INT; }

    public static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(activity)) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4101);
        }
    }

    public static int notifyNative(Context context, String title, String text) {
        if (context == null) return -1;
        if (!hasNotificationPermission(context)) return -2;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return -3;
            ensureChannel(nm);
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new android.app.Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title == null || title.isEmpty() ? "DroidCompiler program" : title)
                    .setContentText(text == null ? "" : text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text == null ? "" : text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NEXT_NOTIFICATION.incrementAndGet(), n);
            return 0;
        } catch (Throwable t) {
            return -4;
        }
    }

    public static synchronized int setCpuWakeLock(Context context, boolean enabled) {
        try {
            if (context == null) return -1;
            if (enabled) {
                if (cpuWakeLock == null) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return -2;
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidCompiler:UserProgram");
                    cpuWakeLock.setReferenceCounted(false);
                }
                if (!cpuWakeLock.isHeld()) cpuWakeLock.acquire();
            } else if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
            return 0;
        } catch (Throwable t) {
            return -3;
        }
    }

    public static int openUrl(Context context, String url) {
        try {
            if (context == null || url == null || url.trim().isEmpty()) return -1;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return 0;
        } catch (Throwable t) {
            return -2;
        }
    }

    static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DroidCompiler programs", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifications requested by programs running inside DroidCompiler");
            nm.createNotificationChannel(ch);
        }
    }
}

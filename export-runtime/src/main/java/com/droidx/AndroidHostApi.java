package com.droidx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.util.concurrent.atomic.AtomicInteger;

public final class AndroidHostApi {
    private static final String CHANNEL = "droidx_export_native";
    private static final AtomicInteger NEXT = new AtomicInteger(9000);
    private static PowerManager.WakeLock wake;
    private AndroidHostApi() {}
    public static int sdkInt() { return Build.VERSION.SDK_INT; }
    public static int notifyNative(Context c, String title, String text) {
        try {
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm==null)return -1;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"C++ notifications",NotificationManager.IMPORTANCE_DEFAULT));
            Intent open=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            PendingIntent pi=PendingIntent.getActivity(c,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title==null?"C++ program":title).setContentText(text==null?"":text).setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(NEXT.incrementAndGet(),n); return 0;
        } catch(Throwable t){return -2;}
    }
    public static synchronized int setCpuWakeLock(Context c, boolean enabled) {
        try {
            if(enabled){ if(wake==null){PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE); wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DroidXExport:Program"); wake.setReferenceCounted(false);} if(!wake.isHeld())wake.acquire(); }
            else if(wake!=null&&wake.isHeld())wake.release(); return 0;
        } catch(Throwable t){return -1;}
    }
    public static int openUrl(Context c,String url){try{Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse(url));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);return 0;}catch(Throwable t){return -1;}}
}

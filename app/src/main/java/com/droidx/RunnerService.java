package com.droidx;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class RunnerService extends Service {
    public static final String EXTRA_LIBRARY = "library";
    public static final String EXTRA_RECEIVER = "receiver";

    private static final String ACTION_INTERACTIVE = "com.droidx.RUN_INTERACTIVE";
    private static final String ACTION_STOP = "com.droidx.STOP_RUNNER";
    private static final String FGS_CHANNEL = "droidx_runner_service";
    private static final int FGS_ID = 6201;

    // Legacy completed-run result codes are kept for compatibility with RunnerEngine.
    public static final int RESULT_STARTED = 100;
    public static final int RESULT_FINISHED = 101;

    public static final int RESULT_PTY_STARTED = 200;
    public static final int RESULT_PTY_FINISHED = 201;
    public static final int RESULT_PTY_FAILED = 202;

    public static final String KEY_PTY = "pty";
    public static final String KEY_PID = "pid";
    public static final String KEY_EXIT_CODE = "exitCode";
    public static final String KEY_MESSAGE = "message";

    private volatile boolean runningInteractive;

    public static void startInteractive(Context context, String library, ResultReceiver receiver) {
        Intent i = new Intent(context, RunnerService.class);
        i.setAction(ACTION_INTERACTIVE);
        i.putExtra(EXTRA_LIBRARY, library);
        i.putExtra(EXTRA_RECEIVER, receiver);
        if (ProjectConfig.foregroundRunner(context)) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void requestStop(Context context) {
        try { context.stopService(new Intent(context, RunnerService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        finishForegroundIfNeeded();
        if (runningInteractive) {
            // The user program may be blocked in native code; STOP means terminate only :runner.
            Process.killProcess(Process.myPid());
            return;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            // This service lives in the dedicated :runner process. Killing this PID
            // is exactly the desired STOP semantics and cannot take down the editor.
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }

        if (ACTION_INTERACTIVE.equals(intent.getAction())) {
            if (ProjectConfig.foregroundRunner(this)) ensureForeground();
            startInteractiveInternal(intent, startId);
            return START_NOT_STICKY;
        }

        // Legacy non-interactive path (kept for RunnerEngine compatibility).
        startLegacy(intent, startId);
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(FGS_CHANNEL, "DroidCompiler runner", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps a user C/C++ program running while DroidCompiler is in the background");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, FGS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DroidCompiler program running")
                .setContentText("Foreground runner is active")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        startForeground(FGS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private void finishForegroundIfNeeded() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
    }

    private void startInteractiveInternal(Intent intent, int startId) {
        final String library = intent.getStringExtra(EXTRA_LIBRARY);
        final ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        if (receiver == null || library == null) {
            stopSelf(startId);
            return;
        }
        if (runningInteractive) {
            sendFailure(receiver, "A console program is already running in :runner.");
            return;
        }
        runningInteractive = true;

        try {
            RunnerBridge.configureRuntimeEnvironment(this);
            RuntimePackageLoader.preloadForConsole(this);
            int masterFd = RunnerBridge.nativeCreatePty();
            if (masterFd < 0) {
                runningInteractive = false;
                sendFailure(receiver, "openpty() failed: " + RunnerBridge.nativeLastError());
                finishForegroundIfNeeded();
                stopSelf(startId);
                return;
            }

            ParcelFileDescriptor localMaster = ParcelFileDescriptor.adoptFd(masterFd);
            Bundle started = new Bundle();
            started.putInt(KEY_PID, Process.myPid());
            started.putParcelable(KEY_PTY, localMaster);
            receiver.send(RESULT_PTY_STARTED, started);
            // Binder transfers a duplicate descriptor to the UI process.
            try { localMaster.close(); } catch (Throwable ignored) {}

            new Thread(() -> {
                int exitCode = Integer.MIN_VALUE;
                String status = "";
                try {
                    status = RunnerBridge.nativeRunPty(library);
                    exitCode = RunnerBridge.nativeLastExitCode();
                } catch (Throwable t) {
                    status = "RUNNER FAILED: " + t;
                }

                Bundle finished = new Bundle();
                finished.putInt(KEY_EXIT_CODE, exitCode);
                finished.putString(KEY_MESSAGE, status == null ? "" : status);
                try { receiver.send(RESULT_PTY_FINISHED, finished); } catch (Throwable ignored) {}
                runningInteractive = false;
                finishForegroundIfNeeded();
                stopSelf(startId);
            }, "droidx-pty-user-program").start();
        } catch (Throwable t) {
            runningInteractive = false;
            sendFailure(receiver, "Interactive runner failed: " + t);
            finishForegroundIfNeeded();
            stopSelf(startId);
        }
    }

    private void sendFailure(ResultReceiver receiver, String message) {
        Bundle b = new Bundle();
        b.putString(KEY_MESSAGE, message);
        try { receiver.send(RESULT_PTY_FAILED, b); } catch (Throwable ignored) {}
    }

    private void startLegacy(Intent intent, int startId) {
        final String library = intent.getStringExtra(EXTRA_LIBRARY);
        final ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        if (receiver == null || library == null) {
            stopSelf(startId);
            return;
        }

        Bundle started = new Bundle();
        started.putInt("pid", Process.myPid());
        receiver.send(RESULT_STARTED, started);

        new Thread(() -> {
            String result;
            try {
                RunnerBridge.configureRuntimeEnvironment(this);
                RuntimePackageLoader.preloadForConsole(this);
                File outDir = new File(getFilesDir(), "runner");
                //noinspection ResultOfMethodCallIgnored
                outDir.mkdirs();
                File stdout = new File(outDir, "stdout-" + Process.myPid() + ".txt");
                String nativeStatus = RunnerBridge.nativeRun(library, stdout.getAbsolutePath());
                String captured = stdout.isFile()
                        ? new String(java.nio.file.Files.readAllBytes(stdout.toPath()), StandardCharsets.UTF_8)
                        : "";
                result = "RUN (:runner pid " + Process.myPid() + ")\n" + library + "\n\n" +
                        captured + (captured.endsWith("\n") || captured.isEmpty() ? "" : "\n") +
                        "\n" + nativeStatus + "\n";
            } catch (Throwable t) {
                result = "RUNNER FAILED\n" + t + "\n";
            }
            Bundle b = new Bundle();
            b.putString("output", result);
            try { receiver.send(RESULT_FINISHED, b); } catch (Throwable ignored) {}
            finishForegroundIfNeeded();
            stopSelf(startId);
        }, "droidx-user-program").start();
    }
}

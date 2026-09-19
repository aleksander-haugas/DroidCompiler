package com.droidx;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.ResultReceiver;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class RunnerService extends Service {
    public static final String EXTRA_LIBRARY = "library";
    public static final String EXTRA_RECEIVER = "receiver";
    public static final int RESULT_STARTED = 100;
    public static final int RESULT_FINISHED = 101;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        final String library = intent.getStringExtra(EXTRA_LIBRARY);
        final ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        if (receiver == null || library == null) { stopSelf(startId); return START_NOT_STICKY; }

        Bundle started = new Bundle();
        started.putInt("pid", Process.myPid());
        receiver.send(RESULT_STARTED, started);

        new Thread(() -> {
            String result;
            try {
                RunnerBridge.configureRuntimeEnvironment(this);
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
            stopSelf(startId);
        }, "droidx-user-program").start();

        return START_NOT_STICKY;
    }
}

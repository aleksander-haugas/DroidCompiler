package com.droidx;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent breadcrumbs for the separate :sdlrunner process. */
final class RunnerDiagnostics {
    private static final String TAG = "DroidX-SDL";
    private static final Object LOCK = new Object();

    private RunnerDiagnostics() {}

    static File logFile(Context context) {
        return new File(context.getFilesDir(), "droidx_sdl_runner.log");
    }

    static void reset(Context context) {
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(logFile(context), false)) {
                String header = "DroidCompiler SDL runner diagnostics\n" +
                        "time=" + stamp() + "\n" +
                        "sdk=" + Build.VERSION.SDK_INT + "\n" +
                        "abis=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS) + "\n" +
                        "process=" + android.app.Application.getProcessName() + "\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
    }

    static void log(Context context, String message) {
        String line = stamp() + "  " + message;
        Log.e(TAG, line);
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(logFile(context), true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Throwable ignored) {}
        }
    }

    static String read(Context context) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(logFile(context).toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "Could not read runner log: " + t;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}

package com.droidx;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RunnerEngine {
    private static final AtomicReference<RunState> CURRENT = new AtomicReference<>();

    private RunnerEngine() {}

    private static final class RunState {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger pid = new AtomicInteger(-1);
        final AtomicReference<String> output = new AtomicReference<>("RUNNER DID NOT RETURN OUTPUT\n");
    }

    public static String run(Context context) {
        File program = ProjectStore.activeProgramLibrary(context);
        File source = ProjectStore.mainCpp(context);
        if (program == null || !program.isFile()) return "NO BUILD\n\nPress BUILD first.";
        if (source.isFile() && source.lastModified() > program.lastModified()) {
            return "SOURCE CHANGED\n\nmain.cpp is newer than the build. Press BUILD first.";
        }

        RunState state = new RunState();
        if (!CURRENT.compareAndSet(null, state)) return "A program is already running. Press STOP first.";

        ResultReceiver receiver = new ResultReceiver((Handler) null) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (resultCode == RunnerService.RESULT_STARTED) {
                    state.pid.set(data.getInt("pid", -1));
                } else if (resultCode == RunnerService.RESULT_FINISHED) {
                    state.output.set(data.getString("output", "RUN finished without output\n"));
                    state.done.countDown();
                }
            }
        };

        try {
            Intent i = new Intent(context, RunnerService.class);
            i.putExtra(RunnerService.EXTRA_LIBRARY, program.getAbsolutePath());
            i.putExtra(RunnerService.EXTRA_RECEIVER, receiver);
            context.startService(i);

            // Keep RUN synchronous for the current UI worker thread. STOP can terminate
            // the separate :runner process without taking down the editor process.
            while (true) {
                if (state.done.await(250, TimeUnit.MILLISECONDS)) break;
                int pid = state.pid.get();
                if (pid > 0 && !isPidAlive(context, pid)) {
                    state.output.set("RUNNER PROCESS TERMINATED\n\nThe user program probably crashed or called exit().\nPID: " + pid + "\n");
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    state.output.set("RUN interrupted.\n");
                    break;
                }
            }
            return state.output.get();
        } catch (Throwable t) {
            return "RUN FAILED\n" + t + "\n";
        } finally {
            CURRENT.compareAndSet(state, null);
        }
    }

    public static void stop() {
        RunState state = CURRENT.getAndSet(null);
        if (state == null) return;
        int pid = state.pid.get();
        if (pid > 0) {
            try { android.os.Process.killProcess(pid); } catch (Throwable ignored) {}
        }
        state.output.set("STOPPED\n");
        state.done.countDown();
    }

    private static boolean isPidAlive(Context c, int pid) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return true;
        List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
        if (ps == null) return true;
        for (ActivityManager.RunningAppProcessInfo p : ps) if (p.pid == pid) return true;
        return false;
    }
}

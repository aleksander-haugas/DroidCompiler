package com.droidx;

import android.app.Activity;
import android.content.Context;

import java.io.File;

final class RunnerBridge {
    static {
        System.loadLibrary("runnerbridge");
    }

    private RunnerBridge() {}

    static native String nativeRun(String libraryPath, String outputPath);
    static native int nativeCreatePty();
    static native String nativeRunPty(String libraryPath);
    static native String nativeLastError();
    static native int nativeLastExitCode();
    static native String nativeProbeSharedObject(String libraryPath, String symbol);
    private static native void nativeConfigureRuntime(Context context, String prefix, String home, String tmp, String caBundle, String projectDir, String assetDir);
    private static native void nativeSetHostActivity(Activity activity);

    static void configureRuntimeEnvironment(Context context) {
        File home = new File(context.getFilesDir(), "home");
        File tmp = new File(context.getFilesDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        File ca = ToolchainManager.curlCaBundle(context);
        nativeConfigureRuntime(
                context,
                ToolchainManager.prefix(context).getAbsolutePath(),
                home.getAbsolutePath(),
                tmp.getAbsolutePath(),
                ca.isFile() ? ca.getAbsolutePath() : "",
                ProjectStore.projectDir(context).getAbsolutePath(),
                ProjectStore.assetsDir(context).getAbsolutePath());
    }

    static void setHostActivity(Activity activity) {
        nativeSetHostActivity(activity);
    }
}


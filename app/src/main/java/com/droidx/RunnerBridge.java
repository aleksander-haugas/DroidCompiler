package com.droidx;

import android.content.Context;

import java.io.File;

final class RunnerBridge {
    static {
        System.loadLibrary("runnerbridge");
    }

    private RunnerBridge() {}

    static native String nativeRun(String libraryPath, String outputPath);
    private static native void nativeConfigureRuntime(String prefix, String home, String tmp, String caBundle);

    static void configureRuntimeEnvironment(Context context) {
        File home = new File(context.getFilesDir(), "home");
        File tmp = new File(context.getFilesDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        File ca = ToolchainManager.curlCaBundle(context);
        nativeConfigureRuntime(
                ToolchainManager.prefix(context).getAbsolutePath(),
                home.getAbsolutePath(),
                tmp.getAbsolutePath(),
                ca.isFile() ? ca.getAbsolutePath() : "");
    }
}

package com.droidx;

import android.app.Activity;
import android.content.Context;
import java.io.File;

public final class RunnerBridge {
    static { System.loadLibrary("runnerbridge"); }
    private RunnerBridge() {}
    public static native String nativeRun(String libraryPath, String outputPath);
    private static native void nativeConfigureRuntime(Context context, String prefix, String home, String tmp, String caBundle, String projectDir, String assetDir);
    private static native void nativeSetHostActivity(Activity activity);

    public static void configureRuntimeEnvironment(Context c, File project, File assets) {
        File home = new File(c.getFilesDir(), "home"); home.mkdirs();
        File tmp = new File(c.getFilesDir(), "tmp"); tmp.mkdirs();
        File prefix = new File(c.getFilesDir(), "usr"); prefix.mkdirs();
        nativeConfigureRuntime(c, prefix.getAbsolutePath(), home.getAbsolutePath(), tmp.getAbsolutePath(), "", project.getAbsolutePath(), assets.getAbsolutePath());
    }
    public static void setHostActivity(Activity a) { nativeSetHostActivity(a); }
}

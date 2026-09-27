package com.droidx;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** Preloads optional package runtimes from the private prefix before dlopen(user artifact). */
public final class RuntimePackageLoader {
    private RuntimePackageLoader() {}

    public static void preloadForConsole(Context context) {
        ToolchainManager.markPackageNativeFilesReadOnly(context);
        OptionalPackageManager.preloadActivatedRuntimes(context);
    }

    public static void loadSDL2(Context context) {
        if (!OptionalPackageManager.isInstalled(context, OptionalPackageManager.SDL2)) {
            throw new UnsatisfiedLinkError("SDL2 package is not installed. Open Settings > Optional dependencies > SDL2.");
        }
        File sdl = OptionalPackageManager.runtimeLibrary(context, OptionalPackageManager.SDL2);
        if (sdl == null || !sdl.isFile()) {
            throw new UnsatisfiedLinkError("SDL2 runtime library is missing from " + ToolchainManager.packageLibDir(context));
        }
        ToolchainManager.markPackageNativeFilesReadOnly(context);
        ToolchainManager.makeReadOnly(sdl);
        Log.i("DroidCompiler", "Loading optional SDL2 runtime: " + sdl);
        System.load(sdl.getAbsolutePath());
    }
}

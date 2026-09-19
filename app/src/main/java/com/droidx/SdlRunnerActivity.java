package com.droidx;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.List;

/** Graphical RUN surface for dynamically compiled SDL2/OpenGL ES programs. */
public final class SdlRunnerActivity extends SDLActivity {
    public static final String EXTRA_LIBRARY = "com.droidx.extra.SDL_PROGRAM_LIBRARY";
    private String programLibrary;

    public static Intent createIntent(Context context, File library) {
        Intent i = new Intent(context, SdlRunnerActivity.class);
        i.putExtra(EXTRA_LIBRARY, library.getAbsolutePath());
        return i;
    }

    public static void stopProcess(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return;
            String wanted = context.getPackageName() + ":sdlrunner";
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (wanted.equals(p.processName)) {
                    android.os.Process.killProcess(p.pid);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        programLibrary = getIntent().getStringExtra(EXTRA_LIBRARY);
        if (programLibrary == null || programLibrary.isEmpty() || !new File(programLibrary).isFile()) {
            finish();
            return;
        }
        RunnerBridge.configureRuntimeEnvironment(this);
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ProjectConfig.requestedOrientation(this));
    }

    @Override
    protected String[] getLibraries() {
        // The user artifact is loaded by SDL nativeRunMain() from the absolute
        // path returned by getMainSharedObject(), so it is not a System.loadLibrary name.
        return new String[] { "SDL2" };
    }

    @Override
    protected String getMainSharedObject() {
        return programLibrary;
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected String[] getArguments() {
        return new String[] { "droidx-sdl" };
    }
}

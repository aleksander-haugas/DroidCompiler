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
        return createPreflightIntent(context, library);
    }

    static Intent createPreflightIntent(Context context, File library) {
        Intent i = new Intent(context, SdlPreflightActivity.class);
        i.putExtra(EXTRA_LIBRARY, library.getAbsolutePath());
        return i;
    }

    static Intent createDirectIntent(Context context, File library) {
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
        RunnerDiagnostics.log(this, "SDL_ACTIVITY_ONCREATE_ENTER");
        programLibrary = getIntent().getStringExtra(EXTRA_LIBRARY);
        if (programLibrary == null || programLibrary.isEmpty() || !new File(programLibrary).isFile()) {
            finish();
            return;
        }
        RunnerDiagnostics.log(this, "SDL_ACTIVITY_PROGRAM_OK " + programLibrary);
        // Keep SDL's JNI runtime load order deterministic. Loading runnerbridge first
        // can map libSDL2 as a dependency before SDLActivity performs its own setup.
        try {
            System.loadLibrary("SDL2");
            RunnerDiagnostics.log(this, "SDL_ACTIVITY_SDL2_PRELOADED");
        } catch (Throwable t) {
            RunnerDiagnostics.log(this, "SDL_ACTIVITY_SDL2_PRELOAD_FAIL " + t);
            finish();
            return;
        }
        RunnerBridge.configureRuntimeEnvironment(this);
        RunnerBridge.setHostActivity(this);
        RunnerDiagnostics.log(this, "SDL_ACTIVITY_BEFORE_SUPER");
        super.onCreate(savedInstanceState);
        RunnerDiagnostics.log(this, "SDL_ACTIVITY_AFTER_SUPER");
        setRequestedOrientation(ProjectConfig.requestedOrientation(this));
        if (ProjectConfig.foregroundRunner(this)) SdlKeepAliveService.start(this);
    }

    @Override
    protected void onDestroy() {
        RunnerDiagnostics.log(this, "SDL_ACTIVITY_ONDESTROY finishing=" + isFinishing());
        try { RunnerBridge.setHostActivity(null); } catch (Throwable ignored) {}
        if (isFinishing()) SdlKeepAliveService.stop(this);
        super.onDestroy();
    }

    @Override
    protected String[] getLibraries() {
        RunnerDiagnostics.log(this, "SDL_GET_LIBRARIES");
        // The user artifact is loaded by SDL nativeRunMain() from the absolute
        // path returned by getMainSharedObject(), so it is not a System.loadLibrary name.
        return new String[] { "SDL2" };
    }

    @Override
    protected String getMainSharedObject() {
        RunnerDiagnostics.log(this, "SDL_GET_MAIN_SO " + programLibrary);
        return programLibrary;
    }

    @Override
    protected String getMainFunction() {
        RunnerDiagnostics.log(this, "SDL_GET_MAIN_FUNCTION SDL_main");
        return "SDL_main";
    }

    @Override
    protected String[] getArguments() {
        return new String[] { "droidx-sdl" };
    }
}

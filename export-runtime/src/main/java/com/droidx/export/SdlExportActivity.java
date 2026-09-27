package com.droidx.export;

import android.os.Bundle;
import org.libsdl.app.SDLActivity;
import com.droidx.RunnerBridge;

import java.io.File;

public final class SdlExportActivity extends SDLActivity {
    private ExportConfig cfg;

    @Override protected void onCreate(Bundle state) {
        cfg = ExportConfig.load(this);
        AssetBootstrap.extractProjectAssets(this);
        if (cfg.runnerBridge) {
            RunnerBridge.configureRuntimeEnvironment(this, AssetBootstrap.projectDir(this), AssetBootstrap.assetDir(this));
            RunnerBridge.setHostActivity(this);
        }
        super.onCreate(state);
        setRequestedOrientation(cfg.requestedOrientation());
        if (cfg.foreground) KeepAliveService.start(this);
    }

    @Override protected void onDestroy() {
        if (cfg != null && cfg.runnerBridge) {
            try { RunnerBridge.setHostActivity(null); } catch (Throwable ignored) {}
        }
        if (isFinishing()) KeepAliveService.stop(this);
        super.onDestroy();
    }

    @Override protected String[] getLibraries() {
        return cfg != null && cfg.runnerBridge
                ? new String[]{"SDL2", "runnerbridge"}
                : new String[]{"SDL2"};
    }

    @Override protected String getMainSharedObject() {
        return new File(getApplicationInfo().nativeLibraryDir, "libprogram.so").getAbsolutePath();
    }

    @Override protected String getMainFunction() { return "SDL_main"; }
    @Override protected String[] getArguments() { return new String[]{"droidx-export"}; }
}

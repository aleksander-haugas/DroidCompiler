package com.droidx.export;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import com.droidx.RunnerBridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ConsoleExportActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Starting C++ program…");
        view.setTextColor(Color.rgb(220, 230, 220));
        view.setBackgroundColor(Color.rgb(10, 12, 15));
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(13);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
        ExportConfig cfg = ExportConfig.load(this);
        setRequestedOrientation(cfg.requestedOrientation());
        AssetBootstrap.extractProjectAssets(this);
        RunnerBridge.configureRuntimeEnvironment(this, AssetBootstrap.projectDir(this), AssetBootstrap.assetDir(this));
        RunnerBridge.setHostActivity(this);
        if (cfg.foreground) KeepAliveService.start(this);

        new Thread(() -> {
            String text;
            try {
                File program = new File(getApplicationInfo().nativeLibraryDir, "libprogram.so");
                File out = new File(getFilesDir(), "program-output.txt");
                String status = RunnerBridge.nativeRun(program.getAbsolutePath(), out.getAbsolutePath());
                String output = out.isFile() ? new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8) : "";
                text = output + (output.endsWith("\n") || output.isEmpty() ? "" : "\n") + status;
            } catch (Throwable t) { text = "RUN FAILED\n" + t; }
            final String result = text;
            runOnUiThread(() -> view.setText(result));
        }, "DroidX-export-main").start();
    }

    @Override protected void onDestroy() {
        try { RunnerBridge.setHostActivity(null); } catch (Throwable ignored) {}
        if (isFinishing()) KeepAliveService.stop(this);
        super.onDestroy();
    }
}

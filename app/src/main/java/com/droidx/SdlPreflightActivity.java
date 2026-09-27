package com.droidx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * Runs in :sdlrunner and validates the dynamically built program before SDLActivity
 * starts its native thread. This turns silent linker/process deaths into an actionable
 * on-screen diagnostic and also establishes the native library load order explicitly.
 */
public final class SdlPreflightActivity extends Activity {
    public static final String EXTRA_LIBRARY = SdlRunnerActivity.EXTRA_LIBRARY;
    private File program;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RunnerDiagnostics.reset(this);
        RunnerDiagnostics.log(this, "PREFLIGHT_ACTIVITY_CREATE");

        String path = getIntent().getStringExtra(EXTRA_LIBRARY);
        program = path == null ? null : new File(path);
        if (program == null || !program.isFile()) {
            fail("Program library is missing: " + path);
            return;
        }

        RunnerDiagnostics.log(this, "PROGRAM=" + program.getAbsolutePath());
        RunnerDiagnostics.log(this, "PROGRAM_SIZE=" + program.length());
        RunnerDiagnostics.log(this, "PROGRAM_READ=" + program.canRead() + " WRITE=" + program.canWrite() + " EXEC=" + program.canExecute());

        String elf = inspectElf(program);
        RunnerDiagnostics.log(this, elf);
        if (elf.startsWith("ELF_ERROR")) {
            fail(elf);
            return;
        }

        try {
            RunnerDiagnostics.log(this, "LOAD_SDL2_BEGIN");
            System.loadLibrary("SDL2");
            RunnerDiagnostics.log(this, "LOAD_SDL2_OK");
        } catch (Throwable t) {
            RunnerDiagnostics.log(this, "LOAD_SDL2_FAIL " + t);
            fail("Could not load libSDL2.so\n\n" + t);
            return;
        }

        try {
            RunnerDiagnostics.log(this, "LOAD_RUNNERBRIDGE_BEGIN");
            // Touching RunnerBridge loads librunnerbridge.so and gives the probe a JNI host.
            RunnerBridge.configureRuntimeEnvironment(this);
            RunnerBridge.setHostActivity(this);
            RunnerDiagnostics.log(this, "LOAD_RUNNERBRIDGE_OK");
        } catch (Throwable t) {
            RunnerDiagnostics.log(this, "LOAD_RUNNERBRIDGE_FAIL " + t);
            fail("Could not load/configure librunnerbridge.so\n\n" + t);
            return;
        }

        String probe;
        try {
            RunnerDiagnostics.log(this, "DLOPEN_PROGRAM_BEGIN");
            probe = RunnerBridge.nativeProbeSharedObject(program.getAbsolutePath(), "SDL_main");
            RunnerDiagnostics.log(this, "DLOPEN_RESULT " + probe.replace('\n', ' '));
        } catch (Throwable t) {
            RunnerDiagnostics.log(this, "DLOPEN_PROBE_JNI_FAIL " + t);
            fail("Native preflight itself failed\n\n" + t);
            return;
        }

        if (probe == null || !probe.startsWith("OK")) {
            fail("SDL program failed native preflight:\n\n" + probe);
            return;
        }

        RunnerDiagnostics.log(this, "PREFLIGHT_OK_LAUNCH_SDL_ACTIVITY");
        Intent i = SdlRunnerActivity.createDirectIntent(this, program);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        try { RunnerBridge.setHostActivity(null); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void fail(String reason) {
        RunnerDiagnostics.log(this, "PREFLIGHT_FAIL " + reason.replace('\n', ' '));
        setTitle("DroidCompiler SDL preflight failed");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.rgb(8, 12, 18));

        TextView title = new TextView(this);
        title.setText("SDL RUNTIME PRECHECK FAILED");
        title.setTextColor(Color.rgb(255, 95, 95));
        title.setTextSize(18f);
        title.setPadding(0, 0, 0, 18);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(reason + "\n\n--- runner log ---\n" + RunnerDiagnostics.read(this));
        body.setTextColor(Color.rgb(220, 230, 238));
        body.setTextSize(13f);
        body.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp);

        Button close = new Button(this);
        close.setText("BACK TO DROIDCOMPILER");
        close.setOnClickListener(v -> finish());
        root.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private static String inspectElf(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[20];
            if (in.read(h) != h.length) return "ELF_ERROR short file";
            if ((h[0] & 0xff) != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return "ELF_ERROR bad magic";
            int klass = h[4] & 0xff;
            int endian = h[5] & 0xff;
            int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
            String m = machine == 183 ? "AARCH64" : machine == 62 ? "X86_64" : ("MACHINE_" + machine);
            String expected = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
            if (klass != 2 || endian != 1) return "ELF_ERROR unsupported ELF class/endian class=" + klass + " endian=" + endian;
            if (("arm64-v8a".equals(expected) && machine != 183) || ("x86_64".equals(expected) && machine != 62)) {
                return "ELF_ERROR ABI mismatch expected=" + expected + " file=" + m;
            }
            return "ELF_OK class=64 endian=LE machine=" + m;
        } catch (Throwable t) {
            return "ELF_ERROR " + t;
        }
    }
}

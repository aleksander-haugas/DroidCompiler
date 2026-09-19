package com.droidx;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText editor;
    private TextView console;
    private Button installButton;
    private Button templateButton;
    private Button networkButton;
    private Button buildButton;
    private Button runButton;
    private Button stopButton;
    private Button apkButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder installLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProjectConfig.ensureDefaults(this);
        setContentView(buildUi());
        loadOrCreateSource();
        console.setText(ToolchainManager.status(this));
    }

    @Override
    protected void onDestroy() {
        RunnerEngine.stop();
        SdlRunnerActivity.stopProcess(this);
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackgroundColor(Color.rgb(17, 19, 24));

        TextView title = new TextView(this);
        title.setText("DroidCompiler 0.6.0 — Runtime Suite");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(4), dp(2), dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(ToolchainManager.deviceAbiLabel() + " · C++20 · SDL2 · GLES2/3 · TCP/UDP · libcurl HTTP/HTTPS/WS/WSS");
        subtitle.setTextColor(Color.rgb(140, 155, 180));
        subtitle.setTextSize(12);
        subtitle.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(subtitle);

        TextView fileName = new TextView(this);
        fileName.setText("HelloCpp / src / main.cpp");
        fileName.setTextColor(Color.rgb(175, 185, 205));
        fileName.setTextSize(13);
        fileName.setPadding(dp(2), 0, dp(2), dp(5));
        root.addView(fileName);

        editor = new EditText(this);
        editor.setTextColor(Color.rgb(225, 230, 240));
        editor.setHintTextColor(Color.GRAY);
        editor.setBackgroundColor(Color.rgb(25, 28, 34));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setPadding(dp(10), dp(10), dp(10), dp(10));
        editor.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(true);
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f));

        installButton = makeButton("INSTALL TOOLCHAIN DATA");
        installButton.setOnClickListener(v -> installToolchain());
        root.addView(installButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        templateButton = makeButton("LOAD GLES3 + TOUCH DEMO");
        templateButton.setOnClickListener(v -> {
            editor.setText(DemoTemplates.SDL_GLES_TOUCH_SAMPLE);
            console.setText("GLES3 + SDL2 touch demo loaded.\n\nPress BUILD, then RUN.\nRUN will open the graphical SDL surface without creating an APK.\n");
        });
        root.addView(templateButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        networkButton = makeButton("LOAD NETWORK DEMO");
        networkButton.setOnClickListener(v -> {
            editor.setText(DemoTemplates.NETWORK_SAMPLE);
            console.setText("Network demo loaded.\n\nIt performs real HTTPS, TCP, UDP DNS and WSS echo tests.\nPress BUILD, then RUN.\n");
        });
        root.addView(networkButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(8));

        buildButton = makeButton("BUILD");
        runButton = makeButton("RUN ▶");
        stopButton = makeButton("STOP ■");
        apkButton = makeButton("APK");

        row.addView(buildButton, weighted());
        row.addView(runButton, weighted());
        row.addView(stopButton, weighted());
        row.addView(apkButton, weighted());
        root.addView(row);

        buildButton.setOnClickListener(v -> buildProject());
        runButton.setOnClickListener(v -> runProject());
        stopButton.setOnClickListener(v -> {
            RunnerEngine.stop();
            SdlRunnerActivity.stopProcess(this);
            appendConsole("\nSTOP requested for console/SDL runner.\n");
        });
        apkButton.setOnClickListener(v -> {
            console.setText("APK EXPORT\n\nNot connected in v0.5 yet.\n\n" +
                    "Current RUN modes:\n" +
                    "Console: libprogram.so -> :runner\n" +
                    "SDL2/GLES: libprogram.so -> SDLActivity in :sdlrunner\nNetwork: TCP/UDP via Bionic sockets; HTTP/HTTPS/WS/WSS via embedded libcurl\n\n" +
                    "Next phase will export the same SDL2 runtime + user library as an APK.");
        });

        TextView consoleTitle = new TextView(this);
        consoleTitle.setText("CONSOLE");
        consoleTitle.setTextColor(Color.rgb(180, 190, 210));
        consoleTitle.setTypeface(Typeface.DEFAULT_BOLD);
        consoleTitle.setTextSize(12);
        root.addView(consoleTitle);

        console = new TextView(this);
        console.setTextColor(Color.rgb(190, 240, 190));
        console.setBackgroundColor(Color.rgb(8, 10, 13));
        console.setTypeface(Typeface.MONOSPACE);
        console.setTextSize(11);
        console.setPadding(dp(10), dp(10), dp(10), dp(10));
        console.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(console);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.72f));

        return root;
    }

    private void loadOrCreateSource() {
        try {
            File main = ProjectStore.mainCpp(this);
            if (main.isFile()) {
                byte[] data = java.nio.file.Files.readAllBytes(main.toPath());
                editor.setText(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                editor.setText(DemoTemplates.CONSOLE_SAMPLE);
                ProjectStore.saveMainCpp(this, DemoTemplates.CONSOLE_SAMPLE);
            }
        } catch (Throwable t) {
            editor.setText(DemoTemplates.CONSOLE_SAMPLE);
            console.setText("Could not load project: " + t);
        }
    }

    private void installToolchain() {
        if (ToolchainManager.isReady(this)) {
            console.setText(ToolchainManager.status(this));
            Toast.makeText(this, "Clang is ready", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        installLog.setLength(0);
        console.setText("Installing compiler/sysroot/SDL2/libcurl development data...\n" +
                "ABI: " + ToolchainManager.deviceAbiLabel() + "\n" +
                "Clang/LLD, libSDL2.so, libcurl.so and TLS dependencies are embedded in the APK.\n" +
                "This step installs C/C++, SDL2 and libcurl headers plus the CA certificate bundle.\n\n");

        worker.submit(() -> {
            try {
                ToolchainManager.install(this, new ToolchainManager.Listener() {
                    @Override
                    public void onLog(String message) {
                        synchronized (installLog) {
                            installLog.append(message).append('\n');
                            if (installLog.length() > 24000) {
                                installLog.delete(0, installLog.length() - 18000);
                            }
                            final String snapshot = installLog.toString();
                            runOnUiThread(() -> console.setText(snapshot));
                        }
                    }

                    @Override
                    public void onProgress(int current, int total, String packageName) {
                        runOnUiThread(() -> installButton.setText(
                                "INSTALLING " + current + "/" + total + " · " + packageName));
                    }
                });
                runOnUiThread(() -> {
                    setBusy(false);
                    installButton.setText("TOOLCHAIN READY ✓");
                    appendConsole("\n" + ToolchainManager.status(this));
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false);
                    installButton.setText("RETRY INSTALL TOOLCHAIN DATA");
                    appendConsole("\nINSTALL FAILED\n" + stackText(t));
                });
            }
        });
    }

    private void buildProject() {
        final String source = editor.getText().toString();
        setBusy(true);
        console.setText("Saving main.cpp...\nStarting real clang++...\n\n");

        worker.submit(() -> {
            try {
                ProjectStore.saveMainCpp(this, source);
                BuildResult r = CompilerEngine.build(this);
                runOnUiThread(() -> {
                    console.setText(r.output);
                    setBusy(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    console.setText("BUILD ERROR\n" + stackText(t));
                    setBusy(false);
                });
            }
        });
    }

    private void runProject() {
        final String source = editor.getText().toString();
        setBusy(true);
        console.setText("Preparing RUN...\n\n");

        worker.submit(() -> {
            try {
                ProjectStore.saveMainCpp(this, source);

                File program = ProjectStore.activeProgramLibrary(this);
                File sourceFile = ProjectStore.mainCpp(this);
                if (program == null || !program.isFile() || sourceFile.lastModified() > program.lastModified()) {
                    BuildResult r = CompilerEngine.build(this);
                    if (!r.ok) {
                        runOnUiThread(() -> {
                            console.setText(r.output);
                            setBusy(false);
                        });
                        return;
                    }
                    program = ProjectStore.activeProgramLibrary(this);
                    final String buildLog = r.output;
                    runOnUiThread(() -> console.setText(buildLog + "\nRUNNING...\n\n"));
                }

                if (CompilerEngine.usesSDL2(source)) {
                    final File graphicalProgram = program;
                    runOnUiThread(() -> {
                        try {
                            Intent intent = SdlRunnerActivity.createIntent(this, graphicalProgram);
                            startActivity(intent);
                            console.setText("SDL2 / OpenGL ES RUN\n\n" +
                                    "Program: " + graphicalProgram + "\n" +
                                    "Process: :sdlrunner\n" +
                                    "Entry: SDL_main\n\n" +
                                    "The graphical surface is now open. Use Android Back to return to the editor.\n");
                        } catch (Throwable t) {
                            console.setText("SDL RUN ERROR\n" + stackText(t));
                        } finally {
                            setBusy(false);
                        }
                    });
                    return;
                }

                String output = RunnerEngine.run(this);
                runOnUiThread(() -> {
                    console.setText(output);
                    setBusy(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    console.setText("RUN ERROR\n" + stackText(t));
                    setBusy(false);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        installButton.setEnabled(!busy);
        templateButton.setEnabled(!busy);
        networkButton.setEnabled(!busy);
        buildButton.setEnabled(!busy);
        runButton.setEnabled(!busy);
        apkButton.setEnabled(!busy);
        // STOP remains available while a program is running.
        stopButton.setEnabled(true);
    }

    private void appendConsole(String s) {
        console.append(s);
    }

    private static String stackText(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

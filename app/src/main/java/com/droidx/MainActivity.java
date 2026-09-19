package com.droidx;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 16, 21);
    private static final int PANEL = Color.rgb(22, 25, 32);
    private static final int PANEL_2 = Color.rgb(27, 31, 39);
    private static final int TEXT = Color.rgb(232, 236, 244);
    private static final int MUTED = Color.rgb(145, 157, 181);
    private static final int ACCENT = Color.rgb(102, 211, 145);
    private static final int BLUE = Color.rgb(92, 154, 245);
    private static final int RED = Color.rgb(230, 95, 105);
    private static final int AMBER = Color.rgb(234, 184, 92);

    private EditText editor;
    private TextView statusText;
    private TextView abiChip;
    private Button installButton;
    private Button examplesButton;
    private Button buildButton;
    private Button runButton;
    private Button stopButton;
    private Button apkButton;
    private Button logButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder fullLog = new StringBuilder();
    private final StringBuilder installLog = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProjectConfig.ensureDefaults(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        loadOrCreateSource();

        String initialStatus = ToolchainManager.status(this);
        appendLog("DroidCompiler 0.7.0 UI\n" + initialStatus);
        if (ToolchainManager.isReady(this)) {
            installButton.setText("Toolchain ✓");
            setStatus("Ready · C++20 toolchain available", ACCENT);
        } else {
            setStatus("Toolchain data required", AMBER);
        }
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
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(8));

        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("DroidCompiler");
        title.setTextColor(TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerText.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("C++20 · SDL2 · OpenGL ES · Network");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(11.5f);
        headerText.addView(subtitle);

        header.addView(headerText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        abiChip = chip(ToolchainManager.deviceAbiLabel());
        header.addView(abiChip);
        root.addView(header);

        LinearLayout fileBar = new LinearLayout(this);
        fileBar.setOrientation(LinearLayout.HORIZONTAL);
        fileBar.setGravity(Gravity.CENTER_VERTICAL);
        fileBar.setPadding(dp(10), dp(7), dp(8), dp(7));
        fileBar.setBackground(roundRect(PANEL, dp(9), Color.rgb(42, 47, 58), 1));

        TextView fileName = new TextView(this);
        fileName.setText("HelloCpp  /  src  /  main.cpp");
        fileName.setTextColor(Color.rgb(191, 201, 219));
        fileName.setTextSize(12.5f);
        fileName.setTypeface(Typeface.MONOSPACE);
        fileBar.addView(fileName, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView cppChip = chip("C++20");
        fileBar.addView(cppChip);
        root.addView(fileBar);

        editor = new EditText(this);
        editor.setTextColor(TEXT);
        editor.setHintTextColor(Color.GRAY);
        editor.setBackground(roundRect(PANEL_2, dp(10), Color.rgb(48, 54, 66), 1));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(true);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        editorParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(editor, editorParams);

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        secondaryRow.setPadding(0, 0, 0, dp(6));

        installButton = makeButton("Toolchain", PANEL_2, TEXT);
        examplesButton = makeButton("Examples", PANEL_2, TEXT);
        installButton.setOnClickListener(v -> {
            if (ToolchainManager.isReady(this)) {
                showToolchainStatus();
            } else {
                installToolchain();
            }
        });
        examplesButton.setOnClickListener(v -> showExamples());

        secondaryRow.addView(installButton, weighted());
        secondaryRow.addView(examplesButton, weighted());
        root.addView(secondaryRow);

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        primaryRow.setPadding(0, 0, 0, dp(7));

        buildButton = makeButton("BUILD", Color.rgb(45, 64, 87), TEXT);
        runButton = makeButton("RUN ▶", Color.rgb(38, 91, 63), TEXT);
        stopButton = makeButton("STOP ■", Color.rgb(82, 45, 52), TEXT);
        apkButton = makeButton("APK", Color.rgb(61, 51, 82), TEXT);

        primaryRow.addView(buildButton, weighted());
        primaryRow.addView(runButton, weighted());
        primaryRow.addView(stopButton, weighted());
        primaryRow.addView(apkButton, weighted());
        root.addView(primaryRow);

        buildButton.setOnClickListener(v -> buildProject());
        runButton.setOnClickListener(v -> runProject());
        stopButton.setOnClickListener(v -> stopProgram());
        apkButton.setOnClickListener(v -> apkInfo());

        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(10), dp(5), dp(5), dp(5));
        statusBar.setBackground(roundRect(PANEL, dp(9), Color.rgb(41, 46, 57), 1));

        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(12);
        statusText.setSingleLine(true);
        statusText.setText("Ready");
        statusBar.addView(statusText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        logButton = makeButton("LOG", Color.rgb(35, 39, 48), Color.rgb(188, 199, 220));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(dp(72), dp(38));
        logParams.setMargins(dp(6), 0, 0, 0);
        statusBar.addView(logButton, logParams);
        logButton.setOnClickListener(v -> showLogDialog());

        root.addView(statusBar);
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
            appendLog("Could not load project:\n" + stackText(t));
            setStatus("Project load error · View log", RED);
        }
    }

    private void showExamples() {
        String[] items = {
                "Console C++",
                "SDL2 + GLES3 + Touch",
                "Network: HTTPS / TCP / UDP / WebSocket"
        };
        new AlertDialog.Builder(this)
                .setTitle("Load example")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        editor.setText(DemoTemplates.CONSOLE_SAMPLE);
                        setStatus("Console example loaded · BUILD then RUN", BLUE);
                        appendLog("Loaded Console C++ example.");
                    } else if (which == 1) {
                        editor.setText(DemoTemplates.SDL_GLES_TOUCH_SAMPLE);
                        setStatus("GLES3 + Touch example loaded · BUILD then RUN", BLUE);
                        appendLog("Loaded SDL2 + GLES3 + Touch example.");
                    } else {
                        editor.setText(DemoTemplates.NETWORK_SAMPLE);
                        setStatus("Network example loaded · BUILD then RUN", BLUE);
                        appendLog("Loaded Network example.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showToolchainStatus() {
        String text = ToolchainManager.status(this);
        appendLog("TOOLCHAIN STATUS\n" + text);
        new AlertDialog.Builder(this)
                .setTitle("Toolchain")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("View log", (d, w) -> showLogDialog())
                .show();
    }

    private void installToolchain() {
        if (ToolchainManager.isReady(this)) {
            installButton.setText("Toolchain ✓");
            setStatus("Toolchain ready", ACCENT);
            Toast.makeText(this, "Clang is ready", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        installLog.setLength(0);
        appendLog("INSTALL TOOLCHAIN DATA\nABI: " + ToolchainManager.deviceAbiLabel());
        setStatus("Installing toolchain data…", AMBER);

        worker.submit(() -> {
            try {
                ToolchainManager.install(this, new ToolchainManager.Listener() {
                    @Override
                    public void onLog(String message) {
                        synchronized (installLog) {
                            installLog.append(message).append('\n');
                            if (installLog.length() > 32000) {
                                installLog.delete(0, installLog.length() - 24000);
                            }
                        }
                        appendLog(message);
                    }

                    @Override
                    public void onProgress(int current, int total, String packageName) {
                        runOnUiThread(() -> {
                            installButton.setText(current + "/" + total + " · " + shortName(packageName));
                            setStatus("Installing " + packageName + "…", AMBER);
                        });
                    }
                });
                runOnUiThread(() -> {
                    setBusy(false);
                    installButton.setText("Toolchain ✓");
                    String status = ToolchainManager.status(this);
                    appendLog(status);
                    setStatus("Toolchain ready · " + ToolchainManager.deviceAbiLabel(), ACCENT);
                });
            } catch (Throwable t) {
                appendLog("INSTALL FAILED\n" + stackText(t));
                runOnUiThread(() -> {
                    setBusy(false);
                    installButton.setText("Retry toolchain");
                    setStatus("Toolchain install failed · View log", RED);
                    Toast.makeText(this, "Install failed — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void buildProject() {
        final String source = editor.getText().toString();
        setBusy(true);
        setStatus("Building main.cpp…", BLUE);
        appendLog("BUILD START\nSaving main.cpp and starting embedded clang++.");

        worker.submit(() -> {
            try {
                ProjectStore.saveMainCpp(this, source);
                BuildResult r = CompilerEngine.build(this);
                appendLog(r.output);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (r.ok) {
                        File out = ProjectStore.activeProgramLibrary(this);
                        String extra = out != null && out.isFile()
                                ? " · " + humanBytes(out.length())
                                : "";
                        setStatus("Build successful" + extra, ACCENT);
                    } else {
                        setStatus("Build failed · View log", RED);
                        Toast.makeText(this, "Build failed — open LOG", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                appendLog("BUILD ERROR\n" + stackText(t));
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Build error · View log", RED);
                    Toast.makeText(this, "Build error — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void runProject() {
        final String source = editor.getText().toString();
        setBusy(true);
        setStatus("Preparing RUN…", BLUE);
        appendLog("RUN REQUEST");

        worker.submit(() -> {
            try {
                ProjectStore.saveMainCpp(this, source);

                File program = ProjectStore.activeProgramLibrary(this);
                File sourceFile = ProjectStore.mainCpp(this);
                if (program == null || !program.isFile() || sourceFile.lastModified() > program.lastModified()) {
                    appendLog("Source changed; rebuilding before RUN.");
                    BuildResult r = CompilerEngine.build(this);
                    appendLog(r.output);
                    if (!r.ok) {
                        runOnUiThread(() -> {
                            setStatus("Run blocked: build failed · View log", RED);
                            setBusy(false);
                            Toast.makeText(this, "Build failed — open LOG", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    program = ProjectStore.activeProgramLibrary(this);
                }

                if (CompilerEngine.usesSDL2(source)) {
                    final File graphicalProgram = program;
                    runOnUiThread(() -> {
                        try {
                            Intent intent = SdlRunnerActivity.createIntent(this, graphicalProgram);
                            startActivity(intent);
                            appendLog("SDL2 / OpenGL ES RUN\nProgram: " + graphicalProgram +
                                    "\nProcess: :sdlrunner\nEntry: SDL_main");
                            setStatus("SDL2 / OpenGL ES running · Back returns to editor", ACCENT);
                        } catch (Throwable t) {
                            appendLog("SDL RUN ERROR\n" + stackText(t));
                            setStatus("SDL run error · View log", RED);
                        } finally {
                            setBusy(false);
                        }
                    });
                    return;
                }

                String output = RunnerEngine.run(this);
                appendLog(output);
                runOnUiThread(() -> {
                    setStatus("Run finished · Output available in LOG", ACCENT);
                    setBusy(false);
                });
            } catch (Throwable t) {
                appendLog("RUN ERROR\n" + stackText(t));
                runOnUiThread(() -> {
                    setStatus("Run error · View log", RED);
                    setBusy(false);
                    Toast.makeText(this, "Run error — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void stopProgram() {
        RunnerEngine.stop();
        SdlRunnerActivity.stopProcess(this);
        appendLog("STOP requested for console / SDL runner.");
        setStatus("Stop requested", AMBER);
    }

    private void apkInfo() {
        appendLog("APK EXPORT requested. Exporter is not connected yet.");
        setStatus("APK exporter is the next implementation phase", AMBER);
        Toast.makeText(this, "APK exporter is not connected yet", Toast.LENGTH_SHORT).show();
    }

    private void showLogDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(12));
        layout.setBackgroundColor(Color.rgb(17, 19, 24));

        TextView title = new TextView(this);
        title.setText("Build & Runtime Log");
        title.setTextColor(TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        layout.addView(title);

        TextView logText = new TextView(this);
        logText.setText(snapshotLog());
        logText.setTextColor(Color.rgb(194, 220, 196));
        logText.setBackgroundColor(Color.rgb(8, 10, 13));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText);
        layout.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        Button copy = makeButton("Copy", PANEL_2, TEXT);
        Button clear = makeButton("Clear", PANEL_2, TEXT);
        Button close = makeButton("Close", Color.rgb(38, 91, 63), TEXT);
        row.addView(copy, weighted());
        row.addView(clear, weighted());
        row.addView(close, weighted());
        layout.addView(row);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("DroidCompiler log", snapshotLog()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        clear.setOnClickListener(v -> {
            synchronized (fullLog) {
                fullLog.setLength(0);
            }
            logText.setText("Log cleared.\n");
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(layout);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.82f);
            w.setAttributes(lp);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setBusy(boolean busy) {
        installButton.setEnabled(!busy);
        examplesButton.setEnabled(!busy);
        buildButton.setEnabled(!busy);
        runButton.setEnabled(!busy);
        apkButton.setEnabled(!busy);
        stopButton.setEnabled(true);
        logButton.setEnabled(true);
        editor.setEnabled(!busy);
    }

    private void setStatus(String text, int color) {
        if (statusText == null) return;
        statusText.setText("●  " + text);
        statusText.setTextColor(color);
    }

    private void appendLog(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (fullLog) {
            fullLog.append('\n')
                    .append('[').append(clock.format(new Date())).append("] ")
                    .append(text);
            if (!text.endsWith("\n")) fullLog.append('\n');
            if (fullLog.length() > 180000) {
                fullLog.delete(0, fullLog.length() - 140000);
            }
        }
    }

    private String snapshotLog() {
        synchronized (fullLog) {
            if (fullLog.length() == 0) return "No log entries yet.\n";
            return fullLog.toString();
        }
    }

    private Button makeButton(String text, int background, int foreground) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(42));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(roundRect(background, dp(9), Color.rgb(55, 61, 73), 1));
        return b;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(181, 194, 217));
        t.setTextSize(10.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setBackground(roundRect(Color.rgb(34, 38, 47), dp(20), Color.rgb(55, 61, 73), 1));
        return t;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    private String shortName(String s) {
        if (s == null) return "";
        return s.length() <= 18 ? s : s.substring(0, 16) + "…";
    }

    private static String stackText(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

package com.droidx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Interactive terminal for console programs.
 *
 * The user library still runs in the isolated :runner process. RunnerService
 * attaches it to a real Android/Bionic PTY and transfers the PTY master file
 * descriptor to this Activity. This gives the user program a real tty-backed
 * stdin/stdout/stderr instead of a completed-run text file.
 */
public final class ConsoleActivity extends Activity {
    private static final int BG = Color.rgb(9, 11, 15);
    private static final int PANEL = Color.rgb(16, 19, 25);
    private static final int BORDER = Color.rgb(44, 50, 61);
    private static final int TEXT = Color.rgb(219, 232, 220);
    private static final int MUTED = Color.rgb(137, 151, 171);
    private static final int ACCENT = Color.rgb(102, 211, 145);
    private static final int RED = Color.rgb(230, 95, 105);
    private static final int BLUE = Color.rgb(92, 154, 245);

    private static final String EXTRA_LIBRARY = "library";
    private static final int MAX_TERMINAL_CHARS = 240_000;
    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    private TextView terminal;
    private ScrollView terminalScroll;
    private EditText command;
    private TextView stateText;
    private Button sendButton;
    private Button stopButton;

    private ParcelFileDescriptor readPfd;
    private ParcelFileDescriptor writePfd;
    private InputStream terminalIn;
    private OutputStream terminalOut;
    private volatile boolean readerRunning;
    private volatile boolean finished;
    private final ExecutorService ioWorker = Executors.newFixedThreadPool(2);
    private final StringBuilder screen = new StringBuilder();

    public static Intent createIntent(Context context, File library) {
        Intent i = new Intent(context, ConsoleActivity.class);
        i.putExtra(EXTRA_LIBRARY, library.getAbsolutePath());
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ProjectConfig.requestedOrientation(this));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());

        String library = getIntent().getStringExtra(EXTRA_LIBRARY);
        if (library == null || !new File(library).isFile()) {
            appendTerminal("DroidCompiler console\n\nNo runnable build was supplied.\n");
            setState("No program", RED);
            setInputEnabled(false);
            return;
        }

        appendTerminal("DroidCompiler Interactive Console\n");
        appendTerminal("Program: " + library + "\n");
        appendTerminal("Process: :runner · PTY terminal\n\n");
        startRunner(library);
    }

    @Override
    protected void onDestroy() {
        readerRunning = false;
        closeTerminalFds();
        ioWorker.shutdownNow();
        if (!finished && isFinishing()) {
            RunnerService.requestStop(this);
        }
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(9));
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(2), 0, dp(2), dp(8));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Console");
        title.setTextColor(Color.rgb(235, 239, 246));
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        stateText = new TextView(this);
        stateText.setText("Starting runner…");
        stateText.setTextColor(MUTED);
        stateText.setTextSize(11.5f);
        titleBox.addView(stateText);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = button("CLEAR", Color.rgb(30, 35, 44));
        clear.setOnClickListener(v -> {
            synchronized (screen) { screen.setLength(0); }
            terminal.setText("");
        });
        top.addView(clear, new LinearLayout.LayoutParams(dp(78), dp(40)));
        root.addView(top);

        terminal = new TextView(this);
        terminal.setTextColor(TEXT);
        terminal.setTextSize(12.5f);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setGravity(Gravity.TOP | Gravity.START);
        terminal.setTextIsSelectable(true);
        terminal.setPadding(dp(11), dp(10), dp(11), dp(10));
        terminal.setBackground(roundRect(PANEL, dp(9), BORDER, 1));

        terminalScroll = new ScrollView(this);
        terminalScroll.setFillViewport(true);
        terminalScroll.addView(terminal, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams termParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(terminalScroll, termParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(8), 0, 0);

        command = new EditText(this);
        command.setSingleLine(true);
        command.setTextColor(Color.rgb(236, 239, 245));
        command.setHintTextColor(Color.rgb(105, 116, 135));
        command.setHint("stdin / command…");
        command.setTextSize(13);
        command.setTypeface(Typeface.MONOSPACE);
        command.setPadding(dp(10), 0, dp(10), 0);
        command.setBackground(roundRect(Color.rgb(21, 25, 32), dp(8), BORDER, 1));
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setImeOptions(EditorInfo.IME_ACTION_SEND);
        command.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (enter) {
                sendCommand();
                return true;
            }
            return false;
        });
        inputRow.addView(command, new LinearLayout.LayoutParams(0, dp(44), 1f));

        sendButton = button("SEND", Color.rgb(37, 88, 62));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(78), dp(44));
        sendParams.setMargins(dp(6), 0, 0, 0);
        inputRow.addView(sendButton, sendParams);
        sendButton.setOnClickListener(v -> sendCommand());

        stopButton = button("STOP", Color.rgb(88, 43, 51));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(78), dp(44));
        stopParams.setMargins(dp(6), 0, 0, 0);
        inputRow.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> {
            RunnerService.requestStop(this);
            appendTerminal("\n[DroidCompiler] STOP requested.\n");
            setState("Stopping…", RED);
            setInputEnabled(false);
        });

        root.addView(inputRow);
        return root;
    }

    private void startRunner(String library) {
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                if (resultCode == RunnerService.RESULT_PTY_STARTED) {
                    ParcelFileDescriptor pfd = data.getParcelable(RunnerService.KEY_PTY, ParcelFileDescriptor.class);
                    int pid = data.getInt(RunnerService.KEY_PID, -1);
                    if (pfd == null) {
                        appendTerminal("[DroidCompiler] Runner started without a PTY.\n");
                        setState("PTY unavailable", RED);
                        return;
                    }
                    attachPty(pfd);
                    setState("Running · PID " + pid + " · stdin/stdout/stderr attached", ACCENT);
                    setInputEnabled(true);
                    command.requestFocus();
                } else if (resultCode == RunnerService.RESULT_PTY_FAILED) {
                    String message = data.getString(RunnerService.KEY_MESSAGE, "Runner failed");
                    appendTerminal("\n[DroidCompiler] " + message + "\n");
                    setState("Run failed", RED);
                    setInputEnabled(false);
                    finished = true;
                } else if (resultCode == RunnerService.RESULT_PTY_FINISHED) {
                    finished = true;
                    int code = data.getInt(RunnerService.KEY_EXIT_CODE, Integer.MIN_VALUE);
                    String status = data.getString(RunnerService.KEY_MESSAGE, "");
                    if (!status.isEmpty()) appendTerminal("\n[DroidCompiler] " + status + "\n");
                    if (code != Integer.MIN_VALUE) {
                        appendTerminal("[DroidCompiler] EXIT_CODE=" + code + "\n");
                        setState("Finished · exit " + code, code == 0 ? ACCENT : RED);
                    } else {
                        setState("Runner finished", MUTED);
                    }
                    setInputEnabled(false);
                }
            }
        };

        RunnerService.startInteractive(this, library, receiver);
    }

    private void attachPty(ParcelFileDescriptor transferred) {
        closeTerminalFds();
        try {
            readPfd = transferred;
            writePfd = ParcelFileDescriptor.dup(readPfd.getFileDescriptor());
            terminalIn = new ParcelFileDescriptor.AutoCloseInputStream(readPfd);
            terminalOut = new ParcelFileDescriptor.AutoCloseOutputStream(writePfd);
            readerRunning = true;
            ioWorker.submit(this::readLoop);
        } catch (Throwable t) {
            appendTerminal("\n[DroidCompiler] Could not attach PTY: " + t + "\n");
            setState("PTY attach failed", RED);
            closeTerminalFds();
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[4096];
        try {
            while (readerRunning && terminalIn != null) {
                int n = terminalIn.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                String text = new String(buffer, 0, n, StandardCharsets.UTF_8);
                runOnUiThread(() -> appendTerminal(text));
            }
        } catch (IOException e) {
            if (readerRunning && !finished) {
                runOnUiThread(() -> appendTerminal("\n[DroidCompiler] terminal stream closed: " + e.getMessage() + "\n"));
            }
        } finally {
            readerRunning = false;
        }
    }

    private void sendCommand() {
        String text = command.getText().toString();
        if (terminalOut == null || finished) {
            Toast.makeText(this, "No running console program", Toast.LENGTH_SHORT).show();
            return;
        }
        command.setText("");
        ioWorker.submit(() -> {
            try {
                // PTY canonical mode turns the newline into a normal terminal input line.
                byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
                synchronized (this) {
                    if (terminalOut != null) {
                        terminalOut.write(bytes);
                        terminalOut.flush();
                    }
                }
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    appendTerminal("\n[DroidCompiler] stdin write failed: " + t + "\n");
                    setState("stdin unavailable", RED);
                });
            }
        });
    }

    private void appendTerminal(String raw) {
        if (raw == null || raw.isEmpty()) return;
        // This is an interactive PTY, but not yet a full VT100 renderer. Strip ANSI
        // control sequences so ordinary colored CLI output remains readable.
        String text = ANSI.matcher(raw).replaceAll("");
        synchronized (screen) {
            screen.append(text);
            if (screen.length() > MAX_TERMINAL_CHARS) {
                screen.delete(0, screen.length() - 180_000);
            }
            terminal.setText(screen.toString());
        }
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setInputEnabled(boolean enabled) {
        command.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        stopButton.setEnabled(!finished);
    }

    private void setState(String text, int color) {
        stateText.setText(text);
        stateText.setTextColor(color);
    }

    private void closeTerminalFds() {
        readerRunning = false;
        try { if (terminalIn != null) terminalIn.close(); } catch (Throwable ignored) {}
        try { if (terminalOut != null) terminalOut.close(); } catch (Throwable ignored) {}
        try { if (readPfd != null) readPfd.close(); } catch (Throwable ignored) {}
        try { if (writePfd != null) writePfd.close(); } catch (Throwable ignored) {}
        terminalIn = null;
        terminalOut = null;
        readPfd = null;
        writePfd = null;
    }

    private Button button(String text, int background) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(234, 238, 245));
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(roundRect(background, dp(8), BORDER, 1));
        return b;
    }

    private GradientDrawable roundRect(int color, int radius, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

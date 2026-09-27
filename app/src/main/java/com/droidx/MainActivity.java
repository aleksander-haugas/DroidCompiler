package com.droidx;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT_ASSET = 4201;
    private static final int REQ_OPEN_PROJECT_TREE = 4202;
    private static final int REQ_SAVE_APK = 4203;
    private static final int REQ_EXPORT_ICON = 4204;
    private static final int BG = Color.rgb(14, 16, 21);
    private static final int PANEL = Color.rgb(22, 25, 32);
    private static final int PANEL_2 = Color.rgb(27, 31, 39);
    private static final int TEXT = Color.rgb(232, 236, 244);
    private static final int MUTED = Color.rgb(145, 157, 181);
    private static final int ACCENT = Color.rgb(102, 211, 145);
    private static final int BLUE = Color.rgb(92, 154, 245);
    private static final int RED = Color.rgb(230, 95, 105);
    private static final int AMBER = Color.rgb(234, 184, 92);

    private CodeEditorView editor;
    private TextView statusText;
    private TextView fileNameText;
    private TextView footerFileText;
    private TextView projectSubtitle;
    private TextView explorerProjectText;
    private EditText explorerFilter;
    private File currentFile;
    private Button buildButton;
    private Button runButton;
    private Button stopButton;
    private Button apkButton;
    private Button logButton;
    private Button editorTabButton;
    private Button settingsTabButton;
    private FrameLayout pageHost;
    private View editorPage;
    private View settingsPage;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private LinearLayout explorerList;
    private View explorerPane;
    private LinearLayout outputPanel;
    private ScrollView outputScroll;
    private TextView outputText;
    private Button outputToggleButton;
    private Button fontDownButton;
    private Button fontUpButton;
    private final List<File> openTabs = new ArrayList<>();
    private final Set<String> expandedExplorerDirs = new LinkedHashSet<>();
    private boolean outputExpanded = false;
    private boolean wideLayout = false;
    private boolean uiBusy = false;
    private float editorFontSp = 13.5f;
    private FrameLayout shellRoot;
    private FrameLayout drawerOverlay;
    private LinearLayout drawerPanel;
    private Button menuButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder fullLog = new StringBuilder();
    private final StringBuilder installLog = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private File lastExportedApk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProjectManager.ensureDefault(this);
        // A fresh app session intentionally starts with no workspace selected.
        // Rotation/configuration recreation keeps the current project, but a new
        // launch never silently re-opens an old project.
        if (savedInstanceState == null) ProjectManager.clearActiveProject(this);
        editorFontSp = getSharedPreferences("ui", MODE_PRIVATE).getFloat("editor_font_sp", 13.5f);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        if (ProjectManager.hasActiveProject(this)) loadActiveProject();
        else showBlankWorkspace(false);

        String initialStatus = ToolchainManager.status(this);
        appendLog("DroidCompiler 1.6.8 Tree Explorer\n" + initialStatus);
        if (ToolchainManager.isReady(this)) {
            setStatus(ProjectManager.hasActiveProject(this)
                    ? "Ready · Core C/C++ toolchain available"
                    : "Ready · Open or create a project", ACCENT);
        } else {
            setStatus("Core toolchain setup required", AMBER);
        }
        updateProjectUiState();
    }

    @Override
    protected void onPause() {
        try {
            if (editor != null && currentFile != null && ProjectStore.isEditableText(currentFile)) {
                saveCurrentFileSnapshot(currentFile, editor.getText().toString());
            }
        } catch (Throwable t) {
            appendLog("AUTO SAVE failed\n" + stackText(t));
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        RunnerEngine.stop();
        SdlRunnerActivity.stopProcess(this);
        worker.shutdownNow();
        if (!isChangingConfigurations()) ProjectManager.clearActiveProject(this);
        super.onDestroy();
    }

    private View buildUi() {
        wideLayout = getResources().getConfiguration().smallestScreenWidthDp >= 600
                || screenWidthDp() >= 760;

        shellRoot = new FrameLayout(this);
        shellRoot.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Android 15+ may lay application content edge-to-edge. Keep the IDE
        // below the system status bar and above gesture/navigation insets so
        // DroidCompiler never collides with the clock, Wi-Fi or battery icons.
        final int sidePad = dp(wideLayout ? 10 : 7);
        final int topPad = dp(6);
        final int bottomPad = dp(6);
        root.setPadding(sidePad, topPad, sidePad, bottomPad);
        if (android.os.Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int sysTop = Math.max(0, insets.getSystemWindowInsetTop());
                int sysBottom = Math.max(0, insets.getSystemWindowInsetBottom());
                v.setPadding(sidePad, sysTop + topPad, sidePad, sysBottom + bottomPad);
                return insets;
            });
        }
        shellRoot.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Compact top bar: menu + branding + Build + Run.
        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        appBar.setBackground(roundRect(Color.rgb(8, 22, 34), dp(12), Color.rgb(19, 79, 116), 1));

        menuButton = makeButton("☰", Color.rgb(14, 31, 48), Color.rgb(46, 211, 255));
        menuButton.setTextSize(20);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(44), dp(40));
        menuLp.setMargins(0, 0, dp(7), 0);
        appBar.addView(menuButton, menuLp);

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(com.droidx.R.drawable.app_icon);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconLp.setMargins(0, 0, dp(9), 0);
        appBar.addView(appIcon, iconLp);
        appIcon.setOnClickListener(v -> switchPage(false));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("DroidCompiler");
        title.setTextColor(TEXT);
        title.setTextSize(wideLayout ? 17f : 15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(title);
        projectSubtitle = new TextView(this);
        projectSubtitle.setText(ProjectManager.activeProjectName(this));
        projectSubtitle.setTextColor(Color.rgb(110, 150, 182));
        projectSubtitle.setTextSize(9.5f);
        projectSubtitle.setSingleLine(true);
        brand.addView(projectSubtitle);
        brand.setOnClickListener(v -> switchPage(false));
        appBar.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        buildButton = makeButton("⚒  Build", Color.rgb(20, 34, 52), TEXT);
        runButton = makeButton("▶  Run", Color.rgb(0, 178, 224), Color.rgb(4, 18, 28));
        LinearLayout.LayoutParams topAction = new LinearLayout.LayoutParams(dp(wideLayout ? 104 : 82), dp(40));
        topAction.setMargins(dp(6), 0, 0, 0);
        appBar.addView(buildButton, topAction);
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(dp(wideLayout ? 100 : 78), dp(40));
        runLp.setMargins(dp(6), 0, 0, 0);
        appBar.addView(runButton, runLp);
        root.addView(appBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        pageHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.setMargins(0, dp(7), 0, 0);
        root.addView(pageHost, hostLp);
        editorPage = buildEditorPage();
        settingsPage = buildSettingsPage();
        pageHost.addView(editorPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pageHost.addView(settingsPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(View.GONE);

        buildButton.setOnClickListener(v -> buildProject());
        runButton.setOnClickListener(v -> runProject());
        runButton.setOnLongClickListener(v -> { stopProgram(); return true; });
        menuButton.setOnClickListener(v -> showDrawer());

        buildDrawer();
        return shellRoot;
    }

    private void buildDrawer() {
        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        drawerOverlay.setVisibility(View.GONE);
        drawerOverlay.setClickable(true);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.TRANSPARENT);
        drawerOverlay.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scrim.setOnClickListener(v -> hideDrawer());

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(16), dp(18), dp(16), dp(18));
        drawerPanel.setBackground(roundRect(Color.rgb(7, 25, 44), dp(18), Color.rgb(0, 139, 220), 1));
        int drawerWidth = Math.min(dp(330), Math.round(getResources().getDisplayMetrics().widthPixels * 0.82f));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;
        dlp.setMargins(dp(7), dp(7), 0, dp(7));
        drawerOverlay.addView(drawerPanel, dlp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(5), dp(5), dp(5), dp(13));
        ImageView icon = new ImageView(this);
        icon.setImageResource(com.droidx.R.drawable.app_icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        head.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText("DroidCompiler");
        name.setTextColor(TEXT);
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        TextView tag = new TextView(this);
        tag.setText("No project");
        tag.setTextColor(Color.rgb(111, 165, 202));
        tag.setTextSize(10.5f);
        texts.addView(tag);
        head.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        drawerPanel.addView(head);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(28, 74, 108));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, 0, 0, dp(12));
        drawerPanel.addView(divider, divLp);

        Button settings = drawerItem("⚙   Settings");
        Button buildOptions = drawerItem("🔧   Build Options");
        Button exportApk = drawerItem("▣   Export APK");
        Button openProject = drawerItem("▱   Open Project");
        Button log = drawerItem("≡   Full Log");
        drawerPanel.addView(settings);
        drawerPanel.addView(buildOptions);
        drawerPanel.addView(exportApk);
        drawerPanel.addView(openProject);
        drawerPanel.addView(log);

        settings.setOnClickListener(v -> { hideDrawer(); switchPage(true); });
        buildOptions.setOnClickListener(v -> { hideDrawer(); switchPage(true); Toast.makeText(this, "Build options are in Settings", Toast.LENGTH_SHORT).show(); });
        exportApk.setOnClickListener(v -> { hideDrawer(); showApkExportDialog(); });
        openProject.setOnClickListener(v -> { hideDrawer(); switchPage(false); showProjects(); });
        log.setOnClickListener(v -> { hideDrawer(); showLogDialog(); });

        shellRoot.addView(drawerOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private Button drawerItem(String text) {
        Button b = makeButton(text, Color.TRANSPARENT, TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTextSize(14);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void showDrawer() {
        if (drawerOverlay != null) drawerOverlay.setVisibility(View.VISIBLE);
    }

    private void hideDrawer() {
        if (drawerOverlay != null) drawerOverlay.setVisibility(View.GONE);
    }

    private View buildEditorPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);

        if (wideLayout) {
            explorerPane = buildExplorerPane();
            LinearLayout.LayoutParams explorerLp = new LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.MATCH_PARENT);
            explorerLp.setMargins(0, 0, dp(7), 0);
            page.addView(explorerPane, explorerLp);
        }

        LinearLayout workspace = new LinearLayout(this);
        workspace.setOrientation(LinearLayout.VERTICAL);
        page.addView(workspace, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Open-file tabs.
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(2), dp(4), dp(2), dp(4));
        tabScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        tabsLp.setMargins(0, 0, 0, dp(6));
        workspace.addView(tabScroll, tabsLp);

        LinearLayout fileBar = new LinearLayout(this);
        fileBar.setOrientation(LinearLayout.HORIZONTAL);
        fileBar.setGravity(Gravity.CENTER_VERTICAL);
        fileBar.setPadding(dp(12), dp(4), dp(8), dp(4));
        fileBar.setBackground(roundRect(Color.rgb(8, 22, 34), dp(9), Color.rgb(18, 77, 113), 1));
        fileNameText = new TextView(this);
        fileNameText.setText(ProjectManager.hasActiveProject(this)
                ? ProjectManager.activeProjectName(this) + " / (no file)" : "No project / no file");
        fileNameText.setTextColor(Color.rgb(159, 185, 210));
        fileNameText.setTextSize(10.5f);
        fileNameText.setTypeface(Typeface.MONOSPACE);
        fileNameText.setSingleLine(true);
        fileNameText.setOnClickListener(v -> showFiles());
        fileNameText.setGravity(Gravity.CENTER_VERTICAL);
        fileBar.addView(fileNameText, new LinearLayout.LayoutParams(0, dp(34), 1f));
        fontDownButton = makeButton("A−", Color.rgb(18, 31, 43), MUTED);
        fontUpButton = makeButton("A+", Color.rgb(18, 31, 43), TEXT);
        LinearLayout.LayoutParams fontLp = new LinearLayout.LayoutParams(dp(44), dp(34));
        fontLp.setMargins(dp(6), 0, 0, 0);
        fileBar.addView(fontDownButton, fontLp);
        fileBar.addView(fontUpButton, fontLp);
        fontDownButton.setOnClickListener(v -> changeEditorFont(-1f));
        fontUpButton.setOnClickListener(v -> changeEditorFont(1f));
        LinearLayout.LayoutParams fileBarLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        fileBarLp.setMargins(0, 0, 0, dp(7));
        workspace.addView(fileBar, fileBarLp);

        editor = new CodeEditorView(this);
        editor.setBackground(roundRect(Color.rgb(7, 17, 26), dp(7), Color.rgb(21, 61, 88), 1));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setEditorTextSizeSp(editorFontSp);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        editorParams.setMargins(0, 0, 0, dp(7));
        workspace.addView(editor, editorParams);

        // Compact docked output. Expanded only when requested or on errors.
        outputPanel = new LinearLayout(this);
        outputPanel.setOrientation(LinearLayout.VERTICAL);
        outputPanel.setBackground(roundRect(Color.rgb(8, 20, 29), dp(7), Color.rgb(21, 61, 88), 1));

        LinearLayout outputHeader = new LinearLayout(this);
        outputHeader.setOrientation(LinearLayout.HORIZONTAL);
        outputHeader.setGravity(Gravity.CENTER_VERTICAL);
        outputHeader.setPadding(dp(12), dp(5), dp(7), dp(5));
        statusText = new TextView(this);
        statusText.setText("●  Ready");
        statusText.setTextColor(MUTED);
        statusText.setTextSize(10.5f);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setSingleLine(true);
        outputHeader.addView(statusText, new LinearLayout.LayoutParams(0, dp(36), 1f));
        stopButton = makeButton("■", Color.rgb(64, 30, 38), Color.rgb(255, 128, 136));
        outputToggleButton = makeButton("▤  Output", Color.rgb(13, 30, 44), Color.rgb(224, 237, 247));
        // Full Log lives in the drawer. The compact bottom bar only exposes
        // Stop and Output, with Output occupying the former Log position.
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(52), dp(36));
        stopLp.setMargins(dp(8), 0, 0, 0);
        outputHeader.addView(stopButton, stopLp);
        LinearLayout.LayoutParams outputLp = new LinearLayout.LayoutParams(dp(92), dp(36));
        outputLp.setMargins(dp(7), 0, 0, 0);
        outputHeader.addView(outputToggleButton, outputLp);
        logButton = null;
        outputPanel.addView(outputHeader);

        outputText = new TextView(this);
        outputText.setTextColor(Color.rgb(179, 205, 226));
        outputText.setTextSize(10.5f);
        outputText.setTypeface(Typeface.MONOSPACE);
        outputText.setPadding(dp(8), dp(4), dp(8), dp(6));
        outputText.setTextIsSelectable(true);
        outputScroll = new ScrollView(this);
        outputScroll.setBackgroundColor(Color.rgb(5, 15, 22));
        outputScroll.addView(outputText, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        outputScroll.setVisibility(View.GONE);
        outputPanel.addView(outputScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(wideLayout ? 175 : 125)));
        LinearLayout.LayoutParams outputPanelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outputPanelLp.setMargins(0, 0, 0, dp(7));
        workspace.addView(outputPanel, outputPanelLp);
        stopButton.setOnClickListener(v -> stopProgram());
        outputToggleButton.setOnClickListener(v -> toggleOutputPanel());

        // Slim IDE status bar.
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(12), 0, dp(12), 0);
        statusBar.setBackground(roundRect(Color.rgb(8, 22, 32), dp(8), Color.rgb(16, 54, 78), 1));

        footerFileText = statusItem("main.cpp", Color.rgb(205, 218, 232));
        TextView cpp = statusItem("C++20", Color.rgb(20, 211, 255));
        TextView abi = statusItem(ToolchainManager.deviceAbiLabel(), Color.rgb(20, 211, 255));
        TextView dbg = statusItem("Debug", Color.rgb(20, 211, 255));
        statusBar.addView(footerFileText, new LinearLayout.LayoutParams(0, dp(38), 1f));
        statusBar.addView(statusDivider());
        statusBar.addView(cpp, new LinearLayout.LayoutParams(0, dp(38), 0.75f));
        statusBar.addView(statusDivider());
        statusBar.addView(abi, new LinearLayout.LayoutParams(0, dp(38), 0.85f));
        statusBar.addView(statusDivider());
        statusBar.addView(dbg, new LinearLayout.LayoutParams(0, dp(38), 0.70f));
        workspace.addView(statusBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        apkButton = makeButton("APK", Color.rgb(20, 34, 52), TEXT); // retained for state handling; action is in drawer
        apkButton.setOnClickListener(v -> showApkExportDialog());
        return page;
    }

    private View buildExplorerPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(9), dp(9), dp(9), dp(9));
        pane.setBackground(roundRect(Color.rgb(7, 20, 30), dp(10), Color.rgb(18, 74, 108), 1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("EXPLORER");
        title.setTextColor(Color.rgb(202, 220, 236));
        title.setTextSize(11.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button newFile = explorerIconButton("+");
        Button refresh = explorerIconButton("↻");
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(32));
        iconLp.setMargins(dp(4), 0, 0, 0);
        head.addView(newFile, iconLp);
        head.addView(refresh, iconLp);
        pane.addView(head);
        newFile.setTooltipText("New file");
        refresh.setTooltipText("Refresh");
        newFile.setOnClickListener(v -> showExplorerCreateMenu(null));
        refresh.setOnClickListener(v -> refreshExplorerPane());

        explorerProjectText = new TextView(this);
        explorerProjectText.setText(ProjectManager.hasActiveProject(this)
                ? ProjectManager.activeProjectName(this) : "No project open");
        explorerProjectText.setTextColor(TEXT);
        explorerProjectText.setTextSize(12f);
        explorerProjectText.setTypeface(Typeface.DEFAULT_BOLD);
        explorerProjectText.setGravity(Gravity.CENTER_VERTICAL);
        explorerProjectText.setPadding(dp(7), dp(3), dp(7), dp(5));
        explorerProjectText.setSingleLine(true);
        explorerProjectText.setOnClickListener(v -> showProjects());
        pane.addView(explorerProjectText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        explorerFilter = settingsEdit("", "Filter files…", 1);
        explorerFilter.setSingleLine(true);
        explorerFilter.setTextSize(11f);
        explorerFilter.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        filterLp.setMargins(0, dp(2), 0, dp(7));
        pane.addView(explorerFilter, filterLp);
        explorerFilter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshExplorerPane(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(17, 55, 80));
        pane.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        explorerList = new LinearLayout(this);
        explorerList.setOrientation(LinearLayout.VERTICAL);
        explorerList.setPadding(0, dp(5), 0, dp(5));
        scroll.addView(explorerList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        ensureDefaultExplorerFoldersExpanded();
        refreshExplorerPane();
        return pane;
    }

    private Button explorerIconButton(String text) {
        Button b = makeButton(text, Color.rgb(12, 31, 45), Color.rgb(65, 211, 255));
        b.setTextSize(15f);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        return b;
    }

    private void ensureDefaultExplorerFoldersExpanded() {
        if (!ProjectManager.hasActiveProject(this)) return;
        if (!expandedExplorerDirs.isEmpty()) return;
        for (String name : new String[]{"src", "include", "assets", "android"}) {
            File f = new File(ProjectStore.projectDir(this), name);
            if (f.isDirectory()) expandedExplorerDirs.add(name);
        }
    }

    private void refreshExplorerPane() {
        if (explorerList == null) return;
        explorerList.removeAllViews();
        if (!ProjectManager.hasActiveProject(this)) {
            TextView none = settingsText("No project open.\nOpen or create a project from the menu.");
            none.setTextColor(MUTED);
            none.setPadding(dp(8), dp(14), dp(8), dp(14));
            explorerList.addView(none);
            return;
        }
        try {
            ensureDefaultExplorerFoldersExpanded();
            String filter = explorerFilter == null ? "" : explorerFilter.getText().toString().trim().toLowerCase(Locale.US);
            int shown = renderExplorerDirectory(explorerList, ProjectStore.projectDir(this), 0, filter, null);
            if (shown == 0) {
                TextView none = settingsText(filter.isEmpty() ? "No project files." : "No files match ‘" + filter + "’. ");
                none.setTextColor(MUTED);
                none.setPadding(dp(8), dp(12), dp(8), dp(12));
                explorerList.addView(none);
            }
        } catch (Throwable t) {
            TextView error = settingsText("Could not read project files: " + t.getMessage());
            error.setTextColor(RED);
            error.setPadding(dp(8), dp(12), dp(8), dp(12));
            explorerList.addView(error);
        }
    }

    private int renderExplorerDirectory(LinearLayout host, File dir, int depth, String filter, AlertDialog[] holder) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        java.util.Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        int shown = 0;
        for (File child : children) {
            if (isHiddenExplorerPath(child)) continue;
            if (!filter.isEmpty() && !explorerPathMatchesFilter(child, filter)) continue;
            boolean directory = child.isDirectory();
            String rel = ProjectStore.relativePath(this, child);
            boolean expanded = !filter.isEmpty() || expandedExplorerDirs.contains(rel);
            host.addView(makeExplorerRow(child, depth, expanded, holder), explorerRowLayout());
            shown++;
            if (directory && expanded) shown += renderExplorerDirectory(host, child, depth + 1, filter, holder);
        }
        return shown;
    }

    private LinearLayout.LayoutParams explorerRowLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(1), 0, dp(1));
        return lp;
    }

    private View makeExplorerRow(File file, int depth, boolean expanded, AlertDialog[] holder) {
        boolean directory = file.isDirectory();
        boolean active = !directory && sameFile(file, currentFile);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5 + Math.min(depth, 8) * 13), 0, dp(6), 0);
        row.setBackground(active
                ? roundRect(Color.rgb(12, 51, 73), dp(6), Color.rgb(0, 160, 220), 1)
                : roundRect(Color.TRANSPARENT, dp(6), Color.TRANSPARENT, 0));

        TextView arrow = new TextView(this);
        arrow.setText(directory ? (expanded ? "⌄" : "›") : "");
        arrow.setTextColor(Color.rgb(111, 160, 193));
        arrow.setTextSize(14f);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(this);
        badge.setText(explorerBadge(file));
        badge.setTextColor(explorerBadgeColor(file));
        badge.setTextSize(directory ? 14f : 9.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        row.addView(badge, new LinearLayout.LayoutParams(dp(29), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextColor(active ? Color.rgb(235, 247, 255) : directory ? Color.rgb(210, 225, 238) : Color.rgb(167, 190, 209));
        name.setTextSize(11.5f);
        name.setTypeface(directory ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        row.setOnClickListener(v -> {
            if (directory) {
                String rel = ProjectStore.relativePath(this, file);
                if (expandedExplorerDirs.contains(rel)) expandedExplorerDirs.remove(rel);
                else expandedExplorerDirs.add(rel);
                if (holder != null && holder.length > 0 && holder[0] != null) renderMobileExplorerList(holder[0]);
                else refreshExplorerPane();
            } else {
                try {
                    openProjectFile(file, true);
                    if (holder != null && holder.length > 0 && holder[0] != null) holder[0].dismiss();
                } catch (Throwable t) {
                    appendLog("OPEN FILE failed\n" + stackText(t));
                    setStatus("Open failed · View log", RED);
                }
            }
        });
        row.setOnLongClickListener(v -> {
            showExplorerContextMenu(file, holder);
            return true;
        });
        return row;
    }

    private boolean isHiddenExplorerPath(File f) {
        if (!f.isDirectory()) return false;
        String n = f.getName();
        return ".droidx".equals(n) || ".git".equals(n) || ".cxx".equals(n)
                || ".gradle".equals(n) || ".idea".equals(n);
    }

    private boolean explorerPathMatchesFilter(File f, String filter) {
        String rel = ProjectStore.relativePath(this, f).toLowerCase(Locale.US);
        if (rel.contains(filter)) return true;
        if (!f.isDirectory()) return false;
        File[] children = f.listFiles();
        if (children == null) return false;
        for (File c : children) {
            if (isHiddenExplorerPath(c)) continue;
            if (explorerPathMatchesFilter(c, filter)) return true;
        }
        return false;
    }

    private String explorerBadge(File f) {
        if (f.isDirectory()) return "▱";
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".c")) return "C++";
        if (n.endsWith(".hpp") || n.endsWith(".hh") || n.endsWith(".h") || n.endsWith(".hxx")) return "H";
        if (n.endsWith(".java")) return "J";
        if (n.endsWith(".kt")) return "K";
        if (n.endsWith(".json")) return "{}";
        if (n.endsWith(".xml")) return "<>";
        if (n.endsWith(".glsl") || n.endsWith(".vert") || n.endsWith(".frag")) return "GL";
        if (n.endsWith("makefile") || n.endsWith(".mk")) return "MK";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) return "IMG";
        return "·";
    }

    private int explorerBadgeColor(File f) {
        if (f.isDirectory()) return Color.rgb(62, 199, 255);
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".c")) return Color.rgb(74, 207, 255);
        if (n.endsWith(".hpp") || n.endsWith(".hh") || n.endsWith(".h") || n.endsWith(".hxx")) return Color.rgb(135, 184, 255);
        if (n.endsWith(".json") || n.endsWith(".xml")) return Color.rgb(255, 190, 92);
        if (n.endsWith(".java") || n.endsWith(".kt")) return Color.rgb(255, 145, 92);
        if (n.endsWith(".glsl") || n.endsWith(".vert") || n.endsWith(".frag")) return Color.rgb(196, 135, 255);
        return Color.rgb(137, 160, 180);
    }

    private void registerOpenTab(File file) {
        if (file == null) return;
        for (File f : openTabs) if (sameFile(f, file)) { refreshOpenTabs(); return; }
        openTabs.add(file);
        if (openTabs.size() > 10) openTabs.remove(0);
        refreshOpenTabs();
        updateProjectUiState();
    }

    private boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try { return a.getCanonicalPath().equals(b.getCanonicalPath()); }
        catch (Throwable ignored) { return a.getAbsolutePath().equals(b.getAbsolutePath()); }
    }

    private void refreshOpenTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        if (openTabs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(ProjectManager.hasActiveProject(this) ? "No open files" : "No project open");
            empty.setTextColor(MUTED);
            empty.setTextSize(11f);
            empty.setPadding(dp(9), 0, dp(9), 0);
            tabStrip.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
            return;
        }
        for (File f : new ArrayList<>(openTabs)) {
            final boolean active = sameFile(f, currentFile);
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(10), 0, dp(4), 0);
            tab.setBackground(roundRect(active ? Color.rgb(20, 42, 62) : Color.rgb(16, 27, 38), dp(9),
                    active ? Color.rgb(0, 180, 230) : Color.rgb(35, 63, 84), 1));

            TextView name = new TextView(this);
            name.setText(f.getName());
            name.setTextColor(active ? TEXT : Color.rgb(148, 161, 182));
            name.setTextSize(11.5f);
            if (active) name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            name.setTypeface(Typeface.MONOSPACE);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setSingleLine(true);
            name.setPadding(dp(3), 0, dp(7), 0);
            tab.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));

            TextView close = new TextView(this);
            close.setText("×");
            close.setTextColor(active ? Color.rgb(226, 232, 241) : Color.rgb(130, 143, 164));
            close.setTextSize(16f);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(6), 0, dp(6), 0);
            tab.addView(close, new LinearLayout.LayoutParams(dp(32), dp(36)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMargins(dp(2), 0, dp(6), 0);
            tabStrip.addView(tab, lp);
            name.setOnClickListener(v -> {
                if (active) return;
                try { openProjectFile(f, true); }
                catch (Throwable t) { appendLog("TAB OPEN failed\n" + stackText(t)); }
            });
            close.setOnClickListener(v -> closeEditorTab(f));
        }

        TextView closeAll = new TextView(this);
        closeAll.setText("× All");
        closeAll.setTextColor(MUTED);
        closeAll.setTextSize(10.5f);
        closeAll.setGravity(Gravity.CENTER);
        closeAll.setPadding(dp(12), 0, dp(12), 0);
        closeAll.setBackground(roundRect(Color.rgb(15, 27, 39), dp(9), Color.rgb(35, 63, 84), 1));
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        allLp.setMargins(0, 0, dp(2), 0);
        tabStrip.addView(closeAll, allLp);
        closeAll.setOnClickListener(v -> closeAllEditorTabs(true));

        if (tabScroll != null) tabScroll.post(() -> tabScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void closeEditorTab(File file) {
        boolean wasActive = sameFile(file, currentFile);
        openTabs.removeIf(f -> sameFile(f, file));
        if (wasActive) {
            try {
                saveCurrentFileSnapshot(currentFile, editor.getText().toString());
                currentFile = null;
                if (footerFileText != null) footerFileText.setText("No file");
                if (!openTabs.isEmpty()) openProjectFile(openTabs.get(openTabs.size() - 1), false);
                else {
                    editor.setText("");
                    editor.setHint("Select a file from the project explorer");
                    fileNameText.setText(ProjectManager.hasActiveProject(this)
                            ? ProjectManager.activeProjectName(this) + " / (no file)" : "No project / no file");
                }
            } catch (Throwable t) { appendLog("TAB CLOSE failed\n" + stackText(t)); }
        }
        refreshOpenTabs();
        refreshExplorerPane();
        updateProjectUiState();
    }

    private void closeAllEditorTabs(boolean saveActive) {
        try {
            if (saveActive && currentFile != null && ProjectStore.isEditableText(currentFile)) {
                saveCurrentFileSnapshot(currentFile, editor.getText().toString());
            }
        } catch (Throwable t) {
            appendLog("CLOSE FILES save failed\n" + stackText(t));
        }
        openTabs.clear();
        currentFile = null;
        if (footerFileText != null) footerFileText.setText("No file");
        if (editor != null) {
            editor.setText("");
            editor.setHint(ProjectManager.hasActiveProject(this)
                    ? "Select a file from the project explorer" : "Open or create a project to start coding");
        }
        if (fileNameText != null) fileNameText.setText(ProjectManager.hasActiveProject(this)
                ? ProjectManager.activeProjectName(this) + " / (no file)" : "No project / no file");
        refreshOpenTabs();
        refreshExplorerPane();
        updateProjectUiState();
    }

    private void changeEditorFont(float delta) {
        editorFontSp = Math.max(10.5f, Math.min(22f, editorFontSp + delta));
        getSharedPreferences("ui", MODE_PRIVATE).edit().putFloat("editor_font_sp", editorFontSp).apply();
        if (editor != null) editor.setEditorTextSizeSp(editorFontSp);
        setStatus("Editor font · " + String.format(Locale.US, "%.1f sp", editorFontSp), BLUE);
    }

    private void toggleOutputPanel() {
        outputExpanded = !outputExpanded;
        if (outputScroll != null) outputScroll.setVisibility(outputExpanded ? View.VISIBLE : View.GONE);
        if (outputToggleButton != null) outputToggleButton.setText(outputExpanded ? "▤  Output ▾" : "▤  Output");
        if (outputExpanded) updateOutputPreview();
    }

    private void updateOutputPreview() {
        if (outputText == null) return;
        String log = snapshotLog();
        int keep = wideLayout ? 9000 : 5500;
        if (log.length() > keep) log = "…\n" + log.substring(log.length() - keep);
        outputText.setText(log);
        if (outputScroll != null) outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int screenWidthDp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(dm.widthPixels / dm.density);
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout settingsHost = new FrameLayout(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(wideLayout ? 12 : 4), dp(2), dp(wideLayout ? 12 : 4), dp(18));
        int settingsWidthDp = Math.max(320, Math.min(820, screenWidthDp() - 24));
        FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(
                wideLayout ? dp(settingsWidthDp) : FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        settingsHost.addView(body, bodyLp);
        scroll.addView(settingsHost, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        body.addView(settingsTitle("Core toolchain"));
        body.addView(settingsText("Clang/LLD are APK-embedded for Android 16. The core sysroot/libc++ is the only mandatory development install."));
        LinearLayout core = settingsCard();
        TextView coreText = settingsText(ToolchainManager.isReady(this) ? "✓ C/C++ Core · READY" : "C/C++ Core · setup required");
        core.addView(coreText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button coreButton = makeButton(ToolchainManager.isReady(this) ? "INFO" : "INSTALL", PANEL_2, TEXT);
        coreButton.setOnClickListener(v -> { if (ToolchainManager.isReady(this)) showToolchainStatus(); else installToolchain(); });
        core.addView(coreButton, new LinearLayout.LayoutParams(dp(92), dp(42)));
        body.addView(core);
        Button settingsLog = makeButton("VIEW BUILD / PACKAGE LOG", Color.rgb(35, 39, 48), Color.rgb(188, 199, 220));
        LinearLayout.LayoutParams settingsLogLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        settingsLogLp.setMargins(0, dp(5), 0, dp(2));
        body.addView(settingsLog, settingsLogLp);
        settingsLog.setOnClickListener(v -> showLogDialog());

        body.addView(settingsTitle("Optional dependencies"));
        body.addView(settingsText("The app starts with the C/C++ core only. Network/database/codec packages are downloaded on demand. SDL2 keeps only its tested Android runtime bridge bundled; its development headers stay optional."));
        for (OptionalPackageManager.PackageInfo pkg : OptionalPackageManager.catalog()) body.addView(packageRow(pkg));

        LinearLayout cacheCard = settingsCard();
        TextView cacheText = settingsText("Download cache · " + humanBytes(ToolchainManager.downloadCacheBytes(this)));
        cacheText.setTextColor(MUTED);
        cacheCard.addView(cacheText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button clearCache = makeButton("CLEAR CACHE", PANEL_2, TEXT);
        clearCache.setOnClickListener(v -> worker.submit(() -> {
            try {
                ToolchainManager.clearDownloadCache(this);
                appendLog("PACKAGE DOWNLOAD CACHE CLEARED");
                runOnUiThread(() -> { setStatus("Package cache cleared", ACCENT); rebuildSettingsPage(); });
            } catch (Throwable t) {
                appendLog("CACHE CLEAN FAILED\n" + stackText(t));
                runOnUiThread(() -> setStatus("Cache clean failed · View log", RED));
            }
        }));
        cacheCard.addView(clearCache, new LinearLayout.LayoutParams(dp(118), dp(42)));
        body.addView(cacheCard);

        if (!ProjectManager.hasActiveProject(this)) {
            body.addView(settingsTitle("Project settings"));
            body.addView(settingsText("No project is open. Open or create a project to configure its build system, compiler flags, runtime and APK settings."));
            Button openProject = makeButton("OPEN / CREATE PROJECT", Color.rgb(38, 91, 63), TEXT);
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            openLp.setMargins(0, dp(7), 0, 0);
            body.addView(openProject, openLp);
            openProject.setOnClickListener(v -> showProjects());
            return scroll;
        }

        body.addView(settingsTitle("Build system"));
        body.addView(settingsText("DroidCompiler uses its embedded Clang/LLD backend. Makefile mode is authoritative: it selects the Makefile source manifest, common/local flags, link libraries and simple per-source groups (for example MAIN_SRC/MAIN_OPT and SCREEN_SRCS/SCREEN_OPT) without executing a shell. IDE optimization/LTO/strip presets are ignored in this mode."));
        final Spinner buildSystem = settingsSpinner(new String[]{"internal", "makefile", "custom"}, BuildSystemConfig.mode(this));
        final EditText makefilePath = settingsEdit(BuildSystemConfig.makefilePath(this), "Makefile", 1);
        final EditText customCommand = settingsEdit(BuildSystemConfig.customCommand(this), "clang++ -std=c++17 -O3 -pthread -Iinclude -llog", 2);
        body.addView(labeledSetting("Build mode", buildSystem));
        body.addView(labeledSetting("Makefile path", makefilePath));
        body.addView(labeledSetting("Custom embedded-clang command", customCommand));
        Button createMakefile = makeButton("CREATE / OPEN SAMPLE MAKEFILE", PANEL_2, TEXT);
        createMakefile.setOnClickListener(v -> {
            try {
                File make = ProjectStore.makefile(this);
                if (!make.isFile()) {
                    ProjectStore.saveTextFile(this, make,
                            "CXXFLAGS += -std=c++20 -O2 -pthread -Iinclude\n" +
                            "LDFLAGS += -pthread\n" +
                            "LDLIBS += -llog -landroid\n" +
                            "SOURCES = src/main.cpp\n");
                }
                openProjectFile(make, false);
                switchPage(false);
                setStatus("Makefile opened", ACCENT);
            } catch (Throwable t) { appendLog("MAKEFILE CREATE FAILED\n" + stackText(t)); }
        });
        LinearLayout.LayoutParams makeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        makeLp.setMargins(0, dp(5), 0, dp(4));
        body.addView(createMakefile, makeLp);

        body.addView(settingsTitle("Build configuration"));
        body.addView(settingsText("Per-project compiler/linker options. Start from a preset or customize every flag like C4droid. Changes invalidate the object cache automatically."));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        Button presetDebug = makeButton("DEBUG", PANEL_2, TEXT);
        Button presetRelease = makeButton("RELEASE", Color.rgb(38, 91, 63), TEXT);
        Button presetSize = makeButton("SIZE", Color.rgb(72, 66, 40), TEXT);
        presetDebug.setOnClickListener(v -> applyBuildPreset("debug"));
        presetRelease.setOnClickListener(v -> applyBuildPreset("release"));
        presetSize.setOnClickListener(v -> applyBuildPreset("size"));
        presets.addView(presetDebug, weighted());
        presets.addView(presetRelease, weighted());
        presets.addView(presetSize, weighted());
        body.addView(presets);

        final Spinner std = settingsSpinner(new String[]{"c++17", "c++20", "c++23"}, BuildSettings.cppStandard(this));
        final Spinner opt = settingsSpinner(new String[]{"-O0", "-O1", "-O2", "-O3", "-Os", "-Oz"}, BuildSettings.optimization(this));
        body.addView(labeledSetting("C++ standard", std));
        body.addView(labeledSetting("Optimization", opt));

        final CheckBox debug = settingsCheck("Debug symbols (-g)", BuildSettings.debugSymbols(this));
        final CheckBox warnings = settingsCheck("Warnings (-Wall -Wextra)", BuildSettings.warnings(this));
        final CheckBox lto = settingsCheck("Link-time optimization (-flto)", BuildSettings.lto(this));
        final CheckBox strip = settingsCheck("Strip symbols on link (-s)", BuildSettings.strip(this));
        body.addView(debug); body.addView(warnings); body.addView(lto); body.addView(strip);

        final EditText compilerFlags = settingsEdit(BuildSettings.extraCompilerFlags(this), "Example: -fno-exceptions -fno-rtti", 2);
        final EditText defines = settingsEdit(BuildSettings.defines(this), "One per line: DEBUG=1", 3);
        final EditText includePaths = settingsEdit(BuildSettings.includePaths(this), "One include directory per line", 3);
        final EditText linkerFlags = settingsEdit(BuildSettings.extraLinkerFlags(this), "Example: -Wl,--gc-sections", 2);
        final EditText libraryPaths = settingsEdit(BuildSettings.libraryPaths(this), "One -L directory per line", 3);
        final EditText libraries = settingsEdit(BuildSettings.libraries(this), "One per line: m\nlog\nfoo", 3);
        body.addView(labeledSetting("Extra compiler flags", compilerFlags));
        body.addView(labeledSetting("Defines", defines));
        body.addView(labeledSetting("Include paths", includePaths));
        body.addView(labeledSetting("Extra linker flags", linkerFlags));
        body.addView(labeledSetting("Library paths", libraryPaths));
        body.addView(labeledSetting("Libraries", libraries));

        body.addView(settingsTitle("Runtime / Android host"));
        final Spinner orientation = settingsSpinner(new String[]{"landscape", "portrait", "sensor", "auto"}, ProjectConfig.orientation(this));
        final CheckBox foregroundRunner = settingsCheck("Foreground Runner (long-running console/bot)", ProjectConfig.foregroundRunner(this));
        final EditText extraPermissions = settingsEdit(ProjectConfig.extraPermissions(this), "APK export permissions, one per line\nandroid.permission.ACCESS_NETWORK_STATE", 3);
        body.addView(labeledSetting("Orientation", orientation));
        body.addView(foregroundRunner);
        body.addView(settingsText("C++ can include <DroidXAndroid.h> for notifications, JNI environment access, wake-lock, Android logging and opening URLs. android/java/ is reserved for custom Java/Kotlin sources used by future APK Export."));
        body.addView(labeledSetting("Extra Android permissions (APK export)", extraPermissions));
        Button notifyPermission = makeButton(AndroidHostApi.hasNotificationPermission(this) ? "NOTIFICATIONS ✓" : "GRANT NOTIFICATIONS", PANEL_2, TEXT);
        notifyPermission.setOnClickListener(v -> AndroidHostApi.requestNotificationPermission(this));
        LinearLayout.LayoutParams notifyLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        notifyLp.setMargins(0, dp(5), 0, dp(2));
        body.addView(notifyPermission, notifyLp);

        // Optional direct shared-storage compatibility for legacy C4droid projects
        // that intentionally use absolute paths such as /storage/emulated/0/chainscan.
        Button allFilesAccess = makeButton(Environment.isExternalStorageManager()
                ? "LEGACY SHARED STORAGE ✓" : "GRANT LEGACY SHARED STORAGE", PANEL_2, TEXT);
        allFilesAccess.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable first) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Throwable second) { appendLog("ALL FILES ACCESS SETTINGS FAILED\n" + stackText(second)); }
            }
        });
        LinearLayout.LayoutParams allFilesLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        allFilesLp.setMargins(0, dp(5), 0, dp(2));
        body.addView(allFilesAccess, allFilesLp);
        body.addView(settingsText("Legacy/C4droid shared storage is optional. Enable it only for projects that directly read/write absolute shared-storage paths such as /storage/emulated/0/chainscan."));

        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setPadding(0, dp(8), 0, dp(12));
        Button reset = makeButton("RESET", PANEL_2, TEXT);
        Button save = makeButton("SAVE SETTINGS", Color.rgb(38, 91, 63), TEXT);
        reset.setOnClickListener(v -> {
            BuildSettings.reset(this);
            BuildSystemConfig.reset(this);
            ProjectConfig.setOrientation(this, ProjectConfig.ORIENTATION_LANDSCAPE);
            ProjectConfig.setForegroundRunner(this, false);
            ProjectConfig.setExtraPermissions(this, "");
            rebuildSettingsPage();
            setStatus("Build settings reset", AMBER);
            appendLog("BUILD SETTINGS RESET");
        });
        save.setOnClickListener(v -> {
            BuildSettings.save(this,
                    std.getSelectedItem().toString(), opt.getSelectedItem().toString(),
                    debug.isChecked(), warnings.isChecked(), lto.isChecked(), strip.isChecked(),
                    compilerFlags.getText().toString(), defines.getText().toString(), includePaths.getText().toString(),
                    linkerFlags.getText().toString(), libraryPaths.getText().toString(), libraries.getText().toString());
            BuildSystemConfig.save(this, buildSystem.getSelectedItem().toString(),
                    makefilePath.getText().toString(), customCommand.getText().toString());
            ProjectConfig.setOrientation(this, orientation.getSelectedItem().toString());
            ProjectConfig.setForegroundRunner(this, foregroundRunner.isChecked());
            ProjectConfig.setExtraPermissions(this, extraPermissions.getText().toString());
            setStatus("Build settings saved", ACCENT);
            appendLog("BUILD SETTINGS SAVED\n" + BuildSettings.fingerprint(this) +
                    "\nBuild system: " + BuildSystemConfig.fingerprint(this) +
                    "\nForeground runner: " + ProjectConfig.foregroundRunner(this));
            Toast.makeText(this, "Project build settings saved", Toast.LENGTH_SHORT).show();
        });
        saveRow.addView(reset, weighted());
        saveRow.addView(save, weighted());
        body.addView(saveRow);
        return scroll;
    }

    private View packageRow(OptionalPackageManager.PackageInfo pkg) {
        boolean installed = OptionalPackageManager.isInstalled(this, pkg.id);
        LinearLayout row = settingsCard();
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView name = settingsText((installed ? "✓ " : "○ ") + pkg.name);
        name.setTextColor(installed ? ACCENT : TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView desc = settingsText(pkg.description);
        desc.setTextColor(MUTED);
        desc.setTextSize(11);
        textBox.addView(name); textBox.addView(desc);
        row.addView(textBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button action = makeButton(installed ? "DEACTIVATE" : "INSTALL", installed ? Color.rgb(72, 48, 52) : Color.rgb(38, 91, 63), TEXT);
        action.setOnClickListener(v -> changeOptionalPackage(pkg.id, installed, action));
        row.addView(action, new LinearLayout.LayoutParams(dp(96), dp(42)));
        return row;
    }

    private void changeOptionalPackage(String id, boolean remove, Button action) {
        action.setEnabled(false);
        action.setText(remove ? "Deactivating…" : "Installing…");
        appendLog((remove ? "REMOVE PACKAGE " : "INSTALL PACKAGE ") + id);
        worker.submit(() -> {
            try {
                ToolchainManager.Listener listener = new ToolchainManager.Listener() {
                    @Override public void onLog(String message) { appendLog(message); }
                    @Override public void onProgress(int current, int total, String packageName) {
                        runOnUiThread(() -> action.setText(current + "/" + total));
                    }
                };
                if (remove) OptionalPackageManager.remove(this, id, listener);
                else OptionalPackageManager.install(this, id, listener);
                runOnUiThread(() -> {
                    setStatus((remove ? "Removed " : "Installed ") + id, remove ? AMBER : ACCENT);
                    rebuildSettingsPage();
                });
            } catch (Throwable t) {
                appendLog("PACKAGE ACTION FAILED\n" + stackText(t));
                runOnUiThread(() -> {
                    action.setEnabled(true);
                    action.setText(remove ? "DEACTIVATE" : "INSTALL");
                    setStatus("Package action failed · View log", RED);
                    Toast.makeText(this, "Package action failed — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyBuildPreset(String preset) {
        BuildSettings.applyPreset(this, preset);
        appendLog("BUILD PRESET APPLIED: " + preset);
        setStatus("Build preset: " + preset, ACCENT);
        rebuildSettingsPage();
    }

    private void rebuildSettingsPage() {
        boolean visible = settingsPage != null && settingsPage.getVisibility() == View.VISIBLE;
        if (pageHost == null) return;
        if (settingsPage != null) pageHost.removeView(settingsPage);
        settingsPage = buildSettingsPage();
        pageHost.addView(settingsPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (editorPage != null) editorPage.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void switchPage(boolean settings) {
        if (settings && settingsPage == null) rebuildSettingsPage();
        editorPage.setVisibility(settings ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (editorTabButton != null && settingsTabButton != null) {
            editorTabButton.setTextColor(settings ? MUTED : TEXT);
            settingsTabButton.setTextColor(settings ? TEXT : MUTED);
            editorTabButton.setBackground(roundRect(settings ? PANEL_2 : Color.rgb(45, 64, 87), dp(9), Color.rgb(55, 61, 73), 1));
            settingsTabButton.setBackground(roundRect(settings ? Color.rgb(45, 64, 87) : PANEL_2, dp(9), Color.rgb(55, 61, 73), 1));
        }
    }

    private TextView settingsTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(12), dp(4), dp(6));
        return t;
    }

    private TextView settingsText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(194, 203, 219));
        t.setTextSize(12);
        return t;
    }

    private LinearLayout settingsCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(8), dp(9));
        row.setBackground(roundRect(PANEL, dp(9), Color.rgb(44, 50, 61), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(lp);
        return row;
    }

    private View labeledSetting(String label, View input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(6), dp(4), dp(4));
        TextView l = settingsText(label);
        l.setTextColor(MUTED);
        l.setPadding(dp(2), 0, 0, dp(4));
        box.addView(l);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private Spinner settingsSpinner(String[] items, String selected) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private TextView style(View convertView, ViewGroup parent, int position, boolean dropdown) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(TEXT);
                t.setTextSize(12f);
                t.setPadding(dp(10), dropdown ? dp(10) : dp(6), dp(10), dropdown ? dp(10) : dp(6));
                t.setBackgroundColor(dropdown ? Color.rgb(30, 34, 42) : PANEL_2);
                return t;
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return style(convertView, parent, position, false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(position, convertView, parent);
                t.setTextColor(TEXT);
                t.setTextSize(12f);
                t.setPadding(dp(12), dp(12), dp(12), dp(12));
                t.setBackgroundColor(Color.rgb(30, 34, 42));
                return t;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        for (int i = 0; i < items.length; i++) if (items[i].equals(selected)) sp.setSelection(i);
        sp.setBackground(roundRect(PANEL_2, dp(7), Color.rgb(48, 54, 66), 1));
        return sp;
    }

    private CheckBox settingsCheck(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setChecked(checked);
        c.setPadding(dp(4), dp(3), dp(4), dp(3));
        return c;
    }

    private EditText settingsEdit(String value, String hint, int lines) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(100, 110, 128));
        e.setTextColor(TEXT);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(12);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(lines);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(roundRect(PANEL_2, dp(8), Color.rgb(48, 54, 66), 1));
        e.setPadding(dp(9), dp(7), dp(9), dp(7));
        return e;
    }

    private void loadActiveProject() {
        if (!ProjectManager.hasActiveProject(this)) {
            showBlankWorkspace(false);
            return;
        }
        try {
            closeAllEditorTabs(false);
            ProjectStore.ensureStructure(this);
            ProjectConfig.ensureDefaults(this);
            // A project switch opens the workspace, not an arbitrary file. This
            // prevents stale/automatic tabs from being mistaken for the active build.
            editor.setText("");
            editor.setHint("Select a file from the project explorer");
            currentFile = null;
            fileNameText.setText(ProjectManager.activeProjectName(this) + " / (no file)");
            appendLog("PROJECT OPENED\nName: " + ProjectManager.activeProjectName(this) +
                    "\nWorkspace: " + ProjectStore.projectDir(this) +
                    (ProjectManager.activeProjectLinked(this) ? "\nLinked folder: " + ProjectManager.activeTreeUri(this) : "\nLocal project"));
            updateProjectChrome();
            refreshOpenTabs();
            refreshExplorerPane();
            updateProjectUiState();
        } catch (Throwable t) {
            appendLog("Could not load project:\n" + stackText(t));
            setStatus("Project load error · View log", RED);
            showBlankWorkspace(false);
        }
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
            setStatus("Core toolchain ready", ACCENT);
            Toast.makeText(this, "Clang is ready", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        installLog.setLength(0);
        appendLog("INSTALL CORE TOOLCHAIN DATA\nABI: " + ToolchainManager.deviceAbiLabel());
        setStatus("Installing core toolchain…", AMBER);

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
                        runOnUiThread(() ->
                                setStatus("Installing " + packageName + "…", AMBER));
                    }
                });
                runOnUiThread(() -> {
                    setBusy(false);
                            String status = ToolchainManager.status(this);
                    appendLog(status);
                    setStatus("Core toolchain ready · " + ToolchainManager.deviceAbiLabel(), ACCENT);
                    rebuildSettingsPage();
                });
            } catch (Throwable t) {
                appendLog("INSTALL FAILED\n" + stackText(t));
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Toolchain install failed · View log", RED);
                    Toast.makeText(this, "Install failed — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void buildProject() {
        if (!ensureProjectOpen("build")) return;
        final String source = editor.getText().toString();
        final File editFile = currentFile;
        setBusy(true);
        setStatus("Building project…", BLUE);
        appendLog("BUILD START\nSaving main.cpp and starting embedded clang++.");

        worker.submit(() -> {
            try {
                saveCurrentFileSnapshot(editFile, source);
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
        if (!ensureProjectOpen("run")) return;
        final String source = editor.getText().toString();
        final File editFile = currentFile;
        setBusy(true);
        setStatus("Preparing RUN…", BLUE);
        appendLog("RUN REQUEST");

        worker.submit(() -> {
            try {
                saveCurrentFileSnapshot(editFile, source);

                File program = ProjectStore.activeProgramLibrary(this);
                if (!ProjectStore.isActiveBuildCurrent(this)) {
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

                if (CompilerEngine.usesSDL2(readWholeProjectText())) {
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

                final File consoleProgram = program;
                runOnUiThread(() -> {
                    try {
                        Intent intent = ConsoleActivity.createIntent(this, consoleProgram);
                        startActivity(intent);
                        appendLog("INTERACTIVE CONSOLE RUN\nProgram: " + consoleProgram +
                                "\nProcess: :runner\nI/O: PTY stdin/stdout/stderr");
                        setStatus("Interactive console opened", ACCENT);
                    } catch (Throwable t) {
                        appendLog("CONSOLE RUN ERROR\n" + stackText(t));
                        setStatus("Console run error · View log", RED);
                    } finally {
                        setBusy(false);
                    }
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
        RunnerService.requestStop(this);
        SdlRunnerActivity.stopProcess(this);
        appendLog("STOP requested for interactive console / SDL runner.");
        setStatus("Stop requested", AMBER);
    }

    private void showApkExportDialog() {
        if (!ensureProjectOpen("export an APK")) return;
        final ApkExportSettings current = ApkExportSettings.load(this);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));

        final EditText appName = settingsEdit(current.appName, "App name", 1);
        final EditText packageName = settingsEdit(current.packageName, "com.example.myapp", 1);
        final EditText versionName = settingsEdit(current.versionName, "1.0.0", 1);
        final EditText versionCode = settingsEdit(Integer.toString(current.versionCode), "1", 1);
        versionCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        final Spinner orientation = settingsSpinner(new String[]{"portrait", "landscape", "sensor", "auto"}, current.orientation);
        final CheckBox foreground = settingsCheck("Foreground runner", current.foreground);
        final CheckBox legacyStorage = settingsCheck("Legacy shared storage (/storage/emulated/0)", current.legacyStorage);

        body.addView(labeledSetting("Application name", appName));
        body.addView(labeledSetting("Package ID", packageName));
        body.addView(labeledSetting("Version name", versionName));
        body.addView(labeledSetting("Version code", versionCode));
        body.addView(labeledSetting("Orientation", orientation));
        body.addView(foreground);
        body.addView(legacyStorage);

        File iconFile = ApkExportSettings.iconFile(this);
        TextView iconStatus = settingsText(iconFile.isFile()
                ? "Custom icon: " + iconFile.getName() + " (will be packed into the APK)"
                : "Icon: DroidCompiler default. Choose a PNG/JPEG/WebP to replace it.");
        body.addView(iconStatus);
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        Button chooseIcon = makeButton("CHOOSE ICON", PANEL_2, TEXT);
        Button defaultIcon = makeButton("DEFAULT ICON", PANEL_2, MUTED);
        iconRow.addView(chooseIcon, weighted());
        iconRow.addView(defaultIcon, weighted());
        body.addView(iconRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Export APK")
                .setMessage("DroidCompiler will build one independent signed APK from the active project. The exporter is integrated inside DroidCompiler.")
                .setView(scroll)
                .setPositiveButton("EXPORT", null)
                .setNegativeButton("CANCEL", null)
                .create();

        chooseIcon.setOnClickListener(v -> {
            try {
                ApkExportSettings draft = readExportSettings(appName, packageName, versionName, versionCode,
                        orientation, foreground, legacyStorage);
                draft.save(this);
                dialog.dismiss();
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_EXPORT_ICON);
            } catch (Throwable t) {
                Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        defaultIcon.setOnClickListener(v -> {
            try {
                File f = ApkExportSettings.iconFile(this);
                if (f.isFile()) f.delete();
                iconStatus.setText("Icon: DroidCompiler default.");
            } catch (Throwable ignored) {}
        });

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ApkExportSettings selected = readExportSettings(appName, packageName, versionName, versionCode,
                        orientation, foreground, legacyStorage);
                selected.save(this);
                dialog.dismiss();
                exportApk(selected);
            } catch (Throwable t) {
                Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private ApkExportSettings readExportSettings(EditText appName, EditText packageName,
                                                  EditText versionName, EditText versionCode,
                                                  Spinner orientation, CheckBox foreground,
                                                  CheckBox legacyStorage) {
        int code;
        try { code = Integer.parseInt(versionCode.getText().toString().trim()); }
        catch (Throwable t) { throw new IllegalArgumentException("Version code must be a whole number >= 1"); }
        return new ApkExportSettings(appName.getText().toString(), packageName.getText().toString(),
                versionName.getText().toString(), code, orientation.getSelectedItem().toString(),
                foreground.isChecked(), legacyStorage.isChecked());
    }

    private void exportApk(final ApkExportSettings exportSettings) {
        final File editFile = currentFile;
        final String source = editor.getText().toString();
        setBusy(true);
        setStatus("Preparing APK export…", AMBER);
        appendLog("APK EXPORT REQUEST\n" + exportSettings.appName + " · " + exportSettings.packageName
                + " · " + exportSettings.versionName + " (" + exportSettings.versionCode + ")");
        worker.submit(() -> {
            try {
                saveCurrentFileSnapshot(editFile, source);
                File program = ProjectStore.activeProgramLibrary(this);
                if (!ProjectStore.isActiveBuildCurrent(this)) {
                    appendLog("Project changed; rebuilding before APK export.");
                    BuildResult br = CompilerEngine.build(this);
                    appendLog(br.output);
                    if (!br.ok) {
                        runOnUiThread(() -> { setBusy(false); setStatus("APK blocked: build failed · View log", RED); });
                        return;
                    }
                }
                ApkExporter.Result result = ApkExporter.export(this, exportSettings, this::appendLog);
                lastExportedApk = result.apk;
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("APK exported · " + result.apk.getName(), ACCENT);
                    showApkResult(result);
                });
            } catch (Throwable t) {
                appendLog("APK EXPORT FAILED\n" + stackText(t));
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("APK export failed · View log", RED);
                    Toast.makeText(this, "APK export failed — open LOG", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showApkResult(ApkExporter.Result result) {
        String msg = "App: " + result.appName + "\n" +
                "Package: " + result.packageName + "\n" +
                "Version: " + result.versionName + " (" + result.versionCode + ")\n" +
                "Mode: " + (result.sdl ? "SDL2" : "console") + "\n" +
                "Size: " + String.format(Locale.US, "%.2f MB", result.apk.length() / 1048576.0) + "\n\n" +
                "Signed with the DroidCompiler development key. Use a private release keystore before publishing.";
        new AlertDialog.Builder(this)
                .setTitle("APK ready")
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> installExportedApk(result.apk))
                .setNeutralButton("Save", (d, w) -> saveExportedApk(result.apk))
                .setNegativeButton("Share", (d, w) -> shareExportedApk(result.apk))
                .show();
    }

    private Uri exportUri(File apk) {
        return Uri.parse("content://com.droidx.exports/" + Uri.encode(apk.getName()));
    }

    private void installExportedApk(File apk) {
        try {
            PackageManager pm = getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                startActivity(settings);
                Toast.makeText(this, "Allow app installs, then tap APK → Install again", Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(exportUri(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            appendLog("APK INSTALL launch failed\n" + stackText(t));
            setStatus("Install launch failed · View log", RED);
        }
    }

    private void saveExportedApk(File apk) {
        lastExportedApk = apk;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_TITLE, apk.getName());
        startActivityForResult(i, REQ_SAVE_APK);
    }

    private void shareExportedApk(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_STREAM, exportUri(apk));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share APK"));
        } catch (Throwable t) {
            appendLog("APK SHARE failed\n" + stackText(t));
        }
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

    private void saveCurrentFileSnapshot(File file, String text) throws Exception {
        if (!ProjectManager.hasActiveProject(this) || file == null) return;
        if (!ProjectStore.isEditableText(file)) return;
        String desired = text == null ? "" : text;
        if (file.isFile()) {
            try {
                if (desired.equals(ProjectStore.readTextFile(this, file))) return;
            } catch (Throwable ignored) {}
        }
        ProjectStore.saveTextFile(this, file, desired);
        ProjectManager.syncFileIfLinked(this, file);
    }

    private void openProjectFile(File file, boolean savePrevious) throws Exception {
        if (!ProjectManager.hasActiveProject(this)) throw new IllegalStateException("No project is open");
        if (savePrevious && currentFile != null && ProjectStore.isEditableText(currentFile)) {
            ProjectStore.saveTextFile(this, currentFile, editor.getText().toString());
            ProjectManager.syncFileIfLinked(this, currentFile);
        }
        if (!ProjectStore.isEditableText(file)) {
            Toast.makeText(this, "Binary asset: " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }
        currentFile = file;
        if (footerFileText != null) footerFileText.setText(file.getName());
        editor.setText(ProjectStore.readTextFile(this, file));
        editor.scheduleHighlightNow();
        fileNameText.setText(ProjectStore.relativePath(this, file));
        registerOpenTab(file);
        refreshExplorerPane();
        updateProjectUiState();
        setStatus("Editing " + ProjectStore.relativePath(this, file), BLUE);
    }

    private String readWholeProjectText() {
        if (!ProjectManager.hasActiveProject(this)) return "";
        StringBuilder out = new StringBuilder();
        try {
            for (File f : ProjectStore.sourceFiles(this)) out.append(ProjectStore.readTextFile(this, f)).append('\n');
            for (File f : ProjectStore.headerFiles(this)) out.append(ProjectStore.readTextFile(this, f)).append('\n');
        } catch (Throwable ignored) {}
        return out.toString();
    }

    private void showProjects() {
        try {
            if (currentFile != null && ProjectStore.isEditableText(currentFile)) {
                saveCurrentFileSnapshot(currentFile, editor.getText().toString());
            }
        } catch (Throwable t) {
            appendLog("SAVE BEFORE PROJECT SWITCH failed\n" + stackText(t));
        }

        List<ProjectManager.Entry> entries = ProjectManager.list(this);
        String activeId = ProjectManager.activeProjectId(this);
        List<String> labels = new ArrayList<>();
        for (ProjectManager.Entry e : entries) {
            labels.add((e.id.equals(activeId) ? "● " : "   ") + e.name + (e.linked() ? "   [folder]" : "   [local]"));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Projects")
                .setMessage("No project is opened automatically. Choose a recent project, open a folder, create a local project, or close the current workspace.")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> switchToProject(entries.get(which)))
                .setPositiveButton("Open folder", (dialog, which) -> openProjectFolderPicker())
                .setNeutralButton("New project", (dialog, which) -> showNewProjectDialog());
        if (ProjectManager.hasActiveProject(this)) {
            builder.setNegativeButton("Close project", (dialog, which) -> closeActiveProject());
        } else {
            builder.setNegativeButton("Dismiss", null);
        }
        builder.show();
    }

    private void openProjectFolderPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_OPEN_PROJECT_TREE);
        } catch (Throwable t) {
            appendLog("OPEN PROJECT PICKER failed\n" + stackText(t));
            setStatus("Could not open folder picker · View log", RED);
        }
    }

    private void showNewProjectDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("MyProject");
        new AlertDialog.Builder(this)
                .setTitle("New project")
                .setMessage("Creates a separate DroidCompiler workspace. You can switch projects later from Projects.")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    try {
                        closeAllEditorTabs(true);
                        ProjectManager.Entry e = ProjectManager.createLocalProject(this, input.getText().toString());
                        ProjectConfig.ensureDefaults(this);
                        rebuildSettingsPage();
                        loadActiveProject();
                        setStatus("Created project · " + e.name, ACCENT);
                    } catch (Throwable t) {
                        appendLog("CREATE PROJECT failed\n" + stackText(t));
                        setStatus("Create project failed · View log", RED);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void switchToProject(ProjectManager.Entry entry) {
        if (entry == null || entry.id.equals(ProjectManager.activeProjectId(this))) return;
        try {
            closeAllEditorTabs(true);
            ProjectManager.switchProject(this, entry.id);
            ProjectConfig.ensureDefaults(this);
            rebuildSettingsPage();
            loadActiveProject();
            setStatus("Switched project · " + entry.name + " · no files opened", ACCENT);
        } catch (Throwable t) {
            appendLog("PROJECT SWITCH failed\n" + stackText(t));
            setStatus("Project switch failed · View log", RED);
        }
    }

    private EditText mobileExplorerFilter;
    private LinearLayout mobileExplorerList;

    private void showFiles() {
        if (!ensureProjectOpen("browse files")) return;
        try {
            if (currentFile != null && ProjectStore.isEditableText(currentFile)) {
                saveCurrentFileSnapshot(currentFile, editor.getText().toString());
            }
        } catch (Throwable t) {
            appendLog("SAVE BEFORE FILES failed\n" + stackText(t));
        }
        ensureDefaultExplorerFoldersExpanded();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(10));
        body.setBackgroundColor(Color.rgb(7, 18, 27));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(ProjectManager.activeProjectName(this));
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button plus = explorerIconButton("+");
        Button refresh = explorerIconButton("↻");
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40), dp(36));
        iconLp.setMargins(dp(5), 0, 0, 0);
        top.addView(plus, iconLp);
        top.addView(refresh, iconLp);
        body.addView(top);

        mobileExplorerFilter = settingsEdit("", "Filter files…", 1);
        mobileExplorerFilter.setSingleLine(true);
        mobileExplorerFilter.setTextSize(11.5f);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        filterLp.setMargins(0, dp(2), 0, dp(7));
        body.addView(mobileExplorerFilter, filterLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(17, 55, 80));
        body.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        mobileExplorerList = new LinearLayout(this);
        mobileExplorerList.setOrientation(LinearLayout.VERTICAL);
        mobileExplorerList.setPadding(0, dp(5), 0, dp(5));
        scroll.addView(mobileExplorerList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(wideLayout ? 560 : 470)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(body)
                .setNegativeButton("Close", null)
                .create();
        final AlertDialog[] holder = new AlertDialog[]{dialog};
        plus.setOnClickListener(v -> showExplorerCreateMenu(holder));
        refresh.setOnClickListener(v -> renderMobileExplorerList(dialog));
        mobileExplorerFilter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderMobileExplorerList(dialog); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        dialog.setOnShowListener(x -> renderMobileExplorerList(dialog));
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = wideLayout ? Math.min(dp(620), getResources().getDisplayMetrics().widthPixels) : WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
    }

    private void renderMobileExplorerList(AlertDialog dialog) {
        if (mobileExplorerList == null || !ProjectManager.hasActiveProject(this)) return;
        mobileExplorerList.removeAllViews();
        String filter = mobileExplorerFilter == null ? "" : mobileExplorerFilter.getText().toString().trim().toLowerCase(Locale.US);
        int shown = renderExplorerDirectory(mobileExplorerList, ProjectStore.projectDir(this), 0, filter, new AlertDialog[]{dialog});
        if (shown == 0) {
            TextView none = settingsText(filter.isEmpty() ? "No project files." : "No files match ‘" + filter + "’. ");
            none.setTextColor(MUTED);
            none.setPadding(dp(8), dp(14), dp(8), dp(14));
            mobileExplorerList.addView(none);
        }
    }

    private void showExplorerCreateMenu(AlertDialog[] holder) {
        if (!ensureProjectOpen("create project content")) return;
        String[] items = new String[]{"New file", "New folder", "Import file"};
        new AlertDialog.Builder(this)
                .setTitle("Add to project")
                .setItems(items, (d, which) -> {
                    if (which == 0) showCreatePath(false);
                    else if (which == 1) showCreatePath(true);
                    else importAsset();
                })
                .show();
    }

    private void showExplorerContextMenu(File target, AlertDialog[] holder) {
        if (target == null) return;
        boolean directory = target.isDirectory();
        String[] items = directory
                ? new String[]{"New file here", "New folder here", "Rename", "Delete"}
                : new String[]{"Open", "Rename", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(ProjectStore.relativePath(this, target))
                .setItems(items, (d, which) -> {
                    if (!directory && which == 0) {
                        try {
                            openProjectFile(target, true);
                            if (holder != null && holder.length > 0 && holder[0] != null) holder[0].dismiss();
                        } catch (Throwable t) {
                            appendLog("OPEN FILE failed\n" + stackText(t));
                            setStatus("Open failed · View log", RED);
                        }
                        return;
                    }
                    int action = directory ? which : which + 1;
                    if (action == 0) showCreatePathIn(target, false);
                    else if (action == 1) showCreatePathIn(target, true);
                    else if (action == 2) showRenameProjectPath(target, holder);
                    else if (action == 3) confirmDeleteProjectPath(target, holder);
                })
                .show();
    }

    private void showCreatePathIn(File directory, boolean makeDirectory) {
        if (directory == null || !directory.isDirectory()) return;
        String base = ProjectStore.relativePath(this, directory);
        if (".".equals(base)) base = "";
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(makeDirectory ? "folder" : "File.cpp");
        String prefix = base.isEmpty() ? "" : base + "/";
        input.setText(prefix);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(makeDirectory ? "New folder" : "New file")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    try {
                        File f = ProjectStore.createProjectFile(this, input.getText().toString(), makeDirectory);
                        ProjectManager.syncFileIfLinked(this, f);
                        expandedExplorerDirs.add(ProjectStore.relativePath(this, directory));
                        appendLog("Created " + ProjectStore.relativePath(this, f));
                        refreshExplorerPane();
                        if (!makeDirectory && ProjectStore.isEditableText(f)) openProjectFile(f, true);
                    } catch (Throwable t) {
                        appendLog("CREATE failed\n" + stackText(t));
                        setStatus("Create failed · View log", RED);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameProjectPath(File target, AlertDialog[] holder) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(target.getName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    try {
                        saveEditorBeforePathChange(target);
                        File renamed = ProjectStore.renameProjectPath(this, target, input.getText().toString());
                        remapOpenFilesAfterRename(target, renamed);
                        appendLog("Renamed " + ProjectStore.relativePath(this, target) + " → " + ProjectStore.relativePath(this, renamed));
                        refreshOpenTabs();
                        refreshExplorerPane();
                        if (holder != null && holder.length > 0 && holder[0] != null) renderMobileExplorerList(holder[0]);
                    } catch (Throwable t) {
                        appendLog("RENAME failed\n" + stackText(t));
                        setStatus("Rename failed · View log", RED);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteProjectPath(File target, AlertDialog[] holder) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + target.getName() + "?")
                .setMessage(target.isDirectory() ? "The folder and all of its contents will be removed." : "This file will be removed from the project.")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        saveEditorBeforePathChange(target);
                        removeOpenFilesInside(target);
                        ProjectStore.deleteProjectPath(this, target);
                        appendLog("Deleted " + target.getName());
                        if (currentFile == null && editor != null) {
                            editor.setText("");
                            editor.setHint("Select a file from the project explorer");
                        }
                        refreshOpenTabs();
                        refreshExplorerPane();
                        updateProjectChrome();
                        if (holder != null && holder.length > 0 && holder[0] != null) renderMobileExplorerList(holder[0]);
                    } catch (Throwable t) {
                        appendLog("DELETE failed\n" + stackText(t));
                        setStatus("Delete failed · View log", RED);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveEditorBeforePathChange(File target) throws Exception {
        if (currentFile != null && ProjectStore.isEditableText(currentFile) && isFileInside(currentFile, target)) {
            saveCurrentFileSnapshot(currentFile, editor.getText().toString());
        }
    }

    private boolean isFileInside(File child, File parent) {
        if (child == null || parent == null) return false;
        try {
            String c = child.getCanonicalPath();
            String p = parent.getCanonicalPath();
            return c.equals(p) || c.startsWith(p + File.separator);
        } catch (Throwable t) {
            return child.equals(parent);
        }
    }

    private void remapOpenFilesAfterRename(File oldPath, File newPath) {
        for (int i = 0; i < openTabs.size(); i++) {
            File f = openTabs.get(i);
            if (!isFileInside(f, oldPath)) continue;
            String suffix = relativeSuffix(oldPath, f);
            openTabs.set(i, suffix.isEmpty() ? newPath : new File(newPath, suffix));
        }
        if (currentFile != null && isFileInside(currentFile, oldPath)) {
            String suffix = relativeSuffix(oldPath, currentFile);
            currentFile = suffix.isEmpty() ? newPath : new File(newPath, suffix);
            if (fileNameText != null) fileNameText.setText(ProjectStore.relativePath(this, currentFile));
            if (footerFileText != null) footerFileText.setText(currentFile.getName());
        }
        String oldRel = ProjectStore.relativePath(this, oldPath);
        String newRel = ProjectStore.relativePath(this, newPath);
        Set<String> remapped = new LinkedHashSet<>();
        for (String rel : expandedExplorerDirs) {
            if (rel.equals(oldRel)) remapped.add(newRel);
            else if (rel.startsWith(oldRel + "/")) remapped.add(newRel + rel.substring(oldRel.length()));
            else remapped.add(rel);
        }
        expandedExplorerDirs.clear();
        expandedExplorerDirs.addAll(remapped);
    }

    private String relativeSuffix(File root, File child) {
        try {
            String r = root.getCanonicalPath();
            String c = child.getCanonicalPath();
            if (c.equals(r)) return "";
            if (c.startsWith(r + File.separator)) return c.substring(r.length() + 1);
        } catch (Throwable ignored) {}
        return "";
    }

    private void removeOpenFilesInside(File target) {
        openTabs.removeIf(f -> isFileInside(f, target));
        if (currentFile != null && isFileInside(currentFile, target)) {
            currentFile = null;
            if (fileNameText != null) fileNameText.setText(ProjectManager.activeProjectName(this) + " / (no file)");
            if (footerFileText != null) footerFileText.setText("No file");
        }
        String rel = ProjectStore.relativePath(this, target);
        expandedExplorerDirs.removeIf(x -> x.equals(rel) || x.startsWith(rel + "/"));
    }

    private void showCreatePath(boolean directory) {
        if (!ensureProjectOpen(directory ? "create a folder" : "create a file")) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(directory ? "assets/textures" : "src/Player.cpp");
        input.setText(directory ? "assets/" : "src/");
        new AlertDialog.Builder(this)
                .setTitle(directory ? "New folder" : "New project file")
                .setMessage(directory ? "Create a folder inside the project." : "Examples: src/Player.cpp, include/Player.hpp, assets/config.json\n(Long-press New file in Files to create a folder.)")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    try {
                        File f = ProjectStore.createProjectFile(this, input.getText().toString(), directory);
                        ProjectManager.syncFileIfLinked(this, f);
                        appendLog("Created " + ProjectStore.relativePath(this, f));
                        if (!directory && ProjectStore.isEditableText(f)) openProjectFile(f, true);
                    } catch (Throwable t) { appendLog("CREATE failed\n" + stackText(t)); setStatus("Create failed · View log", RED); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importAsset() {
        if (!ensureProjectOpen("import an asset")) return;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT_ASSET);
        } catch (Throwable t) {
            appendLog("IMPORT ASSET launch failed\n" + stackText(t));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == REQ_EXPORT_ICON) {
            try {
                Uri uri = data.getData();
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in);
                }
                if (bmp == null) throw new IllegalStateException("Could not decode selected image");
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, 512, 512, true);
                File icon = ApkExportSettings.iconFile(this);
                File parent = icon.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(icon)) {
                    if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Could not encode PNG icon");
                }
                if (scaled != bmp) scaled.recycle();
                bmp.recycle();
                appendLog("APK ICON SELECTED\n" + uri + "\nStored: " + icon);
                setStatus("APK icon selected", ACCENT);
                showApkExportDialog();
            } catch (Throwable t) {
                appendLog("APK ICON IMPORT FAILED\n" + stackText(t));
                setStatus("Icon import failed · View log", RED);
            }
            return;
        }

        if (requestCode == REQ_SAVE_APK) {
            if (lastExportedApk == null || !lastExportedApk.isFile()) { setStatus("No exported APK to save", RED); return; }
            Uri dest = data.getData();
            try (InputStream in = new FileInputStream(lastExportedApk); OutputStream out = getContentResolver().openOutputStream(dest, "w")) {
                if (out == null) throw new IllegalStateException("Could not open destination");
                byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
                appendLog("APK SAVED\n" + dest);
                setStatus("APK saved", ACCENT);
            } catch (Throwable t) { appendLog("APK SAVE failed\n" + stackText(t)); setStatus("APK save failed · View log", RED); }
            return;
        }

        if (requestCode == REQ_OPEN_PROJECT_TREE) {
            Uri tree = data.getData();
            int flags = data.getFlags();
            closeAllEditorTabs(true);
            ProjectManager.clearActiveProject(this);
            updateProjectChrome();
            refreshExplorerPane();
            setBusy(true);
            setStatus("Opening project folder…", AMBER);
            appendLog("OPEN PROJECT FOLDER\n" + tree);
            worker.submit(() -> {
                try {
                    ProjectManager.Entry entry = ProjectManager.openLinkedFolder(this, tree, flags, message -> {
                        appendLog(message);
                        runOnUiThread(() -> setStatus(message, AMBER));
                    });
                    runOnUiThread(() -> {
                        rebuildSettingsPage();
                        loadActiveProject();
                        setBusy(false);
                        setStatus("Project opened · " + entry.name, ACCENT);
                    });
                } catch (Throwable t) {
                    appendLog("OPEN PROJECT FAILED\n" + stackText(t));
                    runOnUiThread(() -> { setBusy(false); setStatus("Open project failed · View log", RED); });
                }
            });
            return;
        }

        if (requestCode != REQ_IMPORT_ASSET) return;
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        if (name == null || name.trim().isEmpty()) name = "asset_" + System.currentTimeMillis();
        name = name.replace('/', '_').replace('\\', '_');
        File out = new File(ProjectStore.assetsDir(this), name);
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("Could not open selected asset");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            ProjectManager.syncFileIfLinked(this, out);
            appendLog("Imported asset: assets/" + name + " (" + out.length() + " bytes)");
            setStatus("Asset imported · assets/" + name, ACCENT);
        } catch (Throwable t) {
            appendLog("IMPORT ASSET failed\n" + stackText(t));
            setStatus("Asset import failed · View log", RED);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean ensureProjectOpen(String action) {
        if (ProjectManager.hasActiveProject(this)) return true;
        setStatus("No project open · Projects → Open folder / New project", AMBER);
        Toast.makeText(this, "Open or create a project before you " + action + ".", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void updateProjectChrome() {
        boolean hasProject = ProjectManager.hasActiveProject(this);
        String name = hasProject ? ProjectManager.activeProjectName(this) : "No project";
        if (projectSubtitle != null) projectSubtitle.setText(name);
        if (explorerProjectText != null) explorerProjectText.setText(hasProject ? name : "No project open");
        if (!hasProject && fileNameText != null) fileNameText.setText("No project / no file");
        if (footerFileText != null) footerFileText.setText(currentFile != null ? currentFile.getName() : "No file");
    }

    private void showBlankWorkspace(boolean announce) {
        closeAllEditorTabs(false);
        updateProjectChrome();
        if (editor != null) {
            editor.setText("");
            editor.setHint("Open or create a project to start coding");
        }
        if (fileNameText != null) fileNameText.setText("No project / no file");
        if (footerFileText != null) footerFileText.setText("No file");
        refreshOpenTabs();
        refreshExplorerPane();
        updateProjectUiState();
        if (announce) setStatus("Project closed · workspace is blank", ACCENT);
    }

    private void closeActiveProject() {
        if (!ProjectManager.hasActiveProject(this)) {
            showBlankWorkspace(false);
            return;
        }
        String oldName = ProjectManager.activeProjectName(this);
        closeAllEditorTabs(true);
        RunnerEngine.stop();
        RunnerService.requestStop(this);
        SdlRunnerActivity.stopProcess(this);
        ProjectManager.clearActiveProject(this);
        rebuildSettingsPage();
        showBlankWorkspace(false);
        appendLog("PROJECT CLOSED\n" + oldName);
        setStatus("Project closed · " + oldName, ACCENT);
    }

    private void setBusy(boolean busy) {
        uiBusy = busy;
        updateProjectUiState();
    }

    private void updateProjectUiState() {
        boolean hasProject = ProjectManager.hasActiveProject(this);
        if (buildButton != null) buildButton.setEnabled(!uiBusy && hasProject);
        if (runButton != null) runButton.setEnabled(!uiBusy && hasProject);
        if (apkButton != null) apkButton.setEnabled(!uiBusy && hasProject);
        if (stopButton != null) stopButton.setEnabled(true);
        if (logButton != null) logButton.setEnabled(true);
        if (editor != null) editor.setEnabled(!uiBusy && hasProject && currentFile != null);
        if (fontDownButton != null) fontDownButton.setEnabled(hasProject && currentFile != null);
        if (fontUpButton != null) fontUpButton.setEnabled(hasProject && currentFile != null);
    }

    private void setStatus(String text, int color) {
        if (statusText == null) return;
        statusText.setText("●  " + text);
        statusText.setTextColor(color);
        if (color == RED && outputScroll != null && !outputExpanded) {
            outputExpanded = true;
            outputScroll.setVisibility(View.VISIBLE);
            if (outputToggleButton != null) outputToggleButton.setText("▤  Output ▾");
            updateOutputPreview();
        }
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
        if (outputText != null) outputText.post(this::updateOutputPreview);
    }

    private String snapshotLog() {
        synchronized (fullLog) {
            if (fullLog.length() == 0) return "No log entries yet.\n";
            return fullLog.toString();
        }
    }

    private TextView statusItem(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(10.5f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(6), 0, dp(6), 0);
        return t;
    }

    private View statusDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(25, 77, 105));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(18));
        lp.setMargins(dp(3), 0, dp(3), 0);
        v.setLayoutParams(lp);
        return v;
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

    private static String stackText(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

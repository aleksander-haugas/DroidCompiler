package com.droidx.export;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public final class BootstrapActivity extends Activity {
    private boolean launched;
    private boolean storagePrompted;
    private boolean notificationPrompted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        launchWhenReady();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!launched) launchWhenReady();
    }

    private void launchWhenReady() {
        ExportConfig cfg = ExportConfig.load(this);
        if (!cfg.validExport) {
            TextView v = new TextView(this);
            v.setText("DroidCompiler export template\n\nThis internal template is not an exported project APK.\nOpen DroidCompiler, BUILD your project, then use APK → Install/Save.");
            v.setTextColor(Color.WHITE);
            v.setBackgroundColor(Color.rgb(12, 15, 20));
            v.setTextSize(16);
            v.setGravity(Gravity.CENTER);
            v.setPadding(40, 40, 40, 40);
            setContentView(v);
            launched = true;
            return;
        }
        if (cfg.legacyStorage && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !storagePrompted) {
            storagePrompted = true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable ignored) {}
        }
        if (cfg.foreground && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !notificationPrompted) {
            notificationPrompted = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
            return;
        }
        AssetBootstrap.extractProjectAssets(this);
        launched = true;
        Intent i = new Intent(this, "console".equals(cfg.mode) ? ConsoleExportActivity.class : SdlExportActivity.class);
        startActivity(i);
        finish();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7001) launchWhenReady();
    }
}

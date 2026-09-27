package com.droidx.export;

import android.content.Context;
import android.content.pm.ActivityInfo;

import java.io.InputStream;
import java.util.Properties;

final class ExportConfig {
    final String mode;
    final String orientation;
    final boolean foreground;
    final boolean legacyStorage;
    final boolean runnerBridge;
    final boolean validExport;
    final String appName;
    final String versionName;
    final int versionCode;

    private ExportConfig(String mode, String orientation, boolean foreground, boolean legacyStorage, boolean runnerBridge, boolean validExport, String appName, String versionName, int versionCode) {
        this.mode = mode;
        this.orientation = orientation;
        this.foreground = foreground;
        this.legacyStorage = legacyStorage;
        this.runnerBridge = runnerBridge;
        this.validExport = validExport;
        this.appName = appName;
        this.versionName = versionName;
        this.versionCode = versionCode;
    }

    static ExportConfig load(Context c) {
        Properties p = new Properties();
        boolean valid = false;
        try (InputStream in = c.getAssets().open("droidx-export.properties")) {
            p.load(in);
            valid = true;
        } catch (Throwable ignored) {}
        return new ExportConfig(
                p.getProperty("mode", "sdl").trim(),
                p.getProperty("orientation", "auto").trim(),
                Boolean.parseBoolean(p.getProperty("foreground", "false")),
                Boolean.parseBoolean(p.getProperty("legacyStorage", "false")),
                Boolean.parseBoolean(p.getProperty("runnerBridge", "false")),
                valid,
                p.getProperty("appName", "C++ program"),
                p.getProperty("versionName", "1.0.0"),
                parseInt(p.getProperty("versionCode", "1"), 1));
    }

    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch (Throwable ignored) { return d; } }

    int requestedOrientation() {
        switch (orientation) {
            case "portrait": return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case "landscape": return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case "sensor": return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
            default: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }
}

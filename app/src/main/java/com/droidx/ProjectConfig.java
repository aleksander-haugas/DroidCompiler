package com.droidx;

import android.content.Context;
import android.content.pm.ActivityInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Per-project runtime settings. v0.6 keeps landscape as the default while
 * preparing the same setting for future Project Settings / APK export UI.
 */
public final class ProjectConfig {
    public static final String ORIENTATION_LANDSCAPE = "landscape";
    public static final String ORIENTATION_PORTRAIT = "portrait";
    public static final String ORIENTATION_SENSOR = "sensor";
    public static final String ORIENTATION_AUTO = "auto";

    private ProjectConfig() {}

    private static File file(Context context) {
        return new File(ProjectStore.projectDir(context), "project.properties");
    }

    public static void ensureDefaults(Context context) {
        File f = file(context);
        if (f.isFile()) return;
        setOrientation(context, ORIENTATION_LANDSCAPE);
    }

    public static String orientation(Context context) {
        Properties p = load(context);
        String v = p.getProperty("orientation", ORIENTATION_LANDSCAPE).trim().toLowerCase();
        switch (v) {
            case ORIENTATION_PORTRAIT:
            case ORIENTATION_SENSOR:
            case ORIENTATION_AUTO:
            case ORIENTATION_LANDSCAPE:
                return v;
            default:
                return ORIENTATION_LANDSCAPE;
        }
    }

    public static void setOrientation(Context context, String value) {
        try {
            File f = file(context);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Properties p = load(context);
            p.setProperty("orientation", value == null ? ORIENTATION_LANDSCAPE : value);
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "DroidCompiler project settings");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save project settings", e);
        }
    }

    public static int requestedOrientation(Context context) {
        switch (orientation(context)) {
            case ORIENTATION_PORTRAIT:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case ORIENTATION_SENSOR:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
            case ORIENTATION_AUTO:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            case ORIENTATION_LANDSCAPE:
            default:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    private static Properties load(Context context) {
        Properties p = new Properties();
        File f = file(context);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {}
        }
        return p;
    }
}

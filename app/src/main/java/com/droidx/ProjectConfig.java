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
        Properties p = loadProperties(context);
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
        Properties p = loadProperties(context);
        p.setProperty("orientation", value == null ? ORIENTATION_LANDSCAPE : value);
        storeProperties(context, p);
    }

    public static boolean foregroundRunner(Context context) {
        return Boolean.parseBoolean(loadProperties(context).getProperty("runtime.foregroundRunner", "false"));
    }

    public static void setForegroundRunner(Context context, boolean enabled) {
        Properties p = loadProperties(context);
        p.setProperty("runtime.foregroundRunner", Boolean.toString(enabled));
        storeProperties(context, p);
    }

    public static String extraPermissions(Context context) {
        return loadProperties(context).getProperty("android.permissions", "");
    }

    public static void setExtraPermissions(Context context, String permissions) {
        Properties p = loadProperties(context);
        p.setProperty("android.permissions", permissions == null ? "" : permissions);
        storeProperties(context, p);
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

    static void storeProperties(Context context, Properties p) {
        try {
            File f = file(context);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "DroidCompiler project settings");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save project settings", e);
        }
    }

    static Properties loadProperties(Context context) {
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

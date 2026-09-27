package com.droidx;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;

/** Per-project metadata used by the integrated APK exporter. */
public final class ApkExportSettings {
    public final String appName;
    public final String packageName;
    public final String versionName;
    public final int versionCode;
    public final String orientation;
    public final boolean foreground;
    public final boolean legacyStorage;

    public ApkExportSettings(String appName, String packageName, String versionName, int versionCode,
                             String orientation, boolean foreground, boolean legacyStorage) {
        this.appName = cleanName(appName);
        this.packageName = validatePackage(packageName);
        this.versionName = cleanVersionName(versionName);
        if (versionCode < 1 || versionCode > 2100000000) throw new IllegalArgumentException("Version code must be between 1 and 2100000000");
        this.versionCode = versionCode;
        this.orientation = normalizeOrientation(orientation);
        this.foreground = foreground;
        this.legacyStorage = legacyStorage;
    }

    private static File settingsFile(Context c) {
        return new File(ProjectStore.projectDir(c), ".droidx/export/export.properties");
    }

    private static Properties loadExportProperties(Context c) {
        Properties out = new Properties();
        File f = settingsFile(c);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) { out.load(in); }
            catch (Throwable ignored) {}
            return out;
        }
        // One-time compatibility import from v1.5.0-v1.5.3 project.properties.
        Properties legacy = ProjectConfig.loadProperties(c);
        for (String key : new String[]{"apk.name","apk.package","apk.versionName","apk.versionCode","apk.orientation","apk.foreground","apk.legacyStorage"}) {
            String v = legacy.getProperty(key);
            if (v != null) out.setProperty(key, v);
        }
        return out;
    }

    public static ApkExportSettings load(Context c) {
        Properties p = loadExportProperties(c);
        String appName = p.getProperty("apk.name", ProjectManager.activeProjectName(c));
        String pkg = p.getProperty("apk.package", defaultPackage(c));
        String versionName = p.getProperty("apk.versionName", "1.0.0");
        int versionCode = 1;
        try { versionCode = Math.max(1, Integer.parseInt(p.getProperty("apk.versionCode", "1").trim())); }
        catch (Throwable ignored) {}
        String orientation = p.getProperty("apk.orientation", ProjectConfig.orientation(c));
        boolean foreground = Boolean.parseBoolean(p.getProperty("apk.foreground",
                Boolean.toString(ProjectConfig.foregroundRunner(c))));
        boolean legacy = Boolean.parseBoolean(p.getProperty("apk.legacyStorage", "false"));
        try { return new ApkExportSettings(appName, pkg, versionName, versionCode, orientation, foreground, legacy); }
        catch (Throwable ignored) { return new ApkExportSettings(cleanName(ProjectManager.activeProjectName(c)), defaultPackage(c), "1.0.0", 1, ProjectConfig.orientation(c), ProjectConfig.foregroundRunner(c), false); }
    }

    public void save(Context c) {
        Properties p = new Properties();
        p.setProperty("apk.name", appName);
        p.setProperty("apk.package", packageName);
        p.setProperty("apk.versionName", versionName);
        p.setProperty("apk.versionCode", Integer.toString(versionCode));
        p.setProperty("apk.orientation", orientation);
        p.setProperty("apk.foreground", Boolean.toString(foreground));
        p.setProperty("apk.legacyStorage", Boolean.toString(legacyStorage));
        File f = settingsFile(c);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists())
            throw new IllegalStateException("Could not create APK export settings directory");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "DroidCompiler APK export settings");
        } catch (Exception e) {
            throw new IllegalStateException("Could not save APK export settings", e);
        }
    }

    public static File iconFile(Context c) {
        return new File(ProjectStore.projectDir(c), ".droidx/export/icon.png");
    }

    public static String defaultPackage(Context c) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(ProjectManager.activeProjectId(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("com.droidx.u");
            for (int i=0;i<4;i++) s.append(String.format(Locale.US, "%02x", h[i]));
            return s.toString();
        } catch (Throwable ignored) { return "com.droidx.u00000001"; }
    }

    public static String validatePackage(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() < 3 || s.length() > 150 || s.startsWith(".") || s.endsWith(".") || !s.contains("."))
            throw new IllegalArgumentException("Invalid package ID");
        String[] parts = s.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Package ID must contain at least two segments");
        for (String p : parts) {
            if (p.isEmpty() || !Character.isLetter(p.charAt(0))) throw new IllegalArgumentException("Each package segment must start with a letter");
            for (int i=1;i<p.length();i++) {
                char ch=p.charAt(i); if (!(Character.isLetterOrDigit(ch) || ch=='_')) throw new IllegalArgumentException("Package ID may contain letters, digits and _");
            }
        }
        return s;
    }

    private static String cleanName(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) s = "DroidCompiler App";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    private static String cleanVersionName(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) s = "1.0.0";
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String normalizeOrientation(String s) {
        if (ProjectConfig.ORIENTATION_PORTRAIT.equals(s) || ProjectConfig.ORIENTATION_LANDSCAPE.equals(s)
                || ProjectConfig.ORIENTATION_SENSOR.equals(s) || ProjectConfig.ORIENTATION_AUTO.equals(s)) return s;
        return ProjectConfig.ORIENTATION_AUTO;
    }
}

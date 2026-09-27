package com.droidx;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/** Selects how DroidCompiler derives the effective compiler/linker flags. */
public final class BuildSystemConfig {
    public static final String INTERNAL = "internal";
    public static final String MAKEFILE = "makefile";
    public static final String CUSTOM = "custom";

    private BuildSystemConfig() {}

    private static File file(Context c) {
        return new File(ProjectStore.projectDir(c), "project.properties");
    }

    public static String mode(Context c) {
        String v = load(c).getProperty("build.system", INTERNAL).trim().toLowerCase();
        if (MAKEFILE.equals(v) || CUSTOM.equals(v)) return v;
        return INTERNAL;
    }

    public static String makefilePath(Context c) {
        return load(c).getProperty("build.makefile", "Makefile").trim();
    }

    public static String customCommand(Context c) {
        return load(c).getProperty("build.customCommand", "clang++ -std=c++20 -O0 -pthread");
    }

    public static void save(Context c, String mode, String makefilePath, String customCommand) {
        Properties p = load(c);
        String m = mode == null ? INTERNAL : mode.trim().toLowerCase();
        if (!MAKEFILE.equals(m) && !CUSTOM.equals(m)) m = INTERNAL;
        p.setProperty("build.system", m);
        p.setProperty("build.makefile", makefilePath == null || makefilePath.trim().isEmpty() ? "Makefile" : makefilePath.trim());
        p.setProperty("build.customCommand", customCommand == null ? "" : customCommand.trim());
        store(c, p);
        ProjectStore.invalidateObjects(c);
    }

    public static void reset(Context c) {
        Properties p = load(c);
        p.remove("build.system");
        p.remove("build.makefile");
        p.remove("build.customCommand");
        store(c, p);
        ProjectStore.invalidateObjects(c);
    }

    public static String fingerprint(Context c) {
        String m = mode(c);
        String extra = "";
        if (MAKEFILE.equals(m)) {
            File f = new File(ProjectStore.projectDir(c), makefilePath(c));
            extra = "|mtime=" + (f.isFile() ? f.lastModified() : -1L) + "|size=" + (f.isFile() ? f.length() : -1L);
        }
        return m + "|makefile=" + makefilePath(c) + "|custom=" + customCommand(c) + extra;
    }

    private static Properties load(Context c) {
        Properties p = new Properties();
        File f = file(c);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            catch (Exception ignored) {}
        }
        return p;
    }

    private static void store(Context c, Properties p) {
        try {
            File f = file(c);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "DroidCompiler project settings");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save build system settings", e);
        }
    }
}

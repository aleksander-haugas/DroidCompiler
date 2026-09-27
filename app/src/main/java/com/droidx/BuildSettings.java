package com.droidx;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Per-project compiler/linker customization, intentionally close to C4droid-style flags. */
public final class BuildSettings {
    private BuildSettings() {}

    private static File file(Context c) {
        return new File(ProjectStore.projectDir(c), "project.properties");
    }

    public static String cppStandard(Context c) { return get(c, "build.cppStandard", "c++20"); }
    public static String optimization(Context c) { return get(c, "build.optimization", "-O0"); }
    public static boolean debugSymbols(Context c) { return getBool(c, "build.debugSymbols", true); }
    public static boolean warnings(Context c) { return getBool(c, "build.warnings", true); }
    public static boolean lto(Context c) { return getBool(c, "build.lto", false); }
    public static boolean strip(Context c) { return getBool(c, "build.strip", false); }
    public static String extraCompilerFlags(Context c) { return get(c, "build.extraCompilerFlags", ""); }
    public static String defines(Context c) { return get(c, "build.defines", ""); }
    public static String includePaths(Context c) { return get(c, "build.includePaths", ""); }
    public static String extraLinkerFlags(Context c) { return get(c, "build.extraLinkerFlags", ""); }
    public static String libraryPaths(Context c) { return get(c, "build.libraryPaths", ""); }
    public static String libraries(Context c) { return get(c, "build.libraries", ""); }

    public static void applyPreset(Context c, String preset) {
        Properties p = load(c);
        String k = preset == null ? "debug" : preset.trim().toLowerCase();
        if ("release".equals(k)) {
            p.setProperty("build.optimization", "-O3");
            p.setProperty("build.debugSymbols", "false");
            p.setProperty("build.warnings", "true");
            p.setProperty("build.lto", "true");
            p.setProperty("build.strip", "true");
        } else if ("size".equals(k)) {
            p.setProperty("build.optimization", "-Oz");
            p.setProperty("build.debugSymbols", "false");
            p.setProperty("build.warnings", "true");
            p.setProperty("build.lto", "true");
            p.setProperty("build.strip", "true");
            String extra = p.getProperty("build.extraLinkerFlags", "");
            if (!extra.contains("--gc-sections")) {
                p.setProperty("build.extraLinkerFlags", (extra + " -Wl,--gc-sections").trim());
            }
        } else {
            p.setProperty("build.optimization", "-O0");
            p.setProperty("build.debugSymbols", "true");
            p.setProperty("build.warnings", "true");
            p.setProperty("build.lto", "false");
            p.setProperty("build.strip", "false");
        }
        store(c, p);
        ProjectStore.invalidateObjects(c);
    }

    public static void save(Context c, String std, String opt, boolean debug, boolean warn,
                            boolean lto, boolean strip, String compilerFlags, String defines,
                            String includePaths, String linkerFlags, String libraryPaths,
                            String libraries) {
        Properties p = load(c);
        p.setProperty("build.cppStandard", safe(std, "c++20"));
        p.setProperty("build.optimization", safe(opt, "-O0"));
        p.setProperty("build.debugSymbols", Boolean.toString(debug));
        p.setProperty("build.warnings", Boolean.toString(warn));
        p.setProperty("build.lto", Boolean.toString(lto));
        p.setProperty("build.strip", Boolean.toString(strip));
        p.setProperty("build.extraCompilerFlags", nvl(compilerFlags));
        p.setProperty("build.defines", nvl(defines));
        p.setProperty("build.includePaths", nvl(includePaths));
        p.setProperty("build.extraLinkerFlags", nvl(linkerFlags));
        p.setProperty("build.libraryPaths", nvl(libraryPaths));
        p.setProperty("build.libraries", nvl(libraries));
        store(c, p);
        ProjectStore.invalidateObjects(c);
    }

    public static void reset(Context c) {
        Properties p = load(c);
        String[] keys = {
                "build.cppStandard", "build.optimization", "build.debugSymbols", "build.warnings",
                "build.lto", "build.strip", "build.extraCompilerFlags", "build.defines",
                "build.includePaths", "build.extraLinkerFlags", "build.libraryPaths", "build.libraries"
        };
        for (String key : keys) p.remove(key);
        store(c, p);
        ProjectStore.invalidateObjects(c);
    }

    public static String fingerprint(Context c) {
        return cppStandard(c) + "|" + optimization(c) + "|dbg=" + debugSymbols(c) +
                "|warn=" + warnings(c) + "|lto=" + lto(c) + "|strip=" + strip(c) +
                "|cf=" + extraCompilerFlags(c) + "|def=" + defines(c) +
                "|inc=" + includePaths(c) + "|lf=" + extraLinkerFlags(c) +
                "|lp=" + libraryPaths(c) + "|libs=" + libraries(c);
    }

    public static List<String> shellTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean single = false, dbl = false, escaped = false, started = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { cur.append(ch); escaped = false; started = true; continue; }
            if (ch == '\\' && !single) { escaped = true; started = true; continue; }
            if (ch == '\'' && !dbl) { single = !single; started = true; continue; }
            if (ch == '"' && !single) { dbl = !dbl; started = true; continue; }
            if (Character.isWhitespace(ch) && !single && !dbl) {
                if (started) { out.add(cur.toString()); cur.setLength(0); started = false; }
                continue;
            }
            cur.append(ch); started = true;
        }
        if (escaped) cur.append('\\');
        if (started) out.add(cur.toString());
        return out;
    }

    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
        }
        return out;
    }

    private static String safe(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }
    private static String nvl(String v) { return v == null ? "" : v; }
    private static boolean getBool(Context c, String key, boolean def) {
        return Boolean.parseBoolean(get(c, key, Boolean.toString(def)));
    }
    private static String get(Context c, String key, String def) {
        return load(c).getProperty(key, def);
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
            throw new IllegalStateException("Could not save build settings", e);
        }
    }
}

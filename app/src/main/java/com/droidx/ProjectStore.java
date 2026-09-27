package com.droidx;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public final class ProjectStore {
    private ProjectStore() {}

    public static File projectDir(Context context) { return ProjectManager.activeProjectDir(context); }
    public static File sourceDir(Context context) { return new File(projectDir(context), "src"); }
    public static File includeDir(Context context) { return new File(projectDir(context), "include"); }
    public static File assetsDir(Context context) { return new File(projectDir(context), "assets"); }
    public static File androidDir(Context context) { return new File(projectDir(context), "android"); }
    public static File androidJavaDir(Context context) { return new File(androidDir(context), "java"); }
    public static File makefile(Context context) { return new File(projectDir(context), "Makefile"); }
    // DroidCompiler owns only .droidx/. A user directory named build/ is normal project content.
    public static File droidxDir(Context context) { return new File(projectDir(context), ".droidx"); }
    public static File buildDir(Context context) { return new File(droidxDir(context), "build"); }
    public static File objectDir(Context context) { return new File(buildDir(context), "obj"); }
    public static File mainCpp(Context context) { return new File(sourceDir(context), "main.cpp"); }
    private static File activeMarker(Context context) { return new File(buildDir(context), "active-library.txt"); }

    public static void ensureStructure(Context context) {
        mkdirs(projectDir(context));
        mkdirs(sourceDir(context));
        mkdirs(includeDir(context));
        mkdirs(assetsDir(context));
        mkdirs(androidDir(context));
        mkdirs(androidJavaDir(context));
        mkdirs(droidxDir(context));
        mkdirs(buildDir(context));
        mkdirs(objectDir(context));
        ensureAndroidBridgeHeader(context);
        ensureAndroidReadme(context);
    }

    public static File saveMainCpp(Context context, String source) throws Exception {
        return saveTextFile(context, mainCpp(context), source);
    }

    public static File saveTextFile(Context context, File file, String text) throws Exception {
        ensureInsideProject(context, file);
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    public static String readTextFile(Context context, File file) throws Exception {
        ensureInsideProject(context, file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static File createProjectFile(Context context, String relativePath, boolean directory) throws Exception {
        ensureStructure(context);
        String rel = normalizeRelative(relativePath);
        if (rel.isEmpty()) throw new IllegalArgumentException("Path is empty");
        File target = new File(projectDir(context), rel);
        ensureInsideProject(context, target);
        if (directory) {
            mkdirs(target);
        } else {
            File parent = target.getParentFile();
            if (parent != null) mkdirs(parent);
            if (!target.exists() && !target.createNewFile()) throw new IllegalStateException("Could not create " + target);
        }
        return target;
    }

    public static File renameProjectPath(Context context, File target, String newName) throws Exception {
        if (target == null || !target.exists()) throw new IllegalArgumentException("Path does not exist");
        ensureInsideProject(context, target);
        if (target.getCanonicalFile().equals(projectDir(context).getCanonicalFile())) throw new IllegalArgumentException("Cannot rename project root");
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid name");
        }
        File parent = target.getParentFile();
        File dest = new File(parent, name);
        ensureInsideProject(context, dest);
        if (dest.exists()) throw new IllegalStateException("A file or folder with that name already exists");
        if (!target.renameTo(dest)) throw new IllegalStateException("Could not rename " + target.getName());
        return dest;
    }

    public static void deleteProjectPath(Context context, File target) throws Exception {
        if (target == null || !target.exists()) return;
        ensureInsideProject(context, target);
        if (target.getCanonicalFile().equals(projectDir(context).getCanonicalFile())) throw new IllegalArgumentException("Cannot delete project root");
        deleteRecursive(target);
    }

    private static void deleteRecursive(File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        if (!file.delete() && file.exists()) throw new IllegalStateException("Could not delete " + file);
    }

    public static String relativePath(Context context, File file) {
        try {
            String root = projectDir(context).getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return ".";
            if (path.startsWith(root + File.separator)) return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        } catch (Exception ignored) {}
        return file.getName();
    }

    public static List<File> sourceFiles(Context context) {
        ensureStructure(context);
        List<File> out = new ArrayList<>();
        if (ProjectManager.activeProjectLinked(context)) collectProjectSources(projectDir(context), out);
        else collect(sourceDir(context), out, true, false);
        Collections.sort(out, Comparator.comparing(f -> relativePath(context, f)));
        return out;
    }

    public static List<File> headerFiles(Context context) {
        ensureStructure(context);
        List<File> out = new ArrayList<>();
        if (ProjectManager.activeProjectLinked(context)) collectCompatHeaders(projectDir(context), out);
        else {
            collect(sourceDir(context), out, false, true);
            collect(includeDir(context), out, false, true);
        }
        Collections.sort(out, Comparator.comparing(f -> relativePath(context, f)));
        return out;
    }

    public static List<File> compatibilityHeaderFiles(Context context) {
        ensureStructure(context);
        List<File> out = new ArrayList<>();
        collectCompatHeaders(projectDir(context), out);
        Collections.sort(out, Comparator.comparing(f -> relativePath(context, f)));
        return out;
    }

    private static void collectCompatHeaders(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (".droidx".equals(n) || "assets".equals(n) || ".git".equals(n) || ".cxx".equals(n)) continue;
                collectCompatHeaders(f, out);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".h") || n.endsWith(".hpp") || n.endsWith(".hh") || n.endsWith(".hxx")) out.add(f);
            }
        }
    }

    public static List<File> explorerFiles(Context context) {
        ensureStructure(context);
        List<File> out = new ArrayList<>();
        collectProjectFiles(projectDir(context), out);
        Collections.sort(out, Comparator.comparing(f -> relativePath(context, f)));
        return out;
    }

    public static File preferredEditorFile(Context context) {
        ensureStructure(context);
        File rootMain = new File(projectDir(context), "main.cpp");
        if (rootMain.isFile()) return rootMain;
        File srcMain = mainCpp(context);
        if (srcMain.isFile()) return srcMain;
        File firstHeader = null;
        for (File f : explorerFiles(context)) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".c")) return f;
            if (firstHeader == null && isEditableText(f)) firstHeader = f;
        }
        return firstHeader;
    }

    public static List<File> allSourceFiles(Context context) {
        ensureStructure(context);
        List<File> out = new ArrayList<>();
        collectProjectSources(projectDir(context), out);
        Collections.sort(out, Comparator.comparing(f -> relativePath(context, f)));
        return out;
    }

    private static void collect(File dir, List<File> out, boolean sources, boolean headers) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out, sources, headers);
            else {
                String n = f.getName().toLowerCase();
                if (sources && (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx"))) out.add(f);
                if (headers && (n.endsWith(".h") || n.endsWith(".hpp") || n.endsWith(".hh") || n.endsWith(".hxx"))) out.add(f);
            }
        }
    }


    private static void collectProjectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (".droidx".equals(n) || ".git".equals(n) || ".cxx".equals(n) || ".gradle".equals(n) || ".idea".equals(n)) continue;
                collectProjectFiles(f, out);
            } else out.add(f);
        }
    }

    private static void collectProjectSources(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (".droidx".equals(n) || "assets".equals(n) || ".git".equals(n) || ".cxx".equals(n) || ".gradle".equals(n) || ".idea".equals(n)) continue;
                collectProjectSources(f, out);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".c")) out.add(f);
            }
        }
    }

    private static void collectAll(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectAll(f, out); else out.add(f);
        }
    }

    public static boolean isEditableText(File file) {
        String n = file.getName().toLowerCase();
        return n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".h") || n.endsWith(".hpp") ||
                n.endsWith(".hh") || n.endsWith(".hxx") || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".ini") ||
                n.endsWith(".cfg") || n.endsWith(".properties") || n.endsWith(".glsl") || n.endsWith(".vert") || n.endsWith(".frag") ||
                n.endsWith(".mk") || n.endsWith("makefile") || n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".xml");
    }

    public static long newestSourceOrHeaderTimestamp(Context context) {
        long newest = 0;
        List<File> sources = sourceFiles(context);
        try {
            MakefileCompat.Overrides ov = MakefileCompat.resolve(context);
            if (!ov.sources.isEmpty()) sources = ov.sources;
        } catch (Throwable ignored) {}
        for (File f : sources) newest = Math.max(newest, f.lastModified());

        List<File> hs = BuildSystemConfig.INTERNAL.equals(BuildSystemConfig.mode(context))
                ? headerFiles(context) : compatibilityHeaderFiles(context);
        for (File f : hs) newest = Math.max(newest, f.lastModified());

        if (BuildSystemConfig.MAKEFILE.equals(BuildSystemConfig.mode(context))) {
            File make = new File(projectDir(context), BuildSystemConfig.makefilePath(context));
            if (make.isFile()) newest = Math.max(newest, make.lastModified());
        }
        return newest;
    }

    private static File buildStateFile(Context context) {
        return new File(buildDir(context), "build-state.properties");
    }

    private static String currentBuildRequestFingerprint(Context context) {
        String mode = BuildSystemConfig.mode(context);
        try {
            if (BuildSystemConfig.MAKEFILE.equals(mode)) {
                MakefileCompat.Overrides ov = MakefileCompat.resolve(context);
                return "MAKEFILE|" + ov.makefileFingerprint + "|" + BuildSystemConfig.fingerprint(context);
            }
        } catch (Throwable t) {
            return "INVALID|" + BuildSystemConfig.fingerprint(context) + "|" + t.getClass().getName();
        }
        return BuildSettings.fingerprint(context) + "|" + BuildSystemConfig.fingerprint(context);
    }

    public static void recordSuccessfulBuildState(Context context, File lib) throws Exception {
        mkdirs(buildDir(context));
        Properties p = new Properties();
        p.setProperty("artifact", lib == null ? "" : lib.getName());
        p.setProperty("requestFingerprint", currentBuildRequestFingerprint(context));
        p.setProperty("newestInputTimestamp", Long.toString(newestSourceOrHeaderTimestamp(context)));
        p.setProperty("recordedAt", Long.toString(System.currentTimeMillis()));
        try (FileOutputStream out = new FileOutputStream(buildStateFile(context))) {
            p.store(out, "DroidCompiler successful build state");
        }
    }

    public static boolean isActiveBuildCurrent(Context context) {
        File lib = activeProgramLibrary(context);
        if (lib == null || !lib.isFile()) return false;
        File state = buildStateFile(context);
        if (!state.isFile()) return false;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(state)) {
            p.load(in);
        } catch (Throwable t) {
            return false;
        }
        if (!lib.getName().equals(p.getProperty("artifact", ""))) return false;
        if (!currentBuildRequestFingerprint(context).equals(p.getProperty("requestFingerprint", ""))) return false;
        return newestSourceOrHeaderTimestamp(context) <= lib.lastModified();
    }

    public static long newestHeaderTimestamp(Context context) {
        long newest = 0;
        for (File f : headerFiles(context)) newest = Math.max(newest, f.lastModified());
        return newest;
    }

    public static File objectFileFor(Context context, File source) {
        String rel = relativePath(context, source).replaceAll("[^A-Za-z0-9._-]", "_");
        String hash = Integer.toHexString(relativePath(context, source).hashCode());
        return new File(objectDir(context), rel + "." + hash + ".o");
    }

    public static File newProgramLibrary(Context context) {
        mkdirs(droidxDir(context));
        mkdirs(buildDir(context));
        return new File(buildDir(context), "libprogram_" + System.currentTimeMillis() + ".so");
    }

    public static void setActiveProgramLibrary(Context context, File lib) throws Exception {
        mkdirs(droidxDir(context));
        mkdirs(buildDir(context));
        Files.write(activeMarker(context).toPath(), lib.getName().getBytes(StandardCharsets.UTF_8));
    }

    public static File activeProgramLibrary(Context context) {
        try {
            File marker = activeMarker(context);
            if (!marker.isFile()) return null;
            String name = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            if (name.isEmpty() || name.contains("/") || name.contains("\\")) return null;
            File f = new File(buildDir(context), name);
            return f.isFile() ? f : null;
        } catch (Throwable ignored) { return null; }
    }

    public static void cleanupOldLibraries(Context context, File keep) {
        File[] files = buildDir(context).listFiles((d, n) -> n.startsWith("libprogram_") && n.endsWith(".so"));
        if (files == null) return;
        for (File f : files) {
            if (keep != null && f.equals(keep)) continue;
            if (System.currentTimeMillis() - f.lastModified() < 60_000L) continue;
            try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) {}
        }
    }

    public static void invalidateObjects(Context context) {
        File[] files = objectDir(context).listFiles();
        if (files == null) return;
        for (File f : files) try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) {}
    }

    private static void ensureAndroidBridgeHeader(Context context) {
        File header = new File(includeDir(context), "DroidXAndroid.h");
        if (header.isFile()) return;
        String text =
                "#pragma once\n" +
                "#ifdef __cplusplus\nextern \"C\" {\n#endif\n" +
                "void* droidx_android_get_jni_env(void);\n" +
                "void* droidx_android_get_activity(void);\n" +
                "int droidx_android_sdk_int(void);\n" +
                "int droidx_android_notify(const char* title, const char* text);\n" +
                "int droidx_android_keep_cpu_awake(int enabled);\n" +
                "int droidx_android_open_url(const char* url);\n" +
                "void droidx_android_log(int priority, const char* tag, const char* text);\n" +
                "#ifdef __cplusplus\n}\n#endif\n";
        try { saveTextFile(context, header, text); } catch (Throwable ignored) {}
    }

    private static void ensureAndroidReadme(Context context) {
        File readme = new File(androidDir(context), "README.txt");
        if (readme.isFile()) return;
        String text =
                "DroidCompiler Android project hooks\n\n" +
                "- C++ RUN can use include/DroidXAndroid.h for JNI host APIs, notifications and wake-lock.\n" +
                "- android/java/ is reserved for Java/Kotlin sources that will be included by APK Export.\n" +
                "- Custom Java/Kotlin is not compiled into the live RUN host yet. Use the built-in bridge for runtime integration.\n" +
                "- Extra APK permissions can be stored in project.properties under android.permissions.\n";
        try { saveTextFile(context, readme, text); } catch (Throwable ignored) {}
    }

    private static String normalizeRelative(String p) {
        if (p == null) return "";
        String s = p.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        if (s.equals("..") || s.startsWith("../") || s.contains("/../")) throw new IllegalArgumentException("Path escapes project");
        return s;
    }

    private static void ensureInsideProject(Context context, File file) throws Exception {
        String root = projectDir(context).getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) throw new SecurityException("Outside project: " + path);
    }

    private static void mkdirs(File f) {
        if (!f.exists() && !f.mkdirs() && !f.exists()) throw new IllegalStateException("Could not create " + f);
    }
}

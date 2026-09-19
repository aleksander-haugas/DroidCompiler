package com.droidx;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class ProjectStore {
    private ProjectStore() {}

    public static File projectDir(Context context) { return new File(context.getFilesDir(), "projects/HelloCpp"); }
    public static File sourceDir(Context context) { return new File(projectDir(context), "src"); }
    public static File buildDir(Context context) { return new File(projectDir(context), "build"); }
    public static File mainCpp(Context context) { return new File(sourceDir(context), "main.cpp"); }
    private static File activeMarker(Context context) { return new File(buildDir(context), "active-library.txt"); }

    public static File saveMainCpp(Context context, String source) throws Exception {
        mkdirs(sourceDir(context));
        mkdirs(buildDir(context));
        File main = mainCpp(context);
        try (FileOutputStream fos = new FileOutputStream(main, false)) {
            fos.write(source.getBytes(StandardCharsets.UTF_8));
        }
        return main;
    }

    public static File newProgramLibrary(Context context) {
        mkdirs(buildDir(context));
        return new File(buildDir(context), "libprogram_" + System.currentTimeMillis() + ".so");
    }

    public static void setActiveProgramLibrary(Context context, File lib) throws Exception {
        mkdirs(buildDir(context));
        java.nio.file.Files.write(activeMarker(context).toPath(),
                lib.getName().getBytes(StandardCharsets.UTF_8));
    }

    public static File activeProgramLibrary(Context context) {
        try {
            File marker = activeMarker(context);
            if (!marker.isFile()) return null;
            String name = new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            if (name.isEmpty() || name.contains("/") || name.contains("\\")) return null;
            File f = new File(buildDir(context), name);
            return f.isFile() ? f : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void cleanupOldLibraries(Context context, File keep) {
        File[] files = buildDir(context).listFiles((d, n) -> n.startsWith("libprogram_") && n.endsWith(".so"));
        if (files == null) return;
        for (File f : files) {
            if (keep != null && f.equals(keep)) continue;
            if (System.currentTimeMillis() - f.lastModified() < 60_000L) continue;
            // Directory permissions control deletion; read-only library files can still be unlinked.
            try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) {}
        }
    }

    private static void mkdirs(File f) {
        if (!f.exists() && !f.mkdirs() && !f.exists()) throw new IllegalStateException("Could not create " + f);
    }
}

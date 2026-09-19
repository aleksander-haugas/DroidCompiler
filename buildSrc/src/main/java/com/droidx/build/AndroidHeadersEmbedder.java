package com.droidx.build;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages the Android NDK platform graphics headers that Termux intentionally
 * removes from its ndk-sysroot package. The resulting asset is installed into
 * DroidCompiler's runtime prefix on-device next to the Termux development
 * headers, so user projects can include EGL/GLES/GLES2/GLES3/KHR normally.
 */
public final class AndroidHeadersEmbedder {
    private static final Set<String> DIRS = new LinkedHashSet<>(Arrays.asList(
            "EGL", "GLES", "GLES2", "GLES3", "KHR"
    ));

    private AndroidHeadersEmbedder() {}

    public static void prepare(File ndkRoot, File generatedAssets) throws Exception {
        if (ndkRoot == null || !ndkRoot.isDirectory()) {
            throw new IOException("Android NDK directory is unavailable: " + ndkRoot);
        }
        File include = findSysrootInclude(ndkRoot);
        if (include == null) {
            throw new IOException("Could not find NDK sysroot/usr/include under " + ndkRoot);
        }

        File gl3 = new File(include, "GLES3/gl3.h");
        File gl2 = new File(include, "GLES2/gl2.h");
        File egl = new File(include, "EGL/egl.h");
        File khr = new File(include, "KHR/khrplatform.h");
        if (!gl3.isFile() || !gl2.isFile() || !egl.isFile() || !khr.isFile()) {
            throw new IOException("Installed Android NDK is missing expected graphics headers at " + include);
        }

        mkdirs(generatedAssets);
        File out = new File(generatedAssets, "droidx-android-graphics-headers.zip");
        File tmp = new File(out.getAbsolutePath() + ".part");
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            for (String dirName : DIRS) {
                File dir = new File(include, dirName);
                if (dir.isDirectory()) packTree(include, dir, zout);
            }
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("DroidCompiler: Android graphics headers ready from " + include + " -> " + out);
    }

    private static File findSysrootInclude(File ndkRoot) {
        File prebuilt = new File(ndkRoot, "toolchains/llvm/prebuilt");
        File[] hosts = prebuilt.listFiles(File::isDirectory);
        if (hosts == null) return null;
        Arrays.sort(hosts, (a, b) -> a.getName().compareTo(b.getName()));
        for (File host : hosts) {
            File include = new File(host, "sysroot/usr/include");
            if (include.isDirectory()) return include;
        }
        return null;
    }

    private static void packTree(File includeRoot, File cur, ZipOutputStream zout) throws Exception {
        File[] xs = cur.listFiles();
        if (xs == null) return;
        Arrays.sort(xs, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : xs) {
            if (f.isDirectory()) {
                packTree(includeRoot, f, zout);
            } else if (f.isFile()) {
                String rel = includeRoot.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
                zout.putNextEntry(new ZipEntry(rel));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) if (n > 0) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
        }
    }

    private static void mkdirs(File f) throws IOException {
        if (f != null && !f.exists() && !f.mkdirs() && !f.exists()) {
            throw new IOException("Cannot create " + f);
        }
    }
}

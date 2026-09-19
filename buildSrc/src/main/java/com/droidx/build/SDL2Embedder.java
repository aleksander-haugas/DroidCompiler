package com.droidx.build;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SDL2Embedder {
    public static final String VERSION = "2.32.10";
    public static final String ROOT_NAME = "SDL2-" + VERSION;
    private static final String[] URLS = {
            "https://github.com/libsdl-org/SDL/releases/download/release-" + VERSION + "/SDL2-" + VERSION + ".zip",
            "https://www.libsdl.org/release/SDL2-" + VERSION + ".zip"
    };

    private SDL2Embedder() {}

    public static void prepare(File rootDir, File generatedRoot, File generatedAssets) throws Exception {
        File cache = new File(rootDir, ".droidx-sdl2-cache");
        mkdirs(cache); mkdirs(generatedRoot); mkdirs(generatedAssets);
        File zip = new File(cache, "SDL2-" + VERSION + ".zip");
        File src = new File(generatedRoot, ROOT_NAME);
        File marker = new File(src, ".droidx-ready");

        if (!marker.isFile() || !new File(src, "include/SDL.h").isFile()
                || !new File(src, "android-project/app/src/main/java/org/libsdl/app/SDLActivity.java").isFile()) {
            deleteTree(generatedRoot); mkdirs(generatedRoot);
            if (!zip.isFile() || zip.length() < 1024 * 1024) downloadAny(zip);
            extractZip(zip, generatedRoot);
            if (!new File(src, "CMakeLists.txt").isFile()) {
                File detected = findSdlRoot(generatedRoot);
                if (detected == null) throw new IOException("SDL2 archive missing CMakeLists.txt/include/SDL.h");
                if (!detected.equals(src)) Files.move(detected.toPath(), src.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            writeText(marker, VERSION);
        }

        File headerAsset = new File(generatedAssets, "droidx-sdl2-headers.zip");
        if (!headerAsset.isFile() || headerAsset.length() < 1024) packHeaders(new File(src, "include"), headerAsset);
        System.out.println("DroidCompiler: SDL2 " + VERSION + " ready at " + src);
    }

    private static void downloadAny(File out) throws Exception {
        Exception last = null;
        for (String spec : URLS) {
            try { System.out.println("DroidCompiler: downloading SDL2 from " + spec); download(spec, out); return; }
            catch (Exception e) { last = e; try { Files.deleteIfExists(out.toPath()); } catch (Throwable ignored) {} }
        }
        throw new IOException("Could not download SDL2 " + VERSION, last);
    }

    private static void download(String spec, File out) throws Exception {
        File tmp = new File(out.getAbsolutePath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(180000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DroidCompiler-build/0.5");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new IOException("HTTP " + code + " for " + spec); }
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[128 * 1024]; long total = 0; int n;
            while ((n = in.read(buf)) >= 0) { if (n == 0) continue; os.write(buf, 0, n); total += n; if (total > 64L*1024*1024) throw new IOException("SDL2 download too large"); }
        } finally { c.disconnect(); }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractZip(File zip, File dst) throws Exception {
        String root = dst.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e; byte[] buf = new byte[128 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dst, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe SDL2 zip entry: " + e.getName());
                if (e.isDirectory()) mkdirs(out); else { mkdirs(out.getParentFile()); try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) { int n; while ((n = zin.read(buf)) >= 0) if (n > 0) os.write(buf,0,n); } }
                zin.closeEntry();
            }
        }
    }

    private static File findSdlRoot(File dir) {
        File[] xs = dir.listFiles(File::isDirectory); if (xs == null) return null;
        for (File f : xs) if (new File(f,"CMakeLists.txt").isFile() && new File(f,"include/SDL.h").isFile()) return f;
        return null;
    }

    private static void packHeaders(File includeDir, File out) throws Exception {
        File tmp = new File(out.getAbsolutePath()+".part"); mkdirs(out.getParentFile());
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) { packDir(includeDir, includeDir, zout); }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void packDir(File root, File cur, ZipOutputStream zout) throws Exception {
        File[] xs = cur.listFiles(); if (xs == null) return;
        for (File f : xs) {
            if (f.isDirectory()) packDir(root, f, zout);
            else if (f.isFile()) {
                String rel = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');
                zout.putNextEntry(new ZipEntry("SDL2/"+rel));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { byte[] b=new byte[65536]; int n; while((n=in.read(b))>=0) if(n>0) zout.write(b,0,n); }
                zout.closeEntry();
            }
        }
    }

    private static void deleteTree(File f) throws IOException { if (!f.exists()) return; File[] xs=f.listFiles(); if(xs!=null) for(File x:xs) deleteTree(x); Files.deleteIfExists(f.toPath()); }
    private static void mkdirs(File f) throws IOException { if(f!=null && !f.exists() && !f.mkdirs() && !f.exists()) throw new IOException("Cannot create "+f); }
    private static void writeText(File f,String s) throws IOException { mkdirs(f.getParentFile()); try(Writer w=new OutputStreamWriter(new FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)){w.write(s);} }
}

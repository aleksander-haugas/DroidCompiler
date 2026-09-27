package com.droidx;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Optional package catalog for the lean DroidCompiler core.
 *
 * Clang/LLD stay APK-embedded because Android 10+ blocks execve() of downloaded
 * executables from the writable app home. Normal shared libraries are installed
 * in the private Termux-compatible prefix and loaded read-only by absolute path.
 */
public final class OptionalPackageManager {
    public static final String SDL2 = "sdl2";
    public static final String OPENGL = "opengl";
    public static final String CURL = "curl";
    public static final String OPENSSL = "openssl";
    public static final String ZLIB = "zlib";
    public static final String ZSTD = "zstd";
    public static final String SQLITE = "sqlite";
    public static final String PNG = "png";
    public static final String JPEG = "jpeg";

    public static final class PackageInfo {
        public final String id;
        public final String name;
        public final String description;
        public final String category;
        public final List<String> termuxRoots;

        PackageInfo(String id, String name, String description, String category, String... termuxRoots) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.category = category;
            this.termuxRoots = Collections.unmodifiableList(Arrays.asList(termuxRoots));
        }
    }

    private static final List<PackageInfo> CATALOG = Collections.unmodifiableList(Arrays.asList(
            new PackageInfo(SDL2, "SDL2", "SDL2 development headers (runtime bridge is bundled for reliability)", "Graphics"),
            new PackageInfo(OPENGL, "OpenGL ES / EGL", "Android GLES/EGL development headers", "Graphics"),
            new PackageInfo(PNG, "libpng", "PNG image codec headers and runtime", "Graphics", "libpng"),
            new PackageInfo(JPEG, "libjpeg-turbo", "JPEG/TurboJPEG image codec", "Graphics", "libjpeg-turbo"),
            new PackageInfo(CURL, "libcurl", "HTTP, HTTPS, WebSocket and WSS", "Network", "libcurl", "ca-certificates"),
            new PackageInfo(OPENSSL, "OpenSSL", "TLS and cryptography", "Network", "openssl"),
            new PackageInfo(SQLITE, "SQLite", "Embedded SQL database engine", "Database", "libsqlite"),
            new PackageInfo(ZLIB, "zlib", "DEFLATE compression", "Compression", "zlib"),
            new PackageInfo(ZSTD, "zstd", "Zstandard compression", "Compression", "zstd")
    ));

    private OptionalPackageManager() {}

    public static List<PackageInfo> catalog() { return CATALOG; }

    public static PackageInfo byId(String id) {
        for (PackageInfo p : CATALOG) if (p.id.equals(id)) return p;
        return null;
    }

    public static boolean isInstalled(Context c, String id) {
        return marker(c, id).isFile() && physicalFilesPresent(c, id);
    }

    public static void install(Context c, String id, ToolchainManager.Listener listener) throws Exception {
        if (!ToolchainManager.isReady(c)) {
            listener.onLog("Core C/C++ toolchain is required first; installing core...");
            ToolchainManager.installCore(c, listener);
        }

        PackageInfo pkg = byId(id);
        if (pkg == null) throw new IllegalArgumentException("Unknown optional package: " + id);

        if (SDL2.equals(id)) {
            listener.onLog("Installing SDL2 development headers...");
            ToolchainManager.installBundledSDL2Headers(c);
            if (!ToolchainManager.sdlRuntimePresent(c)) throw new IllegalStateException("Embedded SDL2 runtime is missing for this ABI.");
        } else if (OPENGL.equals(id)) {
            listener.onLog("Installing EGL/GLES/GLES2/GLES3/KHR headers...");
            ToolchainManager.installBundledAndroidGraphicsHeaders(c);
        } else {
            listener.onLog("Resolving " + pkg.name + " and dependencies...");
            ToolchainManager.installTermuxRoots(c, pkg.termuxRoots, listener);
        }

        ToolchainManager.markPackageNativeFilesReadOnly(c);
        if (!physicalFilesPresent(c, id)) {
            throw new IllegalStateException("Package install finished but validation failed: " + pkg.name);
        }

        File m = marker(c, id);
        File parent = m.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(m.toPath(), ("installed\n" + pkg.name + "\n").getBytes(StandardCharsets.UTF_8));
        if (ProjectManager.hasActiveProject(c)) ProjectStore.invalidateObjects(c);
        listener.onLog("PACKAGE READY: " + pkg.name);
    }

    /**
     * Safe removal for shared dependency graphs: deactivate the package without
     * deleting files that another installed package may depend on. A future
     * package-GC can reclaim orphaned files by dependency ownership.
     */
    public static void remove(Context c, String id, ToolchainManager.Listener listener) throws Exception {
        PackageInfo pkg = byId(id);
        if (pkg == null) throw new IllegalArgumentException("Unknown optional package: " + id);
        Files.deleteIfExists(marker(c, id).toPath());
        if (ProjectManager.hasActiveProject(c)) ProjectStore.invalidateObjects(c);
        listener.onLog("PACKAGE DEACTIVATED: " + pkg.name);
    }

    public static List<String> installedIds(Context c) {
        List<String> out = new ArrayList<>();
        for (PackageInfo p : CATALOG) if (isInstalled(c, p.id)) out.add(p.id);
        return out;
    }

    /** Load the top-level runtime for one package. Dependencies resolve through the patched Termux RUNPATH. */
    public static void preloadRuntime(Context c, String id) {
        if (!isInstalled(c, id)) return;
        File lib = runtimeLibrary(c, id);
        if (lib == null) return; // Header-only package, e.g. OpenGL ES headers.
        ToolchainManager.makeReadOnly(lib);
        System.load(lib.getAbsolutePath());
    }

    /** Best-effort preload of activated runtimes before dlopen(user program). */
    public static void preloadActivatedRuntimes(Context c) {
        // Load lower-level libraries before clients where practical.
        String[] order = { ZLIB, ZSTD, OPENSSL, SQLITE, PNG, JPEG, CURL };
        for (String id : order) {
            try { preloadRuntime(c, id); }
            catch (UnsatisfiedLinkError e) {
                // Do not break unrelated projects merely because an installed package is stale.
                android.util.Log.w("DroidCompiler", "Optional runtime preload failed for " + id + ": " + e);
            }
        }
    }

    public static File runtimeLibrary(Context c, String id) {
        File lib = new File(ToolchainManager.prefix(c), "lib");
        switch (id) {
            case SDL2: return ToolchainManager.embeddedSDL2(c);
            case CURL: return firstExisting(lib, "libcurl.so");
            case OPENSSL: return firstExisting(lib, "libssl.so");
            case ZLIB: return firstExisting(lib, "libz.so");
            case ZSTD: return firstExisting(lib, "libzstd.so");
            case SQLITE: return firstExisting(lib, "libsqlite3.so");
            case PNG: return firstExisting(lib, "libpng.so", "libpng16.so");
            case JPEG: return firstExisting(lib, "libjpeg.so", "libturbojpeg.so");
            default: return null;
        }
    }

    private static boolean physicalFilesPresent(Context c, String id) {
        File inc = new File(ToolchainManager.prefix(c), "include");
        switch (id) {
            case SDL2:
                return new File(inc, "SDL2/SDL.h").isFile() && ToolchainManager.sdlRuntimePresent(c);
            case OPENGL:
                return ToolchainManager.androidGraphicsHeadersInstalled(c);
            case CURL:
                return new File(inc, "curl/curl.h").isFile() && runtimeLibrary(c, id) != null && ToolchainManager.curlCaBundle(c).isFile();
            case OPENSSL:
                return new File(inc, "openssl/ssl.h").isFile() && runtimeLibrary(c, id) != null;
            case ZLIB:
                return new File(inc, "zlib.h").isFile() && runtimeLibrary(c, id) != null;
            case ZSTD:
                return new File(inc, "zstd.h").isFile() && runtimeLibrary(c, id) != null;
            case SQLITE:
                return new File(inc, "sqlite3.h").isFile() && runtimeLibrary(c, id) != null;
            case PNG:
                return new File(inc, "png.h").isFile() && runtimeLibrary(c, id) != null;
            case JPEG:
                return new File(inc, "jpeglib.h").isFile() && runtimeLibrary(c, id) != null;
            default:
                return false;
        }
    }

    private static File marker(Context c, String id) {
        return new File(c.getFilesDir(), "packages/" + id + ".installed");
    }

    private static File firstExisting(File dir, String... names) {
        for (String name : names) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }
}

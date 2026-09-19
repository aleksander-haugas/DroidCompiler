package com.droidx;

import android.content.Context;
import android.os.Build;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ToolchainManager {
    private static final String[] REPOSITORIES = {
            "https://ftp.fau.de/termux/termux-main",
            "https://nl.mirror.flokinet.net/termux/termux-main",
            "https://mirrors.de.sahilister.net/termux/termux-main"
    };

    public interface Listener {
        void onLog(String message);
        default void onProgress(int current, int total, String packageName) {}
    }

    private ToolchainManager() {}

    public static File prefix(Context c) {
        return new File("/data/data/" + c.getPackageName() + "/files/usr");
    }

    /** Executable code shipped in the APK and extracted by PackageManager. */
    public static File embeddedClang(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libdroidx_clang.so");
    }

    public static File embeddedLld(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libdroidx_lld.so");
    }

    public static File embeddedSDL2(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libSDL2.so");
    }

    public static File embeddedCurl(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libcurl.so");
    }

    public static boolean curlRuntimePresent(Context c) {
        return embeddedCurl(c).isFile();
    }

    public static boolean curlHeadersInstalled(Context c) {
        return new File(prefix(c), "include/curl/curl.h").isFile();
    }

    public static File curlCaBundle(Context c) {
        File p = prefix(c);
        File[] candidates = {
                new File(p, "etc/tls/cert.pem"),
                new File(p, "etc/tls/certs/ca-certificates.crt"),
                new File(p, "etc/ssl/cert.pem"),
                new File(p, "etc/ssl/certs/ca-certificates.crt")
        };
        for (File f : candidates) if (f.isFile()) return f;
        return candidates[0];
    }

    public static boolean networkDataInstalled(Context c) {
        return curlHeadersInstalled(c) && curlCaBundle(c).isFile();
    }

    public static File sdlIncludeDir(Context c) {
        return new File(prefix(c), "include/SDL2");
    }

    public static boolean sdlHeadersInstalled(Context c) {
        return new File(sdlIncludeDir(c), "SDL.h").isFile();
    }

    public static boolean sdlRuntimePresent(Context c) {
        return embeddedSDL2(c).isFile();
    }

    public static boolean androidGraphicsHeadersInstalled(Context c) {
        File inc = new File(prefix(c), "include");
        return new File(inc, "EGL/egl.h").isFile()
                && new File(inc, "GLES2/gl2.h").isFile()
                && new File(inc, "GLES3/gl3.h").isFile()
                && new File(inc, "KHR/khrplatform.h").isFile();
    }

    public static String androidTarget() {
        return targetTriple() + "35";
    }

    public static String deviceAbiLabel() {
        return Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
    }

    private static String repoArchForDevice() {
        String abi = deviceAbiLabel();
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "x86_64": return "x86_64";
            default: throw new IllegalStateException("v0.6.0 supports arm64-v8a and x86_64; device ABI is " + abi);
        }
    }

    /** Android NDK multiarch include directory name for the current device ABI. */
    public static String targetTriple() {
        String abi = deviceAbiLabel();
        switch (abi) {
            case "arm64-v8a": return "aarch64-linux-android";
            case "x86_64": return "x86_64-linux-android";
            default: throw new IllegalStateException("Unsupported device ABI: " + abi);
        }
    }

    public static File multiarchIncludeDir(Context c) {
        return new File(prefix(c), "include/" + targetTriple());
    }

    public static boolean runtimeDataInstalled(Context c) {
        File p = prefix(c);
        File marker = new File(c.getFilesDir(), "toolchain-arch.txt");
        String arch = readSmallText(marker);
        File clangRoot = new File(p, "lib/clang");
        File cxx = new File(p, "include/c++/v1");
        File multiarch = multiarchIncludeDir(c);
        return repoArchForDevice().equals(arch)
                && clangRoot.isDirectory()
                && cxx.isDirectory()
                && multiarch.isDirectory()
                && new File(multiarch, "asm/types.h").isFile()
                && new File(p, "lib").isDirectory();
    }

    public static boolean embeddedCompilerPresent(Context c) {
        return embeddedClang(c).isFile() && embeddedLld(c).isFile();
    }

    public static boolean isReady(Context c) {
        if (!embeddedCompilerPresent(c) || !runtimeDataInstalled(c)
                || !sdlRuntimePresent(c) || !sdlHeadersInstalled(c)
                || !androidGraphicsHeadersInstalled(c)
                || !curlRuntimePresent(c) || !networkDataInstalled(c)) return false;
        return !CompilerEngine.clangVersion(c).contains("cannot start");
    }

    public static File resourceDir(Context c) {
        File root = new File(prefix(c), "lib/clang");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return root;
        Arrays.sort(dirs, (a, b) -> compareVersionNames(b.getName(), a.getName()));
        return dirs[0];
    }

    private static int compareVersionNames(String a, String b) {
        String[] aa = a.split("[^0-9]+");
        String[] bb = b.split("[^0-9]+");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length && !aa[i].isEmpty() ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length && !bb[i].isEmpty() ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return a.compareTo(b);
    }

    public static void applyEnvironment(Context c, Map<String, String> env) {
        File p = prefix(c);
        File files = c.getFilesDir();
        File home = new File(files, "home");
        File tmp = new File(files, "tmp");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();

        String nativeDir = c.getApplicationInfo().nativeLibraryDir;
        String oldPath = env.get("PATH");
        env.put("PREFIX", p.getAbsolutePath());
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PATH", nativeDir + ":" + new File(p, "bin").getAbsolutePath() + ":" +
                (oldPath == null ? "/system/bin:/system/xbin" : oldPath));
        env.put("LD_LIBRARY_PATH", nativeDir + ":" + new File(p, "lib").getAbsolutePath());
        if (curlCaBundle(c).isFile()) {
            env.put("CURL_CA_BUNDLE", curlCaBundle(c).getAbsolutePath());
            env.put("SSL_CERT_FILE", curlCaBundle(c).getAbsolutePath());
        }
        env.put("LANG", "C");
        env.put("LC_ALL", "C");
    }

    public static String status(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("DroidCompiler 0.6.2 — Dual ABI · SDL2 + GLES3 + Touch + Network · targetSdk 36\n");
        s.append("ABI: ").append(deviceAbiLabel()).append(" -> repo ").append(repoArchForDevice()).append('\n');
        s.append("Embedded Clang: ").append(embeddedClang(c).isFile()).append('\n');
        s.append("Embedded LLD: ").append(embeddedLld(c).isFile()).append('\n');
        s.append("Runtime headers/sysroot: ").append(runtimeDataInstalled(c)).append('\n');
        s.append("SDL2 runtime: ").append(sdlRuntimePresent(c)).append('\n');
        s.append("SDL2 headers: ").append(sdlHeadersInstalled(c)).append('\n');
        s.append("Android EGL/GLES headers: ").append(androidGraphicsHeadersInstalled(c)).append('\n');
        s.append("libcurl runtime: ").append(curlRuntimePresent(c)).append('\n');
        s.append("curl headers: ").append(curlHeadersInstalled(c)).append('\n');
        s.append("CA bundle: ").append(curlCaBundle(c).isFile()).append('\n');
        s.append("Raw TCP/UDP sockets: true (Android/Bionic libc)\n");
        s.append("Compile target: ").append(androidTarget()).append('\n');
        s.append("Target include: ").append(multiarchIncludeDir(c)).append('\n');
        s.append("asm/types.h: ").append(new File(multiarchIncludeDir(c), "asm/types.h").isFile()).append('\n');
        s.append("Prefix: ").append(prefix(c)).append('\n');
        if (embeddedClang(c).isFile()) s.append(CompilerEngine.clangVersion(c)).append('\n');
        if (!runtimeDataInstalled(c) || !sdlHeadersInstalled(c) || !androidGraphicsHeadersInstalled(c) || !networkDataInstalled(c)) s.append("Tap INSTALL TOOLCHAIN DATA before BUILD.\n");
        else if (isReady(c)) s.append("Toolchain: READY\n");
        else s.append("Toolchain: compiler embedded, but startup/link setup still needs attention.\n");
        return s.toString();
    }

    public static void install(Context context, Listener listener) throws Exception {
        if (!context.getPackageName().equals("com.droidx")) {
            throw new IllegalStateException("applicationId must remain com.droidx because Termux prefix patching is fixed-length.");
        }
        if (!embeddedCompilerPresent(context)) {
            throw new IllegalStateException(
                    "Embedded Clang/LLD are missing for this device ABI (" + deviceAbiLabel() + "). Rebuild Android Studio with droidxAbis=x86_64,arm64-v8a so prepareEmbeddedToolchain packages the compiler for the physical phone as well as the emulator.");
        }

        String repoArch = repoArchForDevice();
        File files = context.getFilesDir();
        File marker = new File(files, "toolchain-arch.txt");
        String installedArch = readSmallText(marker);

        if (!installedArch.isEmpty() && !installedArch.equals(repoArch)) {
            listener.onLog("Removing runtime toolchain data for " + installedArch + " (device is " + repoArch + ")...");
            deleteTree(new File(files, "usr"));
        } else if (!runtimeDataInstalled(context) && new File(files, "usr").exists()) {
            listener.onLog("Runtime toolchain data is incomplete; cleaning prefix before reinstall...");
            deleteTree(new File(files, "usr"));
        }

        File usr = new File(files, "usr");
        File cache = new File(files, "toolchain-cache");
        mkdirs(usr);
        mkdirs(cache);
        mkdirs(new File(files, "home"));
        mkdirs(new File(files, "tmp"));

        if (!runtimeDataInstalled(context) || !networkDataInstalled(context)) {
            listener.onLog("Downloading " + repoArch + " compiler/sysroot/network development packages...");
            RepoIndex repoIndex = fetchIndex(repoArch);
            listener.onLog("Repository: " + repoIndex.base);

            TermuxPackageIndex index = TermuxPackageIndex.parse(repoIndex.text);
            List<String> roots = new ArrayList<>();
            if (!runtimeDataInstalled(context)) roots.add("clang");
            if (!networkDataInstalled(context)) {
                roots.add("libcurl");
                roots.add("ca-certificates");
            }
            List<TermuxPackageIndex.PackageInfo> packages = mergePackageClosures(index, roots, listener);
            listener.onLog("Resolved " + packages.size() + " packages for: " + roots);

            int pos = 0;
            for (TermuxPackageIndex.PackageInfo p : packages) {
                pos++;
                listener.onProgress(pos, packages.size(), p.name);
                listener.onLog("[" + pos + "/" + packages.size() + "] " + p.name + " " + p.version);

                File deb = new File(cache, safeName(p.name + "_" + p.version + ".deb"));
                download(repoIndex.base + "/" + p.filename, deb, listener);
                if (!p.sha256.isEmpty()) {
                    String actual = sha256(deb);
                    if (!actual.equalsIgnoreCase(p.sha256)) {
                        deb.delete();
                        throw new IllegalStateException("SHA-256 mismatch for " + p.name);
                    }
                }
                DebExtractor.extract(deb, files, listener);
                deb.delete();
            }
            writeSmallText(marker, repoArch);
        } else {
            listener.onLog("Runtime Clang/sysroot + libcurl development data already installed; keeping existing files.");
        }

        listener.onLog("Installing bundled SDL2 " + "2.32.10" + " headers...");
        installBundledSDL2Headers(context);
        listener.onLog("SDL2 headers: " + sdlIncludeDir(context));

        listener.onLog("Installing Android NDK EGL/GLES/GLES2/GLES3/KHR headers...");
        installBundledAndroidGraphicsHeaders(context);
        listener.onLog("OpenGL ES headers: " + new File(prefix(context), "include/GLES3"));

        String version = CompilerEngine.clangVersion(context);
        if (version.contains("cannot start")) {
            throw new IllegalStateException("APK-embedded Clang cannot execute: " + version);
        }
        if (!runtimeDataInstalled(context)) {
            throw new IllegalStateException("Packages extracted, but C++ headers/resource directory are incomplete.");
        }
        if (!sdlRuntimePresent(context)) {
            throw new IllegalStateException("libSDL2.so is missing from the APK nativeLibraryDir. Rebuild Android Studio project; prepareSDL2/CMake must run.");
        }
        if (!sdlHeadersInstalled(context)) {
            throw new IllegalStateException("SDL2 headers could not be installed from APK assets.");
        }
        if (!androidGraphicsHeadersInstalled(context)) {
            throw new IllegalStateException("Android EGL/GLES platform headers could not be installed from APK assets.");
        }
        if (!curlRuntimePresent(context)) {
            throw new IllegalStateException("libcurl.so is missing from nativeLibraryDir. Rebuild Android Studio so prepareEmbeddedToolchain embeds the libcurl dependency closure.");
        }
        if (!curlHeadersInstalled(context)) {
            throw new IllegalStateException("curl/curl.h is missing from the runtime development prefix.");
        }
        if (!curlCaBundle(context).isFile()) {
            throw new IllegalStateException("CA certificate bundle is missing; HTTPS/WSS verification would not be reliable.");
        }
        listener.onLog("Networking READY: raw TCP/UDP sockets + libcurl HTTP/HTTPS/WS/WSS");
        listener.onLog("CA bundle: " + curlCaBundle(context));
        listener.onLog("TOOLCHAIN READY");
        listener.onLog(version);
        listener.onLog("Compiler executable: " + embeddedClang(context));
        listener.onLog("Resource dir: " + resourceDir(context));
    }

    private static void installBundledSDL2Headers(Context context) throws Exception {
        installBundledHeaderZip(context, "droidx-sdl2-headers.zip", "SDL2");
    }

    private static void installBundledAndroidGraphicsHeaders(Context context) throws Exception {
        installBundledHeaderZip(context, "droidx-android-graphics-headers.zip", "Android graphics");
    }

    private static void installBundledHeaderZip(Context context, String assetName, String label) throws Exception {
        File dstRoot = new File(prefix(context), "include");
        mkdirs(dstRoot);
        String canonicalRoot = dstRoot.getCanonicalPath() + File.separator;
        try (InputStream raw = context.getAssets().open(assetName);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dstRoot, e.getName());
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    throw new IOException("Unsafe " + label + " header asset entry: " + e.getName());
                }
                if (e.isDirectory()) {
                    mkdirs(out);
                } else {
                    mkdirs(out.getParentFile());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zin.read(buf)) >= 0) if (n > 0) os.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static List<TermuxPackageIndex.PackageInfo> mergePackageClosures(
            TermuxPackageIndex index, List<String> roots, Listener listener) throws Exception {
        LinkedHashMap<String, TermuxPackageIndex.PackageInfo> merged = new LinkedHashMap<>();
        for (String root : roots) {
            for (TermuxPackageIndex.PackageInfo p : index.dependencyClosure(root, listener)) {
                merged.put(p.name, p);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static final class RepoIndex {
        final String base, text;
        RepoIndex(String base, String text) { this.base = base; this.text = text; }
    }

    private static RepoIndex fetchIndex(String repoArch) throws Exception {
        Exception last = null;
        for (String base : REPOSITORIES) {
            try {
                String u = base + "/dists/stable/main/binary-" + repoArch + "/Packages";
                byte[] bytes = downloadBytes(u, 8 * 1024 * 1024);
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.contains("Package: clang\n") || text.contains("Package: clang\r\n")) return new RepoIndex(base, text);
                last = new IllegalStateException("Invalid Packages index from " + base);
            } catch (Exception e) { last = e; }
        }
        throw new IllegalStateException("Could not download Termux " + repoArch + " package index", last);
    }

    private static byte[] downloadBytes(String url, int maxBytes) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int total = 0, n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > maxBytes) throw new IllegalStateException("Download too large: " + url);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static void download(String url, File outFile, Listener listener) throws Exception {
        HttpURLConnection c = open(url);
        long expected = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buf = new byte[128 * 1024];
            long done = 0, next = 5L * 1024 * 1024;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                done += n;
                if (done >= next) {
                    listener.onLog("  downloaded " + mb(done) + (expected > 0 ? " / " + mb(expected) : ""));
                    next += 5L * 1024 * 1024;
                }
            }
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DroidCompiler/0.5 Android");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code + " for " + url);
        }
        return c;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.US, "%02x", b & 0xff));
        return s.toString();
    }

    private static String safeName(String s) { return s.replaceAll("[^A-Za-z0-9._+-]", "_"); }
    private static String mb(long bytes) { return String.format(Locale.US, "%.1f MiB", bytes / 1048576.0); }

    private static void mkdirs(File f) {
        if (!f.exists() && !f.mkdirs() && !f.exists()) throw new IllegalStateException("Cannot create " + f);
    }

    private static void deleteTree(File f) throws Exception {
        final String path = f.getAbsolutePath();
        final android.system.StructStat st;
        try {
            st = android.system.Os.lstat(path);
        } catch (android.system.ErrnoException e) {
            if (e.errno == android.system.OsConstants.ENOENT) return;
            throw new IllegalStateException("Cannot lstat stale toolchain path: " + path, e);
        }
        if (android.system.OsConstants.S_ISDIR(st.st_mode)) {
            try { android.system.Os.chmod(path, 0700); } catch (Throwable ignored) {}
            String[] names = f.list();
            if (names == null) throw new IllegalStateException("Cannot list " + path);
            for (String name : names) deleteTree(new File(f, name));
        }
        try {
            java.nio.file.Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + path, e);
        }
    }

    private static String readSmallText(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) { return ""; }
    }

    private static void writeSmallText(File f, String value) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }
}

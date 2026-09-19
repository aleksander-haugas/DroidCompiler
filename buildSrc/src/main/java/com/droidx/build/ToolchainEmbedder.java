package com.droidx.build;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * Build-time bootstrapper for the Android 10+ compatible compiler layout.
 *
 * It downloads the official Termux clang dependency closure on the desktop,
 * extracts it to a Gradle cache, patches the fixed Termux package id to com.droidx,
 * and copies the compiler executable + its native shared dependencies into a
 * generated jniLibs directory. Android then installs those files in nativeLibraryDir,
 * which is executable even when targetSdk >= 29.
 */
public final class ToolchainEmbedder {
    private static final String[] REPOSITORIES = {
            "https://ftp.fau.de/termux/termux-main",
            "https://nl.mirror.flokinet.net/termux/termux-main",
            "https://mirrors.de.sahilister.net/termux/termux-main"
    };
    private static final byte[] OLD_PACKAGE = "com.termux".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_PACKAGE = "com.droidx".getBytes(StandardCharsets.UTF_8);
    private static final String EMBED_VERSION = "termux-clang-curl-21-v5";

    private ToolchainEmbedder() {}

    public static void prepare(File rootDir, File outDir, String abiCsv) throws Exception {
        List<String> androidAbis = new ArrayList<>();
        for (String x : abiCsv.split(",")) {
            x = x.trim();
            if (!x.isEmpty()) androidAbis.add(x);
        }
        if (androidAbis.isEmpty()) throw new IllegalArgumentException("No DroidCompiler ABIs selected");

        File cacheRoot = new File(rootDir, ".droidx-toolchain-cache");
        mkdirs(cacheRoot);
        mkdirs(outDir);

        for (String androidAbi : androidAbis) {
            String repoArch = repoArch(androidAbi);
            File abiOut = new File(outDir, androidAbi);
            File marker = new File(cacheRoot, "embedded-" + androidAbi + ".version");
            File clangOut = new File(abiOut, "libdroidx_clang.so");
            File lldOut = new File(abiOut, "libdroidx_lld.so");
            File curlOut = new File(abiOut, "libcurl.so");
            if (marker.isFile()
                    && EMBED_VERSION.equals(readText(marker).trim())
                    && clangOut.isFile() && clangOut.length() > 1024
                    && lldOut.isFile() && lldOut.length() > 1024
                    && curlOut.isFile() && curlOut.length() > 1024) {
                System.out.println("DroidCompiler embedded toolchain cached for " + androidAbi);
                continue;
            }

            deleteTree(abiOut);
            mkdirs(abiOut);
            File archCache = new File(cacheRoot, repoArch);
            File stage = new File(archCache, "stage");
            File downloads = new File(archCache, "downloads");
            mkdirs(stage);
            mkdirs(downloads);

            System.out.println("DroidCompiler: preparing embedded Clang for " + androidAbi + " (" + repoArch + ")");
            RepoIndex ri = fetchIndex(repoArch);
            PackageIndex index = PackageIndex.parse(ri.text);
            List<PackageInfo> packages = mergeClosures(index, "clang", "libcurl", "ca-certificates");
            System.out.println("DroidCompiler: " + packages.size() + " packages in compiler + libcurl dependency closure");

            List<LinkSpec> links = new ArrayList<>();
            int n = 0;
            for (PackageInfo p : packages) {
                n++;
                File deb = new File(downloads, safeName(p.name + "_" + p.version + ".deb"));
                if (!deb.isFile() || deb.length() < 128) {
                    System.out.println("  [" + n + "/" + packages.size() + "] downloading " + p.name + " " + p.version);
                    download(ri.base + "/" + p.filename, deb);
                } else {
                    System.out.println("  [" + n + "/" + packages.size() + "] cached " + p.name + " " + p.version);
                }
                if (!p.sha256.isEmpty()) {
                    String actual = sha256(deb);
                    if (!actual.equalsIgnoreCase(p.sha256)) {
                        Files.deleteIfExists(deb.toPath());
                        throw new IOException("SHA-256 mismatch for " + p.name);
                    }
                }
                extractDeb(deb, stage, links);
            }
            resolveLinks(stage, links);

            File usr = new File(stage, "usr");
            File bin = new File(usr, "bin");
            File lib = new File(usr, "lib");
            File clang = findElf(bin, Arrays.asList("clang", "clang-21", "clang-20"), "clang-");
            File lld = findElf(bin, Arrays.asList("lld", "ld.lld", "lld-21", "lld-20"), "lld-");
            if (clang == null) throw new IOException("Could not locate Termux clang executable in " + bin);
            if (lld == null) throw new IOException("Could not locate Termux lld executable in " + bin);

            copyFile(clang, clangOut);
            copyFile(lld, lldOut);

            // Copy top-level ELF shared objects needed by clang/lld at runtime.
            Map<String, String> renames = new LinkedHashMap<>();
            if (lib.isDirectory()) {
                File[] libs = lib.listFiles();
                if (libs != null) {
                    Arrays.sort(libs, Comparator.comparing(File::getName));
                    for (File f : libs) {
                        if (!f.isFile() || !isElf(f)) continue;
                        String name = f.getName();
                        if (!name.contains(".so")) continue;
                        String packaged = packageSoName(name);
                        renames.put(name, packaged);
                        File dst = new File(abiOut, packaged);
                        if (!dst.exists() || dst.length() != f.length()) copyFile(f, dst);
                    }
                }
            }

            // Patch DT_NEEDED/SONAME strings if a versioned .so filename had to be
            // converted to Android's lib*.so packaging convention. Length is preserved.
            File[] packaged = abiOut.listFiles();
            if (packaged != null) {
                for (File f : packaged) {
                    if (!f.isFile() || !isElf(f)) continue;
                    patchFixedStrings(f, renames);
                }
            }

            if (!curlOut.isFile()) {
                throw new IOException("libcurl.so was not produced by Termux libcurl package closure for " + androidAbi);
            }
            writeText(marker, EMBED_VERSION);
            System.out.println("DroidCompiler: embedded compiler/network runtime ready for " + androidAbi +
                    " (" + human(clangOut.length()) + " clang, " + human(lldOut.length()) + " lld, " + human(curlOut.length()) + " libcurl)");
        }
    }

    private static List<PackageInfo> mergeClosures(PackageIndex index, String... roots) {
        LinkedHashMap<String, PackageInfo> merged = new LinkedHashMap<>();
        for (String root : roots) {
            for (PackageInfo p : index.dependencyClosure(root)) merged.put(p.name, p);
        }
        return new ArrayList<>(merged.values());
    }

    private static String repoArch(String abi) {
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "x86_64": return "x86_64";
            default: throw new IllegalArgumentException("Unsupported ABI for v0.5: " + abi);
        }
    }

    private static final class RepoIndex {
        final String base, text;
        RepoIndex(String base, String text) { this.base = base; this.text = text; }
    }

    private static RepoIndex fetchIndex(String arch) throws Exception {
        Exception last = null;
        for (String base : REPOSITORIES) {
            try {
                String u = base + "/dists/stable/main/binary-" + arch + "/Packages";
                String text = new String(downloadBytes(u, 16 * 1024 * 1024), StandardCharsets.UTF_8);
                if (text.contains("Package: clang\n") || text.contains("Package: clang\r\n")) {
                    return new RepoIndex(base, text);
                }
                last = new IOException("Invalid Packages index from " + base);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("Could not download Termux Packages index for " + arch, last);
    }

    private static HttpURLConnection open(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DroidCompiler-build/0.5");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + spec);
        }
        return c;
    }

    private static byte[] downloadBytes(String spec, int max) throws Exception {
        HttpURLConnection c = open(spec);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int total = 0, r;
            while ((r = in.read(buf)) >= 0) {
                if (r == 0) continue;
                total += r;
                if (total > max) throw new IOException("Download too large: " + spec);
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static void download(String spec, File out) throws Exception {
        mkdirs(out.getParentFile());
        File tmp = new File(out.getAbsolutePath() + ".part");
        HttpURLConnection c = open(spec);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
            copy(in, os);
        } finally { c.disconnect(); }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractDeb(File deb, File stage, List<LinkSpec> links) throws Exception {
        byte[] dataTar = null;
        String dataName = null;
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            while ((e = ar.getNextArEntry()) != null) {
                if (e.getName().startsWith("data.tar")) {
                    dataName = e.getName();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    copy(ar, b);
                    dataTar = b.toByteArray();
                    break;
                }
            }
        }
        if (dataTar == null || dataName == null) throw new IOException("No data.tar in " + deb);

        try (InputStream raw = new ByteArrayInputStream(dataTar);
             InputStream dec = decompressor(raw, dataName);
             TarArchiveInputStream tar = new TarArchiveInputStream(dec)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                String rel = mapArchivePath(e.getName());
                if (rel == null || rel.isEmpty()) continue;
                File dest = safeChild(stage, rel);
                if (e.isDirectory()) {
                    mkdirs(dest);
                } else if (e.isSymbolicLink() || e.isLink()) {
                    links.add(new LinkSpec(rel, e.getLinkName()));
                } else if (e.isFile()) {
                    mkdirs(dest.getParentFile());
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        copyPatched(tar, out);
                    }
                    if ((e.getMode() & 0111) != 0) dest.setExecutable(true, false);
                }
            }
        }
    }

    private static InputStream decompressor(InputStream in, String name) throws IOException {
        if (name.endsWith(".xz")) return new XZCompressorInputStream(in, true);
        if (name.endsWith(".gz")) return new GzipCompressorInputStream(in, true);
        if (name.endsWith(".bz2")) return new BZip2CompressorInputStream(in, true);
        if (name.endsWith(".zst") || name.endsWith(".zstd")) return new ZstdCompressorInputStream(in);
        if (name.equals("data.tar")) return in;
        throw new IOException("Unsupported deb compression: " + name);
    }

    private static String mapArchivePath(String raw) {
        if (raw == null) return null;
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        String p = "data/data/com.termux/files/";
        if (n.startsWith(p)) return n.substring(p.length());
        if (n.equals("data/data/com.termux/files")) return "";
        if (n.startsWith("usr/") || n.equals("usr") || n.startsWith("home/") || n.equals("home")) return n;
        return null;
    }

    private static File safeChild(File root, String rel) throws IOException {
        File f = new File(root, rel);
        String rp = root.getCanonicalPath();
        String fp = f.getCanonicalPath();
        if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) throw new IOException("Unsafe archive path " + rel);
        return f;
    }

    private static final class LinkSpec {
        final String destRel, target;
        LinkSpec(String destRel, String target) { this.destRel = destRel; this.target = target; }
    }

    private static void resolveLinks(File stage, List<LinkSpec> links) throws Exception {
        List<LinkSpec> pending = new ArrayList<>(links);
        for (int pass = 0; pass < 8 && !pending.isEmpty(); pass++) {
            Iterator<LinkSpec> it = pending.iterator();
            while (it.hasNext()) {
                LinkSpec l = it.next();
                File dest = safeChild(stage, l.destRel);
                File target = resolveLinkTarget(stage, dest, l.target);
                if (target != null && target.isFile()) {
                    mkdirs(dest.getParentFile());
                    Files.copy(target.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    it.remove();
                }
            }
        }
    }

    private static File resolveLinkTarget(File stage, File dest, String rawTarget) throws Exception {
        if (rawTarget == null || rawTarget.isEmpty()) return null;
        String patched = rawTarget.replace("com.termux", "com.droidx").replace('\\', '/');
        String old = "/data/data/com.droidx/files/";
        if (patched.startsWith(old)) return safeChild(stage, patched.substring(old.length()));
        if (patched.startsWith("/")) return null;
        return new File(dest.getParentFile(), patched).getCanonicalFile();
    }

    private static File findElf(File dir, List<String> preferred, String prefix) throws Exception {
        for (String n : preferred) {
            File f = new File(dir, n);
            if (f.isFile() && isElf(f)) return f;
        }
        File[] fs = dir.listFiles();
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparing(File::getName));
            for (File f : fs) if (f.isFile() && f.getName().startsWith(prefix) && isElf(f)) return f;
        }
        return null;
    }

    private static boolean isElf(File f) throws Exception {
        if (!f.isFile() || f.length() < 4) return false;
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 0x7f && in.read() == 'E' && in.read() == 'L' && in.read() == 'F';
        }
    }

    private static String packageSoName(String n) {
        if (n.matches("lib[^/]+\\.so")) return n;
        int i = n.indexOf(".so.");
        if (i > 0) {
            String stem = n.substring(0, i);
            String ver = n.substring(i + 4).replace('.', '_');
            String out = stem + "_" + ver + ".so";
            if (out.length() != n.length()) throw new IllegalStateException("Length-changing .so rename: " + n + " -> " + out);
            return out;
        }
        if (n.endsWith(".so")) return n;
        // Android only extracts lib*.so from jniLibs. Keep length stable where possible.
        String safe = n.replace('.', '_');
        if (!safe.startsWith("lib")) safe = "lib" + safe;
        if (!safe.endsWith(".so")) safe += ".so";
        return safe;
    }

    private static void patchFixedStrings(File f, Map<String, String> replacements) throws Exception {
        byte[] data = Files.readAllBytes(f.toPath());
        boolean changed = false;
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            if (e.getKey().equals(e.getValue())) continue;
            byte[] a = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            if (a.length != b.length) continue;
            for (int i = 0; i <= data.length - a.length; i++) {
                if (matches(data, i, a)) {
                    System.arraycopy(b, 0, data, i, b.length);
                    i += a.length - 1;
                    changed = true;
                }
            }
        }
        if (changed) Files.write(f.toPath(), data);
    }

    private static void copyPatched(InputStream in, OutputStream out) throws IOException {
        byte[] data = readAll(in);
        for (int i = 0; i <= data.length - OLD_PACKAGE.length; i++) {
            if (matches(data, i, OLD_PACKAGE)) {
                System.arraycopy(NEW_PACKAGE, 0, data, i, NEW_PACKAGE.length);
                i += OLD_PACKAGE.length - 1;
            }
        }
        out.write(data);
    }

    private static boolean matches(byte[] data, int at, byte[] needle) {
        if (at < 0 || at + needle.length > data.length) return false;
        for (int i = 0; i < needle.length; i++) if (data[at + i] != needle[i]) return false;
        return true;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copyFile(File a, File b) throws Exception {
        mkdirs(b.getParentFile());
        Files.copy(a.toPath(), b.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int r;
        while ((r = in.read(buf)) >= 0) if (r > 0) out.write(buf, 0, r);
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[128 * 1024];
            int r;
            while ((r = in.read(buf)) >= 0) if (r > 0) md.update(buf, 0, r);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private static void mkdirs(File f) throws IOException {
        if (f == null || f.isDirectory()) return;
        if (!f.mkdirs() && !f.isDirectory()) throw new IOException("Cannot create " + f);
    }

    private static void deleteTree(File f) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        if (!f.delete() && f.exists()) throw new IOException("Cannot delete " + f);
    }

    private static String safeName(String s) { return s.replaceAll("[^A-Za-z0-9._+-]", "_"); }
    private static String readText(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static void writeText(File f, String s) throws Exception { mkdirs(f.getParentFile()); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
    private static String human(long n) { return String.format(Locale.ROOT, "%.1f MiB", n / 1048576.0); }

    private static final class PackageInfo {
        final String name, version, filename, sha256, depends, preDepends, provides;
        PackageInfo(Map<String, String> f) {
            name = f.getOrDefault("Package", "");
            version = f.getOrDefault("Version", "");
            filename = f.getOrDefault("Filename", "");
            sha256 = f.getOrDefault("SHA256", "");
            depends = f.getOrDefault("Depends", "");
            preDepends = f.getOrDefault("Pre-Depends", "");
            provides = f.getOrDefault("Provides", "");
        }
    }

    private static final class PackageIndex {
        final Map<String, PackageInfo> packages = new HashMap<>();
        final Map<String, String> providers = new HashMap<>();

        static PackageIndex parse(String text) throws Exception {
            PackageIndex idx = new PackageIndex();
            BufferedReader br = new BufferedReader(new StringReader(text));
            Map<String, String> fields = new HashMap<>();
            String key = null, line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    idx.add(fields); fields = new HashMap<>(); key = null; continue;
                }
                if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && key != null) {
                    fields.put(key, fields.get(key) + " " + line.trim()); continue;
                }
                int c = line.indexOf(':');
                if (c <= 0) continue;
                key = line.substring(0, c);
                fields.put(key, line.substring(c + 1).trim());
            }
            idx.add(fields);
            for (PackageInfo p : idx.packages.values()) {
                for (String g : splitDeps(p.provides)) {
                    String n = clean(g);
                    if (!n.isEmpty()) idx.providers.putIfAbsent(n, p.name);
                }
            }
            return idx;
        }

        void add(Map<String, String> f) {
            if (f.isEmpty()) return;
            PackageInfo p = new PackageInfo(f);
            if (!p.name.isEmpty() && !p.filename.isEmpty()) packages.put(p.name, p);
        }

        PackageInfo get(String n) {
            PackageInfo p = packages.get(n);
            if (p != null) return p;
            String x = providers.get(n);
            return x == null ? null : packages.get(x);
        }

        List<PackageInfo> dependencyClosure(String root) {
            List<PackageInfo> out = new ArrayList<>();
            Set<String> visiting = new HashSet<>(), done = new HashSet<>();
            visit(root, visiting, done, out);
            return out;
        }

        void visit(String req, Set<String> visiting, Set<String> done, List<PackageInfo> out) {
            if (req.isEmpty() || done.contains(req) || visiting.contains(req)) return;
            PackageInfo p = get(req);
            if (p == null) { done.add(req); return; }
            if (done.contains(p.name)) { done.add(req); return; }
            visiting.add(p.name);
            String merged = p.preDepends;
            if (!p.depends.isEmpty()) merged = merged.isEmpty() ? p.depends : merged + ", " + p.depends;
            for (String group : splitDeps(merged)) {
                String selected = "";
                for (String alt : group.split("\\|")) {
                    String n = clean(alt);
                    if (!n.isEmpty() && get(n) != null) { selected = n; break; }
                }
                if (!selected.isEmpty()) visit(selected, visiting, done, out);
            }
            visiting.remove(p.name);
            done.add(p.name); done.add(req); out.add(p);
        }
    }

    private static List<String> splitDeps(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        int paren = 0, bracket = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') paren++; else if (c == ')' && paren > 0) paren--;
            else if (c == '[') bracket++; else if (c == ']' && bracket > 0) bracket--;
            else if (c == ',' && paren == 0 && bracket == 0) { out.add(s.substring(start, i).trim()); start = i + 1; }
        }
        String tail = s.substring(start).trim(); if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int p = s.indexOf('('); if (p >= 0) s = s.substring(0, p).trim();
        int b = s.indexOf('['); if (b >= 0) s = s.substring(0, b).trim();
        int c = s.indexOf(':'); if (c >= 0) s = s.substring(0, c).trim();
        if (s.startsWith("${")) return "";
        return s.toLowerCase(Locale.ROOT);
    }
}

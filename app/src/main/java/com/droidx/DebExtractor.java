package com.droidx;

import android.system.Os;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class DebExtractor {
    private static final byte[] OLD_PACKAGE = "com.termux".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_PACKAGE = "com.droidx".getBytes(StandardCharsets.UTF_8);

    private DebExtractor() {}

    static void extract(File deb, File filesDir, ToolchainManager.Listener listener) throws Exception {
        File dataTar = new File(deb.getParentFile(), deb.getName() + ".data");
        String compression = null;

        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            while ((e = ar.getNextArEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("data.tar")) {
                    compression = n;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dataTar))) {
                        copy(ar, out);
                    }
                    break;
                }
            }
        }

        if (compression == null || !dataTar.isFile()) {
            throw new IOException("No data.tar payload in " + deb.getName());
        }

        File usrRoot = new File(filesDir, "usr");
        File homeRoot = new File(filesDir, "home");
        if (!usrRoot.exists() && !usrRoot.mkdirs()) throw new IOException("Cannot create " + usrRoot);
        if (!homeRoot.exists() && !homeRoot.mkdirs()) throw new IOException("Cannot create " + homeRoot);

        List<HardLink> hardLinks = new ArrayList<>();
        try (InputStream raw = new BufferedInputStream(new FileInputStream(dataTar));
             InputStream compressed = decompressor(raw, compression);
             TarArchiveInputStream tar = new TarArchiveInputStream(compressed)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                MappedPath mapped = mapPath(entry.getName(), usrRoot, homeRoot);
                if (mapped == null) continue;
                File dest = mapped.file;
                ensureInside(dest, mapped.root);

                if (entry.isDirectory()) {
                    if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot mkdir " + dest);
                    applyMode(dest, entry.getMode(), true);
                    continue;
                }

                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot mkdir " + parent);
                }

                if (entry.isSymbolicLink()) {
                    deleteIfExists(dest);
                    String target = patchString(entry.getLinkName());
                    Os.symlink(target, dest.getAbsolutePath());
                    continue;
                }

                if (entry.isLink()) {
                    hardLinks.add(new HardLink(entry.getLinkName(), dest, entry.getMode()));
                    continue;
                }

                if (entry.isFile()) {
                    deleteIfExists(dest);
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        copyPatched(tar, out);
                    }
                    applyMode(dest, entry.getMode(), false);
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            dataTar.delete();
        }

        for (HardLink h : hardLinks) {
            MappedPath target = mapPath(h.target, usrRoot, homeRoot);
            if (target == null || !target.file.exists()) continue;
            deleteIfExists(h.dest);
            try {
                Os.link(target.file.getAbsolutePath(), h.dest.getAbsolutePath());
            } catch (Throwable ignored) {
                // Hard links are uncommon in the compiler packages. If linking fails,
                // fall back to a byte copy so installation can continue.
                try (InputStream in = new FileInputStream(target.file);
                     OutputStream out = new FileOutputStream(h.dest)) {
                    copy(in, out);
                }
            }
            applyMode(h.dest, h.mode, false);
        }

        listener.onLog("Extracted " + deb.getName());
    }

    private static InputStream decompressor(InputStream in, String name) throws IOException {
        if (name.endsWith(".xz")) return new XZCompressorInputStream(in, true);
        if (name.endsWith(".gz")) return new GzipCompressorInputStream(in, true);
        if (name.endsWith(".bz2")) return new BZip2CompressorInputStream(in, true);
        if (name.endsWith(".zst") || name.endsWith(".zstd")) return new ZstdCompressorInputStream(in);
        if (name.equals("data.tar")) return in;
        throw new IOException("Unsupported deb compression: " + name);
    }

    private static final class MappedPath {
        final File root;
        final File file;
        MappedPath(File root, File file) { this.root = root; this.file = file; }
    }

    private static MappedPath mapPath(String archivePath, File usrRoot, File homeRoot) {
        if (archivePath == null) return null;
        String n = archivePath;
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);

        String oldUsr = "data/data/com.termux/files/usr";
        String oldHome = "data/data/com.termux/files/home";

        if (n.equals(oldUsr)) return new MappedPath(usrRoot, usrRoot);
        if (n.startsWith(oldUsr + "/")) {
            return new MappedPath(usrRoot, new File(usrRoot, n.substring(oldUsr.length() + 1)));
        }
        if (n.equals(oldHome)) return new MappedPath(homeRoot, homeRoot);
        if (n.startsWith(oldHome + "/")) {
            return new MappedPath(homeRoot, new File(homeRoot, n.substring(oldHome.length() + 1)));
        }

        // Some repackaged mirrors use paths relative to PREFIX.
        if (n.equals("usr")) return new MappedPath(usrRoot, usrRoot);
        if (n.startsWith("usr/")) return new MappedPath(usrRoot, new File(usrRoot, n.substring(4)));
        if (n.equals("home")) return new MappedPath(homeRoot, homeRoot);
        if (n.startsWith("home/")) return new MappedPath(homeRoot, new File(homeRoot, n.substring(5)));

        return null;
    }

    private static String patchString(String s) {
        return s == null ? null : s.replace("com.termux", "com.droidx");
    }

    private static void ensureInside(File file, File root) throws IOException {
        String fp = file.getCanonicalPath();
        String rp = root.getCanonicalPath();
        if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) {
            throw new IOException("Unsafe archive path: " + file);
        }
    }

    private static void deleteIfExists(File f) throws IOException {
        final String path = f.getAbsolutePath();
        try {
            android.system.StructStat st = Os.lstat(path);
            if (android.system.OsConstants.S_ISDIR(st.st_mode)) {
                // This helper is only used when replacing a single archive entry.
                // Directories must be empty before replacement.
                if (!java.nio.file.Files.deleteIfExists(f.toPath())) {
                    throw new IOException("Cannot replace directory " + f);
                }
            } else {
                // Files.deleteIfExists() deletes the symbolic link itself rather than
                // following it, so it also handles dangling symlinks safely.
                java.nio.file.Files.deleteIfExists(f.toPath());
            }
        } catch (android.system.ErrnoException e) {
            if (e.errno != android.system.OsConstants.ENOENT) {
                throw new IOException("Cannot replace " + f + " (errno=" +
                        android.system.OsConstants.errnoName(e.errno) + ")", e);
            }
        }
    }

    private static void applyMode(File f, int mode, boolean directory) {
        // Files live in the private app sandbox, so owner permissions are enough.
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable((mode & 0200) != 0, true);
        if (directory || (mode & 0111) != 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, true);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
    }

    // Rewrites the fixed Termux package id while streaming. The replacement is exactly
    // the same byte length, so ELF offsets and embedded string tables remain intact.
    private static void copyPatched(InputStream in, OutputStream out) throws IOException {
        final int keep = OLD_PACKAGE.length - 1;
        byte[] carry = new byte[0];
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (n == 0) continue;
            byte[] data = new byte[carry.length + n];
            System.arraycopy(carry, 0, data, 0, carry.length);
            System.arraycopy(chunk, 0, data, carry.length, n);

            int safe = Math.max(0, data.length - keep);
            int writtenTo = patchRange(data, 0, safe, out);
            carry = Arrays.copyOfRange(data, writtenTo, data.length);
        }
        patchRange(carry, 0, carry.length, out);
    }

    // Returns the first input index not written. end is a safe scan boundary, not
    // necessarily the physical end of data; a potential token that begins before end
    // is allowed to consume bytes past end.
    private static int patchRange(byte[] data, int start, int end, OutputStream out) throws IOException {
        int i = start;
        while (i < end) {
            if (matches(data, i, OLD_PACKAGE)) {
                out.write(NEW_PACKAGE);
                i += OLD_PACKAGE.length;
            } else {
                out.write(data[i]);
                i++;
            }
        }
        return i;
    }

    private static boolean matches(byte[] data, int at, byte[] needle) {
        if (at + needle.length > data.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (data[at + i] != needle[i]) return false;
        }
        return true;
    }

    private static final class HardLink {
        final String target;
        final File dest;
        final int mode;
        HardLink(String target, File dest, int mode) {
            this.target = target;
            this.dest = dest;
            this.mode = mode;
        }
    }
}

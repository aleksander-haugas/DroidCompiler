package com.droidx;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Project/workspace registry.
 *
 * Android's Storage Access Framework exposes selected folders as content:// tree
 * URIs, not as ordinary filesystem paths that Clang can consume. Linked projects
 * therefore use an internal build mirror while keeping a persistent SAF link to
 * the user's real folder. User edits are synchronized back to that folder.
 */
public final class ProjectManager {
    private static final String PREFS = "droidx_projects";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_IDS = "ids";
    private static final String DEFAULT_ID = "default";

    private ProjectManager() {}

    public static final class Entry {
        public final String id;
        public final String name;
        public final String treeUri;
        public final long lastOpened;

        Entry(String id, String name, String treeUri, long lastOpened) {
            this.id = id;
            this.name = name;
            this.treeUri = treeUri;
            this.lastOpened = lastOpened;
        }

        public boolean linked() { return treeUri != null && !treeUri.isEmpty(); }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void ensureDefault(Context context) {
        SharedPreferences p = prefs(context);
        Set<String> ids = new HashSet<>(p.getStringSet(KEY_IDS, Collections.emptySet()));
        if (!ids.contains(DEFAULT_ID)) {
            ids.add(DEFAULT_ID);
            p.edit()
                    .putStringSet(KEY_IDS, ids)
                    .putString("project." + DEFAULT_ID + ".name", "HelloCpp")
                    .putLong("project." + DEFAULT_ID + ".last", System.currentTimeMillis())
                    .apply();
        }
        // Keep a default workspace in the recent-project registry, but never auto-open it.
        // DroidCompiler starts with an intentionally empty session.
        File dir = projectDirForId(context, DEFAULT_ID);
        if (!dir.exists()) dir.mkdirs();
    }

    public static boolean hasActiveProject(Context context) {
        ensureDefault(context);
        String id = prefs(context).getString(KEY_ACTIVE, "");
        return id != null && !id.isEmpty() && hasProject(context, id);
    }

    public static String activeProjectId(Context context) {
        ensureDefault(context);
        String id = prefs(context).getString(KEY_ACTIVE, "");
        return id != null && hasProject(context, id) ? id : "";
    }

    public static String activeProjectName(Context context) {
        String id = activeProjectId(context);
        return id.isEmpty() ? "No project" : entry(context, id).name;
    }

    public static File activeProjectDir(Context context) {
        String id = activeProjectId(context);
        if (id.isEmpty()) throw new IllegalStateException("No project is open");
        return projectDirForId(context, id);
    }

    public static boolean activeProjectLinked(Context context) {
        String id = activeProjectId(context);
        return !id.isEmpty() && entry(context, id).linked();
    }

    public static Uri activeTreeUri(Context context) {
        String id = activeProjectId(context);
        if (id.isEmpty()) return null;
        String raw = entry(context, id).treeUri;
        return raw == null || raw.isEmpty() ? null : Uri.parse(raw);
    }

    /** Close the current workspace session without deleting it from recents. */
    public static void clearActiveProject(Context context) {
        prefs(context).edit().remove(KEY_ACTIVE).apply();
    }

    public static List<Entry> list(Context context) {
        ensureDefault(context);
        SharedPreferences p = prefs(context);
        List<Entry> out = new ArrayList<>();
        for (String id : new HashSet<>(p.getStringSet(KEY_IDS, Collections.emptySet()))) {
            out.add(entry(context, id));
        }
        out.sort((a, b) -> Long.compare(b.lastOpened, a.lastOpened));
        return out;
    }

    public static Entry createLocalProject(Context context, String requestedName) {
        ensureDefault(context);
        String name = cleanProjectName(requestedName);
        if (name.isEmpty()) name = "Project";
        String id = "local_" + System.currentTimeMillis() + "_" + shortHash(name + System.nanoTime());
        register(context, id, name, null);
        switchProject(context, id);
        File dir = projectDirForId(context, id);
        dir.mkdirs();
        return entry(context, id);
    }

    public static Entry openLinkedFolder(Context context, Uri treeUri, int intentFlags, Progress progress) throws Exception {
        ensureDefault(context);
        if (treeUri == null) throw new IllegalArgumentException("Folder URI is null");
        ContentResolver resolver = context.getContentResolver();
        int flags = intentFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { resolver.takePersistableUriPermission(treeUri, flags); } catch (SecurityException ignored) {}

        String name = queryDisplayName(context, rootDocumentUri(treeUri));
        if (name == null || name.trim().isEmpty()) name = "ExternalProject";
        String id = "linked_" + shortHash(treeUri.toString());
        boolean known = hasProject(context, id);
        register(context, id, name, treeUri.toString());
        File dir = projectDirForId(context, id);
        if (!dir.exists()) dir.mkdirs();
        if (progress != null) progress.onProgress((known ? "Refreshing" : "Importing") + " folder: " + name);
        mirrorTreeIntoWorkspace(context, treeUri, dir, progress);
        switchProject(context, id);
        return entry(context, id);
    }

    public static void switchProject(Context context, String id) {
        ensureDefault(context);
        if (!hasProject(context, id)) throw new IllegalArgumentException("Unknown project: " + id);
        prefs(context).edit()
                .putString(KEY_ACTIVE, id)
                .putLong("project." + id + ".last", System.currentTimeMillis())
                .apply();
    }

    public static void removeProjectFromList(Context context, String id) {
        if (DEFAULT_ID.equals(id)) return;
        SharedPreferences p = prefs(context);
        Set<String> ids = new HashSet<>(p.getStringSet(KEY_IDS, Collections.emptySet()));
        ids.remove(id);
        SharedPreferences.Editor e = p.edit().putStringSet(KEY_IDS, ids)
                .remove("project." + id + ".name")
                .remove("project." + id + ".uri")
                .remove("project." + id + ".last");
        if (id.equals(p.getString(KEY_ACTIVE, ""))) e.remove(KEY_ACTIVE);
        e.apply();
    }

    public static void syncFileIfLinked(Context context, File file) throws Exception {
        Uri tree = activeTreeUri(context);
        if (tree == null || file == null || !file.exists()) return;
        String rel = ProjectStore.relativePath(context, file).replace('\\', '/');
        if (rel.equals(".") || rel.startsWith(".droidx/") || rel.equals(".droidx") || rel.startsWith(".cxx/") || rel.startsWith(".git/")) return;
        // DroidCompiler-generated host files are mirror-only unless the user explicitly edits them later.
        if (rel.equals("include/DroidXAndroid.h") || rel.equals("android/README.txt")) return;
        if (file.isDirectory()) {
            ensureExternalDirectory(context, tree, rel);
        } else {
            Uri out = ensureExternalFile(context, tree, rel, mimeFor(file.getName()));
            try (OutputStream os = context.getContentResolver().openOutputStream(out, "wt"); InputStream in = new java.io.FileInputStream(file)) {
                if (os == null) throw new IllegalStateException("Could not open external file for writing: " + rel);
                copy(in, os);
            }
        }
    }

    public static void syncAllEditableToExternal(Context context, Progress progress) throws Exception {
        if (!activeProjectLinked(context)) return;
        File root = activeProjectDir(context);
        List<File> files = new ArrayList<>();
        collectSyncable(root, root, files);
        int i = 0;
        for (File f : files) {
            i++;
            if (progress != null) progress.onProgress("Sync " + i + "/" + files.size() + ": " + ProjectStore.relativePath(context, f));
            syncFileIfLinked(context, f);
        }
    }

    private static void collectSyncable(File root, File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String rel = relative(root, f);
            if (shouldIgnoreRelative(rel)) continue;
            if (f.isDirectory()) collectSyncable(root, f, out);
            else if (!rel.equals("include/DroidXAndroid.h") && !rel.equals("android/README.txt")) out.add(f);
        }
    }

    private static int mirrorTreeIntoWorkspace(Context context, Uri treeUri, File workspace, Progress progress) throws Exception {
        Uri rootDoc = rootDocumentUri(treeUri);
        int[] count = new int[]{0};
        copyDirectoryContents(context, treeUri, rootDoc, workspace, "", progress, count);
        return count[0];
    }

    /** Refresh the active linked project from its real SAF folder before a build. */
    public static int refreshActiveLinkedProject(Context context, Progress progress) throws Exception {
        Uri tree = activeTreeUri(context);
        if (tree == null) return 0;
        File workspace = activeProjectDir(context);
        if (!workspace.exists() && !workspace.mkdirs())
            throw new IllegalStateException("Could not create linked workspace: " + workspace);
        return mirrorTreeIntoWorkspace(context, tree, workspace, progress);
    }

    /**
     * Materialize one exact project-relative path from the linked SAF tree.
     * This is used as a lazy fallback for quoted includes that are missing from
     * the local mirror (for example build/ChainScanCommonV175.hpp).
     */
    public static boolean materializeLinkedRelativeFile(Context context, String relativePath) throws Exception {
        Uri tree = activeTreeUri(context);
        if (tree == null || relativePath == null) return false;
        String rel = relativePath.trim().replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.equals("..") || rel.startsWith("../") || rel.contains("/../")) return false;

        String[] parts = rel.split("/");
        Uri current = rootDocumentUri(tree);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            Uri child = findChild(context, tree, current, parts[i]);
            if (child == null) return false;
            if (i == parts.length - 1) {
                File out = new File(activeProjectDir(context), rel);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists())
                    throw new IllegalStateException("Could not create " + parent);
                try (InputStream in = context.getContentResolver().openInputStream(child);
                     FileOutputStream fos = new FileOutputStream(out, false)) {
                    if (in == null) return false;
                    copy(in, fos);
                }
                return out.isFile();
            }
            current = child;
        }
        return false;
    }

    private static void copyDirectoryContents(Context context, Uri treeUri, Uri directoryDoc, File destDir,
                                              String relativeBase, Progress progress, int[] count) throws Exception {
        if (!destDir.exists() && !destDir.mkdirs()) throw new IllegalStateException("Could not create " + destDir);
        String parentId = DocumentsContract.getDocumentId(directoryDoc);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor c = context.getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return;
            int idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (c.moveToNext()) {
                String docId = c.getString(idCol);
                String name = c.getString(nameCol);
                String mime = c.getString(mimeCol);
                long remoteSize = (sizeCol >= 0 && !c.isNull(sizeCol)) ? c.getLong(sizeCol) : -1L;
                long remoteModified = (modifiedCol >= 0 && !c.isNull(modifiedCol)) ? c.getLong(modifiedCol) : 0L;
                if (name == null || name.isEmpty()) continue;
                String rel = relativeBase.isEmpty() ? name : relativeBase + "/" + name;
                if (shouldIgnoreRelative(rel)) continue;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                File out = new File(destDir, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copyDirectoryContents(context, treeUri, doc, out, rel, progress, count);
                } else {
                    // Preserve incremental compilation.  Older workspace refreshes rewrote
                    // every mirrored file on every BUILD, giving every source/header a new
                    // timestamp and forcing a full rebuild of large projects such as CHAINSCAN.
                    boolean unchanged = out.isFile()
                            && remoteSize >= 0 && out.length() == remoteSize
                            && remoteModified > 0 && out.lastModified() == remoteModified;
                    if (unchanged) continue;

                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream in = context.getContentResolver().openInputStream(doc);
                         FileOutputStream fos = new FileOutputStream(out, false)) {
                        if (in == null) throw new IllegalStateException("Could not read " + rel);
                        copy(in, fos);
                    }
                    if (remoteModified > 0) out.setLastModified(remoteModified);
                    count[0]++;
                    if (progress != null && (count[0] <= 20 || count[0] % 25 == 0)) progress.onProgress("Updated " + count[0] + " files · " + rel);
                }
            }
        }
    }

    private static boolean shouldIgnoreRelative(String rel) {
        String r = rel.replace('\\', '/');
        String first = r.contains("/") ? r.substring(0, r.indexOf('/')) : r;
        return first.equals(".droidx") || first.equals(".git") || first.equals(".cxx") || first.equals(".gradle") || first.equals(".idea");
    }

    private static Uri ensureExternalFile(Context context, Uri tree, String relativePath, String mime) throws Exception {
        String[] parts = relativePath.split("/");
        Uri parent = rootDocumentUri(tree);
        for (int i = 0; i < parts.length - 1; i++) parent = ensureChildDirectory(context, tree, parent, parts[i]);
        Uri existing = findChild(context, tree, parent, parts[parts.length - 1]);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, parts[parts.length - 1]);
        if (created == null) throw new IllegalStateException("Could not create external file: " + relativePath);
        return created;
    }

    private static Uri ensureExternalDirectory(Context context, Uri tree, String relativePath) throws Exception {
        Uri parent = rootDocumentUri(tree);
        for (String part : relativePath.split("/")) {
            if (!part.isEmpty()) parent = ensureChildDirectory(context, tree, parent, part);
        }
        return parent;
    }

    private static Uri ensureChildDirectory(Context context, Uri tree, Uri parent, String name) throws Exception {
        Uri existing = findChild(context, tree, parent, name);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IllegalStateException("Could not create external folder: " + name);
        return created;
    }

    private static Uri findChild(Context context, Uri tree, Uri parent, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = context.getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return null;
            int idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                if (name.equals(c.getString(nameCol))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idCol));
            }
        }
        return null;
    }

    private static Uri rootDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private static String queryDisplayName(Context context, Uri doc) {
        String[] p = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = context.getContentResolver().query(doc, p, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx") || n.endsWith(".h") || n.endsWith(".hpp") || n.endsWith(".txt") || n.endsWith(".mk") || n.endsWith("makefile")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static boolean hasProject(Context context, String id) {
        return new HashSet<>(prefs(context).getStringSet(KEY_IDS, Collections.emptySet())).contains(id);
    }

    private static Entry entry(Context context, String id) {
        SharedPreferences p = prefs(context);
        String name = p.getString("project." + id + ".name", DEFAULT_ID.equals(id) ? "HelloCpp" : id);
        String uri = p.getString("project." + id + ".uri", "");
        long last = p.getLong("project." + id + ".last", 0L);
        return new Entry(id, name, uri, last);
    }

    private static void register(Context context, String id, String name, String uri) {
        SharedPreferences p = prefs(context);
        Set<String> ids = new HashSet<>(p.getStringSet(KEY_IDS, Collections.emptySet()));
        ids.add(id);
        SharedPreferences.Editor e = p.edit().putStringSet(KEY_IDS, ids)
                .putString("project." + id + ".name", cleanProjectName(name))
                .putLong("project." + id + ".last", System.currentTimeMillis());
        if (uri != null) e.putString("project." + id + ".uri", uri);
        e.apply();
    }

    private static File projectDirForId(Context context, String id) {
        File projects = new File(context.getFilesDir(), "projects");
        if (!projects.exists()) projects.mkdirs();
        if (DEFAULT_ID.equals(id)) return new File(projects, "HelloCpp");
        return new File(projects, id);
    }

    private static String cleanProjectName(String name) {
        if (name == null) return "";
        String n = name.trim().replace('\n', ' ').replace('\r', ' ');
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    private static String shortHash(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", hash[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String relative(File root, File f) {
        try {
            String r = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.startsWith(r + File.separator)) return p.substring(r.length() + 1).replace(File.separatorChar, '/');
        } catch (Exception ignored) {}
        return f.getName();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
    }

    public interface Progress { void onProgress(String message); }
}

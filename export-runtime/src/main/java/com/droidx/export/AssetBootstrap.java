package com.droidx.export;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class AssetBootstrap {
    private AssetBootstrap() {}

    static File projectDir(Context c) {
        File f = new File(c.getFilesDir(), "project");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static File assetDir(Context c) {
        File f = new File(projectDir(c), "assets");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static void extractProjectAssets(Context c) {
        try { copyTree(c, "project_assets", assetDir(c)); }
        catch (Throwable ignored) {}
    }

    private static void copyTree(Context c, String assetPath, File outDir) throws Exception {
        String[] children = c.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            if (assetPath.equals("project_assets")) return;
            File out = new File(outDir.getParentFile(), outDir.getName());
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = c.getAssets().open(assetPath); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) >= 0) if (n > 0) fos.write(buf, 0, n);
            }
            return;
        }
        if (!outDir.exists()) outDir.mkdirs();
        for (String child : children) {
            String sub = assetPath.isEmpty() ? child : assetPath + "/" + child;
            String[] probe = c.getAssets().list(sub);
            File dest = new File(outDir, child);
            if (probe != null && probe.length > 0) copyTree(c, sub, dest);
            else {
                File parent = dest.getParentFile(); if (parent != null) parent.mkdirs();
                try (InputStream in = c.getAssets().open(sub); FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) >= 0) if (n > 0) fos.write(buf, 0, n);
                }
            }
        }
    }
}

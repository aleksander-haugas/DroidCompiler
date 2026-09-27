package com.droidx;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.CRC32;

/** Integrated development APK exporter for the active project. */
public final class ApkExporter {
    private static final String TEMPLATE_ASSET = "droidx-export-template.apk";
    private static final String KEY_ASSET = "droidx-export-debug.p12";
    private static final char[] KEY_PASSWORD = "droidx123".toCharArray();
    private static final String ICON_ENTRY = "res/drawable/droidx_export_icon.png";

    public interface Listener { void onLog(String message); }

    public static final class Result {
        public final File apk;
        public final String packageName;
        public final String appName;
        public final String versionName;
        public final int versionCode;
        public final boolean sdl;
        Result(File apk, ApkExportSettings s, boolean sdl) {
            this.apk=apk; this.packageName=s.packageName; this.appName=s.appName;
            this.versionName=s.versionName; this.versionCode=s.versionCode; this.sdl=sdl;
        }
    }

    private ApkExporter() {}

    public static Result export(Context context, ApkExportSettings settings, Listener listener) throws Exception {
        if (settings == null) settings = ApkExportSettings.load(context);
        settings.save(context);
        log(listener, "APK EXPORT START\nApp: " + settings.appName + "\nPackage: " + settings.packageName
                + "\nVersion: " + settings.versionName + " (" + settings.versionCode + ")");

        File program = ProjectStore.activeProgramLibrary(context);
        if (program == null || !program.isFile()) throw new IllegalStateException("No successful build is available. BUILD the project first.");
        if (!ProjectStore.isActiveBuildCurrent(context))
            throw new IllegalStateException("The active binary is not current for the effective build inputs. BUILD again before APK export.");

        String projectText = readProjectText(context);
        byte[] originalProgramBytes = Files.readAllBytes(program.toPath());
        boolean sdl = containsAscii(originalProgramBytes, "libSDL2.so") || CompilerEngine.usesSDL2(projectText);
        boolean needsRunnerBridge = !sdl || containsAscii(originalProgramBytes, "librunnerbridge.so")
                || containsAscii(originalProgramBytes, "droidx_android_");
        boolean needsCxxShared = containsAscii(originalProgramBytes, "libc++_shared.so");
        log(listener, "Runtime scan: SDL2=" + sdl + ", runnerbridge=" + needsRunnerBridge + ", libc++_shared=" + needsCxxShared);

        boolean unsupportedOptional = containsAscii(originalProgramBytes, "libcurl")
                || containsAscii(originalProgramBytes, "libssl") || containsAscii(originalProgramBytes, "libcrypto")
                || containsAscii(originalProgramBytes, "libzstd") || containsAscii(originalProgramBytes, "libsqlite")
                || containsAscii(originalProgramBytes, "libpng") || containsAscii(originalProgramBytes, "libjpeg");
        if (unsupportedOptional) throw new IllegalStateException(
                "APK Export currently supports system/NDK libraries + SDL2. The built libprogram.so depends on an optional dynamic runtime (curl/OpenSSL/zstd/SQLite/PNG/JPEG).");

        String extraPermissions = ProjectConfig.extraPermissions(context).trim();
        if (!extraPermissions.isEmpty()) log(listener, "Note: custom android.permissions are not injected yet: " + extraPermissions.replace('\n',' '));

        String packageName = settings.packageName;
        String exportProjectDir = "/data/user/0/" + packageName + "/files/project";
        String exportAssetDir = exportProjectDir + "/assets";
        String abi = ToolchainManager.deviceAbiLabel();
        if (!"arm64-v8a".equals(abi) && !"x86_64".equals(abi)) throw new IllegalStateException("Unsupported export ABI: " + abi);

        File work = new File(context.getCacheDir(), "apk-export");
        deleteTree(work); if (!work.mkdirs() && !work.isDirectory()) throw new IllegalStateException("Could not create export work directory");
        File template = new File(work, "template.apk");
        copyAsset(context, TEMPLATE_ASSET, template);
        if (template.length() < 4096) throw new IllegalStateException("Integrated export runtime is missing or invalid. Rebuild DroidCompiler in Android Studio.");

        File patchedProgram = new File(work, "libprogram.so");
        byte[] programBytes = originalProgramBytes.clone();
        patchCString(programBytes, ProjectStore.assetsDir(context).getAbsolutePath(), exportAssetDir);
        patchCString(programBytes, ProjectStore.projectDir(context).getAbsolutePath(), exportProjectDir);
        Files.write(patchedProgram.toPath(), programBytes);

        File unsigned = new File(work, "unsigned.apk");
        createUnsignedApk(context, template, unsigned, patchedProgram, settings, abi, sdl,
                needsRunnerBridge, needsCxxShared, projectText, listener);

        File exportDir = new File(context.getFilesDir(), "exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) throw new IllegalStateException("Could not create exports directory");
        String safeName = settings.appName.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeVersion = settings.versionName.replaceAll("[^A-Za-z0-9._-]", "_");
        File signed = new File(exportDir, safeName + "-" + safeVersion + ".apk");
        sign(context, unsigned, signed, listener);
        verifyNativeEntries(signed, abi, needsRunnerBridge, needsCxxShared, listener, "signed");

        ApkVerifier.Result verify = new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(35).build().verify();
        if (!verify.isVerified()) throw new IllegalStateException("APK signature verification failed: " + verify.getErrors());

        PackageInfo archiveInfo = context.getPackageManager().getPackageArchiveInfo(signed.getAbsolutePath(), 0);
        if (archiveInfo == null) throw new IllegalStateException("Android PackageManager could not parse the exported APK");
        if (!packageName.equals(archiveInfo.packageName)) throw new IllegalStateException(
                "Export package patch failed. Expected " + packageName + " but APK contains " + archiveInfo.packageName);
        if (archiveInfo.getLongVersionCode() != settings.versionCode) throw new IllegalStateException(
                "Export versionCode patch failed. Expected " + settings.versionCode + " but APK contains " + archiveInfo.getLongVersionCode());
        String actualVersion = archiveInfo.versionName == null ? "" : archiveInfo.versionName;
        if (!settings.versionName.equals(actualVersion)) throw new IllegalStateException(
                "Export versionName patch failed. Expected " + settings.versionName + " but APK contains " + actualVersion);
        log(listener, "APK metadata verified by Android: " + archiveInfo.packageName + " " + actualVersion + " (" + archiveInfo.getLongVersionCode() + ")");

        log(listener, "APK EXPORT SUCCESS\nPackage: " + packageName + "\nABI: " + abi + "\nMode: " + (sdl ? "SDL2" : "console")
                + "\nRunner bridge: " + needsRunnerBridge + "\nOutput: " + signed + "\nSize: " + signed.length() + " bytes");
        return new Result(signed, settings, sdl);
    }

    private static void createUnsignedApk(Context context, File template, File out, File program,
                                          ApkExportSettings settings, String abi, boolean sdl,
                                          boolean needsRunnerBridge, boolean needsCxxShared,
                                          String projectText, Listener listener) throws Exception {
        boolean manifestPatched = false;
        boolean iconPatched = false;
        File customIcon = ApkExportSettings.iconFile(context);
        try (ZipFile zip = new ZipFile(template);
             ZipArchiveOutputStream zout = new ZipArchiveOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            Enumeration<ZipArchiveEntry> en = zip.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry src = en.nextElement();
                String name = src.getName();
                if (name.startsWith("META-INF/") || name.startsWith("lib/")) continue;
                byte[] data = readAll(zip.getInputStream(src));
                if ("AndroidManifest.xml".equals(name)) {
                    data = BinaryXmlPatcher.patchManifest(data, settings.packageName, settings.appName,
                            settings.versionName, settings.versionCode);
                    manifestPatched = true;
                } else if (ICON_ENTRY.equals(name) && customIcon.isFile()) {
                    data = Files.readAllBytes(customIcon.toPath());
                    iconPatched = true;
                }
                put(zout, name, data, src.getMethod(), "resources.arsc".equals(name) ? 4 : 0);
            }

            if (!manifestPatched) throw new IllegalStateException("Integrated export runtime has no AndroidManifest.xml");
            if (customIcon.isFile() && !iconPatched) throw new IllegalStateException("Export runtime icon resource was not found");

            Properties props = new Properties();
            props.setProperty("mode", sdl ? "sdl" : "console");
            props.setProperty("orientation", settings.orientation);
            props.setProperty("foreground", Boolean.toString(settings.foreground));
            boolean pathNeedsLegacy = projectText.contains("/storage/emulated/0/") || projectText.contains("/sdcard/");
            props.setProperty("legacyStorage", Boolean.toString(settings.legacyStorage || pathNeedsLegacy));
            props.setProperty("runnerBridge", Boolean.toString(needsRunnerBridge));
            props.setProperty("appName", settings.appName);
            props.setProperty("versionName", settings.versionName);
            props.setProperty("versionCode", Integer.toString(settings.versionCode));
            ByteArrayOutputStream propOut = new ByteArrayOutputStream(); props.store(propOut, "DroidCompiler integrated APK export");
            put(zout, "assets/droidx-export.properties", propOut.toByteArray(), ZipArchiveEntry.DEFLATED, 0);

            addProjectAssets(zout, ProjectStore.assetsDir(context), ProjectStore.assetsDir(context));
            putFile(zout, "lib/" + abi + "/libprogram.so", program, ZipArchiveEntry.STORED, 16384);

            if (needsRunnerBridge) {
                File runner = new File(context.getApplicationInfo().nativeLibraryDir, "librunnerbridge.so");
                if (!runner.isFile()) throw new IllegalStateException("librunnerbridge.so missing from DroidCompiler runtime");
                putFile(zout, "lib/" + abi + "/librunnerbridge.so", runner, ZipArchiveEntry.STORED, 16384);
            }

            // SDL2 is required for SDL exports and by the current runnerbridge build.
            if (sdl || needsRunnerBridge) {
                File sdlLib = ToolchainManager.embeddedSDL2(context);
                if (!sdlLib.isFile()) throw new IllegalStateException("libSDL2.so missing from DroidCompiler runtime");
                putFile(zout, "lib/" + abi + "/libSDL2.so", sdlLib, ZipArchiveEntry.STORED, 16384);
            }

            File cxx = new File(context.getApplicationInfo().nativeLibraryDir, "libc++_shared.so");
            if (!cxx.isFile()) cxx = new File(ToolchainManager.packageLibDir(context), "libc++_shared.so");
            if (needsCxxShared && !cxx.isFile()) throw new IllegalStateException(
                    "libprogram.so requires libc++_shared.so but DroidCompiler could not locate it");
            if (needsCxxShared && cxx.isFile()) putFile(zout, "lib/" + abi + "/libc++_shared.so", cxx, ZipArchiveEntry.STORED, 16384);
        }
        verifyNativeEntries(out, abi, needsRunnerBridge, needsCxxShared, listener, "unsigned");
        log(listener, "Export metadata patched: name/package/version" + (customIcon.isFile() ? "/icon" : "") + ".");
        log(listener, "Unsigned APK assembled: " + out.length() + " bytes");
    }

    private static void verifyNativeEntries(File apk, String abi, boolean needsRunnerBridge,
                                            boolean needsCxxShared, Listener listener, String stage) throws Exception {
        List<String> required = new ArrayList<>();
        required.add("lib/" + abi + "/libprogram.so");
        // runnerbridge currently links SDL2, and SDL applications require it directly.
        if (needsRunnerBridge || hasEntry(apk, "lib/" + abi + "/libSDL2.so")) required.add("lib/" + abi + "/libSDL2.so");
        if (needsRunnerBridge) required.add("lib/" + abi + "/librunnerbridge.so");
        if (needsCxxShared) required.add("lib/" + abi + "/libc++_shared.so");
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : required) {
                ZipArchiveEntry e = zip.getEntry(name);
                if (e == null) throw new IllegalStateException("APK " + stage + " is missing native library: " + name);
                if (e.getSize() <= 0) throw new IllegalStateException("APK " + stage + " contains empty native library: " + name);
                if (e.getMethod() != ZipArchiveEntry.STORED) throw new IllegalStateException(
                        "APK " + stage + " native library is compressed instead of STORED: " + name);
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] magic = new byte[4]; int n=in.read(magic);
                    if(n!=4||magic[0]!=0x7f||magic[1]!='E'||magic[2]!='L'||magic[3]!='F')
                        throw new IllegalStateException("APK " + stage + " native entry is not an ELF library: " + name);
                }
                log(listener, "Native APK entry OK [" + stage + "]: " + name + " (" + e.getSize() + " bytes, STORED/16K aligned)");
            }
        }
    }

    private static boolean hasEntry(File apk,String name)throws Exception{try(ZipFile z=new ZipFile(apk)){return z.getEntry(name)!=null;}}

    private static void sign(Context context, File input, File output, Listener listener) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = context.getAssets().open(KEY_ASSET)) { ks.load(in, KEY_PASSWORD); }
        PrivateKey key = (PrivateKey) ks.getKey("droidx", KEY_PASSWORD);
        Certificate[] chain = ks.getCertificateChain("droidx");
        if (key == null || chain == null || chain.length == 0) throw new IllegalStateException("DroidCompiler export signing key is invalid");
        List<X509Certificate> certs = new ArrayList<>(); for (Certificate c : chain) certs.add((X509Certificate)c);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("DROIDX", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(input).setOutputApk(output)
                .setOtherSignersSignaturesPreserved(false).setV1SigningEnabled(true).setV2SigningEnabled(true)
                .setV3SigningEnabled(true).setV4SigningEnabled(false).setMinSdkVersion(35).build().sign();
        log(listener, "APK signed with DroidCompiler development key (v1/v2/v3).");
    }

    private static void addProjectAssets(ZipArchiveOutputStream zout, File root, File cur) throws Exception {
        File[] files=cur.listFiles(); if(files==null)return;
        for(File f:files){if(f.isDirectory())addProjectAssets(zout,root,f);else if(f.isFile()){
            String rel=root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');
            putFile(zout,"assets/project_assets/"+rel,f,ZipArchiveEntry.DEFLATED,0);
        }}
    }

    private static void putFile(ZipArchiveOutputStream out,String name,File f,int method,int alignment)throws Exception{
        try(InputStream in=new BufferedInputStream(new FileInputStream(f))){put(out,name,readAll(in),method,alignment);}
    }
    private static void put(ZipArchiveOutputStream out,String name,byte[] data,int method,int alignment)throws Exception{
        ZipArchiveEntry e=new ZipArchiveEntry(name); if(method!=ZipArchiveEntry.STORED&&method!=ZipArchiveEntry.DEFLATED)method=ZipArchiveEntry.DEFLATED;
        e.setMethod(method);if(alignment>0)e.setAlignment(alignment);if(method==ZipArchiveEntry.STORED){CRC32 crc=new CRC32();crc.update(data);e.setSize(data.length);e.setCrc(crc.getValue());}
        out.putArchiveEntry(e);out.write(data);out.closeArchiveEntry();
    }
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=x.read(b))>=0)if(n>0)out.write(b,0,n);return out.toByteArray();}}

    private static String readProjectText(Context context)throws Exception{
        StringBuilder b=new StringBuilder();for(File f:ProjectStore.explorerFiles(context)){if(!ProjectStore.isEditableText(f))continue;String n=f.getName().toLowerCase(Locale.US);
            if(!(n.endsWith(".c")||n.endsWith(".cc")||n.endsWith(".cpp")||n.endsWith(".cxx")||n.endsWith(".h")||n.endsWith(".hpp")||n.endsWith(".hh")||n.endsWith(".hxx")))continue;
            b.append(ProjectStore.readTextFile(context,f)).append('\n');}return b.toString();
    }
    private static boolean containsAscii(byte[]data,String text){byte[]p=text.getBytes(StandardCharsets.US_ASCII);outer:for(int i=0;i<=data.length-p.length;i++){for(int j=0;j<p.length;j++)if(data[i+j]!=p[j])continue outer;return true;}return false;}
    private static void patchCString(byte[]data,String from,String to){if(from==null||from.isEmpty()||to==null)return;byte[]a=from.getBytes(StandardCharsets.UTF_8),b=to.getBytes(StandardCharsets.UTF_8);if(b.length>a.length)return;for(int i=0;i<=data.length-a.length;i++){boolean ok=true;for(int j=0;j<a.length;j++)if(data[i+j]!=a[j]){ok=false;break;}if(!ok)continue;System.arraycopy(b,0,data,i,b.length);for(int j=b.length;j<a.length;j++)data[i+j]=0;i+=a.length-1;}}
    private static void copyAsset(Context c,String name,File out)throws Exception{try(InputStream in=c.getAssets().open(name);FileOutputStream fos=new FileOutputStream(out)){byte[]buf=new byte[65536];int n;while((n=in.read(buf))>=0)if(n>0)fos.write(buf,0,n);}}
    private static void deleteTree(File f)throws Exception{if(!f.exists())return;File[]xs=f.listFiles();if(xs!=null)for(File x:xs)deleteTree(x);Files.deleteIfExists(f.toPath());}
    private static void log(Listener l,String s){if(l!=null)l.onLog(s);}
}

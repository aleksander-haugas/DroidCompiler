package com.droidx.build;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.JavaFileObject;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Builds the tiny APK-export runtime as an opaque asset of DroidCompiler.
 * It is deliberately NOT an Android application/module in the root project,
 * so Android Studio exposes/builds only :app.
 */
public final class ExportTemplateEmbedder {
    private ExportTemplateEmbedder() {}

    public static void prepare(File rootDir,
                               File sdkDir,
                               File sdl2Root,
                               File generatedAssets,
                               int compileSdk,
                               int minSdk,
                               int targetSdk) throws Exception {
        File runtimeRoot = new File(rootDir, "export-runtime/src/main");
        File manifest = new File(runtimeRoot, "AndroidManifest.xml");
        File javaRoot = new File(runtimeRoot, "java");
        File resRoot = new File(runtimeRoot, "res");
        File sdlJavaRoot = new File(sdl2Root, "android-project/app/src/main/java");
        if (!manifest.isFile()) throw new IOException("Missing export runtime manifest: " + manifest);
        if (!javaRoot.isDirectory()) throw new IOException("Missing export runtime Java sources: " + javaRoot);
        if (!sdlJavaRoot.isDirectory()) throw new IOException("Missing SDL2 Java sources: " + sdlJavaRoot);

        File androidJar = new File(sdkDir, "platforms/android-" + compileSdk + "/android.jar");
        if (!androidJar.isFile()) throw new IOException("Missing Android platform android-" + compileSdk + ": " + androidJar);
        File buildTools = newestBuildTools(sdkDir);
        File aapt2 = executable(buildTools, "aapt2");
        File d8 = executable(buildTools, "d8");
        if (!aapt2.isFile()) throw new IOException("aapt2 not found under " + buildTools);
        if (!d8.isFile()) throw new IOException("d8 not found under " + buildTools);

        File work = new File(rootDir, "app/build/generated/exportRuntime");
        File outApk = new File(generatedAssets, "droidx-export-template.apk");
        File stamp = new File(work, ".stamp");
        String signature = signature(manifest, javaRoot, resRoot, sdlJavaRoot, androidJar, buildTools,
                compileSdk, minSdk, targetSdk);
        if (outApk.isFile() && outApk.length() > 4096 && stamp.isFile()
                && signature.equals(readText(stamp).trim())) {
            System.out.println("DroidCompiler: integrated APK export runtime up-to-date: " + outApk);
            return;
        }

        deleteTree(work);
        mkdirs(work); mkdirs(generatedAssets);
        File compiledRes = new File(work, "compiled-res.zip");
        File baseApk = new File(work, "base-resources.apk");
        File classesDir = new File(work, "classes");
        File classesJar = new File(work, "classes.jar");
        File dexDir = new File(work, "dex");
        mkdirs(classesDir); mkdirs(dexDir);

        if (resRoot.isDirectory()) {
            run(aapt2.getParentFile(), command(aapt2, "compile", "--dir", resRoot.getAbsolutePath(), "-o", compiledRes.getAbsolutePath()));
        }
        List<String> link = command(aapt2, "link",
                "-o", baseApk.getAbsolutePath(),
                "--manifest", manifest.getAbsolutePath(),
                "--min-sdk-version", Integer.toString(minSdk),
                "--target-sdk-version", Integer.toString(targetSdk),
                "-I", androidJar.getAbsolutePath());
        if (compiledRes.isFile()) link.add(compiledRes.getAbsolutePath());
        run(aapt2.getParentFile(), link);

        List<File> sources = new ArrayList<>();
        collect(javaRoot, ".java", sources);
        collect(sdlJavaRoot, ".java", sources);
        if (sources.isEmpty()) throw new IOException("No Java sources found for export runtime");
        compileJava(sources, classesDir, androidJar);
        jarDirectory(classesDir, classesJar);
        // On Windows, d8.bat depends on JAVA_HOME/PATH. Android Studio can run
        // Gradle with its bundled JBR even when neither variable is available to
        // cmd.exe. Invoke D8's Java main class directly with the exact JVM that
        // is already running this Gradle build, avoiding the wrapper entirely.
        File d8Jar = new File(buildTools, "lib/d8.jar");
        File javaExe = currentJavaExecutable();
        if (d8Jar.isFile() && javaExe != null && javaExe.isFile()) {
            List<String> d8Direct = new ArrayList<>();
            d8Direct.add(javaExe.getAbsolutePath());
            d8Direct.add("-cp");
            d8Direct.add(d8Jar.getAbsolutePath());
            d8Direct.add("com.android.tools.r8.D8");
            d8Direct.add("--min-api");
            d8Direct.add(Integer.toString(minSdk));
            d8Direct.add("--lib");
            d8Direct.add(androidJar.getAbsolutePath());
            d8Direct.add("--output");
            d8Direct.add(dexDir.getAbsolutePath());
            d8Direct.add(classesJar.getAbsolutePath());
            System.out.println("DroidCompiler: D8 JVM = " + javaExe.getAbsolutePath());
            System.out.println("DroidCompiler: D8 jar = " + d8Jar.getAbsolutePath());
            run(d8.getParentFile(), d8Direct);
        } else {
            System.out.println("DroidCompiler: direct D8 unavailable; falling back to build-tools wrapper.");
            run(d8.getParentFile(), command(d8,
                    "--min-api", Integer.toString(minSdk),
                    "--lib", androidJar.getAbsolutePath(),
                    "--output", dexDir.getAbsolutePath(),
                    classesJar.getAbsolutePath()));
        }
        File classesDex = new File(dexDir, "classes.dex");
        if (!classesDex.isFile()) throw new IOException("D8 did not produce classes.dex");

        File tmp = new File(outApk.getAbsolutePath() + ".part");
        assemble(baseApk, classesDex, tmp);
        Files.move(tmp.toPath(), outApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        writeText(stamp, signature);
        System.out.println("DroidCompiler: integrated APK export runtime built into app asset: " + outApk
                + " (" + outApk.length() + " bytes)");
    }

    private static File newestBuildTools(File sdk) throws IOException {
        File root = new File(sdk, "build-tools");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) throw new IOException("No Android build-tools installed under " + root);
        Arrays.sort(dirs, (a,b) -> compareVersions(b.getName(), a.getName()));
        for (File d : dirs) {
            if (executable(d, "aapt2").isFile() && executable(d, "d8").isFile()) return d;
        }
        throw new IOException("No build-tools version containing aapt2 and d8 under " + root);
    }

    private static int compareVersions(String a, String b) {
        String[] aa=a.split("[.-]"), bb=b.split("[.-]");
        int n=Math.max(aa.length,bb.length);
        for(int i=0;i<n;i++){
            int ai=i<aa.length?num(aa[i]):0, bi=i<bb.length?num(bb[i]):0;
            if(ai!=bi)return Integer.compare(ai,bi);
        }
        return a.compareTo(b);
    }
    private static int num(String s){ try{return Integer.parseInt(s.replaceAll("\\D.*$", ""));}catch(Exception e){return 0;} }

    private static File executable(File dir, String base) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
        if (win) {
            File exe = new File(dir, base + ".exe"); if (exe.isFile()) return exe;
            File bat = new File(dir, base + ".bat"); if (bat.isFile()) return bat;
        }
        return new File(dir, base);
    }

    private static List<String> command(File exe, String... args) {
        List<String> c = new ArrayList<>();
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
        if (win && exe.getName().toLowerCase(Locale.US).endsWith(".bat")) {
            c.add("cmd"); c.add("/c"); c.add(exe.getAbsolutePath());
        } else c.add(exe.getAbsolutePath());
        c.addAll(Arrays.asList(args)); return c;
    }

    private static void run(File cwd, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);

        // Android Studio/Gradle can run perfectly with its bundled JBR even when
        // the user's shell has no JAVA_HOME and no java.exe in PATH. Windows
        // build-tools wrappers such as d8.bat do not inherit that knowledge,
        // so explicitly forward the JVM that is running this Gradle daemon.
        File javaHome = currentJavaHome();
        if (javaHome != null) {
            Map<String,String> env = pb.environment();
            env.put("JAVA_HOME", javaHome.getAbsolutePath());
            File javaBin = new File(javaHome, "bin");
            String oldPath = env.get("PATH");
            if (oldPath == null) oldPath = env.get("Path");
            String merged = javaBin.getAbsolutePath() + File.pathSeparator + (oldPath == null ? "" : oldPath);
            env.put("PATH", merged);
            env.put("Path", merged);
        }

        Process p = pb.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b=new byte[16384]; int n; while((n=in.read(b))>=0) if(n>0) log.write(b,0,n);
        }
        int rc=p.waitFor();
        if(rc!=0) throw new IOException("Command failed ("+rc+"): "+String.join(" ",cmd)+"\n"+log.toString("UTF-8"));
    }


    private static File currentJavaExecutable() {
        File home = currentJavaHome();
        if (home == null) return null;
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
        File exe = new File(new File(home, "bin"), win ? "java.exe" : "java");
        return exe.isFile() ? exe : null;
    }

    private static File currentJavaHome() {
        String home = System.getProperty("java.home", "").trim();
        if (home.isEmpty()) return null;
        File h = new File(home);
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
        File javaExe = new File(new File(h, "bin"), win ? "java.exe" : "java");
        if (javaExe.isFile()) return h;
        File parent = h.getParentFile();
        if (parent != null) {
            File parentJava = new File(new File(parent, "bin"), win ? "java.exe" : "java");
            if (parentJava.isFile()) return parent;
        }
        return h;
    }

    private static void compileJava(List<File> sources, File out, File androidJar) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("JDK Java compiler is unavailable; run Gradle with Android Studio JDK");
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.US, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
            List<String> opts = Arrays.asList("-source", "11", "-target", "11", "-encoding", "UTF-8",
                    "-classpath", androidJar.getAbsolutePath(), "-d", out.getAbsolutePath(), "-Xlint:none");
            Boolean ok = compiler.getTask(null, fm, d -> System.out.println("DroidCompiler export runtime javac: " + d), opts, null, units).call();
            if (!Boolean.TRUE.equals(ok)) throw new IOException("Failed compiling integrated export runtime Java sources");
        }
    }

    private static void jarDirectory(File root, File out) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            List<File> files=new ArrayList<>(); collect(root, ".class", files);
            for(File f:files){
                String rel=root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');
                JarEntry e=new JarEntry(rel); e.setTime(0); jar.putNextEntry(e);
                Files.copy(f.toPath(),jar); jar.closeEntry();
            }
        }
    }

    private static void assemble(File baseApk, File dex, File out) throws Exception {
        try (ZipFile zin=new ZipFile(baseApk); ZipOutputStream zout=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            Enumeration<? extends ZipEntry> en=zin.entries(); byte[] buf=new byte[65536];
            while(en.hasMoreElements()){
                ZipEntry src=en.nextElement(); if("classes.dex".equals(src.getName()) || src.getName().startsWith("META-INF/"))continue;
                ZipEntry e=new ZipEntry(src.getName()); e.setMethod(src.getMethod()); e.setTime(0);
                if(src.getMethod()==ZipEntry.STORED){e.setSize(src.getSize());e.setCompressedSize(src.getCompressedSize());e.setCrc(src.getCrc());}
                zout.putNextEntry(e); try(InputStream in=zin.getInputStream(src)){int n;while((n=in.read(buf))>=0)if(n>0)zout.write(buf,0,n);} zout.closeEntry();
            }
            ZipEntry de=new ZipEntry("classes.dex"); de.setTime(0); zout.putNextEntry(de); Files.copy(dex.toPath(),zout); zout.closeEntry();
        }
    }

    private static String signature(File manifest, File javaRoot, File resRoot, File sdlJava, File androidJar, File buildTools,
                                    int compileSdk,int minSdk,int targetSdk) throws Exception {
        long v=17; v=mix(v,manifest); v=mixTree(v,javaRoot,".java"); v=mixTree(v,resRoot,null); v=mixTree(v,sdlJava,".java");
        v=31*v+androidJar.length()+androidJar.lastModified(); v=31*v+buildTools.getName().hashCode();
        return "export-runtime-v153-"+Long.toHexString(v)+"-"+compileSdk+"-"+minSdk+"-"+targetSdk;
    }
    private static long mixTree(long v,File dir,String suffix)throws Exception{List<File> fs=new ArrayList<>();collect(dir,suffix,fs);for(File f:fs)v=mix(v,f);return v;}
    private static long mix(long v,File f)throws Exception{return 31*v+f.getCanonicalPath().hashCode()+f.length()+f.lastModified();}

    private static void collect(File dir,String suffix,List<File> out){File[] xs=dir.listFiles();if(xs==null)return;Arrays.sort(xs,Comparator.comparing(File::getName));for(File f:xs){if(f.isDirectory())collect(f,suffix,out);else if(suffix==null||f.getName().endsWith(suffix))out.add(f);}}
    private static void mkdirs(File f)throws IOException{if(f!=null&&!f.exists()&&!f.mkdirs()&&!f.exists())throw new IOException("Cannot create "+f);}
    private static void deleteTree(File f)throws IOException{if(!f.exists())return;File[] xs=f.listFiles();if(xs!=null)for(File x:xs)deleteTree(x);Files.deleteIfExists(f.toPath());}
    private static String readText(File f)throws IOException{return new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8);}
    private static void writeText(File f,String s)throws IOException{mkdirs(f.getParentFile());Files.write(f.toPath(),s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}

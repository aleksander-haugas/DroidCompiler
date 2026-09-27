package com.droidx;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompilerEngine {
    private CompilerEngine() {}

    public static BuildResult build(Context context) {
        File clang = ToolchainManager.embeddedClang(context);
        File lld = ToolchainManager.embeddedLld(context);
        if (!clang.isFile() || !lld.isFile()) {
            return new BuildResult(false, -1,
                    "EMBEDDED COMPILER MISSING\n\nRebuild the Android Studio project so prepareEmbeddedToolchain runs.");
        }
        if (!ToolchainManager.runtimeDataInstalled(context)) {
            return new BuildResult(false, -1,
                    "CORE TOOLCHAIN NOT INSTALLED\n\nOpen Settings > Core toolchain and install the C/C++ core first.");
        }

        StringBuilder linkedRefreshLog = new StringBuilder();
        if (ProjectManager.activeProjectLinked(context)) {
            try {
                int copied = ProjectManager.refreshActiveLinkedProject(context, null);
                linkedRefreshLog.append("Linked project refresh: ").append(copied)
                        .append(" files updated from external folder.\n");
            } catch (Throwable t) {
                return new BuildResult(false, -1,
                        "LINKED PROJECT REFRESH FAILED\n\n" + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        ProjectStore.ensureStructure(context);

        // Linked C4droid projects normally ship a Makefile that defines the exact
        // source manifest and app-local flags.  Internal recursive source discovery
        // would incorrectly compile historical/alternate translation units (for
        // example CHAINSCAN's old blockmaster_v*.cpp files), so prefer the Makefile
        // automatically when the linked project clearly matches that workflow.
        if (ProjectManager.activeProjectLinked(context)
                && BuildSystemConfig.INTERNAL.equals(BuildSystemConfig.mode(context))
                && MakefileCompat.looksLikeC4droidMakefile(context)) {
            BuildSystemConfig.save(context, BuildSystemConfig.MAKEFILE, "Makefile", BuildSystemConfig.customCommand(context));
            linkedRefreshLog.append("Build system auto-selected: Makefile (C4droid-style linked project).\n");
        }

        final MakefileCompat.Overrides buildOverrides;
        try {
            buildOverrides = MakefileCompat.resolve(context);
        } catch (Throwable t) {
            return new BuildResult(false, -1, "BUILD SYSTEM ERROR\n\n" + t.getMessage());
        }
        final boolean makefileAuthoritative = buildOverrides.authoritative;
        List<File> sources = buildOverrides.sources.isEmpty()
                ? (BuildSystemConfig.INTERNAL.equals(BuildSystemConfig.mode(context))
                    ? ProjectStore.sourceFiles(context) : ProjectStore.allSourceFiles(context))
                : new ArrayList<>(buildOverrides.sources);
        if (sources.isEmpty()) return new BuildResult(false, -1, "No C/C++ sources were selected for this build.");
        final List<File> headers = BuildSystemConfig.INTERNAL.equals(BuildSystemConfig.mode(context))
                ? ProjectStore.headerFiles(context)
                : ProjectStore.compatibilityHeaderFiles(context);

        if (ProjectManager.activeProjectLinked(context)) {
            try {
                LinkedIncludeHydration hydration = hydrateMissingQuotedIncludes(context, sources, headers);
                if (hydration.materialized > 0) {
                    linkedRefreshLog.append("Lazy include hydration: ").append(hydration.materialized)
                            .append(" file(s) materialized from external folder.\n");
                }
                for (String missing : hydration.missing) {
                    linkedRefreshLog.append("External include lookup: NOT FOUND: ").append(missing).append('\n');
                }
            } catch (Throwable t) {
                linkedRefreshLog.append("Include hydration warning: ").append(t.getMessage()).append('\n');
            }
        }

        final String allSourceText;
        try {
            StringBuilder joined = new StringBuilder();
            for (File source : sources) {
                joined.append("\n/* ").append(ProjectStore.relativePath(context, source)).append(" */\n");
                joined.append(new String(Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            for (File header : headers) {
                joined.append("\n/* ").append(ProjectStore.relativePath(context, header)).append(" */\n");
                joined.append(new String(Files.readAllBytes(header.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            allSourceText = joined.toString();
        } catch (Exception e) {
            return new BuildResult(false, -1, "Could not scan project sources: " + e);
        }

        final boolean useSDL2 = usesSDL2(allSourceText);
        final boolean useGLES = usesOpenGLES(allSourceText);
        final boolean useCurl = usesCurl(allSourceText);
        final boolean useSockets = usesSockets(allSourceText);
        final boolean useOpenSSL = usesOpenSSL(allSourceText);
        final boolean useZlib = usesZlib(allSourceText);
        final boolean useZstd = usesZstd(allSourceText);
        final boolean useSQLite = usesSQLite(allSourceText);
        final boolean usePng = usesPng(allSourceText);
        final boolean useJpeg = usesJpeg(allSourceText);
        final boolean useAndroidBridge = usesAndroidBridge(allSourceText);
        if (useSDL2 && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.SDL2)) {
            return missingPackage("SDL2", "Settings > Optional dependencies > SDL2");
        }
        if (useSDL2 && !ToolchainManager.sdlRuntimePresent(context)) {
            return new BuildResult(false, -1, "SDL2 RUNTIME MISSING\n\nReinstall SDL2 from Settings > Optional dependencies.");
        }
        if (useGLES && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.OPENGL)) {
            return missingPackage("OpenGL ES / EGL", "Settings > Optional dependencies > OpenGL ES / EGL");
        }
        if (useCurl && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.CURL)) {
            return missingPackage("libcurl", "Settings > Optional dependencies > libcurl");
        }
        if (useCurl && !ToolchainManager.curlRuntimePresent(context)) {
            return new BuildResult(false, -1, "LIBCURL RUNTIME MISSING\n\nReinstall libcurl from Settings > Optional dependencies.");
        }
        if (useOpenSSL && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.OPENSSL)) {
            return missingPackage("OpenSSL", "Settings > Optional dependencies > OpenSSL");
        }
        if (useZlib && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.ZLIB)) {
            return missingPackage("zlib", "Settings > Optional dependencies > zlib");
        }
        if (useZstd && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.ZSTD)) {
            return missingPackage("zstd", "Settings > Optional dependencies > zstd");
        }
        if (useSQLite && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.SQLITE)) {
            return missingPackage("SQLite", "Settings > Optional dependencies > SQLite");
        }
        if (usePng && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.PNG)) {
            return missingPackage("libpng", "Settings > Optional dependencies > libpng");
        }
        if (useJpeg && !OptionalPackageManager.isInstalled(context, OptionalPackageManager.JPEG)) {
            return missingPackage("libjpeg-turbo", "Settings > Optional dependencies > libjpeg-turbo");
        }

        File out = ProjectStore.newProgramLibrary(context);
        StringBuilder text = new StringBuilder();
        File prefix = ToolchainManager.prefix(context);
        File cxxInclude = new File(prefix, "include/c++/v1");
        File targetInclude = ToolchainManager.multiarchIncludeDir(context);
        File commonInclude = new File(prefix, "include");
        File asmTypes = new File(targetInclude, "asm/types.h");
        if (!asmTypes.isFile()) {
            return new BuildResult(false, -1,
                    "ANDROID SYSROOT INCOMPLETE\n\nABI: " + ToolchainManager.deviceAbiLabel() +
                    "\nExpected multiarch include: " + targetInclude + "\nMissing: " + asmTypes);
        }

        if (makefileAuthoritative) {
            text.append("$ embedded-clang++ <authoritative Makefile plan> -fPIC -shared\n");
        } else {
            text.append("$ embedded-clang++ -std=").append(BuildSettings.cppStandard(context))
                    .append(' ').append(BuildSettings.optimization(context)).append(" -fPIC -shared <project sources>\n");
        }
        text.append("Compiler: ").append(clang).append('\n');
        text.append("Linker:   ").append(lld).append('\n');
        text.append("ABI:      ").append(ToolchainManager.deviceAbiLabel()).append('\n');
        text.append("Target:   ").append(ToolchainManager.androidTarget()).append('\n');
        text.append("Sources:  ").append(sources.size()).append('\n');
        text.append("Profile:  ").append(profileName(useSDL2, useGLES, useCurl, useSockets, useOpenSSL, useZlib, useZstd, useSQLite, usePng, useJpeg)).append("\n");
        text.append("Build cfg: ").append(makefileAuthoritative ? "Makefile authoritative (IDE optimization/LTO/strip ignored)" : BuildSettings.fingerprint(context)).append('\n');
        text.append("Build system: ").append(buildOverrides.description).append('\n');
        if (linkedRefreshLog.length() > 0) text.append(linkedRefreshLog);
        if (!buildOverrides.compileFlags.isEmpty()) text.append("Imported compile flags: ").append(buildOverrides.compileFlags).append('\n');
        if (!buildOverrides.sourceCompileFlags.isEmpty()) text.append("Per-source Makefile flags: ").append(buildOverrides.sourceCompileFlags).append('\n');
        if (!buildOverrides.linkFlags.isEmpty() || !buildOverrides.libraries.isEmpty())
            text.append("Imported link flags/libs: ").append(buildOverrides.linkFlags).append(' ').append(buildOverrides.libraries).append('\n');
        text.append('\n');

        String effectiveBuildFingerprint = makefileAuthoritative
                ? ("MAKEFILE|" + buildOverrides.makefileFingerprint)
                : BuildSettings.fingerprint(context);
        String fingerprint = ToolchainManager.androidTarget() + "|" + effectiveBuildFingerprint +
                "|sdl=" + useSDL2 + "|gles=" + useGLES + "|curl=" + useCurl +
                "|ssl=" + useOpenSSL + "|zlib=" + useZlib + "|zstd=" + useZstd +
                "|sqlite=" + useSQLite + "|png=" + usePng + "|jpeg=" + useJpeg +
                "|androidBridge=" + useAndroidBridge + "|buildSystem=" + BuildSystemConfig.fingerprint(context);
        File fpFile = new File(ProjectStore.buildDir(context), "compile-fingerprint.txt");
        boolean fingerprintChanged = true;
        try {
            fingerprintChanged = !fpFile.isFile() || !fingerprint.equals(new String(Files.readAllBytes(fpFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
        if (fingerprintChanged) {
            ProjectStore.invalidateObjects(context);
            text.append("Build configuration changed: object cache invalidated.\n");
        }

        long newestHeader = 0;
        for (File header : headers) newestHeader = Math.max(newestHeader, header.lastModified());
        List<File> objects = new ArrayList<>();
        int compiledCount = 0;
        int cachedCount = 0;
        text.append("== COMPILE STAGE ==\n");
        for (File source : sources) {
            File obj = ProjectStore.objectFileFor(context, source);
            objects.add(obj);
            boolean needsCompile = !obj.isFile() || source.lastModified() > obj.lastModified() || newestHeader > obj.lastModified();
            if (!needsCompile) {
                cachedCount++;
                text.append("cached   ").append(ProjectStore.relativePath(context, source)).append("\n");
                continue;
            }

            deleteQuietly(obj);
            List<String> compile = baseCompileCommand(context, clang, cxxInclude, targetInclude, commonInclude, !makefileAuthoritative);
            compile.add("-I" + ProjectStore.projectDir(context).getAbsolutePath());
            compile.add("-I" + ProjectStore.sourceDir(context).getAbsolutePath());
            compile.add("-I" + ProjectStore.includeDir(context).getAbsolutePath());
            compile.add("-DDROIDX_PROJECT_DIR=\"" + ProjectStore.projectDir(context).getAbsolutePath() + "\"");
            compile.add("-DDROIDX_ASSET_DIR=\"" + ProjectStore.assetsDir(context).getAbsolutePath() + "\"");
            if (useSDL2) {
                compile.add("-I" + ToolchainManager.sdlIncludeDir(context).getAbsolutePath());
                compile.add("-D_REENTRANT");
            }
            if (useCurl && ToolchainManager.curlCaBundle(context).isFile()) {
                compile.add("-DDROIDX_CA_BUNDLE=\"" + ToolchainManager.curlCaBundle(context).getAbsolutePath() + "\"");
            }
            if (!makefileAuthoritative) {
                for (String def : BuildSettings.lines(BuildSettings.defines(context))) {
                    compile.add(def.startsWith("-D") ? def : "-D" + def);
                }
                for (String path : BuildSettings.lines(BuildSettings.includePaths(context))) {
                    compile.add(path.startsWith("-I") ? path : "-I" + path);
                }
                compile.addAll(BuildSettings.shellTokens(BuildSettings.extraCompilerFlags(context)));
            }
            for (String path : buildOverrides.includePaths) compile.add(path.startsWith("-I") ? path : "-I" + path);
            compile.addAll(buildOverrides.compileFlags);
            compile.addAll(buildOverrides.compileFlagsFor(context, source));
            compile.add("-c");
            compile.add(source.getAbsolutePath());
            compile.add("-o");
            compile.add(obj.getAbsolutePath());

            text.append("compile  ").append(ProjectStore.relativePath(context, source)).append("\n");
            text.append("  cmd: ").append(formatCommand(compile)).append("\n");
            ExecResult cr = run(context, compile, ProjectStore.projectDir(context));
            text.append(cr.output);
            if (cr.exitCode != 0 || !obj.isFile()) {
                deleteQuietly(obj);
                deleteQuietly(out);
                text.append("\nBUILD FAILED compiling ").append(ProjectStore.relativePath(context, source))
                        .append(" (exit ").append(cr.exitCode).append(")\n");
                return new BuildResult(false, cr.exitCode, text.toString());
            }
            compiledCount++;
        }
        text.append("Objects: ").append(compiledCount).append(" compiled, ").append(cachedCount).append(" cached\n\n");

        List<String> plan = new ArrayList<>();
        plan.add(clang.getAbsolutePath());
        plan.add("--driver-mode=g++");
        plan.add("-###");
        plan.add("-shared");
        if (!makefileAuthoritative && BuildSettings.lto(context)) plan.add("-flto");
        if (!makefileAuthoritative && BuildSettings.strip(context)) plan.add("-s");
        plan.add("-fuse-ld=lld");
        plan.add("--ld-path=" + lld.getAbsolutePath());
        plan.add("--target=" + ToolchainManager.androidTarget());
        plan.add("-resource-dir=" + ToolchainManager.resourceDir(context).getAbsolutePath());
        plan.add("-L" + new File(prefix, "lib").getAbsolutePath());
        // v1.6.3: user-program optional runtimes staged beside libprogram.so are
        // discoverable by Android's linker without relying on LD_LIBRARY_PATH.
        plan.add("-Wl,-rpath,$ORIGIN");
        // SDL2 remains an APK-embedded runtime for the tested SDLActivity/JNI bridge.
        if (useSDL2) plan.add("-L" + context.getApplicationInfo().nativeLibraryDir);
        if (!makefileAuthoritative) {
            for (String path : BuildSettings.lines(BuildSettings.libraryPaths(context))) {
                plan.add(path.startsWith("-L") ? path : "-L" + path);
            }
        }
        for (String path : buildOverrides.libraryPaths) plan.add(path.startsWith("-L") ? path : "-L" + path);
        if (useAndroidBridge) plan.add("-L" + context.getApplicationInfo().nativeLibraryDir);
        for (File obj : objects) plan.add(obj.getAbsolutePath());
        if (useSDL2) {
            plan.add("-lSDL2");
            plan.add("-landroid");
            plan.add("-llog");
        }
        if (useCurl) plan.add("-lcurl");
        if (useOpenSSL) { plan.add("-lssl"); plan.add("-lcrypto"); }
        if (useZlib) plan.add("-lz");
        if (useZstd) plan.add("-lzstd");
        if (useSQLite) plan.add("-lsqlite3");
        if (usePng) plan.add("-lpng");
        if (useJpeg) plan.add("-ljpeg");
        if (useGLES) {
            plan.add("-lGLESv3");
            plan.add("-lGLESv2");
            plan.add("-lEGL");
        }
        if (useAndroidBridge) {
            plan.add("-lrunnerbridge");
            plan.add("-landroid");
            plan.add("-llog");
        }
        if (!makefileAuthoritative) {
            for (String lib : BuildSettings.lines(BuildSettings.libraries(context))) {
                if (lib.startsWith("-l") || lib.startsWith("-Wl,") || lib.endsWith(".a") || lib.endsWith(".so")) plan.add(lib);
                else plan.add("-l" + lib);
            }
        }
        for (String lib : buildOverrides.libraries) plan.add(lib.startsWith("-l") || lib.endsWith(".a") || lib.endsWith(".so") ? lib : "-l" + lib);
        if (!makefileAuthoritative) plan.addAll(BuildSettings.shellTokens(BuildSettings.extraLinkerFlags(context)));
        plan.addAll(buildOverrides.linkFlags);
        plan.add("-o");
        plan.add(out.getAbsolutePath());

        text.append("== LINK PLAN (clang -###) ==\n");
        ExecResult planResult = run(context, plan, ProjectStore.projectDir(context));
        text.append(planResult.output);
        if (planResult.exitCode != 0) {
            deleteQuietly(out);
            return new BuildResult(false, planResult.exitCode, text.append("\nBUILD FAILED while generating linker plan\n").toString());
        }

        List<String> driverLink = findLinkerCommand(planResult.output, lld.getAbsolutePath());
        if (driverLink == null || driverLink.size() < 2) {
            deleteQuietly(out);
            return new BuildResult(false, -1, text.append("\nBUILD FAILED: Clang did not emit a parsable LLD command.\n").toString());
        }

        List<String> link = new ArrayList<>();
        link.add(lld.getAbsolutePath());
        link.add("-flavor");
        link.add("gnu");
        for (int i = 1; i < driverLink.size(); i++) link.add(driverLink.get(i));

        text.append("\n== LINK STAGE (LLD GNU/ELF) ==\n");
        ExecResult linkResult = run(context, link, ProjectStore.projectDir(context));
        text.append(linkResult.output);

        if (linkResult.exitCode == 0 && out.isFile()) {
            try {
                if (useSQLite) {
                    stageRuntimeBesideProgram(context, OptionalPackageManager.SQLITE, out, text);
                }
            } catch (Exception e) {
                deleteQuietly(out);
                return new BuildResult(false, -1, text.append("\nBUILD FAILED staging SQLite runtime: ").append(e).append('\n').toString());
            }
            out.setReadable(true, true);
            out.setWritable(false, false);
            try {
                ProjectStore.setActiveProgramLibrary(context, out);
                Files.write(fpFile.toPath(), fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ProjectStore.recordSuccessfulBuildState(context, out);
            } catch (Exception e) {
                deleteQuietly(out);
                return new BuildResult(false, -1, text.append("\nBUILD FAILED registering artifact: ").append(e).append('\n').toString());
            }
            ProjectStore.cleanupOldLibraries(context, out);
            text.append("\nBUILD SUCCESS\n");
            text.append("Output: ").append(out).append('\n');
            text.append("Sources: ").append(sources.size()).append(" · compiled ").append(compiledCount)
                    .append(" · cached ").append(cachedCount).append('\n');
            text.append("Assets: ").append(ProjectStore.assetsDir(context)).append('\n');
            text.append("DROIDX_ASSET_DIR is defined at compile time and exported at runtime.\n");
            return new BuildResult(true, 0, text.toString());
        }

        deleteQuietly(out);
        text.append("\nBUILD FAILED during link (exit ").append(linkResult.exitCode).append(")\n");
        return new BuildResult(false, linkResult.exitCode, text.toString());
    }

    /**
     * Android classloader namespaces do not search DroidCompiler's private PREFIX/lib
     * for DT_NEEDED entries of a user .so loaded from a project directory. Stage the
     * exact optional runtime beside libprogram.so; $ORIGIN RUNPATH + runner preloading
     * then makes the dependency resolvable in both :runner and :sdlrunner.
     */
    private static void stageRuntimeBesideProgram(Context context, String packageId, File program, StringBuilder log) throws Exception {
        File src = OptionalPackageManager.runtimeLibrary(context, packageId);
        if (src == null || !src.isFile()) {
            throw new java.io.FileNotFoundException("Runtime library missing for " + packageId);
        }
        File dir = program.getParentFile();
        if (dir == null) throw new java.io.IOException("Program has no build directory");
        File dst = new File(dir, src.getName());
        if (!dst.isFile() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
            java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        dst.setReadable(true, true);
        dst.setWritable(false, false);
        if (log != null) log.append("Staged runtime: ").append(dst.getAbsolutePath()).append('\n');
    }

    private static List<String> baseCompileCommand(Context context, File clang,
                                                    File cxxInclude, File targetInclude,
                                                    File commonInclude, boolean useIdeBuildSettings) {
        List<String> cmd = new ArrayList<>();
        cmd.add(clang.getAbsolutePath());
        cmd.add("--driver-mode=g++");
        cmd.add("--target=" + ToolchainManager.androidTarget());
        if (useIdeBuildSettings) {
            cmd.add("-std=" + BuildSettings.cppStandard(context));
            cmd.add(BuildSettings.optimization(context));
            if (BuildSettings.debugSymbols(context)) cmd.add("-g");
            if (BuildSettings.warnings(context)) { cmd.add("-Wall"); cmd.add("-Wextra"); }
            if (BuildSettings.lto(context)) cmd.add("-flto");
        }
        cmd.add("-fPIC");
        cmd.add("-resource-dir=" + ToolchainManager.resourceDir(context).getAbsolutePath());
        cmd.add("-isystem");
        cmd.add(cxxInclude.getAbsolutePath());
        cmd.add("-isystem");
        cmd.add(targetInclude.getAbsolutePath());
        cmd.add("-isystem");
        cmd.add(commonInclude.getAbsolutePath());
        return cmd;
    }

    public static boolean usesSDL2(String source) {
        if (source == null) return false;
        return source.contains("<SDL2/SDL.h>") || source.contains("<SDL.h>") ||
                source.contains("\"SDL.h\"") || source.contains("SDL_Init(") || source.contains("SDL_CreateWindow(");
    }

    public static boolean usesCurl(String source) {
        if (source == null) return false;
        return source.contains("<curl/curl.h>") || source.contains("curl_easy_") ||
                source.contains("curl_multi_") || source.contains("curl_ws_");
    }

    public static boolean usesSockets(String source) {
        if (source == null) return false;
        return source.contains("<sys/socket.h>") || source.contains("<netinet/in.h>") ||
                source.contains("<arpa/inet.h>") || source.contains("<netdb.h>") ||
                source.contains("socket(") || source.contains("sendto(") || source.contains("recvfrom(");
    }

    private static String profileName(boolean sdl, boolean gles, boolean curl, boolean sockets,
                                      boolean ssl, boolean zlib, boolean zstd, boolean sqlite,
                                      boolean png, boolean jpeg) {
        List<String> parts = new ArrayList<>();
        if (sdl) parts.add(gles ? "SDL2 + OpenGL ES" : "SDL2");
        else parts.add("Console");
        if (curl) parts.add("libcurl HTTP/HTTPS/WS/WSS");
        if (sockets) parts.add("TCP/UDP sockets");
        if (ssl) parts.add("OpenSSL");
        if (sqlite) parts.add("SQLite");
        if (png) parts.add("libpng");
        if (jpeg) parts.add("JPEG");
        if (zlib) parts.add("zlib");
        if (zstd) parts.add("zstd");
        return String.join(" + ", parts);
    }

    public static boolean usesOpenSSL(String source) {
        if (source == null) return false;
        return source.contains("<openssl/") || source.contains("SSL_CTX_") || source.contains("EVP_");
    }

    public static boolean usesZlib(String source) {
        if (source == null) return false;
        return source.contains("<zlib.h>") || source.contains("deflate(") || source.contains("inflate(");
    }

    public static boolean usesZstd(String source) {
        if (source == null) return false;
        return source.contains("<zstd.h>") || source.contains("ZSTD_");
    }

    public static boolean usesSQLite(String source) {
        if (source == null) return false;
        return source.contains("<sqlite3.h>") || source.contains("sqlite3_");
    }

    public static boolean usesPng(String source) {
        if (source == null) return false;
        return source.contains("<png.h>") || source.contains("png_create_") || source.contains("png_read_");
    }

    public static boolean usesJpeg(String source) {
        if (source == null) return false;
        return source.contains("<jpeglib.h>") || source.contains("jpeg_create_") || source.contains("jpeg_read_");
    }

    private static BuildResult missingPackage(String name, String location) {
        return new BuildResult(false, -1, "OPTIONAL DEPENDENCY NOT INSTALLED\n\n" + name +
                " is required by this project.\nInstall it from " + location + ".");
    }

    public static boolean usesAndroidBridge(String source) {
        if (source == null) return false;
        return source.contains("<DroidXAndroid.h>") || source.contains("\"DroidXAndroid.h\"") ||
                source.contains("droidx_android_");
    }

    public static boolean usesOpenGLES(String source) {
        if (source == null) return false;
        return source.contains("<GLES2/") || source.contains("<GLES3/") || source.contains("<EGL/") ||
                source.contains("SDL_WINDOW_OPENGL") || source.contains("SDL_GL_") || source.contains("glClear(");
    }

    private static final Pattern QUOTED_INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s*\\\"([^\\\"]+)\\\"");

    private static final class LinkedIncludeHydration {
        int materialized;
        final Set<String> missing = new LinkedHashSet<>();
    }

    private static LinkedIncludeHydration hydrateMissingQuotedIncludes(Context context,
                                                                        List<File> sources,
                                                                        List<File> headers) throws Exception {
        LinkedIncludeHydration result = new LinkedIncludeHydration();
        List<File> scan = new ArrayList<>();
        scan.addAll(sources);
        scan.addAll(headers);
        File root = ProjectStore.projectDir(context);
        Set<String> attempted = new LinkedHashSet<>();
        for (File file : scan) {
            String body;
            try {
                body = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) { continue; }
            Matcher m = QUOTED_INCLUDE.matcher(body);
            while (m.find()) {
                String inc = m.group(1).trim().replace('\\', '/');
                if (inc.isEmpty()) continue;
                File beside = new File(file.getParentFile(), inc);
                File rooted = new File(root, inc);
                if (beside.isFile() || rooted.isFile()) continue;

                // Convert ../ relative includes to a safe project-root relative path.
                // This covers modular trees such as ui/screens/../../build/Common.hpp.
                String rootRelative = inc;
                try {
                    File candidate = beside.getCanonicalFile();
                    String rootPath = root.getCanonicalPath();
                    String candidatePath = candidate.getCanonicalPath();
                    if (candidatePath.startsWith(rootPath + File.separator)) {
                        rootRelative = candidatePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
                    } else {
                        continue;
                    }
                } catch (Throwable ignored) {
                    if (inc.startsWith("../") || inc.contains("/../")) continue;
                }
                if (!attempted.add(rootRelative)) continue;
                if (ProjectManager.materializeLinkedRelativeFile(context, rootRelative)) result.materialized++;
                else if (rootRelative.contains("/")) result.missing.add(rootRelative);
            }
        }
        return result;
    }

    private static final class ExecResult {
        final int exitCode;
        final String output;
        ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ExecResult run(Context context, List<String> command, File cwd) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            ToolchainManager.applyEnvironment(context, pb.environment());
            if (cwd != null) pb.directory(cwd);
            process = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) output.append(line).append('\n');
            }
            return new ExecResult(process.waitFor(), output.toString());
        } catch (Throwable t) {
            output.append("PROCESS START FAILED: ").append(t).append('\n');
            return new ExecResult(-1, output.toString());
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Find the linker command printed by clang -###. Clang normally prints each
     * argv element quoted on one line. We select the line containing our embedded
     * linker path and parse shell-like single/double quoted tokens.
     */
    private static List<String> findLinkerCommand(String output, String linkerPath) {
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (!line.contains(linkerPath) && !line.contains("libdroidx_lld.so")) continue;
            List<String> tokens = parseCommandLine(line);
            if (!tokens.isEmpty()) return tokens;
        }
        return null;
    }

    private static List<String> parseCommandLine(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        boolean escaped = false;
        boolean started = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                cur.append(ch);
                escaped = false;
                started = true;
                continue;
            }
            if (ch == '\\' && !single) {
                escaped = true;
                started = true;
                continue;
            }
            if (ch == '\'' && !dbl) {
                single = !single;
                started = true;
                continue;
            }
            if (ch == '"' && !single) {
                dbl = !dbl;
                started = true;
                continue;
            }
            if (Character.isWhitespace(ch) && !single && !dbl) {
                if (started) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(ch);
            started = true;
        }
        if (escaped) cur.append('\\');
        if (started) out.add(cur.toString());
        return out;
    }

    private static String formatCommand(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (String token : command) {
            if (out.length() > 0) out.append(' ');
            if (token == null) { out.append("''"); continue; }
            if (token.matches("[A-Za-z0-9_./:=+,-]+")) out.append(token);
            else out.append('\'').append(token.replace("'", "'\"'\"'")).append('\'');
        }
        return out.toString();
    }

    private static void deleteQuietly(File f) {
        try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) {}
    }

    public static String clangVersion(Context context) {
        File clang = ToolchainManager.embeddedClang(context);
        if (!clang.isFile()) return "Embedded Clang: missing from APK/nativeLibraryDir";
        try {
            ProcessBuilder pb = new ProcessBuilder(clang.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            ToolchainManager.applyEnvironment(context, env);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String first = br.readLine();
                int code = p.waitFor();
                if (code != 0) return "Embedded Clang cannot start (exit " + code + "): " + first;
                return first == null ? "Embedded Clang started" : first;
            }
        } catch (Throwable t) {
            return "Embedded Clang cannot start: " + t.getMessage();
        }
    }
}

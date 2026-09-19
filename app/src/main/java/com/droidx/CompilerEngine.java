package com.droidx;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                    "TOOLCHAIN DATA NOT INSTALLED\n\nTap INSTALL TOOLCHAIN DATA first.");
        }

        File source = ProjectStore.mainCpp(context);
        if (!source.isFile()) return new BuildResult(false, -1, "main.cpp does not exist.");

        final String sourceText;
        try {
            sourceText = new String(Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new BuildResult(false, -1, "Could not read main.cpp: " + e);
        }
        final boolean useSDL2 = usesSDL2(sourceText);
        final boolean useGLES = usesOpenGLES(sourceText);
        final boolean useCurl = usesCurl(sourceText);
        final boolean useSockets = usesSockets(sourceText);
        if (useSDL2 && (!ToolchainManager.sdlRuntimePresent(context) || !ToolchainManager.sdlHeadersInstalled(context))) {
            return new BuildResult(false, -1,
                    "SDL2 SUPPORT NOT READY\n\nTap INSTALL TOOLCHAIN DATA and rebuild the Android Studio app if libSDL2.so is missing.");
        }
        if (useGLES && !ToolchainManager.androidGraphicsHeadersInstalled(context)) {
            return new BuildResult(false, -1,
                    "OPENGL ES HEADERS NOT READY\n\nTap INSTALL TOOLCHAIN DATA to install EGL/GLES platform headers.");
        }
        if (useCurl && (!ToolchainManager.curlRuntimePresent(context) || !ToolchainManager.curlHeadersInstalled(context))) {
            return new BuildResult(false, -1,
                    "LIBCURL SUPPORT NOT READY\n\nTap INSTALL TOOLCHAIN DATA and rebuild the Android Studio app if libcurl.so is missing.");
        }

        File out = ProjectStore.newProgramLibrary(context);
        File obj = new File(out.getParentFile(), out.getName() + ".main.o");
        StringBuilder text = new StringBuilder();

        File prefix = ToolchainManager.prefix(context);
        File cxxInclude = new File(prefix, "include/c++/v1");
        File targetInclude = ToolchainManager.multiarchIncludeDir(context);
        File commonInclude = new File(prefix, "include");
        File asmTypes = new File(targetInclude, "asm/types.h");
        if (!asmTypes.isFile()) {
            return new BuildResult(false, -1,
                    "ANDROID SYSROOT INCOMPLETE\n\n" +
                    "ABI: " + ToolchainManager.deviceAbiLabel() + "\n" +
                    "Expected multiarch include: " + targetInclude + "\n" +
                    "Missing: " + asmTypes + "\n\n" +
                    "Tap INSTALL TOOLCHAIN DATA again to reinstall ndk-sysroot.");
        }

        text.append("$ embedded-clang++ -std=c++20 -fPIC -shared main.cpp\n");
        text.append("Compiler: ").append(clang).append('\n');
        text.append("Linker:   ").append(lld).append('\n');
        text.append("ABI:      ").append(ToolchainManager.deviceAbiLabel()).append('\n');
        text.append("C++ inc:  ").append(cxxInclude).append('\n');
        text.append("Target inc: ").append(targetInclude).append('\n');
        text.append("asm/types.h: ").append(asmTypes.isFile()).append('\n');
        text.append("Target:    ").append(ToolchainManager.androidTarget()).append('\n');
        text.append("Profile:   ").append(profileName(useSDL2, useGLES, useCurl, useSockets)).append("\n\n");

        // Stage 1: compile only. This deliberately avoids invoking any linker.
        List<String> compile = baseCompileCommand(context, clang, cxxInclude, targetInclude, commonInclude);
        if (useSDL2) {
            compile.add("-I" + ToolchainManager.sdlIncludeDir(context).getAbsolutePath());
            compile.add("-D_REENTRANT");
        }
        if (useCurl && ToolchainManager.curlCaBundle(context).isFile()) {
            compile.add("-DDROIDX_CA_BUNDLE=\"" + ToolchainManager.curlCaBundle(context).getAbsolutePath() + "\"");
        }
        compile.add("-c");
        compile.add(source.getAbsolutePath());
        compile.add("-o");
        compile.add(obj.getAbsolutePath());

        text.append("== COMPILE STAGE ==\n");
        ExecResult compileResult = run(context, compile, ProjectStore.projectDir(context));
        text.append(compileResult.output);
        if (compileResult.exitCode != 0 || !obj.isFile()) {
            deleteQuietly(obj);
            deleteQuietly(out);
            text.append("\nBUILD FAILED during compile (exit ").append(compileResult.exitCode).append(")\n");
            return new BuildResult(false, compileResult.exitCode, text.toString());
        }
        text.append("Compiled object: ").append(obj).append(" (").append(obj.length()).append(" bytes)\n\n");

        // Stage 2a: ask Clang to calculate the exact Android linker command, but
        // do not execute it. This preserves Clang's CRT, libc++, compiler-rt,
        // emulation and search-path decisions instead of hard-coding them here.
        List<String> plan = new ArrayList<>();
        plan.add(clang.getAbsolutePath());
        plan.add("--driver-mode=g++");
        plan.add("-###");
        plan.add("-shared");
        plan.add("-fuse-ld=lld");
        plan.add("--ld-path=" + lld.getAbsolutePath());
        plan.add("--target=" + ToolchainManager.androidTarget());
        plan.add("-resource-dir=" + ToolchainManager.resourceDir(context).getAbsolutePath());
        plan.add("-L" + new File(prefix, "lib").getAbsolutePath());
        if (useSDL2 || useCurl) {
            plan.add("-L" + context.getApplicationInfo().nativeLibraryDir);
        }
        plan.add(obj.getAbsolutePath());
        if (useSDL2) {
            plan.add("-lSDL2");
            plan.add("-landroid");
            plan.add("-llog");
        }
        if (useCurl) {
            plan.add("-lcurl");
        }
        if (useGLES) {
            plan.add("-lGLESv3");
            plan.add("-lGLESv2");
            plan.add("-lEGL");
        }
        plan.add("-o");
        plan.add(out.getAbsolutePath());

        text.append("== LINK PLAN (clang -###) ==\n");
        ExecResult planResult = run(context, plan, ProjectStore.projectDir(context));
        text.append(planResult.output);
        if (planResult.exitCode != 0) {
            deleteQuietly(obj);
            deleteQuietly(out);
            text.append("\nBUILD FAILED while generating linker plan (exit ")
                    .append(planResult.exitCode).append(")\n");
            return new BuildResult(false, planResult.exitCode, text.toString());
        }

        List<String> driverLink = findLinkerCommand(planResult.output, lld.getAbsolutePath());
        if (driverLink == null || driverLink.size() < 2) {
            deleteQuietly(obj);
            deleteQuietly(out);
            text.append("\nBUILD FAILED: Clang did not emit a parsable LLD command.\n");
            return new BuildResult(false, -1, text.toString());
        }

        // libdroidx_lld.so is LLVM's generic multicall LLD binary. Its basename
        // cannot be ld.lld because Android only extracts native files packaged as
        // lib*.so. Invoke it directly and make -flavor gnu argv[1]/argv[2], which
        // selects the ELF/Unix driver before normal linker arguments are parsed.
        List<String> link = new ArrayList<>();
        link.add(lld.getAbsolutePath());
        link.add("-flavor");
        link.add("gnu");
        for (int i = 1; i < driverLink.size(); i++) link.add(driverLink.get(i));

        text.append("\n== LINK STAGE (LLD GNU/ELF) ==\n");
        text.append("Driver: generic LLD + -flavor gnu\n");
        ExecResult linkResult = run(context, link, ProjectStore.projectDir(context));
        text.append(linkResult.output);

        deleteQuietly(obj);

        if (linkResult.exitCode == 0 && out.isFile()) {
            // targetSdk 36: never execve this file. RUN loads it as a native library
            // in the separate :runner process. Mark it read-only before loading.
            if (!out.setReadable(true, true) || !out.setWritable(false, false)) {
                text.append("Warning: could not mark output read-only with java.io.File.\n");
            }
            try {
                ProjectStore.setActiveProgramLibrary(context, out);
            } catch (Exception e) {
                deleteQuietly(out);
                text.append("\nBUILD FAILED: linked library was created, but it could not be registered as the active RUN artifact.\n");
                text.append("Reason: ").append(e).append('\n');
                return new BuildResult(false, -1, text.toString());
            }
            ProjectStore.cleanupOldLibraries(context, out);
            text.append("\nBUILD SUCCESS\n");
            text.append("Output: ").append(out).append('\n');
            text.append("Type: shared library (RUN artifact, not APK)\n");
            text.append("Read-only: ").append(!out.canWrite()).append('\n');
            text.append("Size: ").append(out.length()).append(" bytes\n");
            return new BuildResult(true, 0, text.toString());
        }

        deleteQuietly(out);
        text.append("\nBUILD FAILED during link (exit ").append(linkResult.exitCode).append(")\n");
        return new BuildResult(false, linkResult.exitCode, text.toString());
    }

    private static List<String> baseCompileCommand(Context context, File clang,
                                                    File cxxInclude, File targetInclude,
                                                    File commonInclude) {
        List<String> cmd = new ArrayList<>();
        cmd.add(clang.getAbsolutePath());
        cmd.add("--driver-mode=g++");
        cmd.add("--target=" + ToolchainManager.androidTarget());
        cmd.add("-std=c++20");
        cmd.add("-O0");
        cmd.add("-g");
        cmd.add("-Wall");
        cmd.add("-Wextra");
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

    private static String profileName(boolean sdl, boolean gles, boolean curl, boolean sockets) {
        List<String> parts = new ArrayList<>();
        if (sdl) parts.add(gles ? "SDL2 + OpenGL ES" : "SDL2");
        else parts.add("Console");
        if (curl) parts.add("libcurl HTTP/HTTPS/WS/WSS");
        if (sockets) parts.add("TCP/UDP sockets");
        return String.join(" + ", parts);
    }

    public static boolean usesOpenGLES(String source) {
        if (source == null) return false;
        return source.contains("<GLES2/") || source.contains("<GLES3/") || source.contains("<EGL/") ||
                source.contains("SDL_WINDOW_OPENGL") || source.contains("SDL_GL_") || source.contains("glClear(");
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

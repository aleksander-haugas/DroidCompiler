package com.droidx;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatibility reader for common C/C++ Makefiles used by C4droid projects.
 *
 * GNU make itself is not executed.  We resolve the declarative parts that are
 * important for an Android C/C++ project: exact source lists, common flags,
 * link libraries and simple per-source groups such as MAIN_SRC/MAIN_OPT and
 * SCREEN_SRCS/SCREEN_OPT.  The resolved plan is then executed by DroidX's
 * embedded Clang/LLD backend.
 */
public final class MakefileCompat {
    private MakefileCompat() {}

    public static final class Overrides {
        public final List<String> compileFlags = new ArrayList<>();
        public final List<String> linkFlags = new ArrayList<>();
        public final List<String> libraries = new ArrayList<>();
        public final List<String> libraryPaths = new ArrayList<>();
        public final List<String> includePaths = new ArrayList<>();
        public final List<File> sources = new ArrayList<>();
        public final Map<String, List<String>> sourceCompileFlags = new LinkedHashMap<>();
        public boolean authoritative = false;
        public String description = "DroidCompiler internal";
        public String makefileFingerprint = "";

        public List<String> compileFlagsFor(Context context, File source) {
            String rel = ProjectStore.relativePath(context, source).replace('\\', '/');
            List<String> v = sourceCompileFlags.get(rel);
            return v == null ? java.util.Collections.emptyList() : v;
        }
    }

    public static Overrides resolve(Context context) throws Exception {
        String mode = BuildSystemConfig.mode(context);
        if (BuildSystemConfig.MAKEFILE.equals(mode)) return fromMakefile(context);
        if (BuildSystemConfig.CUSTOM.equals(mode)) return fromCustomCommand(context);
        return new Overrides();
    }

    /** Heuristic used only for linked external projects. */
    public static boolean looksLikeC4droidMakefile(Context context) {
        try {
            File makefile = new File(ProjectStore.projectDir(context), "Makefile");
            if (!makefile.isFile()) return false;
            String raw = new String(Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8);
            boolean compilerRecipe = raw.contains("$(CXX)") || raw.contains("${CXX}") || raw.contains("$(CC)");
            boolean sourceManifest = raw.matches("(?s).*\\b(SRCS|SOURCES|MAIN_SRC|SCREEN_SRCS)\\s*[:+?]?=.*");
            boolean androidish = raw.contains("-lSDL2") || raw.contains("-landroid") || raw.toLowerCase().contains("c4droid");
            return compilerRecipe && sourceManifest && androidish;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Overrides fromMakefile(Context context) throws Exception {
        File project = ProjectStore.projectDir(context);
        File makefile = new File(project, BuildSystemConfig.makefilePath(context));
        if (!makefile.isFile()) {
            throw new IllegalStateException("Makefile compatibility mode is enabled, but this file does not exist: " + makefile);
        }

        String raw = new String(Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8);
        Map<String, String> vars = parseVariables(raw);
        vars.putIfAbsent("PROJECT", project.getAbsolutePath());
        vars.putIfAbsent("PREFIX", ToolchainManager.prefix(context).getAbsolutePath());
        vars.putIfAbsent("ABI", ToolchainManager.deviceAbiLabel());
        vars.putIfAbsent("TARGET", ToolchainManager.androidTarget());

        Overrides out = new Overrides();
        out.authoritative = true;
        out.description = "Makefile compatibility (authoritative): " + ProjectStore.relativePath(context, makefile);
        out.makefileFingerprint = Integer.toHexString(raw.hashCode()) + ":" + raw.length();

        // Common C4droid/NDK conventions.  *_COMMON and *_LOCAL are deliberately
        // included because large projects often keep app-local flags out of the
        // generic CXXFLAGS variable.
        addCompile(out, expand(joinVars(vars,
                "CPPFLAGS", "CXXFLAGS", "CFLAGS", "INCLUDES",
                "CPPFLAGS_COMMON", "CPPFLAGS_LOCAL",
                "CXXFLAGS_COMMON", "CXXFLAGS_LOCAL",
                "CFLAGS_COMMON", "CFLAGS_LOCAL"), vars));

        addLink(out, expand(joinVars(vars,
                "LDFLAGS", "LDLIBS", "LIBS",
                "LDFLAGS_COMMON", "LDFLAGS_LOCAL",
                "LDLIBS_COMMON", "LDLIBS_LOCAL",
                "LIBS_COMMON", "LIBS_LOCAL"), vars));

        String sourceText = expand(firstNonEmpty(vars, "SOURCES", "SRCS", "CPP_SOURCES", "CXX_SOURCES"), vars);
        resolveSources(context, sourceText, out.sources);

        // Resolve simple source groups.  CHAINSCAN V212h, for example, declares
        // MAIN_SRC + MAIN_OPT and SCREEN_SRCS + SCREEN_OPT.  Applying these after
        // common flags faithfully gives the main TU -O1 and UI TUs -O2.
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String key = e.getKey();
            String prefix = null;
            if (key.endsWith("_SRCS") && key.length() > 5) prefix = key.substring(0, key.length() - 5);
            else if (key.endsWith("_SRC") && key.length() > 4) prefix = key.substring(0, key.length() - 4);
            if (prefix == null || prefix.isEmpty()) continue;

            String groupFlags = expand(joinVars(vars,
                    prefix + "_OPT",
                    prefix + "_CPPFLAGS",
                    prefix + "_CXXFLAGS",
                    prefix + "_CFLAGS",
                    prefix + "_FLAGS"), vars);
            if (groupFlags.trim().isEmpty()) continue;

            List<File> groupSources = new ArrayList<>();
            resolveSources(context, expand(e.getValue(), vars), groupSources);
            List<String> tokens = BuildSettings.shellTokens(groupFlags);
            for (File f : groupSources) {
                String rel = ProjectStore.relativePath(context, f).replace('\\', '/');
                out.sourceCompileFlags.computeIfAbsent(rel, k -> new ArrayList<>()).addAll(tokens);
            }
        }

        return out;
    }

    private static Overrides fromCustomCommand(Context context) {
        String command = BuildSystemConfig.customCommand(context);
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalStateException("Custom embedded-clang mode is enabled but the command line is empty.");
        }
        if (command.contains("|") || command.contains("&&") || command.contains(";") || command.contains("`")) {
            throw new IllegalStateException("Custom mode accepts a clang/g++ command line, not shell pipelines or scripts.");
        }
        Overrides out = new Overrides();
        out.description = "Custom embedded-clang command";
        List<String> tokens = BuildSettings.shellTokens(command);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == 0 && looksLikeCompiler(t)) continue;
            if (isSourceOrOutputToken(t)) { if ("-o".equals(t) && i + 1 < tokens.size()) i++; continue; }
            classify(out, t);
        }
        return out;
    }

    private static boolean looksLikeCompiler(String t) {
        String n = new File(t).getName().toLowerCase();
        return n.equals("clang++") || n.equals("clang") || n.equals("g++") || n.equals("gcc") || n.equals("c++");
    }

    private static boolean isSourceOrOutputToken(String t) {
        String s = t.toLowerCase();
        return "-o".equals(t) || s.endsWith(".cpp") || s.endsWith(".cc") || s.endsWith(".cxx") || s.endsWith(".c") || s.endsWith(".o");
    }

    private static void addCompile(Overrides out, String flags) {
        for (String t : BuildSettings.shellTokens(flags)) classify(out, t);
    }

    private static void addLink(Overrides out, String flags) {
        for (String t : BuildSettings.shellTokens(flags)) {
            if (t.startsWith("-I") || t.startsWith("-D")) out.compileFlags.add(t);
            else classify(out, t);
        }
    }

    private static void classify(Overrides out, String t) {
        if (t == null || t.isEmpty()) return;
        if (t.startsWith("-I")) { out.includePaths.add(t); return; }
        if (t.startsWith("-L")) { out.libraryPaths.add(t); return; }
        if (t.startsWith("-l") || t.endsWith(".a") || t.endsWith(".so")) { out.libraries.add(t); return; }
        if (t.startsWith("-Wl,") || t.equals("-shared") || t.equals("-static") || t.startsWith("-fuse-ld")) {
            out.linkFlags.add(t); return;
        }
        if (t.equals("-pthread")) {
            out.compileFlags.add(t);
            out.linkFlags.add(t);
            return;
        }
        out.compileFlags.add(t);
    }

    private static Map<String, String> parseVariables(String raw) {
        Map<String, String> vars = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String physical : raw.split("\\r?\\n")) {
            String line = physical;
            if (logical.length() > 0) logical.append(' ');
            boolean cont = line.endsWith("\\");
            logical.append(cont ? line.substring(0, line.length() - 1) : line);
            if (cont) continue;
            String full = logical.toString().trim();
            if (full.startsWith("export ")) full = full.substring(7).trim();
            logical.setLength(0);
            if (full.isEmpty() || full.startsWith("#") || full.startsWith("\t")) continue;
            int hash = findUnquotedHash(full);
            if (hash >= 0) full = full.substring(0, hash).trim();
            Matcher m = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(\\+=|:=|\\?=|=)\\s*(.*)$").matcher(full);
            if (!m.matches()) continue;
            String key = m.group(1), op = m.group(2), value = m.group(3).trim();
            if ("+=".equals(op)) vars.put(key, (vars.getOrDefault(key, "") + " " + value).trim());
            else if ("?=".equals(op)) vars.putIfAbsent(key, value);
            else vars.put(key, value);
        }
        return vars;
    }

    private static int findUnquotedHash(String s) {
        boolean single = false, dbl = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return i;
        }
        return -1;
    }

    private static String expand(String value, Map<String, String> vars) {
        if (value == null) return "";
        String out = value;
        Pattern p = Pattern.compile("\\$\\(([^)]+)\\)|\\$\\{([^}]+)\\}");
        for (int round = 0; round < 8; round++) {
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1) != null ? m.group(1).trim() : m.group(2).trim();
                String replacement = vars.getOrDefault(key, "");
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            m.appendTail(sb);
            out = sb.toString();
            if (!changed) break;
        }
        return out;
    }

    private static String joinVars(Map<String, String> vars, String... names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            String v = vars.get(n);
            if (v != null && !v.trim().isEmpty()) b.append(' ').append(v.trim());
        }
        return b.toString().trim();
    }

    private static String firstNonEmpty(Map<String, String> vars, String... names) {
        for (String n : names) {
            String v = vars.get(n);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static String globRegex(String glob) {
        StringBuilder r = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { r.append(".*"); i++; }
                else r.append("[^/]*");
            } else if (c == '?') r.append("[^/]");
            else if (".[]{}()+-^$|\\".indexOf(c) >= 0) r.append('\\').append(c);
            else r.append(c);
        }
        return r.append('$').toString();
    }

    private static void resolveSources(Context context, String text, List<File> out) {
        if (text == null || text.trim().isEmpty()) return;
        File root = ProjectStore.projectDir(context);
        Set<String> seen = new LinkedHashSet<>();
        for (File existing : out) seen.add(existing.getAbsolutePath());
        for (String token : BuildSettings.shellTokens(text)) {
            if (token.contains("$(wildcard") || token.contains("${wildcard")) continue;
            String clean = token.replace("\\", "/");
            if (clean.contains("*")) {
                String regex = globRegex(clean);
                for (File f : ProjectStore.allSourceFiles(context)) {
                    String rel = ProjectStore.relativePath(context, f).replace('\\', '/');
                    if (rel.matches(regex) && seen.add(f.getAbsolutePath())) out.add(f);
                }
            } else {
                File f = new File(root, clean);
                if (f.isFile() && seen.add(f.getAbsolutePath())) out.add(f);
            }
        }
    }
}

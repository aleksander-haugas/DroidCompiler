package com.droidx;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TermuxPackageIndex {
    static final class PackageInfo {
        final String name;
        final String version;
        final String filename;
        final String sha256;
        final String depends;
        final String preDepends;
        final String provides;

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

    private final Map<String, PackageInfo> packages = new HashMap<>();
    private final Map<String, String> providerFor = new HashMap<>();

    static TermuxPackageIndex parse(String text) throws Exception {
        TermuxPackageIndex result = new TermuxPackageIndex();
        BufferedReader br = new BufferedReader(new StringReader(text));
        Map<String, String> fields = new HashMap<>();
        String currentKey = null;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                result.add(fields);
                fields = new HashMap<>();
                currentKey = null;
                continue;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && currentKey != null) {
                fields.put(currentKey, fields.get(currentKey) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            currentKey = line.substring(0, colon);
            fields.put(currentKey, line.substring(colon + 1).trim());
        }
        result.add(fields);
        result.buildProviders();
        return result;
    }

    private void add(Map<String, String> fields) {
        if (fields.isEmpty()) return;
        PackageInfo p = new PackageInfo(fields);
        if (!p.name.isEmpty() && !p.filename.isEmpty()) packages.put(p.name, p);
    }

    private void buildProviders() {
        for (PackageInfo p : packages.values()) {
            for (String v : splitDeps(p.provides)) {
                String n = cleanDepName(v);
                if (!n.isEmpty()) providerFor.putIfAbsent(n, p.name);
            }
        }
    }

    PackageInfo get(String name) {
        PackageInfo p = packages.get(name);
        if (p != null) return p;
        String provider = providerFor.get(name);
        return provider == null ? null : packages.get(provider);
    }

    List<PackageInfo> dependencyClosure(String root, ToolchainManager.Listener listener) throws Exception {
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        LinkedHashSet<String> done = new LinkedHashSet<>();
        List<PackageInfo> ordered = new ArrayList<>();
        visit(root, visiting, done, ordered, listener);
        return ordered;
    }

    private void visit(String requested,
                       Set<String> visiting,
                       Set<String> done,
                       List<PackageInfo> ordered,
                       ToolchainManager.Listener listener) throws Exception {
        if (done.contains(requested)) return;
        if (visiting.contains(requested)) return;

        PackageInfo p = get(requested);
        if (p == null) {
            // APT metadata occasionally names a system/virtual dependency that is not a package.
            listener.onLog("Skipping unresolved dependency: " + requested);
            done.add(requested);
            return;
        }

        if (done.contains(p.name)) {
            done.add(requested);
            return;
        }

        visiting.add(p.name);
        String merged = p.preDepends;
        if (!p.depends.isEmpty()) merged = merged.isEmpty() ? p.depends : merged + ", " + p.depends;

        for (String group : splitDeps(merged)) {
            String selected = selectAlternative(group);
            if (!selected.isEmpty()) visit(selected, visiting, done, ordered, listener);
        }

        visiting.remove(p.name);
        done.add(p.name);
        done.add(requested);
        ordered.add(p);
    }

    private String selectAlternative(String group) {
        String[] alternatives = group.split("\\|");
        for (String alt : alternatives) {
            String name = cleanDepName(alt);
            if (name.isEmpty()) continue;
            if (get(name) != null) return name;
        }
        return alternatives.length == 0 ? "" : cleanDepName(alternatives[0]);
    }

    private static List<String> splitDeps(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        int paren = 0;
        int bracket = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') paren++;
            else if (c == ')' && paren > 0) paren--;
            else if (c == '[') bracket++;
            else if (c == ']' && bracket > 0) bracket--;
            else if (c == ',' && paren == 0 && bracket == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = s.substring(start).trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    private static String cleanDepName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int pipe = s.indexOf('|');
        if (pipe >= 0) s = s.substring(0, pipe).trim();
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren).trim();
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket).trim();
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon).trim();
        // Reject substitution variables if one somehow survives repository generation.
        if (s.startsWith("${")) return "";
        return s.toLowerCase(Locale.ROOT);
    }
}

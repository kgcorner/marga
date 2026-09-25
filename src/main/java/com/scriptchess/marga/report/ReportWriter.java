package com.scriptchess.marga.report;

import com.scriptchess.marga.graph.CallGraph;
import com.scriptchess.marga.impact.Impact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the report folder:
 * <pre>
 *   index.html            viewer
 *   data/manifest.js      counts, chunk index, format version
 *   data/core.js          topology (always loaded)
 *   data/search.js        method names (loaded right after core)
 *   data/overview.js      class-to-class call counts (lazy, for the overview)
 *   data/sig-NNN.js       parameter lists per package group (lazy)
 *   data/impact.js        changed methods of a diff (only when an impact analysis ran)
 *   impact.json / .md     the same impact, for CI and pull-request comments
 * </pre>
 * Each data file is a single {@code Marga.receive(name, payload)} call so it loads via
 * a script tag, which also works when the report is opened from file://.
 */
public final class ReportWriter {

    public static final int FORMAT_VERSION = 2;

    private static final int METHODS_PER_SIG_CHUNK = 20_000;
    private static final String TEMPLATE_RESOURCE = "/marga/report-template.html";
    private static final String CYTOSCAPE_RESOURCE = "/marga/cytoscape.min.js";
    private static final String JS_PLACEHOLDER = "/*__CYTOSCAPE_JS__*/";

    /** Writes the report into {@code outputDir} and returns the path of index.html. */
    public Path write(CallGraph graph, Path outputDir) throws IOException {
        return write(graph, outputDir, null);
    }

    /** Same, plus the change impact of a diff when {@code impact} is not null. */
    public Path write(CallGraph graph, Path outputDir, Impact impact) throws IOException {
        Path dataDir = outputDir.resolve("data");
        Files.createDirectories(dataDir);
        deleteOldDataFiles(dataDir);

        List<Map<String, Object>> sigChunks = writeSignatureChunks(graph, dataDir);
        writeData(dataDir, "core", core(graph));
        writeData(dataDir, "search", search(graph));
        writeData(dataDir, "overview", overview(graph));
        if (impact != null) {
            writeData(dataDir, "impact", impactData(impact));
            Files.writeString(outputDir.resolve("impact.json"), Json.write(impactJson(graph, impact)) + "\n", StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("impact.md"), impactMarkdown(graph, impact), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(outputDir.resolve("impact.json"));
            Files.deleteIfExists(outputDir.resolve("impact.md"));
        }
        Map<String, Object> manifest = manifest(graph, sigChunks);
        manifest.put("impact", impact != null);
        writeData(dataDir, "manifest", manifest);

        String cytoscape = readResource(CYTOSCAPE_RESOURCE).replace("</script", "<\\/script");
        String html = readResource(TEMPLATE_RESOURCE).replace(JS_PLACEHOLDER, cytoscape);
        Path index = outputDir.resolve("index.html");
        Files.writeString(index, html, StandardCharsets.UTF_8);
        return index;
    }

    // ------------------------------------------------------------------ payloads

    private static Map<String, Object> manifest(CallGraph g, List<Map<String, Object>> sigChunks) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("packages", g.packages().length);
        stats.put("classes", g.classCount());
        stats.put("methods", g.methodCount());
        stats.put("edges", g.edgeCount());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("stats", stats);
        manifest.put("profiles", g.activeProfiles());
        manifest.put("sigChunks", sigChunks);
        return manifest;
    }

    private static Map<String, Object> core(CallGraph g) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("packages", g.packages());
        core.put("pkgClassOffsets", b64(g.pkgClassOffsets()));
        core.put("modules", g.modules());
        core.put("classNames", g.classNames());
        core.put("classModule", b64(g.classModule()));
        core.put("classFlags", b64(g.classFlags()));
        core.put("classMethodOffsets", b64(g.classMethodOffsets()));
        core.put("methodFlags", b64(g.methodFlags()));
        core.put("fwdOffsets", b64(g.fwdOffsets()));
        core.put("fwdTargets", b64(g.fwdTargets()));
        core.put("fwdKinds", b64(g.fwdKinds()));
        core.put("revOffsets", b64(g.revOffsets()));
        core.put("revSources", b64(g.revSources()));
        core.put("revKinds", b64(g.revKinds()));
        core.put("ovrOffsets", b64(g.ovrOffsets()));
        core.put("ovrTargets", b64(g.ovrTargets()));
        core.put("implOffsets", b64(g.implOffsets()));
        core.put("implSources", b64(g.implSources()));
        return core;
    }

    /** Class-to-class call counts, for the (upcoming) overview; kept out of core to keep first load small. */
    private static Map<String, Object> overview(CallGraph g) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("classEdgePairs", b64(g.classEdgePairs()));
        overview.put("classEdgeWeights", b64(g.classEdgeWeights()));
        return overview;
    }

    /** Deduplicated method-name table plus one index per method. */
    private static Map<String, Object> search(CallGraph g) {
        StringTable names = new StringTable();
        int[] idx = new int[g.methodCount()];
        for (int m = 0; m < idx.length; m++) {
            idx[m] = names.id(g.methodNames()[m]);
        }
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("names", names.values());
        search.put("idx", b64(idx));
        return search;
    }

    /** Parameter lists, chunked along package boundaries at ~METHODS_PER_SIG_CHUNK methods. */
    private static List<Map<String, Object>> writeSignatureChunks(CallGraph g, Path dataDir) throws IOException {
        List<Map<String, Object>> chunks = new ArrayList<>();
        int[] pkgClass = g.pkgClassOffsets();
        int[] classMethod = g.classMethodOffsets();
        int start = 0;
        for (int p = 0; p < g.packages().length; p++) {
            int pkgEnd = classMethod[pkgClass[p + 1]];
            boolean last = p == g.packages().length - 1;
            if (pkgEnd - start >= METHODS_PER_SIG_CHUNK || (last && pkgEnd > start)) {
                String name = String.format("sig-%03d", chunks.size());
                writeData(dataDir, name, signatureChunk(g, start, pkgEnd));
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("name", name);
                ref.put("start", start);
                ref.put("end", pkgEnd);
                chunks.add(ref);
                start = pkgEnd;
            }
        }
        return chunks;
    }

    private static Map<String, Object> signatureChunk(CallGraph g, int start, int end) {
        StringTable params = new StringTable();
        int[] idx = new int[end - start];
        for (int m = start; m < end; m++) {
            idx[m - start] = params.id(g.methodParams()[m]);
        }
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("start", start);
        chunk.put("params", params.values());
        chunk.put("idx", b64(idx));
        return chunk;
    }

    // ------------------------------------------------------------------ impact

    private static Map<String, Object> impactData(Impact impact) {
        List<Object> changed = new ArrayList<>();
        for (Impact.ChangedMethod c : impact.changed()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("m", c.method());
            item.put("file", c.file());
            item.put("lines", c.lines());
            changed.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", impact.base());
        data.put("changed", changed);
        data.put("unmapped", unmappedList(impact));
        return data;
    }

    private static Map<String, Object> impactJson(CallGraph g, Impact impact) {
        List<Object> changed = new ArrayList<>();
        for (Impact.ChangedMethod c : impact.changed()) {
            Map<String, Object> item = methodJson(g, c.method());
            item.put("file", c.file());
            item.put("lines", c.lines());
            changed.add(item);
        }
        List<Object> entries = new ArrayList<>();
        for (int m : impact.entryPoints()) {
            entries.add(methodJson(g, m));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changedMethods", impact.changed().size());
        summary.put("impactedMethods", impact.impacted().length);
        summary.put("affectedEntryPoints", impact.entryPoints().length);
        summary.put("unmappedChanges", impact.unmapped().size());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("base", impact.base());
        json.put("profiles", g.activeProfiles());
        json.put("summary", summary);
        json.put("affectedEntryPoints", entries);
        json.put("changedMethods", changed);
        json.put("unmapped", unmappedList(impact));
        return json;
    }

    private static List<Object> unmappedList(Impact impact) {
        List<Object> list = new ArrayList<>();
        for (Impact.Unmapped u : impact.unmapped()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", u.file());
            item.put("lines", u.lines());
            item.put("reason", u.reason());
            list.add(item);
        }
        return list;
    }

    private static Map<String, Object> methodJson(CallGraph g, int m) {
        int c = classOf(g, m);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("class", fqn(g, c));
        item.put("method", methodLabel(g, m, c));
        return item;
    }

    private static String impactMarkdown(CallGraph g, Impact impact) {
        StringBuilder md = new StringBuilder();
        md.append("# Marga change impact\n\n");
        md.append("Compared against `").append(impact.base()).append("` · profile: ")
                .append(String.join(", ", g.activeProfiles())).append("\n\n");
        md.append("**").append(impact.changed().size()).append(" methods changed · ")
                .append(impact.impacted().length).append(" methods impacted · ")
                .append(impact.entryPoints().length).append(" entry points affected**\n\n");

        md.append("## Affected entry points\n\n");
        if (impact.entryPoints().length == 0) {
            md.append("None found.\n\n");
        }
        for (int m : impact.entryPoints()) {
            int c = classOf(g, m);
            md.append("- `").append(qualified(g, m, c)).append("` — ").append(fqn(g, c)).append('\n');
        }
        md.append("\n## Changed methods\n\n");
        for (Impact.ChangedMethod ch : impact.changed()) {
            int c = classOf(g, ch.method());
            md.append("- `").append(qualified(g, ch.method(), c)).append("` — ").append(ch.file());
            if (ch.lines().isEmpty()) {
                md.append(" (contains a changed local/anonymous class)");
            } else {
                md.append(':').append(ranges(ch.lines()));
            }
            md.append('\n');
        }
        if (!impact.unmapped().isEmpty()) {
            md.append("\n## Changes not mapped to a method\n\n");
            for (Impact.Unmapped u : impact.unmapped()) {
                md.append("- ").append(u.file());
                if (!u.lines().isEmpty()) {
                    md.append(':').append(ranges(u.lines()));
                }
                md.append(" — ").append(u.reason()).append('\n');
            }
        }
        return md.toString();
    }

    /** [3,4,5,9] -> "3-5, 9" */
    private static String ranges(List<Integer> lines) {
        List<Integer> sorted = lines.stream().distinct().sorted().toList();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            int start = sorted.get(i);
            while (i + 1 < sorted.size() && sorted.get(i + 1) == sorted.get(i) + 1) {
                i++;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(start);
            if (sorted.get(i) != start) {
                out.append('-').append(sorted.get(i));
            }
        }
        return out.toString();
    }

    private static int classOf(CallGraph g, int m) {
        int[] offsets = g.classMethodOffsets();
        int lo = 0, hi = offsets.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= m) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static String fqn(CallGraph g, int c) {
        int p = 0;
        while (g.pkgClassOffsets()[p + 1] <= c) {
            p++;
        }
        String pkg = g.packages()[p];
        return pkg.isEmpty() ? g.classNames()[c] : pkg + "." + g.classNames()[c];
    }

    /** "UserService.create(String)", or "new UserService(Repo)" for constructors. */
    private static String qualified(CallGraph g, int m, int c) {
        boolean constructor = (g.methodFlags()[m] & CallGraph.M_CONSTRUCTOR) != 0;
        return constructor ? methodLabel(g, m, c) : g.classNames()[c] + "." + methodLabel(g, m, c);
    }

    private static String methodLabel(CallGraph g, int m, int c) {
        String name = (g.methodFlags()[m] & CallGraph.M_CONSTRUCTOR) != 0 ? "new " + g.classNames()[c] : g.methodNames()[m];
        return name + "(" + g.methodParams()[m] + ")";
    }

    // ------------------------------------------------------------------ io helpers

    private static void writeData(Path dataDir, String name, Map<String, Object> payload) throws IOException {
        String js = "Marga.receive(\"" + name + "\"," + Json.write(payload) + ");\n";
        Files.writeString(dataDir.resolve(name + ".js"), js, StandardCharsets.UTF_8);
    }

    private static void deleteOldDataFiles(Path dataDir) throws IOException {
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dataDir, "*.js")) {
            for (Path file : old) {
                Files.delete(file);
            }
        }
    }

    private static String b64(int[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(values);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static String b64(byte[] values) {
        return Base64.getEncoder().encodeToString(values);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = ReportWriter.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing plugin resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Assigns stable ids to repeated strings. */
    private static final class StringTable {
        private final Map<String, Integer> ids = new LinkedHashMap<>();

        int id(String value) {
            return ids.computeIfAbsent(value, v -> ids.size());
        }

        String[] values() {
            return ids.keySet().toArray(String[]::new);
        }
    }
}
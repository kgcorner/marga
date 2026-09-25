package com.scriptchess.marga.impact;

import com.scriptchess.marga.graph.CallGraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Maps changed source lines onto methods, then walks callers to find the impact radius.
 *
 * When the source file can be read, blank and comment-only lines are ignored (they cannot change
 * behavior) and import/package lines are reported as class-level changes. A changed line L is
 * then attributed to:
 * <ol>
 *   <li>every method with bytecode on line L (lambda bodies count as their enclosing method;
 *       field initializers count as the constructors);</li>
 *   <li>otherwise, when L continues a statement started on an earlier line (the previous code
 *       line does not end with ; { or }), the methods owning that statement;</li>
 *   <li>otherwise, when L looks like part of a declaration (an annotation or a signature line),
 *       the method whose first code line follows within {@value #MAX_HEADER_LINES} lines;</li>
 *   <li>otherwise, the innermost method whose line range contains L.</li>
 * </ol>
 * Lines matching none of these (fields without initializer, class annotations) are reported as unmapped.
 * A changed method inside a local or anonymous class also marks its enclosing method.
 */
public final class ImpactAnalyzer {

    static final int MAX_HEADER_LINES = 20;

    /** Analyzes without source text (less precise: comments and imports cannot be told apart). */
    public Impact analyze(CallGraph graph, Map<String, TreeSet<Integer>> changedLines, String base) {
        return analyze(graph, changedLines, base, null);
    }

    /**
     * @param sourceRoot directory the diff paths are relative to (the git repository root),
     *                   used to read changed lines; null to skip source-text checks
     */
    public Impact analyze(CallGraph graph, Map<String, TreeSet<Integer>> changedLines, String base, Path sourceRoot) {
        Map<String, List<Integer>> classesBySource = new HashMap<>();
        for (int c = 0; c < graph.classCount(); c++) {
            String source = graph.classSourcePaths()[c];
            if (source != null) {
                classesBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(c);
            }
        }

        Map<Integer, Impact.ChangedMethod> changed = new LinkedHashMap<>();
        List<Impact.Unmapped> unmapped = new ArrayList<>();

        for (Map.Entry<String, TreeSet<Integer>> file : changedLines.entrySet()) {
            String path = file.getKey();
            if (!path.endsWith(".java")) {
                unmapped.add(new Impact.Unmapped(path, List.of(), "not Java source"));
                continue;
            }
            List<Integer> classes = lookup(path, classesBySource);
            if (classes == null) {
                unmapped.add(new Impact.Unmapped(path, List.of(),
                        "not in the scanned code (test sources or a module that was not built)"));
                continue;
            }
            FileIndex index = new FileIndex(graph, classes, readSource(sourceRoot, path));
            List<Integer> missed = new ArrayList<>();
            for (int line : file.getValue()) {
                if (index.isNoise(line)) {
                    continue; // blank or comment-only: cannot change behavior
                }
                List<Integer> hits = index.hit(line);
                if (hits.isEmpty()) {
                    missed.add(line);
                }
                for (int m : hits) {
                    changed.computeIfAbsent(m, k -> new Impact.ChangedMethod(k, path, new ArrayList<>()))
                            .lines().add(line);
                }
            }
            if (!missed.isEmpty()) {
                unmapped.add(new Impact.Unmapped(path, missed,
                        "outside any method body (imports, fields without initializer, class annotations)"));
            }
        }

        // a change inside a local/anonymous class also changes the method that declares it
        Deque<Integer> pending = new ArrayDeque<>(changed.keySet());
        while (!pending.isEmpty()) {
            int m = pending.pop();
            int outer = graph.classOuterMethod()[classOf(graph, m)];
            if (outer >= 0 && !changed.containsKey(outer)) {
                changed.put(outer, new Impact.ChangedMethod(outer, changed.get(m).file(), new ArrayList<>()));
                pending.push(outer);
            }
        }

        // impact radius: every transitive caller of a changed method
        BitSet impacted = new BitSet(graph.methodCount());
        Deque<Integer> queue = new ArrayDeque<>(changed.keySet());
        changed.keySet().forEach(impacted::set);
        while (!queue.isEmpty()) {
            int m = queue.poll();
            for (int e = graph.revOffsets()[m]; e < graph.revOffsets()[m + 1]; e++) {
                int caller = graph.revSources()[e];
                if (!impacted.get(caller)) {
                    impacted.set(caller);
                    queue.add(caller);
                }
            }
        }
        int[] impactedIds = impacted.stream().toArray();
        int[] entryPoints = impacted.stream()
                .filter(m -> (graph.methodFlags()[m] & CallGraph.M_ENTRY) != 0)
                .toArray();

        return new Impact(base, List.copyOf(changed.values()), impactedIds, entryPoints, unmapped);
    }

    private static List<String> readSource(Path root, String path) {
        if (root == null) {
            return null;
        }
        try {
            return Files.readAllLines(root.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // not on disk (e.g. a patch file from elsewhere): fall back to line tables only
        }
    }

    /** Matches a repo-relative diff path to a scanned source path by its longest suffix. */
    private static List<Integer> lookup(String path, Map<String, List<Integer>> classesBySource) {
        List<Integer> found = classesBySource.get(path);
        for (int i = path.indexOf('/'); found == null && i >= 0; i = path.indexOf('/', i + 1)) {
            found = classesBySource.get(path.substring(i + 1));
        }
        return found;
    }

    static int classOf(CallGraph graph, int method) {
        int[] offsets = graph.classMethodOffsets();
        int lo = 0, hi = offsets.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= method) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Line lookup structures for the classes compiled from one source file. */
    private static final class FileIndex {
        private final TreeMap<Integer, List<Integer>> methodsByLine = new TreeMap<>();
        private final List<int[]> ranges = new ArrayList<>(); // {method, first, last}
        private final List<String> source;                     // null when unavailable

        FileIndex(CallGraph graph, List<Integer> classes, List<String> source) {
            this.source = source;
            for (int c : classes) {
                for (int m = graph.classMethodOffsets()[c]; m < graph.classMethodOffsets()[c + 1]; m++) {
                    int[] lines = graph.methodLines()[m];
                    if (lines.length == 0) {
                        continue; // abstract, or compiled without line numbers
                    }
                    for (int line : lines) {
                        methodsByLine.computeIfAbsent(line, k -> new ArrayList<>()).add(m);
                    }
                    ranges.add(new int[]{m, lines[0], lines[lines.length - 1]});
                }
            }
        }

        boolean isNoise(int line) {
            String text = text(line);
            return text != null && (text.isEmpty() || text.startsWith("//") || text.startsWith("/*")
                    || text.startsWith("*") || text.endsWith("*/") && !text.contains(";"));
        }

        List<Integer> hit(int line) {
            List<Integer> exact = methodsByLine.get(line);
            if (exact != null) {
                return exact;
            }
            String text = text(line);
            if (text != null && (text.startsWith("import ") || text.startsWith("package "))) {
                return List.of();
            }
            if(text == null) {
                return List.of();
            }
            if (looksLikeDeclaration(text)) {
                List<Integer> declared = declarationOf(line);
                if (!declared.isEmpty()) {
                    return declared;
                }
            }
            List<Integer> continued = continuation(line, text);
            if (continued != null) {
                return continued;
            }

            int[] innermost = null;
            for (int[] r : ranges) {
                if (r[1] <= line && line <= r[2] && (innermost == null || r[2] - r[1] < innermost[2] - innermost[1])) {
                    innermost = r;
                }
            }
            return innermost == null ? List.of() : List.of(innermost[0]);
        }

        /** L continues a statement when the previous meaningful source line is an unfinished statement. */
        private List<Integer> continuation(int line, String text) {
            if (source == null || text.startsWith("@")) {
                return null;
            }
            Map.Entry<Integer, List<Integer>> previousCode = methodsByLine.lowerEntry(line);
            if (previousCode == null) {
                return null;
            }
            for (int p = line - 1; p >= previousCode.getKey(); p--) {
                String prev = text(p);
                if (prev == null || prev.isEmpty() || prev.startsWith("//") || prev.startsWith("*") || prev.startsWith("/*")) {
                    continue;
                }
                boolean finished = prev.endsWith(";") || prev.endsWith("{") || prev.endsWith("}") || prev.startsWith("@");
                return finished ? null : previousCode.getValue();
            }
            return null;
        }

        /** The method whose first code line follows L closely, with no other code in between. */
        private List<Integer> declarationOf(int line) {
            Map.Entry<Integer, List<Integer>> next = methodsByLine.higherEntry(line);
            List<Integer> starting = new ArrayList<>();
            if (next != null && next.getKey() - line <= MAX_HEADER_LINES) {
                for (int[] r : ranges) {
                    if (r[1] == next.getKey()) {
                        starting.add(r[0]);
                    }
                }
            }
            return starting;
        }

        /** Annotation, signature or throws clause (field declarations end with ';' and are excluded). */
        private static boolean looksLikeDeclaration(String text) {
            return text.startsWith("@") || text.startsWith("throws ") || text.equals("{")
                    || (text.contains("(") && !text.endsWith(";"));
        }

        private String text(int line) {
            return source == null || line < 1 || line > source.size() ? null : source.get(line - 1).trim();
        }
    }
}
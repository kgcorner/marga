package com.scriptchess.marga.report;

import com.scriptchess.marga.graph.CallGraph;
import com.scriptchess.marga.impact.Impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Renders an {@link Impact} as report data, impact.json and a QA-friendly impact.md. */
final class ImpactFormatter {

    /** Entry-point kinds in checklist order, with section titles. */
    private static final int[] KIND_ORDER = {1, 4, 5, 6, 7, 2, 3, 0};
    private static final Map<Integer, String> KIND_TITLES = Map.of(
            1, "HTTP endpoints", 2, "Scheduled jobs", 3, "Event listeners", 4, "Kafka consumers",
            5, "RabbitMQ listeners", 6, "JMS listeners", 7, "SQS listeners", 0, "Other entry points");
    private static final int MAX_LIST = 150;       // entries per checklist section (PR comments are size-capped)
    private static final int MAX_RISKIEST = 10;

    private ImpactFormatter() {
    }

    /** "1 method" / "3 methods" */
    private static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    // ------------------------------------------------------------------ report data (impact.js)

    static Map<String, Object> data(CallGraph g, Impact impact) {
        List<Object> changed = new ArrayList<>();
        for (Impact.ChangedMethod c : impact.changed()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("m", c.method());
            item.put("file", c.file());
            item.put("lines", c.lines());
            item.put("reach", c.reach());
            item.put("model", isModelChange(g, c.method()));
            item.put("added", c.added());
            item.put("config", g.configMethods()[c.method()]);
            changed.add(item);
        }
        List<Object> entries = new ArrayList<>();
        for (Impact.EntryImpact e : impact.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("m", e.method());
            item.put("edited", e.edited());
            item.put("near", e.nearestChange());
            item.put("hops", e.hops());
            item.put("certain", e.certain());
            item.put("changes", e.changes());
            item.put("reached", e.reachedChanges());
            entries.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", impact.base());
        data.put("changed", changed);
        data.put("entries", entries);
        data.put("impactedCount", impact.impacted().length);
        data.put("unmapped", unmapped(impact));
        return data;
    }

    // ------------------------------------------------------------------ impact.json

    static Map<String, Object> json(CallGraph g, Impact impact) {
        List<Object> entries = new ArrayList<>();
        for (Impact.EntryImpact e : impact.entries()) {
            Map<String, Object> item = method(g, e.method());
            item.put("kind", kindTitle(g, e.method()));
            item.put("trigger", g.entryRoutes()[e.method()]);
            item.put("editedDirectly", e.edited());
            item.put("new", e.edited() && isAdded(impact, e.method()));
            if (!e.edited()) {
                item.put("via", method(g, e.nearestChange()));
                item.put("hops", e.hops());
            }
            item.put("certain", e.certain());
            entries.add(item);
        }
        List<Object> changed = new ArrayList<>();
        for (Impact.ChangedMethod c : impact.changed()) {
            Map<String, Object> item = method(g, c.method());
            item.put("file", c.file());
            item.put("lines", c.lines());
            item.put("entryPointsReached", c.reach());
            item.put("modelChange", isModelChange(g, c.method()));
            item.put("new", c.added());
            item.put("configuration", g.configMethods()[c.method()]);
            changed.add(item);
        }
        long direct = impact.entries().stream().filter(Impact.EntryImpact::edited).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changedMethods", impact.changed().size());
        summary.put("changedClasses", impact.changed().stream().map(c -> classOf(g, c.method())).distinct().count());
        summary.put("impactedMethods", impact.impacted().length);
        summary.put("affectedEntryPoints", impact.entries().size());
        summary.put("editedDirectly", direct);
        summary.put("affectedThroughDependencies", impact.entries().size() - direct);
        summary.put("unmappedChanges", impact.unmapped().size());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("base", impact.base());
        json.put("profiles", g.activeProfiles());
        json.put("summary", summary);
        json.put("affectedEntryPoints", entries);
        json.put("changedMethods", changed);
        json.put("unmapped", unmapped(impact));
        return json;
    }

    // ------------------------------------------------------------------ impact.md

    static String markdown(CallGraph g, Impact impact) {
        StringBuilder md = new StringBuilder();
        List<Impact.EntryImpact> indirect = impact.entries().stream().filter(e -> !e.edited()).toList();
        List<Impact.EntryImpact> direct = impact.entries().stream().filter(Impact.EntryImpact::edited).toList();
        long classes = impact.changed().stream().map(c -> classOf(g, c.method())).distinct().count();
        Map<Integer, Impact.ChangedMethod> changedById = new LinkedHashMap<>();
        impact.changed().forEach(c -> changedById.put(c.method(), c));
        Set<Integer> newMethods = new HashSet<>();
        impact.changed().stream().filter(Impact.ChangedMethod::added).forEach(c -> newMethods.add(c.method()));
        long newEntries = direct.stream().filter(e -> newMethods.contains(e.method())).count();

        md.append("# Marga change impact\n\n");
        md.append("**Compared against:** `").append(impact.base()).append("` · **Profile:** ")
                .append(String.join(", ", g.activeProfiles())).append("\n\n");
        md.append("> **").append(count(impact.changed().size(), "method", "methods")).append(" changed** in ")
                .append(count(classes, "class", "classes")).append(" → **")
                .append(count(impact.entries().size(), "entry point", "entry points")).append(" affected** (")
                .append(direct.size()).append(" edited directly")
                .append(newEntries > 0 ? ", " + newEntries + " of them new" : "")
                .append(" · ").append(indirect.size()).append(" through dependencies)");
        if (!impact.unmapped().isEmpty()) {
            md.append(" · ").append(count(impact.unmapped().size(), "change", "changes")).append(" not mapped");
        }
        md.append("\n\n");

        List<Impact.ChangedMethod> config = impact.changed().stream().filter(c -> g.configMethods()[c.method()]).toList();
        if (!config.isEmpty()) {
            md.append("> ⚠️ **Configuration changed:** ")
                    .append(String.join(", ", config.stream().map(c -> "`" + qualified(g, c.method()) + "`").toList()))
                    .append(". Configuration (security, serialization, data sources…) can affect every endpoint, "
                            + "so smoke-test the application broadly, not only the entry points listed below.\n\n");
        }

        // ---- minimal test set
        boolean allRanked = impact.changed().stream().allMatch(c -> c.reach() >= 0);
        if (allRanked && !impact.entries().isEmpty()) {
            List<Pick> picks = minimalTestSet(impact);
            int covered = picks.stream().mapToInt(p -> p.covers().size()).sum();
            md.append("## Minimal test set\n\n");
            md.append("Testing these **").append(count(picks.size(), "entry point", "entry points"))
                    .append("** exercises all **").append(count(covered, "changed method", "changed methods"))
                    .append("** that any entry point reaches.\n\n");
            for (Pick p : picks) {
                appendEntry(md, g, p.entry());
                if (p.entry().edited() && isAdded(impact, p.entry().method())) {
                    md.append(" · 🆕 new");
                }
                md.append(" · covers ").append(count(p.covers().size(), "changed method", "changed methods"));
                if (!p.entry().certain()) {
                    md.append(" · ⚠️ may call");
                }
                md.append('\n');
            }
            List<Impact.ChangedMethod> unreached = impact.changed().stream().filter(c -> c.reach() == 0).toList();
            if (!unreached.isEmpty()) {
                List<Impact.ChangedMethod> configuration = unreached.stream().filter(c -> g.configMethods()[c.method()]).toList();
                List<Impact.ChangedMethod> framework = unreached.stream()
                        .filter(c -> !g.configMethods()[c.method()] && isModelChange(g, c.method())).toList();
                List<Impact.ChangedMethod> other = unreached.stream()
                        .filter(c -> !configuration.contains(c) && !framework.contains(c)).toList();
                md.append("\n**").append(count(unreached.size(), "changed method is", "changed methods are"))
                        .append(" not reached by any entry point:**\n\n");
                unreachedGroup(md, g, configuration, "Configuration, runs at startup and may affect every endpoint: "
                        + "covered by the broad smoke test above");
                unreachedGroup(md, g, framework, "Model methods called by frameworks (JSON or database mapping): "
                        + "covered by endpoints that read or write these models");
                unreachedGroup(md, g, other, "No caller found: check with unit tests");
            }
            md.append('\n');
        }

        // ---- QA checklist
        md.append("## QA checklist\n\n");
        if (impact.entries().isEmpty()) {
            md.append("No endpoint, listener or scheduled job reaches the changed code.\n\n");
        } else {
            md.append("Entry points reached by this change. The ones affected **through dependencies** are the ones "
                    + "most easily missed in testing.\n\n");
            md.append("### Affected through dependencies (").append(indirect.size()).append(")\n\n");
            if (indirect.isEmpty()) {
                md.append("None: every affected entry point was edited directly.\n\n");
            } else {
                dependencyChecklist(md, g, indirect, newMethods);
            }
            md.append("### Edited directly (").append(direct.size()).append(")\n\n");
            if (!direct.isEmpty()) {
                md.append("<details><summary>Show ").append(count(direct.size(), "entry point", "entry points"))
                        .append(direct.size() == 1 ? " whose own code changed" : " whose own code changed")
                        .append("</summary>\n\n");
                checklist(md, g, direct, false, newMethods);
                md.append("</details>\n\n");
            }
        }

        // ---- riskiest changes
        List<Impact.ChangedMethod> ranked = impact.changed().stream()
                .filter(c -> c.reach() > 0 && !isModelChange(g, c.method()))
                .sorted(Comparator.comparingInt(Impact.ChangedMethod::reach).reversed())
                .limit(MAX_RISKIEST).toList();
        if (!ranked.isEmpty()) {
            int wide = Math.max(5, (int) Math.ceil(impact.entries().size() * 0.3));
            md.append("## Riskiest changes\n\n");
            md.append("Changes ranked by how many entry points they reach. Review these most carefully.\n\n");
            md.append("| Changed method | Entry points reached | |\n|---|---:|---|\n");
            for (Impact.ChangedMethod c : ranked) {
                md.append("| `").append(qualified(g, c.method())).append("` | ").append(c.reach()).append(" | ")
                        .append(c.reach() >= wide ? "🔥 shared code, wide blast radius" : "").append(" |\n");
            }
            md.append('\n');
        }

        // ---- changes by class (excluding plain model accessors)
        Map<Integer, List<Impact.ChangedMethod>> byClass = new TreeMap<>();
        List<Impact.ChangedMethod> model = new ArrayList<>();
        for (Impact.ChangedMethod c : impact.changed()) {
            if (isModelChange(g, c.method())) {
                model.add(c);
            } else {
                byClass.computeIfAbsent(classOf(g, c.method()), k -> new ArrayList<>()).add(c);
            }
        }
        if (!byClass.isEmpty()) {
            md.append("## All changes by class\n\n");
            byClass.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<Integer, List<Impact.ChangedMethod>> e) ->
                            e.getValue().stream().mapToInt(Impact.ChangedMethod::reach).max().orElse(0)).reversed())
                    .forEach(e -> {
                        int c = e.getKey();
                        int reach = e.getValue().stream().mapToInt(Impact.ChangedMethod::reach).max().orElse(0);
                        md.append("<details><summary><b>").append(g.classNames()[c]).append("</b> · ")
                                .append(count(e.getValue().size(), "method", "methods"));
                        if (reach > 0) {
                            md.append(" · reaches up to ").append(count(reach, "entry point", "entry points"));
                        }
                        md.append("</summary>\n\n| Method | Where | Entry points reached |\n|---|---|---:|\n");
                        e.getValue().stream().sorted(Comparator.comparingInt(Impact.ChangedMethod::reach).reversed())
                                .forEach(ch -> md.append("| ").append(ch.added() ? "🆕 " : "").append('`').append(label(g, ch.method())).append("` | `")
                                        .append(where(ch)).append("` | ").append(ch.reach() < 0 ? "–" : ch.reach())
                                        .append(" |\n"));
                        md.append("\n</details>\n\n");
                    });
        }

        // ---- model / DTO changes
        if (!model.isEmpty()) {
            long modelClasses = model.stream().map(c -> classOf(g, c.method())).distinct().count();
            md.append("## Model / DTO changes\n\n");
            md.append("<details><summary>").append(count(model.size(), "getter, setter or constructor",
                            "getters, setters or constructors")).append(" in ")
                    .append(count(modelClasses, "model class", "model classes")).append("</summary>\n\n");
            model.forEach(c -> md.append("- `").append(qualified(g, c.method())).append("` · ").append(where(c)).append('\n'));
            md.append("\n</details>\n\n");
        }

        // ---- unmapped
        if (!impact.unmapped().isEmpty()) {
            md.append("## Changes not mapped to a method\n\n");
            md.append("Check these by hand: configuration and resources can change behavior without touching Java code.\n\n");
            for (Impact.Unmapped u : impact.unmapped()) {
                md.append("- `").append(u.file());
                if (!u.lines().isEmpty()) {
                    md.append(':').append(ranges(u.lines()));
                }
                md.append("` · ").append(u.reason()).append('\n');
            }
            md.append('\n');
        }

        md.append("---\n<sub>⚠️ <b>may call</b>: the path passes through an interface or overridden method, so this entry "
                + "point is affected only if that implementation is the one used at runtime. Marga is static analysis: "
                + "reflection, async events and HTTP calls between services are not followed. Generated by Marga.</sub>\n");
        return md.toString();
    }

    /**
     * When one change alone explains most dependency-affected entry points (a shared helper),
     * those are folded away: they all exercise the same changed code.
     */
    private static void dependencyChecklist(StringBuilder md, CallGraph g, List<Impact.EntryImpact> indirect,
                                            Set<Integer> newMethods) {
        Map<Integer, List<Impact.EntryImpact>> onlyVia = new LinkedHashMap<>();
        indirect.stream().filter(e -> e.changes() == 1)
                .forEach(e -> onlyVia.computeIfAbsent(e.nearestChange(), k -> new ArrayList<>()).add(e));
        Map.Entry<Integer, List<Impact.EntryImpact>> dominant = onlyVia.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size())).orElse(null);
        if (dominant == null || dominant.getValue().size() < 5 || dominant.getValue().size() < indirect.size() * 0.4) {
            checklist(md, g, indirect, true, newMethods);
            return;
        }
        String via = qualified(g, dominant.getKey());
        List<Impact.EntryImpact> folded = dominant.getValue();
        List<Impact.EntryImpact> rest = indirect.stream().filter(e -> !folded.contains(e)).toList();
        md.append("> **").append(folded.size()).append(" of ").append(indirect.size()).append("** are reached only through `")
                .append(via).append("`. They exercise the same changed code, so a few of them (see the minimal test set) "
                        + "cover that change.\n\n");
        if (!rest.isEmpty()) {
            checklist(md, g, rest, true, newMethods);
        }
        md.append("<details><summary>").append(count(folded.size(), "entry point", "entry points"))
                .append(" reached only through <code>").append(via).append("</code></summary>\n\n");
        checklist(md, g, folded, true, newMethods);
        md.append("</details>\n\n");
    }

    private record Pick(Impact.EntryImpact entry, List<Integer> covers) {
    }

    private static void unreachedGroup(StringBuilder md, CallGraph g, List<Impact.ChangedMethod> changes, String title) {
        if (changes.isEmpty()) {
            return;
        }
        md.append("- ").append(title).append(": ");
        md.append(String.join(", ", changes.stream().limit(10).map(c -> "`" + qualified(g, c.method()) + "`").toList()));
        if (changes.size() > 10) {
            md.append(" and ").append(changes.size() - 10).append(" more");
        }
        md.append('\n');
    }

    private static boolean isAdded(Impact impact, int method) {
        return impact.changed().stream().anyMatch(c -> c.method() == method && c.added());
    }

    /**
     * Greedy set cover: repeatedly picks the entry point that reaches the most changed methods not yet
     * covered (ties: certain paths first, then fewer hops).
     */
    private static List<Pick> minimalTestSet(Impact impact) {
        Set<Integer> left = new LinkedHashSet<>();
        impact.entries().forEach(e -> left.addAll(e.reachedChanges()));
        List<Pick> picks = new ArrayList<>();
        while (!left.isEmpty()) {
            Impact.EntryImpact best = null;
            List<Integer> bestCover = List.of();
            for (Impact.EntryImpact e : impact.entries()) {
                List<Integer> cover = e.reachedChanges().stream().filter(left::contains).toList();
                if (cover.isEmpty()) {
                    continue;
                }
                boolean better = cover.size() > bestCover.size()
                        || cover.size() == bestCover.size()
                        && (e.certain() && !best.certain() || e.certain() == best.certain() && e.hops() < best.hops());
                if (better) {
                    best = e;
                    bestCover = cover;
                }
            }
            if (best == null) {
                break;
            }
            picks.add(new Pick(best, bestCover));
            bestCover.forEach(left::remove);
        }
        return picks;
    }

    private static void appendEntry(StringBuilder md, CallGraph g, Impact.EntryImpact e) {
        String route = g.entryRoutes()[e.method()];
        md.append("- [ ] ");
        if (route != null) {
            md.append('`').append(route).append("` — ");
        }
        md.append('`').append(qualified(g, e.method())).append('`');
    }

    /** Checklist items grouped by entry-point kind, then by class. */
    private static void checklist(StringBuilder md, CallGraph g, List<Impact.EntryImpact> entries, boolean withReason,
                                  Set<Integer> newMethods) {
        Map<Integer, List<Impact.EntryImpact>> byKind = new LinkedHashMap<>();
        for (int kind : KIND_ORDER) {
            byKind.put(kind, new ArrayList<>());
        }
        entries.forEach(e -> byKind.get(entryKind(g, e.method())).add(e));

        for (Map.Entry<Integer, List<Impact.EntryImpact>> kind : byKind.entrySet()) {
            List<Impact.EntryImpact> list = kind.getValue();
            if (list.isEmpty()) {
                continue;
            }
            md.append("#### ").append(KIND_TITLES.get(kind.getKey())).append(" (").append(list.size()).append(")\n\n");
            Map<Integer, List<Impact.EntryImpact>> byClass = new TreeMap<>(
                    Comparator.comparing((Integer c) -> g.classNames()[c]));
            list.forEach(e -> byClass.computeIfAbsent(classOf(g, e.method()), k -> new ArrayList<>()).add(e));
            int shown = 0;
            for (Map.Entry<Integer, List<Impact.EntryImpact>> cls : byClass.entrySet()) {
                if (shown >= MAX_LIST) {
                    break;
                }
                md.append("**").append(g.classNames()[cls.getKey()]).append("**\n\n");
                for (Impact.EntryImpact e : cls.getValue().stream()
                        .sorted(Comparator.comparing((Impact.EntryImpact x) -> String.valueOf(g.entryRoutes()[x.method()])))
                        .toList()) {
                    if (shown++ >= MAX_LIST) {
                        break;
                    }
                    String route = g.entryRoutes()[e.method()];
                    md.append("- [ ] ");
                    if (route != null) {
                        md.append("`").append(route).append("` — ");
                    }
                    md.append("`").append(label(g, e.method())).append('`');
                    if (withReason) {
                        md.append(" · via `").append(qualified(g, e.nearestChange())).append("`, ")
                                .append(count(e.hops(), "hop", "hops"));
                        if (e.changes() > 1) {
                            md.append(" (+").append(count(e.changes() - 1, "more change", "more changes")).append(')');
                        }
                    }
                    if (e.edited() && newMethods.contains(e.method())) {
                        md.append(" · 🆕 new");
                    }
                    if (!e.certain()) {
                        md.append(" · ⚠️ may call");
                    }
                    md.append('\n');
                }
                md.append('\n');
            }
            if (list.size() > MAX_LIST) {
                md.append("_…and ").append(list.size() - MAX_LIST).append(" more (see impact.json)._\n\n");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A getter, setter or constructor in a class that is not a Spring bean: usually a DTO or entity. */
    static boolean isModelChange(CallGraph g, int m) {
        int flags = g.methodFlags()[m];
        boolean accessorOrCtor = (flags & (CallGraph.M_ACCESSOR | CallGraph.M_CONSTRUCTOR)) != 0;
        return accessorOrCtor && (g.classFlags()[classOf(g, m)] & 7) == CallGraph.STEREO_OTHER;
    }

    private static List<Object> unmapped(Impact impact) {
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

    private static Map<String, Object> method(CallGraph g, int m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("class", fqn(g, classOf(g, m)));
        item.put("method", label(g, m));
        return item;
    }

    private static int entryKind(CallGraph g, int m) {
        return (g.methodFlags()[m] >> CallGraph.M_ENTRY_KIND_SHIFT) & 7;
    }

    private static String kindTitle(CallGraph g, int m) {
        return KIND_TITLES.get(entryKind(g, m));
    }

    private static String where(Impact.ChangedMethod c) {
        if (c.lines().isEmpty()) {
            return c.file() + " (inside a changed anonymous class)";
        }
        if (c.added()) { // a whole new method: its span reads better than every fragment
            int first = c.lines().stream().mapToInt(Integer::intValue).min().orElse(0);
            int last = c.lines().stream().mapToInt(Integer::intValue).max().orElse(0);
            return c.file() + ":" + (first == last ? String.valueOf(first) : first + "-" + last);
        }
        return c.file() + ":" + ranges(c.lines());
    }

    /** [3,4,5,9] -> "3-5, 9" */
    static String ranges(List<Integer> lines) {
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

    static int classOf(CallGraph g, int m) {
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
        int lo = 0, hi = g.packages().length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (g.pkgClassOffsets()[mid] <= c) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        String pkg = g.packages()[lo];
        return pkg.isEmpty() ? g.classNames()[c] : pkg + "." + g.classNames()[c];
    }

    /** "create(String)", or "new UserService(Repo)" for constructors. */
    private static String label(CallGraph g, int m) {
        int c = classOf(g, m);
        String name = (g.methodFlags()[m] & CallGraph.M_CONSTRUCTOR) != 0 ? "new " + g.classNames()[c] : g.methodNames()[m];
        return name + "(" + g.methodParams()[m] + ")";
    }

    /** "UserService.create(String)", or "new UserService(Repo)" for constructors. */
    private static String qualified(CallGraph g, int m) {
        boolean constructor = (g.methodFlags()[m] & CallGraph.M_CONSTRUCTOR) != 0;
        return constructor ? label(g, m) : g.classNames()[classOf(g, m)] + "." + label(g, m);
    }
}
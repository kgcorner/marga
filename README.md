# Marga Maven Plugin

**Marga** (Sanskrit for *path*) scans the compiled classes of a Maven project — including every
module in a multi-module reactor — and generates an **interactive method call graph** as a
self-contained HTML report. With an optional git diff, it also performs **change impact analysis**:
which entry points and methods are affected by your uncommitted or branch changes.

It works directly on compiled `.class` bytecode (via [ASM](https://asm.ow2.io/)), so it needs no
source parsing, no running application, and no special compiler setup — just `mvn compile` output.

## Features

- **Static call graph** of all compiled classes across all reactor modules — direct calls,
  lambda/method-reference calls, and virtual dispatch edges are distinguished.
- **Spring-aware**: recognizes `@Controller`, `@Service`, `@Repository`, `@Component`,
  `@Bean` methods, constructor/field injection, interfaces and overrides, and local/anonymous
  classes (lambda bodies are attributed to their enclosing method).
- **Profile-aware**: evaluates Spring `@Profile` expressions (`prod`, `!prod`,
  `dev & (eu | us)`, `|`, `&`, parentheses) and marks beans as active, inactive, or conditional
  for the active profile set.
- **Interactive HTML report** — a [Cytoscape.js](https://js.cytoscape.org/)-based viewer with
  search, overview, and light/dark theme, fully self-contained and openable via `file://`.
- **Change impact analysis** — map a git diff onto changed methods, then walk callers to compute
  the impact radius. Results are shown in the report and emitted as `impact.json` / `impact.md`
  for CI pipelines and pull-request comments.
- **Large-codebase friendly** — the graph is stored in compact array-based form and the report
  data is chunked and loaded lazily, so it scales to big codebases.

## Requirements

- **JDK 17+** (the plugin itself is compiled with `--release 17`)
- **Apache Maven 3.9.0+**
- **Git 2.30+** — only for impact analysis with `-Dmarga.diffBase` (needed for `--merge-base`)

## Getting started

Build and install the plugin locally:

```bash
mvn install
```

Then run it in the project you want to analyze. Marga is an **aggregator goal**: it runs once at
the end and scans every module of the reactor, so run it from the root:

```bash
mvn compile marga:graph
```

(`mvn install marga:graph` also works, but compiling is all it needs.)

Open the generated report:

```
target/marga/index.html
```

## Change impact analysis

Point Marga at a git ref (branch, tag, or commit) or a ready-made patch file:

```bash
# Compare against origin/main (committed + uncommitted changes are included)
mvn compile marga:graph -Dmarga.diffBase=origin/main

# Or analyze a diff file produced elsewhere (e.g. in CI)
mvn compile marga:graph -Dmarga.diffFile=changes.patch
```

When a diff base or file is provided, Marga:

1. maps each changed line onto the method(s) that own it (lambda bodies count as their enclosing
   method; blank/comment lines are ignored);
2. walks the caller graph from every changed method to compute the impact radius;
3. highlights impacted methods and affected entry points in the HTML report;
4. writes `impact.json` and `impact.md` next to the report for CI consumption and PR comments.

Without `-Dmarga.diffBase` / `-Dmarga.diffFile`, only the call graph report is generated.

## Configuration

All properties are optional.

| Property                | Default                            | Description                                       |
|-------------------------|------------------------------------|---------------------------------------------------|
| `marga.outputDirectory` | `${project.build.directory}/marga` | Where the report and data files are written.      |
| `marga.skip`            | `false`                            | Set to `true` to skip execution.                  |
| `marga.diffBase`        | —                                  | Git ref to diff against (e.g. `origin/main`).     |
| `marga.diffFile`        | —                                  | Path to a unified diff file instead of running `git diff`. |

## Output layout

```
target/marga/
├── index.html          interactive call graph viewer
├── scan.json           raw scan result (debug output)
├── impact.json         impact analysis result (only with a diff)   [JSON, for CI]
├── impact.md           same impact, rendered as Markdown         [for PR comments]
└── data/
    ├── manifest.js     counts, chunk index, format version
    ├── core.js         graph topology (always loaded)
    ├── search.js       method names for search
    ├── overview.js     class-to-class call counts (lazy)
    ├── sig-NNN.js      parameter lists per package group (lazy)
    └── impact.js       changed methods of the diff (only with a diff)
```

The data files are plain `<script>` payloads, so the report works when opened directly from the
filesystem — no web server required.

## How it works

1. **Scan** — `MargaClassFileScanner` walks each module's `target/classes` and parses every
   `.class` file with ASM (`MargaClassVisitor` / `MargaMethodVisitor`), collecting the class
   hierarchy, annotations, `@Profile` values, source-file mappings and per-method call sites.
2. **Build** — `CallGraphBuilder` resolves call sites into a compact, array-based `CallGraph`
   record with forward/reverse edges, override/implementation links, and class-level edge pairs.
   Spring bean stereotypes and profile activity are resolved here.
3. **Analyze** (optional) — `ImpactAnalyzer` maps diff lines onto methods and walks reverse
   (caller) edges to find the impact radius.
4. **Report** — `ReportWriter` renders everything into the HTML template with embedded
   Cytoscape.js, chunked and lazily loaded data files, plus optional `impact.json` / `impact.md`.

## Development

```bash
mvn install        # build, run tests, install locally
```

Project layout:

```
src/main/java/com/scriptchess/marga/
├── mojo/       Maven goal (marga:graph)
├── scanner/    class-file discovery
├── visitors/   ASM class/method visitors
├── scan/       scan result model (classes, methods, call sites)
├── graph/      call graph construction (records, profile evaluation)
├── impact/     git diff + change impact analysis
└── report/     HTML report generation
```

## Status

`0.1.0-SNAPSHOT` — early development. The plugin goal name and configuration properties may
still change.

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
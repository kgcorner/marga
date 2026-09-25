package com.scriptchess.marga.mojo;

import com.scriptchess.marga.graph.CallGraph;
import com.scriptchess.marga.graph.CallGraphBuilder;
import com.scriptchess.marga.impact.GitDiff;
import com.scriptchess.marga.impact.Impact;
import com.scriptchess.marga.impact.ImpactAnalyzer;
import com.scriptchess.marga.report.MargaHtmlReportRenderer;
import com.scriptchess.marga.report.ReportWriter;
import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.scanner.MargaClassFileScanner;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans compiled classes of every module in the reactor.
 * Aggregator: runs once, after all modules are built.
 * Usage: mvn compile marga:graph
 */
@Mojo(name = "graph", aggregator = true, threadSafe = true)
public class MargaMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "marga.outputDirectory", defaultValue = "${project.build.directory}/marga")
    private File outputDirectory;

    @Parameter(property = "marga.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "marga.diffBase") private String diffBase;   // e.g. origin/main
    @Parameter(property = "marga.diffFile") private File diffFile;     // or a ready .patch from CI

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Marga: skipped");
            return;
        }

        MargaClassFileScanner scanner = new MargaClassFileScanner(getLog());
        List<ScannedClass> allClasses = new ArrayList<>();

        for (MavenProject module : session.getProjects()) {
            if ("pom".equals(module.getPackaging())) {
                continue;
            }
            Path classesDir = Path.of(module.getBuild().getOutputDirectory());
            if (!Files.isDirectory(classesDir)) {
                getLog().warn("Marga: no compiled classes in module '" + module.getArtifactId()
                        + "'. Run 'mvn compile marga:graph'.");
                continue;
            }
            try {
                List<ScannedClass> classes = scanner.scan(classesDir, module.getArtifactId());
                getLog().info("Marga: " + module.getArtifactId() + " -> " + classes.size() + " classes");
                allClasses.addAll(classes);
            } catch (IOException e) {
                throw new MojoExecutionException("Marga: failed to scan " + classesDir, e);
            }
        }

        writeScanResult(allClasses);
        generateReport(allClasses);
    }

    private void generateReport(List<ScannedClass> allClasses) throws MojoExecutionException {
        CallGraph graph = new CallGraphBuilder().build(allClasses);
        Path index = null;
        try {
            Impact impact = null;
            if (diffBase != null || diffFile != null) {
                Path root = Path.of(session.getExecutionRootDirectory());
                Path repo = GitDiff.repoRoot(root);
                String diff = diffFile != null ? Files.readString(diffFile.toPath()) : GitDiff.run(root, diffBase);
                impact = new ImpactAnalyzer().analyze(graph, GitDiff.parse(diff),
                        diffFile != null ? diffFile.getName() : diffBase, repo);
                //new ReportWriter().write(graph, outputDirectory.toPath(), impact);
                getLog().info("Marga: impact report -> " + outputDirectory.toPath().resolve("impact.html").toUri());
            } else {
                getLog().info("Marga: no diff base or diff file provided; skipping impact analysis");
            }
            index = new ReportWriter().write(graph, outputDirectory.toPath(), impact);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        getLog().info("Marga: report -> " + index.toUri());
    }

    /** Temporary debug output; replaced by the HTML report later. */
    private void writeScanResult(List<ScannedClass> classes) throws MojoExecutionException {
        Path target = outputDirectory.toPath().resolve("scan.json");
        try {
            Files.createDirectories(target.getParent());
            JsonMapper mapper = JsonMapper.builder()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .build();
            mapper.writeValue(target.toFile(), classes);
            getLog().info("Marga: scanned " + classes.size() + " classes -> " + target);
        } catch (IOException | JacksonException e) {
            throw new MojoExecutionException("Marga: failed to write " + target, e);
        }
    }
}
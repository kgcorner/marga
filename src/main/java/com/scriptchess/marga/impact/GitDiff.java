package com.scriptchess.marga.impact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs `git diff` and turns unified-diff output into changed line numbers per file (new side). */
public final class GitDiff {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private GitDiff() {
    }

    /**
     * Diff between the merge base of {@code base} and the working tree, so both committed and
     * uncommitted changes are included. Requires git 2.30+ (for --merge-base).
     */
    public static String run(Path workingDir, String base) throws IOException {
        return git(workingDir, List.of("git", "diff", "--no-color", "--no-ext-diff", "--unified=0",
                "--merge-base", base));
    }

    private static String git(Path workingDir, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).directory(workingDir.toFile()).start();
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        String stdout = read(process.getInputStream());
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("git timed out: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git failed (" + String.join(" ", command) + "): " + stderr.join().trim());
        }
        return stdout;
    }

    /** The repository root (`git rev-parse --show-toplevel`); diff paths are relative to it. */
    public static Path repoRoot(Path workingDir) throws IOException {
        return Path.of(git(workingDir, List.of("git", "rev-parse", "--show-toplevel")).trim());
    }

    /**
     * Changed lines per file path (as written in the diff, repo-relative).
     * Pure deletions mark the lines around the deletion point. Deleted files are skipped.
     */
    public static Map<String, TreeSet<Integer>> parse(String diff) {
        Map<String, TreeSet<Integer>> changes = new LinkedHashMap<>();
        TreeSet<Integer> current = null;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.equals("/dev/null")) {
                    current = null; // file deleted
                } else {
                    path = path.startsWith("b/") ? path.substring(2) : path;
                    current = changes.computeIfAbsent(path, k -> new TreeSet<>());
                }
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (current != null && hunk.find()) {
                int start = Integer.parseInt(hunk.group(1));
                int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                if (count == 0) {           // pure deletion after line `start`
                    if (start > 0) {
                        current.add(start);
                    }
                    current.add(start + 1);
                } else {
                    for (int l = start; l < start + count; l++) {
                        current.add(l);
                    }
                }
            }
        }
        return changes;
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
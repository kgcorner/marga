package com.scriptchess.marga.impact;

import java.util.List;

/**
 * Result of mapping a diff onto the call graph.
 *
 * @param base        what the diff was taken against (branch, commit or patch file name)
 * @param changed     methods whose code the diff touches
 * @param impacted    changed methods plus everything that calls them, transitively
 * @param entryPoints entry points (endpoints, listeners, schedulers) among the impacted methods
 * @param unmapped    changes that could not be attributed to a method
 */
public record Impact(
        String base,
        List<ChangedMethod> changed,
        int[] impacted,
        int[] entryPoints,
        List<Unmapped> unmapped) {

    /** @param lines changed lines attributed to the method; empty when it only encloses a changed local/anonymous class */
    public record ChangedMethod(int method, String file, List<Integer> lines) {
    }

    public record Unmapped(String file, List<Integer> lines, String reason) {
    }
}
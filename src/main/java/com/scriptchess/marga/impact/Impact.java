package com.scriptchess.marga.impact;

import java.util.List;

/**
 * Result of mapping a diff onto the call graph.
 *
 * @param base        what the diff was taken against (branch, commit or patch file name)
 * @param changed     methods whose code the diff touches
 * @param impacted    changed methods plus everything that calls them, transitively
 * @param entries     entry points (endpoints, listeners, schedulers) among the impacted methods
 * @param unmapped    changes that could not be attributed to a method
 */
public record Impact(
        String base,
        List<ChangedMethod> changed,
        int[] impacted,
        List<EntryImpact> entries,
        List<Unmapped> unmapped) {

    public record ChangedMethod(int method, String file, List<Integer> lines, int reach, boolean added) {
        ChangedMethod withReach(int value) {
            return new ChangedMethod(method, file, lines, value, added);
        }

        ChangedMethod withAdded(boolean value) {
            return new ChangedMethod(method, file, lines, reach, value);
        }
    }

    /**
     * Why an entry point is affected.
     *
     * @param edited        its own code changed
     * @param nearestChange the closest changed method it reaches
     * @param hops          call hops to that change (0 when edited)
     * @param certain       reached without passing through a "may call" dispatch edge
     * @param changes       how many changed methods it reaches, or -1 when not ranked
     * @param reachedChanges the changed methods it reaches (empty when not ranked)
     */
    public record EntryImpact(int method, boolean edited, int nearestChange, int hops, boolean certain, int changes,
                              List<Integer> reachedChanges) {
    }

    public record Unmapped(String file, List<Integer> lines, String reason) {
    }
}
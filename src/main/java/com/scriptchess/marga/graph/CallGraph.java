package com.scriptchess.marga.graph;

/**
 * Compact, array-based call graph.
 *
 * IDs are dense ints sorted package -> class -> method, so:
 *  - classes of package p are  [pkgClassOffsets[p],  pkgClassOffsets[p + 1])
 *  - methods of class c are    [classMethodOffsets[c], classMethodOffsets[c + 1])
 *  - callees of method m are   fwdTargets[fwdOffsets[m] .. fwdOffsets[m + 1])
 *  - callers of method m are   revSources[revOffsets[m] .. revOffsets[m + 1])
 *  - m overrides / implements  ovrTargets[ovrOffsets[m] .. ovrOffsets[m + 1])
 *  - m is overridden by        implSources[implOffsets[m] .. implOffsets[m + 1])
 *
 * Used at build time only (not written to the report):
 *  - classSourcePaths[c]  "com/acme/UserService.java", or null without debug info
 *  - classOuterMethod[c]  enclosing method id of a local/anonymous class, or -1
 *  - methodLines[m]       sorted source lines with bytecode, lambda bodies included
 */
public record CallGraph(
        String[] activeProfiles,
        String[] packages,
        int[] pkgClassOffsets,
        String[] modules,
        String[] classNames,
        int[] classModule,
        byte[] classFlags,
        int[] classMethodOffsets,
        String[] methodNames,
        String[] methodParams,
        byte[] methodFlags,
        int[] fwdOffsets,
        int[] fwdTargets,
        byte[] fwdKinds,
        int[] revOffsets,
        int[] revSources,
        byte[] revKinds,
        int[] ovrOffsets,
        int[] ovrTargets,
        int[] implOffsets,
        int[] implSources,
        int[] classEdgePairs,
        int[] classEdgeWeights,
        String[] classSourcePaths,
        int[] classOuterMethod,
        int[][] methodLines) {

    // classFlags: bits 0-2 stereotype, bits 3-4 kind, bits 5-6 bean state
    public static final int STEREO_OTHER = 0;
    public static final int STEREO_CONTROLLER = 1;
    public static final int STEREO_SERVICE = 2;
    public static final int STEREO_REPOSITORY = 3;
    public static final int STEREO_COMPONENT = 4;
    public static final int KIND_CLASS = 0;
    public static final int KIND_INTERFACE = 1;
    public static final int KIND_ABSTRACT = 2;
    public static final int BEAN_ACTIVE = 0;      // active, or not Spring-managed
    public static final int BEAN_INACTIVE = 1;    // only created by beans the active profiles switch off
    public static final int BEAN_CONDITIONAL = 2; // active, but guarded by @Conditional* Marga cannot evaluate

    // methodFlags
    public static final int M_ENTRY = 1;
    public static final int M_CONSTRUCTOR = 1 << 1;
    public static final int M_STATIC = 1 << 2;
    public static final int M_ACCESSOR = 1 << 3;
    public static final int M_ABSTRACT = 1 << 4;
    /** bits 5-7: entry-point kind, see {@link #ENTRY_KINDS} (0 = not an entry point) */
    public static final int M_ENTRY_KIND_SHIFT = 5;
    public static final String[] ENTRY_KINDS = {"", "http", "scheduled", "event", "kafka", "rabbit", "jms", "sqs"};

    // edge kinds (an edge keeps the weakest kind on its way)
    public static final byte EDGE_DIRECT = 0;   // solid: this body certainly runs
    public static final byte EDGE_LAMBDA = 1;   // dashed: called inside a lambda / method reference
    public static final byte EDGE_DISPATCH = 2; // dotted: may run, depending on the runtime receiver

    public int methodCount() {
        return methodNames.length;
    }

    public int classCount() {
        return classNames.length;
    }

    public int edgeCount() {
        return fwdTargets.length;
    }
}
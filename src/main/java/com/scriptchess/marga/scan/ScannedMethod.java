package com.scriptchess.marga.scan;

import java.util.List;
import java.util.Map;

/**
 * A method declared in a scanned class.
 * profiles:       values of a @Profile annotation on the method (used on @Bean methods).
 * instantiations: internal names of every type created with NEW inside the method.
 * lines:          sorted, distinct source lines that have bytecode (empty without debug info).
 * annotationValues: attribute values of entry-point annotations (descriptor -> attribute -> values).
 */
public record ScannedMethod(
        String name,
        String descriptor,
        int access,
        List<String> annotations,
        List<String> profiles,
        List<CallSite> calls,
        List<String> instantiations,
        int[] lines,
        Map<String, Map<String, List<String>>> annotationValues) {
}
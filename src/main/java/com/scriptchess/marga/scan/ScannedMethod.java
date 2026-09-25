package com.scriptchess.marga.scan;

import java.util.List;

/**
 * A method declared in a scanned class.
 * profiles:       values of a @Profile annotation on the method (used on @Bean methods).
 * instantiations: internal names of every type created with NEW inside the method.
 * lines:          sorted, distinct source lines that have bytecode (empty without debug info).
 */
public record ScannedMethod(
        String name,
        String descriptor,
        int access,
        List<String> annotations,
        List<String> profiles,
        List<CallSite> calls,
        List<String> instantiations,
        int[] lines) {
}
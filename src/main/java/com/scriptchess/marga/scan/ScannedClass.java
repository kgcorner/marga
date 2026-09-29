package com.scriptchess.marga.scan;

import java.util.List;
import java.util.Map;

/**
 * A class read from a module's target/classes. All type names are internal names (slashes).
 * profiles:    values of a @Profile annotation on the class.
 * sourceFile:  simple source file name, e.g. "UserService.java" (null without debug info).
 * outerMethod: for local/anonymous classes, the enclosing method as "Owner#name(desc)", else null.
 * annotationValues: attribute values of tracked annotations, e.g. a class-level @RequestMapping path.
 */
public record ScannedClass(
        String name,
        String superName,
        List<String> interfaces,
        int access,
        String module,
        List<String> annotations,
        List<String> profiles,
        String sourceFile,
        String outerMethod,
        Map<String, Map<String, List<String>>> annotationValues,
        List<ScannedMethod> methods) {
}
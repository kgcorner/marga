package com.scriptchess.marga.report;

import com.scriptchess.marga.scan.CallSite;
import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.scan.ScannedMethod;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns scanned classes into a self-contained HTML report:
 * classes become compound (parent) nodes, methods become child nodes,
 * and calls between scanned methods become edges.
 */
public final class MargaHtmlReportRenderer {

    private static final String TEMPLATE_RESOURCE = "/marga/report-template.html";
    private static final String CYTOSCAPE_RESOURCE = "/marga/cytoscape.min.js";
    private static final String JS_PLACEHOLDER = "/*__CYTOSCAPE_JS__*/";
    private static final String DATA_PLACEHOLDER = "/*__MARGA_GRAPH_JSON__*/null";

    private static final String SPRING_WEB = "Lorg/springframework/web/bind/annotation/";
    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            SPRING_WEB + "RequestMapping;",
            SPRING_WEB + "GetMapping;",
            SPRING_WEB + "PostMapping;",
            SPRING_WEB + "PutMapping;",
            SPRING_WEB + "DeleteMapping;",
            SPRING_WEB + "PatchMapping;",
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/context/event/EventListener;",
            "Lorg/springframework/kafka/annotation/KafkaListener;",
            "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
            "Lorg/springframework/jms/annotation/JmsListener;",
            "Lio/awspring/cloud/sqs/annotation/SqsListener;");

    private static final String STEREO = "Lorg/springframework/stereotype/";
    private static final String REST_CONTROLLER = SPRING_WEB + "RestController;";
    private static final String CONTROLLER = STEREO + "Controller;";
    private static final String SERVICE = STEREO + "Service;";
    private static final String REPOSITORY = STEREO + "Repository;";
    private static final String COMPONENT = STEREO + "Component;";
    private static final String CONFIGURATION = "Lorg/springframework/context/annotation/Configuration;";

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** Renders the report and returns the written file. */
    public Path render(List<ScannedClass> classes, Path outputFile) throws IOException {
        String graphJson = mapper.writeValueAsString(buildGraph(classes))
                .replace("</", "<\\/");                       // never close the <script> tag early
        String cytoscapeJs = readResource(CYTOSCAPE_RESOURCE)
                .replace("</script", "<\\/script");

        String html = readResource(TEMPLATE_RESOURCE)
                .replace(JS_PLACEHOLDER, cytoscapeJs)
                .replace(DATA_PLACEHOLDER, graphJson);

        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);
        return outputFile;
    }

    // ------------------------------------------------------------------ graph building

    private record Index(Map<String, ScannedClass> classes, Map<String, ScannedMethod> methods) {
    }

    private Map<String, Object> buildGraph(List<ScannedClass> scanned) {
        Index index = index(scanned);
        List<Map<String, Object>> elements = new ArrayList<>();
        Set<String> visibleMethodIds = new HashSet<>();

        // nodes: class = parent, method = child
        for (ScannedClass cls : index.classes().values()) {
            if (isSynthetic(cls.access())) {
                continue;
            }
            String simpleName = simpleName(cls.name());
            elements.add(element("nodes", data(
                    "id", cls.name(),
                    "type", "class",
                    "label", simpleName,
                    "fqn", cls.name().replace('/', '.'),
                    "module", cls.module(),
                    "kind", kind(cls.access()),
                    "stereotype", stereotype(cls))));

            for (ScannedMethod method : cls.methods()) {
                if (!isVisible(method)) {
                    continue;
                }
                String id = methodId(cls.name(), method.name(), method.descriptor());
                String label = methodLabel(method, simpleName);
                visibleMethodIds.add(id);
                elements.add(element("nodes", data(
                        "id", id,
                        "type", "method",
                        "parent", cls.name(),
                        "label", label,
                        "fqn", cls.name().replace('/', '.') + "#" + label,
                        "entry", isEntryPoint(method))));
            }
        }

        // edges: caller method -> callee method (only inside the scanned code base)
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (ScannedClass cls : index.classes().values()) {
            for (ScannedMethod method : cls.methods()) {
                String source = methodId(cls.name(), method.name(), method.descriptor());
                if (!visibleMethodIds.contains(source)) {
                    continue;
                }
                Set<String> targets = new LinkedHashSet<>();
                collectTargets(method, index, targets, new HashSet<>());
                for (String target : targets) {
                    if (!target.equals(source) && visibleMethodIds.contains(target)) {
                        edgeKeys.add(source + "\n" + target);
                    }
                }
            }
        }
        int edgeNo = 0;
        for (String key : edgeKeys) {
            String[] ends = key.split("\n", 2);
            elements.add(element("edges", data("id", "e" + edgeNo++, "source", ends[0], "target", ends[1])));
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("stats", data(
                "classes", elements.stream().filter(e -> "class".equals(typeOf(e))).count(),
                "methods", visibleMethodIds.size(),
                "edges", edgeKeys.size()));
        graph.put("elements", elements);
        return graph;
    }

    private static Index index(List<ScannedClass> scanned) {
        Map<String, ScannedClass> classes = new LinkedHashMap<>();
        Map<String, ScannedMethod> methods = new HashMap<>();
        for (ScannedClass cls : scanned) {
            if (classes.putIfAbsent(cls.name(), cls) != null) {
                continue; // same class in two modules: first one wins
            }
            for (ScannedMethod m : cls.methods()) {
                methods.put(methodId(cls.name(), m.name(), m.descriptor()), m);
            }
        }
        return new Index(classes, methods);
    }

    /**
     * Resolves each call to a scanned method. Synthetic targets (lambda bodies, bridge
     * methods, accessors) are not shown; their calls are attributed to the caller instead.
     */
    private static void collectTargets(ScannedMethod method, Index index,
                                       Set<String> out, Set<ScannedMethod> expanded) {
        for (CallSite call : method.calls()) {
            String target = resolve(call.owner(), call.name(), call.descriptor(), index, new HashSet<>());
            if (target == null) {
                continue; // JDK, Spring or other library code
            }
            ScannedMethod targetMethod = index.methods().get(target);
            if (isSynthetic(targetMethod.access())) {
                if (expanded.add(targetMethod)) {
                    collectTargets(targetMethod, index, out, expanded);
                }
            } else {
                out.add(target);
            }
        }
    }

    /**
     * Finds the method a call lands on, walking up superclasses and interfaces
     * (e.g. a call on a subclass to a method declared in its parent).
     */
    private static String resolve(String owner, String name, String descriptor,
                                  Index index, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }
        String id = methodId(owner, name, descriptor);
        if (index.methods().containsKey(id)) {
            return id;
        }
        ScannedClass cls = index.classes().get(owner);
        if (cls == null) {
            return null;
        }
        String found = resolve(cls.superName(), name, descriptor, index, visited);
        if (found != null) {
            return found;
        }
        for (String itf : cls.interfaces()) {
            found = resolve(itf, name, descriptor, index, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ classification

    private static boolean isVisible(ScannedMethod method) {
        return !isSynthetic(method.access())
                && (method.access() & Opcodes.ACC_BRIDGE) == 0
                && !"<clinit>".equals(method.name());
    }

    private static boolean isEntryPoint(ScannedMethod method) {
        return method.annotations().stream().anyMatch(ENTRY_POINT_ANNOTATIONS::contains);
    }

    private static String stereotype(ScannedClass cls) {
        List<String> a = cls.annotations();
        if (a.contains(REST_CONTROLLER) || a.contains(CONTROLLER)) {
            return "controller";
        }
        if (a.contains(SERVICE)) {
            return "service";
        }
        if (a.contains(REPOSITORY)
                || cls.interfaces().stream().anyMatch(i -> i.startsWith("org/springframework/data/"))) {
            return "repository";
        }
        if (a.contains(COMPONENT) || a.contains(CONFIGURATION)) {
            return "component";
        }
        return "other";
    }

    private static String kind(int access) {
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        return (access & Opcodes.ACC_ABSTRACT) != 0 ? "abstract" : "class";
    }

    private static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    // ------------------------------------------------------------------ naming

    private static String methodId(String owner, String name, String descriptor) {
        return owner + "#" + name + descriptor;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** e.g. create(User, String) or new UserService(UserRepository) */
    private static String methodLabel(ScannedMethod method, String simpleClassName) {
        String name = "<init>".equals(method.name()) ? "new " + simpleClassName : method.name();
        String args = Arrays.stream(Type.getArgumentTypes(method.descriptor()))
                .map(t -> {
                    String className = t.getClassName();
                    return className.substring(className.lastIndexOf('.') + 1);
                })
                .collect(Collectors.joining(", "));
        return name + "(" + args + ")";
    }

    // ------------------------------------------------------------------ small utils

    private static Map<String, Object> element(String group, Map<String, Object> data) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("group", group);
        element.put("data", data);
        return element;
    }

    /** Ordered map from key/value pairs; null values are dropped. */
    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Object typeOf(Map<String, Object> element) {
        return ((Map<String, Object>) element.get("data")).get("type");
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = MargaHtmlReportRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing plugin resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
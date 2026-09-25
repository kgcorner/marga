package com.scriptchess.marga.graph;

import com.scriptchess.marga.scan.CallKind;
import com.scriptchess.marga.scan.CallSite;
import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.scan.ScannedMethod;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scriptchess.marga.graph.CallGraph.*;

/**
 * Builds a {@link CallGraph} from scanned classes:
 * ID assignment, call resolution with dispatch (CHA), method hierarchy,
 * bean activity under the active Spring profiles, and CSR arrays.
 */
public final class CallGraphBuilder {

    private static final String SPRING_WEB = "Lorg/springframework/web/bind/annotation/";
    private static final String STEREO = "Lorg/springframework/stereotype/";
    private static final String CONTEXT = "Lorg/springframework/context/annotation/";

    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            SPRING_WEB + "RequestMapping;", SPRING_WEB + "GetMapping;", SPRING_WEB + "PostMapping;",
            SPRING_WEB + "PutMapping;", SPRING_WEB + "DeleteMapping;", SPRING_WEB + "PatchMapping;",
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/context/event/EventListener;",
            "Lorg/springframework/kafka/annotation/KafkaListener;",
            "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
            "Lorg/springframework/jms/annotation/JmsListener;",
            "Lio/awspring/cloud/sqs/annotation/SqsListener;");

    private static final Set<String> CONTROLLER = Set.of(SPRING_WEB + "RestController;", STEREO + "Controller;");
    private static final Set<String> SERVICE = Set.of(STEREO + "Service;");
    private static final Set<String> REPOSITORY = Set.of(STEREO + "Repository;");
    private static final Set<String> COMPONENT = Set.of(STEREO + "Component;", CONTEXT + "Configuration;");
    private static final Set<String> ANY_BEAN_STEREOTYPE = Set.of(
            SPRING_WEB + "RestController;", STEREO + "Controller;", STEREO + "Service;",
            STEREO + "Repository;", STEREO + "Component;", CONTEXT + "Configuration;");
    private static final String BEAN = CONTEXT + "Bean;";
    private static final String CONDITIONAL = CONTEXT + "Conditional;";
    private static final String BOOT_CONDITIONAL_PREFIX = "Lorg/springframework/boot/autoconfigure/condition/Conditional";

    private final Set<String> activeProfiles;

    /** Uses Spring's "default" profile. */
    public CallGraphBuilder() {
        this(Set.of("default"));
    }

    public CallGraphBuilder(Set<String> activeProfiles) {
        this.activeProfiles = activeProfiles.isEmpty() ? Set.of("default") : Set.copyOf(activeProfiles);
    }

    /**
     * Resolution index over ALL scanned classes, including synthetic ones.
     * subtypes:      direct subclasses and implementors of each scanned type
     * bridgeAliases: "Owner#name(realDesc)" -> descriptors of bridge methods delegating to it
     */
    private record Index(Map<String, ScannedClass> classes,
                         Map<String, ScannedMethod> methods,
                         Map<String, List<String>> subtypes,
                         Map<String, List<String>> bridgeAliases,
                         Map<String, List<String>> overrideCache) {
    }

    private record Csr(int[] offsets, int[] targets, byte[] kinds) {
    }

    public CallGraph build(List<ScannedClass> scanned) {
        Index index = index(scanned);
        Map<String, Integer> beanStates = beanStates(index);

        // ---- 1. visible classes, sorted package -> simple name
        List<ScannedClass> classes = index.classes().values().stream()
                .filter(c -> !isSynthetic(c.access()))
                .sorted(Comparator.comparing((ScannedClass c) -> packageOf(c.name()))
                        .thenComparing(c -> simpleName(c.name())))
                .toList();

        int classCount = classes.size();
        List<String> packages = new ArrayList<>();
        List<Integer> pkgOffsets = new ArrayList<>();
        Map<String, Integer> moduleIds = new LinkedHashMap<>();
        String[] classNames = new String[classCount];
        int[] classModule = new int[classCount];
        byte[] classFlags = new byte[classCount];
        int[] classMethodOffsets = new int[classCount + 1];

        // ---- 2. methods, sorted by name + descriptor inside each class
        Map<String, Integer> methodIds = new HashMap<>();
        List<ScannedMethod> methods = new ArrayList<>();
        List<Integer> methodClass = new ArrayList<>();

        for (int c = 0; c < classCount; c++) {
            ScannedClass cls = classes.get(c);
            String pkg = packageOf(cls.name());
            if (packages.isEmpty() || !packages.get(packages.size() - 1).equals(pkg)) {
                packages.add(pkg);
                pkgOffsets.add(c);
            }
            classNames[c] = simpleName(cls.name());
            classModule[c] = moduleIds.computeIfAbsent(String.valueOf(cls.module()), k -> moduleIds.size());
            int state = beanStates.getOrDefault(cls.name(), BEAN_ACTIVE);
            classFlags[c] = (byte) (stereotype(cls, index) | (kind(cls.access()) << 3) | (state << 5));
            classMethodOffsets[c] = methods.size();

            List<ScannedMethod> visible = cls.methods().stream()
                    .filter(CallGraphBuilder::isVisible)
                    .sorted(Comparator.comparing(ScannedMethod::name).thenComparing(ScannedMethod::descriptor))
                    .toList();
            for (ScannedMethod m : visible) {
                methodIds.put(methodKey(cls.name(), m.name(), m.descriptor()), methods.size());
                methods.add(m);
                methodClass.add(c);
            }
        }
        classMethodOffsets[classCount] = methods.size();
        pkgOffsets.add(classCount);

        int methodCount = methods.size();
        String[] methodNames = new String[methodCount];
        String[] methodParams = new String[methodCount];
        byte[] methodFlags = new byte[methodCount];
        for (int m = 0; m < methodCount; m++) {
            ScannedMethod method = methods.get(m);
            methodNames[m] = method.name();
            methodParams[m] = String.join(", ", parameterNames(method.descriptor()));
            methodFlags[m] = methodFlags(method);
        }

        // ---- 3. call edges (deduplicated per caller; the strongest kind wins)
        List<Map<Integer, Byte>> callees = new ArrayList<>(methodCount);
        for (int m = 0; m < methodCount; m++) {
            Map<Integer, Byte> targets = new LinkedHashMap<>();
            collectTargets(methods.get(m), EDGE_DIRECT, index, methodIds, targets, new HashSet<>());
            targets.remove(m);
            callees.add(targets);
        }
        Csr fwd = forward(callees);
        Csr rev = reverse(fwd, methodCount);

        // ---- 4. method hierarchy: which declarations each method overrides / implements
        List<Map<Integer, Byte>> overrides = new ArrayList<>(methodCount);
        for (int m = 0; m < methodCount; m++) {
            ScannedMethod method = methods.get(m);
            ScannedClass owner = classes.get(methodClass.get(m));
            Map<Integer, Byte> declarations = new LinkedHashMap<>();
            if (canOverride(method)) {
                List<String> descriptors = new ArrayList<>();
                descriptors.add(method.descriptor());
                descriptors.addAll(index.bridgeAliases().getOrDefault(
                        methodKey(owner.name(), method.name(), method.descriptor()), List.of()));
                for (String descriptor : descriptors) {
                    for (String key : nearestDeclarations(owner, method.name(), descriptor, index)) {
                        Integer id = methodIds.get(key);
                        if (id != null && id != m) {
                            declarations.put(id, EDGE_DIRECT);
                        }
                    }
                }
            }
            overrides.add(declarations);
        }
        Csr ovr = forward(overrides);
        Csr impl = reverse(ovr, methodCount);

        // ---- 5. source mapping for diff impact (lambda bodies count as their enclosing method)
        String[] classSourcePaths = new String[classCount];
        int[] classOuterMethod = new int[classCount];
        for (int c = 0; c < classCount; c++) {
            ScannedClass cls = classes.get(c);
            int slash = cls.name().lastIndexOf('/');
            classSourcePaths[c] = cls.sourceFile() == null ? null
                    : (slash < 0 ? "" : cls.name().substring(0, slash + 1)) + cls.sourceFile();
            Integer outer = cls.outerMethod() == null ? null : methodIds.get(cls.outerMethod());
            classOuterMethod[c] = outer == null ? -1 : outer;
        }
        int[][] methodLines = new int[methodCount][];
        for (int m = 0; m < methodCount; m++) {
            java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
            collectLines(methods.get(m), index, lines, new HashSet<>());
            methodLines[m] = lines.stream().mapToInt(Integer::intValue).toArray();
        }

        // ---- 6. class-level edges for the overview
        Map<Long, Integer> classEdges = new LinkedHashMap<>();
        for (int m = 0; m < methodCount; m++) {
            int from = methodClass.get(m);
            for (int i = fwd.offsets()[m]; i < fwd.offsets()[m + 1]; i++) {
                int to = methodClass.get(fwd.targets()[i]);
                if (from != to) {
                    classEdges.merge(((long) from << 32) | to, 1, Integer::sum);
                }
            }
        }
        int[] classEdgePairs = new int[classEdges.size() * 2];
        int[] classEdgeWeights = new int[classEdges.size()];
        int k = 0;
        for (Map.Entry<Long, Integer> ce : classEdges.entrySet()) {
            classEdgePairs[k * 2] = (int) (ce.getKey() >>> 32);
            classEdgePairs[k * 2 + 1] = (int) (long) ce.getKey();
            classEdgeWeights[k++] = ce.getValue();
        }

        return new CallGraph(
                activeProfiles.stream().sorted().toArray(String[]::new),
                packages.toArray(String[]::new),
                pkgOffsets.stream().mapToInt(Integer::intValue).toArray(),
                moduleIds.keySet().toArray(String[]::new),
                classNames, classModule, classFlags, classMethodOffsets,
                methodNames, methodParams, methodFlags,
                fwd.offsets(), fwd.targets(), fwd.kinds(),
                rev.offsets(), rev.targets(), rev.kinds(),
                ovr.offsets(), ovr.targets(), impl.offsets(), impl.targets(),
                classEdgePairs, classEdgeWeights,
                classSourcePaths, classOuterMethod, methodLines);
    }

    /** Own lines plus the lines of lambda bodies (synthetic methods) it creates, recursively. */
    private static void collectLines(ScannedMethod method, Index index, java.util.TreeSet<Integer> out, Set<String> seen) {
        for (int line : method.lines()) {
            out.add(line);
        }
        for (CallSite call : method.calls()) {
            if (call.kind() != CallKind.LAMBDA) {
                continue;
            }
            String key = methodKey(call.owner(), call.name(), call.descriptor());
            ScannedMethod target = index.methods().get(key);
            if (target != null && isSynthetic(target.access()) && seen.add(key)) {
                collectLines(target, index, out, seen);
            }
        }
    }

    // ------------------------------------------------------------------ index

    private static Index index(List<ScannedClass> scanned) {
        Map<String, ScannedClass> classes = new LinkedHashMap<>();
        Map<String, ScannedMethod> methods = new HashMap<>();
        for (ScannedClass cls : scanned) {
            if (classes.putIfAbsent(cls.name(), cls) != null) {
                continue; // same class in two modules: first one wins
            }
            for (ScannedMethod m : cls.methods()) {
                methods.put(methodKey(cls.name(), m.name(), m.descriptor()), m);
            }
        }
        Map<String, List<String>> subtypes = new HashMap<>();
        Map<String, List<String>> bridgeAliases = new HashMap<>();
        for (ScannedClass cls : classes.values()) {
            for (String supertype : supertypes(cls)) {
                subtypes.computeIfAbsent(supertype, k -> new ArrayList<>()).add(cls.name());
            }
            for (ScannedMethod m : cls.methods()) {
                if ((m.access() & Opcodes.ACC_BRIDGE) == 0) {
                    continue;
                }
                // a bridge like save(Object) delegates to the real save(User) on the same class
                for (CallSite call : m.calls()) {
                    if (call.name().equals(m.name()) && call.owner().equals(cls.name())) {
                        bridgeAliases.computeIfAbsent(methodKey(cls.name(), m.name(), call.descriptor()),
                                k -> new ArrayList<>()).add(m.descriptor());
                        break;
                    }
                }
            }
        }
        return new Index(classes, methods, subtypes, bridgeAliases, new HashMap<>());
    }

    // ------------------------------------------------------------------ calls

    /**
     * Collects the call targets of a method.
     * <ul>
     *   <li>Static, private, constructor and super calls: solid edge to the declared method.</li>
     *   <li>Virtual/interface calls with no override in the subtypes: solid edge to the declared
     *       method (this includes interface methods with no implementation, e.g. Spring Data).</li>
     *   <li>Abstract declaration with implementations: dotted edges to each implementation,
     *       none to the abstract method itself.</li>
     *   <li>Concrete declaration overridden in some subtypes: dotted edges to the declaration
     *       and to each override (none of them is certain).</li>
     *   <li>Synthetic targets (lambda bodies, bridge methods) are folded into the caller.</li>
     * </ul>
     */
    private static void collectTargets(ScannedMethod method, byte inheritedKind, Index index,
                                       Map<String, Integer> methodIds, Map<Integer, Byte> out,
                                       Set<String> expanded) {
        for (CallSite call : method.calls()) {
            String declaredKey = resolve(call.owner(), call.name(), call.descriptor(), index, new HashSet<>());
            if (declaredKey == null) {
                continue; // JDK, Spring or other library code
            }
            ScannedMethod declared = index.methods().get(declaredKey);
            byte kind = (byte) Math.max(inheritedKind, call.kind() == CallKind.LAMBDA ? EDGE_LAMBDA : EDGE_DIRECT);

            List<String> overrides = isDispatched(call, declared)
                    ? overrides(call.owner(), call.name(), call.descriptor(), index)
                    : List.of();
            if (overrides.isEmpty()) {
                addTarget(declaredKey, kind, index, methodIds, out, expanded);
                continue;
            }
            byte dispatch = (byte) Math.max(kind, EDGE_DISPATCH);
            if ((declared.access() & Opcodes.ACC_ABSTRACT) == 0) {
                addTarget(declaredKey, dispatch, index, methodIds, out, expanded);
            }
            for (String override : overrides) {
                addTarget(override, dispatch, index, methodIds, out, expanded);
            }
        }
    }

    private static void addTarget(String key, byte kind, Index index, Map<String, Integer> methodIds,
                                  Map<Integer, Byte> out, Set<String> expanded) {
        ScannedMethod target = index.methods().get(key);
        if (isSynthetic(target.access())) {
            if (expanded.add(key)) {
                collectTargets(target, kind, index, methodIds, out, expanded);
            }
            return;
        }
        Integer id = methodIds.get(key);
        if (id != null) {
            out.merge(id, kind, (a, b) -> (byte) Math.min(a, b));
        }
    }

    /** Only virtual/interface calls (and method references) can land on an override. */
    private static boolean isDispatched(CallSite call, ScannedMethod declared) {
        boolean virtualKind = call.kind() == CallKind.VIRTUAL
                || call.kind() == CallKind.INTERFACE
                || call.kind() == CallKind.LAMBDA;
        return virtualKind && canOverride(declared);
    }

    /**
     * Every non-abstract declaration of name+descriptor in the subtypes of {@code owner}.
     * Only scanned owners are expanded: a call on Runnable or Function would otherwise
     * link to every implementation in the code base.
     */
    private static List<String> overrides(String owner, String name, String descriptor, Index index) {
        if (!index.classes().containsKey(owner)) {
            return List.of();
        }
        String cacheKey = methodKey(owner, name, descriptor);
        List<String> cached = index.overrideCache().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<String> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>(index.subtypes().getOrDefault(owner, List.of()));
        for (int i = 0; i < queue.size(); i++) {
            String type = queue.get(i);
            if (!visited.add(type)) {
                continue;
            }
            String key = methodKey(type, name, descriptor);
            ScannedMethod m = index.methods().get(key);
            if (m != null && (m.access() & Opcodes.ACC_ABSTRACT) == 0) {
                found.add(key);
            }
            queue.addAll(index.subtypes().getOrDefault(type, List.of()));
        }
        List<String> result = List.copyOf(found);
        index.overrideCache().put(cacheKey, result);
        return result;
    }

    /** Walks superclasses and interfaces to find where a called method is declared. */
    private static String resolve(String owner, String name, String descriptor, Index index, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }
        String key = methodKey(owner, name, descriptor);
        if (index.methods().containsKey(key)) {
            return key;
        }
        ScannedClass cls = index.classes().get(owner);
        if (cls == null) {
            return null;
        }
        for (String supertype : supertypes(cls)) {
            String found = resolve(supertype, name, descriptor, index, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ hierarchy

    /** Closest declarations of name+descriptor in each supertype branch of {@code cls}. */
    private static List<String> nearestDeclarations(ScannedClass cls, String name, String descriptor, Index index) {
        List<String> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String supertype : supertypes(cls)) {
            findDeclaration(supertype, name, descriptor, index, visited, found);
        }
        return found;
    }

    private static void findDeclaration(String type, String name, String descriptor, Index index,
                                        Set<String> visited, List<String> found) {
        if (!visited.add(type)) {
            return;
        }
        ScannedClass cls = index.classes().get(type);
        if (cls == null) {
            return;
        }
        String key = methodKey(type, name, descriptor);
        ScannedMethod declared = index.methods().get(key);
        if (declared != null && isVisible(declared) && canOverride(declared)) {
            found.add(key);
            return;
        }
        for (String supertype : supertypes(cls)) {
            findDeclaration(supertype, name, descriptor, index, visited, found);
        }
    }

    private static List<String> supertypes(ScannedClass cls) {
        List<String> result = new ArrayList<>(cls.interfaces().size() + 1);
        if (cls.superName() != null) {
            result.add(cls.superName());
        }
        result.addAll(cls.interfaces());
        return result;
    }

    // ------------------------------------------------------------------ beans & profiles

    /**
     * A class is INACTIVE when every place that creates it is switched off by the active
     * profiles: its own component annotation, @Bean methods, or any other `new` site.
     * Classes with no creation site at all may be created by frameworks, so they stay ACTIVE.
     */
    private Map<String, Integer> beanStates(Index index) {
        Map<String, int[]> sources = new HashMap<>(); // [active, activeConditional, inactive]
        for (ScannedClass cls : index.classes().values()) {
            if (isSynthetic(cls.access())) {
                continue;
            }
            boolean classActive = ProfileExpression.anyMatches(
                    effectiveProfiles(cls.annotations(), cls.profiles(), index, new HashSet<>()), activeProfiles);
            boolean classConditional = isConditional(cls.annotations(), index, new HashSet<>());
            boolean concrete = (cls.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0;
            if (concrete && hasAnnotation(cls.annotations(), ANY_BEAN_STEREOTYPE, index, new HashSet<>())) {
                addSource(sources, cls.name(), classActive, classConditional);
            }
            for (ScannedMethod m : cls.methods()) {
                boolean beanMethod = m.annotations().contains(BEAN);
                boolean active = classActive && (!beanMethod || ProfileExpression.anyMatches(
                        effectiveProfiles(m.annotations(), m.profiles(), index, new HashSet<>()), activeProfiles));
                boolean conditional = classConditional
                        || (beanMethod && isConditional(m.annotations(), index, new HashSet<>()));
                for (String type : m.instantiations()) {
                    addSource(sources, type, active, conditional);
                }
            }
        }
        Map<String, Integer> states = new HashMap<>();
        sources.forEach((type, s) -> states.put(type,
                s[0] > 0 ? BEAN_ACTIVE : s[1] > 0 ? BEAN_CONDITIONAL : BEAN_INACTIVE));
        return states;
    }

    private static void addSource(Map<String, int[]> sources, String type, boolean active, boolean conditional) {
        int[] s = sources.computeIfAbsent(type, k -> new int[3]);
        s[!active ? 2 : conditional ? 1 : 0]++;
    }

    /** @Profile values on the element plus those of custom annotations meta-annotated with @Profile. */
    private static List<String> effectiveProfiles(List<String> annotations, List<String> own, Index index, Set<String> visited) {
        List<String> profiles = new ArrayList<>(own);
        for (String descriptor : annotations) {
            ScannedClass annotation = scannedAnnotation(descriptor, index);
            if (annotation != null && visited.add(annotation.name())) {
                profiles.addAll(effectiveProfiles(annotation.annotations(), annotation.profiles(), index, visited));
            }
        }
        return profiles;
    }

    private static boolean isConditional(List<String> annotations, Index index, Set<String> visited) {
        for (String descriptor : annotations) {
            if (descriptor.equals(CONDITIONAL) || descriptor.startsWith(BOOT_CONDITIONAL_PREFIX)) {
                return true;
            }
            ScannedClass annotation = scannedAnnotation(descriptor, index);
            if (annotation != null && visited.add(annotation.name())
                    && isConditional(annotation.annotations(), index, visited)) {
                return true;
            }
        }
        return false;
    }

    /** Direct match, or through custom annotations in the scanned code (meta-annotations). */
    private static boolean hasAnnotation(List<String> annotations, Set<String> wanted, Index index, Set<String> visited) {
        for (String descriptor : annotations) {
            if (wanted.contains(descriptor)) {
                return true;
            }
            ScannedClass annotation = scannedAnnotation(descriptor, index);
            if (annotation != null && visited.add(annotation.name())
                    && hasAnnotation(annotation.annotations(), wanted, index, visited)) {
                return true;
            }
        }
        return false;
    }

    private static ScannedClass scannedAnnotation(String descriptor, Index index) {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            return null;
        }
        ScannedClass cls = index.classes().get(descriptor.substring(1, descriptor.length() - 1));
        return cls != null && (cls.access() & Opcodes.ACC_ANNOTATION) != 0 ? cls : null;
    }

    // ------------------------------------------------------------------ classification

    private static boolean isVisible(ScannedMethod m) {
        return !isSynthetic(m.access())
                && (m.access() & Opcodes.ACC_BRIDGE) == 0
                && !"<clinit>".equals(m.name());
    }

    private static boolean canOverride(ScannedMethod m) {
        return (m.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !"<init>".equals(m.name());
    }

    private static byte methodFlags(ScannedMethod m) {
        int flags = 0;
        if (m.annotations().stream().anyMatch(ENTRY_POINT_ANNOTATIONS::contains)) {
            flags |= M_ENTRY;
        }
        if ("<init>".equals(m.name())) {
            flags |= M_CONSTRUCTOR;
        }
        if ((m.access() & Opcodes.ACC_STATIC) != 0) {
            flags |= M_STATIC;
        }
        if (isAccessor(m.name(), m.descriptor())) {
            flags |= M_ACCESSOR;
        }
        if ((m.access() & Opcodes.ACC_ABSTRACT) != 0) {
            flags |= M_ABSTRACT;
        }
        return (byte) flags;
    }

    /** getX() / isX() with no args returning a value, or setX(v) returning void. */
    private static boolean isAccessor(String name, String descriptor) {
        int args = parameterNames(descriptor).size();
        boolean returnsVoid = descriptor.endsWith(")V");
        if (args == 0 && !returnsVoid) {
            return hasPropertySuffix(name, "get") || hasPropertySuffix(name, "is");
        }
        return args == 1 && returnsVoid && hasPropertySuffix(name, "set");
    }

    private static boolean hasPropertySuffix(String name, String prefix) {
        return name.length() > prefix.length() && name.startsWith(prefix)
                && Character.isUpperCase(name.charAt(prefix.length()));
    }

    private static int stereotype(ScannedClass cls, Index index) {
        List<String> a = cls.annotations();
        if (hasAnnotation(a, CONTROLLER, index, new HashSet<>())) {
            return STEREO_CONTROLLER;
        }
        if (hasAnnotation(a, SERVICE, index, new HashSet<>())) {
            return STEREO_SERVICE;
        }
        if (hasAnnotation(a, REPOSITORY, index, new HashSet<>())
                || cls.interfaces().stream().anyMatch(i -> i.startsWith("org/springframework/data/"))) {
            return STEREO_REPOSITORY;
        }
        if (hasAnnotation(a, COMPONENT, index, new HashSet<>())) {
            return STEREO_COMPONENT;
        }
        return STEREO_OTHER;
    }

    private static int kind(int access) {
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return KIND_INTERFACE;
        }
        return (access & Opcodes.ACC_ABSTRACT) != 0 ? KIND_ABSTRACT : KIND_CLASS;
    }

    private static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    // ------------------------------------------------------------------ csr

    private static Csr forward(List<Map<Integer, Byte>> adjacency) {
        int n = adjacency.size();
        int total = adjacency.stream().mapToInt(Map::size).sum();
        int[] offsets = new int[n + 1];
        int[] targets = new int[total];
        byte[] kinds = new byte[total];
        int e = 0;
        for (int m = 0; m < n; m++) {
            offsets[m] = e;
            for (Map.Entry<Integer, Byte> t : adjacency.get(m).entrySet()) {
                targets[e] = t.getKey();
                kinds[e++] = t.getValue();
            }
        }
        offsets[n] = e;
        return new Csr(offsets, targets, kinds);
    }

    private static Csr reverse(Csr fwd, int n) {
        int[] offsets = new int[n + 1];
        for (int target : fwd.targets()) {
            offsets[target + 1]++;
        }
        for (int m = 0; m < n; m++) {
            offsets[m + 1] += offsets[m];
        }
        int[] cursor = offsets.clone();
        int[] sources = new int[fwd.targets().length];
        byte[] kinds = new byte[fwd.targets().length];
        for (int m = 0; m < n; m++) {
            for (int i = fwd.offsets()[m]; i < fwd.offsets()[m + 1]; i++) {
                int slot = cursor[fwd.targets()[i]]++;
                sources[slot] = m;
                kinds[slot] = fwd.kinds()[i];
            }
        }
        return new Csr(offsets, sources, kinds);
    }

    // ------------------------------------------------------------------ naming

    private static String methodKey(String owner, String name, String descriptor) {
        return owner + '#' + name + descriptor;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** "(Ljava/lang/String;[IJ)V" -> [String, int[], long] */
    static List<String> parameterNames(String descriptor) {
        List<String> names = new ArrayList<>();
        int i = 1; // skip '('
        while (descriptor.charAt(i) != ')') {
            int dims = 0;
            while (descriptor.charAt(i) == '[') {
                dims++;
                i++;
            }
            String type;
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                String internal = descriptor.substring(i + 1, end);
                type = internal.substring(internal.lastIndexOf('/') + 1);
                i = end + 1;
            } else {
                type = switch (c) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> throw new IllegalArgumentException("Bad descriptor: " + descriptor);
                };
                i++;
            }
            names.add(type + "[]".repeat(dims));
        }
        return names;
    }
}
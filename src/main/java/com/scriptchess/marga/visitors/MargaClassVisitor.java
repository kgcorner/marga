package com.scriptchess.marga.visitors;

import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.scan.ScannedMethod;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects class-level structure: hierarchy, annotations, @Profile values, source file and methods. */
public final class MargaClassVisitor extends ClassVisitor {

    private final String module;
    private final List<String> annotations = new ArrayList<>();
    private final List<String> profiles = new ArrayList<>();
    private final List<ScannedMethod> methods = new ArrayList<>();
    private final Map<String, Map<String, List<String>>> annotationValues = new LinkedHashMap<>();

    private String name;
    private String superName;
    private List<String> interfaces = List.of();
    private int access;
    private String sourceFile;
    private String outerMethod;

    public MargaClassVisitor(String module) {
        super(Opcodes.ASM9);
        this.module = module;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.access = access;
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.sourceFile = source;
    }

    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
        if (name != null) {
            this.outerMethod = owner + '#' + name + descriptor; // local or anonymous class
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        annotations.add(descriptor);
        if (ProfileValuesVisitor.PROFILE.equals(descriptor)) {
            return new ProfileValuesVisitor(profiles);
        }
        return AnnotationValuesVisitor.TRACKED.contains(descriptor)
                ? new AnnotationValuesVisitor(annotationValues.computeIfAbsent(descriptor, k -> new LinkedHashMap<>()))
                : null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        return new MargaMethodVisitor(name, descriptor, access, methods);
    }

    public ScannedClass result() {
        return new ScannedClass(name, superName, interfaces, access, module,
                List.copyOf(annotations), List.copyOf(profiles), sourceFile, outerMethod, annotationValues, List.copyOf(methods));
    }
}
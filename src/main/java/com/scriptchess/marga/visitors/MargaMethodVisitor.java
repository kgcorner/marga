package com.scriptchess.marga.visitors;

import com.scriptchess.marga.scan.CallKind;
import com.scriptchess.marga.scan.CallSite;
import com.scriptchess.marga.scan.ScannedMethod;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Records method annotations, @Profile values, every outgoing call (including lambda /
 * method-reference targets), every type instantiated with NEW, and the source lines that
 * have bytecode. Adds the finished {@link ScannedMethod} to the sink in visitEnd().
 */
public final class MargaMethodVisitor extends MethodVisitor {

    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private final String name;
    private final String descriptor;
    private final int access;
    private final List<ScannedMethod> sink;

    private final List<String> annotations = new ArrayList<>();
    private final List<String> profiles = new ArrayList<>();
    private final List<CallSite> calls = new ArrayList<>();
    private final List<String> instantiations = new ArrayList<>();
    private final TreeSet<Integer> lines = new TreeSet<>();
    private int currentLine = -1;

    public MargaMethodVisitor(String name, String descriptor, int access, List<ScannedMethod> sink) {
        super(Opcodes.ASM9);
        this.name = name;
        this.descriptor = descriptor;
        this.access = access;
        this.sink = sink;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        annotations.add(descriptor);
        return ProfileValuesVisitor.PROFILE.equals(descriptor) ? new ProfileValuesVisitor(profiles) : null;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        currentLine = line;
        lines.add(line);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (opcode == Opcodes.NEW && !instantiations.contains(type)) {
            instantiations.add(type);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
        if (owner.startsWith("[")) {
            return; // array pseudo-methods like int[].clone()
        }
        calls.add(new CallSite(CallKind.fromOpcode(opcode), owner, name, descriptor, currentLine));
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                       Object... bootstrapArgs) {
        // Lambdas and method refs: arg[1] is the real implementation method.
        if (LAMBDA_FACTORY.equals(bootstrap.getOwner())
                && bootstrapArgs.length > 1
                && bootstrapArgs[1] instanceof Handle impl) {
            calls.add(new CallSite(CallKind.LAMBDA, impl.getOwner(), impl.getName(),
                    impl.getDesc(), currentLine));
        }
    }

    @Override
    public void visitEnd() {
        sink.add(new ScannedMethod(name, descriptor, access, List.copyOf(annotations), List.copyOf(profiles),
                List.copyOf(calls), List.copyOf(instantiations),
                lines.stream().mapToInt(Integer::intValue).toArray()));
    }
}
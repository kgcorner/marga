package com.scriptchess.marga.visitors;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/** Reads the String[] value of a @Profile annotation into a list. */
final class ProfileValuesVisitor extends AnnotationVisitor {

    static final String PROFILE = "Lorg/springframework/context/annotation/Profile;";

    private final List<String> profiles;

    ProfileValuesVisitor(List<String> profiles) {
        super(Opcodes.ASM9);
        this.profiles = profiles;
    }

    @Override
    public void visit(String name, Object value) {
        if (value instanceof String s) {
            profiles.add(s); // array element (name == null) or single value
        }
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        return "value".equals(name) ? this : null;
    }
}
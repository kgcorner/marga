package com.scriptchess.marga.scan;

import org.objectweb.asm.Opcodes;

/** How a method call is dispatched in bytecode. LAMBDA = target of a lambda / method reference. */
public enum CallKind {
    VIRTUAL, STATIC, SPECIAL, INTERFACE, LAMBDA;

    public static CallKind fromOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> VIRTUAL;
            case Opcodes.INVOKESTATIC -> STATIC;
            case Opcodes.INVOKESPECIAL -> SPECIAL;
            case Opcodes.INVOKEINTERFACE -> INTERFACE;
            default -> throw new IllegalArgumentException("Unexpected invoke opcode: " + opcode);
        };
    }
}
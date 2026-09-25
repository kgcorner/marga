package com.scriptchess.marga.scan;

/**
 * One outgoing call from a method.
 * owner is the internal class name (e.g. com/acme/UserRepository), as written in bytecode.
 * line is -1 when the class was compiled without debug info.
 */
public record CallSite(CallKind kind, String owner, String name, String descriptor, int line) {
}
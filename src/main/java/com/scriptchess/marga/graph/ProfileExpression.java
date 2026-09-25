package com.scriptchess.marga.graph;

import java.util.Collection;
import java.util.Set;

/**
 * Evaluates Spring @Profile expressions against a set of active profiles.
 * Supports names, !, &amp;, | and parentheses, e.g. "prod", "!prod", "dev &amp; (eu | us)".
 * A @Profile with several values matches when any of them matches.
 */
final class ProfileExpression {

    private final String input;
    private final Set<String> active;
    private int pos;

    private ProfileExpression(String input, Set<String> active) {
        this.input = input;
        this.active = active;
    }

    /** True when there are no expressions, or when any expression matches. */
    static boolean anyMatches(Collection<String> expressions, Set<String> active) {
        if (expressions.isEmpty()) {
            return true;
        }
        for (String expression : expressions) {
            if (matches(expression, active)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String expression, Set<String> active) {
        ProfileExpression parser = new ProfileExpression(expression, active);
        boolean result = parser.or();
        parser.skipSpaces();
        if (parser.pos != expression.length()) {
            throw new IllegalArgumentException("Invalid @Profile expression: " + expression);
        }
        return result;
    }

    private boolean or() {
        boolean result = and();
        while (consume('|')) {
            result |= and(); // evaluate both sides: parser must advance
        }
        return result;
    }

    private boolean and() {
        boolean result = not();
        while (consume('&')) {
            result &= not();
        }
        return result;
    }

    private boolean not() {
        if (consume('!')) {
            return !not();
        }
        if (consume('(')) {
            boolean result = or();
            if (!consume(')')) {
                throw new IllegalArgumentException("Missing ')' in @Profile expression: " + input);
            }
            return result;
        }
        return active.contains(name());
    }

    private String name() {
        skipSpaces();
        int start = pos;
        while (pos < input.length() && "!&|() ".indexOf(input.charAt(pos)) < 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("Missing profile name in @Profile expression: " + input);
        }
        return input.substring(start, pos);
    }

    private boolean consume(char c) {
        skipSpaces();
        if (pos < input.length() && input.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (pos < input.length() && input.charAt(pos) == ' ') {
            pos++;
        }
    }
}
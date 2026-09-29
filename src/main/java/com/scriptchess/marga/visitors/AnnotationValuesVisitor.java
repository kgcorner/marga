package com.scriptchess.marga.visitors;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captures the attribute values of entry-point annotations (paths, HTTP methods, topics,
 * schedules) as strings: attribute name -> values. Enum values keep their constant name.
 */
final class AnnotationValuesVisitor extends AnnotationVisitor {

    private static final String WEB = "Lorg/springframework/web/bind/annotation/";

    /** Annotations whose values Marga reads; everything else is only recorded by descriptor. */
    static final Set<String> TRACKED = Set.of(
            WEB + "RequestMapping;", WEB + "GetMapping;", WEB + "PostMapping;", WEB + "PutMapping;",
            WEB + "DeleteMapping;", WEB + "PatchMapping;",
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/kafka/annotation/KafkaListener;",
            "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
            "Lorg/springframework/jms/annotation/JmsListener;",
            "Lio/awspring/cloud/sqs/annotation/SqsListener;");

    private final Map<String, List<String>> values;
    private final String arrayName; // non-null when visiting the elements of one array attribute

    AnnotationValuesVisitor(Map<String, List<String>> values) {
        this(values, null);
    }

    private AnnotationValuesVisitor(Map<String, List<String>> values, String arrayName) {
        super(Opcodes.ASM9);
        this.values = values;
        this.arrayName = arrayName;
    }

    @Override
    public void visit(String name, Object value) {
        String key = arrayName != null ? arrayName : name;
        if (value.getClass().isArray()) { // primitive arrays arrive in one call
            for (int i = 0; i < Array.getLength(value); i++) {
                add(key, String.valueOf(Array.get(value, i)));
            }
        } else {
            add(key, String.valueOf(value));
        }
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
        add(arrayName != null ? arrayName : name, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        values.computeIfAbsent(name, k -> new ArrayList<>());
        return new AnnotationValuesVisitor(values, name);
    }

    private void add(String name, String value) {
        values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
}
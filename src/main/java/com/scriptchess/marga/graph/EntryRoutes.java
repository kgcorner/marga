package com.scriptchess.marga.graph;

import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.scan.ScannedMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Describes what triggers an entry point, from its annotation values:
 * "GET /api/books/{id}", "topics: orders", "cron: 0 0 * * * *", "on UserCreatedEvent".
 * Property placeholders such as ${app.topic} are kept as written.
 */
final class EntryRoutes {

    private static final String WEB = "Lorg/springframework/web/bind/annotation/";
    private static final Map<String, String> VERBS = Map.of(
            WEB + "GetMapping;", "GET", WEB + "PostMapping;", "POST", WEB + "PutMapping;", "PUT",
            WEB + "DeleteMapping;", "DELETE", WEB + "PatchMapping;", "PATCH", WEB + "RequestMapping;", "");

    private EntryRoutes() {
    }

    static String describe(ScannedClass cls, ScannedMethod method, int kind) {
        return switch (kind) {
            case 1 -> http(cls, method);
            case 2 -> schedule(values(method, "Lorg/springframework/scheduling/annotation/Scheduled;"));
            case 3 -> {
                List<String> params = CallGraphBuilder.parameterNames(method.descriptor());
                yield params.isEmpty() ? null : "on " + params.get(0);
            }
            case 4 -> listen("topics", values(method, "Lorg/springframework/kafka/annotation/KafkaListener;"),
                    "topics", "topicPattern");
            case 5 -> listen("queues", values(method, "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;"),
                    "queues");
            case 6 -> listen("destination", values(method, "Lorg/springframework/jms/annotation/JmsListener;"),
                    "destination");
            case 7 -> listen("queue", values(method, "Lio/awspring/cloud/sqs/annotation/SqsListener;"),
                    "value", "queueNames");
            default -> null;
        };
    }

    private static String http(ScannedClass cls, ScannedMethod method) {
        for (Map.Entry<String, String> verb : VERBS.entrySet()) {
            Map<String, List<String>> v = method.annotationValues().get(verb.getKey());
            if (v == null) {
                continue;
            }
            String verbs = verb.getValue().isEmpty()
                    ? String.join("|", v.getOrDefault("method", List.of("ANY")))
                    : verb.getValue();
            List<String> prefixes = paths(cls.annotationValues().get(WEB + "RequestMapping;"));
            List<String> paths = new ArrayList<>();
            for (String prefix : prefixes) {
                for (String path : paths(v)) {
                    paths.add(join(prefix, path));
                }
            }
            return verbs + " " + String.join(", ", paths);
        }
        return null;
    }

    private static List<String> paths(Map<String, List<String>> values) {
        if (values == null) {
            return List.of("");
        }
        List<String> paths = new ArrayList<>(values.getOrDefault("value", List.of()));
        paths.addAll(values.getOrDefault("path", List.of()));
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static String join(String prefix, String path) {
        String joined = ("/" + prefix + "/" + path).replaceAll("/{2,}", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String schedule(Map<String, List<String>> v) {
        if (v == null) {
            return null;
        }
        String cron = first(v, "cron");
        if (cron != null && !cron.isEmpty()) {
            return "cron: " + cron;
        }
        for (String attr : List.of("fixedRate", "fixedRateString", "fixedDelay", "fixedDelayString")) {
            String value = first(v, attr);
            if (value != null && !value.isEmpty() && !value.equals("-1")) {
                String unit = first(v, "timeUnit");
                return (attr.startsWith("fixedRate") ? "every " : "fixed delay ") + value + " "
                        + (unit == null ? "ms" : unit.toLowerCase());
            }
        }
        return null;
    }

    private static String listen(String label, Map<String, List<String>> v, String... attributes) {
        if (v == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String attr : attributes) {
            names.addAll(v.getOrDefault(attr, List.of()));
        }
        return names.isEmpty() ? null : label + ": " + String.join(", ", names);
    }

    private static Map<String, List<String>> values(ScannedMethod method, String descriptor) {
        return method.annotationValues().get(descriptor);
    }

    private static String first(Map<String, List<String>> v, String attr) {
        List<String> values = v.get(attr);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
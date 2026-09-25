package com.scriptchess.marga.scanner;

import com.scriptchess.marga.scan.ScannedClass;
import com.scriptchess.marga.visitors.MargaClassVisitor;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;



public class MargaClassFileScanner {
    private final Log log;

    public MargaClassFileScanner(Log log) {
        this.log = log;
    }

    public List<ScannedClass> scan(Path classesDir, String module) throws IOException {
        if (!Files.isDirectory(classesDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(classesDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(MargaClassFileScanner::isScannableClass)
                    .map(file -> scanFile(file, module))
                    .flatMap(Optional::stream)
                    .toList();
        }
    }

    private Optional<ScannedClass> scanFile(Path file, String module) {
        try {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            MargaClassVisitor visitor = new MargaClassVisitor(module);
            // keep debug info for line numbers, skip stack-map frames (not needed)
            reader.accept(visitor, ClassReader.SKIP_FRAMES);
            return Optional.of(visitor.result());
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers class files newer than ASM supports
            log.warn("Marga: skipping unreadable class " + file + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    private static boolean isScannableClass(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".class")
                && !fileName.equals("module-info.class")
                && !fileName.equals("package-info.class");
    }

}

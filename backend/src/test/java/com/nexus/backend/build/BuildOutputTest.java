package com.nexus.backend.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the build output against classes this build did not produce.
 *
 * <p>This checkout is opened in an IDE that compiles into {@code target/classes}.
 * Maven then treats those files as up to date and runs the suite against them, which
 * twice produced {@code Flexible Constructor Bodies is only available with source
 * level 25} from inside {@code JwtAuthenticationFilter.<init>} — or, worse, could
 * silently run an outdated class, so a failing test would describe behaviour the
 * source no longer has.
 *
 * <p>Two things are asserted, because the incident has two shapes. A class compiled
 * at another source level cannot be loaded by the JDK the build targets; and an IDE
 * that compiled a file with errors writes an <em>error stub</em>, which keeps the
 * right class-file version and fails only when something instantiates it. The stub
 * observed here was major version 65, so the level check alone would have passed it.
 * The stub is recognised by the marker Eclipse's compiler embeds for its
 * {@code java.lang.Error}.
 */
class BuildOutputTest {

    /** Every Java class file starts with 0xCAFEBABE: magic, minor, major. */
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    /** Class-file major version for Java 1.1; each feature release adds one. */
    private static final int FEATURE_TO_MAJOR_OFFSET = 44;

    private static final int HEADER_LENGTH = 8;

    private static final Pattern DECLARED_SOURCE_LEVEL =
        Pattern.compile("<java\\.version>\\s*(\\d+)");

    /**
     * Eclipse's error-stub message, matched without its trailing plural/singular.
     *
     * <p>Assembled at runtime on purpose: a constant would be folded into this
     * class's own constant pool, and the check would then flag the test that
     * performs it.
     */
    private static final String IDE_ERROR_STUB_MARKER =
        String.join(" ", "Unresolved", "compilation", "problem");

    @Test
    void everyClassIsCompiledAtTheDeclaredSourceLevel() throws IOException {
        int declaredFeature = declaredSourceLevel();
        int expectedMajor = declaredFeature + FEATURE_TO_MAJOR_OFFSET;

        List<String> offenders = new ArrayList<>();
        for (Path file : compiledClasses()) {
            int major = majorVersion(file);
            if (major != expectedMajor) {
                offenders.add("%s is major version %d (Java %d)".formatted(
                    display(file), major, major - FEATURE_TO_MAJOR_OFFSET));
            }
        }

        assertThat(offenders)
            .withFailMessage("""
                %d class file(s) in target/ were not compiled for Java %d (major %d):
                  %s
                That is stale IDE output, not this build's. Delete backend/target/classes and \
                backend/target/test-classes, then rebuild with Maven.""",
                offenders.size(), declaredFeature, expectedMajor, String.join("\n  ", offenders))
            .isEmpty();
    }

    @Test
    void noClassIsAnIdeErrorStub() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : compiledClasses()) {
            if (isIdeErrorStub(file)) {
                offenders.add(display(file));
            }
        }

        assertThat(offenders)
            .withFailMessage("""
                %d class file(s) in target/ are Eclipse error stubs, not compiled code:
                  %s
                The IDE compiled a source file that had errors and left a class that throws \
                java.lang.Error on instantiation. Delete backend/target/classes and \
                backend/target/test-classes, fix what the IDE reports, then rebuild with Maven.""",
                offenders.size(), String.join("\n  ", offenders))
            .isEmpty();
    }

    /**
     * Every class file under the module's two output directories.
     *
     * <p>An empty list would make both checks pass by doing nothing, which is the
     * failure mode they exist to prevent, so it is treated as a broken assumption:
     * surefire runs from the module directory, where {@code target/classes} exists.
     */
    private static List<Path> compiledClasses() throws IOException {
        List<Path> classes = new ArrayList<>();
        for (Path root : List.of(Path.of("target", "classes"), Path.of("target", "test-classes"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                classes.addAll(files
                    .filter(file -> file.getFileName().toString().endsWith(".class"))
                    .toList());
            }
        }
        assertThat(classes)
            .withFailMessage("no compiled classes found under %s — this check expects Maven's module directory as the working directory",
                Path.of("").toAbsolutePath())
            .isNotEmpty();
        return classes;
    }

    /**
     * The level from this module's {@code <java.version>}, which the parent's compiler
     * configuration turns into {@code --release}. Read rather than hardcoded so that
     * bumping the project's Java version does not leave this check failing every build,
     * and read from the pom rather than the running JVM so that building with a newer
     * JDK — which still emits the declared level — is not mistaken for stale output.
     */
    private static int declaredSourceLevel() throws IOException {
        Path pom = Path.of("pom.xml");
        Matcher declared = DECLARED_SOURCE_LEVEL.matcher(Files.readString(pom));
        if (!declared.find()) {
            throw new IOException("no <java.version> in " + pom.toAbsolutePath()
                + "; this check cannot tell which level the build targets");
        }
        return Integer.parseInt(declared.group(1));
    }

    /** Reads the major version from the class-file header: magic, minor, major. */
    private static int majorVersion(Path file) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(header, 0, HEADER_LENGTH) != HEADER_LENGTH) {
                throw new IOException("truncated class file: " + file);
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        if (buffer.getInt() != CLASS_FILE_MAGIC) {
            throw new IOException("not a class file: " + file);
        }
        buffer.getShort(); // minor version
        return buffer.getShort() & 0xFFFF;
    }

    /**
     * True when the compiler left its error message in the constant pool, which is
     * where Eclipse keeps it for the {@code java.lang.Error} the stub throws.
     *
     * <p>Latin-1 maps bytes to characters one-to-one, so this searches the raw class
     * file and not a decoded interpretation of it.
     */
    private static boolean isIdeErrorStub(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        return content.contains(IDE_ERROR_STUB_MARKER);
    }

    private static String display(Path file) {
        return Path.of("target").relativize(file).toString();
    }
}

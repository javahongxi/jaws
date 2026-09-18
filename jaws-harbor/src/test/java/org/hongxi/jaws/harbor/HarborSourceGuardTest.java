package org.hongxi.jaws.harbor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guards on the layering that makes a native client viable in
 * this module instead of a separate artifact. Each rule is a scan over
 * {@code src/main/java}, because the coupling they forbid is exactly the kind
 * the compiler allows: same-module classes are always reachable.
 * <ul>
 *   <li>{@code model/} is the reusable contract, so it stays free of transport
 *       and server types — that is what lets a client build the same DTOs the
 *       server parses.</li>
 *   <li>A wire token is derived from a DTO class name, never written out, so
 *       the two ends of the protocol cannot drift apart.</li>
 *   <li>A {@code client/} package may not reach into server internals; today it
 *       does not exist at all, so this rule starts biting the moment it lands.</li>
 * </ul>
 *
 * @author shenhongxi
 */
class HarborSourceGuardTest {

    /** The single place allowed to spell out a wire name by hand. */
    private static final String PROTOCOL_SOURCE = "HarborProtocol.java";

    private static final Pattern WIRE_TOKEN_LITERAL =
            Pattern.compile("\"[A-Z][A-Za-z0-9]*(?:Request|Response)\"");

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\s*;");

    private static final List<String> MODEL_ALLOWED_PREFIXES = List.of(
            "java.",
            "org.hongxi.jaws.harbor.model.");

    private static final List<String> CLIENT_ALLOWED_PREFIXES = List.of(
            "java.",
            "com.alibaba.fastjson2.",
            "com.google.protobuf.",
            "org.hongxi.jaws.common.",
            "org.hongxi.jaws.exception.",
            "org.hongxi.jaws.harbor.HarborProtocol",
            "org.hongxi.jaws.harbor.model.",
            "org.hongxi.jaws.harbor.proto.",
            "org.hongxi.jaws.rpc.",
            "org.hongxi.jaws.stream.",
            "org.hongxi.jaws.transport.StreamSubject",
            "org.hongxi.jaws.wire.",
            "org.slf4j.");

    @Test
    void contractTypesStayFreeOfTransportAndServerTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourcesUnder(harborRoot().resolve("model"))) {
            for (String imported : importsOf(file)) {
                if (!startsWithAny(imported, MODEL_ALLOWED_PREFIXES)) {
                    violations.add(relative(file) + " imports " + imported);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "model/ is the contract a client must be able to reuse as-is:" + newline()
                        + String.join(newline(), violations));
    }

    @Test
    void wireTokensAreDerivedFromDtoClassesNotWrittenOut() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourcesUnder(harborRoot())) {
            if (PROTOCOL_SOURCE.equals(file.getFileName().toString())) {
                continue;
            }
            Matcher matcher = WIRE_TOKEN_LITERAL.matcher(codeOf(file));
            while (matcher.find()) {
                violations.add(relative(file) + " hard-codes " + matcher.group());
            }
        }
        assertTrue(violations.isEmpty(),
                "HarborProtocol.typeToken(dto.getClass()) is the only source of a"
                        + " metadata.type value:" + newline()
                        + String.join(newline(), violations));
    }

    @Test
    void clientCodeMayNotReachIntoServerInternals() throws IOException {
        Path clientRoot = harborRoot().resolve("client");
        if (!Files.isDirectory(clientRoot)) {
            // Vacuously green until the first client class lands, which is the
            // point: the boundary must exist before the code that could cross it.
            return;
        }

        List<String> violations = new ArrayList<>();
        for (Path file : sourcesUnder(clientRoot)) {
            for (String imported : importsOf(file)) {
                if (!startsWithAny(imported, CLIENT_ALLOWED_PREFIXES)) {
                    violations.add(relative(file) + " imports " + imported);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "a client speaks the protocol, it does not read the server's state:"
                        + newline() + String.join(newline(), violations));
    }

    // ========================================================================
    // Scanning helpers
    // ========================================================================

    private static Path harborRoot() {
        String base = System.getProperty("user.dir");
        Path fromModule = Path.of(base, "src", "main", "java",
                "org", "hongxi", "jaws", "harbor");
        Path fromRepoRoot = Path.of(base, "jaws-harbor", "src", "main", "java",
                "org", "hongxi", "jaws", "harbor");
        Path root = Files.isDirectory(fromModule) ? fromModule : fromRepoRoot;
        assertTrue(Files.isDirectory(root),
                "cannot locate jaws-harbor sources from " + root);
        return root;
    }

    private static List<Path> sourcesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        List<String> imports = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher matcher = IMPORT_LINE.matcher(line.trim());
            if (matcher.find()) {
                imports.add(matcher.group(1));
            }
        }
        return imports;
    }

    /**
     * Comments are where a wire name legitimately belongs as prose, so strip
     * them before hunting for literals.
     */
    private static String codeOf(Path file) {
        try {
            String source = Files.readString(file);
            return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)//.*$", " ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        return prefixes.stream().anyMatch(value::startsWith);
    }

    private static String relative(Path file) {
        return harborRoot().relativize(file).toString();
    }

    private static String newline() {
        return System.lineSeparator();
    }
}

package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The entities express the domain rules without depending on HTTP (AGENTS.md, section 4; ADR-0020):
 * no class under an {@code entity} package imports Spring's HTTP types or the web layer of {@code
 * shared} (#131, test case T12 of #142).
 */
class DomainWithoutHttpTest {

    private static final Path SOURCES = Path.of("src/main/java/es/uma/urbanpulse");

    @Test
    void noEntityImportsTheWebLayer() throws IOException {
        List<String> offending;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            offending =
                    files.filter(file -> file.getParent().getFileName().toString().equals("entity"))
                            .filter(file -> file.toString().endsWith(".java"))
                            .filter(DomainWithoutHttpTest::importsTheWebLayer)
                            .map(Path::toString)
                            .toList();
        }

        assertThat(offending).isEmpty();
    }

    private static boolean importsTheWebLayer(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(
                            line ->
                                    line.startsWith("import org.springframework.http")
                                            || line.startsWith(
                                                    "import es.uma.urbanpulse.shared.web"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}

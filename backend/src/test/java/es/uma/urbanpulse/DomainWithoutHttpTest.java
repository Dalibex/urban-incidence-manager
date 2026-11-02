package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test of #142, T12: the domain does not depend on HTTP (ADR-0020, AGENTS.md section 4).
 * No class in an {@code entity} package of any module imports Spring's HTTP types or the web layer
 * of {@code shared.web}; {@code ApiExceptionHandler} is the only place that maps domain errors to
 * HTTP (#131). It reads the sources, so it runs as a unit test from {@code backend/}.
 */
class DomainWithoutHttpTest {

    private static final Path SOURCES = Path.of("src/main/java/es/uma/urbanpulse");
    private static final List<String> FORBIDDEN =
            List.of("import org.springframework.http", "import es.uma.urbanpulse.shared.web");

    @Test
    void noEntityImportsHttpOrTheWebLayer() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            offenders =
                    files.filter(file -> file.getParent().getFileName().toString().equals("entity"))
                            .filter(file -> file.toString().endsWith(".java"))
                            .filter(DomainWithoutHttpTest::importsForbidden)
                            .map(file -> SOURCES.relativize(file).toString())
                            .toList();
        }

        assertThat(offenders).as("entity classes that import HTTP or shared.web").isEmpty();
    }

    @Test
    void readsTheEntityPackages() throws IOException {
        // Guards the test above: from another working directory it would find nothing and pass.
        try (Stream<Path> files = Files.walk(SOURCES)) {
            assertThat(files.filter(file -> file.toString().contains("entity")).count())
                    .isGreaterThan(10);
        }
    }

    private static boolean importsForbidden(Path file) {
        try {
            var source = Files.readString(file);
            return FORBIDDEN.stream().anyMatch(source::contains);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}

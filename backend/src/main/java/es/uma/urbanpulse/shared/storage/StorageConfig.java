package es.uma.urbanpulse.shared.storage;

import jakarta.servlet.MultipartConfigElement;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * The {@link StorageService} of the application, the local folder of {@code STORAGE_BASE_PATH}, and
 * the limits of the evidence policy (ADR-0013).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceProperties.class)
class StorageConfig {

    // Room for the multipart boundaries and headers around the file.
    private static final DataSize MULTIPART_OVERHEAD = DataSize.ofKilobytes(64);

    @Bean
    StorageService storageService(@Value("${urbanpulse.storage.base-path}") Path basePath) {
        return new LocalFsStorage(basePath);
    }

    /**
     * The server refuses an upload over {@code EVIDENCE_MAX_FILE_SIZE} while reading it, before it
     * is held in memory; {@code ApiExceptionHandler} answers it with {@code 413}. Replaces Spring
     * Boot's default of 1 MB.
     */
    @Bean
    MultipartConfigElement multipartConfigElement(EvidenceProperties evidence) {
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(evidence.maxFileSize());
        factory.setMaxRequestSize(
                DataSize.ofBytes(evidence.maxFileSize().toBytes() + MULTIPART_OVERHEAD.toBytes()));
        return factory.createMultipartConfig();
    }
}

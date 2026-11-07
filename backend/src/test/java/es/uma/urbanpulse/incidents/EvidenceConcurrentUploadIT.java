package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.shared.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T13 of #67 when the two uploads of the same content arrive at the same time (#192): both pass the
 * lookup of a retry, and the unique index of {@code V13} must keep only one photo.
 *
 * <p>The race is made deterministic: while this request is storing its file, the "other request"
 * commits its row with the same content. Unlike {@code EvidenceApiIT}, this class does not run in a
 * transaction, because the index can only refuse a row that another transaction has committed; the
 * rows are deleted after the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class EvidenceConcurrentUploadIT {

    private static final byte[] JPEG = jpeg();

    @TempDir static Path storageFolder;

    @DynamicPropertySource
    static void storeFilesInATemporaryFolder(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.storage.base-path", () -> storageFolder.toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @MockitoSpyBean private StorageService storage;

    private UUID citizen;
    private UUID incident;

    @BeforeEach
    void createCitizenAndIncident() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test', ?, '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(Instant.parse("2026-11-02T09:00:00Z")),
                        Timestamp.from(Instant.parse("2026-11-02T09:00:00Z")));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        jdbc.update("DELETE FROM attachment WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @Test
    void theSecondOfTwoSimultaneousUploadsAnswersWithTheFirstPhoto() throws Exception {
        List<String> storedKeys = new ArrayList<>();
        UUID[] winner = new UUID[1];
        doAnswer(
                        invocation -> {
                            String key = (String) invocation.callRealMethod();
                            storedKeys.add(key);
                            winner[0] = otherRequestCommitsTheSameContent();
                            return key;
                        })
                .when(storage)
                .put(any(byte[].class));

        var file = new MockMultipartFile("file", "street-light.jpg", "image/jpeg", JPEG);
        mvc.perform(
                        multipart("/api/v1/incidents/" + incident + "/attachments")
                                .file(file)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(citizen, Role.CITIZEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(winner[0].toString()));

        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM attachment WHERE incident_id = ?",
                        Integer.class,
                        incident);
        assertThat(rows).isEqualTo(1);
        assertThat(storedKeys).hasSize(1);
        assertThat(storage.exists(storedKeys.getFirst()))
                .as("the file of the upload that lost is removed")
                .isFalse();
    }

    private UUID otherRequestCommitsTheSameContent() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JPEG));
        return jdbc.queryForObject(
                "INSERT INTO attachment (incident_id, uploader_id, file_name, file_type, file_size,"
                        + " storage_key, content_hash, uploaded_at)"
                        + " VALUES (?, ?, 'IMG_0001.jpg', 'image/jpeg', ?, ?, ?, now()) RETURNING id",
                UUID.class,
                incident,
                citizen,
                JPEG.length,
                UUID.randomUUID().toString(),
                sha256);
    }

    private static byte[] jpeg() {
        byte[] rest = "a broken street light".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[4 + rest.length];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        System.arraycopy(rest, 0, bytes, 4, rest.length);
        return bytes;
    }
}

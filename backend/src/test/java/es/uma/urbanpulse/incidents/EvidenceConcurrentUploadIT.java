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
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two uploads to the same incident at the same time (#192, #275): both pass the first checks before
 * either has stored its file. The row of the photo is written after locking the incident and
 * repeating the checks, so the upload that loses the race is answered as a retry of the same
 * content (T13 of #67), goes over the limit of photos, or comes after the operator validated the
 * incident; either way its stored file is removed.
 *
 * <p>The race is made deterministic: while this request is storing its file, the "other request"
 * commits its change. Unlike {@code EvidenceApiIT}, this class does not run in a transaction,
 * because the second check can only see a change that another transaction has committed; the rows
 * are deleted after the test.
 */
@SpringBootTest(
        properties =
                "urbanpulse.storage.evidence.max-files-per-incident="
                        + EvidenceConcurrentUploadIT.MAX_FILES)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class EvidenceConcurrentUploadIT {

    static final int MAX_FILES = 2;

    private static final byte[] JPEG = jpeg("a broken street light");
    private static final byte[] OTHER_JPEG = jpeg("the same street light from the corner");

    @TempDir static Path storageFolder;

    @DynamicPropertySource
    static void storeFilesInATemporaryFolder(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.storage.base-path", () -> storageFolder.toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private TransactionTemplate transactions;
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
        UUID[] winner = new UUID[1];
        List<String> storedKeys = whileStoring(() -> winner[0] = otherRequestCommits(JPEG));

        upload(JPEG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(winner[0].toString()));

        assertThat(photos()).isEqualTo(1);
        assertThatTheFileOfTheLoserIsRemoved(storedKeys);
    }

    // #275 B: two different photos race for the last place.
    @Test
    void ofTwoDifferentPhotosForTheLastPlaceOnlyOneIsSaved() throws Exception {
        for (int i = 1; i < MAX_FILES; i++) {
            otherRequestCommits(jpeg("photo number " + i));
        }
        List<String> storedKeys = whileStoring(() -> otherRequestCommits(OTHER_JPEG));

        upload(JPEG)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'file')]").exists());

        assertThat(photos()).isEqualTo(MAX_FILES);
        assertThatTheFileOfTheLoserIsRemoved(storedKeys);
    }

    // #275 B: the operator validates the incident while the citizen's photo is being stored.
    @Test
    void aPhotoThatArrivesAfterTheValidationIsRefused() throws Exception {
        List<String> storedKeys =
                whileStoring(
                        () ->
                                jdbc.update(
                                        "UPDATE incident SET status = 'VALIDATED' WHERE id = ?",
                                        incident));

        upload(JPEG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_ALLOWED"));

        assertThat(photos()).isZero();
        assertThatTheFileOfTheLoserIsRemoved(storedKeys);
    }

    // #275 B: the other upload holds the incident while it writes its row, so this one must wait
    // for it before counting. The side transaction plays that upload and commits only once this
    // one is waiting for it.
    @Test
    void anUploadWaitsForTheOneThatIsWritingItsRow() throws Exception {
        for (int i = 1; i < MAX_FILES; i++) {
            otherRequestCommits(jpeg("photo number " + i));
        }
        List<String> storedKeys = whileStoring(() -> {});
        CountDownLatch rowWritten = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> otherUpload =
                    threads.submit(
                            () ->
                                    transactions.executeWithoutResult(
                                            transaction -> {
                                                jdbc.queryForObject(
                                                        "SELECT id FROM incident WHERE id = ?"
                                                                + " FOR UPDATE",
                                                        UUID.class,
                                                        incident);
                                                otherRequestCommits(OTHER_JPEG);
                                                rowWritten.countDown();
                                                awaitUninterruptibly(commit);
                                            }));
            assertThat(rowWritten.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResultActions> thisUpload = threads.submit(() -> upload(JPEG));
            waitUntilAQueryWaitsForALock();
            commit.countDown();

            otherUpload.get(10, TimeUnit.SECONDS);
            thisUpload
                    .get(10, TimeUnit.SECONDS)
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(photos()).isEqualTo(MAX_FILES);
        assertThatTheFileOfTheLoserIsRemoved(storedKeys);
    }

    /** What the other request commits while this one stores its file. */
    @FunctionalInterface
    private interface OtherRequest {
        void commit() throws Exception;
    }

    /** Runs the other request once this one has stored its file; returns the keys it stored. */
    private List<String> whileStoring(OtherRequest otherRequest) {
        List<String> storedKeys = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            String key = (String) invocation.callRealMethod();
                            storedKeys.add(key);
                            otherRequest.commit();
                            return key;
                        })
                .when(storage)
                .put(any(byte[].class));
        return storedKeys;
    }

    private ResultActions upload(byte[] content) throws Exception {
        var file = new MockMultipartFile("file", "street-light.jpg", "image/jpeg", content);
        return mvc.perform(
                multipart("/api/v1/incidents/" + incident + "/attachments")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(citizen, Role.CITIZEN)));
    }

    private void assertThatTheFileOfTheLoserIsRemoved(List<String> storedKeys) {
        assertThat(storedKeys).hasSize(1);
        assertThat(storage.exists(storedKeys.getFirst()))
                .as("the file of the upload that lost is removed")
                .isFalse();
    }

    private int photos() {
        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM attachment WHERE incident_id = ?",
                        Integer.class,
                        incident);
        return rows == null ? 0 : rows;
    }

    /** Gives up after 5 seconds, so an upload that never waits makes the test fail, not hang. */
    private void waitUntilAQueryWaitsForALock() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                Integer.class)
                        == 0) {
            Thread.sleep(20);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes the row of a photo with this content, as another request would; it commits with the
     * transaction it runs in, or at once outside one.
     */
    private UUID otherRequestCommits(byte[] content) {
        String sha256 = HexFormat.of().formatHex(sha256(content));
        return jdbc.queryForObject(
                "INSERT INTO attachment (incident_id, uploader_id, file_name, file_type, file_size,"
                        + " storage_key, content_hash, uploaded_at)"
                        + " VALUES (?, ?, 'IMG_0001.jpg', 'image/jpeg', ?, ?, ?, now()) RETURNING id",
                UUID.class,
                incident,
                citizen,
                content.length,
                UUID.randomUUID().toString(),
                sha256);
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM provides SHA-256", e);
        }
    }

    private static byte[] jpeg(String text) {
        byte[] rest = text.getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[4 + rest.length];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        System.arraycopy(rest, 0, bytes, 4, rest.length);
        return bytes;
    }
}

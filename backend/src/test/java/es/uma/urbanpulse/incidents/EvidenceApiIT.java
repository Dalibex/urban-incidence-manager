package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.shared.storage.StorageException;
import es.uma.urbanpulse.shared.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #67 for the evidence endpoints of #192: {@code listIncidentAttachments},
 * {@code createIncidentAttachment} and {@code getAttachmentContent} (ADR-0013). They cover T4 to
 * T7, T9 to T13 and T17; T8 is the contract, checked on every response by {@link ApiContract}. T1
 * (a restart) is manual; its storage side is in {@code LocalFsStorageTest}. {@link
 * PhotosUploadedByStaff} covers #284: a citizen only lists and downloads the photos they uploaded.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}) and ADR-0013,
 * not from the implementation: users and incidents are inserted with SQL, every check of behaviour
 * goes through HTTP and the stored rows are read back with SQL. The files go to a temporary folder,
 * and the {@link StorageService} is a spy, so a test can make it fail (T12) or check that nothing
 * was written.
 *
 * <p>The limits are small so the tests stay small: 1 KB per file and 2 files per incident.
 *
 * <p>Each test runs in a transaction that is rolled back, so the tests never see each other's data.
 */
@SpringBootTest(
        properties = {
            "urbanpulse.storage.evidence.max-file-size=1KB",
            "urbanpulse.storage.evidence.max-files-per-incident=2"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class EvidenceApiIT {

    private static final String API = "/api/v1";
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-11-03T09:00:00Z");

    private static final byte[] JPEG = image(0xFF, 0xD8, 0xFF, 0xE0);
    private static final byte[] PNG = image(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\n".getBytes(StandardCharsets.US_ASCII);
    // A JPEG header followed by 2 KB: over the 1 KB limit of this class.
    private static final byte[] OVERSIZED_JPEG = withHeader(JPEG, new byte[2048]);

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
    private UUID otherCitizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID analyst;
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN);
        otherCitizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES ('Street lighting') RETURNING id",
                        UUID.class);
    }

    // T4
    @Test
    void reporterUploadsAJpegAndGetsItsMetadataAndWhereToReadIt() throws Exception {
        UUID incident = incident(citizen, "REPORTED");

        ResultActions result =
                upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg")
                        .andExpect(status().isCreated())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.fileName").value("street-light.jpg"))
                        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                        .andExpect(jsonPath("$.size").value(JPEG.length))
                        .andExpect(jsonPath("$.uploadedAt").isNotEmpty());
        UUID id = idOf(result);

        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(API + "/attachments/" + id + "/content");
        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT incident_id, uploader_id, file_name, file_type, file_size,"
                                + " content_hash FROM attachment WHERE id = ?",
                        id);
        assertThat(stored.get("incident_id")).isEqualTo(incident);
        assertThat(stored.get("uploader_id")).isEqualTo(citizen);
        assertThat(stored.get("file_name")).isEqualTo("street-light.jpg");
        assertThat(stored.get("file_type")).isEqualTo("image/jpeg");
        assertThat(stored.get("file_size")).isEqualTo((long) JPEG.length);
        assertThat((String) stored.get("content_hash")).hasSize(64);
    }

    // T4: the reporter, operators, administrators and the assigned technician read the photo,
    // with the type detected when it was uploaded.
    @Test
    void everyoneWhoMaySeeTheIncidentDownloadsThePhoto() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID id = idOf(upload(citizen, incident, PNG, "photo.png", "image/png"));
        assign(incident, technician, true);

        for (UUID caller : new UUID[] {citizen, operator, administrator, technician}) {
            byte[] downloaded =
                    mvc.perform(as(caller, get(API + "/attachments/" + id + "/content")))
                            .andExpect(status().isOk())
                            .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                            .andReturn()
                            .getResponse()
                            .getContentAsByteArray();
            assertThat(downloaded).as("photo read by %s", roleOf(caller)).isEqualTo(PNG);
        }
    }

    // T4: the list holds the metadata of the photos of the incident, oldest first.
    @Test
    void listHoldsThePhotosOfTheIncidentOldestFirst() throws Exception {
        UUID incident = incident(citizen, "VALIDATED");
        UUID tuesday = storedAttachment(incident, "second.png", "image/png", TUESDAY);
        UUID monday = storedAttachment(incident, "first.jpg", "image/jpeg", MONDAY);
        storedAttachment(incident(otherCitizen, "REPORTED"), "other.jpg", "image/jpeg", MONDAY);

        String body =
                mvc.perform(as(citizen, get(attachmentsOf(incident))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].fileName").value("first.jpg"))
                        .andExpect(jsonPath("$[1].contentType").value("image/png"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<String> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).containsExactly(monday.toString(), tuesday.toString());
    }

    // T4: staff upload whatever the status; only citizens are limited to REPORTED (T11).
    @Test
    void staffUploadWhateverTheStatusAndAreRecordedAsTheUploader() throws Exception {
        UUID validated = incident(citizen, "VALIDATED");
        UUID inProgress = incident(citizen, "IN_PROGRESS");
        assign(inProgress, technician, true);

        UUID byOperator = idOf(upload(operator, validated, JPEG, "a.jpg", "image/jpeg"));
        UUID byTechnician = idOf(upload(technician, inProgress, PNG, "b.png", "image/png"));

        assertThat(uploaderOf(byOperator)).isEqualTo(operator);
        assertThat(uploaderOf(byTechnician)).isEqualTo(technician);
    }

    // T5
    @Test
    void fileOverTheSizeLimitIsRefusedWith413AndNothingIsStored() throws Exception {
        UUID incident = incident(citizen, "REPORTED");

        upload(citizen, incident, OVERSIZED_JPEG, "big.jpg", "image/jpeg")
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        assertNothingStored(incident);
    }

    // T5: the type comes from the content, never from the name or the declared Content-Type.
    @Test
    void pdfRenamedToJpgIsRefusedAndNothingIsStored() throws Exception {
        UUID incident = incident(citizen, "REPORTED");

        expectInvalidFile(upload(citizen, incident, PDF, "street-light.jpg", "image/jpeg"));

        assertNothingStored(incident);
    }

    // #285: PostgreSQL cannot store U+0000 in the file name, so it used to fail with a 500.
    @Test
    void aFileNameWithTheNullCharacterIsRefusedAndNothingIsStored() throws Exception {
        UUID incident = incident(citizen, "REPORTED");

        expectInvalidFile(upload(citizen, incident, JPEG, "street\0light.jpg", "image/jpeg"));

        assertNothingStored(incident);
    }

    // T5: 2 files per incident in this class.
    @Test
    void aFileOverTheLimitOfFilesPerIncidentIsRefused() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        idOf(upload(citizen, incident, JPEG, "1.jpg", "image/jpeg"));
        idOf(upload(citizen, incident, PNG, "2.png", "image/png"));

        expectInvalidFile(upload(citizen, incident, image(0xFF, 0xD8, 0xFF, 0xE1), "3.jpg", null));

        assertThat(attachmentRowsOf(incident)).isEqualTo(2);
        verify(storage, times(2)).put(any(byte[].class));
    }

    // T6: another citizen gets the same 404 as for an incident that does not exist.
    @Test
    void anotherCitizenCannotListDownloadOrUpload() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID id = idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));

        expectNotFound(mvc.perform(as(otherCitizen, get(attachmentsOf(incident)))));
        expectNotFound(mvc.perform(as(otherCitizen, get(API + "/attachments/" + id + "/content"))));
        expectNotFound(upload(otherCitizen, incident, PNG, "mine.png", "image/png"));

        assertThat(attachmentRowsOf(incident)).isEqualTo(1);
    }

    // T7: neither the storage key nor a public path leaves the server.
    @Test
    void noResponseHoldsTheStorageKeyOrAPublicPath() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        ResultActions created = upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg");
        UUID id = idOf(created);
        String storageKey =
                jdbc.queryForObject(
                        "SELECT storage_key FROM attachment WHERE id = ?", String.class, id);

        String uploadBody = created.andReturn().getResponse().getContentAsString();
        String location = created.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        String listBody =
                mvc.perform(as(citizen, get(attachmentsOf(incident))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Map<String, Object> metadata = JsonPath.read(uploadBody, "$");
        assertThat(metadata)
                .containsOnlyKeys("id", "fileName", "contentType", "size", "uploadedAt");
        for (String exposed : new String[] {uploadBody, listBody, location}) {
            assertThat(exposed).doesNotContain(storageKey).doesNotContain(storageFolder.toString());
        }
    }

    // T9
    @Test
    void analystIsForbiddenOnTheThreeOperations() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID id = idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));

        expectForbidden(mvc.perform(as(analyst, get(attachmentsOf(incident)))));
        expectForbidden(mvc.perform(as(analyst, get(API + "/attachments/" + id + "/content"))));
        expectForbidden(upload(analyst, incident, PNG, "photo.png", "image/png"));

        assertThat(attachmentRowsOf(incident)).isEqualTo(1);
    }

    // T10: a technician without an assignment and the one whose assignment ended.
    @Test
    void technicianNotActivelyAssignedGetsNotFound() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID id = idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));
        jdbc.update("UPDATE incident SET status = 'ASSIGNED' WHERE id = ?", incident);
        assign(incident, technician, false);

        for (UUID caller : new UUID[] {technician, otherTechnician}) {
            expectNotFound(mvc.perform(as(caller, get(attachmentsOf(incident)))));
            expectNotFound(mvc.perform(as(caller, get(API + "/attachments/" + id + "/content"))));
            expectNotFound(upload(caller, incident, PNG, "photo.png", "image/png"));
        }
        assertThat(attachmentRowsOf(incident)).isEqualTo(1);
    }

    // T11
    @Test
    void reporterCannotUploadOnceTheIncidentIsNoLongerReported() throws Exception {
        for (String status : new String[] {"VALIDATED", "IN_PROGRESS", "RESOLVED"}) {
            UUID incident = incident(citizen, status);

            upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg")
                    .andExpect(status().isConflict())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_ALLOWED"));

            assertNothingStored(incident);
        }
    }

    // T12: the storage is down while uploading. The photo is stored before its row is written
    // (EvidenceContent comes first, so a retry is detected without touching the disk), so a
    // storage failure must leave no row at all.
    @Test
    void storageFailureAnswers500AndLeavesTheIncidentAsItWas() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        Map<String, Object> before =
                jdbc.queryForMap("SELECT status, updated_at FROM incident WHERE id = ?", incident);
        doThrow(new StorageException("The disk is full")).when(storage).put(any(byte[].class));

        upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg")
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(not(containsString("disk"))));

        verify(storage).put(any(byte[].class));
        assertThat(attachmentRowsOf(incident)).isZero();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status, updated_at FROM incident WHERE id = ?", incident))
                .isEqualTo(before);
    }

    // T13: a retry with the same content stores nothing and answers with the first attachment,
    // whatever the file name.
    @Test
    void sameContentTwiceOnTheSameIncidentIsOneAttachment() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID first = idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));

        upload(citizen, incident, JPEG.clone(), "IMG_0001.jpg", "image/jpeg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.toString()))
                .andExpect(jsonPath("$.fileName").value("street-light.jpg"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        assertThat(attachmentRowsOf(incident)).isEqualTo(1);
        verify(storage, times(1)).put(any(byte[].class));
    }

    // T13: the retry is detected per incident; the same photo on another incident is new.
    @Test
    void sameContentOnAnotherIncidentIsANewAttachment() throws Exception {
        UUID first = incident(citizen, "REPORTED");
        UUID second = incident(citizen, "REPORTED");

        UUID onFirst = idOf(upload(citizen, first, JPEG, "street-light.jpg", "image/jpeg"));
        UUID onSecond = idOf(upload(citizen, second, JPEG, "street-light.jpg", "image/jpeg"));

        assertThat(onSecond).isNotEqualTo(onFirst);
    }

    // T5: a file a browser could run is refused even when it says it is an image. An SVG and an
    // HTML page would execute their scripts if they were ever served back.
    @Test
    void activeContentDeclaredAsAnImageIsRefused() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        byte[] svg =
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] html =
                "<!DOCTYPE html><html><script>alert(1)</script></html>"
                        .getBytes(StandardCharsets.UTF_8);

        expectInvalidFile(upload(citizen, incident, svg, "street-light.svg", "image/svg+xml"));
        expectInvalidFile(upload(citizen, incident, svg, "street-light.png", "image/png"));
        expectInvalidFile(upload(citizen, incident, html, "street-light.jpg", "image/jpeg"));

        assertNothingStored(incident);
    }

    // T7: the name of the file is only shown, never used as a path (ADR-0013). A name that climbs
    // out of the folder still ends up as an opaque key inside the storage folder.
    @Test
    void aFileNameWithAPathNeverChoosesWhereThePhotoIsStored() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        Path outside = storageFolder.getParent().resolve("outside.jpg");

        UUID unixName = idOf(upload(citizen, incident, JPEG, "../outside.jpg", "image/jpeg"));
        UUID windowsName =
                idOf(
                        upload(
                                citizen,
                                incident,
                                image(0xFF, 0xD8, 0xFF, 0xE1),
                                "..\\outside.jpg",
                                "image/jpeg"));

        for (UUID id : List.of(unixName, windowsName)) {
            String key =
                    jdbc.queryForObject(
                            "SELECT storage_key FROM attachment WHERE id = ?", String.class, id);
            assertThat(key).doesNotContain("..").doesNotContain("/").doesNotContain("\\");
            assertThat(storageFolder.resolve(key)).isRegularFile();
        }
        assertThat(outside).doesNotExist();
    }

    // T4 and T7: a private photo is served as the type detected on upload, the browser may not
    // guess another one, and no shared cache keeps a copy.
    @Test
    void aDownloadedPhotoIsNeitherSniffedNorCachedPublicly() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        UUID id = idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));

        mvc.perform(as(citizen, get(API + "/attachments/" + id + "/content")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(
                        header().string(HttpHeaders.CACHE_CONTROL, not(containsString("public"))));
    }

    // T17: an incident or an attachment that does not exist.
    @Test
    void unknownIncidentOrAttachmentIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        expectNotFound(mvc.perform(as(operator, get(attachmentsOf(unknown)))));
        expectNotFound(
                mvc.perform(as(operator, get(API + "/attachments/" + unknown + "/content"))));
        expectNotFound(upload(operator, unknown, JPEG, "street-light.jpg", "image/jpeg"));
        verify(storage, never()).put(any(byte[].class));
    }

    // T17: no token.
    @Test
    void withoutATokenTheThreeOperationsAreUnauthorized() throws Exception {
        UUID incident = incident(citizen, "REPORTED");
        var file = new MockMultipartFile("file", "street-light.jpg", "image/jpeg", JPEG);

        for (RequestBuilder request :
                List.of(
                        get(attachmentsOf(incident)),
                        get(API + "/attachments/" + UUID.randomUUID() + "/content"),
                        multipart(attachmentsOf(incident)).file(file))) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }
        assertNothingStored(incident);
    }

    // #284: a citizen only sees the photos they uploaded themselves; staff see every photo.
    @Nested
    @DisplayName("Photos of an incident uploaded by staff (#284)")
    class PhotosUploadedByStaff {

        @Nested
        @DisplayName("when an operator adds a photo to a reported incident")
        class WhenAnOperatorAddsAPhoto {

            private UUID incident;
            private UUID byReporter;
            private UUID byOperator;

            @BeforeEach
            void reporterAndOperatorUploadOnePhotoEach() throws Exception {
                incident = incident(citizen, "REPORTED");
                byReporter =
                        idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));
                byOperator = idOf(upload(operator, incident, PNG, "inspection.png", "image/png"));
            }

            @Test
            @DisplayName("the reporter's list holds only the photo they uploaded")
            void reporterListsTheirPhotoButNotTheOperators() throws Exception {
                // Act
                ResultActions result = mvc.perform(as(citizen, get(attachmentsOf(incident))));

                // Assert
                assertThat(listedIds(result)).containsExactly(byReporter.toString());
            }

            @Test
            @DisplayName(
                    "the reporter gets for the operator's photo the same 404 as for an unknown id")
            void reporterGetsTheSameNotFoundForTheOperatorsPhotoAsForAnUnknownOne()
                    throws Exception {
                // Arrange
                UUID unknown = UUID.randomUUID();
                Map<String, Object> unknownProblem =
                        problemOf(
                                mvc.perform(
                                        as(
                                                citizen,
                                                get(API + "/attachments/" + unknown + "/content"))),
                                unknown);

                // Act
                ResultActions result =
                        mvc.perform(
                                as(citizen, get(API + "/attachments/" + byOperator + "/content")));

                // Assert
                assertThat(problemOf(result, byOperator)).isEqualTo(unknownProblem);
            }

            @Test
            @DisplayName("the reporter still downloads the photo they uploaded")
            void reporterStillDownloadsTheirOwnPhoto() throws Exception {
                // Act
                ResultActions result =
                        mvc.perform(
                                as(citizen, get(API + "/attachments/" + byReporter + "/content")));

                // Assert
                assertThat(
                                result.andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsByteArray())
                        .isEqualTo(JPEG);
            }

            // #279
            @Test
            @DisplayName(
                    "the thumbnail in the reporter's listing is their photo, and they download it")
            void reporterDownloadsTheThumbnailOfTheirListing() throws Exception {
                // Arrange
                String listing =
                        mvc.perform(as(citizen, get(API + "/incidents")))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                List<String> thumbnails =
                        JsonPath.read(
                                listing,
                                "$.content[?(@.id == '" + incident + "')].thumbnailAttachmentId");
                assertThat(thumbnails).containsExactly(byReporter.toString());

                // Act
                ResultActions result =
                        mvc.perform(
                                as(
                                        citizen,
                                        get(
                                                API
                                                        + "/attachments/"
                                                        + thumbnails.get(0)
                                                        + "/content")));

                // Assert
                assertThat(
                                result.andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsByteArray())
                        .isEqualTo(JPEG);
            }

            // Accepted limit of #284 (docs/security.md): the retry check is per incident, not per
            // uploader, so a citizen who already holds the same bytes gets the staff's attachment.
            @Test
            @DisplayName(
                    "accepted limit: the reporter uploading the operator's exact bytes gets the"
                            + " operator's photo back as a retry, as they already hold that"
                            + " content")
            void reporterUploadingTheOperatorsExactBytesGetsTheOperatorsPhotoAsARetry()
                    throws Exception {
                // Act
                ResultActions result =
                        upload(citizen, incident, PNG.clone(), "copy.png", "image/png");

                // Assert
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(byOperator.toString()));
                assertThat(attachmentRowsOf(incident)).isEqualTo(2);
            }

            // Accepted limit of #284 (docs/security.md): the limit of photos per incident counts
            // every photo, also the ones the reporter cannot see.
            @Test
            @DisplayName(
                    "accepted limit: the operator's photo counts towards the limit of photos per"
                            + " incident, although the reporter only lists their own")
            void operatorsPhotoCountsTowardsTheReportersLimitOfPhotos() throws Exception {
                // Act
                ResultActions result =
                        upload(
                                citizen,
                                incident,
                                image(0xFF, 0xD8, 0xFF, 0xE1),
                                "third.jpg",
                                "image/jpeg");

                // Assert
                expectInvalidFile(result);
                assertThat(attachmentRowsOf(incident)).isEqualTo(2);
            }

            @ParameterizedTest(name = "{0}")
            @EnumSource(
                    value = Role.class,
                    names = {"MUNICIPAL_OPERATOR", "ADMINISTRATOR"})
            @DisplayName("staff list both photos, the reporter's and the operator's")
            void staffListEveryPhotoOfTheIncident(Role role) throws Exception {
                // Arrange
                UUID staff = role == Role.ADMINISTRATOR ? administrator : operator;

                // Act
                ResultActions result = mvc.perform(as(staff, get(attachmentsOf(incident))));

                // Assert
                assertThat(listedIds(result))
                        .containsExactly(byReporter.toString(), byOperator.toString());
            }

            @Test
            @DisplayName("an administrator downloads the photo another staff member uploaded")
            void administratorDownloadsThePhotoOfAnotherStaffMember() throws Exception {
                // Act
                ResultActions result =
                        mvc.perform(
                                as(
                                        administrator,
                                        get(API + "/attachments/" + byOperator + "/content")));

                // Assert
                assertThat(
                                result.andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsByteArray())
                        .isEqualTo(PNG);
            }
        }

        @Nested
        @DisplayName("when the assigned technician adds a photo while it is in progress")
        class WhenTheAssignedTechnicianAddsAPhoto {

            private UUID incident;
            private UUID byReporter;
            private UUID byTechnician;

            @BeforeEach
            void reporterUploadsThenTheAssignedTechnicianUploads() throws Exception {
                incident = incident(citizen, "REPORTED");
                byReporter =
                        idOf(upload(citizen, incident, JPEG, "street-light.jpg", "image/jpeg"));
                jdbc.update("UPDATE incident SET status = 'IN_PROGRESS' WHERE id = ?", incident);
                assign(incident, technician, true);
                byTechnician = idOf(upload(technician, incident, PNG, "repair.png", "image/png"));
            }

            @Test
            @DisplayName("the reporter's list holds only the photo they uploaded")
            void reporterListsTheirPhotoButNotTheTechnicians() throws Exception {
                // Act
                ResultActions result = mvc.perform(as(citizen, get(attachmentsOf(incident))));

                // Assert
                assertThat(listedIds(result)).containsExactly(byReporter.toString());
            }

            @Test
            @DisplayName(
                    "the reporter gets for the technician's photo the same 404 as for an unknown"
                            + " id")
            void reporterGetsTheSameNotFoundForTheTechniciansPhotoAsForAnUnknownOne()
                    throws Exception {
                // Arrange
                UUID unknown = UUID.randomUUID();
                Map<String, Object> unknownProblem =
                        problemOf(
                                mvc.perform(
                                        as(
                                                citizen,
                                                get(API + "/attachments/" + unknown + "/content"))),
                                unknown);

                // Act
                ResultActions result =
                        mvc.perform(
                                as(
                                        citizen,
                                        get(API + "/attachments/" + byTechnician + "/content")));

                // Assert
                assertThat(problemOf(result, byTechnician)).isEqualTo(unknownProblem);
            }

            @Test
            @DisplayName("the assigned technician lists both photos")
            void assignedTechnicianListsBothPhotos() throws Exception {
                // Act
                ResultActions result = mvc.perform(as(technician, get(attachmentsOf(incident))));

                // Assert
                assertThat(listedIds(result))
                        .containsExactly(byReporter.toString(), byTechnician.toString());
            }
        }

        @Test
        @DisplayName("the reporter's own photos are listed oldest first, without the staff's")
        void reporterListsTheirOwnPhotosOldestFirst() throws Exception {
            // Arrange: inserted newest first, so only the query's order can put Monday first.
            UUID incident = incident(citizen, "REPORTED");
            UUID tuesday = storedAttachment(incident, citizen, "second.png", "image/png", TUESDAY);
            storedAttachment(incident, operator, "staff.jpg", "image/jpeg", MONDAY);
            UUID monday = storedAttachment(incident, citizen, "first.jpg", "image/jpeg", MONDAY);

            // Act
            ResultActions result = mvc.perform(as(citizen, get(attachmentsOf(incident))));

            // Assert
            assertThat(listedIds(result)).containsExactly(monday.toString(), tuesday.toString());
        }
    }

    private static List<String> listedIds(ResultActions listed) throws Exception {
        String body =
                listed.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    /**
     * The Problem body of a {@code 404} for this attachment, without what changes on every request
     * or with the id asked for: the {@code traceId}, and the id inside {@code detail} and {@code
     * instance}. Two answers that only differ there reveal nothing about which ids exist.
     */
    private static Map<String, Object> problemOf(ResultActions result, UUID attachment)
            throws Exception {
        expectNotFound(result);
        String body =
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replace(attachment.toString(), "{attachmentId}");
        Map<String, Object> problem = new HashMap<>(JsonPath.<Map<String, Object>>read(body, "$"));
        problem.remove("traceId");
        return problem;
    }

    private ResultActions upload(
            UUID caller, UUID incident, byte[] bytes, String fileName, String declaredType)
            throws Exception {
        var file = new MockMultipartFile("file", fileName, declaredType, bytes);
        return mvc.perform(as(caller, multipart(attachmentsOf(incident)).file(file)));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(UUID caller, B request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)));
    }

    private static String attachmentsOf(UUID incident) {
        return API + "/incidents/" + incident + "/attachments";
    }

    private static UUID idOf(ResultActions created) throws Exception {
        String body =
                created.andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static void expectInvalidFile(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'file')]").exists());
    }

    private static void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private void assertNothingStored(UUID incident) {
        assertThat(attachmentRowsOf(incident)).isZero();
        verify(storage, never()).put(any(byte[].class));
    }

    private int attachmentRowsOf(UUID incident) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM attachment WHERE incident_id = ?",
                        Integer.class,
                        incident);
        return count == null ? 0 : count;
    }

    private UUID uploaderOf(UUID attachment) {
        return jdbc.queryForObject(
                "SELECT uploader_id FROM attachment WHERE id = ?", UUID.class, attachment);
    }

    /** A row whose file is in the storage, inserted with SQL to control {@code uploaded_at}. */
    private UUID storedAttachment(
            UUID incident, String fileName, String contentType, Instant uploadedAt) {
        return storedAttachment(incident, citizen, fileName, contentType, uploadedAt);
    }

    private UUID storedAttachment(
            UUID incident, UUID uploader, String fileName, String contentType, Instant uploadedAt) {
        String key = storage.put(JPEG);
        return jdbc.queryForObject(
                "INSERT INTO attachment (incident_id, uploader_id, file_name, file_type, file_size,"
                        + " storage_key, content_hash, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                incident,
                uploader,
                fileName,
                contentType,
                JPEG.length,
                key,
                // A different hash per row: one incident cannot hold the same content twice.
                (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(uploadedAt));
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, '{bcrypt}not-used', ?)"
                        + " RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private UUID incident(UUID reporter, String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at)"
                                + " VALUES (?, 'Broken streetlight', 'The lamp is off.', ?, ?, ?)"
                                + " RETURNING id",
                        UUID.class,
                        reporter,
                        status,
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        return incident;
    }

    private void assign(UUID incident, UUID assignedTechnician, boolean active) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, ?)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(MONDAY),
                active);
    }

    private static byte[] image(int... header) {
        var bytes = new byte[header.length];
        for (int i = 0; i < header.length; i++) {
            bytes[i] = (byte) header[i];
        }
        return withHeader(bytes, "a broken street light".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] withHeader(byte[] header, byte[] rest) {
        var bytes = new byte[header.length + rest.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(rest, 0, bytes, header.length, rest.length);
        return bytes;
    }
}

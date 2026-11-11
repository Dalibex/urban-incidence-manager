package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.shared.error.FieldError;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A comment of the staff on an incident (RF13). In the MVP every comment is internal, so it is
 * never visible to the citizen, and once written it is not changed: the comments are part of the
 * history of the incident.
 */
@Entity
@Table(name = "comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment {

    /** The limit of the contract ({@code CreateCommentRequest}), in characters (code points). */
    public static final int MAX_TEXT_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "visible_to_citizen", nullable = false)
    private Boolean visibleToCitizen;

    private Comment(Incident incident, User author, String text, OffsetDateTime createdAt) {
        this.incident = incident;
        this.author = author;
        this.text = text;
        this.createdAt = createdAt;
        this.visibleToCitizen = false;
    }

    /**
     * An internal comment, which the citizen never sees.
     *
     * @throws InvalidIncidentDataException if the text is missing, blank or longer than {@link
     *     #MAX_TEXT_LENGTH} characters
     */
    public static Comment internal(
            Incident incident, User author, String text, OffsetDateTime createdAt) {
        requireValidText(text);
        return new Comment(incident, author, text, createdAt);
    }

    private static void requireValidText(String text) {
        if (text == null || text.isBlank()) {
            throw invalidText("Write the comment.");
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            throw invalidText("The comment can have at most " + MAX_TEXT_LENGTH + " characters.");
        }
    }

    private static InvalidIncidentDataException invalidText(String message) {
        return new InvalidIncidentDataException(List.of(new FieldError("text", message)));
    }
}

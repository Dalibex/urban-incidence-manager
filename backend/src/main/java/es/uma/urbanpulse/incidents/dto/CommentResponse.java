package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Comment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code CommentResponse}: an internal comment. Only staff read comments, so there is no citizen
 * view, and {@code visibleToCitizen} is not sent: in the MVP it is always {@code false}.
 */
public record CommentResponse(
        UUID id, StatusChangeResponse.Actor author, String text, Instant createdAt) {

    public static CommentResponse of(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                StatusChangeResponse.Actor.of(comment.getAuthor()),
                comment.getText(),
                comment.getCreatedAt().toInstant());
    }
}

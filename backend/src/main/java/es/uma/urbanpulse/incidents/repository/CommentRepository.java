package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Comment;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** One page of the comments of an incident, with their authors in the same query. */
    @EntityGraph(attributePaths = "author")
    Page<Comment> findByIncidentId(UUID incidentId, Pageable page);
}

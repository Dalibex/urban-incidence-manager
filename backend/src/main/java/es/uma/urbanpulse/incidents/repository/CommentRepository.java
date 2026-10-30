package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Comment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, UUID> {}

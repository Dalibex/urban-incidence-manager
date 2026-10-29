package es.uma.urbanpulse.urbancontext.entity;

import es.uma.urbanpulse.incidents.entity.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "urban_context")
@Getter
@Setter
@NoArgsConstructor
public class UrbanContext {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false, unique = true)
    private Incident incident;

    @Enumerated(EnumType.STRING)
    private Weather weather;

    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_status")
    private TrafficStatus trafficStatus;

    private String event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UrbanContextStatus status;

    @Column(name = "captured_at")
    private OffsetDateTime capturedAt;
}

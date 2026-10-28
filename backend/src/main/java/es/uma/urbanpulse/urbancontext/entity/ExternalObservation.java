package es.uma.urbanpulse.urbancontext.entity;

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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "external_observation")
@Getter
@Setter
@NoArgsConstructor
public class ExternalObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "urban_context_id", nullable = false)
    private UrbanContext urbanContext;

    @Column(nullable = false)
    private String source;

    @Column(name = "measurement_type", nullable = false)
    private String measurementType;

    private String value;

    private String unit;

    private String quality;

    @Column(name = "observation_instant")
    private OffsetDateTime observationInstant;

    @Column(name = "ingestion_date", nullable = false)
    private OffsetDateTime ingestionDate;

    @Column(name = "expiration_date")
    private OffsetDateTime expirationDate;

    @Column(nullable = false)
    private String status;
}

package es.uma.urbanpulse.geospatial.entity;

import es.uma.urbanpulse.incidents.entity.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "incident_asset")
@Getter
@Setter
@NoArgsConstructor
public class IncidentAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "urban_asset_id", nullable = false)
    private UrbanAsset urbanAsset;

    @Column(name = "distance_to_incident")
    private Double distanceToIncident;

    @Column(name = "confidence_level")
    private Double confidenceLevel;

    @Column(nullable = false)
    private String status;
}

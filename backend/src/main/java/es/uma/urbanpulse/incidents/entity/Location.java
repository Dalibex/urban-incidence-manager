package es.uma.urbanpulse.incidents.entity;

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
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

@Entity
@Table(name = "location")
@Getter
@Setter
@NoArgsConstructor
public class Location {

    public static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false, unique = true)
    private Incident incident;

    @Column(nullable = false, columnDefinition = "geography(Point,4326)")
    private Point coordinates;

    private Double accuracy;

    /** Set only while {@code addressStatus} is {@code AVAILABLE}. */
    private String address;

    // A new location waits for the address worker (#375): the report never calls the source.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AddressStatus addressStatus = AddressStatus.PENDING;

    /** Where the address comes from; set only with the address. */
    private String addressSource;

    /** The instant the backend stored the address; set only with the address. */
    private OffsetDateTime addressResolvedAt;

    private String neighborhood;

    private String district;
}

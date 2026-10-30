package es.uma.urbanpulse.urbancontext.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "data_source_config")
@Getter
@Setter
@NoArgsConstructor
public class DataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "endpoint_url", nullable = false)
    private String endpointUrl;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;
}

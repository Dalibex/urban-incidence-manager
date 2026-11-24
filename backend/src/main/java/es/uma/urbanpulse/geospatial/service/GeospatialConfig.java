package es.uma.urbanpulse.geospatial.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The configuration of the address enrichment (#375) and the address search (#179). */
@Configuration
@EnableConfigurationProperties({AddressEnrichmentProperties.class, PlaceSearchProperties.class})
class GeospatialConfig {}

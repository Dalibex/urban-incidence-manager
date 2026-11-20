package es.uma.urbanpulse.urbancontext.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The configuration of the weather enrichment (ADR-0018). */
@Configuration
@EnableConfigurationProperties(WeatherEnrichmentProperties.class)
class UrbanContextConfig {}

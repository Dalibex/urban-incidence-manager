package es.uma.urbanpulse.shared.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * {@code @PreAuthorize} checks, apart from {@link SecurityConfig} so they also apply where there is
 * no web server, such as a one-off process. Without them a protected method called there would run
 * unchecked; with them it fails, because nobody is signed in.
 */
@Configuration
@EnableMethodSecurity
class MethodSecurityConfig {}

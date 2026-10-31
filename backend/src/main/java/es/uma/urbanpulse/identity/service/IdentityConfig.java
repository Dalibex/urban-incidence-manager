package es.uma.urbanpulse.identity.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(AccountSecurityProperties.class)
class IdentityConfig {

    /**
     * bcrypt (cost 10) behind a prefix, {@code {bcrypt}$2a$10$...}: a stronger algorithm such as
     * Argon2 can become the default later and old hashes keep working until each user signs in.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

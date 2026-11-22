package es.uma.urbanpulse.shared.security;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;

import es.uma.urbanpulse.identity.entity.Role;
import java.util.Arrays;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * The roles that may call each operation of {@code /api/v1}: the <em>Permission Matrix</em> of
 * {@code docs/security.md}, one rule per operation. The security filters check them before Spring
 * MVC reads anything of the request, so a role that may not call an operation gets {@code 403}
 * before any {@code 400}, {@code 413}, {@code 415} or {@code 422} its request would get (#286).
 *
 * <p>Whether the caller may see the incident ("own", "assigned" in the matrix) depends on the
 * resource, so the services check it, with {@link AuthenticatedUser}.
 *
 * <p>Deny by default: an operation without a rule here is refused to every role, so an endpoint
 * added without its rule is closed, not open. The public operations have their own chain in {@link
 * SecurityConfig} and never reach these rules.
 */
final class ApiAuthorizationRules {

    private static final String API = "/api/v1";

    private static final String[] OPERATORS = names(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR);
    private static final String[] STAFF =
            names(Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR);
    // The roles that report or handle incidents; the services narrow it to the caller's incidents.
    private static final String[] INCIDENT_ROLES =
            names(Role.CITIZEN, Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR);
    private static final String[] REPORT_READERS = names(Role.ANALYST, Role.ADMINISTRATOR);
    private static final String ADMINISTRATOR = Role.ADMINISTRATOR.name();
    private static final String CITIZEN = Role.CITIZEN.name();
    private static final String TECHNICIAN = Role.TECHNICIAN.name();

    private ApiAuthorizationRules() {}

    static void authorize(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        // Sessions and users. /users/me goes before /users/*, which would match it too.
        auth.requestMatchers(DELETE, API + "/sessions/current").authenticated();
        auth.requestMatchers(GET, API + "/users/me").authenticated();
        auth.requestMatchers(GET, API + "/users").hasRole(ADMINISTRATOR);
        auth.requestMatchers(POST, API + "/users").hasRole(ADMINISTRATOR);
        auth.requestMatchers(GET, API + "/users/*").hasRole(ADMINISTRATOR);
        auth.requestMatchers(PATCH, API + "/users/*").hasRole(ADMINISTRATOR);
        auth.requestMatchers(GET, API + "/technicians").hasAnyRole(OPERATORS);
        auth.requestMatchers(GET, API + "/audit-entries").hasRole(ADMINISTRATOR);

        // Catalogues.
        auth.requestMatchers(GET, API + "/categories").authenticated();
        auth.requestMatchers(GET, API + "/departments").hasAnyRole(STAFF);

        // Incidents.
        auth.requestMatchers(GET, API + "/incidents").hasAnyRole(INCIDENT_ROLES);
        auth.requestMatchers(POST, API + "/incidents").hasRole(CITIZEN);
        auth.requestMatchers(GET, API + "/incidents/*").hasAnyRole(INCIDENT_ROLES);
        auth.requestMatchers(PATCH, API + "/incidents/*").hasAnyRole(OPERATORS);
        auth.requestMatchers(GET, API + "/incidents/*/classification-changes").hasAnyRole(STAFF);
        auth.requestMatchers(GET, API + "/incidents/*/status-changes").hasAnyRole(INCIDENT_ROLES);

        // Lifecycle (ADR-0020).
        auth.requestMatchers(POST, API + "/incidents/*/validation").hasAnyRole(OPERATORS);
        auth.requestMatchers(POST, API + "/incidents/*/rejection").hasAnyRole(OPERATORS);
        auth.requestMatchers(POST, API + "/incidents/*/information-requests").hasAnyRole(OPERATORS);
        auth.requestMatchers(POST, API + "/incidents/*/assignments").hasAnyRole(OPERATORS);
        auth.requestMatchers(POST, API + "/incidents/*/acceptance").hasRole(TECHNICIAN);
        auth.requestMatchers(POST, API + "/incidents/*/resolution").hasRole(TECHNICIAN);
        auth.requestMatchers(POST, API + "/incidents/*/closure").hasAnyRole(OPERATORS);
        auth.requestMatchers(POST, API + "/incidents/*/reopening").hasAnyRole(OPERATORS);

        // Evidence (ADR-0013).
        auth.requestMatchers(GET, API + "/incidents/*/attachments").hasAnyRole(INCIDENT_ROLES);
        auth.requestMatchers(POST, API + "/incidents/*/attachments").hasAnyRole(INCIDENT_ROLES);
        auth.requestMatchers(GET, API + "/attachments/*/content").hasAnyRole(INCIDENT_ROLES);

        // Collaboration and context.
        auth.requestMatchers(GET, API + "/incidents/*/comments").hasAnyRole(STAFF);
        auth.requestMatchers(POST, API + "/incidents/*/comments").hasAnyRole(STAFF);
        auth.requestMatchers(GET, API + "/incidents/*/urban-context").hasAnyRole(STAFF);

        // Map, statistics and reports.
        auth.requestMatchers(GET, API + "/incident-locations").hasAnyRole(STAFF);
        auth.requestMatchers(GET, API + "/incident-statistics").hasAnyRole(STAFF);
        auth.requestMatchers(GET, API + "/incident-reports").hasAnyRole(REPORT_READERS);

        auth.requestMatchers(API + "/**").denyAll();
    }

    private static String[] names(Role... roles) {
        return Arrays.stream(roles).map(Role::name).toArray(String[]::new);
    }
}

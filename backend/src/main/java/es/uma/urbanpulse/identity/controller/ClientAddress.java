package es.uma.urbanpulse.identity.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Address of the client for the rate limits. It is the TCP peer, not {@code X-Forwarded-For}: no
 * proxy sits in front of the backend yet (ADR-0017 is only proposed), and trusting that header
 * without one would let any client skip the limit by sending a different address each time. When a
 * proxy is added, Spring is told to trust only that proxy ({@code
 * server.forward-headers-strategy}).
 */
final class ClientAddress {

    private ClientAddress() {}

    static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}

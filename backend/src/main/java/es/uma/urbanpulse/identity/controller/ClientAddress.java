package es.uma.urbanpulse.identity.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Address of the client for the rate limits: {@code getRemoteAddr()}, never a header read here.
 *
 * <p>In development it is the TCP peer, the client itself. In the deployment the peer is nginx
 * (ADR-0017), so {@code SERVER_FORWARD_HEADERS_STRATEGY=native} makes Tomcat replace it with the
 * {@code X-Forwarded-For} that nginx writes, and only for requests from a private address (#200).
 * Reading that header here would also believe it in development, where any client can send it, and
 * a client could skip the limit by sending a different address each time.
 */
final class ClientAddress {

    private ClientAddress() {}

    static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}

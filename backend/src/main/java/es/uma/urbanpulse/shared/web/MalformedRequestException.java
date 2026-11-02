package es.uma.urbanpulse.shared.web;

import org.springframework.http.HttpStatus;

/**
 * A request the API cannot read ({@code 400}, {@code MALFORMED_REQUEST} in {@code docs/api.md}),
 * for a check Spring does not make by itself, such as an unknown {@code sort} property.
 */
public class MalformedRequestException extends ApiProblemException {

    static final String CODE = "MALFORMED_REQUEST";

    public MalformedRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, CODE, detail);
    }
}

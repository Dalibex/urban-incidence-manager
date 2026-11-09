package es.uma.urbanpulse.shared.web;

import org.springframework.http.HttpStatus;

/**
 * The file is over the size limit ({@code 413}, code {@code FILE_TOO_LARGE} of {@code
 * docs/api.md}). The server usually refuses it while reading the request ({@link
 * ApiExceptionHandler}); the service checks it again, so the limit holds whatever reads the
 * request.
 */
public class FileTooLargeException extends ApiProblemException {

    public static final String CODE = "FILE_TOO_LARGE";
    static final String DETAIL = "The file is over the size limit.";

    public FileTooLargeException() {
        super(HttpStatus.CONTENT_TOO_LARGE, CODE, DETAIL);
    }
}

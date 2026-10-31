package es.uma.urbanpulse.identity.service;

import java.util.Locale;

/** Emails are compared and stored in one form, so "Ana@X.com" and "ana@x.com" are one account. */
final class EmailAddresses {

    private EmailAddresses() {}

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

package es.uma.urbanpulse.incidents.entity;

/**
 * Whether the normalized address of a location is there, and why not (#375). A missing address is
 * never shown as current.
 */
public enum AddressStatus {
    /** Saved with the report; the address worker has not resolved it yet. */
    PENDING,
    /** The address, its source and the instant it was stored are set. */
    AVAILABLE,
    /** The position is outside the municipality of Málaga: there is no address to give. */
    OUTSIDE_AREA,
    /**
     * The source has no street at the position, refused the request, or did not answer before the
     * maximum delay.
     */
    UNAVAILABLE
}

package es.uma.urbanpulse.geospatial.service;

import java.util.Objects;

/** What the address source answered for a position (#375). */
public sealed interface AddressLookup {

    /** A street in Málaga, written as the normalized address, such as "Calle Larios, 5". */
    record Found(String address) implements AddressLookup {
        public Found {
            Objects.requireNonNull(address, "address");
            if (address.isBlank()) {
                throw new IllegalArgumentException("address must not be blank");
            }
        }
    }

    /** The position is outside the municipality of Málaga, or the source knows no place there. */
    record OutsideArea() implements AddressLookup {}

    /** The position is in Málaga, but the source has no street there (a park, the port). */
    record NoStreet() implements AddressLookup {}
}

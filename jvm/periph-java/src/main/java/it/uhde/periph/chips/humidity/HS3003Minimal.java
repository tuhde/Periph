package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

/**
 * HS3003 — same protocol as the whole HS30xx family; an alias of {@link HS30xxMinimal}.
 */
public class HS3003Minimal extends HS30xxMinimal {
    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x44
     */
    public HS3003Minimal(Connection connection) {
        super(connection);
    }
}

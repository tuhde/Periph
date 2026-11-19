package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

/**
 * HS4003 — same protocol as the whole HS40xx family; an alias of {@link HS40xxMinimal}.
 */
public class HS4003Minimal extends HS40xxMinimal {
    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x54
     */
    public HS4003Minimal(Connection connection) {
        super(connection);
    }
}

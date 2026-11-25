package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

/**
 * HS3002 — same protocol as the whole HS30xx family; an alias of {@link HS30xxFull}.
 */
public class HS3002Full extends HS30xxFull {
    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x44
     */
    public HS3002Full(Connection connection) {
        super(connection);
    }
}

package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HS4003 — same protocol as the whole HS40xx family; an alias of {@link HS40xxFull}.
 */
@CompileStatic
class HS4003Full extends HS40xxFull {
    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x54
     */
    HS4003Full(Connection connection) {
        super(connection)
    }
}

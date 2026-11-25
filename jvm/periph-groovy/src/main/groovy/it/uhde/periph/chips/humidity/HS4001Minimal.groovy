package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HS4001 — same protocol as the whole HS40xx family; an alias of {@link HS40xxMinimal}.
 */
@CompileStatic
class HS4001Minimal extends HS40xxMinimal {
    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x54
     */
    HS4001Minimal(Connection connection) {
        super(connection)
    }
}

package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Narrow capability some connections provide: reporting how many bytes are
 * currently buffered and ready to read without blocking.
 *
 * <p>{@link UARTConnection} implements this so callers that need to avoid
 * blocking on {@code read()} (e.g. NEO-6 polling a streaming NMEA protocol)
 * can check {@code available() > 0} first. Depending on this interface
 * instead of the concrete {@link UARTConnection} class also makes such
 * callers substitutable with a fake connection for unit testing — same
 * reasoning as {@link DHTxxConn} / {@link ResetExtender}.
 */
public interface AvailableConnection {

    /**
     * @return number of bytes available to read without blocking
     * @throws IOException if the underlying query fails
     */
    int available() throws IOException;
}

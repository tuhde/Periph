package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Narrow capability every DHTxx-family (DHT11, DHT22, ...) single-wire
 * connection provides: a single {@link #read()} returning the raw 5-byte
 * frame.
 *
 * <p>DHTxx's single-wire protocol has no byte-oriented write, so it cannot
 * reuse the shared {@link Connection} interface either — same reasoning as
 * Go's separate {@code HX711Conn}/{@code DHTxxConn} connection interfaces.
 *
 * <p>{@link DHTxxConnection} implements this interface so chip drivers
 * (e.g. {@code Dht11Minimal}, {@code Dht11Full}) can depend on it instead of
 * the concrete FFM-backed class, making them substitutable with a fake
 * connection for unit testing.
 */
public interface DHTxxConn {

    /**
     * Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     * @return 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].
     * @throws IOException on timeout, framing error, or other connection failure.
     */
    byte[] read() throws IOException;
}

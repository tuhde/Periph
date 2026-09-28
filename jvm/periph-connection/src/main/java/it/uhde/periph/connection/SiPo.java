package it.uhde.periph.connection;

import java.io.IOException;

/**
 * A {@link Connection} to a write-only SiPo (serial-in/parallel-out shift
 * register) device, adding the SRCLR/G hardware-clear and output-enable
 * features that plain {@link Connection#read}/{@link Connection#writeRead}
 * can't express (those two remain unsupported for SiPo — it is write-only).
 *
 * <p>Implemented by {@link SiPoConnection}; chip drivers (e.g. TPIC6B595)
 * depend on this interface rather than the concrete class so they can be
 * unit-tested against a mock with no real spidev/GPIO hardware.
 */
public interface SiPo extends Connection {

    /**
     * Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     * <p>The storage register (and therefore the outputs) is unaffected until
     * the next {@link #write}.
     *
     * @throws IllegalStateException if SRCLR was not configured
     * @throws IOException on GPIO error
     */
    void clear() throws IOException;

    /**
     * Drive G LOW ({@code enabled = true}) or HIGH ({@code enabled = false}).
     *
     * @param enabled {@code true} drives G LOW, letting the storage register
     *                drive the outputs. {@code false} drives G HIGH, forcing
     *                every output off without disturbing the storage
     *                register's contents.
     * @throws IllegalStateException if G was not configured
     * @throws IOException on GPIO error
     */
    void setOutputEnable(boolean enabled) throws IOException;
}

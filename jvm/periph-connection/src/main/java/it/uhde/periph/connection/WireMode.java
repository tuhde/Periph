package it.uhde.periph.connection;

/**
 * How a display controller's data pin is wired to the host (see {@link
 * DisplayConnection}).
 */
public enum WireMode {
    /** MOSI to DIN, write only; {@link DisplayConn#readData} is not available. */
    SIMPLEX,
    /**
     * DIN/DOUT shared with MOSI, read through the SPI peripheral's hardware
     * 3-wire mode. Not wired through the JVM spidev layer — selecting it fails
     * at construction; use {@link #HALF_DUPLEX_PIN}.
     */
    HALF_DUPLEX_HW,
    /**
     * MOSI &mdash; resistor &mdash; MISO joined at DIN/DOUT, read through a
     * full-duplex (hardware or software) SPI master.
     */
    HALF_DUPLEX_PIN
}
